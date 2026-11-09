package com.toadgallery.gallery.ml.index

import com.toadgallery.gallery.ml.engine.MlEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The r23 face-index coordinator: turns caller-supplied photo JPEGs into
 * clustered face rows in the app-private index store, and nothing else.
 *
 * Pipeline per photo (in caller order): `detectFaceEmbeddings(jpeg)` ---
 * the r23 JNI embeddings surface (boxes + 192-float MobileFaceNet
 * embeddings) --- then one `putFace(fileId, embedding)` per detected face
 * into the store. After all photos: `clusterFaces(threshold)` over
 * everything the store holds, using the vendored upstream clustering
 * ([RECOMMENDED_DISTANCE_THRESHOLD] = upstream `face_clustering`'s
 * `RECOMMENDED_DISTANCE_THRESHOLD`, 0.24, quoted from the vendored
 * `ente-photos-ml/src/face_clustering/mod.rs`).
 *
 * Design rules this class pins:
 *  - NO MediaStore, NO decode path, NO UI concerns here: the caller hands
 *    over `List<Pair<Long, ByteArray>>` (MediaStore `_id` + JPEG bytes) so
 *    the coordinator is pure JVM-testable logic over the [FaceIndexEngine]
 *    seam;
 *  - the engine seam is [FaceIndexEngine], NOT the JNI object --- the
 *    facade ([MlEngine]) is the production binding (see
 *    [MlEngineIndexEngine]); tests bind fakes;
 *  - coroutines-friendly: the whole run sits on [ioDispatcher]
 *    (Dispatchers.IO in production, the engine calls are blocking), and
 *    cancellation is honored BETWEEN photos (never mid-face) so the store
 *    always holds whole photos;
 *  - progress is reported after every photo via [onProgress].
 */
class IndexCoordinator(
    private val engine: FaceIndexEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * The engine surface the coordinator drives, as an injectable seam.
     * Production: [MlEngineIndexEngine] over the [MlEngine] facade (whose
     * JNI contract and state machine carry the r23 rules --- store ops
     * before `openFaceIndex` throw, embeddings are stride-197 flat
     * floats, etc.). The JVM suite binds fakes.
     *
     * r24 adds the store maintenance pair the background queue needs:
     * [indexedFaceFileIds] (the resumability read) and
     * [deleteFacesForFiles] (the per-file invalidation delete).
     */
    interface FaceIndexEngine {
        /** Face boxes + 192-float embeddings for one JPEG's bytes. */
        fun detectFaceEmbeddings(jpeg: ByteArray): List<MlEngine.FaceEmbedding>

        /** Inserts one face row; returns the store's face row id (> 0). */
        fun putFace(fileId: Long, embedding: FloatArray): Long

        /** Total face rows currently in the store. */
        fun faceCount(): Long

        /** Clusters the store's faces; returns the parsed summary. */
        fun clusterFaces(distanceThreshold: Double): MlEngine.ClusterSummary

        /** File IDs that currently carry current-model-version face rows (r24). */
        fun indexedFaceFileIds(): List<Long>

        /** Deletes every face row for [fileIds]; the invalidation primitive (r24). */
        fun deleteFacesForFiles(fileIds: LongArray): Boolean
    }

    /**
     * Live per-photo progress: [photosProcessed] photos of
     * [totalPhotos] done, [facesIndexed] face rows written so far.
     */
    data class IndexProgress(
        val photosProcessed: Int,
        val totalPhotos: Int,
        val facesIndexed: Int,
    )

    /**
     * The completed run's summary: [photosProcessed] photos fed in,
     * [facesIndexed] face rows written by THIS run, [totalFacesInStore]
     * the store's total after the run, and the [clusterSummary] the
     * clustering pass produced.
     */
    data class IndexSummary(
        val photosProcessed: Int,
        val facesIndexed: Int,
        val totalFacesInStore: Long,
        val clusterSummary: MlEngine.ClusterSummary,
    )

    /**
     * Indexes every photo, then clusters the store.
     *
     * Cancellation: checked between photos (and once more before the
     * clustering pass) --- a cancelled run leaves whole photos in the
     * store and never runs a half pass. [onProgress] runs on the
     * coordinator's context after each photo.
     *
     * Engine failures (IllegalStateException/IllegalArgumentException per
     * the facade's contract) propagate to the caller unchanged; the
     * coordinator never swallows or paraphrases them.
     */
    suspend fun indexAndCluster(
        photos: List<Pair<Long, ByteArray>>,
        distanceThreshold: Double = RECOMMENDED_DISTANCE_THRESHOLD,
        onProgress: suspend (IndexProgress) -> Unit = {},
    ): IndexSummary = withContext(ioDispatcher) {
        var facesIndexed = 0
        for ((index, photo) in photos.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (fileId, jpeg) = photo
            for (face in engine.detectFaceEmbeddings(jpeg)) {
                engine.putFace(fileId, face.embedding)
                facesIndexed++
            }
            onProgress(IndexProgress(index + 1, photos.size, facesIndexed))
        }
        currentCoroutineContext().ensureActive()
        val clusterSummary = engine.clusterFaces(distanceThreshold)
        IndexSummary(
            photosProcessed = photos.size,
            facesIndexed = facesIndexed,
            totalFacesInStore = engine.faceCount(),
            clusterSummary = clusterSummary,
        )
    }

    companion object {
        /**
         * Upstream's recommended clustering distance: the vendored
         * `ente-photos-ml` `face_clustering` module's
         * `RECOMMENDED_DISTANCE_THRESHOLD` constant (0.24; see
         * `engine/crates/ente-photos-ml/src/face_clustering/mod.rs` at
         * the vendored pin). The Settings r23 dogfood passes exactly
         * this; callers may override for experiments.
         */
        const val RECOMMENDED_DISTANCE_THRESHOLD = 0.24
    }
}

/**
 * Production [IndexCoordinator.FaceIndexEngine]: a thin delegation to the
 * [MlEngine] facade (which owns the JNI bridge, its state machine and its
 * synchronization). Constructed with the SAME [MlEngine] instance the
 * rest of the surface uses.
 */
class MlEngineIndexEngine(private val engine: MlEngine = MlEngine()) :
    IndexCoordinator.FaceIndexEngine {

    override fun detectFaceEmbeddings(jpeg: ByteArray): List<MlEngine.FaceEmbedding> =
        engine.detectFaceEmbeddings(jpeg)

    override fun putFace(fileId: Long, embedding: FloatArray): Long =
        engine.putFace(fileId, embedding)

    override fun faceCount(): Long = engine.faceCount()

    override fun clusterFaces(distanceThreshold: Double): MlEngine.ClusterSummary =
        engine.clusterFaces(distanceThreshold)

    override fun indexedFaceFileIds(): List<Long> = engine.indexedFaceFileIds()

    override fun deleteFacesForFiles(fileIds: LongArray): Boolean =
        engine.deleteFacesForFiles(fileIds)
}
