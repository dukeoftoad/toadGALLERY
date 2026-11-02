package io.github.dukeoftoad.gallery.ml.engine

/**
 * The Kotlin half of the r22 JNI bridge to the vendored Ente engine: the
 * native library `libtoad_ml_jni.so` (crate `toad-ml-jni`, Rust counterpart
 * in `engine/`), loaded via [System.loadLibrary]. The four declarations
 * below ARE the contract --- the Rust side registers their exact
 * counterparts; names, signatures and return shapes must not drift.
 *
 * Library packaging: the `.so` is built ONLY with the engine property and
 * is ABSENT from plain CI/dev builds. A missing library is a graceful
 * state, never a crash: the try-load in the `init` block catches
 * [UnsatisfiedLinkError] once and reports it through [available]
 * (`false`), so touching this object on a library-less build --- including
 * class-load on the JVM unit-test runtime --- can never throw.
 *
 * Rust guarantees (the r22 contract): panics surface as
 * [IllegalStateException] with the Rust message; inference is serialized
 * on the native side (the [MlEngine] facade synchronizes on top);
 * re-initialization after [nativeRelease] works.
 */
object MlEngineJni {

    private const val LIBRARY = "toad_ml_jni"

    /**
     * True when `libtoad_ml_jni.so` loaded in THIS process: engine builds
     * report `true`; plain builds (and the JVM test runtime) report
     * `false`, and every caller degrades through
     * [MlEngine.State.LibraryMissing] instead of crashing.
     */
    val available: Boolean

    init {
        available = try {
            System.loadLibrary(LIBRARY)
            true
        } catch (_: UnsatisfiedLinkError) {
            // No packaged .so (plain build) or no loadable ABI: reported,
            // not thrown --- see the class KDoc.
            false
        }
    }

    /**
     * Opens an engine session over [modelsDir] (the provisioner's install
     * root). True on success; false when the session could not be created
     * (e.g. models missing/stale). Panics surface as
     * IllegalStateException.
     */
    @JvmStatic
    external fun nativeInit(modelsDir: String): Boolean

    /** Build/session identity string, or null when uninitialized. */
    @JvmStatic
    external fun nativeEngineInfo(): String?

    /**
     * Runs face detection over one JPEG's bytes. Returns N*5 floats in
     * SOURCE-IMAGE PIXEL coordinates --- [x1, y1, x2, y2, score, ...] ---
     * one flat run of 5 per face; an empty array means "no faces".
     * Throws IllegalStateException when uninitialized or failed.
     */
    @JvmStatic
    external fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray

    /**
     * Frees the engine session: true if one was freed, false if there was
     * none. Idempotent; a subsequent [nativeInit] re-opens a session.
     */
    @JvmStatic
    external fun nativeRelease(): Boolean
}
