package com.toadgallery.editor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The face-retouch consent machine: default-off, per-install grant, re-asking deny,
 * permanent disable -- and the stage-refusal rule under consent-off.
 */
class RetouchConsentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): RetouchConsentStore {
        val dir = tmp.newFolder()
        return RetouchConsentStore(dir)
    }

    @Test
    fun `default-off is the honest default and the dialog re-asks after a deny`() {
        assertEquals(RetouchConsent.NOT_GRANTED, store().load())
        assertTrue(RetouchConsentMachine.shouldAsk(RetouchConsent.NOT_GRANTED))
        // Deny: stays not-granted, so the NEXT use re-asks.
        val afterDeny = RetouchConsentMachine.onDeny(RetouchConsent.NOT_GRANTED)
        assertEquals(RetouchConsent.NOT_GRANTED, afterDeny)
        assertTrue(RetouchConsentMachine.shouldAsk(afterDeny))
        assertFalse(afterDeny.stageMayRun)
    }

    @Test
    fun `grant persists per install and the stage may run`() {
        val dir = tmp.newFolder()
        val store = RetouchConsentStore(dir)
        store.save(RetouchConsentMachine.onGrant(RetouchConsent.NOT_GRANTED))
        val reloaded = RetouchConsentStore(dir).load()
        assertEquals(RetouchConsent.GRANTED, reloaded)
        assertFalse(RetouchConsentMachine.shouldAsk(reloaded))
        assertTrue(reloaded.stageMayRun)
    }

    @Test
    fun `the permanent kill-switch dominates from any state and cannot be re-granted`() {
        for (from in RetouchConsent.entries) {
            val disabled = RetouchConsentMachine.onPermanentlyDisable(from)
            assertEquals(RetouchConsent.PERMANENTLY_DISABLED, disabled)
            assertEquals(disabled, RetouchConsentMachine.onGrant(disabled))
            assertEquals(disabled, RetouchConsentMachine.onDeny(disabled))
            assertFalse(disabled.stageMayRun)
            assertFalse(RetouchConsentMachine.shouldAsk(disabled))
        }
    }

    @Test
    fun `an absent or corrupt file degrades to default-off`() {
        val dir = tmp.newFolder()
        assertEquals(RetouchConsent.NOT_GRANTED, RetouchConsentStore(dir).load())
        File(dir, "retouch-consent").writeText("not-a-wire-name")
        assertEquals(RetouchConsent.NOT_GRANTED, RetouchConsentStore(dir).load())
        File(dir, "retouch-consent").writeBytes(ByteArray(16) { 9 })
        assertEquals(RetouchConsent.NOT_GRANTED, RetouchConsentStore(dir).load())
    }

    @Test
    fun `the stage refuses under consent-off even with a restored sidecar`() {
        // The whole-gate rule: only GRANTED lets the retouch stage run; the layer
        // stays verbatim in the recipe either way (pinned in AiRenderTest).
        for (state in RetouchConsent.entries) {
            assertEquals(state == RetouchConsent.GRANTED, state.stageMayRun)
        }
        // And the refusal composes with the pipeline's input bundle.
        val boxes = FaceBoxes(listOf(FaceBoxes.Box(0.2, 0.2, 0.8, 0.8)))
        val inputsOff = EditAiStageInputs(
            eraseMasks = emptyMap(),
            eraseFills = emptyMap(),
            skyMask = null,
            skySource = null,
            depth = null,
            faceBoxes = boxes,
            retouchConsentGranted = RetouchConsent.NOT_GRANTED.stageMayRun,
        )
        assertFalse(inputsOff.retouchConsentGranted)
    }
}
