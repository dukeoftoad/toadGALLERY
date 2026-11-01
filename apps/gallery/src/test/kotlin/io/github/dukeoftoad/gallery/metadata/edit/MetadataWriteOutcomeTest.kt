package io.github.dukeoftoad.gallery.metadata.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure failure-to-state mapping of the write round
 * (ADR-0016): [MetadataWriteFlow.classify] turns (raw outcome, API level,
 * grant state) into the UI's honest next action. The verified platform
 * inputs: `MediaStore.createWriteRequest` exists from API 30 only
 * (developer.android.com/reference/android/provider/MediaStore), so a
 * provider refusal below 30 is final, and above it the dialog is offered
 * exactly once per session (the grant machine pins the session half).
 */
class MetadataWriteOutcomeTest {

    // ---- The unambiguous outcomes ----

    @Test
    fun `SUCCESS classifies to Success regardless of platform and grant state`() {
        for (api in listOf(28, 29, 30, 34)) {
            for (state in WriteGrantState.entries) {
                assertEquals(
                    MetadataWriteResult.Success,
                    MetadataWriteFlow.classify(RawWriteOutcome.SUCCESS, api, state),
                )
            }
        }
    }

    @Test
    fun `FORMAT_REFUSED classifies to UNSUPPORTED_FORMAT regardless of platform and grant state`() {
        for (api in listOf(28, 29, 30, 34)) {
            for (state in WriteGrantState.entries) {
                assertEquals(
                    MetadataWriteResult.Refused(WriteRefusalReason.UNSUPPORTED_FORMAT),
                    MetadataWriteFlow.classify(RawWriteOutcome.FORMAT_REFUSED, api, state),
                )
            }
        }
    }

    @Test
    fun `IO classifies to IO regardless of platform and grant state`() {
        for (api in listOf(28, 29, 30, 34)) {
            for (state in WriteGrantState.entries) {
                assertEquals(
                    MetadataWriteResult.Refused(WriteRefusalReason.IO),
                    MetadataWriteFlow.classify(RawWriteOutcome.IO, api, state),
                )
            }
        }
    }

    // ---- SECURITY: the escalation decision ----

    @Test
    fun `SECURITY on API 30 plus at IDLE escalates to the write-grant dialog`() {
        for (api in listOf(30, 31, 34)) {
            assertEquals(
                MetadataWriteResult.NeedsGrant,
                MetadataWriteFlow.classify(RawWriteOutcome.SECURITY, api, WriteGrantState.IDLE),
            )
        }
    }

    @Test
    fun `SECURITY on API 30 plus after a grant is READ_ONLY_STILL_BLOCKED - single shot`() {
        assertEquals(
            MetadataWriteResult.Refused(WriteRefusalReason.READ_ONLY_STILL_BLOCKED),
            MetadataWriteFlow.classify(RawWriteOutcome.SECURITY, 30, WriteGrantState.GRANTED),
        )
    }

    @Test
    fun `SECURITY on API 30 plus in a stray grant state stays conservative`() {
        // An attempt outside the IDLE/GRANTED discipline (a stray retry
        // while the dialog is up, after a denial, or in a terminal
        // READ_ONLY) never earns a second dialog.
        for (state in listOf(
            WriteGrantState.NEEDS_GRANT,
            WriteGrantState.REQUESTED,
            WriteGrantState.DENIED,
            WriteGrantState.READ_ONLY,
        )) {
            assertEquals(
                MetadataWriteResult.Refused(WriteRefusalReason.READ_ONLY_STILL_BLOCKED),
                MetadataWriteFlow.classify(RawWriteOutcome.SECURITY, 34, state),
            )
        }
    }

    @Test
    fun `SECURITY below API 30 is READ_ONLY_PLATFORM - the dialog does not exist there`() {
        for (api in listOf(28, 29)) {
            for (state in WriteGrantState.entries) {
                assertEquals(
                    MetadataWriteResult.Refused(WriteRefusalReason.READ_ONLY_PLATFORM),
                    MetadataWriteFlow.classify(RawWriteOutcome.SECURITY, api, state),
                )
            }
        }
    }

    // ---- The escalation boundary itself ----

    @Test
    fun `the createWriteRequest floor is exactly API 30`() {
        assertEquals(30, MetadataWriteFlow.API_30)
    }

    @Test
    fun `every raw outcome maps to a distinct honest UI action at the boundary`() {
        // One API-30 device, one IDLE session: the four raw kinds take four
        // different paths --- save, ask, refuse-because-format,
        // refuse-because-io. Nothing collapses into a generic failure.
        assertEquals(
            MetadataWriteResult.Success,
            MetadataWriteFlow.classify(RawWriteOutcome.SUCCESS, 30, WriteGrantState.IDLE),
        )
        assertEquals(
            MetadataWriteResult.NeedsGrant,
            MetadataWriteFlow.classify(RawWriteOutcome.SECURITY, 30, WriteGrantState.IDLE),
        )
        assertEquals(
            MetadataWriteResult.Refused(WriteRefusalReason.UNSUPPORTED_FORMAT),
            MetadataWriteFlow.classify(RawWriteOutcome.FORMAT_REFUSED, 30, WriteGrantState.IDLE),
        )
        assertEquals(
            MetadataWriteResult.Refused(WriteRefusalReason.IO),
            MetadataWriteFlow.classify(RawWriteOutcome.IO, 30, WriteGrantState.IDLE),
        )
    }
}
