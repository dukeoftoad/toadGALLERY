package com.toadgallery.gallery.ml.search

/**
 * The semantic surfaces' six honest content states (r26, Workstream 7;
 * r39 renames the r26 `MagicSearchContentPolicy` to its domain name ---
 * ONE truth shared by Magic Search AND the Things collections --- and
 * drops the FACE-model input the r26 session shape carried), decided by
 * the PURE [SemanticContentPolicy] --- the r25
 * [com.toadgallery.gallery.ml.people.PeopleContentPolicy]
 * discipline applied to the semantic features: no Android imports,
 * JVM-tested, so the "which state does the user see" rules are pinned
 * rather than implicit in a ViewModel's control flow (ADR-0016).
 */
sealed interface SemanticContentState {

    /**
     * The pre-first-read state (the section ViewModels' initial value;
     * the POLICY never decides this arm --- [SemanticContentPolicy.decide]
     * is total over its inputs without it, the `PeopleContentState
     * .Loading` precedent verbatim).
     */
    data object Loading : SemanticContentState

    /**
     * The semantic consent is OFF (the feature's own preference ---
     * `magicSearchEnabled` for Magic Search, `thingsAlbumEnabled` for
     * Things; both default FALSE --- ADR-0010's opt-in rule). Persisted
     * clip rows may exist, but the feature must not query or present
     * them while the user's choice says off. The state's copy points at
     * the Settings toggle.
     */
    data object ConsentOff : SemanticContentState

    /**
     * Consent is ON but this build carries no engine library
     * (`libtoad_ml_jni.so` --- built only with the engine property): the
     * honest build-shape state, never a disguised "nothing indexed".
     */
    data object EngineMissing : SemanticContentState

    /**
     * Consent on, engine present, but the SEMANTIC model pack (the
     * three CLIP assets, ~213 MB) is not fully installed. Since r39 the
     * semantic session is installable WITHOUT the FACE pack (the r26
     * session's documented limitation, dropped), so this state keys on
     * the SEMANTIC capability alone. Copy points at the Settings
     * download.
     */
    data object ModelsNotProvisioned : SemanticContentState

    /**
     * All gates pass but the CLIP index is EMPTY (clip count 0): the
     * semantic background index has not run (or is still draining --- a
     * half-indexed store is only usable once the queue's fill pass
     * lands). Copy says the feature appears after the semantic index
     * runs.
     */
    data object NotIndexed : SemanticContentState

    /**
     * Ready: queries may run. Carried as an explicit state (not a
     * fall-through) so the policy's output is total over its inputs and
     * the UI's mode-switch wiring can branch exhaustively.
     */
    data object Ready : SemanticContentState

    /**
     * A read failed: [message] is the engine's/store's own diagnostic
     * text, surfaced verbatim (never paraphrased away). Dominates every
     * other input --- a failed gate read must not mask as consent, build
     * or empty states.
     */
    data class Error(val message: String) : SemanticContentState
}

/**
 * The PURE mapping from the semantic features' inputs to their content
 * state (r26 as `MagicSearchContentPolicy`; r39 the rename + the FACE
 * decoupling). The ViewModels add nothing but the I/O that produces the
 * inputs; every rule below is pinned by `SemanticContentPolicyTest`.
 *
 * Inputs:
 *  - [consentEnabled]: the feature's own preference (`magicSearchEnabled`
 *    / `thingsAlbumEnabled`, both default false --- ADR-0010);
 *  - [engineAvailable]: the seam's build-shape flag;
 *  - [semanticModelsReady]: the provisioner's own SEMANTIC capability
 *    state, flattened to a boolean (Ready or not). The r26 `FACE`
 *    input is GONE: since r39 the engine session's semantic half is
 *    installable from scratch, so the FACE pack is not a semantic
 *    gate anymore (the honest decoupling `docs/phase-5/ente-exit.md`
 *    records);
 *  - [clipCount]: the store's CLIP row count (the engine seam read);
 *  - [errorMessage]: a non-null value when the input-gathering read
 *    itself failed.
 *
 * Rule order (error dominance first, consent before capability, honesty
 * before emptiness):
 *  1. A non-null [errorMessage] wins over EVERYTHING: consent, engine
 *     presence, model packs and clip rows cannot turn a failed read into
 *     a presentable state (the honest-states doctrine).
 *  2. Consent OFF is [SemanticContentState.ConsentOff] regardless of
 *     what the store holds (ADR-0010: the features are opt-in, off by
 *     default; querying content while the toggle says off would present
 *     data the user's own choice withholds --- the same reasoning the
 *     People and Map policies pin).
 *  3. Consent ON but engine missing is
 *     [SemanticContentState.EngineMissing] --- the build-shape fact.
 *  4. Consent + engine but an incomplete SEMANTIC pack is
 *     [SemanticContentState.ModelsNotProvisioned] --- capability before
 *     emptiness: a store with rows but no model pack on disk cannot be
 *     searched, and pretending otherwise would surface a query failure
 *     where an honest "download the models" card belongs.
 *  5. All gates pass but zero CLIP rows is
 *     [SemanticContentState.NotIndexed] (includes "the queue is still
 *     draining").
 *  6. Otherwise [SemanticContentState.Ready].
 */
object SemanticContentPolicy {

    /** See the object KDoc for the full rule order. */
    fun decide(
        consentEnabled: Boolean,
        engineAvailable: Boolean,
        semanticModelsReady: Boolean,
        clipCount: Long,
        errorMessage: String? = null,
    ): SemanticContentState = when {
        errorMessage != null -> SemanticContentState.Error(errorMessage)
        !consentEnabled -> SemanticContentState.ConsentOff
        !engineAvailable -> SemanticContentState.EngineMissing
        !semanticModelsReady -> SemanticContentState.ModelsNotProvisioned
        clipCount <= 0L -> SemanticContentState.NotIndexed
        else -> SemanticContentState.Ready
    }
}
