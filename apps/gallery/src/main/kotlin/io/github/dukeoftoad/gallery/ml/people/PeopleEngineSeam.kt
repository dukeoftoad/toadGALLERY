package io.github.dukeoftoad.gallery.ml.people

/**
 * One face cluster as the r25 People surfaces see it (Workstream 6, the
 * "People UI & Cluster Management" round).
 *
 * A cluster is a LIVE view over the r24 face-index store's cluster table
 * (the `nativeStoreCluster` pass's output), never a copy: [faceCount] and
 * [coverFileId] describe the table RIGHT NOW, and the next re-clustering
 * pass (every draining background-queue run, or a threshold change) may
 * renumber, split or merge clusters out from under any cached value.
 *
 * [coverFileId] is the MediaStore `_id` of one JPEG in the cluster (the
 * external-primary volume the r23/r24 pipeline indexes; the thumbnail URI
 * is `ContentUris.withAppendedId(MediaStore.Images.Media
 * .EXTERNAL_CONTENT_URI, id)`), or null when the engine could not pick a
 * cover --- the UI renders an honest glyph avatar there, never a fake
 * thumbnail.
 *
 * ## Cluster-id instability (pinned caveat)
 * [clusterId] is the store's own row identity for the CURRENT clustering
 * pass. It is NOT stable across re-clustering passes: a new pass assigns
 * fresh ids, so anything the app persists keyed by cluster id (the r25
 * people-state.json names and hidden sets) can go STALE after a recluster.
 * The state store's load path prunes entries whose ids no longer resolve
 * against the live clusters ([PeopleState.prunedTo]) --- the documented,
 * tested mitigation; losing a name after a recluster is the honest cost
 * of ids that were never promises.
 *
 * Faces/cluster data NEVER leaves the device (ADR-0010 on-device-only AI;
 * ADR-0020 models.ente.com delivery): the seam reads the app-private
 * `face-index` store, and there is no network surface of any kind.
 */
data class FaceCluster(
    val clusterId: String,
    val faceCount: Int,
    val coverFileId: Long?,
)

/**
 * The r25 People domain's ONLY view of the ML engine (Workstream 6).
 *
 * Everything the People UI needs from the face-index store, as four small
 * synchronous reads/mutations over the LIVE cluster table:
 *  - [faceClusters]: the live clusters, face-count descending;
 *  - [clusterFileIds]: the MediaStore `_id`s whose faces sit in one
 *    cluster (a photo with several faces in the same cluster MAY repeat
 *    its id; consumers dedupe);
 *  - [mergeFaceClusters]: fold [sourceClusterId]'s faces into
 *    [targetClusterId] and drop the source;
 *  - [removeFacesFromCluster]: drop the given faces (by their photos'
 *    file ids) from a cluster --- the "this is not them" correction.
 *
 * [engineAvailable] is the build-shape truth (is `libtoad_ml_jni.so` in
 * this APK at all): false means every store method is unreachable and the
 * UI shows the honest EngineMissing state, never a fake empty one.
 *
 * The production binding is [PeopleEngineAdapter] (a thin wrapper over
 * `io.github.dukeoftoad.gallery.ml.engine.MlEngine`, the r25 cluster-read
 * and cluster-mutation surface Agent A lands this round); tests bind
 * fakes. UI/domain code NEVER imports MlEngine directly --- this seam is
 * the whole contract, so the People logic stays JVM-testable (ADR-0016).
 */
interface PeopleEngineSeam {

    /** Whether the engine library is present in this build (per-process fixed). */
    val engineAvailable: Boolean

    /** The live clusters, face-count descending; empty when none exist. */
    fun faceClusters(): List<FaceCluster>

    /**
     * The MediaStore `_id`s of the photos whose faces sit in [clusterId]
     * (possibly with duplicates when one photo contributed several faces).
     * Throws when the cluster id no longer resolves --- callers treat that
     * as "the person left between refreshes" (honest Missing state), not
     * an app crash.
     */
    fun clusterFileIds(clusterId: String): List<Long>

    /**
     * Merges [sourceClusterId] into [targetClusterId] (the source cluster
     * disappears; its faces belong to the target afterwards). Returns the
     * engine's own success flag; false means nothing changed.
     */
    fun mergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean

    /**
     * Removes the faces belonging to [fileIds] from [clusterId] (the
     * photos themselves are untouched --- only the cluster membership
     * goes). Returns the engine's own success flag.
     */
    fun removeFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean
}
