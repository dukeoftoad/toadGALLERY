package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zebra-clipping pins: per-channel zone classification and deterministic opposite-slope stripe tiling. */
class LilypadZebraMathTest {

    // ---- classify ----

    @Test
    fun `a white-clipped pixel is a highlight`() {
        assertEquals(
            LilypadZebraMath.Zone.HIGHLIGHT,
            LilypadZebraMath.classify(252, 251, 250),
        )
    }

    @Test
    fun `a single clipped channel is a highlight even when the pixel is not white`() {
        // A saturated red petal: R at the pin, G and B deep --- the channel's detail is gone.
        assertEquals(
            LilypadZebraMath.Zone.HIGHLIGHT,
            LilypadZebraMath.classify(250, 30, 20),
        )
        assertEquals(
            LilypadZebraMath.Zone.HIGHLIGHT,
            LilypadZebraMath.classify(10, 255, 40),
        )
        assertEquals(
            LilypadZebraMath.Zone.HIGHLIGHT,
            LilypadZebraMath.classify(0, 12, 250),
        )
    }

    @Test
    fun `a black-crushed pixel is a shadow`() {
        assertEquals(
            LilypadZebraMath.Zone.SHADOW,
            LilypadZebraMath.classify(4, 3, 5),
        )
        assertEquals(
            LilypadZebraMath.Zone.SHADOW,
            LilypadZebraMath.classify(5, 200, 180), // one channel pinned low
        )
    }

    @Test
    fun `tones between the pins are not clipped`() {
        assertEquals(LilypadZebraMath.Zone.NONE, LilypadZebraMath.classify(128, 128, 128))
        assertEquals(LilypadZebraMath.Zone.NONE, LilypadZebraMath.classify(249, 6, 130))
    }

    @Test
    fun `highlight wins over shadow at a pixel both pins could claim`() {
        // R pinned HIGH and B pinned LOW: the top-of-range clip is the one the warning must not hide.
        assertEquals(
            LilypadZebraMath.Zone.HIGHLIGHT,
            LilypadZebraMath.classify(255, 100, 0),
        )
    }

    @Test
    fun `one below the highlight pin and one above the shadow pin are clean`() {
        assertEquals(LilypadZebraMath.Zone.NONE, LilypadZebraMath.classify(249, 249, 249))
        assertEquals(LilypadZebraMath.Zone.NONE, LilypadZebraMath.classify(6, 6, 6))
    }

    // ---- the stripe tiling ----

    @Test
    fun `the highlight stripe tiles exactly half the cells on the diagonal`() {
        var on = 0
        val n = LilypadZebraMath.STRIPE_PERIOD * 40
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (LilypadZebraMath.highlightStripeOn(x, y)) on++
            }
        }
        // Duty 3 of period 6: exactly half, at any scale the period divides.
        assertEquals(n * n / 2, on)
    }

    @Test
    fun `the shadow stripe is the opposite diagonal`() {
        // Highlight tiling is constant along (1,-1) (x + y fixed); shadow along (1,1) (x - y fixed) ---
        // opposite slopes, so the two zones read differently where they meet.
        for (y in 0 until 12) {
            for (x in 0 until 12) {
                assertEquals(
                    LilypadZebraMath.highlightStripeOn(x, y),
                    LilypadZebraMath.highlightStripeOn(x + 1, y - 1),
                )
                assertEquals(
                    LilypadZebraMath.shadowStripeOn(x, y),
                    LilypadZebraMath.shadowStripeOn(x + 1, y + 1),
                )
            }
        }
        // Phase pin: the main diagonal sits at shadow-phase zero (always on) --- never a moire.
        for (i in 0 until 24) {
            assertTrue(LilypadZebraMath.shadowStripeOn(i, i))
        }
    }

    @Test
    fun `the stripes are periodic with the pinned period`() {
        for (y in 0 until 12) {
            for (x in 0 until 12) {
                assertEquals(
                    LilypadZebraMath.highlightStripeOn(x, y),
                    LilypadZebraMath.highlightStripeOn(x + LilypadZebraMath.STRIPE_PERIOD, y),
                )
                assertEquals(
                    LilypadZebraMath.shadowStripeOn(x, y),
                    LilypadZebraMath.shadowStripeOn(x, y + LilypadZebraMath.STRIPE_PERIOD),
                )
            }
        }
    }

    @Test
    fun `the tiling never goes negative on the subtraction diagonal`() {
        // x - y dips negative in the upper triangle; the mod must stay non-negative (Kotlin % keeps the sign).
        for (y in 0 until 12) {
            for (x in 0 until y) {
                LilypadZebraMath.shadowStripeOn(x, y) // must not throw
            }
        }
    }

    // ---- the pins ----

    @Test
    fun `the thresholds are the pinned constants`() {
        assertEquals(250, LilypadZebraMath.HIGHLIGHT_THRESHOLD)
        assertEquals(5, LilypadZebraMath.SHADOW_THRESHOLD)
        assertEquals(6, LilypadZebraMath.STRIPE_PERIOD)
        assertEquals(3, LilypadZebraMath.STRIPE_DUTY)
        assertEquals(4, LilypadZebraMath.DOWNSAMPLE)
    }
}
