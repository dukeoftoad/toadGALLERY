package io.github.dukeoftoad.gallery.ml.search

import android.app.Application
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import io.github.dukeoftoad.gallery.ml.index.FACE_INDEX_DIR_NAME
import java.io.File

/**
 * The production [MagicSearchEngineSeam] (r26, Workstream 7; r39 splits
 * it into its own file --- the PeopleEngineSeam/PeopleEngineAdapter
 * convention --- and drops the FACE-model session requirement): a THIN
 * delegation over the `MlEngine` facade's semantic surface
 * ([MlEngine.clipTextEmbedding] / [MlEngine.searchClips] /
 * [MlEngine.searchClipsWithin] / [MlEngine.clipCount]), plus the session
 * lifecycle those calls need.
 *
 * ## Session lifecycle (the r39 shape)
 * The CLIP store reads run against an OPEN `ml-index.sqlite` store and
 * a READY semantic session, so the adapter opens its session lazily and
 * exactly once per instance as `initSemantic(modelsDir)` ->
 * `openFaceIndex(faceIndexDir)` --- NO `initialize` call: since r39 the
 * native layer installs the CLIP half from scratch when no engine
 * exists (a SEMANTIC-ONLY session), so the semantic surfaces run on the
 * SEMANTIC pack alone (the r26 round's documented FACE-model
 * requirement, dropped; `docs/phase-5/ente-exit.md`). When a face
 * session already exists, the same call upgrades it with the CLIP half
 * (the r26 behavior, unchanged). Every seam method calls
 * [ensureSessionOpen] first (idempotent: an already-open session is
 * reused); a failure throws `IllegalStateException` carrying the
 * engine's OWN message --- the repository maps it to its typed
 * EngineFailure result, the ViewModel surfaces it as the Error state,
 * and nothing is paraphrased away.
 *
 * [close] releases the session (`closeFaceIndex` + `release`) and is
 * called from the owning ViewModel's `onCleared`. Known residual (the
 * r25 adapter's, unchanged): the r24/r26 background workers drive their
 * own `MlEngine` instances; if a worker runs while a semantic surface
 * holds an open store, the two sessions alias the same process-global
 * native engine --- the facade's own `@Synchronized` surface is the only
 * guard, which is precisely the live-store semantics this domain
 * documents.
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

    override fun searchClipsWithin(
        queryEmbedding: FloatArray,
        maxDistance: Float,
        limit: Int,
    ): List<MlEngine.ClipMatch> {
        ensureSessionOpen()
        return engine.searchClipsWithin(queryEmbedding, maxDistance, limit)
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
     * Opens the engine session once, in the r39 order
     * (initSemantic --- which installs the semantic half from scratch or
     * upgrades an existing face session --- then the shared face-index
     * store at the SAME directory the r24 queue's `ml-index.sqlite`, the
     * r26 `clip-index-state.json` and the r39 `things-state.json` live
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
         * Settings resets the ML store, both queues' bookkeeping, the
         * r25 people state AND the r39 things state together; the r26
         * semantic queue's state file rides the same reset).
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
