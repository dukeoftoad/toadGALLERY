package io.github.dukeoftoad.gallery.ml.search

import android.app.Application
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import io.github.dukeoftoad.gallery.ml.index.FACE_INDEX_DIR_NAME
import java.io.File

/**
 * The r26 Magic Search domain's ONLY view of the ML engine (Workstream 7,
 * `docs/phase-5/magic-search.md`): the [io.github.dukeoftoad.gallery.ml
 * .people.PeopleEngineSeam] pattern applied to the semantic surface.
 *
 * Everything the Search tab's Content mode needs from the engine, as one
 * small synchronous contract over the LIVE CLIP store (the same
 * `ml-index.sqlite` the faces live in):
 *  - [engineAvailable] is the build-shape truth (is `libtoad_ml_jni.so`
 *    in this APK at all): false means every other member is unreachable
 *    and the UI shows the honest EngineMissing state, never a fake
 *    empty one;
 *  - [semanticState]/[initSemantic] expose the engine's own semantic
 *    session state (the r26 contract's `MlEngine.SemanticState`);
 *  - [openFaceIndex] is the store lifecycle help the repository needs
 *    (all CLIP store functions ride the SAME open-store state machine
 *    the faces do);
 *  - [clipTextEmbedding]/[searchClips]/[clipCount] are the query path:
 *    text in, 512-float embedding out, ranked [MlEngine.ClipMatch]es,
 *    and the index's size.
 *
 * The production binding is [MagicSearchEngineAdapter] (a thin wrapper
 * over `io.github.dukeoftoad.gallery.ml.engine.MlEngine`, Agent A's r26
 * semantic surface); tests bind fakes. UI/domain code NEVER imports
 * MlEngine directly --- this seam is the whole contract, so the Magic
 * Search logic stays JVM-testable (ADR-0016).
 *
 * Queries NEVER touch the network (ADR-0010 on-device-only AI; ADR-0020
 * `models.ente.com` delivery serves the model pack, never queries): the
 * seam reads the app-private store and nothing else.
 */
interface MagicSearchEngineSeam {

    /** Whether the engine library is present in this build (per-process fixed). */
    val engineAvailable: Boolean

    /** The engine's current semantic session state (the r26 contract). */
    fun semanticState(): MlEngine.SemanticState

    /**
     * Opens the semantic half of an already-initialized engine session
     * over [modelsDir]; returns the engine's own resulting
     * [MlEngine.SemanticState].
     */
    fun initSemantic(modelsDir: String): MlEngine.SemanticState

    /**
     * Opens the shared ML store at `<dir>/ml-index.sqlite` (the SAME
     * store the face index lives in); the engine's own success flag.
     */
    fun openFaceIndex(dir: String): Boolean

    /**
     * The 512-float CLIP text embedding for [text]. Throws the facade's
     * own failure shapes (IllegalStateException) when the semantic
     * session is not ready.
     */
    fun clipTextEmbedding(text: String): FloatArray

    /**
     * The store's nearest [limit] CLIP rows for [queryEmbedding], in the
     * vecdb's own order (distance ascending); preserved verbatim by the
     * callers --- the ranking layer's documented contract.
     */
    fun searchClips(queryEmbedding: FloatArray, limit: Int): List<MlEngine.ClipMatch>

    /** Total CLIP rows currently in the store (0 = not indexed yet). */
    fun clipCount(): Long
}

/**
 * The production [MagicSearchEngineSeam] (r26, Workstream 7): a THIN
 * delegation over the `MlEngine` facade's r26 semantic surface
 * ([MlEngine.clipTextEmbedding] / [MlEngine.searchClips] /
 * [MlEngine.clipCount] --- Agent A's engine-side half of this round's
 * contract), plus the session lifecycle those calls need.
 *
 * ## Session lifecycle
 * The CLIP store reads run against an OPEN `ml-index.sqlite` store and
 * a READY semantic session, so the adapter mirrors `ClipIndexWorker`'s
 * known-good sequence lazily and exactly once per instance:
 * `initialize(modelsDir)` (the FACE-based engine session --- the honest
 * r26 limitation) -> `initSemantic(modelsDir)` ->
 * `openFaceIndex(faceIndexDir)`. Every seam method calls
 * [ensureSessionOpen] first (idempotent: an already-open session is
 * reused); a failure throws `IllegalStateException` carrying the
 * engine's OWN message --- the repository maps it to its typed
 * EngineFailure result, the ViewModel surfaces it as the Error state,
 * and nothing is paraphrased away.
 *
 * [close] releases the session (`closeFaceIndex` + `release`) and is
 * called from the owning ViewModel's `onCleared`. Known residual (the
 * r25 adapter's, unchanged): the r24/r26 background workers drive their
 * own `MlEngine` instances; if a worker runs while a Search tab holds an
 * open store, the two sessions alias the same process-global native
 * engine --- the facade's own `@Synchronized` surface is the only guard,
 * which is precisely the live-store semantics this domain documents.
 *
 * Android-adjacent glue, untested per ADR-0016 (the pure logic this
 * adapter feeds is JVM-tested behind the seam); privacy: ADR-0010
 * (on-device only, consent-gated in the UI layer) and ADR-0020 (the
 * models.ente.com delivery that provisioned the models it reads).
 */
class MagicSearchEngineAdapter(
    private val engine: MlEngine = MlEngine(),
    private val modelsDir: String,
    private val faceIndexDir: File,
) : MagicSearchEngineSeam {

    override val engineAvailable: Boolean
        get() = engine.available

    /** Whether the lazily-opened session has been opened already. */
    private var sessionOpen = false

    override fun semanticState(): MlEngine.SemanticState = engine.semanticState()

    override fun initSemantic(modelsDir: String): MlEngine.SemanticState =
        engine.initSemantic(modelsDir)

    override fun openFaceIndex(dir: String): Boolean = engine.openFaceIndex(dir)

    override fun clipTextEmbedding(text: String): FloatArray {
        ensureSessionOpen()
        return engine.clipTextEmbedding(text)
    }

    override fun searchClips(queryEmbedding: FloatArray, limit: Int): List<MlEngine.ClipMatch> {
        ensureSessionOpen()
        return engine.searchClips(queryEmbedding, limit)
    }

    override fun clipCount(): Long {
        ensureSessionOpen()
        return engine.clipCount()
    }

    /**
     * Releases the session; safe to call repeatedly and from a ViewModel
     * that never opened one. After a close the adapter lazily re-opens on
     * the next seam call (the engine contract's re-init guarantee).
     */
    @Synchronized
    fun close() {
        if (!engineAvailable) return
        engine.closeFaceIndex()
        engine.release()
        sessionOpen = false
    }

    /**
     * Opens the engine session once, in `ClipIndexWorker`'s order
     * (initialize --- the FACE-based session --- then initSemantic, then
     * open the shared face-index store at the SAME directory the r24
     * queue's `ml-index.sqlite` and the r26 `clip-index-state.json` live
     * in). Synchronized on this adapter: the seam's methods may be called
     * from different coroutines, and the open must land exactly once.
     */
    @Synchronized
    private fun ensureSessionOpen() {
        if (sessionOpen) return
        if (!engineAvailable) {
            // Dominates: a library-less build fails with the build-shape
            // message, never a confusing state-machine one.
            throw IllegalStateException("engine library not in this build (build with the engine flag)")
        }
        when (val init = engine.initialize(modelsDir)) {
            is MlEngine.State.Ready -> Unit
            is MlEngine.State.Error ->
                throw IllegalStateException("engine init failed: ${init.message}")
            else ->
                throw IllegalStateException("engine init: $init")
        }
        when (val semantic = engine.initSemantic(modelsDir)) {
            is MlEngine.SemanticState.Ready -> Unit
            is MlEngine.SemanticState.Error ->
                throw IllegalStateException("semantic init failed: ${semantic.message}")
            else ->
                throw IllegalStateException("semantic init: $semantic")
        }
        if (!engine.openFaceIndex(faceIndexDir.absolutePath)) {
            throw IllegalStateException("face index store did not open")
        }
        sessionOpen = true
    }

    companion object {
        /**
         * The production wiring: the process-wide provisioner's model
         * install root and the app-private `face-index` directory (the
         * r24 constants --- the SAME directory whose recursive delete in
         * Settings resets the ML store, both queues' bookkeeping AND the
         * r25 people state together; the r26 semantic queue's state file
         * rides the same reset).
         */
        fun fromApplication(app: Application): MagicSearchEngineAdapter {
            val application = app as ToadGalleryApplication
            return MagicSearchEngineAdapter(
                modelsDir = application.modelProvisioner.modelsDir.absolutePath,
                faceIndexDir = File(app.filesDir, FACE_INDEX_DIR_NAME),
            )
        }
    }
}
