package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The straighten dial's JVM pins (r68, the photo pro round): the clock
 * angle convention (0 at twelve, positive clockwise, the seam stable),
 * the shortest-way-round delta, the rotary scrub with and without the
 * fine gain, the zero detent and the range bounds. Pure JVM, no
 * compose/android (ADR-0011) --- one of the Lilypad-prefixed set the
 * editor CI filter runs.
 */
class LilypadDialMathTest {

    // ---- vectorAngleDeg: the quadrants ----

    @Test
    fun `a finger straight up reads zero degrees`() {
        assertEquals(0f, LilypadDialMath.vectorAngleDeg(0f, -1f), 0f)
    }

    @Test
    fun `a finger right of centre reads plus ninety`() {
        assertEquals(90f, LilypadDialMath.vectorAngleDeg(1f, 0f), 0f)
    }

    @Test
    fun `a finger left of centre reads minus ninety`() {
        assertEquals(-90f, LilypadDialMath.vectorAngleDeg(-1f, 0f), 0f)
    }

    @Test
    fun `the four diagonals read their forty-five degree sectors`() {
        // Screen coordinates: y grows down, so (1, -1) is the upper
        // right (clockwise from twelve) and (1, 1) the lower right.
        assertEquals(45f, LilypadDialMath.vectorAngleDeg(1f, -1f), 1e-4f)
        assertEquals(135f, LilypadDialMath.vectorAngleDeg(1f, 1f), 1e-4f)
        assertEquals(-135f, LilypadDialMath.vectorAngleDeg(-1f, 1f), 1e-4f)
        assertEquals(-45f, LilypadDialMath.vectorAngleDeg(-1f, -1f), 1e-4f)
    }

    @Test
    fun `a finger straight down reads plus one eighty, never the negative twin`() {
        // The seam: straight down is the one direction atan2 can answer
        // either way; the pinned answer is +180 so its sign is stable
        // whichever float route produced it.
        assertEquals(180f, LilypadDialMath.vectorAngleDeg(0f, 1f), 0f)
        // And the negative-zero twin of the same finger (dx = -0.0)
        // reads the SAME +180 --- the seam normalization's whole job.
        assertEquals(180f, LilypadDialMath.vectorAngleDeg(-0.0f, 1f), 0f)
    }

    // ---- shortestDeltaDeg: the shortest signed way round ----

    @Test
    fun `a clockwise hop reads positive however the angles wrapped`() {
        assertEquals(20f, LilypadDialMath.shortestDeltaDeg(0f, 20f), 0f)
        // Crossing twelve o'clock: 350 -> 10 is a 20 degree hop, never
        // the 340 degree tour the other way.
        assertEquals(20f, LilypadDialMath.shortestDeltaDeg(350f, 10f), 0f)
        assertEquals(20f, LilypadDialMath.shortestDeltaDeg(170f, -170f), 0f)
    }

    @Test
    fun `a counterclockwise hop reads negative however the angles wrapped`() {
        assertEquals(-20f, LilypadDialMath.shortestDeltaDeg(20f, 0f), 0f)
        assertEquals(-20f, LilypadDialMath.shortestDeltaDeg(10f, 350f), 0f)
        assertEquals(-20f, LilypadDialMath.shortestDeltaDeg(-170f, 170f), 0f)
    }

    @Test
    fun `an exactly half turn reads minus one eighty from either side`() {
        // Either direction is honest at exactly half a turn; the sign is
        // just pinned stable.
        assertEquals(-180f, LilypadDialMath.shortestDeltaDeg(0f, 180f), 0f)
        assertEquals(-180f, LilypadDialMath.shortestDeltaDeg(0f, -180f), 0f)
    }

    @Test
    fun `the delta stays shortest for inputs beyond one full turn`() {
        // The pure function answers ANY pair of angles, not only the
        // composable's (-180, 180] outputs; the floor-mod keeps the
        // answer in range where a sign-preserving mod would not.
        assertEquals(20f, LilypadDialMath.shortestDeltaDeg(0f, 380f), 1e-4f)
        assertEquals(-20f, LilypadDialMath.shortestDeltaDeg(0f, -380f), 1e-4f)
        assertEquals(0f, LilypadDialMath.shortestDeltaDeg(720f, 0f), 0f)
        assertEquals(0f, LilypadDialMath.shortestDeltaDeg(-720f, 0f), 0f)
        assertEquals(-90f, LilypadDialMath.shortestDeltaDeg(0f, -450f), 1e-4f)
    }

    // ---- scrub: the rotary position-relative drag ----

    @Test
    fun `a quarter turn of the finger moves the value a quarter of the span`() {
        // One full finger turn covers the whole +/-45 span (the 4:1
        // reduction): 90 degrees of finger = 22.5 degrees of value.
        assertEquals(
            22.5f,
            LilypadDialMath.scrub(
                startValue = 0f,
                startAngleDeg = 0f,
                currentAngleDeg = 90f,
                range = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
            ),
            1e-4f,
        )
    }

    @Test
    fun `the scrub is anchored at the value the drag started from`() {
        assertEquals(
            12.5f, // -10 anchored, plus 90 deg of finger = +22.5 of value
            LilypadDialMath.scrub(
                startValue = -10f,
                startAngleDeg = 0f,
                currentAngleDeg = 90f,
                range = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
            ),
            1e-4f,
        )
        // A drag that came back to its own anchor moves nothing.
        assertEquals(
            -10f,
            LilypadDialMath.scrub(
                startValue = -10f,
                startAngleDeg = 45f,
                currentAngleDeg = 45f,
                range = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
            ),
            0f,
        )
    }

    @Test
    fun `the finger crossing twelve scrubs the short way`() {
        // 350 -> 10 around the seam is +20 degrees of finger, not -340.
        assertEquals(
            5f, // 20/360 of the 90 degree span
            LilypadDialMath.scrub(
                startValue = 0f,
                startAngleDeg = 350f,
                currentAngleDeg = 10f,
                range = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
            ),
            1e-4f,
        )
    }

    @Test
    fun `a scrub never escapes its range`() {
        assertEquals(
            LilypadDialMath.RANGE_MAX_DEG,
            LilypadDialMath.scrub(
                startValue = 40f,
                startAngleDeg = 0f,
                currentAngleDeg = 180f,
                range = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
            ),
            0f,
        )
        assertEquals(
            LilypadDialMath.RANGE_MIN_DEG,
            LilypadDialMath.scrub(
                startValue = -40f,
                startAngleDeg = 0f,
                currentAngleDeg = -180f,
                range = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
            ),
            0f,
        )
    }

    @Test
    fun `a degenerate range has nowhere to scrub to`() {
        assertEquals(
            7f,
            LilypadDialMath.scrub(
                startValue = 7f,
                startAngleDeg = 0f,
                currentAngleDeg = 179f,
                range = 7f..7f,
            ),
            0f,
        )
    }

    // ---- scrub: the fine gain ----

    @Test
    fun `the fine mode re-runs the same scrub at the ProSlider's own gain`() {
        val coarse = LilypadDialMath.scrub(
            startValue = 0f,
            startAngleDeg = 0f,
            currentAngleDeg = 90f,
            range = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
        )
        val fine = LilypadDialMath.scrub(
            startValue = 0f,
            startAngleDeg = 0f,
            currentAngleDeg = 90f,
            range = LilypadDialMath.RANGE_MIN_DEG..LilypadDialMath.RANGE_MAX_DEG,
            fine = true,
        )
        assertEquals(coarse * LilypadSliderMath.FINE_GAIN, fine, 1e-4f)
        // The 10% itself: 22.5 degrees of value becomes 2.25.
        assertEquals(2.25f, fine, 1e-4f)
    }

    // ---- snappedToDetent: the zero detent ----

    @Test
    fun `values inside the detent window snap home`() {
        assertEquals(0f, LilypadDialMath.snappedToDetent(0f), 0f)
        assertEquals(0f, LilypadDialMath.snappedToDetent(1.4f), 0f)
        assertEquals(0f, LilypadDialMath.snappedToDetent(-1.4f), 0f)
        // The window is inclusive at its edge.
        assertEquals(0f, LilypadDialMath.snappedToDetent(LilypadDialMath.DETENT_WINDOW_DEG), 0f)
        assertEquals(0f, LilypadDialMath.snappedToDetent(-LilypadDialMath.DETENT_WINDOW_DEG), 0f)
    }

    @Test
    fun `values outside the detent window pass through untouched`() {
        assertEquals(1.6f, LilypadDialMath.snappedToDetent(1.6f), 0f)
        assertEquals(-1.6f, LilypadDialMath.snappedToDetent(-1.6f), 0f)
        assertEquals(45f, LilypadDialMath.snappedToDetent(45f), 0f)
        assertEquals(-45f, LilypadDialMath.snappedToDetent(-45f), 0f)
    }

    // ---- the pins ----

    @Test
    fun `the bounds are the recipe's own validation window`() {
        assertEquals(-45f, LilypadDialMath.RANGE_MIN_DEG, 0f)
        assertEquals(45f, LilypadDialMath.RANGE_MAX_DEG, 0f)
        assertEquals(360f, LilypadDialMath.FULL_CIRCLE_DEG, 0f)
        assertEquals(1.5f, LilypadDialMath.DETENT_WINDOW_DEG, 0f)
    }
}
