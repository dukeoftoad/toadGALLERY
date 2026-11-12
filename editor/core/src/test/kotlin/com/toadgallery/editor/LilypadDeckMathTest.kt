package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Deck arithmetic's JVM pins (ADR-0011: the heights, the snap ladder
 * and the tap toggle are pure arithmetic, so they are tested here and
 * only the sheet's drawing and spring ride the compose surface).
 *
 * The pins are the properties the chassis leans on: the collapsed peek is
 * a fixed dp height, the working fractions are exactly 45% and 78%, every
 * height respects the available space (and a degenerate chassis yields
 * exactly zero), a drag snaps at most one step along the ladder with the
 * threshold read from both sides, and the tap toggle cycles the working
 * pair.
 */
class LilypadDeckMathTest {

    // ---- targetHeightPx ---

    @Test
    fun `the collapsed peek is the fixed dp height`() {
        assertEquals(192f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Collapsed, 2400f, 3f))
        assertEquals(168f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Collapsed, 2400f, 2.625f))
        assertEquals(64f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Collapsed, 2400f, 1f))
    }

    @Test
    fun `half and full are exact fractions of the available height`() {
        assertEquals(45f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Half, 100f, 2f))
        assertEquals(78f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Full, 100f, 2f))
    }

    @Test
    fun `the states order strictly on a real chassis`() {
        val max = 2400f
        val density = 2.625f
        val collapsed = LilypadDeckMath.targetHeightPx(LilypadDeckState.Collapsed, max, density)
        val half = LilypadDeckMath.targetHeightPx(LilypadDeckState.Half, max, density)
        val full = LilypadDeckMath.targetHeightPx(LilypadDeckState.Full, max, density)
        assertTrue(collapsed < half)
        assertTrue(half < full)
        assertTrue(full < max)
    }

    @Test
    fun `no height ever exceeds the available space`() {
        // A tiny chassis clamps the fixed peek and shrinks the fractions;
        // the ordering invariant is only promised on a real chassis (the
        // one this Deck ships in --- see targetHeightPx's contract).
        assertEquals(50f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Collapsed, 50f, 2f))
        assertEquals(22.5f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Half, 50f, 2f))
        assertEquals(39f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Full, 50f, 2f))
    }

    @Test
    fun `a degenerate chassis yields exactly zero`() {
        assertEquals(0f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Collapsed, 0f, 2f))
        assertEquals(0f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Half, -10f, 2f))
        assertEquals(0f, LilypadDeckMath.targetHeightPx(LilypadDeckState.Full, 0f, 2f))
    }

    // ---- snapAfterDrag ---

    @Test
    fun `a drag past the threshold moves exactly one step up`() {
        // Threshold at density 2 is 96px.
        assertEquals(LilypadDeckState.Half, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Collapsed, -100f, 2f))
        assertEquals(LilypadDeckState.Full, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Half, -100f, 2f))
        // The ladder's top is a wall.
        assertEquals(LilypadDeckState.Full, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Full, -100f, 2f))
    }

    @Test
    fun `a drag past the threshold moves exactly one step down`() {
        assertEquals(LilypadDeckState.Collapsed, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Half, 100f, 2f))
        assertEquals(LilypadDeckState.Half, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Full, 100f, 2f))
        // The ladder's bottom is a wall.
        assertEquals(LilypadDeckState.Collapsed, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Collapsed, 100f, 2f))
    }

    @Test
    fun `a sub-threshold drag changes nothing`() {
        assertEquals(LilypadDeckState.Collapsed, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Collapsed, -50f, 2f))
        assertEquals(LilypadDeckState.Half, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Half, 95f, 2f))
    }

    @Test
    fun `the threshold reads the same from both sides`() {
        // At density 2 the threshold is exactly 96px: 95 stays, 96 snaps.
        assertEquals(LilypadDeckState.Collapsed, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Collapsed, -95f, 2f))
        assertEquals(LilypadDeckState.Half, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Collapsed, -96f, 2f))
        assertEquals(LilypadDeckState.Half, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Half, 95f, 2f))
        assertEquals(LilypadDeckState.Collapsed, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Half, 96f, 2f))
        // And at another density the threshold scales: 3f -> 144px.
        assertEquals(LilypadDeckState.Collapsed, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Collapsed, -143f, 3f))
        assertEquals(LilypadDeckState.Half, LilypadDeckMath.snapAfterDrag(LilypadDeckState.Collapsed, -144f, 3f))
    }

    // ---- tapToggle ---

    @Test
    fun `the tap cycles the working pair and collapses the full state`() {
        assertEquals(LilypadDeckState.Half, LilypadDeckMath.tapToggle(LilypadDeckState.Collapsed))
        assertEquals(LilypadDeckState.Collapsed, LilypadDeckMath.tapToggle(LilypadDeckState.Half))
        assertEquals(LilypadDeckState.Collapsed, LilypadDeckMath.tapToggle(LilypadDeckState.Full))
    }
}
