package com.toadgallery.gallery.data.media.changes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the r40 scan-walk cancellation policy (the Phase 10
 * hardening round's boundedness fix).
 *
 * The policy is PURE (ADR-0016: the decision lives in a tested pure type,
 * the Android cursor walk only consults it), so these run in plain
 * `testDebugUnitTest` --- no emulator, no Robolectric. They pin:
 *
 *  - the probe period constant (500; changing it is a hardening-tuning
 *    decision that must be made consciously, not by drift),
 *  - the boundary semantics of [ScanWalkPolicy.cancellationDue] (never at
 *    zero or below, due exactly on every 500th row),
 *  - the probe COUNT a realistic walk performs (a 2,000-row walk probes
 *    exactly 4 times; a 50,000-row library probes exactly 100 --- the
 *    cancelled-walk tail is bounded by construction).
 */
class ScanWalkPolicyTest {

    @Test
    fun `probe period is the pinned 500`() {
        assertEquals(500, ScanWalkPolicy.CANCELLATION_CHECK_ROWS)
    }

    @Test
    fun `never due at zero or below`() {
        assertFalse(ScanWalkPolicy.cancellationDue(0))
        assertFalse(ScanWalkPolicy.cancellationDue(-1))
        assertFalse(ScanWalkPolicy.cancellationDue(-500))
    }

    @Test
    fun `due exactly on every 500th row`() {
        assertFalse(ScanWalkPolicy.cancellationDue(1))
        assertFalse(ScanWalkPolicy.cancellationDue(499))
        assertTrue(ScanWalkPolicy.cancellationDue(500))
        assertFalse(ScanWalkPolicy.cancellationDue(501))
        assertFalse(ScanWalkPolicy.cancellationDue(999))
        assertTrue(ScanWalkPolicy.cancellationDue(1000))
        assertTrue(ScanWalkPolicy.cancellationDue(50_000))
    }

    @Test
    fun `a realistic walk probes the documented number of times`() {
        fun probeCount(rows: Int): Int =
            (1..rows).count { ScanWalkPolicy.cancellationDue(it) }

        // A 2,000-row walk probes 4 times.
        assertEquals(4, probeCount(2_000))
        // A 50,000-row library (the roadmap's scale target) probes 100
        // times --- the cancelled-walk tail stays bounded on any device.
        assertEquals(100, probeCount(50_000))
        // An empty volume probes zero times.
        assertEquals(0, probeCount(0))
    }
}
