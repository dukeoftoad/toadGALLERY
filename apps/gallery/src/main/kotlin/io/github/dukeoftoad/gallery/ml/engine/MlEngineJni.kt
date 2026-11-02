package io.github.dukeoftoad.gallery.ml.engine

/**
 * The Kotlin half of the r22 JNI bridge to the vendored Ente engine: the
 * native library `libtoad_ml_jni.so` (crate `toad-ml-jni`, Rust counterpart
 * in `engine/`), loaded via [System.loadLibrary]. The declarations below
 * ARE the contract --- the Rust side registers their exact counterparts;
 * names, signatures and return shapes must not drift.
 *
 * r23 adds six more entry points on the same conventions: embeddings
 * (`nativeDetectFaceEmbeddingsJpeg`, flat stride `5 + 192`) and the
 * app-private face-index store lifecycle (`nativeStoreOpen` /
 * `nativeStorePutFace` / `nativeStoreFaceCount` /
 * `nativeStoreCluster` / `nativeStoreClose`) over the vendored
 * `ente-photos-ml` persistence + clustering stack.
 *
 * r24 adds the store maintenance pair the background queue drives:
 * `nativeStoreIndexedFaceFileIds` (which files carry current-version face
 * rows) and `nativeStoreDeleteFacesForFiles` (the per-file invalidation
 * delete behind revision-driven re-indexing and orphan cleanup).
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
     * none. Idempotent; a subsequent [nativeInit] re-opens a session. Also
     * closes the face-index store (r23) if one is open.
     */
    @JvmStatic
    external fun nativeRelease(): Boolean

    // ---- r23: face embeddings + the app-private face-index store ----

    /**
     * Runs face detection + embedding over one JPEG's bytes. Returns
     * `N * (5 + 192)` flat row-major floats: per face
     * `[x1, y1, x2, y2, score, e0..e191]` in SOURCE-IMAGE PIXEL
     * coordinates with the 192-dimensional MobileFaceNet embedding
     * appended. An empty array means "no faces". Throws
     * IllegalStateException when uninitialized, failed, or the runtime
     * embedding dimension is not 192 (stride 197 violated).
     */
    @JvmStatic
    external fun nativeDetectFaceEmbeddingsJpeg(jpegBytes: ByteArray): FloatArray

    /**
     * Opens the face-index store at `<dir>/ml-index.sqlite` (creating it
     * via its own migrations). Idempotent: an already-open store returns
     * true. Returns true on success; failure throws
     * IllegalStateException.
     */
    @JvmStatic
    external fun nativeStoreOpen(dir: String): Boolean

    /**
     * Inserts one face row for [fileId] with the given 192-float
     * embedding. Returns the face row id (> 0). Throws
     * IllegalArgumentException when the embedding dimension is not 192
     * and IllegalStateException on a closed store or DB failure.
     */
    @JvmStatic
    external fun nativeStorePutFace(fileId: Long, embedding: FloatArray): Long

    /**
     * Total face rows in the store. Throws IllegalStateException when
     * the store is not open.
     */
    @JvmStatic
    external fun nativeStoreFaceCount(): Long

    /**
     * Runs the vendored upstream face clustering over the faces in the
     * store at [distanceThreshold] and returns a compact JSON summary:
     * `{"faces":N,"clusters":M,"sizes":[s1,s2,...]}` with sizes in
     * descending order. Throws IllegalStateException on failure or a
     * closed store.
     */
    @JvmStatic
    external fun nativeStoreCluster(distanceThreshold: Double): String

    /**
     * The file IDs that currently carry face rows at the vendored pin's
     * model version (r24), via upstream's own indexed-files read. An
     * empty array means no indexed files. Throws IllegalStateException
     * when the store is not open.
     */
    @JvmStatic
    external fun nativeStoreIndexedFaceFileIds(): LongArray

    /**
     * Deletes every face row for [fileIds] (r24), via upstream's own
     * chunked, transactional per-file delete. An empty array is a no-op.
     * Returns true on success; throws IllegalStateException on a closed
     * store or DB failure.
     */
    @JvmStatic
    external fun nativeStoreDeleteFacesForFiles(fileIds: LongArray): Boolean

    /**
     * Closes the face-index store. Idempotent; returns true.
     */
    @JvmStatic
    external fun nativeStoreClose(): Boolean
}
