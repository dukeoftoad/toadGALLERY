package com.toadgallery.gallery.ml.people

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One recorded user merge intent (r26, the edit-preserving reclustering
 * overlay): the cluster the user merged INTO ([targetClusterId] --- the
 * person's identity holder, the one that carries the name) and the
 * MERGED membership as the engine saw it right after the merge
 * ([memberFileIds] --- the union of both sides' photo file ids).
 *
 * The engine's own merge is NOT sticky: the queue's next clustering pass
 * recomputes every assignment from the embeddings, so the two sides may
 * come back as separate clusters. This record is the r26 answer: at the
 * next reconciled snapshot the overlay finds every live cluster whose
 * faces majority-descend from [memberFileIds] and folds them back into
 * ONE person (see [PeopleReclusterRemap]) --- the user's merge survives
 * the recluster instead of being silently undone.
 */
data class PeopleMerge(
    val targetClusterId: String,
    val memberFileIds: Set<Long>,
)

/**
 * The r25 People user state, extended by the r26 edit-preserving
 * reclustering overlay: the NAMES the user gave clusters ([names]), the
 * clusters they HID ([hidden]) --- plus the three overlay books that
 * make those edits survive the queue's re-clustering passes.
 *
 * All of it lives in ONE small JSON file (`people-state.json`, schema
 * version 1, grown ADDITIVELY in r26 --- see [PeopleStateFile]) inside
 * the SAME app-private face-index directory the ML store
 * (`ml-index.sqlite`) and the r24 queue's bookkeeping
 * (`index-state.json`) share --- so the Settings "Reset face index"
 * that recursively deletes the directory also erases names, hidden sets
 * and overlay books together with the rows they describe (the same
 * never-outlive-your-data rule `FaceIndexStateFile` pins).
 *
 * ## The r26 overlay books
 *  - [memberships] --- a membership FINGERPRINT per edited cluster: the
 *    set of photo file ids that sat in the cluster when the user last
 *    edited it. File ids (MediaStore `_id`s) are stable across
 *    re-clustering passes --- unlike cluster ids, which every pass
 *    re-rolls --- so the fingerprint is the only stable identity a user
 *    edit can be re-keyed through. Captured at edit time (rename /
 *    hide / unhide / remove-from-person / merge), never on the read
 *    path, and dropped again by [canonicalized] once a cluster carries
 *    no edit at all;
 *  - [merges] --- one [PeopleMerge] per merge target, the intent that
 *    re-folds a recluster-split person back into one cluster;
 *  - [removals] --- per cluster, the file ids the user removed from
 *    that person ("not this person"). The re-clustering pass re-derives
 *    assignments from the embeddings and may put those faces RIGHT
 *    BACK; the overlay re-applies the removal to the person's
 *    reclustered continuation.
 *
 * ## Cluster-id instability (the honest caveat, r26-scoped)
 * Cluster ids are NOT stable across re-clustering passes (see
 * [FaceCluster]): every pass assigns fresh random ids, orphaning every
 * id-keyed entry. r25's answer was the blind [prunedTo] load-time
 * prune; r26's is the [PeopleReclusterRemap] overlay --- remap through
 * the membership fingerprint when one exists, drop honestly when it
 * does not (edits made before the r26 upgrade carry no fingerprint, so
 * they still prune until the user edits that person again --- the
 * documented upgrade path, not a silent loss).
 *
 * The mutation helpers ([renamed], [withHidden], [withMembership],
 * [mergeRecorded], [withRemoval], [withoutClusterEdits],
 * [canonicalized], [prunedTo]) are PURE over immutable copies and
 * JVM-tested; [PeopleStateFile] is the production persistence (atomic
 * temp-then-move, forgiving parse --- `FaceIndexStateFile`'s exact
 * write discipline).
 */
data class PeopleState(
    val names: Map<String, String> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    val memberships: Map<String, Set<Long>> = emptyMap(),
    val merges: List<PeopleMerge> = emptyList(),
    val removals: Map<String, Set<Long>> = emptyMap(),
) {
    companion object {
        /** The empty state: no names, nothing hidden, no overlay books. */
        val EMPTY = PeopleState()
    }
}

/**
 * One [PeopleStateStore.load] result: the state plus the honest note of
 * whether the file existed but could not be parsed ([recoveredFromCorruptFile]
 * --- the user's names were RESET, and the UI must say so instead of
 * silently pretending nothing was lost).
 */
data class PeopleStateLoad(
    val state: PeopleState,
    val recoveredFromCorruptFile: Boolean,
)

/** The persistence seam [PeopleRepository] reads and writes people state through. */
interface PeopleStateStore {

    /** The persisted state (empty + [PeopleStateLoad.recoveredFromCorruptFile] when corrupt). */
    suspend fun load(): PeopleStateLoad

    /** Persists [state] as the WHOLE people state (an atomic full rewrite). */
    suspend fun save(state: PeopleState)
}

/**
 * Returns [this] with [clusterId]'s name set to [rawName] (trimmed).
 * A BLANK name REMOVES the entry --- "Unnamed" is the honest default the
 * UI derives from absence, so an empty string would only ever be a second
 * spelling of the same state (pinned by tests).
 */
fun PeopleState.renamed(clusterId: String, rawName: String): PeopleState {
    val name = rawName.trim()
    if (name.isEmpty()) {
        if (clusterId !in names) return this
        return copy(names = names - clusterId)
    }
    if (names[clusterId] == name) return this
    return copy(names = names + (clusterId to name))
}

/**
 * Returns [this] with [clusterId]'s hidden membership set to [hidden].
 * Hiding a cluster removes it from every People surface while the state
 * stays; the r26 People screen's hidden section is the way back
 * ([PeopleRepository.unhide] --- no index reset, names and hidden sets
 * intact).
 */
fun PeopleState.withHidden(clusterId: String, hidden: Boolean): PeopleState {
    val current = clusterId in this.hidden
    if (current == hidden) return this
    return copy(hidden = if (hidden) this.hidden + clusterId else this.hidden - clusterId)
}

/**
 * Returns [this] with [clusterId]'s membership fingerprint set to
 * [fileIds] (the r26 overlay's stable identity for the cluster's user
 * edits --- see [PeopleState.memberships]). A no-op when the fingerprint
 * already equals [fileIds] (same instance back).
 */
fun PeopleState.withMembership(clusterId: String, fileIds: Set<Long>): PeopleState {
    if (memberships[clusterId] == fileIds) return this
    return copy(memberships = memberships + (clusterId to fileIds))
}

/**
 * Returns [this] with the files of one remove-from-person correction
 * ACCUMULATED onto [clusterId]'s removal record (r26): the re-clustering
 * pass re-derives assignments from the embeddings and may assign those
 * faces right back, so the intent is recorded per person and re-applied
 * to the person's reclustered continuation by [PeopleReclusterRemap].
 */
fun PeopleState.withRemoval(clusterId: String, fileIds: Set<Long>): PeopleState {
    if (fileIds.isEmpty()) return this
    val accumulated = (removals[clusterId] ?: emptySet()) + fileIds
    if (removals[clusterId] == accumulated) return this
    return copy(removals = removals + (clusterId to accumulated))
}

/**
 * Returns [this] with EVERY user edit keyed to [clusterId] dropped
 * (name, hidden flag, membership fingerprint, removal record and any
 * merge record targeting it) --- the post-merge cleanup for a cluster
 * the engine has folded away. A no-op (same instance) when nothing is
 * keyed to the id.
 */
fun PeopleState.withoutClusterEdits(clusterId: String): PeopleState {
    val mergeKept = merges.filterNot { it.targetClusterId == clusterId }
    if (clusterId !in names && clusterId !in hidden &&
        clusterId !in memberships && clusterId !in removals &&
        mergeKept.size == merges.size
    ) {
        return this
    }
    return copy(
        names = names - clusterId,
        hidden = hidden - clusterId,
        memberships = memberships - clusterId,
        removals = removals - clusterId,
        merges = mergeKept,
    )
}

/**
 * Records one user merge (r26): [sourceClusterId]'s edits fold away (the
 * engine already folded its faces into [targetClusterId]), the target
 * gains the merge intent [PeopleMerge] over the POST-MERGE membership
 * [mergedFileIds] (the union of both sides, read after the engine
 * merge), and the source's accumulated removals follow the person into
 * the target (a "not this person" correction stays one no matter which
 * side it was made on). An existing merge record for the same target is
 * REPLACED --- the newest merged set subsumes the older one.
 */
fun PeopleState.mergeRecorded(
    sourceClusterId: String,
    targetClusterId: String,
    mergedFileIds: Set<Long>,
): PeopleState {
    val sourceRemovals = removals[sourceClusterId] ?: emptySet()
    val targetRemovals = (removals[targetClusterId] ?: emptySet()) + sourceRemovals
    val mergesKept = merges.filterNot { it.targetClusterId == targetClusterId } + PeopleMerge(targetClusterId, mergedFileIds)
    return copy(
        names = names - sourceClusterId,
        hidden = hidden - sourceClusterId,
        memberships = (memberships - sourceClusterId) + (targetClusterId to mergedFileIds),
        merges = mergesKept,
        removals = (removals - sourceClusterId - targetClusterId) +
            (if (targetRemovals.isEmpty()) emptyMap() else mapOf(targetClusterId to targetRemovals)),
    )
}

/**
 * Whether [clusterId] carries ANY user edit besides a membership
 * fingerprint (a name, a hidden flag, a removal record or a merge
 * target) --- i.e. whether the fingerprint is worth keeping. The
 * membership map is deliberately NOT consulted: a fingerprint alone
 * preserves nothing.
 */
fun PeopleState.carriesEdits(clusterId: String): Boolean =
    clusterId in names || clusterId in hidden ||
        clusterId in removals || merges.any { it.targetClusterId == clusterId }

/**
 * The ids of every cluster this state keys a USER EDIT to (names,
 * hidden, removals, merge targets --- membership fingerprints alone do
 * not count; they are the shadow of an edit, not one). The r26
 * reconcile path treats every one of these that no longer resolves as a
 * re-clustering casualty to remap.
 */
val PeopleState.editedClusterIds: Set<String>
    get() = buildSet {
        addAll(names.keys)
        addAll(hidden)
        addAll(removals.keys)
        merges.forEach { add(it.targetClusterId) }
    }

/**
 * Returns [this] with the overlay books trimmed to what still means
 * something (r26): membership fingerprints for clusters with no edit
 * left, EMPTY fingerprints, empty removal records, and merge records
 * over an empty membership all go --- the state file never accumulates
 * dead weight. A state that is already canonical returns `this` itself
 * (the repository's save-back signal, exactly like [prunedTo]).
 */
fun PeopleState.canonicalized(): PeopleState {
    val edited = editedClusterIds
    val membershipsKept = memberships.filterKeys { it in edited }.filterValues { it.isNotEmpty() }
    // A removal record only survives when its person still has an edit
    // book BEYOND the record itself (a name, a hidden flag, a surviving
    // fingerprint, or a merge record): a removal alone is not a
    // person-shaped thing worth carrying (pinned by the canonicalized test).
    val removalsKept = removals
        .filterValues { it.isNotEmpty() }
        .filterKeys { it in names.keys || it in hidden || it in membershipsKept || merges.any { m -> m.targetClusterId == it } }
    val mergesKept = merges.filter { it.memberFileIds.isNotEmpty() }
    if (membershipsKept.size == memberships.size && removalsKept.size == removals.size &&
        mergesKept.size == merges.size
    ) {
        return this
    }
    return copy(
        memberships = membershipsKept,
        removals = removalsKept,
        merges = mergesKept,
    )
}

/**
 * Returns [this] with every entry whose cluster id is NOT in
 * [liveClusterIds] dropped --- the r25 load-time prune of ids a
 * re-clustering pass made stale. r26 SUPERSEDES this on the repository
 * read path with the [PeopleReclusterRemap] overlay (fingerprint remap
 * first, honest drop only when no match exists); the helper stays the
 * documented no-fingerprint fallback and prunes every id-keyed book. A
 * state that survives unchanged returns `this` itself, so callers can
 * detect a no-op prune with `===`.
 */
fun PeopleState.prunedTo(liveClusterIds: Set<String>): PeopleState {
    val namesKept = names.filterKeys { it in liveClusterIds }
    val hiddenKept = hidden.filterTo(LinkedHashSet()) { it in liveClusterIds }
    val membershipsKept = memberships.filterKeys { it in liveClusterIds }
    val removalsKept = removals.filterKeys { it in liveClusterIds }
    val mergesKept = merges.filter { it.targetClusterId in liveClusterIds }
    if (namesKept.size == names.size && hiddenKept.size == hidden.size &&
        membershipsKept.size == memberships.size && removalsKept.size == removals.size &&
        mergesKept.size == merges.size
    ) {
        return this
    }
    return PeopleState(
        names = namesKept,
        hidden = hiddenKept,
        memberships = membershipsKept,
        removals = removalsKept,
        merges = mergesKept,
    )
}

/**
 * The production [PeopleStateStore]: one JSON file
 * (`people-state.json`) in the face-index directory.
 *
 * Write path mirrors `FaceIndexStateFile` exactly: serialize to
 * `<file>.tmp`, then an atomic NIO move over the previous file (same
 * directory = same filesystem = atomic on POSIX; a plain-filesystem
 * fallback keeps exotic mount layouts honest). A crash mid-write can
 * therefore never destroy the previous contents.
 *
 * ## Schema version 1, grown additively (r26)
 * The r26 overlay books ([PeopleState.memberships], [PeopleState.merges],
 * [PeopleState.removals]) ride the SAME schema version 1 as the r25
 * names/hidden shape: the new keys are strictly OPTIONAL on both sides,
 * so an r25 file loads with the books empty (an upgrade loses nothing)
 * and an r26 file read by an r25 build degrades to names/hidden only
 * (org.json ignores unknown keys) --- no version bump, no migration
 * cliff. A `version` other than 1 remains "not ours to interpret"
 * (empty + the corrupt note), exactly as r25 pinned.
 *
 * Read path is FORGIVING but HONEST: a missing file is simply the empty
 * state (nothing was ever saved); a file that EXISTS but cannot be
 * parsed (corrupt, partially written, hand-edited, or a future schema
 * version) also degrades to empty --- but flagged with
 * [PeopleStateLoad.recoveredFromCorruptFile], because this file holds
 * USER-AUTHORED data (names they typed), and losing it silently would be
 * exactly the masked-failure shape the honest-states doctrine forbids.
 * The UI surfaces the note; recovery is "start over naming".
 *
 * The save runs on [Dispatchers.IO] through [withContext] (the file is
 * tiny, but it is still disk); the parse is pure `org.json` over plain
 * values, JVM-tested in `PeopleStateStoreTest`.
 */
class PeopleStateFile(
    private val file: File,
) : PeopleStateStore {

    override suspend fun load(): PeopleStateLoad = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext PeopleStateLoad(PeopleState.EMPTY, recoveredFromCorruptFile = false)
        try {
            val root = JSONObject(file.readText())
            if (root.optInt(VERSION_KEY) != SUPPORTED_VERSION) {
                return@withContext PeopleStateLoad(PeopleState.EMPTY, recoveredFromCorruptFile = true)
            }
            val names = HashMap<String, String>()
            val namesObj = root.optJSONObject(NAMES_KEY)
            if (namesObj != null) {
                for (key in namesObj.keys()) {
                    val value = namesObj.optString(key)
                    if (key.isNotEmpty() && value.isNotBlank()) names[key] = value
                }
            }
            val hidden = LinkedHashSet<String>()
            val hiddenArr = root.optJSONArray(HIDDEN_KEY)
            if (hiddenArr != null) {
                for (i in 0 until hiddenArr.length()) {
                    val id = hiddenArr.optString(i)
                    if (id.isNotEmpty()) hidden.add(id)
                }
            }
            // The r26 overlay books --- absent in r25 files, optional ever
            // since (see the class KDoc's additive-schema note):
            val memberships = HashMap<String, Set<Long>>()
            val membershipsObj = root.optJSONObject(MEMBERSHIPS_KEY)
            if (membershipsObj != null) {
                for (key in membershipsObj.keys()) {
                    if (key.isEmpty()) continue
                    val fileIds = readLongSet(membershipsObj.optJSONArray(key)) ?: continue
                    if (fileIds.isNotEmpty()) memberships[key] = fileIds
                }
            }
            val removals = HashMap<String, Set<Long>>()
            val removalsObj = root.optJSONObject(REMOVALS_KEY)
            if (removalsObj != null) {
                for (key in removalsObj.keys()) {
                    if (key.isEmpty()) continue
                    val fileIds = readLongSet(removalsObj.optJSONArray(key)) ?: continue
                    if (fileIds.isNotEmpty()) removals[key] = fileIds
                }
            }
            val merges = ArrayList<PeopleMerge>()
            val mergesArr = root.optJSONArray(MERGES_KEY)
            if (mergesArr != null) {
                for (i in 0 until mergesArr.length()) {
                    val entry = mergesArr.optJSONObject(i) ?: continue
                    val target = entry.optString(MERGE_TARGET_KEY)
                    if (target.isEmpty()) continue
                    val fileIds = readLongSet(entry.optJSONArray(MERGE_FILES_KEY)) ?: continue
                    if (fileIds.isNotEmpty()) merges.add(PeopleMerge(target, fileIds))
                }
            }
            PeopleStateLoad(
                PeopleState(names, hidden, memberships, merges, removals),
                recoveredFromCorruptFile = false,
            )
        } catch (_: Exception) {
            // Corrupt / partially-written / hand-edited: the honest state is
            // "reset" plus the flag that says the user's names were lost ---
            // never a thrown parse error into the People pipeline.
            PeopleStateLoad(PeopleState.EMPTY, recoveredFromCorruptFile = true)
        }
    }

    override suspend fun save(state: PeopleState) {
        withContext(Dispatchers.IO) {
            val names = JSONObject()
            state.names.forEach { (id, name) -> names.put(id, name) }
            val hidden = JSONArray()
            state.hidden.forEach { hidden.put(it) }
            val root = JSONObject()
                .put(VERSION_KEY, SUPPORTED_VERSION)
                .put(NAMES_KEY, names)
                .put(HIDDEN_KEY, hidden)
            // The r26 overlay books --- written only when non-empty, so an
            // edit-free state stays byte-shape the r25 one (additive schema).
            if (state.memberships.isNotEmpty()) {
                val memberships = JSONObject()
                state.memberships.forEach { (id, fileIds) ->
                    memberships.put(id, JSONArray().apply { fileIds.forEach { put(it) } })
                }
                root.put(MEMBERSHIPS_KEY, memberships)
            }
            if (state.merges.isNotEmpty()) {
                val merges = JSONArray()
                state.merges.forEach { merge ->
                    merges.put(
                        JSONObject()
                            .put(MERGE_TARGET_KEY, merge.targetClusterId)
                            .put(MERGE_FILES_KEY, JSONArray().apply { merge.memberFileIds.forEach { put(it) } }),
                    )
                }
                root.put(MERGES_KEY, merges)
            }
            if (state.removals.isNotEmpty()) {
                val removals = JSONObject()
                state.removals.forEach { (id, fileIds) ->
                    removals.put(id, JSONArray().apply { fileIds.forEach { put(it) } })
                }
                root.put(REMOVALS_KEY, removals)
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Layouts without atomic rename support still get the durable
                // temp-write + replace ordering; only the atomicity window widens.
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    private companion object {
        const val VERSION_KEY = "version"
        const val NAMES_KEY = "names"
        const val HIDDEN_KEY = "hidden"

        // The r26 overlay books (optional keys within schema version 1).
        const val MEMBERSHIPS_KEY = "memberships"
        const val MERGES_KEY = "merges"
        const val REMOVALS_KEY = "removals"
        const val MERGE_TARGET_KEY = "target"
        const val MERGE_FILES_KEY = "files"

        const val SUPPORTED_VERSION = 1

        /**
         * Reads a JSON array of file ids into a set; null when the array is
         * absent or not an array (the forgiving-parse rule: a bad book entry
         * degrades to "not present", never a thrown parse error --- but a
         * PRESENT-but-garbage whole file still recovers via the outer catch).
         */
        fun readLongSet(array: JSONArray?): Set<Long>? {
            if (array == null) return null
            val out = LinkedHashSet<Long>(array.length())
            for (i in 0 until array.length()) {
                val value = array.optLong(i, Long.MIN_VALUE)
                if (value != Long.MIN_VALUE) out.add(value)
            }
            return out
        }
    }
}

/** The people state file's name, inside the face-index directory (r25). */
const val PEOPLE_STATE_FILE_NAME = "people-state.json"
