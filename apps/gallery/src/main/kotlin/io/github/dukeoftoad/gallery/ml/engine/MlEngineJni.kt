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
 * r25 adds the People read/management paths over the same store:
 * `nativeStoreFaceClusters` (the live cluster list as JSON),
 * `nativeStoreClusterFileIds` (a cluster's current file ids),
 * `nativeStoreMergeFaceClusters` (merge two people; a source equal to
 * its target throws IllegalArgumentException before any store mutation)
 * and `nativeStoreRemoveFacesFromCluster` (remove a photo from a
 * person). Manual edits persist only until the next clustering pass
 * recomputes assignments --- the r25 contract carries that caveat
 * honestly.
 *
 * r26 adds the semantic (Magic Search) surface: the CLIP session
 * (`nativeInitSemantic` --- registers + warms up the pinned MobileCLIP-S2
 * image/text encoders and the BPE vocab ON TOP of the face engine; a
 * failed warm-up restores a consistent face-only engine), the two CLIP
 * encoders (`nativeClipImageEmbeddingJpeg` /
 * `nativeClipTextEmbedding` --- 512-float vectors; a runtime dimension
 * drift off 512 is an IllegalStateException), and the CLIP vector-index
 * store functions over the SAME face-index store
 * (`nativeStorePutClip` with the putFace-style dimension guard,
 * `nativeStoreDeleteClipsForFiles`, `nativeStoreClipIndexedFileIds`,
 * `nativeStoreClipCount`, `nativeStoreSearchClips` and
 * `nativeStoreFillClipIndex` --- both JSON; the search result preserves
 * the vecdb's own order verbatim).
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

    // ---- r25: the People read/management paths ----

    /**
     * The live cluster list for the People UI (r25), as the JSON array
     * `[{"cluster_id":"…","count":12,"cover_file_id":4813},…]`
     * (`cover_file_id` is JSON null when the cluster's top face id has no
     * parsable leading file-id segment), sorted by count descending then
     * cluster id ascending. Counts come from the LIVE assignments table
     * --- NOT the clustering pass's summaries --- so manual merges and
     * removals reflect immediately. Throws IllegalStateException when
     * the store is not open or the read fails.
     */
    @JvmStatic
    external fun nativeStoreFaceClusters(): String

    /**
     * The file IDs whose faces currently sit in [clusterId] (r25), via
     * upstream's own distinct-file read over the live assignments. An
     * empty array means no files --- including an unknown cluster id.
     * Throws IllegalStateException when the store is not open or the
     * read fails.
     */
    @JvmStatic
    external fun nativeStoreClusterFileIds(clusterId: String): LongArray

    /**
     * Moves every face of [sourceClusterId] into [targetClusterId] (r25)
     * --- "merge these two people" --- via upstream's own atomic batch
     * upsert. An empty source is a no-op returning true. Throws
     * IllegalArgumentException when source and target are equal (before
     * any store mutation) and IllegalStateException on a closed store or
     * DB failure. Manual merges persist only until the next clustering
     * pass recomputes assignments.
     */
    @JvmStatic
    external fun nativeStoreMergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean

    /**
     * Removes [fileIds]' faces from [clusterId] (r25) --- "remove this
     * photo from this person" / the split primitive --- via upstream's
     * own atomic batch delete. Only faces currently assigned to
     * [clusterId] are dropped (a file with faces in other clusters keeps
     * those); the face rows themselves stay for the next clustering pass.
     * An empty array is a no-op returning true. Throws
     * IllegalStateException when the store is not open or any
     * read/write fails.
     */
    @JvmStatic
    external fun nativeStoreRemoveFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean

    // ---- r26: the semantic (CLIP) session + the CLIP vector-index store ----

    /**
     * Initializes the semantic (CLIP) half of an ALREADY-initialized
     * engine session over [modelsDir]: the three pinned files (CLIP image
     * encoder, CLIP text encoder, BPE vocab) are validated (exact catalog
     * sizes) and the combined face + CLIP model paths registered with the
     * runtime, then a warm-up CLIP inference builds the image session so
     * init fails fast. True on success (idempotent: an already-semantic
     * engine returns true without reloading). A failed warm-up leaves a
     * consistent face-only engine. Throws IllegalStateException when no
     * engine session is open or the pipeline fails.
     */
    @JvmStatic
    external fun nativeInitSemantic(modelsDir: String): Boolean

    /**
     * Runs the CLIP image encoder over one JPEG's bytes. Returns the
     * 512-float L2-normalized MobileCLIP-S2 embedding. Throws
     * IllegalStateException when the engine (or its semantic half) is
     * not initialized, the call fails, or the runtime embedding
     * dimension is not 512.
     */
    @JvmStatic
    external fun nativeClipImageEmbeddingJpeg(jpegBytes: ByteArray): FloatArray

    /**
     * Runs the CLIP text encoder over [text] (upstream's run_clip_text
     * with the pinned text model + BPE vocab). Returns the 512-float
     * embedding. Throws IllegalStateException when the engine (or its
     * semantic half) is not initialized, the call fails, or the runtime
     * embedding dimension is not 512.
     */
    @JvmStatic
    external fun nativeClipTextEmbedding(text: String): FloatArray

    /**
     * Upserts one CLIP embedding row for [fileId] (the SQLite row AND
     * the live vector-index key together; a re-insert replaces). Returns
     * true on success. Throws IllegalArgumentException when the
     * embedding dimension is not 512 (before any store access) and
     * IllegalStateException on a closed store or DB failure.
     */
    @JvmStatic
    external fun nativeStorePutClip(fileId: Long, embedding: FloatArray): Boolean

    /**
     * Deletes every CLIP row for [fileIds] (rows + vector-index keys;
     * an empty array is a no-op). Returns true on success; throws
     * IllegalStateException on a closed store or DB failure.
     */
    @JvmStatic
    external fun nativeStoreDeleteClipsForFiles(fileIds: LongArray): Boolean

    /**
     * The file IDs that currently carry CLIP rows at the vendored pin's
     * model version, sorted ascending. An empty array means no indexed
     * files. Throws IllegalStateException when the store is not open.
     */
    @JvmStatic
    external fun nativeStoreClipIndexedFileIds(): LongArray

    /**
     * Total CLIP rows in the store. Throws IllegalStateException when
     * the store is not open.
     */
    @JvmStatic
    external fun nativeStoreClipCount(): Long

    /**
     * Runs the top-K vector search over the CLIP index and returns the
     * JSON array `[{"file_id":123,"distance":0.42},…]` in the vecdb's
     * own order (nearest first; preserved verbatim --- never re-sorted).
     * Throws IllegalStateException when the store is not open, the
     * search fails, or the query embedding dimension is not 512.
     */
    @JvmStatic
    external fun nativeStoreSearchClips(queryEmbedding: FloatArray, limit: Int): String

    /**
     * Fills the CLIP vector index from the SQLite rows and returns the
     * JSON report `{"outcome":"Completed"|"AlreadyFilled"|"Superseded",
     * "rows":N,"indexed":M,"skipped":K,"resumed":true|false}`. Throws
     * IllegalStateException when the store is not open or the fill
     * fails.
     */
    @JvmStatic
    external fun nativeStoreFillClipIndex(): String

    /**
     * Closes the face-index store. Idempotent; returns true.
     */
    @JvmStatic
    external fun nativeStoreClose(): Boolean
}
