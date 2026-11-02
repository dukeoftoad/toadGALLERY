package io.github.dukeoftoad.gallery.ml.people

/**
 * The r25 People repository (Workstream 6): the ONE object the People
 * ViewModels talk to. It composes the two r25 truths --- the LIVE cluster
 * table through [PeopleEngineSeam] and the user's names/hidden through
 * [PeopleStateStore] --- plus the MediaStore photo lookup behind
 * [queryPhotos], and owns NO CACHE: every call reads the current truth
 * again, which is exactly the live-table semantics the engine pins
 * (clusters change under the app: background queue runs, merges, removes,
 * re-clustering passes). A ViewModel "refresh" is therefore nothing but
 * another repository call.
 *
 * All functions are `suspend` and expect the CALLER to hop off the main
 * thread (the ViewModels wrap them in `withContext(Dispatchers.IO)`,
 * the house pattern); the engine seam calls are plain blocking reads.
 *
 * Faces/cluster data NEVER leaves the device (ADR-0010; ADR-0020 for the
 * model delivery that produced the index): this repository reads the
 * app-private store and MediaStore, and nothing else.
 *
 * ## The prune-on-load contract
 * [snapshot] prunes the persisted names/hidden to the LIVE cluster ids
 * ([PeopleState.prunedTo] --- cluster ids are not stable across
 * re-clustering passes) and, when the prune actually dropped something,
 * persists the pruned state back so the file never accumulates dead ids.
 * Merges and hides therefore clean up after themselves on the NEXT
 * snapshot, without the repository having to know which ids a merge just
 * orphaned.
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

    /**
     * One full read of the People truth: the live clusters, the (pruned)
     * names and hidden sets, and the honest corrupt-file note from the
     * state store. Prunes stale ids out of the persisted state and saves
     * the pruned copy back when the prune dropped anything.
     *
     * Throws whatever the engine seam throws (an unreadable store, an
     * uninitialized session) --- callers map that to the policy's Error
     * state with the engine's own message; this repository never
     * paraphrases a failure away.
     */
    suspend fun snapshot(): PeopleSnapshot {
        val clusters = engine.faceClusters()
        val load = stateStore.load()
        val pruned = load.state.prunedTo(clusters.mapTo(HashSet()) { it.clusterId })
        if (pruned !== load.state) {
            stateStore.save(pruned)
        }
        return PeopleSnapshot(
            clusters = clusters,
            names = pruned.names,
            hidden = pruned.hidden,
            recoveredFromCorruptFile = load.recoveredFromCorruptFile,
        )
    }

    /**
     * Sets/updates/removes [clusterId]'s name (blank input removes the
     * entry --- see [PeopleState.renamed]); persists immediately.
     */
    suspend fun rename(clusterId: String, name: String) {
        stateStore.save(stateStore.load().state.renamed(clusterId, name))
    }

    /**
     * Adds [clusterId] to the hidden set; persists immediately. The
     * cluster stays in the store (only the People surfaces filter it);
     * r25 ships no unhide UI --- "Reset face index" in Settings is the
     * documented way back (see [PeopleState.withHidden]).
     */
    suspend fun hide(clusterId: String) {
        stateStore.save(stateStore.load().state.withHidden(clusterId, true))
    }

    /**
     * Folds [sourceClusterId] into [targetClusterId] through the engine
     * seam (source order, target order --- the direction the Person
     * detail's "Merge with…" dialog encodes). Returns the engine's own
     * success flag; the orphaned source name/hidden entries are pruned
     * by the NEXT [snapshot] (the load-time prune), keeping this call a
     * thin delegation.
     *
     * HONEST CAVEAT (the engine's own): a manual merge edits the LIVE
     * assignments table; the next re-clustering pass recomputes
     * assignments from the embeddings and may split or re-merge the
     * result. Cluster correction is advisory, not permanent --- the
     * same instability class as the cluster ids themselves.
     */
    suspend fun merge(sourceClusterId: String, targetClusterId: String): Boolean =
        engine.mergeFaceClusters(sourceClusterId, targetClusterId)

    /**
     * Removes the faces of [fileIds] from [clusterId] through the engine
     * seam (the "not this person" correction; the photos themselves are
     * untouched, and the face ROWS stay --- only the assignment goes, so
     * a later re-clustering pass may re-derive it). Returns the engine's
     * own success flag.
     */
    suspend fun removePhotos(clusterId: String, fileIds: LongArray): Boolean =
        engine.removeFacesFromCluster(clusterId, fileIds)

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
