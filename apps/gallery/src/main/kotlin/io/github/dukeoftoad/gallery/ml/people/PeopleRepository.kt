package io.github.dukeoftoad.gallery.ml.people

/**
 * The r25 People repository (Workstream 6), grown by the r26 follow-ups
 * round: the ONE object the People ViewModels talk to. It composes the
 * live cluster table through [PeopleEngineSeam] with the user's
 * names/hidden/merge/removal intents through [PeopleStateStore], plus
 * the MediaStore photo lookup behind [queryPhotos], and owns NO CACHE:
 * every call reads the current truth again, which is exactly the
 * live-table semantics the engine pins (clusters change under the app:
 * background queue runs, merges, removes, re-clustering passes). A
 * ViewModel "refresh" is therefore nothing but another repository call.
 *
 * ## The r26 edit-preserving reconcile (the overlay's execution half)
 * [PeopleState]'s entries are keyed to cluster ids, which every
 * re-clustering pass re-rolls (fresh random ids --- see
 * [PeopleReclusterRemap]). Every read AND every edit therefore runs
 * through [reconcileIfStale] first: when a persisted edit keys to an id
 * that no longer resolves, the repository reads every live cluster's
 * membership, plans the remap through the PURE
 * [PeopleReclusterRemap.plan], EXECUTES the plan's engine mutations
 * (re-folded merges first, then re-applied removals), persists the
 * re-keyed state, and hands back the post-mutation truth. Names,
 * hidden flags, merges and remove-from-person corrections survive the
 * queue's recluster passes instead of being pruned away.
 *
 * Honest failure shape: when the ENGINE declines a plan mutation
 * (false, not a throw), the reconcile aborts WITHOUT saving --- the old
 * state stays intact and the next read replans from the then-current
 * live table (self-healing, never corrupting). A thrown failure
 * propagates unchanged: the ViewModels map it to the policy's Error
 * state with the failure's own message, never paraphrased away.
 *
 * Mid-window edits (a recluster landing between the UI's last snapshot
 * and the edit's tap) are re-keyed through the plan's composite mapping
 * when the person carries a fingerprint; an edit against an id with NO
 * recorded membership (a person never edited before) cannot be matched
 * and keeps the r25 honest-loss behavior --- documented, not hidden.
 *
 * All functions are `suspend` and expect the CALLER to hop off the main
 * thread (the ViewModels wrap them in `withContext(Dispatchers.IO)`,
 * the house pattern); the engine seam calls are plain blocking reads.
 *
 * Faces/cluster data NEVER leaves the device (ADR-0010; ADR-0020 for
 * the model delivery that produced the index): this repository reads
 * the app-private store and MediaStore, and nothing else.
 *
 * Pure-JVM over injected seams (ADR-0016): `PeopleRepositoryTest` binds
 * a fake engine + a fake state store; the production wiring (the
 * [PeopleEngineAdapter] over MlEngine, [PeopleStateFile], and the
 * MediaStore [queryPhotos] binding from `PeopleMediaQuery.kt`) is
 * Android-adjacent and untested per the house rule.
 *
 * @param queryPhotos maps a cluster's MediaStore file ids to its photos
 * (newest first); the default returns nothing, which keeps this class
 * constructible in pure JVM contexts that never ask for photos.
 */
class PeopleRepository(
    private val engine: PeopleEngineSeam,
    private val stateStore: PeopleStateStore,
    private val queryPhotos: suspend (fileIds: List<Long>) -> List<PersonPhoto> = { emptyList() },
) {

    /** The build-shape truth: is the engine library in this APK at all. */
    val engineAvailable: Boolean
        get() = engine.engineAvailable

    // ---- the r26 reconcile core ----

    /**
     * One reconcile attempt's outcome: the state to work from (the
     * re-keyed plan state on success, the UNTOUCHED loaded state when
     * the engine declined a mutation), the clusters the snapshot should
     * report (re-read after mutations, so counts reflect the folds),
     * the composite old-id-to-final-id mapping the edit paths re-key
     * their target through, and the state store's honest corrupt-file
     * note (carried once instead of a second load). [changed] is true
     * when a plan was applied (the caller persists [state] if it does
     * not save its own edit on top first).
     */
    private class ReconcileResult(
        val state: PeopleState,
        val clusters: List<FaceCluster>,
        val mapping: Map<String, String>,
        val changed: Boolean,
        val recoveredFromCorruptFile: Boolean,
    )

    /**
     * The shared read-and-edit pre-step: load the state, and when a
     * recluster orphaned any user edit, run the [PeopleReclusterRemap]
     * plan (mutations + re-keyed state + re-read clusters). See the
     * class KDoc for the failure shapes.
     */
    private suspend fun reconcileIfStale(): ReconcileResult {
        val load = stateStore.load()
        val state = load.state
        val clusters = engine.faceClusters()
        val liveIds = clusters.mapTo(HashSet()) { it.clusterId }
        if (!PeopleReclusterRemap.needsReconcile(state, liveIds)) {
            val canonical = state.canonicalized()
            if (canonical !== state) stateStore.save(canonical)
            return ReconcileResult(canonical, clusters, emptyMap(), changed = false, load.recoveredFromCorruptFile)
        }

        // A recluster orphaned edits: read every live cluster's membership
        // (the only stable identity --- cluster ids were re-rolled) and
        // plan over it.
        val liveMemberships = HashMap<String, Set<Long>>(clusters.size)
        for (cluster in clusters) {
            liveMemberships[cluster.clusterId] = engine.clusterFileIds(cluster.clusterId).toSet()
        }
        val plan = PeopleReclusterRemap.plan(state, clusters, liveMemberships)
        if (plan.hasEngineWork) {
            // Folds first (each folded source's faces move wholesale into
            // its final home), then the removals that undo the recluster's
            // re-added faces. Any declined mutation aborts the whole save.
            for ((source, target) in plan.merges) {
                if (!engine.mergeFaceClusters(source, target)) {
                    return ReconcileResult(state, clusters, emptyMap(), changed = false, load.recoveredFromCorruptFile)
                }
            }
            for ((clusterId, fileIds) in plan.removals) {
                if (!engine.removeFacesFromCluster(clusterId, fileIds)) {
                    return ReconcileResult(state, clusters, emptyMap(), changed = false, load.recoveredFromCorruptFile)
                }
            }
            stateStore.save(plan.state)
            return ReconcileResult(
                plan.state,
                engine.faceClusters(),
                plan.remappedIds,
                changed = true,
                load.recoveredFromCorruptFile,
            )
        }
        stateStore.save(plan.state)
        return ReconcileResult(plan.state, clusters, plan.remappedIds, changed = true, load.recoveredFromCorruptFile)
    }

    /** Re-keys an edit target through a reconcile's composite mapping. */
    private fun ReconcileResult.rekey(clusterId: String): String =
        mapping[clusterId] ?: clusterId

    /**
     * The edit paths' membership capture: the CURRENT live membership of
     * [clusterId] as the fingerprint its edits remap through --- read
     * AFTER the edit's own engine mutation, so the fingerprint is the
     * post-edit truth. An empty read (the cluster does not resolve)
     * captures nothing: an empty fingerprint preserves nothing.
     */
    private fun membershipOf(clusterId: String): Set<Long> =
        engine.clusterFileIds(clusterId).toSet()

    // ---- the reads ----

    /**
     * One full read of the People truth: the live clusters, the
     * (reconciled) names and hidden sets, and the honest corrupt-file
     * note from the state store. Reconciles FIRST when a recluster
     * orphaned edits (see the class KDoc), so what comes back is the
     * post-mutation truth with user edits re-keyed onto it.
     *
     * Throws whatever the engine seam throws (an unreadable store, an
     * uninitialized session) --- callers map that to the policy's Error
     * state with the engine's own message; this repository never
     * paraphrases a failure away.
     */
    suspend fun snapshot(): PeopleSnapshot {
        val reconciled = reconcileIfStale()
        return PeopleSnapshot(
            clusters = reconciled.clusters,
            names = reconciled.state.names,
            hidden = reconciled.state.hidden,
            recoveredFromCorruptFile = reconciled.recoveredFromCorruptFile,
        )
    }

    // ---- the edits (each: reconcile first, capture the fingerprint, save once) ----

    /**
     * Sets/updates/removes [clusterId]'s name (blank input removes the
     * entry --- see [PeopleState.renamed]); persists immediately with
     * the cluster's membership fingerprint, so the name survives the
     * next recluster through the overlay.
     */
    suspend fun rename(clusterId: String, name: String) {
        val reconciled = reconcileIfStale()
        val target = reconciled.rekey(clusterId)
        var state = reconciled.state.renamed(target, name)
        if (state.carriesEdits(target)) {
            membershipOf(target).takeIf { it.isNotEmpty() }?.let { live ->
                state = state.withMembership(target, live)
            }
        }
        stateStore.save(state.canonicalized())
    }

    /**
     * Adds [clusterId] to the hidden set; persists immediately with the
     * membership fingerprint (the hidden flag must survive reclusters
     * too). The cluster stays in the store --- only the People surfaces
     * filter it, and the r26 People screen's hidden section is the way
     * back ([unhide] --- no index reset).
     */
    suspend fun hide(clusterId: String) {
        val reconciled = reconcileIfStale()
        val target = reconciled.rekey(clusterId)
        var state = reconciled.state.withHidden(target, hidden = true)
        if (state.carriesEdits(target)) {
            membershipOf(target).takeIf { it.isNotEmpty() }?.let { live ->
                state = state.withMembership(target, live)
            }
        }
        stateStore.save(state.canonicalized())
    }

    /**
     * Restores a hidden person (r26): removes [clusterId] from the
     * hidden set, nothing else --- NO queue reset, no engine mutation,
     * names and every other edit intact. The counterpart of [hide].
     */
    suspend fun unhide(clusterId: String) {
        val reconciled = reconcileIfStale()
        val target = reconciled.rekey(clusterId)
        var state = reconciled.state.withHidden(target, hidden = false)
        if (state.carriesEdits(target)) {
            membershipOf(target).takeIf { it.isNotEmpty() }?.let { live ->
                state = state.withMembership(target, live)
            }
        }
        stateStore.save(state.canonicalized())
    }

    /**
     * Folds [sourceClusterId] into [targetClusterId] through the engine
     * seam (source order, target order --- the direction the Person
     * detail's "Merge with…" dialog encodes). Returns the engine's own
     * success flag. On success the merge INTENT is recorded (r26): the
     * post-merge membership becomes the target's fingerprint and its
     * [PeopleMerge] record, so when the next recluster splits the person
     * again the overlay folds the pieces back into ONE cluster; the
     * source's own edits (name, hidden, accumulated removals) fold away
     * with it --- the target's identity wins, exactly the r25 rule.
     */
    suspend fun merge(sourceClusterId: String, targetClusterId: String): Boolean {
        val reconciled = reconcileIfStale()
        val source = reconciled.rekey(sourceClusterId)
        val target = reconciled.rekey(targetClusterId)
        val ok = engine.mergeFaceClusters(source, target)
        if (!ok) return false
        val mergedMembership = membershipOf(target)
        val merged = if (mergedMembership.isNotEmpty()) {
            reconciled.state.mergeRecorded(source, target, mergedMembership)
        } else {
            // An empty post-merge read (the engine folded nothing): the
            // source's edits still fold away --- the person is gone.
            reconciled.state.withoutClusterEdits(source)
        }
        stateStore.save(merged.canonicalized())
        return true
    }

    /**
     * Removes the faces of [fileIds] from [clusterId] through the engine
     * seam (the "not this person" correction; the photos themselves are
     * untouched, and the face ROWS stay --- only the assignment goes, so
     * a later re-clustering pass may re-derive it --- which is exactly
     * why the r26 overlay records the removal and re-applies it to the
     * person's reclustered continuation). Returns the engine's own
     * success flag.
     */
    suspend fun removePhotos(clusterId: String, fileIds: LongArray): Boolean {
        val reconciled = reconcileIfStale()
        val target = reconciled.rekey(clusterId)
        val ok = engine.removeFacesFromCluster(target, fileIds)
        if (!ok) return false
        var state = reconciled.state.withRemoval(target, fileIds.toSet())
        if (state.carriesEdits(target)) {
            membershipOf(target).takeIf { it.isNotEmpty() }?.let { live ->
                state = state.withMembership(target, live)
            }
        }
        stateStore.save(state.canonicalized())
        return true
    }

    /**
     * The cluster's photos: [PeopleEngineSeam.clusterFileIds] (deduped ---
     * one photo can contribute several faces) mapped through [queryPhotos]
     * into light [PersonPhoto]s, newest first. Empty when the cluster has
     * no resolvable MediaStore rows (deleted/trashed/vault-moved photos
     * --- their face rows exist, their thumbnails do not).
     */
    suspend fun photosOf(clusterId: String): List<PersonPhoto> =
        queryPhotos(engine.clusterFileIds(clusterId).distinct())
}

/**
 * One [PeopleRepository.snapshot]: everything the People ViewModels need
 * to feed [PeopleContentPolicy.decide], plus the corrupt-file note the
 * People screen surfaces honestly.
 */
data class PeopleSnapshot(
    val clusters: List<FaceCluster>,
    val names: Map<String, String>,
    val hidden: Set<String>,
    val recoveredFromCorruptFile: Boolean,
)
