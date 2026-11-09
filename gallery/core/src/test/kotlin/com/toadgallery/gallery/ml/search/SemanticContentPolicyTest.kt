package com.toadgallery.gallery.ml.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the semantic content-state mapping (r26 as
 * `MagicSearchContentPolicy`; r39 the rename to the domain name + the
 * FACE-input decoupling; ADR-0016 --- [SemanticContentPolicy] is pure
 * Kotlin over plain values, so the "which state does the user see"
 * rules are pinned here instead of living implicitly in a ViewModel's
 * control flow; the same shape as the r25 `PeopleContentPolicyTest`).
 *
 * What is pinned (all six decided states + the dominance order):
 * - ERROR DOMINANCE: a non-null error message wins over consent, engine
 *   presence, model packs and clip rows --- a failed read can never mask
 *   as a presentable state;
 * - the CONSENT gate decides before capability: consent off shows
 *   ConsentOff even when everything else would pass (ADR-0010's opt-in
 *   rule --- querying content while the toggle says off would present
 *   data the user's own choice withholds);
 * - consent on + engine missing -> EngineMissing (the build shape);
 * - consent + engine but the SEMANTIC pack incomplete ->
 *   ModelsNotProvisioned (capability before emptiness; the r39 shape:
 *   the FACE pack is no longer an input --- the semantic session
 *   installs without it);
 * - all gates pass but ZERO clip rows -> NotIndexed (the queue has not
 *   run / is still draining);
 * - everything pass + rows -> Ready.
 */
class SemanticContentPolicyTest {

    @Test
    fun `error dominates everything - consent, engine, models and rows cannot rescue a failed read`() {
        val state = SemanticContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            semanticModelsReady = true,
            clipCount = 500L,
            errorMessage = "face index store is not open",
        )
        assertEquals(SemanticContentState.Error("face index store is not open"), state)

        // Also over the consent-off, engine-missing and models-missing arms:
        assertEquals(
            SemanticContentState.Error("boom"),
            SemanticContentPolicy.decide(false, false, false, 0L, "boom"),
        )
    }

    @Test
    fun `consent off wins even over a fully indexed store - the opt-in gate`() {
        val state = SemanticContentPolicy.decide(
            consentEnabled = false,
            engineAvailable = true,
            semanticModelsReady = true,
            clipCount = 1_000L,
        )
        assertEquals(SemanticContentState.ConsentOff, state)

        // Consent off + engine missing + no models is still ConsentOff
        // (consent first):
        assertEquals(
            SemanticContentState.ConsentOff,
            SemanticContentPolicy.decide(false, false, false, 0L),
        )
    }

    @Test
    fun `consent on but engine missing is the honest build-shape state`() {
        val state = SemanticContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = false,
            semanticModelsReady = true,
            clipCount = 500L,
        )
        assertEquals(SemanticContentState.EngineMissing, state)
    }

    @Test
    fun `an incomplete SEMANTIC pack is models-not-provisioned - capability before emptiness`() {
        val state = SemanticContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            semanticModelsReady = false,
            clipCount = 500L, // rows exist, but there is nothing to embed with
        )
        assertEquals(SemanticContentState.ModelsNotProvisioned, state)

        // And the all-gates-pass-but-empty-index shape is distinct:
        assertEquals(
            SemanticContentState.NotIndexed,
            SemanticContentPolicy.decide(true, true, true, 0L),
        )
    }

    @Test
    fun `all gates pass with zero clip rows is not-indexed - including the queue still draining`() {
        val state = SemanticContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            semanticModelsReady = true,
            clipCount = 0L,
        )
        assertEquals(SemanticContentState.NotIndexed, state)
    }

    @Test
    fun `every gate passing with rows indexed is ready`() {
        val state = SemanticContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            semanticModelsReady = true,
            clipCount = 1L,
        )
        assertEquals(SemanticContentState.Ready, state)
    }

    @Test
    fun `the r39 decoupling - the FACE pack is no longer an input, semantic-only devices reach Ready`() {
        // The r26 policy took a faceModelsReady input and gated on it (the
        // face-based engine session's documented limitation). r39 drops
        // the input entirely: a device with ONLY the SEMANTIC pack ---
        // the semantic-only session's whole point --- passes every gate
        // and reaches Ready. This test exists so the decoupling is a
        // PINNED fact, not an accident of the signature: it cannot be
        // re-added without failing this suite's compile.
        val state = SemanticContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            semanticModelsReady = true,
            clipCount = 12L,
        )
        assertEquals(SemanticContentState.Ready, state)
    }
}
