package com.toadgallery.gallery.ml.people

import java.util.TreeSet

/**
 * The r26 edit-preserving reclustering overlay (the People follow-ups
 * round): the PURE planner that keeps user edits alive across the face
 * index's re-clustering passes.
 *
 * ## The problem (r25's honest caveat, now fixed)
 * Every clustering pass (each draining background-queue run, each
 * threshold change, each model re-provision) recomputes EVERY face's
 * assignment from the embedding and hands out FRESH RANDOM cluster ids
 * (upstream `new_cluster_id` --- see the r25 `FaceCluster` KDoc). User
 * edits are keyed to cluster ids, so a pass orphans them all: r25's
 * answer was the blind load-time prune ([PeopleState.prunedTo]) ---
 * honest, but it turned every recluster into "lose your names".
 *
 * ## The stable identity: photo membership
 * What a pass CANNOT re-roll is WHICH PHOTOS a person's faces came
 * from: face rows are keyed by their file id (`"{file_id}_{ordinal}"`,
 * stable since the r23 put path), and a cluster is just the set of
 * files its faces belong to. [PeopleState.memberships] records that
 * set at edit time --- the fingerprint the overlay remaps through.
 *
 * ## What the planner does (all pure, over one snapshot's inputs)
 * Given the persisted [PeopleState] and the LIVE clusters with their
 * memberships, when a recluster orphaned an edited id:
 *  1. REMAP: every stale edited cluster maps onto the live cluster
 *     sharing the most photo files with its fingerprint (ties: bigger
 *     cluster, then smaller id --- deterministic). Stale ids without a
 *     fingerprint (pre-r26 edits) or without any shared file drop
 *     honestly --- the r25 rule, now the fallback instead of the
 *     whole answer;
 *  2. RE-FOLD MERGES: for every recorded [PeopleMerge], the live
 *     clusters whose faces MAJORITY-descend from the merged membership
 *     (at least half of the live cluster's files were part of the
 *     merged person) fold back into the best-overlap one --- the
 *     engine re-merge pairs land in [Plan.merges];
 *  3. RE-APPLY REMOVALS: every recorded removal whose files the
 *     recluster put BACK into the person's continuation is re-removed
 *     ([Plan.removals]);
 *  4. RE-KEY: names, hidden flags, removal records and merge records
 *     move onto the new ids (two names colliding on one new id resolve
 *     by overlap score, then smaller old id; a live id's own name
 *     always wins over a remapped one --- strongest continuity);
 *  5. REFRESH: the new state's fingerprints are the PREDICTED
 *     post-mutation memberships (set arithmetic over the same inputs
 *     --- the engine merge unions, the engine removal subtracts), so
 *     the persisted state matches the store the mutations produce.
 *
 * The engine mutations themselves are the CALLER's ([PeopleRepository]
 * executes [Plan.merges] first, then [Plan.removals]; on any declined
 * mutation it keeps the OLD state untouched --- the next snapshot
 * replans from the then-current truth, so a failed reconcile is
 * self-healing, never corrupting).
 *
 * Pure Kotlin over plain values (ADR-0016), pinned by
 * `PeopleReclusterRemapTest` and the reconcile tests in
 * `PeopleRepositoryTest`.
 */
object PeopleReclusterRemap {

    /**
     * One reconcile plan: the RE-KEYED state to persist (with refreshed
     * fingerprints), the engine mutations that must run for that state
     * to be TRUE, and the honest bookkeeping of what the remap did.
     *
     * [state] is canonical ([PeopleState.canonicalized]) and its ids
     * are the POST-mutation ones (merge homes, not folded sources).
     * [merges] are (source, target) pairs in the repository merge
     * direction; [removals] are (clusterId, fileIds) pairs covering
     * only the files that actually came BACK (a removal whose files
     * stayed out is already true). [remappedIds] is the composite
     * old-id-to-final-id mapping (through the best match AND the merge
     * folds); [droppedIds] are the stale edited ids with no surviving
     * continuation --- the honest losses.
     */
    data class Plan(
        val state: PeopleState,
        val merges: List<Pair<String, String>>,
        val removals: List<Pair<String, LongArray>>,
        val remappedIds: Map<String, String>,
        val droppedIds: Set<String>,
    ) {
        /** Whether the plan needs engine mutations to become true. */
        val hasEngineWork: Boolean get() = merges.isNotEmpty() || removals.isNotEmpty()
    }

    /**
     * True when any user edit keys to a cluster id that no longer
     * resolves against [liveClusterIds] --- the cheap pre-check that
     * tells the repository a recluster orphaned something BEFORE it
     * pays for the per-cluster membership reads. (Every clustering pass
     * re-rolls ALL ids, so in practice a pass makes every edited id
     * stale at once; the check is per-id so a partially-stale state ---
     * mid-window edits, a declined reconcile --- is handled the same.)
     */
    fun needsReconcile(state: PeopleState, liveClusterIds: Set<String>): Boolean =
        state.editedClusterIds.any { it !in liveClusterIds }

    /**
     * Plans the reconcile of [state] against the live truth. See the
     * object KDoc for the five steps. A state whose edited ids all
     * resolve is already in sync: the plan is a no-op carrying the
     * canonicalized state (same instance when already canonical) and
     * no engine work.
     *
     * @param liveClusters the seam's live clusters (any order; the
     * planner never re-sorts them for output --- it only picks).
     * @param liveMemberships each live cluster's current file-id set
     * (the caller reads them through [PeopleEngineSeam.clusterFileIds]
     * only when [needsReconcile] said a recluster orphaned edits).
     */
    fun plan(
        state: PeopleState,
        liveClusters: List<FaceCluster>,
        liveMemberships: Map<String, Set<Long>>,
    ): Plan {
        val liveIds = HashSet<String>(liveClusters.size)
        liveClusters.forEach { liveIds.add(it.clusterId) }
        val staleEdited = state.editedClusterIds.filterTo(LinkedHashSet()) { it !in liveIds }
        if (staleEdited.isEmpty()) {
            return Plan(state.canonicalized(), emptyList(), emptyList(), emptyMap(), emptySet())
        }

        // Step 1: fingerprint best-match for every stale edited id.
        val mapping = HashMap<String, String>()
        val scores = HashMap<String, Int>()
        val dropped = TreeSet<String>()
        for (oldId in staleEdited) {
            val recorded = state.memberships[oldId]
            if (recorded.isNullOrEmpty()) {
                // Pre-r26 edit (no fingerprint) or a fingerprint that
                // captured nothing: the r25 honest loss.
                dropped.add(oldId)
                continue
            }
            val best = liveClusters
                .filter { cluster -> overlap(liveMemberships[cluster.clusterId], recorded) > 0 }
                .maxWithOrNull(matchComparator(recorded, liveMemberships))
            if (best == null) {
                dropped.add(oldId)
            } else {
                mapping[oldId] = best.clusterId
                scores[oldId] = overlap(liveMemberships[best.clusterId], recorded)
            }
        }

        // Step 2: merge intents re-folded over the live table. `fold`
        // maps a live cluster to its DIRECT home per the newest record
        // that claimed it; `resolve` follows chains to the final home.
        val fold = HashMap<String, String>()
        for (record in state.merges) {
            val descendants = liveClusters.filter { cluster ->
                val live = liveMemberships[cluster.clusterId].orEmpty()
                val shared = overlap(live, record.memberFileIds)
                shared > 0 && shared * 2 >= live.size
            }
            if (descendants.isEmpty()) continue // the record dies with its person
            val home = descendants.maxWith(matchComparator(record.memberFileIds, liveMemberships))
            for (descendant in descendants) {
                fold[descendant.clusterId] = home.clusterId
            }
        }

        fun resolve(liveId: String): String {
            var current = liveId
            var hops = 0
            while (true) {
                val next = fold[current] ?: return current
                if (next == current) return current
                current = next
                // A cyclic hand-edit of two merge records cannot loop the
                // planner: give up at the fold map's own size and land on
                // the last id seen (an honest best effort, never a hang).
                if (++hops > fold.size) return current
            }
        }

        // The engine re-merge pairs: every folded cluster folds DIRECTLY
        // into its final home (independent, order-free engine calls);
        // sorted by source id for deterministic execution order.
        val reMerges = fold.keys
            .filter { resolve(it) != it }
            .sorted()
            .map { it to resolve(it) }

        // Step 5's input: predicted post-fold memberships (each folded
        // source's files move wholesale into its final home).
        val predicted = HashMap<String, MutableSet<Long>>(liveMemberships.size)
        liveMemberships.forEach { (id, files) -> predicted[id] = LinkedHashSet(files) }
        for (source in fold.keys) {
            val home = resolve(source)
            if (home == source) continue
            val moved = predicted.remove(source) ?: continue
            predicted.getOrPut(home) { LinkedHashSet() }.addAll(moved)
        }

        // The composite mapping every re-key goes through: live ids fold,
        // stale ids best-match then fold.
        fun composite(oldId: String): String? =
            if (oldId in liveIds) {
                resolve(oldId)
            } else {
                mapping[oldId]?.let { resolve(it) }
            }

        // Step 4a: names re-keyed, collisions resolved (live ids keep
        // their own name --- the strongest continuity signal; remapped
        // names compete by overlap score, then by the smaller old id ---
        // deterministic over user data, never a coin flip).
        val newNames = LinkedHashMap<String, String>()
        val nameScores = HashMap<String, Long>()
        val nameOwners = HashMap<String, String>()
        for ((oldId, name) in state.names) {
            val target = composite(oldId) ?: continue // dropped below
            val score = if (oldId in liveIds) Long.MAX_VALUE else (scores[oldId] ?: 0).toLong()
            val incumbentScore = nameScores[target]
            if (incumbentScore == null ||
                score > incumbentScore ||
                (score == incumbentScore && oldId < nameOwners[target].orEmpty())
            ) {
                nameScores[target] = score
                nameOwners[target] = oldId
                newNames[target] = name
            }
        }

        // Step 4b: hidden flags re-keyed (set semantics, no collisions).
        val newHidden = LinkedHashSet<String>()
        for (oldId in state.hidden) {
            composite(oldId)?.let { newHidden.add(it) }
        }

        // Step 4c + 3: removal records re-keyed and accumulated, then the
        // files the recluster put BACK into the person's continuation
        // become the engine re-removals.
        val newRemovals = LinkedHashMap<String, Set<Long>>()
        for ((oldId, removedFiles) in state.removals) {
            val target = composite(oldId) ?: continue
            newRemovals[target] = (newRemovals[target] ?: emptySet()) + removedFiles
        }
        val reRemovals = ArrayList<Pair<String, LongArray>>()
        for (target in newRemovals.keys.sorted()) {
            val again = predicted[target].orEmpty() intersect newRemovals[target].orEmpty()
            if (again.isNotEmpty()) {
                reRemovals.add(target to again.toSortedSet().toLongArray())
            }
        }

        // Step 4d: merge records re-keyed onto their composite target with
        // the predicted post-fold, post-removal membership (an existing
        // record for the same target is replaced --- the newest merged set
        // subsumes the older one).
        val newMerges = LinkedHashMap<String, PeopleMerge>()
        for (record in state.merges) {
            val target = composite(record.targetClusterId) ?: continue
            val files = (predicted[target].orEmpty() - newRemovals[target].orEmpty())
            if (files.isEmpty()) continue
            newMerges[target] = PeopleMerge(target, files)
        }

        // Step 5: refreshed fingerprints --- the predicted post-mutation
        // memberships minus what the removals take back out, for every id
        // the new state still keys an edit to.
        val newState = PeopleState(
            names = newNames,
            hidden = newHidden,
            memberships = emptyMap(),
            merges = newMerges.values.toList(),
            removals = newRemovals,
        )
        val withMemberships = newState.editedClusterIds.fold(newState) { acc, id ->
            val membership = predicted[id].orEmpty() - newRemovals[id].orEmpty()
            acc.withMembership(id, membership)
        }

        // The composite mapping exposed to callers (the repository re-keys
        // a mid-window edit's target id through it).
        val remapped = LinkedHashMap<String, String>()
        for (oldId in staleEdited) {
            composite(oldId)?.let { remapped[oldId] = it }
        }
        return Plan(withMemberships.canonicalized(), reMerges, reRemovals, remapped, dropped)
    }

    // ---- the pure helpers ----

    /** |[mine] ∩ [theirs]| --- 0 when either side is absent/empty. */
    private fun overlap(mine: Set<Long>?, theirs: Set<Long>): Int {
        if (mine.isNullOrEmpty()) return 0
        var shared = 0
        for (file in mine) {
            if (file in theirs) shared++
        }
        return shared
    }

    /**
     * The best-continuation order over live clusters against a recorded
     * membership: MORE shared files wins; ties go to the BIGGER cluster
     * (the recluster's main line), then to the SMALLER cluster id ---
     * fully deterministic, never a coin flip over user data.
     */
    private fun matchComparator(
        recorded: Set<Long>,
        liveMemberships: Map<String, Set<Long>>,
    ): Comparator<FaceCluster> =
        // ASCENDING tiers so that maxWith picks the documented winner
        // (a DESCENDING order makes maxWith pick the WORST match): the
        // most shared files, then the bigger cluster, then the smaller id
        // (thenByDescending on the id --- the SMALLER id ranks greater).
        compareBy<FaceCluster> { overlap(liveMemberships[it.clusterId], recorded) }
            .thenBy { it.faceCount }
            .thenByDescending { it.clusterId }
}
