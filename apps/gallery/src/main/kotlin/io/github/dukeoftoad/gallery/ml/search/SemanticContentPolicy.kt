package io.github.dukeoftoad.gallery.ml.search

/**
 * The Magic Search surfaces' six honest content states (r26, Workstream
 * 7), decided by the PURE [MagicSearchContentPolicy] --- the r25
 * [io.github.dukeoftoad.gallery.ml.people.PeopleContentPolicy]
 * discipline applied to semantic search: no Android imports, JVM-tested,
 * so the "which state does the user see" rules are pinned rather than
 * implicit in a ViewModel's control flow (ADR-0016).
 */
sealed interface MagicSearchState {

    /**
     * The Magic Search consent is OFF (the `magicSearchEnabled`
     * preference, default FALSE --- ADR-0010's opt-in rule). Persisted
     * clip rows may exist, but the feature must not query or present
     * them while the user's choice says off. The state's copy points at
     * the Settings toggle.
     */
    data object ConsentOff : MagicSearchState

    /**
     * Consent is ON but this build carries no engine library
     * (`libtoad_ml_jni.so` --- built only with the engine property): the
     * honest build-shape state, never a disguised "nothing indexed".
     */
    data object EngineMissing : MagicSearchState

    /**
     * Consent on, engine present, but the models the r26 session needs
     * are not fully installed: EITHER pack --- FACE (the engine session
     * is face-based in r26; the honest documented limitation) or SEMANTIC
     * (the three CLIP assets, ~213 MB) --- suffices to land here. Copy
     * points at the Settings download.
     */
    data object ModelsNotProvisioned : MagicSearchState

    /**
     * All gates pass but the CLIP index is EMPTY (clip count 0): the
     * semantic background index has not run (or is still draining --- a
     * half-indexed store is only usable once the queue's fill pass
     * lands). Copy says content search appears after the semantic index
     * runs.
     */
    data object NotIndexed : MagicSearchState

    /**
     * Ready: queries may run. Carried as an explicit state (not a
     * fall-through) so the policy's output is total over its inputs and
     * the UI's mode-switch wiring can branch exhaustively.
     */
    data object Ready : MagicSearchState

    /**
     * A read failed: [message] is the engine's/store's own diagnostic
     * text, surfaced verbatim (never paraphrased away). Dominates every
     * other input --- a failed gate read must not mask as consent, build
     * or empty states.
     */
    data class Error(val message: String) : MagicSearchState
}

/**
 * The PURE mapping from Magic Search's inputs to the feature's content
 * state (r26, Workstream 7). The ViewModels add nothing but the I/O that
 * produces the inputs; every rule below is pinned by
 * `MagicSearchContentPolicyTest`.
 *
 * Inputs:
 *  - [consentEnabled]: the `magicSearchEnabled` preference (default
 *    false --- ADR-0010);
 *  - [engineAvailable]: the seam's build-shape flag;
 *  - [faceModelsReady]/[semanticModelsReady]: the provisioner's own
 *    capability states for FACE and SEMANTIC, flattened to booleans
 *    (Ready or not). FACE participates because the r26 engine session
 *    initializes the face models even for CLIP-only work --- the
 *    session-shape limitation the round documents honestly;
 *  - [clipCount]: the store's CLIP row count (the engine seam read);
 *  - [errorMessage]: a non-null value when the input-gathering read
 *    itself failed.
 *
 * Rule order (error dominance first, consent before capability, honesty
 * before emptiness):
 *  1. A non-null [errorMessage] wins over EVERYTHING: consent, engine
 *     presence, model packs and clip rows cannot turn a failed read into
 *     a presentable state (the honest-states doctrine).
 *  2. Consent OFF is [MagicSearchState.ConsentOff] regardless of what
 *     the store holds (ADR-0010: the feature is opt-in, off by default;
 *     querying content while the toggle says off would present data the
 *     user's own choice withholds --- the same reasoning the People and
 *     Map policies pin).
 *  3. Consent ON but engine missing is [MagicSearchState.EngineMissing]
 *     --- the build-shape fact.
 *  4. Consent + engine but an incomplete FACE OR SEMANTIC pack is
 *     [MagicSearchState.ModelsNotProvisioned] --- capability before
 *     emptiness: a store with rows but no model pack on disk cannot be
 *     searched, and pretending otherwise would surface a query failure
 *     where an honest "download the models" card belongs.
 *  5. All gates pass but zero CLIP rows is
 *     [MagicSearchState.NotIndexed] (includes "the queue is still
 *     draining").
 *  6. Otherwise [MagicSearchState.Ready].
 */
object MagicSearchContentPolicy {

    /** See the object KDoc for the full rule order. */
    fun decide(
        consentEnabled: Boolean,
        engineAvailable: Boolean,
        faceModelsReady: Boolean,
        semanticModelsReady: Boolean,
        clipCount: Long,
        errorMessage: String? = null,
    ): MagicSearchState = when {
        errorMessage != null -> MagicSearchState.Error(errorMessage)
        !consentEnabled -> MagicSearchState.ConsentOff
        !engineAvailable -> MagicSearchState.EngineMissing
        !faceModelsReady || !semanticModelsReady -> MagicSearchState.ModelsNotProvisioned
        clipCount <= 0L -> MagicSearchState.NotIndexed
        else -> MagicSearchState.Ready
    }
}
