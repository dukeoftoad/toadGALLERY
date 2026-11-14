package com.toadgallery.gallery.ml.engine

import org.json.JSONArray
import org.json.JSONObject

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
 *
 * r23 extends the same discipline with (a) the embeddings variant of the
 * face pipeline ([detectFaceEmbeddings] --- the r22 detector output plus
 * the 192-float MobileFaceNet embedding, flat stride 197 on the wire) and
 * (b) the app-private face-index store lifecycle ([openFaceIndex] /
 * [putFace] / [faceCount] / [clusterFaces] / [closeFaceIndex]): a
 * separate open/closed state under the same synchronization, released
 * together with the engine by [release] (the Rust side closes the store
 * on `nativeRelease`), and re-openable after --- the same idempotency
 * rules the r22 session follows.
 *
 * r24 adds the store maintenance pair the background queue drives over
 * the SAME open-store state machine: [indexedFaceFileIds] (which files
 * carry current-model-version face rows --- the resumability read) and
 * [deleteFacesForFiles] (the per-file invalidation delete behind
 * revision-driven re-indexing and orphan cleanup).
 *
 * r25 adds the People read/management paths over the SAME open-store
 * state machine: [faceClusters] (the live cluster list --- counts are
 * read from the live assignments table, NOT the clustering pass's
 * summaries, so manual edits reflect immediately), [clusterFileIds] (a
 * person's current file ids), [mergeFaceClusters] (merge two people;
 * source==target is an IllegalArgumentException BEFORE any native call,
 * mirrored by a Rust-side guard as defense in depth) and
 * [removeFacesFromCluster] (remove a photo from a person --- the split
 * primitive). Honest caveat, not an overpromise: manual edits persist
 * only until the next [clusterFaces] pass recomputes assignments from
 * the embeddings --- they are not sticky.
 *
 * r26 adds the semantic (Magic Search) layer over the same two state
 * machines: [initSemantic] / [semanticState] (a THIRD open/closed state
 * --- the CLIP half of an engine session, tracked like
 * [initialize]'s error so [semanticState] stays honest, reset by
 * [release] with the engine), the CLIP encoders ([clipImageEmbedding] /
 * [clipTextEmbedding] --- 512-float vectors; the engine's own
 * IllegalStateException propagates unchanged, including its "call
 * nativeInitSemantic first" message), and the CLIP vector-index store
 * functions over the SAME face-index store ([putClip] /
 * [deleteClipsForFiles] / [clipIndexedFileIds] / [clipCount] /
 * [searchClips] / [fillClipIndex]): clip rows live in the same
 * `ml-index.sqlite`, so the r23 open-store gate governs them too.
 *
 * r39 makes the session halves SYMMETRIC and adds the distance-cut
 * search: [initSemantic] no longer presupposes an [initialize]d engine
 * --- the native layer installs a SEMANTIC-ONLY session from scratch
 * (the r26 round's documented FACE-model requirement, dropped; see
 * `docs/phase-5/ente-exit.md`) --- and [initialize] upgrades such a
 * session with the face half in place. The facade's two state machines
 * stay independent and honest: [state] reports the FACE half,
 * [semanticState] the CLIP half, and neither claims the other. On a
 * semantic-only session the face calls fail with the engine's own
 * dedicated message (carried verbatim). [searchClipsWithin] is the
 * r39 membership search: the same top-K over the same index, cut by
 * cosine distance inside the vecdb (upstream's own `max_distance`).
 *
 * r51 (audit C-4) centralizes what the rounds above documented
 * piecemeal: the native engine behind the bridge is ONE PROCESS-GLOBAL
 * singleton (the Rust side's `engine::ENGINE` / `store::STORE` OnceLock
 * statics), but the facade tracked its open/ready/error state per
 * INSTANCE with per-instance monitors --- one instance's [release] left
 * another still reporting [State.Ready] over a released engine while
 * its next native call threw (workers construct an engine per run;
 * adapters, the self-test and the ViewModels hold their own). The five
 * state flags now live in shared static state (`SharedEngineState`, the
 * bottom of this file) behind ONE shared monitor: every instance reads
 * and writes the same process-wide truth, so a release, close or init
 * outcome on ANY instance is immediately visible to ALL of them. The
 * class stays instantiable with unchanged signatures (zero call-site
 * changes), and [initialize] / [initSemantic] remain the unconditional
 * re-opens they always were --- which over shared state is exactly the
 * idempotent re-init contract the per-run workers and the Settings
 * self-test dogfood.
 */
class MlEngine(private val bridge: Bridge = JniBridge) {

    /**
     * One detected face: source-image pixel coordinates plus the
     * detector's score, exactly one row of the JNI contract's flat
     * `N * [x1, y1, x2, y2, score]` output.
     */
    data class FaceBox(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val score: Float)

    /**
     * One detected face WITH its 192-float embedding (r23): the r22
     * [FaceBox] coordinates/score in source-image pixel space plus the
     * MobileFaceNet embedding, exactly one row of the JNI contract's flat
     * `N * [x1, y1, x2, y2, score, e0..e191]` output. Array equality is
     * content-based (a [FaceBox] is value-equal; the embedding is
     * compared with [FloatArray.contentEquals]).
     */
    data class FaceEmbedding(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val embedding: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean =
            other is FaceEmbedding &&
                x1 == other.x1 && y1 == other.y1 && x2 == other.x2 && y2 == other.y2 &&
                score == other.score && embedding.contentEquals(other.embedding)

        override fun hashCode(): Int =
            31 * (31 * (31 * (31 * (31 * x1.toRawBits() + y1.toRawBits()) + x2.toRawBits()) + y2.toRawBits()) + score.toRawBits()) +
                embedding.contentHashCode()
    }

    /**
     * The compact `nativeStoreCluster` summary, parsed from the JSON
     * string `{"faces":N,"clusters":M,"sizes":[s1,s2,...]}` (sizes in
     * descending order as the Rust side emits them; preserved verbatim
     * here --- the parse never re-sorts).
     */
    data class ClusterSummary(val faces: Int, val clusters: Int, val sizes: List<Int>)

    /**
     * One row of the People UI's cluster list (r25): the cluster id, its
     * LIVE face count (manual merges/exclusions reflect immediately),
     * and the cover file id --- the file of the cluster's highest-scored
     * face, or null when the cluster has no parsable cover. Plain
     * content equality.
     */
    data class FaceCluster(val clusterId: String, val faceCount: Int, val coverFileId: Long?)

    /** One CLIP vector-search match (r26): the matched file and its vector distance (vecdb order, never re-sorted). */
    data class ClipMatch(val fileId: Long, val distance: Float)

    /** The `nativeStoreFillClipIndex` report (r26). outcome is one of "Completed" / "AlreadyFilled" / "Superseded". */
    data class ClipFillReport(val outcome: String, val rows: Int, val indexed: Int, val skipped: Int, val resumed: Boolean)

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
     * The semantic (CLIP) half's coarse state (r26), tracked like [State]
     * so callers surface it directly. [LibraryMissing] dominates;
     * [Uninitialized] covers both "the engine session itself is missing
     * (initialize first)" and "released".
     */
    sealed class SemanticState {
        /**
         * `libtoad_ml_jni.so` is not in this build (built only with the
         * engine property); no semantic operation can run.
         */
        object LibraryMissing : SemanticState()

        /** Engine session not initialized (`initialize` first) or released. */
        object Uninitialized : SemanticState()

        /** [initSemantic] succeeded; CLIP embeddings can run. */
        object Ready : SemanticState()

        /** The last [initSemantic] failed; [message] says why (no semantic half). */
        data class Error(val message: String) : SemanticState()
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

        // ---- r23 ----
        fun nativeDetectFaceEmbeddingsJpeg(jpegBytes: ByteArray): FloatArray
        fun nativeStoreOpen(dir: String): Boolean
        fun nativeStorePutFace(fileId: Long, embedding: FloatArray): Long
        fun nativeStoreFaceCount(): Long
        fun nativeStoreCluster(distanceThreshold: Double): String
        fun nativeStoreClose(): Boolean

        // ---- r24 ----
        fun nativeStoreIndexedFaceFileIds(): LongArray
        fun nativeStoreDeleteFacesForFiles(fileIds: LongArray): Boolean

        // ---- r25 ----
        fun nativeStoreFaceClusters(): String
        fun nativeStoreClusterFileIds(clusterId: String): LongArray
        fun nativeStoreMergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean
        fun nativeStoreRemoveFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean

        // ---- r26 ----
        fun nativeInitSemantic(modelsDir: String): Boolean
        fun nativeClipImageEmbeddingJpeg(jpegBytes: ByteArray): FloatArray
        fun nativeClipTextEmbedding(text: String): FloatArray
        fun nativeStorePutClip(fileId: Long, embedding: FloatArray): Boolean
        fun nativeStoreDeleteClipsForFiles(fileIds: LongArray): Boolean
        fun nativeStoreClipIndexedFileIds(): LongArray
        fun nativeStoreClipCount(): Long
        fun nativeStoreSearchClips(queryEmbedding: FloatArray, limit: Int): String
        fun nativeStoreFillClipIndex(): String

        // ---- r39 ----
        fun nativeStoreSearchClipsWithin(
            queryEmbedding: FloatArray,
            maxDistance: Float,
            limit: Int,
        ): String
    }

    private object JniBridge : Bridge {
        /**
         * The r75 lockstep defense: a LOADED-but-MISMATCHED
         * `libtoad_ml_jni.so` raises [UnsatisfiedLinkError] (an [Error],
         * NOT a [RuntimeException]) on the first native call whose
         * symbol the library can't bind --- the same mixed-version
         * packaging that breaks `libonnxruntime4j_jni.so` against a
         * staged `libonnxruntime.so` (see `engine/scripts/build-android.sh`
         * and `EditAiOrtRuntime`). The class KDoc already promises
         * *"IllegalStateException (not UnsatisfiedLinkError) on a
         * library-less build"*; this helper extends that promise to the
         * loaded-but-mismatched build, so no caller (the `finally` in
         * `release()` included) ever sees an [Error] escape --- the
         * facade's documented failure shape holds in every build.
         */
        private inline fun <T> guarded(call: () -> T): T = try {
            call()
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "engine library present but unbindable (native/JNI symbol mismatch --- " +
                    "the packaged libtoad_ml_jni.so does not match this Kotlin bridge): ${e.message}",
                e,
            )
        }

        override val available: Boolean get() = MlEngineJni.available
        override fun nativeInit(modelsDir: String): Boolean = guarded { MlEngineJni.nativeInit(modelsDir) }
        override fun nativeEngineInfo(): String? = guarded { MlEngineJni.nativeEngineInfo() }
        override fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray =
            guarded { MlEngineJni.nativeDetectFacesJpeg(jpegBytes) }

        override fun nativeRelease(): Boolean = guarded { MlEngineJni.nativeRelease() }

        // ---- r23 ----
        override fun nativeDetectFaceEmbeddingsJpeg(jpegBytes: ByteArray): FloatArray =
            guarded { MlEngineJni.nativeDetectFaceEmbeddingsJpeg(jpegBytes) }

        override fun nativeStoreOpen(dir: String): Boolean = guarded { MlEngineJni.nativeStoreOpen(dir) }
        override fun nativeStorePutFace(fileId: Long, embedding: FloatArray): Long =
            guarded { MlEngineJni.nativeStorePutFace(fileId, embedding) }

        override fun nativeStoreFaceCount(): Long = guarded { MlEngineJni.nativeStoreFaceCount() }
        override fun nativeStoreCluster(distanceThreshold: Double): String =
            guarded { MlEngineJni.nativeStoreCluster(distanceThreshold) }

        override fun nativeStoreClose(): Boolean = guarded { MlEngineJni.nativeStoreClose() }

        // ---- r24 ----
        override fun nativeStoreIndexedFaceFileIds(): LongArray =
            guarded { MlEngineJni.nativeStoreIndexedFaceFileIds() }

        override fun nativeStoreDeleteFacesForFiles(fileIds: LongArray): Boolean =
            guarded { MlEngineJni.nativeStoreDeleteFacesForFiles(fileIds) }

        // ---- r25 ----
        override fun nativeStoreFaceClusters(): String = guarded { MlEngineJni.nativeStoreFaceClusters() }

        override fun nativeStoreClusterFileIds(clusterId: String): LongArray =
            guarded { MlEngineJni.nativeStoreClusterFileIds(clusterId) }

        override fun nativeStoreMergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean =
            guarded { MlEngineJni.nativeStoreMergeFaceClusters(sourceClusterId, targetClusterId) }

        override fun nativeStoreRemoveFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean =
            guarded { MlEngineJni.nativeStoreRemoveFacesFromCluster(clusterId, fileIds) }

        // ---- r26 ----
        override fun nativeInitSemantic(modelsDir: String): Boolean =
            guarded { MlEngineJni.nativeInitSemantic(modelsDir) }

        override fun nativeClipImageEmbeddingJpeg(jpegBytes: ByteArray): FloatArray =
            guarded { MlEngineJni.nativeClipImageEmbeddingJpeg(jpegBytes) }

        override fun nativeClipTextEmbedding(text: String): FloatArray =
            guarded { MlEngineJni.nativeClipTextEmbedding(text) }

        override fun nativeStorePutClip(fileId: Long, embedding: FloatArray): Boolean =
            guarded { MlEngineJni.nativeStorePutClip(fileId, embedding) }

        override fun nativeStoreDeleteClipsForFiles(fileIds: LongArray): Boolean =
            guarded { MlEngineJni.nativeStoreDeleteClipsForFiles(fileIds) }

        override fun nativeStoreClipIndexedFileIds(): LongArray =
            guarded { MlEngineJni.nativeStoreClipIndexedFileIds() }

        override fun nativeStoreClipCount(): Long = guarded { MlEngineJni.nativeStoreClipCount() }

        override fun nativeStoreSearchClips(queryEmbedding: FloatArray, limit: Int): String =
            guarded { MlEngineJni.nativeStoreSearchClips(queryEmbedding, limit) }

        override fun nativeStoreFillClipIndex(): String = guarded { MlEngineJni.nativeStoreFillClipIndex() }

        // ---- r39 ----
        override fun nativeStoreSearchClipsWithin(
            queryEmbedding: FloatArray,
            maxDistance: Float,
            limit: Int,
        ): String = guarded { MlEngineJni.nativeStoreSearchClipsWithin(queryEmbedding, maxDistance, limit) }
    }

    // The five facade-state flags are PROCESS-GLOBAL (r51, audit C-4): they
    // live in SharedEngineState (bottom of this file) behind its one shared
    // lock, because the native engine they describe is a process-wide
    // singleton --- per-instance copies could disagree with the native truth
    // and with each other.

    /**
     * Store gate for the r23 state machine. The state it guards is the
     * SHARED faceIndexOpen flag, so callers must hold SharedEngineState's
     * lock --- and every caller does (a `synchronized(SharedEngineState.lock)`
     * block), the discipline the per-instance @Synchronized monitors had
     * before r51.
     */
    private fun requireFaceIndexOpen() {
        // Library-missing dominates: on a library-less build every store op
        // must fail with the build-shape message, never the state-machine one.
        if (!bridge.available) {
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        check(SharedEngineState.faceIndexOpen) { FACE_INDEX_NOT_OPEN }
    }

    /** Whether the engine library is present in this build. */
    val available: Boolean get() = bridge.available

    /**
     * The current [State]; [State.LibraryMissing] dominates when set.
     * Process-global since r51: this reports the SHARED session's state,
     * so a [release] on any instance is visible here immediately.
     */
    fun state(): State = synchronized(SharedEngineState.lock) {
        when {
            !bridge.available -> State.LibraryMissing
            SharedEngineState.initError != null -> State.Error(SharedEngineState.initError!!)
            SharedEngineState.sessionOpen -> State.Ready
            else -> State.Uninitialized
        }
    }

    /**
     * Opens a session over [modelsDir]. Returns the new [State]:
     * [State.Ready] on a true [Bridge.nativeInit], [State.Error] on a
     * false one, [State.LibraryMissing] when the build carries no
     * library. An IllegalStateException from the native layer (panic
     * surfaced per the contract) propagates unchanged --- callers that
     * want a message read it; the state stays without a session. The
     * call is an unconditional re-open over the process-global session
     * (r51): the outcome is immediately visible to every instance.
     */
    fun initialize(modelsDir: String): State = synchronized(SharedEngineState.lock) {
        if (!bridge.available) return State.LibraryMissing
        if (bridge.nativeInit(modelsDir)) {
            SharedEngineState.sessionOpen = true
            SharedEngineState.initError = null
            State.Ready
        } else {
            SharedEngineState.sessionOpen = false
            SharedEngineState.initError = "nativeInit failed for $modelsDir"
            State.Error(SharedEngineState.initError!!)
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
     * Process-global since r51: the freed state is shared, so EVERY
     * instance's [state] leaves Ready --- no instance keeps reporting a
     * session the native side no longer has (the C-4 desync, dead).
     */
    /**
     * Frees the session: true when one was freed, false when there was
     * none (also false on a library-less build). Idempotent; the state
     * returns to [State.Uninitialized] and [initialize] works again.
     * Process-global since r51: the freed state is shared, so EVERY
     * instance's [state] leaves Ready --- no instance keeps reporting a
     * session the native side no longer has (the C-4 desync, dead).
     *
     * TOTAL since r75: [release] is called from `finally` blocks (the
     * AI dogfood self-test, the index queues' teardown), so it MUST NOT
     * throw --- an exception here masks the original result and escapes
     * as the user-visible crash. The r75 lockstep failure (a
     * loaded-but-mismatched `libtoad_ml_jni.so`) makes
     * [nativeRelease] raise [IllegalStateException] via the bridge's
     * `guarded` helper; that is caught here, the shared state is reset
     * anyway (the native session is already unusable), and `false` is
     * returned --- the next `initialize` re-opens from a clean slate.
     */
    fun release(): Boolean = synchronized(SharedEngineState.lock) {
        if (!bridge.available) return false
        // r75: a broken native release must not escape from a finally.
        val freed = try {
            bridge.nativeRelease()
        } catch (_: IllegalStateException) {
            // The library is loaded but unbindable (the lockstep failure):
            // the session is already dead on the native side, so the
            // facade resets to Uninitialized and reports "nothing freed".
            false
        }
        SharedEngineState.sessionOpen = false
        SharedEngineState.initError = null
        // nativeRelease closes the face-index store too (the r23 contract);
        // the facade state follows without a second native call.
        SharedEngineState.faceIndexOpen = false
        // It also tears the whole engine --- the semantic half with it (the
        // r26 contract); same discipline, no second native call.
        SharedEngineState.semanticReady = false
        SharedEngineState.semanticError = null
        freed
    }

    /**
     * Face detection + embedding over one JPEG's bytes (r23). Returns the
     * flat stride-197 output mapped to [FaceEmbedding] rows in
     * source-image pixel coordinates; an empty list means no faces.
     * Throws IllegalStateException with the Rust message when the engine
     * is uninitialized or the call fails, and IllegalStateException (not
     * UnsatisfiedLinkError) on a library-less build.
     */
    @Synchronized
    fun detectFaceEmbeddings(jpegBytes: ByteArray): List<FaceEmbedding> {
        if (!bridge.available) {
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        return faceEmbeddings(bridge.nativeDetectFaceEmbeddingsJpeg(jpegBytes))
    }

    /**
     * Opens the app-private face-index store at `<dir>/ml-index.sqlite`
     * (r23). True on success (idempotent for an already-open store).
     * Throws IllegalStateException on a library-less build or when the
     * native open fails.
     */
    fun openFaceIndex(dir: String): Boolean = synchronized(SharedEngineState.lock) {
        if (!bridge.available) {
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        val opened = bridge.nativeStoreOpen(dir)
        SharedEngineState.faceIndexOpen = opened
        opened
    }

    /**
     * Inserts one face row for [fileId] (r23), returning the face row id
     * (> 0). Throws IllegalArgumentException when [embedding] is not
     * [FACE_EMBEDDING_DIM] floats (checked HERE, before any native call)
     * and IllegalStateException when the store is not open or the native
     * insert fails.
     */
    fun putFace(fileId: Long, embedding: FloatArray): Long = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        require(embedding.size == FACE_EMBEDDING_DIM) {
            "embedding must be $FACE_EMBEDDING_DIM floats; got ${embedding.size}"
        }
        bridge.nativeStorePutFace(fileId, embedding)
    }

    /**
     * Total face rows in the store (r23). Throws IllegalStateException
     * when the store is not open (or the build is library-less).
     */
    fun faceCount(): Long = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        bridge.nativeStoreFaceCount()
    }

    /**
     * Clusters the faces in the store at [distanceThreshold] (r23) and
     * parses the compact JSON summary into [ClusterSummary]. Throws
     * IllegalStateException when the store is not open, the native call
     * fails, or the JSON shape is not the pinned
     * `{"faces":N,"clusters":M,"sizes":[...]}`.
     */
    fun clusterFaces(distanceThreshold: Double): ClusterSummary = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        parseClusterSummary(bridge.nativeStoreCluster(distanceThreshold))
    }

    /**
     * Closes the face-index store (r23). Idempotent: false means there
     * was no open store to close; the state returns to closed either way
     * and [openFaceIndex] works again.
     */
    fun closeFaceIndex(): Boolean = synchronized(SharedEngineState.lock) {
        if (!bridge.available) return false
        val closed = bridge.nativeStoreClose()
        SharedEngineState.faceIndexOpen = false
        closed
    }

    /**
     * The file IDs that currently carry face rows at the vendored pin's
     * model version (r24), via upstream's own indexed-files read. An
     * empty list means no indexed files --- including "every row is
     * older than the current model version", which callers treat as
     * un-indexed. Throws IllegalStateException when the store is not
     * open (or the build is library-less).
     */
    fun indexedFaceFileIds(): List<Long> = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        bridge.nativeStoreIndexedFaceFileIds().toList()
    }

    /**
     * Deletes every face row for [fileIds] (r24), via upstream's own
     * chunked, transactional per-file delete. An empty array is a no-op
     * returning true. This is the invalidation primitive the background
     * queue drives: a CONTENT_CHANGED revision (or a file that left the
     * eligible public listing) must drop its stale rows BEFORE any
     * re-index, because [putFace] ordinals append rather than replace.
     * Throws IllegalStateException when the store is not open (or the
     * build is library-less).
     */
    fun deleteFacesForFiles(fileIds: LongArray): Boolean = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        bridge.nativeStoreDeleteFacesForFiles(fileIds)
    }

    /**
     * The live cluster list for the People UI (r25), in the Rust side's
     * order (face count descending, ties by cluster id ascending;
     * preserved verbatim --- the parse never re-sorts). Counts come from
     * the LIVE assignments table, so manual [mergeFaceClusters] /
     * [removeFacesFromCluster] edits are reflected immediately; the
     * cover is the cluster's highest-scored face's file (null when the
     * cluster has no parsable cover). Throws IllegalStateException when
     * the store is not open (or the build is library-less), the native
     * call fails, or the JSON shape is not the pinned
     * `[{"cluster_id":…,"count":…,"cover_file_id":…}]` array.
     */
    fun faceClusters(): List<FaceCluster> = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        parseFaceClusters(bridge.nativeStoreFaceClusters())
    }

    /**
     * The file IDs whose faces currently sit in [clusterId] (r25), via
     * upstream's own distinct-file read over the live assignments. An
     * empty list means no files --- including an unknown cluster id; the
     * ordering is upstream's own. Throws IllegalStateException when the
     * store is not open (or the build is library-less) or the native
     * call fails.
     */
    fun clusterFileIds(clusterId: String): List<Long> = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        bridge.nativeStoreClusterFileIds(clusterId).toList()
    }

    /**
     * Moves every face of [sourceClusterId] into [targetClusterId] (r25)
     * --- "merge these two people". An empty source is a no-op returning
     * true. [sourceClusterId] equal to [targetClusterId] throws
     * IllegalArgumentException BEFORE any native call (mirroring the
     * putFace embedding-dimension guard); the Rust side guards again as
     * defense in depth. Honest caveat: a manual merge persists only
     * until the next [clusterFaces] pass recomputes assignments from the
     * embeddings. Throws IllegalStateException when the store is not
     * open (or the build is library-less) or the native call fails.
     */
    fun mergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        require(sourceClusterId != targetClusterId) {
            "source and target cluster ids must differ; both are \"$sourceClusterId\""
        }
        bridge.nativeStoreMergeFaceClusters(sourceClusterId, targetClusterId)
    }

    /**
     * Removes [fileIds]' faces from [clusterId] (r25) --- "remove this
     * photo from this person" / the split primitive. Only faces
     * currently assigned to [clusterId] are dropped (a file whose other
     * faces live in other clusters keeps those); the face rows
     * themselves stay, so the next [clusterFaces] pass re-derives
     * assignments from the embeddings. An empty array is a no-op
     * returning true. Throws IllegalStateException when the store is
     * not open (or the build is library-less) or the native call fails.
     */
    fun removeFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        bridge.nativeStoreRemoveFacesFromCluster(clusterId, fileIds)
    }

    // ---- r26: the semantic (Magic Search) layer ----

    /**
     * Opens the semantic (CLIP) half of the engine session over
     * [modelsDir] (r26; r39 makes the halves symmetric). Returns the new
     * [SemanticState]: [SemanticState.Ready] on a true
     * [Bridge.nativeInitSemantic], [SemanticState.Error] on a false one
     * (the flag stays false), [SemanticState.LibraryMissing] when the
     * build carries no library (no native call).
     *
     * Since r39 this call does NOT presuppose [initialize]: the native
     * layer installs the CLIP half on an existing face session (the r26
     * upgrade) or FROM SCRATCH as a semantic-only session (the r26
     * round's documented FACE-model requirement, dropped --- Magic
     * Search and Things run on the SEMANTIC pack alone). [initialize] on
     * a semantic-only session upgrades it with the face half in place;
     * [state] and [semanticState] report the two halves independently.
     * Requires nothing about the face-index store; an
     * IllegalStateException from the native layer propagates unchanged.
     * The re-open is unconditional and (r51) process-global: the outcome
     * is immediately visible to every instance.
     */
    fun initSemantic(modelsDir: String): SemanticState = synchronized(SharedEngineState.lock) {
        if (!bridge.available) return SemanticState.LibraryMissing
        if (bridge.nativeInitSemantic(modelsDir)) {
            SharedEngineState.semanticReady = true
            SharedEngineState.semanticError = null
            SemanticState.Ready
        } else {
            SharedEngineState.semanticReady = false
            SharedEngineState.semanticError = "nativeInitSemantic failed for $modelsDir"
            SemanticState.Error(SharedEngineState.semanticError!!)
        }
    }

    /**
     * The current [SemanticState] (r26); [SemanticState.LibraryMissing]
     * dominates when set, then a remembered [initSemantic] error, then
     * ready, then uninitialized. Since r39 "uninitialized" means only
     * what it says --- the CLIP half is not installed --- with NO claim
     * about the face half ([state] owns that truth; either half may be
     * installed first, and the other upgrades in place). Process-global
     * since r51: a [release] on any instance is visible here immediately.
     */
    fun semanticState(): SemanticState = synchronized(SharedEngineState.lock) {
        when {
            !bridge.available -> SemanticState.LibraryMissing
            SharedEngineState.semanticError != null -> SemanticState.Error(SharedEngineState.semanticError!!)
            SharedEngineState.semanticReady -> SemanticState.Ready
            else -> SemanticState.Uninitialized
        }
    }

    /**
     * The 512-float L2-normalized CLIP image embedding for one JPEG's
     * bytes (r26). Throws IllegalStateException with the Rust message
     * when the engine (or its semantic half) is uninitialized or the
     * call fails, and IllegalStateException (not UnsatisfiedLinkError)
     * on a library-less build.
     */
    @Synchronized
    fun clipImageEmbedding(jpegBytes: ByteArray): FloatArray {
        if (!bridge.available) {
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        return bridge.nativeClipImageEmbeddingJpeg(jpegBytes)
    }

    /**
     * The 512-float CLIP text embedding for [text] (r26) --- the query
     * side of Magic Search. Throws IllegalStateException with the Rust
     * message when the engine (or its semantic half) is uninitialized or
     * the call fails, and IllegalStateException (not
     * UnsatisfiedLinkError) on a library-less build.
     */
    @Synchronized
    fun clipTextEmbedding(text: String): FloatArray {
        if (!bridge.available) {
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        return bridge.nativeClipTextEmbedding(text)
    }

    /**
     * Upserts one CLIP embedding row for [fileId] (r26), returning the
     * native boolean (true on success). Clip rows live in the SAME
     * `ml-index.sqlite` as the face rows, so the r23 open-store gate
     * governs this too. Throws IllegalArgumentException when [embedding]
     * is not [CLIP_EMBEDDING_DIM] floats (checked HERE, before any
     * native call --- the putFace discipline, mirrored Rust-side as
     * defense in depth) and IllegalStateException when the store is not
     * open or the native upsert fails.
     */
    fun putClip(fileId: Long, embedding: FloatArray): Boolean = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        require(embedding.size == CLIP_EMBEDDING_DIM) {
            "embedding must be $CLIP_EMBEDDING_DIM floats; got ${embedding.size}"
        }
        bridge.nativeStorePutClip(fileId, embedding)
    }

    /**
     * Deletes every CLIP row for [fileIds] (r26) --- the semantic twin of
     * the r24 face invalidation delete. An empty array is a no-op
     * returning true. Throws IllegalStateException when the store is not
     * open (or the build is library-less) or the native call fails.
     */
    fun deleteClipsForFiles(fileIds: LongArray): Boolean = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        bridge.nativeStoreDeleteClipsForFiles(fileIds)
    }

    /**
     * The file IDs that currently carry CLIP rows at the vendored pin's
     * model version (r26), sorted ascending --- the Magic-Search
     * resumability read. An empty list means no indexed files (including
     * "every row is older than the current model version"). Throws
     * IllegalStateException when the store is not open (or the build is
     * library-less).
     */
    fun clipIndexedFileIds(): List<Long> = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        bridge.nativeStoreClipIndexedFileIds().toList()
    }

    /**
     * Total CLIP rows in the store (r26). Throws IllegalStateException
     * when the store is not open (or the build is library-less).
     */
    fun clipCount(): Long = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        bridge.nativeStoreClipCount()
    }

    /**
     * Runs the top-K vector search over the CLIP index (r26) and parses
     * the JSON into [ClipMatch] rows in the vecdb's own order (nearest
     * first; preserved verbatim --- the parse never re-sorts). Throws
     * IllegalArgumentException when [queryEmbedding] is not
     * [CLIP_EMBEDDING_DIM] floats or [limit] is not positive (both
     * checked HERE, before any native call) and IllegalStateException
     * when the store is not open (or the build is library-less), the
     * native call fails, or the JSON shape is not the pinned
     * `[{"file_id":…,"distance":…}]` array.
     */
    fun searchClips(queryEmbedding: FloatArray, limit: Int): List<ClipMatch> = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        require(queryEmbedding.size == CLIP_EMBEDDING_DIM) {
            "query embedding must be $CLIP_EMBEDDING_DIM floats; got ${queryEmbedding.size}"
        }
        require(limit > 0) { "limit must be positive; got $limit" }
        parseClipMatches(bridge.nativeStoreSearchClips(queryEmbedding, limit))
    }

    /**
     * Runs the DISTANCE-CUT vector search over the CLIP index (r39, the
     * Things/membership half): the same index and the same honest
     * order-preserving parse as [searchClips], but only matches at
     * cosine distance `<= [maxDistance]` (INCLUSIVE) come back --- the
     * cut runs INSIDE the vecdb (upstream's own `max_distance`, plumbed
     * through the r39 bridge), so a membership query never drags the
     * whole index across the JNI boundary just to discard most of it.
     *
     * Throws IllegalArgumentException when [queryEmbedding] is not
     * [CLIP_EMBEDDING_DIM] floats, [maxDistance] is non-finite or
     * negative, or [limit] is not positive (all checked HERE, before any
     * native call) and IllegalStateException when the store is not open
     * (or the build is library-less), the native call fails, or the JSON
     * shape is not the pinned array.
     */
    fun searchClipsWithin(
        queryEmbedding: FloatArray,
        maxDistance: Float,
        limit: Int,
    ): List<ClipMatch> = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        require(queryEmbedding.size == CLIP_EMBEDDING_DIM) {
            "query embedding must be $CLIP_EMBEDDING_DIM floats; got ${queryEmbedding.size}"
        }
        require(maxDistance.isFinite() && maxDistance >= 0f) {
            "max distance must be a finite non-negative float; got $maxDistance"
        }
        require(limit > 0) { "limit must be positive; got $limit" }
        parseClipMatches(bridge.nativeStoreSearchClipsWithin(queryEmbedding, maxDistance, limit))
    }

    /**
     * Fills the CLIP vector index from the SQLite rows (r26) and parses
     * the JSON into [ClipFillReport]; `outcome` is validated to be one of
     * the three pinned strings. Throws IllegalStateException when the
     * store is not open (or the build is library-less), the native call
     * fails, or the JSON shape is not the pinned
     * `{"outcome":…,"rows":…,"indexed":…,"skipped":…,"resumed":…}`.
     */
    fun fillClipIndex(): ClipFillReport = synchronized(SharedEngineState.lock) {
        requireFaceIndexOpen()
        parseClipFillReport(bridge.nativeStoreFillClipIndex())
    }

    internal companion object {
        /**
         * The r23 face-embedding dimension (upstream MobileFaceNet output;
         * `CLUSTER_CENTROID_DIMENSIONS` in the vendored clustering).
         */
        internal const val FACE_EMBEDDING_DIM = 192

        /** Flat JNI stride for [detectFaceEmbeddings]: 5 box floats + 192. */
        internal const val EMBEDDING_STRIDE = FACE_EMBEDDING_DIM + 5

        /**
         * The r26 CLIP-embedding dimension (upstream MobileCLIP-S2 output;
         * `CLIP_EMBEDDING_DIMENSIONS` in the vendored store).
         */
        internal const val CLIP_EMBEDDING_DIM = 512

        /**
         * The facade-side message for store operations run before
         * [openFaceIndex] (the r23 state machine's failure shape).
         */
        private const val FACE_INDEX_NOT_OPEN =
            "face index store is not open; call openFaceIndex(dir) first"

        /** The pinned `nativeStoreFillClipIndex` outcome strings (r26). */
        private val CLIP_FILL_OUTCOMES = setOf("Completed", "AlreadyFilled", "Superseded")

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

        /**
         * Maps the r23 flat stride-197 output into [FaceEmbedding] rows;
         * a length that is not a multiple of 197 is a broken engine
         * contract (including a runtime embedding dimension that drifted
         * off 192) and fails as IllegalStateException.
         */
        internal fun faceEmbeddings(flat: FloatArray): List<FaceEmbedding> {
            check(flat.size % EMBEDDING_STRIDE == 0) {
                "engine returned ${flat.size} floats; expected a multiple of $EMBEDDING_STRIDE " +
                    "(N * [x1, y1, x2, y2, score, e0..e191])"
            }
            return flat.toList().chunked(EMBEDDING_STRIDE).map { row ->
                FaceEmbedding(row[0], row[1], row[2], row[3], row[4], row.drop(5).toFloatArray())
            }
        }

        /**
         * Parses the compact `nativeStoreCluster` JSON summary. Shapes
         * other than the pinned `{"faces":N,"clusters":M,"sizes":[...]}`
         * fail as IllegalStateException (the facade's own failure shape).
         */
        internal fun parseClusterSummary(json: String): ClusterSummary {
            return try {
                val obj = JSONObject(json)
                val sizes = ArrayList<Int>()
                val array = obj.getJSONArray("sizes")
                for (i in 0 until array.length()) sizes.add(array.getInt(i))
                ClusterSummary(obj.getInt("faces"), obj.getInt("clusters"), sizes)
            } catch (e: Exception) {
                throw IllegalStateException("malformed cluster summary JSON: ${e.message}")
            }
        }

        /**
         * Parses the r25 `nativeStoreFaceClusters` JSON array into
         * [FaceCluster] rows. Shapes other than the pinned
         * `[{"cluster_id":"…","count":N,"cover_file_id":…|null}]` --- a
         * non-array envelope, a missing field, a wrong field type, a
         * negative face count --- fail as IllegalStateException (the
         * facade's own failure shape), exactly like [parseClusterSummary].
         */
        internal fun parseFaceClusters(json: String): List<FaceCluster> {
            return try {
                val array = JSONArray(json)
                val clusters = ArrayList<FaceCluster>(array.length())
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    val faceCount = entry.getInt("count")
                    check(faceCount >= 0) {
                        "cluster \"${entry.optString("cluster_id")}\" has a negative face count: $faceCount"
                    }
                    // The pinned shape ALWAYS carries the cover key ---
                    // JSON null when the cluster has no parsable cover. A
                    // missing key is a broken engine contract and fails
                    // like any other malformed shape (a bare isNull would
                    // silently treat it as null).
                    check(entry.has("cover_file_id")) {
                        "cluster \"${entry.optString("cluster_id")}\" is missing cover_file_id"
                    }
                    clusters.add(
                        FaceCluster(
                            clusterId = entry.getString("cluster_id"),
                            faceCount = faceCount,
                            coverFileId = if (entry.isNull("cover_file_id")) null else entry.getLong("cover_file_id"),
                        )
                    )
                }
                clusters
            } catch (e: Exception) {
                throw IllegalStateException("malformed face clusters JSON: ${e.message}")
            }
        }

        /**
         * Parses the r26 `nativeStoreSearchClips` JSON array into
         * [ClipMatch] rows, preserving the vecdb's own order verbatim.
         * Shapes other than the pinned `[{"file_id":N,"distance":F}]` ---
         * a non-array envelope, a missing field, a wrong field type, an
         * unparsable file id --- fail as IllegalStateException (the
         * facade's own failure shape), exactly like [parseFaceClusters].
         */
        internal fun parseClipMatches(json: String): List<ClipMatch> {
            return try {
                val array = JSONArray(json)
                val matches = ArrayList<ClipMatch>(array.length())
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    matches.add(
                        ClipMatch(
                            fileId = entry.getLong("file_id"),
                            distance = entry.getDouble("distance").toFloat(),
                        )
                    )
                }
                matches
            } catch (e: Exception) {
                throw IllegalStateException("malformed clip matches JSON: ${e.message}")
            }
        }

        /**
         * Parses the r26 `nativeStoreFillClipIndex` JSON object into
         * [ClipFillReport]. Shapes other than the pinned
         * `{"outcome":"…","rows":N,"indexed":M,"skipped":K,"resumed":bool}` ---
         * a non-object envelope, a missing field, a wrong type, or an
         * outcome string outside the three pinned ones --- fail as
         * IllegalStateException (the facade's own failure shape).
         */
        internal fun parseClipFillReport(json: String): ClipFillReport {
            return try {
                val obj = JSONObject(json)
                val outcome = obj.getString("outcome")
                check(outcome in CLIP_FILL_OUTCOMES) {
                    "clip fill outcome \"$outcome\" is not one of $CLIP_FILL_OUTCOMES"
                }
                ClipFillReport(
                    outcome = outcome,
                    rows = obj.getInt("rows"),
                    indexed = obj.getInt("indexed"),
                    skipped = obj.getInt("skipped"),
                    resumed = obj.getBoolean("resumed"),
                )
            } catch (e: Exception) {
                throw IllegalStateException("malformed clip fill report JSON: ${e.message}")
            }
        }
    }
}

/**
 * The process-global facade state (r51, audit C-4): the five
 * open/ready/error flags EVERY [MlEngine] instance shares. The JNI
 * engine behind the production bridge is one process-wide singleton
 * (the Rust side's `engine::ENGINE` / `store::STORE` OnceLock statics),
 * so its Kotlin-side truth must be process-wide too --- per-instance
 * flags let one instance's [MlEngine.release] leave another reporting
 * [MlEngine.State.Ready] over a released engine (workers construct an
 * engine per run; adapters, the Settings self-test and the ViewModels
 * hold their own). [lock] is the ONE monitor for all five flags and
 * nothing else: every read and write goes through it, so an open /
 * close / init outcome on ANY instance is immediately visible to EVERY
 * instance.
 */
private object SharedEngineState {

    /** The single monitor for the five flags below (and nothing else). */
    val lock = Any()

    /** True after a successful initialize on ANY instance (the FACE half). */
    var sessionOpen = false

    /** The last initialize failure, process-wide like the engine it describes. */
    var initError: String? = null

    /** The r23 face-index store's open state (separate from the session). */
    var faceIndexOpen = false

    /** The r26 semantic half's ready state (requires the engine session). */
    var semanticReady = false

    /** The last [MlEngine.initSemantic] failure, tracked like [initError]. */
    var semanticError: String? = null
}
