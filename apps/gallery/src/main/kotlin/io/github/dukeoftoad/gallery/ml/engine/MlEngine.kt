package io.github.dukeoftoad.gallery.ml.engine

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

        // ---- r23 ----
        fun nativeDetectFaceEmbeddingsJpeg(jpegBytes: ByteArray): FloatArray
        fun nativeStoreOpen(dir: String): Boolean
        fun nativeStorePutFace(fileId: Long, embedding: FloatArray): Long
        fun nativeStoreFaceCount(): Long
        fun nativeStoreCluster(distanceThreshold: Double): String
        fun nativeStoreClose(): Boolean
    }

    private object JniBridge : Bridge {
        override val available: Boolean get() = MlEngineJni.available
        override fun nativeInit(modelsDir: String): Boolean = MlEngineJni.nativeInit(modelsDir)
        override fun nativeEngineInfo(): String? = MlEngineJni.nativeEngineInfo()
        override fun nativeDetectFacesJpeg(jpegBytes: ByteArray): FloatArray =
            MlEngineJni.nativeDetectFacesJpeg(jpegBytes)

        override fun nativeRelease(): Boolean = MlEngineJni.nativeRelease()

        // ---- r23 ----
        override fun nativeDetectFaceEmbeddingsJpeg(jpegBytes: ByteArray): FloatArray =
            MlEngineJni.nativeDetectFaceEmbeddingsJpeg(jpegBytes)

        override fun nativeStoreOpen(dir: String): Boolean = MlEngineJni.nativeStoreOpen(dir)
        override fun nativeStorePutFace(fileId: Long, embedding: FloatArray): Long =
            MlEngineJni.nativeStorePutFace(fileId, embedding)

        override fun nativeStoreFaceCount(): Long = MlEngineJni.nativeStoreFaceCount()
        override fun nativeStoreCluster(distanceThreshold: Double): String =
            MlEngineJni.nativeStoreCluster(distanceThreshold)

        override fun nativeStoreClose(): Boolean = MlEngineJni.nativeStoreClose()
    }

    private var sessionOpen = false
    private var initError: String? = null
    /** The r23 face-index store's open state (separate from the session). */
    private var faceIndexOpen = false

    /** Instance-side guard for the r23 store state machine. */
    private fun requireFaceIndexOpen() {
        // Library-missing dominates: on a library-less build every store op
        // must fail with the build-shape message, never the state-machine one.
        if (!bridge.available) {
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        check(faceIndexOpen) { FACE_INDEX_NOT_OPEN }
    }

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
        // nativeRelease closes the face-index store too (the r23 contract);
        // the facade state follows without a second native call.
        faceIndexOpen = false
        return freed
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
    @Synchronized
    fun openFaceIndex(dir: String): Boolean {
        if (!bridge.available) {
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        val opened = bridge.nativeStoreOpen(dir)
        faceIndexOpen = opened
        return opened
    }

    /**
     * Inserts one face row for [fileId] (r23), returning the face row id
     * (> 0). Throws IllegalArgumentException when [embedding] is not
     * [FACE_EMBEDDING_DIM] floats (checked HERE, before any native call)
     * and IllegalStateException when the store is not open or the native
     * insert fails.
     */
    @Synchronized
    fun putFace(fileId: Long, embedding: FloatArray): Long {
        requireFaceIndexOpen()
        require(embedding.size == FACE_EMBEDDING_DIM) {
            "embedding must be $FACE_EMBEDDING_DIM floats; got ${embedding.size}"
        }
        return bridge.nativeStorePutFace(fileId, embedding)
    }

    /**
     * Total face rows in the store (r23). Throws IllegalStateException
     * when the store is not open (or the build is library-less).
     */
    @Synchronized
    fun faceCount(): Long {
        requireFaceIndexOpen()
        return bridge.nativeStoreFaceCount()
    }

    /**
     * Clusters the faces in the store at [distanceThreshold] (r23) and
     * parses the compact JSON summary into [ClusterSummary]. Throws
     * IllegalStateException when the store is not open, the native call
     * fails, or the JSON shape is not the pinned
     * `{"faces":N,"clusters":M,"sizes":[...]}`.
     */
    @Synchronized
    fun clusterFaces(distanceThreshold: Double): ClusterSummary {
        requireFaceIndexOpen()
        return parseClusterSummary(bridge.nativeStoreCluster(distanceThreshold))
    }

    /**
     * Closes the face-index store (r23). Idempotent: false means there
     * was no open store to close; the state returns to closed either way
     * and [openFaceIndex] works again.
     */
    @Synchronized
    fun closeFaceIndex(): Boolean {
        if (!bridge.available) return false
        val closed = bridge.nativeStoreClose()
        faceIndexOpen = false
        return closed
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
         * The facade-side message for store operations run before
         * [openFaceIndex] (the r23 state machine's failure shape).
         */
        private const val FACE_INDEX_NOT_OPEN =
            "face index store is not open; call openFaceIndex(dir) first"

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
    }
}
