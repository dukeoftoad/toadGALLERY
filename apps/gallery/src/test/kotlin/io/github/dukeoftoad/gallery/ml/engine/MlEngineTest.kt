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
}
