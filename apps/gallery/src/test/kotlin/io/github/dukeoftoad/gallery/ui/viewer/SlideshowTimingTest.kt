package io.github.dukeoftoad.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the slideshow's pure timing core (r27, the MI-style
 * organisational UX round; ADR-0016 --- [SlideshowTiming] and
 * [ViewerCrossfade] are pure Kotlin, so the interval catalog, the
 * wrap-around page math, and the crossfade shape are pinned here instead
 * of living implicitly inside `ViewerScreen`'s coroutine loop and
 * graphics-layer lambdas).
 *
 * What is pinned:
 * - the interval catalog (3 / 5 / 10 seconds ascending) and the 5s
 *   default;
 * - [SlideshowTiming.coerceInterval]: exact options round-trip, the
 *   NEAREST option wins, ties snap to the SHORTER option (an impatient
 *   user's mistake should cost less, not more), non-positive values
 *   yield the default;
 * - [SlideshowTiming.nextIndex]: the wrap-around rule (after the last
 *   page, back to the first), degenerate counts answer 0;
 * - [ViewerCrossfade.pageAlpha]: the settled page is fully opaque, alpha
 *   falls linearly with distance from the settling position, a full page
 *   away is fully transparent, and the answer never goes negative.
 */
class SlideshowTimingTest {

    // ------------------------------------------------------------------
    // The interval catalog
    // ------------------------------------------------------------------

    @Test
    fun `the interval catalog is three ascending options with a 5s default`() {
        assertEquals(listOf(3_000L, 5_000L, 10_000L), SlideshowTiming.INTERVALS_MILLIS)
        assertEquals(5_000L, SlideshowTiming.DEFAULT_INTERVAL_MILLIS)
    }

    @Test
    fun `an exact catalog option round-trips through coerceInterval`() {
        SlideshowTiming.INTERVALS_MILLIS.forEach { option ->
            assertEquals(option, SlideshowTiming.coerceInterval(option))
        }
    }

    @Test
    fun `the nearest option wins and ties snap to the shorter one`() {
        // 4000 is equidistant between 3000 and 5000: the tie goes to the
        // SHORTER (3000) --- the documented impatient-user rule.
        assertEquals(3_000L, SlideshowTiming.coerceInterval(4_000L))
        // One millisecond either side breaks the tie.
        assertEquals(3_000L, SlideshowTiming.coerceInterval(3_999L))
        assertEquals(5_000L, SlideshowTiming.coerceInterval(4_001L))
        // 7000 is equidistant between 5000 and 10000: the shorter wins.
        assertEquals(5_000L, SlideshowTiming.coerceInterval(7_000L))
        // Clear nearest cases on both flanks and in the middle.
        assertEquals(3_000L, SlideshowTiming.coerceInterval(1_000L))
        assertEquals(3_000L, SlideshowTiming.coerceInterval(2_900L))
        assertEquals(5_000L, SlideshowTiming.coerceInterval(5_500L))
        assertEquals(5_000L, SlideshowTiming.coerceInterval(6_000L))
        assertEquals(10_000L, SlideshowTiming.coerceInterval(8_000L))
        assertEquals(10_000L, SlideshowTiming.coerceInterval(60_000L))
    }

    @Test
    fun `non-positive values yield the default - never a negative sleep`() {
        assertEquals(SlideshowTiming.DEFAULT_INTERVAL_MILLIS, SlideshowTiming.coerceInterval(0L))
        assertEquals(SlideshowTiming.DEFAULT_INTERVAL_MILLIS, SlideshowTiming.coerceInterval(-1L))
        assertEquals(SlideshowTiming.DEFAULT_INTERVAL_MILLIS, SlideshowTiming.coerceInterval(-60_000L))
    }

    // ------------------------------------------------------------------
    // The wrap-around advance math
    // ------------------------------------------------------------------

    @Test
    fun `the slideshow advances one page and wraps to the first after the last`() {
        assertEquals(1, SlideshowTiming.nextIndex(0, 3))
        assertEquals(2, SlideshowTiming.nextIndex(1, 3))
        // The loop rule: the page after the last is the first.
        assertEquals(0, SlideshowTiming.nextIndex(2, 3))
        assertEquals(0, SlideshowTiming.nextIndex(9, 10))
    }

    @Test
    fun `degenerate counts answer the only honest page`() {
        // Nothing to advance: a one-page window (or an empty one) stays
        // on page 0 --- never a crash from an out-of-bounds advance.
        assertEquals(0, SlideshowTiming.nextIndex(0, 1))
        assertEquals(0, SlideshowTiming.nextIndex(5, 1))
        assertEquals(0, SlideshowTiming.nextIndex(0, 0))
        assertEquals(0, SlideshowTiming.nextIndex(3, -2))
    }

    // ------------------------------------------------------------------
    // The crossfade shape
    // ------------------------------------------------------------------

    @Test
    fun `the settled page is fully opaque`() {
        assertEquals(1f, ViewerCrossfade.pageAlpha(0, 0f), 0f)
        assertEquals(1f, ViewerCrossfade.pageAlpha(3, 3f), 0f)
    }

    @Test
    fun `alpha falls linearly with distance from the settling position`() {
        // Halfway between pages 0 and 1: both sit at distance 0.5.
        assertEquals(0.5f, ViewerCrossfade.pageAlpha(0, 0.5f), 1e-6f)
        assertEquals(0.5f, ViewerCrossfade.pageAlpha(1, 0.5f), 1e-6f)
        // A quarter of the way into page 1: page 0 is 0.75 gone, page 1
        // is 0.75 arrived.
        assertEquals(0.25f, ViewerCrossfade.pageAlpha(0, 0.75f), 1e-6f)
        assertEquals(0.75f, ViewerCrossfade.pageAlpha(1, 0.75f), 1e-6f)
    }

    @Test
    fun `a full page away is fully transparent and the alpha never goes negative`() {
        assertEquals(0f, ViewerCrossfade.pageAlpha(0, 1f), 0f)
        assertEquals(0f, ViewerCrossfade.pageAlpha(0, 2.5f), 0f)
        assertEquals(0f, ViewerCrossfade.pageAlpha(4, 0f), 0f)
        // The clamp: distances beyond 1 must answer 0, never a negative
        // (an invalid layer property).
        assertTrue(ViewerCrossfade.pageAlpha(0, 7f) >= 0f)
        assertTrue(ViewerCrossfade.pageAlpha(2, -3f) >= 0f)
    }
}
