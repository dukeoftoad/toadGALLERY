package io.github.dukeoftoad.gallery.ml.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Facade tests for [MlEngine] (r22). The external functions cannot run on
 * the JVM --- and the suite refuses to pretend otherwise: every test
 * injects the [MlEngine.Bridge] seam with a scripted fake (a recorder,
 * not a mock native), except ONE that deliberately exercises the
 * production [MlEngineJni] binding to prove the library-absent path is a
 * graceful state: on the JVM there is no `libtoad_ml_jni.so`, so
 * [MlEngineJni.available] must read false and the facade must degrade to
 * [MlEngine.State.LibraryMissing] --- never an UnsatisfiedLinkError, never
 * a class-load crash.
 */
class MlEngineTest {

    /** Scripted bridge: records calls, answers canned results. */
    private class FakeBridge : MlEngine.Bridge {
        override var available: Boolean = true
        var initResult: Boolean = true
        var infoResult: String? = "toad-ml (ente pin 423b2d80)"
        var detectResult: FloatArray = FloatArray(0)
        var releaseResult: Boolean = true
        var initCalls = 0
        var detectCalls = 0
        var releaseCalls = 0
        var lastInitArg: String? = null
        var lastDetectArg: ByteArray? = null

        // ---- r23 ----
        var embeddingsResult: FloatArray = FloatArray(0)
        var storeOpenResult: Boolean = true
        var storePutResult: Long = 1L
        var storeFaceCountResult: Long = 0L
        var storeClusterResult: String = "{\"faces\":0,\"clusters\":0,\"sizes\":[]}"
        var storeCloseResult: Boolean = true
        var embeddingsCalls = 0
        var storeOpenCalls = 0
        var storePutCalls = 0
        var storeFaceCountCalls = 0
        var storeClusterCalls = 0
        var storeCloseCalls = 0
        var lastStoreOpenArg: String? = null
        var lastPutFileId: Long? = null
        var lastPutEmbedding: FloatArray? = null
        var lastClusterThreshold: Double? = null

        // ---- r24 ----
        var storeIndexedFileIdsResult: LongArray = LongArray(0)
        var storeDeleteResult: Boolean = true
        var storeIndexedCalls = 0
        var storeDeleteCalls = 0
        var lastDeleteArg: LongArray? = null

        override fun nativeInit(modelsDir: String): Boolean {
            initCalls++
            lastInitArg = modelsDir
            return initResult
        }

        override fun nativeEngineInfo(): String? = infoResult

        override fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray {
            detectCalls++
            lastDetectArg = jpegBytes
            return detectResult
        }

        override fun nativeRelease(): Boolean {
            releaseCalls++
            return releaseResult
        }

        override fun nativeDetectFaceEmbeddingsJpeg(jpegBytes: ByteArray): FloatArray {
            embeddingsCalls++
            return embeddingsResult
        }

        override fun nativeStoreOpen(dir: String): Boolean {
            storeOpenCalls++
            lastStoreOpenArg = dir
            return storeOpenResult
        }

        override fun nativeStorePutFace(fileId: Long, embedding: FloatArray): Long {
            storePutCalls++
            lastPutFileId = fileId
            lastPutEmbedding = embedding
            return storePutResult
        }

        override fun nativeStoreFaceCount(): Long {
            storeFaceCountCalls++
            return storeFaceCountResult
        }

        override fun nativeStoreCluster(distanceThreshold: Double): String {
            storeClusterCalls++
            lastClusterThreshold = distanceThreshold
            return storeClusterResult
        }

        override fun nativeStoreClose(): Boolean {
            storeCloseCalls++
            return storeCloseResult
        }

        override fun nativeStoreIndexedFaceFileIds(): LongArray {
            storeIndexedCalls++
            return storeIndexedFileIdsResult
        }

        override fun nativeStoreDeleteFacesForFiles(fileIds: LongArray): Boolean {
            storeDeleteCalls++
            lastDeleteArg = fileIds
            return storeDeleteResult
        }
    }

    @Test
    fun `missing library degrades every operation to the LibraryMissing state`() {
        val bridge = FakeBridge().apply { available = false }
        val engine = MlEngine(bridge)

        assertEquals(MlEngine.State.LibraryMissing, engine.state())
        assertEquals(MlEngine.State.LibraryMissing, engine.initialize("/data/models"))
        assertEquals("no native call may happen without a library", 0, bridge.initCalls)
        assertNull("there is no engine to describe", engine.engineInfo())
        assertFalse("nothing was loaded, so nothing was freed", engine.release())
        try {
            engine.detectFacesJpeg(byteArrayOf(1))
            fail("inference on a library-less build must fail explicitly, not UnsatisfiedLinkError")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not in this build"))
        }
    }

    @Test
    fun `the production bridge reports unavailable on the JVM without a class-load crash`() {
        // Touches the REAL MlEngineJni object: the JVM runtime has no
        // libtoad_ml_jni.so, so the try-load in its init must capture the
        // UnsatisfiedLinkError once and report false --- plain builds (and
        // the JVM suite) never crash at class-load.
        assertFalse(MlEngineJni.available)
        val engine = MlEngine() // default bridge -> MlEngineJni
        assertFalse(engine.available)
        assertEquals(MlEngine.State.LibraryMissing, engine.state())
    }

    @Test
    fun `initialize maps the native result to Ready and Error`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        bridge.initResult = true
        assertEquals(MlEngine.State.Ready, engine.initialize("/data/models"))
        assertEquals(MlEngine.State.Ready, engine.state())
        assertEquals("/data/models", bridge.lastInitArg)

        bridge.initResult = false
        val state = engine.initialize("/data/models")
        assertTrue(state is MlEngine.State.Error)
        assertEquals(MlEngine.State.Error((state as MlEngine.State.Error).message), engine.state())
        assertTrue("the error message must carry the failed dir", state.message.contains("/data/models"))
    }

    @Test
    fun `detectFacesJpeg maps the flat N5 output into boxes and passes the bytes through`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")

        bridge.detectResult = floatArrayOf(
            10f, 20f, 110f, 220f, 0.5f,
            1f, 2f, 3f, 4f, 0.9f,
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val boxes = engine.detectFacesJpeg(jpeg)

        assertEquals(2, boxes.size)
        assertEquals(MlEngine.FaceBox(10f, 20f, 110f, 220f, 0.5f), boxes[0])
        assertEquals(MlEngine.FaceBox(1f, 2f, 3f, 4f, 0.9f), boxes[1])
        assertTrue(bridge.lastDetectArg!!.contentEquals(jpeg))

        // The empty array is the contract's "no faces":
        bridge.detectResult = FloatArray(0)
        assertEquals(emptyList<MlEngine.FaceBox>(), engine.detectFacesJpeg(jpeg))
    }

    @Test
    fun `a malformed flat output fails as IllegalStateException`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")
        bridge.detectResult = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f) // 7 floats: not N*5
        try {
            engine.detectFacesJpeg(byteArrayOf(1))
            fail("a broken engine output must surface as the facade's failure shape")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("7"))
        }
    }

    @Test
    fun `the native IllegalStateException surfaces unchanged with its message`() {
        val bridge = object : MlEngine.Bridge {
            override val available = true
            override fun nativeInit(modelsDir: String) = true
            override fun nativeEngineInfo(): String? = null
            override fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray =
                throw IllegalStateException("panic in ort session")

            override fun nativeRelease() = true
            override fun nativeDetectFaceEmbeddingsJpeg(jpegBytes: ByteArray): FloatArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreOpen(dir: String) = true
            override fun nativeStorePutFace(fileId: Long, embedding: FloatArray) = 1L
            override fun nativeStoreFaceCount() = 0L
            override fun nativeStoreCluster(distanceThreshold: Double) = "{}"
            override fun nativeStoreClose() = true
            override fun nativeStoreIndexedFaceFileIds(): LongArray = LongArray(0)
            override fun nativeStoreDeleteFacesForFiles(fileIds: LongArray): Boolean = true
        }
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")
        try {
            engine.detectFacesJpeg(byteArrayOf(1))
            fail("the Rust panic must surface")
        } catch (expected: IllegalStateException) {
            assertEquals("panic in ort session", expected.message)
        }
    }

    @Test
    fun `release maps the native result and re-init after release works`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")
        assertEquals(MlEngine.State.Ready, engine.state())

        bridge.releaseResult = true
        assertTrue(engine.release())
        assertEquals(MlEngine.State.Uninitialized, engine.state())
        assertEquals(1, bridge.releaseCalls)

        // Idempotent "nothing to free" leg, then the contract's re-init:
        bridge.releaseResult = false
        assertFalse(engine.release())
        assertEquals(MlEngine.State.Ready, engine.initialize("/data/models"))
        assertEquals(2, bridge.initCalls)
    }

    @Test
    fun `engineInfo delegates to the bridge only when a library exists`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        assertEquals("toad-ml (ente pin 423b2d80)", engine.engineInfo())

        bridge.available = false
        assertNull(engine.engineInfo())
    }

    // ---- r23: embeddings + the face-index store state machine ----

    /** The contract's stride: 5 box floats + the 192-float embedding. */
    private val stride = MlEngine.EMBEDDING_STRIDE

    private fun embeddingRow(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        score: Float,
        seed: Float,
    ): FloatArray {
        val row = FloatArray(stride)
        row[0] = x1
        row[1] = y1
        row[2] = x2
        row[3] = y2
        row[4] = score
        for (i in 5 until stride) row[i] = seed + i
        return row
    }

    @Test
    fun `detectFaceEmbeddings maps the flat stride-197 output into rows`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")

        val row0 = embeddingRow(10f, 20f, 110f, 220f, 0.7f, 0f)
        val row1 = embeddingRow(1f, 2f, 3f, 4f, 0.9f, 1f)
        bridge.embeddingsResult = row0 + row1
        val faces = engine.detectFaceEmbeddings(byteArrayOf(1, 2, 3))

        assertEquals(2, faces.size)
        assertEquals(10f, faces[0].x1, 0f)
        assertEquals(0.7f, faces[0].score, 0f)
        assertEquals(MlEngine.FACE_EMBEDDING_DIM, faces[0].embedding.size)
        assertEquals(5f, faces[0].embedding[0], 0f)
        assertEquals((stride - 1).toFloat(), faces[0].embedding[191], 0f)
        assertEquals(1f, faces[1].x1, 0f)
        assertEquals(0.9f, faces[1].score, 0f)

        // The empty array is the contract's "no faces":
        bridge.embeddingsResult = FloatArray(0)
        assertEquals(emptyList<MlEngine.FaceEmbedding>(), engine.detectFaceEmbeddings(byteArrayOf(1)))
    }

    @Test
    fun `a non-stride-197 embeddings output fails as IllegalStateException`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")

        // One full row plus 6 stray floats (a drifted 198-wide stride):
        bridge.embeddingsResult = embeddingRow(0f, 0f, 1f, 1f, 0.5f, 0f) + FloatArray(6)
        try {
            engine.detectFaceEmbeddings(byteArrayOf(1))
            fail("a broken stride (non-197 embedding dim) must surface as the facade's failure shape")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains(stride.toString()))
        }
    }

    @Test
    fun `store operations before openFaceIndex throw IllegalStateException`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        val rejects = listOf<() -> Any?>(
            { engine.putFace(7L, FloatArray(MlEngine.FACE_EMBEDDING_DIM)) },
            { engine.faceCount() },
            { engine.clusterFaces(0.24) },
            { engine.indexedFaceFileIds() },
            { engine.deleteFacesForFiles(longArrayOf(7L)) },
        )
        for (reject in rejects) {
            try {
                reject()
                fail("a store op before openFaceIndex must throw")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("not open"))
            }
        }
        assertEquals("no native store call may happen while closed", 0, bridge.storePutCalls)
        assertEquals(0, bridge.storeFaceCountCalls)
        assertEquals(0, bridge.storeClusterCalls)
    }

    @Test
    fun `putFace rejects a wrong embedding dimension before any native call`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        for (badDim in intArrayOf(0, 191, 193, 512)) {
            try {
                engine.putFace(7L, FloatArray(badDim))
                fail("$badDim floats must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("192"))
            }
        }
        assertEquals("the dimension check fires facade-side", 0, bridge.storePutCalls)

        // The exact 192-float embedding goes through verbatim:
        val good = FloatArray(MlEngine.FACE_EMBEDDING_DIM) { it.toFloat() }
        bridge.storePutResult = 42L
        assertEquals(42L, engine.putFace(7L, good))
        assertEquals(1, bridge.storePutCalls)
        assertEquals(7L, bridge.lastPutFileId)
        assertTrue(bridge.lastPutEmbedding!!.contentEquals(good))
    }

    @Test
    fun `clusterFaces parses the compact JSON summary and passes the threshold through`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        bridge.storeClusterResult = "{\"faces\":4,\"clusters\":2,\"sizes\":[3,1]}"
        val summary = engine.clusterFaces(0.24)

        assertEquals(4, summary.faces)
        assertEquals(2, summary.clusters)
        assertEquals(listOf(3, 1), summary.sizes)
        assertEquals(0.24, bridge.lastClusterThreshold!!, 0.0)
        assertEquals(1, bridge.storeClusterCalls)
    }

    @Test
    fun `malformed cluster JSON fails as IllegalStateException`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        for (bad in listOf("not json at all", "{\"faces\":1}", "{\"faces\":1,\"clusters\":0}")) {
            bridge.storeClusterResult = bad
            try {
                engine.clusterFaces(0.24)
                fail("malformed summary JSON must fail: $bad")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("malformed cluster summary"))
            }
        }
    }

    @Test
    fun `the store lifecycle is idempotent and release closes the store`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Open, use, close, close again (idempotent):
        assertTrue(engine.openFaceIndex("/data/face-index"))
        assertEquals("/data/face-index", bridge.lastStoreOpenArg)
        bridge.storeFaceCountResult = 3L
        assertEquals(3L, engine.faceCount())
        assertTrue(engine.closeFaceIndex())
        bridge.storeCloseResult = false
        assertFalse("nothing open to close the second time", engine.closeFaceIndex())
        try {
            engine.faceCount()
            fail("after closeFaceIndex the store is closed again")
        } catch (expected: IllegalStateException) {
            // the pinned store-before-open shape
        }

        // Re-open works, and engine release closes the store with it:
        bridge.storeCloseResult = true
        assertTrue(engine.openFaceIndex("/data/face-index"))
        assertTrue(engine.release())
        try {
            engine.putFace(1L, FloatArray(MlEngine.FACE_EMBEDDING_DIM))
            fail("engine release must close the face-index store too")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }

        // The r22 re-init discipline carries over to the store: open again.
        assertTrue(engine.openFaceIndex("/data/face-index"))
        assertEquals(3, bridge.storeOpenCalls)
    }

    @Test
    fun `indexedFaceFileIds maps the native long array through the open-store gate`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Closed store: the state-machine rejection, no native call.
        try {
            engine.indexedFaceFileIds()
            fail("indexedFaceFileIds before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeIndexedCalls)

        // Open store: the bridge's long array verbatim, as a List.
        engine.openFaceIndex("/data/face-index")
        bridge.storeIndexedFileIdsResult = longArrayOf(4L, 8L, 15L)
        assertEquals(listOf(4L, 8L, 15L), engine.indexedFaceFileIds())
        assertEquals(1, bridge.storeIndexedCalls)

        // Empty is honest empty (nothing indexed at the current version):
        bridge.storeIndexedFileIdsResult = LongArray(0)
        assertEquals(emptyList<Long>(), engine.indexedFaceFileIds())
    }

    @Test
    fun `deleteFacesForFiles passes the file ids through and returns the native result`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Closed store: rejected before any native call.
        try {
            engine.deleteFacesForFiles(longArrayOf(1L, 2L))
            fail("deleteFacesForFiles before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeDeleteCalls)

        // Open store: ids passed verbatim, native result mapped, and the
        // empty array (upstream's own no-op) goes through unchanged.
        engine.openFaceIndex("/data/face-index")
        assertTrue(engine.deleteFacesForFiles(longArrayOf(1L, 2L)))
        assertTrue(bridge.lastDeleteArg!!.contentEquals(longArrayOf(1L, 2L)))
        assertTrue(engine.deleteFacesForFiles(LongArray(0)))
        assertTrue(bridge.lastDeleteArg!!.isEmpty())
        assertEquals(2, bridge.storeDeleteCalls)

        // The native failure shape maps through (false without throwing):
        bridge.storeDeleteResult = false
        assertFalse(engine.deleteFacesForFiles(longArrayOf(9L)))
    }

    @Test
    fun `a library-less build rejects every r23 operation explicitly`() {
        val bridge = FakeBridge().apply { available = false }
        val engine = MlEngine(bridge)

        val rejects = listOf<() -> Any?>(
            { engine.detectFaceEmbeddings(byteArrayOf(1)) },
            { engine.openFaceIndex("/data/face-index") },
            { engine.faceCount() },
            { engine.clusterFaces(0.24) },
            { engine.indexedFaceFileIds() },
            { engine.deleteFacesForFiles(longArrayOf(1L)) },
        )
        for (reject in rejects) {
            try {
                reject()
                fail("a store op on a library-less build must fail explicitly, not UnsatisfiedLinkError")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("not in this build"))
            }
        }
        assertEquals("no native call may happen without a library", 0, bridge.embeddingsCalls)
        assertEquals(0, bridge.storeOpenCalls)
        assertEquals(0, bridge.storeFaceCountCalls)
        assertEquals(0, bridge.storeClusterCalls)
        assertFalse("nothing was open, so nothing was closed", engine.closeFaceIndex())
    }
}
