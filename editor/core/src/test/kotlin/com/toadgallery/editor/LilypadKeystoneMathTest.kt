package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keystone sliders' JVM pins (r68, the photo pro round): the
 * two-percent quad shape (0 = identity, the slider's end = the engine's
 * own clamp bound), the exact read-back round-trip, the projection's
 * coercion, and the preview-to-export grid scaling that keeps the two
 * grids showing one warp. Pure JVM, no compose/android (ADR-0011) --- one
 * of the Lilypad-prefixed set the editor CI filter runs.
 */
class LilypadKeystoneMathTest {

    // ---- quadFor: the shape the two percents carve ----

    @Test
    fun `zero in both percents is exactly the identity quad`() {
        assertEquals(
            PerspectiveQuad.IDENTITY,
            LilypadKeystoneMath.quadFor(verticalPct = 0, horizontalPct = 0, width = 400, height = 200),
        )
    }

    @Test
    fun `a degenerate grid degenerates to the identity`() {
        // No decoded base yet: the only honest quad for a grid that does
        // not exist.
        assertEquals(
            PerspectiveQuad.IDENTITY,
            LilypadKeystoneMath.quadFor(verticalPct = 80, horizontalPct = -60, width = 0, height = 0),
        )
    }

    @Test
    fun `positive vertical pinches the top edge symmetrically and moves nothing else`() {
        val quad = LilypadKeystoneMath.quadFor(verticalPct = 50, horizontalPct = 0, width = 400, height = 200)
        // 50% of the X clamp bound (400 / 4 = 100) = 50px.
        assertEquals(50.0, quad.tlX, 1e-9)
        assertEquals(-50.0, quad.trX, 1e-9)
        // The left edge and both bottom corners are the foundation: they
        // never move for a top-edge correction.
        assertEquals(0.0, quad.tlY, 1e-9)
        assertEquals(0.0, quad.blY, 1e-9)
        assertEquals(0.0, quad.brX, 1e-9)
        assertEquals(0.0, quad.brY, 1e-9)
    }

    @Test
    fun `positive horizontal pinches the left edge symmetrically and moves nothing else`() {
        val quad = LilypadKeystoneMath.quadFor(verticalPct = 0, horizontalPct = 25, width = 400, height = 200)
        // 25% of the Y clamp bound (200 / 4 = 50) = 12.5px.
        assertEquals(12.5, quad.tlY, 1e-9)
        assertEquals(-12.5, quad.blY, 1e-9)
        assertEquals(0.0, quad.tlX, 1e-9)
        assertEquals(0.0, quad.trX, 1e-9)
        assertEquals(0.0, quad.brX, 1e-9)
        assertEquals(0.0, quad.brY, 1e-9)
    }

    @Test
    fun `the slider's end lands exactly on the engine's own clamp bound`() {
        // The span IS the legal span: no dead zone at either end.
        val quad = LilypadKeystoneMath.quadFor(verticalPct = 100, horizontalPct = -100, width = 400, height = 200)
        assertEquals(100.0, quad.tlX, 1e-9) // w/4
        assertEquals(-100.0, quad.trX, 1e-9)
        assertEquals(-50.0, quad.tlY, 1e-9) // -h/4
        assertEquals(50.0, quad.blY, 1e-9)
        // And the clamped warp the engine runs agrees: clamping a quad
        // built at the bound is a no-op.
        assertEquals(quad, quad.clamped(400, 200))
    }

    // ---- the read-back round-trip ----

    @Test
    fun `the percents read back exactly from the quad they built`() {
        for (v in -100..100 step 7) {
            for (h in -100..100 step 11) {
                val quad = LilypadKeystoneMath.quadFor(v, h, 400, 200)
                assertEquals("vertical $v", v, LilypadKeystoneMath.verticalPctOf(quad, 400))
                assertEquals("horizontal $h", h, LilypadKeystoneMath.horizontalPctOf(quad, 200))
            }
        }
    }

    @Test
    fun `the round-trip is exact on odd dimensions too`() {
        // w/4 and h/4 are fractional here; the double arithmetic still
        // cancels, and the roundToInt absorbs the float noise.
        for (v in -100..100 step 13) {
            val quad = LilypadKeystoneMath.quadFor(v, 0, 99, 301)
            assertEquals(v, LilypadKeystoneMath.verticalPctOf(quad, 99))
        }
        for (h in -100..100 step 13) {
            val quad = LilypadKeystoneMath.quadFor(0, h, 99, 301)
            assertEquals(h, LilypadKeystoneMath.horizontalPctOf(quad, 301))
        }
    }

    @Test
    fun `the identity quad reads zero in both`() {
        assertEquals(0, LilypadKeystoneMath.verticalPctOf(PerspectiveQuad.IDENTITY, 400))
        assertEquals(0, LilypadKeystoneMath.horizontalPctOf(PerspectiveQuad.IDENTITY, 200))
    }

    @Test
    fun `a beyond-clamp displacement reads as the bound, not as a phantom value`() {
        // A hand-edited sidecar can carry anything; the warp clips at
        // +/- w/4 anyway, so the honest displayable percent is the bound.
        val hostile = PerspectiveQuad(
            tlX = 200.0, tlY = -400.0, trX = 0.0, trY = 0.0,
            brX = 0.0, brY = 0.0, blX = 0.0, blY = 0.0,
        )
        assertEquals(100, LilypadKeystoneMath.verticalPctOf(hostile, 400))
        assertEquals(-100, LilypadKeystoneMath.horizontalPctOf(hostile, 200))
    }

    @Test
    fun `a missing grid reads zero`() {
        assertEquals(0, LilypadKeystoneMath.verticalPctOf(PerspectiveQuad.IDENTITY, 0))
        assertEquals(0, LilypadKeystoneMath.horizontalPctOf(PerspectiveQuad.IDENTITY, 0))
    }

    // ---- the grid scaling ----

    @Test
    fun `scaling a preview-grid quad to the export grid equals building it there`() {
        // The whole point of the export pass: the percents are the
        // resolution-independent truth both grids agree on.
        val preview = LilypadKeystoneMath.quadFor(verticalPct = 40, horizontalPct = -30, width = 100, height = 50)
        val scaled = LilypadKeystoneMath.scaledForExport(preview, 100, 50, 400, 200)
        val built = LilypadKeystoneMath.quadFor(verticalPct = 40, horizontalPct = -30, width = 400, height = 200)
        assertEquals(built.tlX, scaled.tlX, 1e-9)
        assertEquals(built.tlY, scaled.tlY, 1e-9)
        assertEquals(built.trX, scaled.trX, 1e-9)
        assertEquals(built.blY, scaled.blY, 1e-9)
    }

    @Test
    fun `the identity quad survives the export scaling exactly`() {
        assertEquals(
            PerspectiveQuad.IDENTITY,
            LilypadKeystoneMath.scaledForExport(PerspectiveQuad.IDENTITY, 100, 50, 400, 200),
        )
    }

    @Test
    fun `a missing reference grid returns the quad verbatim`() {
        val quad = LilypadKeystoneMath.quadFor(verticalPct = 60, horizontalPct = 60, width = 400, height = 200)
        assertEquals(quad, LilypadKeystoneMath.scaledForExport(quad, 0, 0, 400, 200))
    }

    // ---- the pins ----

    @Test
    fun `the sliders' span is the recipe's own bipolar convention`() {
        assertEquals(-100, LilypadKeystoneMath.KEYSTONE_MIN)
        assertEquals(100, LilypadKeystoneMath.KEYSTONE_MAX)
    }
}
