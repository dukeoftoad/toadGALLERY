package io.github.dukeoftoad.gallery.ml.people

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The r25 People user state: the NAMES the user gave clusters and the
 * clusters they HID, keyed by [FaceCluster.clusterId] (Workstream 6).
 *
 * Both live in ONE small JSON file (`people-state.json`) inside the SAME
 * app-private face-index directory the ML store (`ml-index.sqlite`) and
 * the r24 queue's bookkeeping (`index-state.json`) share --- so the
 * Settings "Reset face index" that recursively deletes the directory also
 * erases names and hidden sets together with the rows they describe (the
 * same never-outlive-your-data rule `FaceIndexStateFile` pins).
 *
 * ## Cluster-id instability (the honest caveat)
 * Cluster ids are NOT stable across re-clustering passes (see
 * [FaceCluster]): every draining background-queue run may hand out fresh
 * ids, at which point persisted names/hidden are stale by construction.
 * [prunedTo] drops entries whose ids no longer resolve against the live
 * clusters; [PeopleRepository] runs it on every load and persists the
 * pruned state back, so the file never accumulates dead ids. A name lost
 * to a recluster is the honest cost of ids that were never promises ---
 * keyed by face CONTENT would survive, but the store does not expose that
 * and guessing it would be dishonest.
 *
 * The mutation helpers ([renamed], [withHidden], [prunedTo]) are PURE
 * over immutable copies and JVM-tested; [PeopleStateFile] is the
 * production persistence (atomic temp-then-move, forgiving parse ---
 * `FaceIndexStateFile`'s exact write discipline).
 */
data class PeopleState(
    val names: Map<String, String> = emptyMap(),
    val hidden: Set<String> = emptySet(),
) {
    companion object {
        /** The empty state: no names, nothing hidden. */
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
        return PeopleState(names - clusterId, hidden)
    }
    if (names[clusterId] == name) return this
    return PeopleState(names + (clusterId to name), hidden)
}

/**
 * Returns [this] with [clusterId]'s hidden membership set to [hidden].
 * Hiding a cluster removes it from every People surface (the state stays,
 * so the tested removal primitive exists for a future unhide surface;
 * r25 ships no unhide UI --- "Reset face index" in Settings is the
 * documented way back, and it erases the whole index by design).
 */
fun PeopleState.withHidden(clusterId: String, hidden: Boolean): PeopleState {
    val current = clusterId in this.hidden
    if (current == hidden) return this
    return PeopleState(names, if (hidden) this.hidden + clusterId else this.hidden - clusterId)
}

/**
 * Returns [this] with every entry whose cluster id is NOT in
 * [liveClusterIds] dropped --- the load-time prune of ids a re-clustering
 * pass made stale (see [PeopleState]). A state that survives unchanged
 * returns `this` itself, so callers can detect a no-op prune with `===`.
 */
fun PeopleState.prunedTo(liveClusterIds: Set<String>): PeopleState {
    if (names.all { it.key in liveClusterIds } && hidden.all { it in liveClusterIds }) return this
    return PeopleState(
        names = names.filterKeys { it in liveClusterIds },
        hidden = hidden.filterTo(LinkedHashSet()) { it in liveClusterIds },
    )
}

/**
 * The production [PeopleStateStore]: one versioned JSON file
 * (`people-state.json`) in the face-index directory.
 *
 * Write path mirrors `FaceIndexStateFile` exactly: serialize to
 * `<file>.tmp`, then an atomic NIO move over the previous file (same
 * directory = same filesystem = atomic on POSIX; a plain-filesystem
 * fallback keeps exotic mount layouts honest). A crash mid-write can
 * therefore never destroy the previous contents.
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
            PeopleStateLoad(PeopleState(names, hidden), recoveredFromCorruptFile = false)
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
        const val SUPPORTED_VERSION = 1
    }
}

/** The people state file's name, inside the face-index directory (r25). */
const val PEOPLE_STATE_FILE_NAME = "people-state.json"
