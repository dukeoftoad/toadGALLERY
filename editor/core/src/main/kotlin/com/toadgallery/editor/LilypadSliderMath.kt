package com.toadgallery.editor

// ---------------------------------------------------------------------------
// r67: the Lilypad foundation round (Phase 1 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the ProSlider's arithmetic as PURE
// DATA: Float math, no compose, no android (the ADR-0011 split: pure logic
// tested pure --- LilypadSliderMathTest pins every function on the JVM).
//
// (r67 recovery note: the r67 working tree was lost mid-round; this file
// was RE-DERIVED from the ProSlider contract (ProSlider.kt's documented
// behavior is the spec), not restored verbatim --- the lost-tree recovery
// documented in the round's delivery report.)
// ---------------------------------------------------------------------------

/**
 * The ProSlider's pure arithmetic (the C1 contract's math):
 *
 *  - [scrub]: the POSITION-RELATIVE drag (the Snapseed lesson) --- the
 *    finger's DELTA over the row width maps to the range span and shifts
 *    the value the drag started from, so no thumb-sized target ever has
 *    to be hit; [fine] re-runs the same scrub at [FINE_GAIN].
 *  - [crossedNeutral]: the ZERO_TICK predicate --- the moves that cross
 *    the neutral or land on it.
 *  - [isLive]: the ember-when-live rule --- the value has left its
 *    neutral.
 *  - [fraction]: a value's 0..1 position along a range (the track
 *    geometry's one truth).
 *  - [isSymmetric]: the notch guard --- only a range whose neutral IS its
 *    geometric center may draw the center mark.
 */
internal object LilypadSliderMath {

    /** The fine-mode gain: a long-press drag moves the value at 10%. */
    const val FINE_GAIN = 0.10f

    /**
     * The position-relative scrub: [currentPx] - [startPx] over
     * [trackWidthPx] is the fraction of the range span the finger
     * traveled, applied to the [startValue] the drag anchored at (and
     * clamped into [range] --- a scrub never escapes its range). [fine]
     * multiplies the traveled span by [FINE_GAIN]: the same finger motion
     * at one tenth the value, the surgical touch for exposure and
     * temperature micro-edits.
     */
    fun scrub(
        startValue: Float,
        startPx: Float,
        currentPx: Float,
        trackWidthPx: Float,
        range: ClosedFloatingPointRange<Float>,
        fine: Boolean = false,
    ): Float {
        val span = range.endInclusive - range.start
        if (span == 0f || trackWidthPx <= 0f) {
            // A degenerate range or an unmeasured track has nowhere to
            // scrub to; the anchored value, clamped home, is the only
            // honest answer.
            return startValue.coerceIn(range.start, range.endInclusive)
        }
        val gain = if (fine) FINE_GAIN else 1f
        val traveled = (currentPx - startPx) / trackWidthPx * span * gain
        return (startValue + traveled).coerceIn(range.start, range.endInclusive)
    }

    /**
     * The ZERO_TICK predicate: true when the move goes from one side of
     * the neutral to the other, or lands ON it --- the crossings that
     * fire the haptic (a move that merely LEAVES the neutral from a
     * standing start on it does not tick; only a crossing or a landing
     * does).
     */
    fun crossedNeutral(previousValue: Float, nextValue: Float, neutral: Float): Boolean =
        (previousValue < neutral && nextValue >= neutral) ||
            (previousValue > neutral && nextValue <= neutral)

    /** The live-pill rule: the value has left its neutral. */
    fun isLive(value: Float, neutral: Float): Boolean = value != neutral

    /**
     * The SIGNED value text for bipolar integers: 0 -> "0", 5 -> "+5",
     * -30 -> "-30". The mono pill's string (a bipolar value that moved
     * reads its sign --- the direction is information, not decoration).
     */
    fun signedValueText(value: Int): String = when {
        value > 0 -> "+$value"
        value < 0 -> value.toString()
        else -> "0"
    }

    /**
     * The value's position along the range as a 0..1 track fraction,
     * clamped (a draft or a neutral outside the range still draws inside
     * the track, never off its ends).
     */
    fun fraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        val span = range.endInclusive - range.start
        if (span == 0f) return 0f
        return ((value - range.start) / span).coerceIn(0f, 1f)
    }

    /**
     * The notch guard: true only where the neutral is the range's
     * geometric center --- the bipolar rows ([-100, 100] at 0, [-15, 15]
     * at 0), where the double-tap target sits mid-track. A unipolar row
     * (0..100 at 100, 1..100 at its default) must NOT draw the center
     * mark: its neutral is an END, not a center (the r66 rule, verbatim).
     */
    fun isSymmetric(range: ClosedFloatingPointRange<Float>, neutral: Float): Boolean =
        range.start + range.endInclusive == 2f * neutral
}
