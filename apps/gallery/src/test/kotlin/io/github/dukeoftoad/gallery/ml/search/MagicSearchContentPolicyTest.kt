package io.github.dukeoftoad.gallery.ml.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the Magic Search content-state mapping (r26, Workstream
 * 7; ADR-0016 --- [MagicSearchContentPolicy] is pure Kotlin over plain
 * values, so the "which state does the user see" rules are pinned here
 * instead of living implicitly in the ViewModel's control flow; the
 * same shape as the r25 `PeopleContentPolicyTest`).
 *
 * What is pinned (all six states + the dominance order):
 * - ERROR DOMINANCE: a non-null error message wins over consent, engine
 *   presence, model packs and clip rows --- a failed read can never mask
 *   as a presentable state;
 * - the CONSENT gate decides before capability: consent off shows
 *   ConsentOff even when everything else would pass (ADR-0010's opt-in
 *   rule --- querying content while the toggle says off would present
 *   data the user's own choice withholds);
 * - consent on + engine missing -> EngineMissing (the build shape);
 * - consent + engine but EITHER model pack incomplete ->
 *   ModelsNotProvisioned (capability before emptiness);
 * - all gates pass but ZERO clip rows -> NotIndexed (the queue has not
 *   run / is still draining);
 * - everything pass + rows -> Ready.
 */
class MagicSearchContentPolicyTest {

    @Test
    fun `error dominates everything - consent, engine, models and rows cannot rescue a failed read`() {
        val state = MagicSearchContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            faceModelsReady = true,
            semanticModelsReady = true,
            clipCount = 500L,
            errorMessage = "face index store is not open",
        )
        assertEquals(MagicSearchState.Error("face index store is not open"), state)

        // Also over the consent-off, engine-missing and models-missing arms:
        assertEquals(
            MagicSearchState.Error("boom"),
            MagicSearchContentPolicy.decide(false, false, false, false, 0L, "boom"),
        )
    }

    @Test
    fun `consent off wins even over a fully indexed store - the opt-in gate`() {
        val state = MagicSearchContentPolicy.decide(
            consentEnabled = false,
            engineAvailable = true,
            faceModelsReady = true,
            semanticModelsReady = true,
            clipCount = 1_000L,
        )
        assertEquals(MagicSearchState.ConsentOff, state)

        // Consent off + engine missing + no models is still ConsentOff
        // (consent first):
        assertEquals(
            MagicSearchState.ConsentOff,
            MagicSearchContentPolicy.decide(false, false, false, false, 0L),
        )
    }

    @Test
    fun `consent on but engine missing is the honest build-shape state`() {
        val state = MagicSearchContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = false,
            faceModelsReady = true,
            semanticModelsReady = true,
            clipCount = 500L,
        )
        assertEquals(MagicSearchState.EngineMissing, state)
    }

    @Test
    fun `an incomplete FACE pack is models-not-provisioned - the session is face-based in r26`() {
        val state = MagicSearchContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            faceModelsReady = false,
            semanticModelsReady = true,
            clipCount = 500L, // rows exist, but the session cannot open
        )
        assertEquals(MagicSearchState.ModelsNotProvisioned, state)
    }

    @Test
    fun `an incomplete SEMANTIC pack is models-not-provisioned - capability before emptiness`() {
        val state = MagicSearchContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            faceModelsReady = true,
            semanticModelsReady = false,
            clipCount = 500L, // rows exist, but there is nothing to embed with
        )
        assertEquals(MagicSearchState.ModelsNotProvisioned, state)

        // And the all-gates-pass-but-empty-index shape is distinct:
        assertEquals(
            MagicSearchState.NotIndexed,
            MagicSearchContentPolicy.decide(true, true, true, true, 0L),
        )
    }

    @Test
    fun `all gates pass with zero clip rows is not-indexed - including the queue still draining`() {
        val state = MagicSearchContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            faceModelsReady = true,
            semanticModelsReady = true,
            clipCount = 0L,
        )
        assertEquals(MagicSearchState.NotIndexed, state)
    }

    @Test
    fun `every gate passing with rows indexed is ready`() {
        val state = MagicSearchContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            faceModelsReady = true,
            semanticModelsReady = true,
            clipCount = 1L,
        )
        assertEquals(MagicSearchState.Ready, state)
    }
}
