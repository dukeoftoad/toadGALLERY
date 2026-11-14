package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export preflight: the large-file decode guard, the storage byte model + 1.5x-headroom decision,
 * and the process-death policy.
 */
class ExportPreflightTest {

    // ---- estimateBytes: the deliberately generous model ----

    @Test
    fun `png ignores quality (lossless)`() {
        assertEquals(
            ExportPreflight.estimateBytes(1_000_000L, ExportFormat.PNG, 1),
            ExportPreflight.estimateBytes(1_000_000L, ExportFormat.PNG, 100),
        )
    }

    @Test
    fun `lossy estimates rise monotonically with quality`() {
        var previous = -1L
        for (q in 1..100) {
            val estimate = ExportPreflight.estimateBytes(1_000_000L, ExportFormat.JPEG, q)
            assertTrue("quality $q must not regress", estimate >= previous)
            previous = estimate
        }
    }

    @Test
    fun `the lossy formula spot-checks at the documented points`() {
        // raw = pixels x 4 = 4_000_000; (5 + q) / 100 scaling, rounded down.
        assertEquals(4_000_000L, ExportPreflight.estimateBytes(1_000_000L, ExportFormat.JPEG, 95))
        assertEquals(2_200_000L, ExportPreflight.estimateBytes(1_000_000L, ExportFormat.JPEG, 50))
        assertEquals(4_200_000L, ExportPreflight.estimateBytes(1_000_000L, ExportFormat.JPEG, 100))
        assertEquals(960_000L, ExportPreflight.estimateBytes(1_000_000L, ExportFormat.WEBP, 19))
        assertEquals(0L, ExportPreflight.estimateBytes(0L, ExportFormat.JPEG, 95))
    }

    @Test
    fun `the default-quality jpeg estimate is conservative (equals the raw size)`() {
        // At q95 one megapixel costs its raw ARGB_8888 footprint --- an over-estimate of any real encode.
        assertEquals(4L * 1_000_000L, ExportPreflight.estimateBytes(1_000_000L, ExportFormat.JPEG, 95))
    }

    // ---- decide: the 1.5x-headroom storage rule ----

    @Test
    fun `ample storage proceeds`() {
        assertEquals(
            ExportPreflight.Decision.PROCEED,
            ExportPreflight.decide(1_000_000L, 10_000_000L),
        )
    }

    @Test
    fun `exactly the headroom boundary proceeds --- one byte below refuses`() {
        // need = est + est/2 = 1_500_000 for est = 1_000_000.
        assertEquals(
            ExportPreflight.Decision.PROCEED,
            ExportPreflight.decide(1_000_000L, 1_500_000L),
        )
        assertEquals(
            ExportPreflight.Decision.INSUFFICIENT,
            ExportPreflight.decide(1_000_000L, 1_499_999L),
        )
    }

    @Test
    fun `zero-byte estimates always proceed`() {
        assertEquals(ExportPreflight.Decision.PROCEED, ExportPreflight.decide(0L, 0L))
    }

    // ---- decodeGuard: the large-file cap ----

    @Test
    fun `unknown bounds pass the guard (the decode answers the real verdict)`() {
        assertEquals(ExportPreflight.Decision.PROCEED, ExportPreflight.decodeGuard(0, 0, ExportPreflight.MAX_EXPORT_PIXELS))
        assertEquals(ExportPreflight.Decision.PROCEED, ExportPreflight.decodeGuard(-1, 100, ExportPreflight.MAX_EXPORT_PIXELS))
    }

    @Test
    fun `the cap refuses strictly-over and admits exactly-at`() {
        // 8000 x 6000 = 48_000_000 = the cap exactly -> allowed.
        assertEquals(
            ExportPreflight.Decision.PROCEED,
            ExportPreflight.decodeGuard(8000, 6000, ExportPreflight.MAX_EXPORT_PIXELS),
        )
        // One pixel over -> refused before any decode work.
        assertEquals(
            ExportPreflight.Decision.INSUFFICIENT,
            ExportPreflight.decodeGuard(8001, 6000, ExportPreflight.MAX_EXPORT_PIXELS),
        )
        assertEquals(
            ExportPreflight.Decision.PROCEED,
            ExportPreflight.decodeGuard(4096, 3072, ExportPreflight.MAX_EXPORT_PIXELS),
        )
    }

    @Test
    fun `the assumed-pixel fallback sits well under the cap`() {
        // The storage estimate's fallback size must never trip the decode guard it shares the preflight with.
        assertTrue(ExportPreflight.ASSUMED_PIXELS < ExportPreflight.MAX_EXPORT_PIXELS)
        assertEquals(48_000_000L, ExportPreflight.MAX_EXPORT_PIXELS)
        assertEquals(12_000_000L, ExportPreflight.ASSUMED_PIXELS)
    }

    // ---- onProcessDeath: the interruption policy ----

    @Test
    fun `process death on Q+ leaves a reclaimed pending row`() {
        assertEquals(
            ExportPreflight.Interruption.PENDING_RECLAIMED_BY_MEDIASTORE,
            ExportPreflight.onProcessDeath(api29Plus = true),
        )
    }

    @Test
    fun `process death pre-Q may leave a visible partial row (no delete permission)`() {
        assertEquals(
            ExportPreflight.Interruption.PARTIAL_ROW_VISIBLE_PRE_Q,
            ExportPreflight.onProcessDeath(api29Plus = false),
        )
    }
}
