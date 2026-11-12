package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ProSlider arithmetic's JVM pins (ADR-0011: the scrub, the tick
 * predicate, the live rule and the track geometry are pure Float math,
 * so they are tested here and only the drawing rides the screenshot
 * harness).
 *
 * The pins are the properties the C1 contract leans on: a full row-width
 * drag maps exactly to the range span (no gain drift), fine mode is
 * exactly one tenth, scrubs never escape the range, the zero tick fires
 * on crossings and landings but not on same-side moves, the pill's live
 * rule flips exactly at the neutral, and the bipolar track puts zero at
 * its geometric center (the notch's honesty guard).
 */
class LilypadSliderMathTest {

    private val bipolar = -100f..100f

    private fun assertClose(expected: Float, actual: Float, epsilon: Float = 0.0001f) {
        assertTrue("expected $expected but was $actual", Math.abs(expected - actual) <= epsilon)
    }

    // ---- scrub ---

    @Test
    fun `a full row-width drag maps the whole span`() {
        assertClose(100f, LilypadSliderMath.scrub(0f, 0f, 400f, 400f, bipolar))
        assertClose(-100f, LilypadSliderMath.scrub(0f, 400f, 0f, 400f, bipolar))
    }

    @Test
    fun `a half row-width drag maps half the span`() {
        // 200px over a 400px track is half the track: half of the 200-wide
        // bipolar span is +100.
        assertClose(100f, LilypadSliderMath.scrub(0f, 100f, 300f, 400f, bipolar))
    }

    @Test
    fun `fine mode is exactly one tenth`() {
        // A full-width drag moves the whole span (+200) at normal gain;
        // the same drag in fine mode moves exactly one tenth of it (+20).
        assertClose(20f, LilypadSliderMath.scrub(0f, 0f, 400f, 400f, bipolar, fine = true))
    }

    @Test
    fun `scrubs clamp at both ends`() {
        assertClose(100f, LilypadSliderMath.scrub(90f, 0f, 400f, 400f, bipolar))
        assertClose(-100f, LilypadSliderMath.scrub(-90f, 400f, 0f, 400f, bipolar))
    }

    @Test
    fun `a degenerate track degrades to the anchored value`() {
        assertClose(15f, LilypadSliderMath.scrub(15f, 0f, 300f, 0f, bipolar))
        assertClose(15f, LilypadSliderMath.scrub(15f, 0f, 300f, -5f, bipolar))
    }

    // ---- crossedNeutral ---

    @Test
    fun `a crossing fires the tick`() {
        assertTrue(LilypadSliderMath.crossedNeutral(-1f, 1f, 0f))
        assertTrue(LilypadSliderMath.crossedNeutral(1f, -1f, 0f))
    }

    @Test
    fun `a landing fires the tick`() {
        assertTrue(LilypadSliderMath.crossedNeutral(-1f, 0f, 0f))
        assertTrue(LilypadSliderMath.crossedNeutral(1f, 0f, 0f))
    }

    @Test
    fun `a same-side move does not fire`() {
        assertFalse(LilypadSliderMath.crossedNeutral(1f, 2f, 0f))
        assertFalse(LilypadSliderMath.crossedNeutral(-5f, -1f, 0f))
    }

    @Test
    fun `the predicate is neutral-position honest`() {
        assertTrue(LilypadSliderMath.crossedNeutral(9f, 11f, 10f))
        assertFalse(LilypadSliderMath.crossedNeutral(11f, 12f, 10f))
    }

    // ---- isLive ---

    @Test
    fun `the pill flips exactly at the neutral`() {
        assertFalse(LilypadSliderMath.isLive(0f, 0f))
        assertFalse(LilypadSliderMath.isLive(100f, 100f))
        assertTrue(LilypadSliderMath.isLive(0.001f, 0f))
        assertTrue(LilypadSliderMath.isLive(-30f, 0f))
    }

    // ---- signedValueText ---

    @Test
    fun `the signed value text reads its direction`() {
        assertEquals("0", LilypadSliderMath.signedValueText(0))
        assertEquals("+5", LilypadSliderMath.signedValueText(5))
        assertEquals("-30", LilypadSliderMath.signedValueText(-30))
        assertEquals("+100", LilypadSliderMath.signedValueText(100))
    }

    // ---- fraction ---

    @Test
    fun `the bipolar track puts zero at its center`() {
        assertClose(0.5f, LilypadSliderMath.fraction(0f, bipolar))
        assertClose(0f, LilypadSliderMath.fraction(-100f, bipolar))
        assertClose(1f, LilypadSliderMath.fraction(100f, bipolar))
    }

    @Test
    fun `a unipolar track maps linearly`() {
        assertClose(0.5f, LilypadSliderMath.fraction(50f, 0f..100f))
        assertClose(0f, LilypadSliderMath.fraction(0f, 0f..100f))
        assertClose(1f, LilypadSliderMath.fraction(100f, 0f..100f))
    }

    @Test
    fun `an out-of-range value clamps inside the track`() {
        assertClose(1f, LilypadSliderMath.fraction(150f, bipolar))
        assertClose(0f, LilypadSliderMath.fraction(-150f, bipolar))
    }

    // ---- isSymmetric ---

    @Test
    fun `the notch guard admits only center neutrals`() {
        assertTrue(LilypadSliderMath.isSymmetric(bipolar, 0f))
        assertTrue(LilypadSliderMath.isSymmetric(-15f..15f, 0f))
        assertFalse(LilypadSliderMath.isSymmetric(0f..100f, 0f))
        assertFalse(LilypadSliderMath.isSymmetric(bipolar, 10f))
    }

    // ---- integration ---

    @Test
    fun `a drag through zero crosses exactly once`() {
        var crossed = 0
        var prev = 10f
        // Ten steps from +10 to -10, the drag's emission cadence.
        for (step in 1..10) {
            val next = 10f - 2f * step
            if (LilypadSliderMath.crossedNeutral(prev, next, 0f)) crossed++
            prev = next
        }
        assertEquals(1, crossed)
    }

    @Test
    fun `a stay-positive drag never crosses`() {
        var crossed = false
        var prev = 10f
        for (step in 1..10) {
            val next = 10f + step.toFloat()
            if (LilypadSliderMath.crossedNeutral(prev, next, 0f)) crossed = true
            prev = next
        }
        assertFalse(crossed)
    }
}
