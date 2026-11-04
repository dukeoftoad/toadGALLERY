package io.github.dukeoftoad.gallery.ml.search

import io.github.dukeoftoad.gallery.ml.engine.MlEngine

/**
 * The semantic domain's ONLY view of the ML engine (r26, Workstream 7;
 * r39 renames its scope from "the Search tab's Content mode" to the
 * SEMANTIC domain --- Magic Search AND the Things collections --- and
 * adds the distance-cut search): the
 * [io.github.dukeoftoad.gallery.ml.people.PeopleEngineSeam] pattern
 * applied to the semantic surface. The production adapter lives in its
 * own file (`MagicSearchEngineAdapter.kt`, the PeopleEngineSeam /
 * PeopleEngineAdapter file convention).
 *
 * Everything the semantic consumers need from the engine, as one small
 * synchronous contract over the LIVE CLIP store (the same
 * `ml-index.sqlite` the faces live in):
 *  - [engineAvailable] is the build-shape truth (is `libtoad_ml_jni.so`
 *    in this APK at all): false means every other member is unreachable
 *    and the UI shows the honest EngineMissing state, never a fake
 *    empty one;
 *  - [semanticState]/[initSemantic] expose the engine's own semantic
 *    session state (the r26 contract's `MlEngine.SemanticState`; since
 *    r39 the session is installable from scratch, without the FACE
 *    half);
 *  - [openFaceIndex] is the store lifecycle help the repository needs
 *    (all CLIP store functions ride the SAME open-store state machine
 *    the faces do);
 *  - [clipTextEmbedding]/[searchClips]/[searchClipsWithin]/[clipCount]
 *    are the query paths: text in, 512-float embedding out, ranked
 *    [MlEngine.ClipMatch]es (top-K, or top-K-within-a-distance for the
 *    Things membership half), and the index's size.
 *
 * The production binding is [MagicSearchEngineAdapter] (a thin wrapper
 * over `io.github.dukeoftoad.gallery.ml.engine.MlEngine`); tests bind
 * fakes. UI/domain code NEVER imports MlEngine directly --- this seam
 * is the whole contract, so the semantic logic stays JVM-testable
 * (ADR-0016).
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
     * Opens the semantic half of the engine session over [modelsDir]
     * (r39: from scratch when no engine exists --- a semantic-only
     * session --- or as the CLIP upgrade of an existing face session);
     * returns the engine's own resulting [MlEngine.SemanticState].
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

    /**
     * The store's CLIP rows for [queryEmbedding] at cosine distance
     * `<= maxDistance` (r39), in the vecdb's own order (nearest first,
     * preserved verbatim) --- the Things membership search. The cut runs
     * INSIDE the vecdb, so a membership read never drags the whole
     * index across the bridge.
     */
    fun searchClipsWithin(
        queryEmbedding: FloatArray,
        maxDistance: Float,
        limit: Int,
    ): List<MlEngine.ClipMatch>

    /** Total CLIP rows currently in the store (0 = not indexed yet). */
    fun clipCount(): Long
}
