package io.github.dukeoftoad.gallery.ml.engine

/**
 * Kotlin facade over the [MlEngineJni] bridge (r22, ADR-0020): a small,
 * honest state machine that keeps every caller away from the raw external
 * functions --- a library-less build reports [State.LibraryMissing]
 * instead of throwing [UnsatisfiedLinkError], inference is serialized
 * ([Synchronized] on top of the native serialization the contract
 * guarantees), and the engine's own failure shape
 * ([IllegalStateException] with the Rust message) is surfaced unchanged
 * rather than swallowed.
 *
 * A session is opened with [initialize] over the provisioner's install
 * root ([ModelProvisioner.modelsDir]); until then the state is
 * [State.Uninitialized], and after [release] it returns there (re-init
 * after release is part of the contract).
 */
class MlEngine(private val bridge: Bridge = JniBridge) {

    /**
     * One detected face: source-image pixel coordinates plus the
     * detector's score, exactly one row of the JNI contract's flat
     * `N * [x1, y1, x2, y2, score]` output.
     */
    data class FaceBox(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float)

    /** The coarse session state every UI surfaces. */
    sealed class State {
        /**
         * `libtoad_ml_jni.so` is not in this build (built only with the
         * engine property); no operation can run.
         */
        object LibraryMissing : State()

        /** Library loaded, no open session (before init or after release). */
        object Uninitialized : State()

        /** [initialize] succeeded; inference can run. */
        object Ready : State()

        /** The last [initialize] failed; [message] says why (no session). */
        data class Error(val message: String) : State()
    }

    /**
     * The four JNI entry points as an injectable seam. Production binds
     * [JniBridge] (delegating to [MlEngineJni]); the JVM suite binds
     * fakes --- the external functions cannot run on the JVM, so the
     * tests exercise THIS class's mapping/state logic, never the Rust
     * engine (and never a "pretend native").
     */
    interface Bridge {
        val available: Boolean
        fun nativeInit(modelsDir: String): Boolean
        fun nativeEngineInfo(): String?
        fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray
        fun nativeRelease(): Boolean
    }

    private object JniBridge : Bridge {
        override val available: Boolean get() = MlEngineJni.available
        override fun nativeInit(modelsDir: String): Boolean = MlEngineJni.nativeInit(modelsDir)
        override fun nativeEngineInfo(): String? = MlEngineJni.nativeEngineInfo()
        override fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray =
            MlEngineJni.nativeDetectFacesJpeg(jpegBytes)

        override fun nativeRelease(): Boolean = MlEngineJni.nativeRelease()
    }

    private var sessionOpen = false
    private var initError: String? = null

    /** Whether the engine library is present in this build. */
    val available: Boolean get() = bridge.available

    /** The current [State]; [State.LibraryMissing] dominates when set. */
    @Synchronized
    fun state(): State = when {
        !bridge.available -> State.LibraryMissing
        initError != null -> State.Error(initError!!)
        sessionOpen -> State.Ready
        else -> State.Uninitialized
    }

    /**
     * Opens a session over [modelsDir]. Returns the new [State]:
     * [State.Ready] on a true [Bridge.nativeInit], [State.Error] on a
     * false one, [State.LibraryMissing] when the build carries no
     * library. An IllegalStateException from the native layer (panic
     * surfaced per the contract) propagates unchanged --- callers that
     * want a message read it; the state stays without a session.
     */
    @Synchronized
    fun initialize(modelsDir: String): State {
        if (!bridge.available) return State.LibraryMissing
        return if (bridge.nativeInit(modelsDir)) {
            sessionOpen = true
            initError = null
            State.Ready
        } else {
            sessionOpen = false
            initError = "nativeInit failed for $modelsDir"
            State.Error(initError!!)
        }
    }

    /**
     * The engine's build/session identity, or null when uninitialized
     * (and always null on a library-less build --- there is no engine to
     * describe).
     */
    @Synchronized
    fun engineInfo(): String? {
        if (!bridge.available) return null
        return bridge.nativeEngineInfo()
    }

    /**
     * Face detection over one JPEG's bytes. Returns the flat output
     * mapped to [FaceBox]es in source-image pixel coordinates; an empty
     * list means no faces. Throws IllegalStateException with the Rust
     * message when the engine is uninitialized or the call fails, and
     * IllegalStateException (not UnsatisfiedLinkError) on a library-less
     * build.
     */
    @Synchronized
    fun detectFacesJpeg(jpegBytes: ByteArray): List<FaceBox> {
        if (!bridge.available) {
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        return faceBoxes(bridge.nativeDetectFacesJpeg(jpegBytes))
    }

    /**
     * Frees the session: true when one was freed, false when there was
     * none (also false on a library-less build). Idempotent; the state
     * returns to [State.Uninitialized] and [initialize] works again.
     */
    @Synchronized
    fun release(): Boolean {
        if (!bridge.available) return false
        val freed = bridge.nativeRelease()
        sessionOpen = false
        initError = null
        return freed
    }

    internal companion object {
        /**
         * Maps the JNI contract's flat `N * 5` output into boxes; a length
         * that is not a multiple of 5 is a broken engine contract and
         * fails as IllegalStateException (the facade's own failure shape).
         */
        internal fun faceBoxes(flat: FloatArray): List<FaceBox> {
            check(flat.size % 5 == 0) {
                "engine returned ${flat.size} floats; expected a multiple of 5 (N * [x1, y1, x2, y2, score])"
            }
            return flat.toList().chunked(5).map { FaceBox(it[0], it[1], it[2], it[3], it[4]) }
        }
    }
}
