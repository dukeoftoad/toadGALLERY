package io.github.dukeoftoad.gallery.ml.index

import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for the r23 [IndexCoordinator] over a FAKE
 * [IndexCoordinator.FaceIndexEngine] --- never the JNI object, never the
 * facade's native path. The suite pins the coordinator's own contract:
 * one `putFace` per detected face with the caller's file id, per-photo
 * progress in order, the 0.24 upstream threshold passed through,
 * cancellation honored BETWEEN photos, and the cluster summary assembled
 * from the engine's answer untouched.
 */
class IndexCoordinatorTest {

    /** Scripted engine: deterministic faces per photo, records everything. */
    private class FakeEngine : IndexCoordinator.FaceIndexEngine {
        /** Faces returned per detectFaceEmbeddings call, consumed in order. */
        val facesPerPhoto = ArrayDeque(listOf(2, 1, 0))

        val detectCalls = mutableListOf<ByteArray>()
        val putCalls = mutableListOf<Pair<Long, FloatArray>>()
        var clusterCalls = 0
        var lastThreshold: Double? = null
        var clusterResult = MlEngine.ClusterSummary(faces = 3, clusters = 2, sizes = listOf(2, 1))
        var faceCountResult = 3L
        var detectFail: RuntimeException? = null

        // ---- r24 maintenance surface (unused by indexAndCluster; the
        // queue processor's tests script these) ----
        var indexedFileIdsResult: List<Long> = emptyList()
        val deleteCalls = mutableListOf<LongArray>()

        override fun indexedFaceFileIds(): List<Long> = indexedFileIdsResult

        override fun deleteFacesForFiles(fileIds: LongArray): Boolean {
            deleteCalls.add(fileIds)
            return true
        }

        private fun embedding(seed: Float) = FloatArray(192) { it.toFloat() + seed }

        private fun face(seed: Float) = MlEngine.FaceEmbedding(
            x1 = seed,
            y1 = 1f,
            x2 = 2f,
            y2 = 3f,
            score = 0.5f + seed,
            embedding = embedding(seed),
        )

        override fun detectFaceEmbeddings(jpeg: ByteArray): List<MlEngine.FaceEmbedding> {
            detectFail?.let { throw it }
            detectCalls.add(jpeg)
            val count = if (facesPerPhoto.isEmpty()) 0 else facesPerPhoto.removeFirst()
            return (0 until count).map { face(it.toFloat()) }
        }

        override fun putFace(fileId: Long, embedding: FloatArray): Long {
            putCalls.add(fileId to embedding)
            return putCalls.size.toLong()
        }

        override fun faceCount(): Long = faceCountResult

        override fun clusterFaces(distanceThreshold: Double): MlEngine.ClusterSummary {
            clusterCalls++
            lastThreshold = distanceThreshold
            return clusterResult
        }
    }

    private fun photos(n: Int): List<Pair<Long, ByteArray>> =
        (1..n).map { id -> id.toLong() * 10 to byteArrayOf(id.toByte(), 0x1F, 0x2E) }

    @Test
    fun `one putFace per detected face and the summary assembles`() = runBlocking {
        val engine = FakeEngine() // photo1: 2 faces, photo2: 1 face, photo3: 0 faces
        val coordinator = IndexCoordinator(engine, Dispatchers.Unconfined)

        val progress = mutableListOf<IndexCoordinator.IndexProgress>()
        val summary = coordinator.indexAndCluster(photos(3)) { progress.add(it) }

        // One put per face, keyed by the CALLER's file id, embedding passed
        // through verbatim:
        assertEquals(3, engine.putCalls.size)
        assertEquals(10L, engine.putCalls[0].first)
        assertEquals(10L, engine.putCalls[1].first)
        assertEquals(20L, engine.putCalls[2].first)
        assertEquals(192, engine.putCalls[0].second.size)
        assertEquals(0f, engine.putCalls[0].second[0], 0f)
        assertEquals(1f, engine.putCalls[1].second[0], 0f)

        // Progress: one entry per photo, in order, running totals:
        assertEquals(
            listOf(
                IndexCoordinator.IndexProgress(1, 3, 2),
                IndexCoordinator.IndexProgress(2, 3, 3),
                IndexCoordinator.IndexProgress(3, 3, 3),
            ),
            progress,
        )

        // Summary: the run's counts + the engine's cluster answer, untouched:
        assertEquals(3, summary.photosProcessed)
        assertEquals(3, summary.facesIndexed)
        assertEquals(3L, summary.totalFacesInStore)
        assertEquals(MlEngine.ClusterSummary(3, 2, listOf(2, 1)), summary.clusterSummary)
        assertEquals(1, engine.clusterCalls)
    }

    @Test
    fun `the upstream 0_24 threshold is the default and passes through`() = runBlocking {
        val engine = FakeEngine()
        val coordinator = IndexCoordinator(engine, Dispatchers.Unconfined)

        coordinator.indexAndCluster(emptyList())
        assertEquals(IndexCoordinator.RECOMMENDED_DISTANCE_THRESHOLD, engine.lastThreshold!!, 0.0)
        assertEquals(0.24, engine.lastThreshold!!, 0.0)

        coordinator.indexAndCluster(emptyList(), distanceThreshold = 0.30)
        assertEquals(0.30, engine.lastThreshold!!, 0.0)
    }

    @Test
    fun `no photos still clusters the store`() = runBlocking {
        val engine = FakeEngine()
        val coordinator = IndexCoordinator(engine, Dispatchers.Unconfined)

        val summary = coordinator.indexAndCluster(emptyList())

        assertEquals(0, engine.detectCalls.size)
        assertEquals(0, engine.putCalls.size)
        assertEquals(0, summary.photosProcessed)
        assertEquals(0, summary.facesIndexed)
        assertEquals(1, engine.clusterCalls)
        assertEquals(MlEngine.ClusterSummary(3, 2, listOf(2, 1)), summary.clusterSummary)
    }

    @Test
    fun `cancellation is honored between photos, never mid-face`() = runBlocking {
        val engine = FakeEngine()
        val coordinator = IndexCoordinator(engine, Dispatchers.Unconfined)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.indexAndCluster(photos(3)) { progress ->
                // Cancel AFTER the first photo finished (its faces put):
                if (progress.photosProcessed == 1) {
                    currentCoroutineContext().cancel()
                }
            }
        }
        job.join()
        assertTrue("the run must surface as cancelled", job.isCancelled)

        // Photo 1 ran whole (2 faces put); photo 2 and 3 never ran; the
        // clustering pass never ran:
        assertEquals(1, engine.detectCalls.size)
        assertEquals(2, engine.putCalls.size)
        assertEquals(0, engine.clusterCalls)
    }

    @Test
    fun `engine failures propagate unchanged`() {
        val engine = FakeEngine()
        engine.detectFail =
            IllegalStateException("face index store is not open; call openFaceIndex(dir) first")
        val coordinator = IndexCoordinator(engine, Dispatchers.Unconfined)

        try {
            runBlocking {
                coordinator.indexAndCluster(photos(2))
            }
            fail("the engine's failure shape must propagate")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
    }
}
