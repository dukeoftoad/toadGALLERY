package com.toadgallery.gallery.ml.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
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

        // ---- r25 ----
        var storeFaceClustersResult: String = "[]"
        var storeClusterFileIdsResult: LongArray = LongArray(0)
        var storeMergeResult: Boolean = true
        var storeRemoveFacesResult: Boolean = true
        var storeFaceClustersCalls = 0
        var storeClusterFileIdsCalls = 0
        var storeMergeCalls = 0
        var storeRemoveFacesCalls = 0
        var lastClusterFileIdsArg: String? = null
        var lastMergeSource: String? = null
        var lastMergeTarget: String? = null
        var lastRemoveClusterId: String? = null
        var lastRemoveFileIds: LongArray? = null

        // ---- r26 ----
        var initSemanticResult: Boolean = true
        var clipImageResult: FloatArray = FloatArray(MlEngine.CLIP_EMBEDDING_DIM)
        var clipTextResult: FloatArray = FloatArray(MlEngine.CLIP_EMBEDDING_DIM)
        var storePutClipResult: Boolean = true
        var storeDeleteClipsResult: Boolean = true
        var storeClipIndexedResult: LongArray = LongArray(0)
        var storeClipCountResult: Long = 0L
        var storeSearchClipsResult: String = "[]"
        var storeFillClipResult: String =
            "{\"outcome\":\"Completed\",\"rows\":0,\"indexed\":0,\"skipped\":0,\"resumed\":false}"
        var initSemanticCalls = 0
        var clipImageCalls = 0
        var clipTextCalls = 0
        var storePutClipCalls = 0
        var storeDeleteClipsCalls = 0
        var storeClipIndexedCalls = 0
        var storeClipCountCalls = 0
        var storeSearchClipsCalls = 0
        var storeFillClipCalls = 0
        var lastInitSemanticArg: String? = null
        var lastClipImageArg: ByteArray? = null
        var lastClipTextArg: String? = null
        var lastPutClipFileId: Long? = null
        var lastPutClipEmbedding: FloatArray? = null
        var lastDeleteClipsArg: LongArray? = null
        var lastSearchQuery: FloatArray? = null
        var lastSearchLimit: Int? = null

        // ---- r39 ----
        var storeSearchClipsWithinResult: String = "[]"
        var storeSearchClipsWithinCalls = 0
        var lastWithinQuery: FloatArray? = null
        var lastWithinMaxDistance: Float? = null
        var lastWithinLimit: Int? = null

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

        override fun nativeStoreFaceClusters(): String {
            storeFaceClustersCalls++
            return storeFaceClustersResult
        }

        override fun nativeStoreClusterFileIds(clusterId: String): LongArray {
            storeClusterFileIdsCalls++
            lastClusterFileIdsArg = clusterId
            return storeClusterFileIdsResult
        }

        override fun nativeStoreMergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean {
            storeMergeCalls++
            lastMergeSource = sourceClusterId
            lastMergeTarget = targetClusterId
            return storeMergeResult
        }

        override fun nativeStoreRemoveFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean {
            storeRemoveFacesCalls++
            lastRemoveClusterId = clusterId
            lastRemoveFileIds = fileIds
            return storeRemoveFacesResult
        }

        // ---- r26 ----
        override fun nativeInitSemantic(modelsDir: String): Boolean {
            initSemanticCalls++
            lastInitSemanticArg = modelsDir
            return initSemanticResult
        }

        override fun nativeClipImageEmbeddingJpeg(jpegBytes: ByteArray): FloatArray {
            clipImageCalls++
            lastClipImageArg = jpegBytes
            return clipImageResult
        }

        override fun nativeClipTextEmbedding(text: String): FloatArray {
            clipTextCalls++
            lastClipTextArg = text
            return clipTextResult
        }

        override fun nativeStorePutClip(fileId: Long, embedding: FloatArray): Boolean {
            storePutClipCalls++
            lastPutClipFileId = fileId
            lastPutClipEmbedding = embedding
            return storePutClipResult
        }

        override fun nativeStoreDeleteClipsForFiles(fileIds: LongArray): Boolean {
            storeDeleteClipsCalls++
            lastDeleteClipsArg = fileIds
            return storeDeleteClipsResult
        }

        override fun nativeStoreClipIndexedFileIds(): LongArray {
            storeClipIndexedCalls++
            return storeClipIndexedResult
        }

        override fun nativeStoreClipCount(): Long {
            storeClipCountCalls++
            return storeClipCountResult
        }

        override fun nativeStoreSearchClips(queryEmbedding: FloatArray, limit: Int): String {
            storeSearchClipsCalls++
            lastSearchQuery = queryEmbedding
            lastSearchLimit = limit
            return storeSearchClipsResult
        }

        // ---- r39 ----
        override fun nativeStoreSearchClipsWithin(
            queryEmbedding: FloatArray,
            maxDistance: Float,
            limit: Int,
        ): String {
            storeSearchClipsWithinCalls++
            lastWithinQuery = queryEmbedding
            lastWithinMaxDistance = maxDistance
            lastWithinLimit = limit
            return storeSearchClipsWithinResult
        }

        override fun nativeStoreFillClipIndex(): String {
            storeFillClipCalls++
            return storeFillClipResult
        }
    }

    /**
     * r51 (audit C-4): the facade's five state flags are process-global
     * shared state, so every test in this class --- and any other engine
     * construction in the same JVM --- reads and writes ONE truth. Each
     * test is therefore bracketed by a full reset: release() on a fresh
     * available bridge restores exactly the five flags' initial values
     * (the production teardown's own discipline --- no test-only hook in
     * the facade). @Before defends against state leaked in by an earlier
     * test, @After against state this class leaks out.
     */
    @Before
    fun resetSharedEngineStateBefore() {
        resetSharedEngineState()
    }

    @After
    fun resetSharedEngineStateAfter() {
        resetSharedEngineState()
    }

    private fun resetSharedEngineState() {
        MlEngine(FakeBridge()).release()
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
            override fun nativeStoreFaceClusters(): String =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreClusterFileIds(clusterId: String): LongArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreMergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreRemoveFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeInitSemantic(modelsDir: String): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeClipImageEmbeddingJpeg(jpegBytes: ByteArray): FloatArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeClipTextEmbedding(text: String): FloatArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStorePutClip(fileId: Long, embedding: FloatArray): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreDeleteClipsForFiles(fileIds: LongArray): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreClipIndexedFileIds(): LongArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreClipCount(): Long =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreSearchClips(queryEmbedding: FloatArray, limit: Int): String =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreSearchClipsWithin(
                queryEmbedding: FloatArray,
                maxDistance: Float,
                limit: Int,
            ): String = throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreFillClipIndex(): String =
                throw UnsupportedOperationException("unused in this test")
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

    // ---- r25: the People read/management paths ----

    @Test
    fun `faceClusters parses the live cluster JSON including null covers`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        bridge.storeFaceClustersResult =
            "[{\"cluster_id\":\"cluster_a\",\"count\":12,\"cover_file_id\":4813}," +
                "{\"cluster_id\":\"cluster_b\",\"count\":3,\"cover_file_id\":null}]"
        val clusters = engine.faceClusters()

        // Content equality + the engine's ordering preserved verbatim:
        assertEquals(
            listOf(
                MlEngine.FaceCluster("cluster_a", 12, 4813L),
                MlEngine.FaceCluster("cluster_b", 3, null),
            ),
            clusters,
        )
        assertEquals(1, bridge.storeFaceClustersCalls)

        // The empty array is the honest "no clusters yet":
        bridge.storeFaceClustersResult = "[]"
        assertEquals(emptyList<MlEngine.FaceCluster>(), engine.faceClusters())
    }

    @Test
    fun `malformed face clusters JSON fails as IllegalStateException`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        for (bad in listOf(
            "not json at all",
            "{\"faces\":1}", // an object, not the array envelope
            "[{\"cluster_id\":\"cluster_a\"}]", // missing count/cover fields
            "[{\"cluster_id\":\"cluster_a\",\"count\":1}]", // missing cover field
            "[{\"cluster_id\":\"cluster_a\",\"count\":-1,\"cover_file_id\":null}]", // negative count
            "[{\"cluster_id\":7,\"count\":1,\"cover_file_id\":null}]", // wrong type: numeric id
            "[{\"cluster_id\":\"cluster_a\",\"count\":{},\"cover_file_id\":null}]", // wrong type: object count
        )) {
            bridge.storeFaceClustersResult = bad
            try {
                engine.faceClusters()
                fail("malformed face clusters JSON must fail: $bad")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("malformed face clusters"))
            }
        }
    }

    @Test
    fun `faceClusters before openFaceIndex throws without a native call`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        try {
            engine.faceClusters()
            fail("faceClusters before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals("the state machine rejects before the bridge", 0, bridge.storeFaceClustersCalls)
    }

    @Test
    fun `clusterFileIds passes the id through and maps the long array`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Closed store: the state-machine rejection, no native call.
        try {
            engine.clusterFileIds("cluster_a")
            fail("clusterFileIds before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeClusterFileIdsCalls)

        // Open store: the bridge's long array verbatim, as a List.
        engine.openFaceIndex("/data/face-index")
        bridge.storeClusterFileIdsResult = longArrayOf(4L, 8L, 15L)
        assertEquals(listOf(4L, 8L, 15L), engine.clusterFileIds("cluster_a"))
        assertEquals("cluster_a", bridge.lastClusterFileIdsArg)

        // Empty is honest empty (no files in the cluster / unknown id):
        bridge.storeClusterFileIdsResult = LongArray(0)
        assertEquals(emptyList<Long>(), engine.clusterFileIds("cluster_a"))
        assertEquals(2, bridge.storeClusterFileIdsCalls)
    }

    @Test
    fun `mergeFaceClusters rejects a self-merge before any native call`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        try {
            engine.mergeFaceClusters("cluster_same", "cluster_same")
            fail("a self-merge must be rejected facade-side")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("must differ"))
            assertTrue(expected.message!!.contains("cluster_same"))
        }
        assertEquals("the guard fires before the native call", 0, bridge.storeMergeCalls)

        // Distinct ids delegate with the arguments verbatim:
        bridge.storeMergeResult = true
        assertTrue(engine.mergeFaceClusters("cluster_a", "cluster_b"))
        assertEquals("cluster_a", bridge.lastMergeSource)
        assertEquals("cluster_b", bridge.lastMergeTarget)
        assertEquals(1, bridge.storeMergeCalls)

        // The native result maps through (false without throwing):
        bridge.storeMergeResult = false
        assertFalse(engine.mergeFaceClusters("cluster_a", "cluster_b"))
    }

    @Test
    fun `mergeFaceClusters follows the store state machine`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Closed store: the state-machine rejection fires before the
        // argument guard AND before any native call (the putFace order):
        try {
            engine.mergeFaceClusters("cluster_a", "cluster_b")
            fail("mergeFaceClusters before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        try {
            engine.mergeFaceClusters("cluster_same", "cluster_same")
            fail("the closed-store rejection still dominates a bad argument")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeMergeCalls)
    }

    @Test
    fun `removeFacesFromCluster passes arguments through and maps the result`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Closed store: rejected before any native call.
        try {
            engine.removeFacesFromCluster("cluster_a", longArrayOf(7L))
            fail("removeFacesFromCluster before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeRemoveFacesCalls)

        // Open store: arguments verbatim, native result mapped.
        engine.openFaceIndex("/data/face-index")
        bridge.storeRemoveFacesResult = true
        assertTrue(engine.removeFacesFromCluster("cluster_a", longArrayOf(7L, 8L)))
        assertEquals("cluster_a", bridge.lastRemoveClusterId)
        assertTrue(bridge.lastRemoveFileIds!!.contentEquals(longArrayOf(7L, 8L)))

        // The empty selection (the no-op) goes through unchanged:
        assertTrue(engine.removeFacesFromCluster("cluster_a", LongArray(0)))
        assertTrue(bridge.lastRemoveFileIds!!.isEmpty())
        assertEquals(2, bridge.storeRemoveFacesCalls)

        // The native failure shape maps through (false without throwing):
        bridge.storeRemoveFacesResult = false
        assertFalse(engine.removeFacesFromCluster("cluster_a", longArrayOf(9L)))
    }

    @Test
    fun `release closes the face index for the r25 operations too`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")
        assertTrue(engine.release())

        try {
            engine.faceClusters()
            fail("engine release must close the face-index store for r25 reads too")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        try {
            engine.mergeFaceClusters("cluster_a", "cluster_b")
            fail("engine release must close the face-index store for r25 writes too")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeFaceClustersCalls)
        assertEquals(0, bridge.storeMergeCalls)
    }

    @Test
    fun `a library-less build rejects every r25 operation explicitly`() {
        val bridge = FakeBridge().apply { available = false }
        val engine = MlEngine(bridge)

        val rejects = listOf<() -> Any?>(
            { engine.faceClusters() },
            { engine.clusterFileIds("cluster_a") },
            { engine.mergeFaceClusters("cluster_a", "cluster_b") },
            { engine.removeFacesFromCluster("cluster_a", longArrayOf(1L)) },
        )
        for (reject in rejects) {
            try {
                reject()
                fail("an r25 op on a library-less build must fail explicitly, not UnsatisfiedLinkError")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("not in this build"))
            }
        }
        assertEquals("no native call may happen without a library", 0, bridge.storeFaceClustersCalls)
        assertEquals(0, bridge.storeClusterFileIdsCalls)
        assertEquals(0, bridge.storeMergeCalls)
        assertEquals(0, bridge.storeRemoveFacesCalls)
    }

    @Test
    fun `a native r25 failure surfaces unchanged with its message`() {
        val bridge = object : MlEngine.Bridge {
            override val available = true
            override fun nativeInit(modelsDir: String) = true
            override fun nativeEngineInfo(): String? = null
            override fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray = FloatArray(0)
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
            override fun nativeStoreFaceClusters(): String =
                throw IllegalStateException("store read failed")
            override fun nativeStoreClusterFileIds(clusterId: String): LongArray = LongArray(0)
            override fun nativeStoreMergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean = true
            override fun nativeStoreRemoveFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean = true
            override fun nativeInitSemantic(modelsDir: String): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeClipImageEmbeddingJpeg(jpegBytes: ByteArray): FloatArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeClipTextEmbedding(text: String): FloatArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStorePutClip(fileId: Long, embedding: FloatArray): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreDeleteClipsForFiles(fileIds: LongArray): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreClipIndexedFileIds(): LongArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreClipCount(): Long =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreSearchClips(queryEmbedding: FloatArray, limit: Int): String =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreSearchClipsWithin(
                queryEmbedding: FloatArray,
                maxDistance: Float,
                limit: Int,
            ): String = throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreFillClipIndex(): String =
                throw UnsupportedOperationException("unused in this test")
        }
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")
        try {
            engine.faceClusters()
            fail("the native failure must surface")
        } catch (expected: IllegalStateException) {
            assertEquals("store read failed", expected.message)
        }
    }

    // ---- r26: the semantic (Magic Search) layer ----

    @Test
    fun `initSemantic maps the native result to Ready and Error and release resets it`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")
        assertEquals(MlEngine.SemanticState.Uninitialized, engine.semanticState())

        bridge.initSemanticResult = true
        assertEquals(MlEngine.SemanticState.Ready, engine.initSemantic("/data/models"))
        assertEquals(MlEngine.SemanticState.Ready, engine.semanticState())
        assertEquals("/data/models", bridge.lastInitSemanticArg)

        // A failed re-init flips the state to Error (the flag stays false):
        bridge.initSemanticResult = false
        val state = engine.initSemantic("/data/models")
        assertTrue(state is MlEngine.SemanticState.Error)
        assertEquals(MlEngine.SemanticState.Error((state as MlEngine.SemanticState.Error).message), engine.semanticState())
        assertTrue("the error message must carry the failed dir", state.message.contains("/data/models"))

        // Engine release tears the semantic half with it (the r26 contract):
        engine.release()
        assertEquals(MlEngine.SemanticState.Uninitialized, engine.semanticState())

        // And re-init works after release (the r22 discipline):
        bridge.initSemanticResult = true
        assertEquals(MlEngine.SemanticState.Ready, engine.initSemantic("/data/models"))
        assertEquals(3, bridge.initSemanticCalls)
    }

    @Test
    fun `initSemantic degrades to LibraryMissing without a native call`() {
        val bridge = FakeBridge().apply { available = false }
        val engine = MlEngine(bridge)

        assertEquals(MlEngine.SemanticState.LibraryMissing, engine.initSemantic("/data/models"))
        assertEquals(MlEngine.SemanticState.LibraryMissing, engine.semanticState())
        assertEquals("no native call may happen without a library", 0, bridge.initSemanticCalls)
    }

    @Test
    fun `initSemantic needs no store but the r26 store ops follow the open-store gate`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")

        // The semantic session is independent of the face-index store:
        assertEquals(MlEngine.SemanticState.Ready, engine.initSemantic("/data/models"))

        // ...while the clip store functions require the store open:
        val rejects = listOf<() -> Any?>(
            { engine.putClip(7L, FloatArray(MlEngine.CLIP_EMBEDDING_DIM)) },
            { engine.deleteClipsForFiles(longArrayOf(7L)) },
            { engine.clipIndexedFileIds() },
            { engine.clipCount() },
            { engine.searchClips(FloatArray(MlEngine.CLIP_EMBEDDING_DIM), 1) },
            { engine.fillClipIndex() },
        )
        for (reject in rejects) {
            try {
                reject()
                fail("an r26 store op before openFaceIndex must throw")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("not open"))
            }
        }
        assertEquals("no native store call may happen while closed", 0, bridge.storePutClipCalls)
        assertEquals(0, bridge.storeSearchClipsCalls)
        assertEquals(0, bridge.storeFillClipCalls)
    }

    @Test
    fun `putClip rejects a wrong embedding dimension before any native call`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        // 192 is the FACE dimension --- the clip guard must not accept it:
        for (badDim in intArrayOf(0, 192, 511, 513)) {
            try {
                engine.putClip(7L, FloatArray(badDim))
                fail("$badDim floats must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("512"))
            }
        }
        assertEquals("the dimension check fires facade-side", 0, bridge.storePutClipCalls)

        // The exact 512-float embedding goes through verbatim, native boolean mapped:
        val good = FloatArray(MlEngine.CLIP_EMBEDDING_DIM) { it.toFloat() }
        bridge.storePutClipResult = true
        assertTrue(engine.putClip(7L, good))
        assertEquals(1, bridge.storePutClipCalls)
        assertEquals(7L, bridge.lastPutClipFileId)
        assertTrue(bridge.lastPutClipEmbedding!!.contentEquals(good))

        // The native failure shape maps through (false without throwing):
        bridge.storePutClipResult = false
        assertFalse(engine.putClip(8L, good))
    }

    @Test
    fun `clipImageEmbedding and clipTextEmbedding pass through and map the 512-float vectors`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")
        engine.initSemantic("/data/models")

        val imageVector = FloatArray(MlEngine.CLIP_EMBEDDING_DIM) { 0.5f }
        bridge.clipImageResult = imageVector
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        assertTrue(engine.clipImageEmbedding(jpeg).contentEquals(imageVector))
        assertTrue(bridge.lastClipImageArg!!.contentEquals(jpeg))

        val textVector = FloatArray(MlEngine.CLIP_EMBEDDING_DIM) { -0.25f }
        bridge.clipTextResult = textVector
        assertTrue(engine.clipTextEmbedding("a red bicycle").contentEquals(textVector))
        assertEquals("a red bicycle", bridge.lastClipTextArg)
    }

    @Test
    fun `searchClips guards the query dimension and the limit before any native call`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        for (badDim in intArrayOf(0, 511, 513)) {
            try {
                engine.searchClips(FloatArray(badDim), 1)
                fail("$badDim floats must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("512"))
            }
        }
        for (badLimit in intArrayOf(0, -1)) {
            try {
                engine.searchClips(FloatArray(MlEngine.CLIP_EMBEDDING_DIM), badLimit)
                fail("$badLimit must be rejected as a limit")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("positive"))
            }
        }
        assertEquals("the guards fire facade-side", 0, bridge.storeSearchClipsCalls)
    }

    @Test
    fun `searchClips parses the match JSON preserving the vecdb order`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        bridge.storeSearchClipsResult =
            "[{\"file_id\":4813,\"distance\":0.42},{\"file_id\":7,\"distance\":0.9}]"
        val query = FloatArray(MlEngine.CLIP_EMBEDDING_DIM) { 0.1f }
        val matches = engine.searchClips(query, 2)

        // Verbatim order (the vecdb's own), content equality:
        assertEquals(
            listOf(MlEngine.ClipMatch(4813L, 0.42f), MlEngine.ClipMatch(7L, 0.9f)),
            matches,
        )
        assertTrue(bridge.lastSearchQuery!!.contentEquals(query))
        assertEquals(2, bridge.lastSearchLimit)
        assertEquals(1, bridge.storeSearchClipsCalls)

        // The empty array is the honest "no matches":
        bridge.storeSearchClipsResult = "[]"
        assertEquals(emptyList<MlEngine.ClipMatch>(), engine.searchClips(query, 2))
    }

    // ---- r39: the distance-cut search + the symmetric session halves ----

    @Test
    fun `searchClipsWithin guards dimension, distance and limit before any native call`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        for (badDim in intArrayOf(0, 511, 513)) {
            try {
                engine.searchClipsWithin(FloatArray(badDim), 0.7f, 1)
                fail("$badDim floats must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("512"))
            }
        }
        for (badDistance in floatArrayOf(-0.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            try {
                engine.searchClipsWithin(FloatArray(MlEngine.CLIP_EMBEDDING_DIM), badDistance, 1)
                fail("$badDistance must be rejected as a max distance")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("max distance"))
            }
        }
        for (badLimit in intArrayOf(0, -1)) {
            try {
                engine.searchClipsWithin(FloatArray(MlEngine.CLIP_EMBEDDING_DIM), 0.7f, badLimit)
                fail("$badLimit must be rejected as a limit")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("positive"))
            }
        }
        assertEquals("the guards fire facade-side", 0, bridge.storeSearchClipsWithinCalls)
    }

    @Test
    fun `searchClipsWithin delegates the cut to the bridge and parses the same JSON shape`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        bridge.storeSearchClipsWithinResult =
            "[{\"file_id\":11,\"distance\":0.30},{\"file_id\":22,\"distance\":0.69}]"
        val query = FloatArray(MlEngine.CLIP_EMBEDDING_DIM) { 0.25f }
        val matches = engine.searchClipsWithin(query, 0.7f, 50)

        assertEquals(
            listOf(MlEngine.ClipMatch(11L, 0.30f), MlEngine.ClipMatch(22L, 0.69f)),
            matches,
        )
        assertTrue(bridge.lastWithinQuery!!.contentEquals(query))
        assertEquals(0.7f, bridge.lastWithinMaxDistance!!)
        assertEquals(50, bridge.lastWithinLimit)
        assertEquals(1, bridge.storeSearchClipsWithinCalls)
    }

    @Test
    fun `initSemantic without initialize opens a semantic-only session - the two state machines stay independent`() {
        // The r39 contract: the native layer installs the CLIP half from
        // scratch; the facade neither requires nor fakes a FACE session.
        // The two state machines report their own halves honestly.
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Before anything: both halves uninitialized, the honest pair.
        assertEquals(MlEngine.State.Uninitialized, engine.state())
        assertEquals(MlEngine.SemanticState.Uninitialized, engine.semanticState())

        // The semantic half ALONE (no initialize call):
        bridge.initSemanticResult = true
        assertEquals(MlEngine.SemanticState.Ready, engine.initSemantic("/data/models"))
        assertEquals(MlEngine.SemanticState.Ready, engine.semanticState())
        // ...while the FACE half stays honestly uninitialized (no fake
        // Ready, no Error --- the two truths are independent):
        assertEquals(MlEngine.State.Uninitialized, engine.state())
        assertEquals(0, bridge.initCalls)
        assertEquals(1, bridge.initSemanticCalls)

        // initialize UPGRADES the semantic-only session with the face
        // half (the native layer's symmetric upgrade; the facade maps it
        // the same way it always did):
        bridge.initResult = true
        assertEquals(MlEngine.State.Ready, engine.initialize("/data/models"))
        assertEquals(MlEngine.State.Ready, engine.state())
        assertEquals(MlEngine.SemanticState.Ready, engine.semanticState())

        // Release tears BOTH halves (the r26 contract, unchanged):
        engine.release()
        assertEquals(MlEngine.State.Uninitialized, engine.state())
        assertEquals(MlEngine.SemanticState.Uninitialized, engine.semanticState())
    }

    @Test
    fun `malformed clip matches JSON fails as IllegalStateException`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")
        val query = FloatArray(MlEngine.CLIP_EMBEDDING_DIM)

        for (bad in listOf(
            "not json at all",
            "{\"file_id\":1}", // an object, not the array envelope
            "[{\"file_id\":1}]", // missing the distance field
            "[{\"distance\":0.5}]", // missing the file_id field
            "[{\"file_id\":null,\"distance\":0.5}]", // null file_id
            "[{\"file_id\":\"nope\",\"distance\":0.5}]", // unparsable file id
            "[{\"file_id\":{},\"distance\":0.5}]", // wrong type: object file id
            "[{\"file_id\":1,\"distance\":{}}]", // wrong type: object distance
        )) {
            bridge.storeSearchClipsResult = bad
            try {
                engine.searchClips(query, 1)
                fail("malformed clip matches JSON must fail: $bad")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("malformed clip matches"))
            }
        }
    }

    @Test
    fun `fillClipIndex parses all three pinned outcomes`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        for ((outcome, resumed) in listOf(
            "Completed" to false,
            "AlreadyFilled" to false,
            "Superseded" to true,
        )) {
            bridge.storeFillClipResult =
                "{\"outcome\":\"$outcome\",\"rows\":4,\"indexed\":2,\"skipped\":2,\"resumed\":$resumed}"
            assertEquals(
                MlEngine.ClipFillReport(outcome, 4, 2, 2, resumed),
                engine.fillClipIndex(),
            )
        }
        assertEquals(3, bridge.storeFillClipCalls)
    }

    @Test
    fun `malformed clip fill report JSON fails as IllegalStateException`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.openFaceIndex("/data/face-index")

        for (bad in listOf(
            "not json at all",
            "[]", // an array, not the object envelope
            "{\"outcome\":\"Completed\"}", // missing the count fields
            "{\"outcome\":\"Completed\",\"rows\":1,\"indexed\":1,\"skipped\":0}", // missing resumed
            "{\"rows\":1,\"indexed\":1,\"skipped\":0,\"resumed\":false}", // missing outcome
            "{\"outcome\":\"Restarted\",\"rows\":1,\"indexed\":1,\"skipped\":0,\"resumed\":false}", // not a pinned outcome
            "{\"outcome\":\"Completed\",\"rows\":{},\"indexed\":1,\"skipped\":0,\"resumed\":false}", // wrong type
            "{\"outcome\":\"Completed\",\"rows\":1,\"indexed\":1,\"skipped\":0,\"resumed\":\"yes\"}", // wrong type: string resumed
        )) {
            bridge.storeFillClipResult = bad
            try {
                engine.fillClipIndex()
                fail("malformed clip fill report JSON must fail: $bad")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("malformed clip fill report"))
            }
        }
    }

    @Test
    fun `clipIndexedFileIds maps the native long array through the open-store gate`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Closed store: the state-machine rejection, no native call.
        try {
            engine.clipIndexedFileIds()
            fail("clipIndexedFileIds before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeClipIndexedCalls)

        // Open store: the bridge's long array verbatim, as a List.
        engine.openFaceIndex("/data/face-index")
        bridge.storeClipIndexedResult = longArrayOf(4L, 8L, 15L)
        assertEquals(listOf(4L, 8L, 15L), engine.clipIndexedFileIds())
        assertEquals(1, bridge.storeClipIndexedCalls)

        // Empty is honest empty (nothing indexed at the current version):
        bridge.storeClipIndexedResult = LongArray(0)
        assertEquals(emptyList<Long>(), engine.clipIndexedFileIds())
    }

    @Test
    fun `deleteClipsForFiles passes the file ids through and maps the result`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Closed store: rejected before any native call.
        try {
            engine.deleteClipsForFiles(longArrayOf(1L, 2L))
            fail("deleteClipsForFiles before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeDeleteClipsCalls)

        // Open store: ids passed verbatim, native result mapped, empty no-op.
        engine.openFaceIndex("/data/face-index")
        bridge.storeDeleteClipsResult = true
        assertTrue(engine.deleteClipsForFiles(longArrayOf(1L, 2L)))
        assertTrue(bridge.lastDeleteClipsArg!!.contentEquals(longArrayOf(1L, 2L)))
        assertTrue(engine.deleteClipsForFiles(LongArray(0)))
        assertTrue(bridge.lastDeleteClipsArg!!.isEmpty())
        assertEquals(2, bridge.storeDeleteClipsCalls)

        // The native failure shape maps through (false without throwing):
        bridge.storeDeleteClipsResult = false
        assertFalse(engine.deleteClipsForFiles(longArrayOf(9L)))
    }

    @Test
    fun `clipCount maps the native count through the open-store gate`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)

        // Closed store: rejected before any native call.
        try {
            engine.clipCount()
            fail("clipCount before openFaceIndex must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storeClipCountCalls)

        // Open store: the native count verbatim.
        engine.openFaceIndex("/data/face-index")
        bridge.storeClipCountResult = 4813L
        assertEquals(4813L, engine.clipCount())
        assertEquals(1, bridge.storeClipCountCalls)
    }

    @Test
    fun `release closes the store and resets the semantic state for the r26 operations`() {
        val bridge = FakeBridge()
        val engine = MlEngine(bridge)
        engine.initialize("/data/models")
        engine.initSemantic("/data/models")
        engine.openFaceIndex("/data/face-index")
        assertTrue(engine.release())

        try {
            engine.putClip(1L, FloatArray(MlEngine.CLIP_EMBEDDING_DIM))
            fail("engine release must close the face-index store for r26 writes too")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals(0, bridge.storePutClipCalls)

        // The semantic state reset with the engine (the r26 contract):
        assertEquals(MlEngine.SemanticState.Uninitialized, engine.semanticState())
    }

    @Test
    fun `a library-less build rejects every r26 operation explicitly`() {
        val bridge = FakeBridge().apply { available = false }
        val engine = MlEngine(bridge)

        val rejects = listOf<() -> Any?>(
            { engine.clipImageEmbedding(byteArrayOf(1)) },
            { engine.clipTextEmbedding("a red bicycle") },
            { engine.putClip(1L, FloatArray(MlEngine.CLIP_EMBEDDING_DIM)) },
            { engine.deleteClipsForFiles(longArrayOf(1L)) },
            { engine.clipIndexedFileIds() },
            { engine.clipCount() },
            { engine.searchClips(FloatArray(MlEngine.CLIP_EMBEDDING_DIM), 1) },
            { engine.fillClipIndex() },
        )
        for (reject in rejects) {
            try {
                reject()
                fail("an r26 op on a library-less build must fail explicitly, not UnsatisfiedLinkError")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("not in this build"))
            }
        }
        assertEquals("no native call may happen without a library", 0, bridge.clipImageCalls)
        assertEquals(0, bridge.clipTextCalls)
        assertEquals(0, bridge.storePutClipCalls)
        assertEquals(0, bridge.storeDeleteClipsCalls)
        assertEquals(0, bridge.storeClipIndexedCalls)
        assertEquals(0, bridge.storeClipCountCalls)
        assertEquals(0, bridge.storeSearchClipsCalls)
        assertEquals(0, bridge.storeFillClipCalls)
    }

    @Test
    fun `a native r26 failure surfaces unchanged with its message`() {
        val bridge = object : MlEngine.Bridge {
            override val available = true
            override fun nativeInit(modelsDir: String) = true
            override fun nativeEngineInfo(): String? = null
            override fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray = FloatArray(0)
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
            override fun nativeStoreFaceClusters(): String =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreClusterFileIds(clusterId: String): LongArray = LongArray(0)
            override fun nativeStoreMergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean = true
            override fun nativeStoreRemoveFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean = true
            override fun nativeInitSemantic(modelsDir: String): Boolean = true
            override fun nativeClipImageEmbeddingJpeg(jpegBytes: ByteArray): FloatArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeClipTextEmbedding(text: String): FloatArray =
                throw IllegalStateException(
                    "semantic models are not initialized; call nativeInitSemantic(modelsDir) first"
                )
            override fun nativeStorePutClip(fileId: Long, embedding: FloatArray): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreDeleteClipsForFiles(fileIds: LongArray): Boolean =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreClipIndexedFileIds(): LongArray =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreClipCount(): Long =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreSearchClips(queryEmbedding: FloatArray, limit: Int): String =
                throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreSearchClipsWithin(
                queryEmbedding: FloatArray,
                maxDistance: Float,
                limit: Int,
            ): String = throw UnsupportedOperationException("unused in this test")
            override fun nativeStoreFillClipIndex(): String =
                throw UnsupportedOperationException("unused in this test")
        }
        val engine = MlEngine(bridge)
        try {
            engine.clipTextEmbedding("a red bicycle")
            fail("the native failure must surface")
        } catch (expected: IllegalStateException) {
            assertEquals(
                "semantic models are not initialized; call nativeInitSemantic(modelsDir) first",
                expected.message,
            )
        }
    }

    // ---- r51 (audit C-4): the process-global shared-state contract ----

    @Test
    fun `release on one instance is observed by every instance - the C-4 desync is dead`() {
        // One fake bridge shared by both instances: the injected bridge is
        // still per-instance (the seam), but the state it drives is
        // process-global --- the production shape (JniBridge everywhere).
        val bridge = FakeBridge()
        val a = MlEngine(bridge)
        val b = MlEngine(bridge)

        assertEquals(MlEngine.State.Ready, a.initialize("/data/models"))
        assertTrue(a.openFaceIndex("/data/index"))
        assertEquals("b observes a's initialize over the shared state", MlEngine.State.Ready, b.state())

        assertTrue(a.release())

        // The desync is dead: b no longer reports Ready over the engine a
        // just released...
        assertEquals(MlEngine.State.Uninitialized, b.state())
        // ...and b's next store op fails at the FACADE's own shared-state
        // gate, before any native call --- not as a Rust-side
        // IllegalStateException thrown at a stale-Ready instance.
        try {
            b.faceCount()
            fail("a store op over a released engine must fail at the facade's gate")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
        assertEquals("no native call may happen behind the facade's gate", 0, bridge.storeFaceCountCalls)

        // Idempotent re-init preserved: initialize is the unconditional
        // re-open it always was, now process-global --- b re-opens, a
        // observes it, and a second initialize while ALREADY open still
        // calls nativeInit (the control flow the state move did not touch).
        assertEquals(MlEngine.State.Ready, b.initialize("/data/models"))
        assertEquals(MlEngine.State.Ready, a.state())
        assertEquals(MlEngine.State.Ready, a.initialize("/data/models"))
        assertEquals(3, bridge.initCalls)
    }

    @Test
    fun `release on one instance resets the semantic half for every instance too`() {
        val bridge = FakeBridge()
        val a = MlEngine(bridge)
        val b = MlEngine(bridge)

        assertEquals(MlEngine.SemanticState.Ready, a.initSemantic("/data/models"))
        assertEquals(
            "b observes a's initSemantic over the shared state",
            MlEngine.SemanticState.Ready,
            b.semanticState(),
        )

        a.release()

        assertEquals(MlEngine.SemanticState.Uninitialized, b.semanticState())
        assertEquals(
            "re-init after release works for any instance",
            MlEngine.SemanticState.Ready,
            b.initSemantic("/data/models"),
        )
        assertEquals(MlEngine.SemanticState.Ready, a.semanticState())
    }
}
