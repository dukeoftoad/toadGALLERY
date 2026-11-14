package com.toadgallery.editor

// The ProSlider's arithmetic as pure data (ADR-0011 split): Float math, no compose,
// no android; LilypadSliderMathTest pins every function on the JVM.

/**
 * The ProSlider's pure arithmetic:
 *
 *  - [scrub]: POSITION-RELATIVE drag --- the finger's DELTA over the row width maps to
 *    the range span, shifting the value the drag started from (no thumb-sized target);
 *    [fine] re-runs it at [FINE_GAIN].
 *  - [crossedNeutral]: the ZERO_TICK predicate (crossings and landings).
 *  - [isLive]: ember-when-live --- the value has left its neutral.
 *  - [fraction]: a value's 0..1 position along a range.
 *  - [isSymmetric]: the notch guard --- only a center-neutral range may draw the mark.
 */
internal object LilypadSliderMath {

    /** The fine-mode gain: a long-press drag moves the value at 10%. */
    const val FINE_GAIN = 0.10f

    /**
     * The position-relative scrub: ([currentPx] - [startPx]) / [trackWidthPx] is the
     * fraction of the range span the finger traveled, applied to the anchored
     * [startValue], clamped into [range] (a scrub never escapes its range). [fine]
     * multiplies the traveled span by [FINE_GAIN] --- the same motion at a tenth the value.
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
            // Degenerate range or unmeasured track: return the anchored value, clamped home.
            return startValue.coerceIn(range.start, range.endInclusive)
        }
        val gain = if (fine) FINE_GAIN else 1f
        val traveled = (currentPx - startPx) / trackWidthPx * span * gain
        return (startValue + traveled).coerceIn(range.start, range.endInclusive)
    }

    /**
     * The ZERO_TICK predicate: true when the move goes from one side of the neutral to
     * the other, or lands ON it. A move that merely LEAVES the neutral from a standing
     * start on it does not tick; only a crossing or a landing does.
     */
    fun crossedNeutral(previousValue: Float, nextValue: Float, neutral: Float): Boolean =
        (previousValue < neutral && nextValue >= neutral) ||
            (previousValue > neutral && nextValue <= neutral)

    /** The live-pill rule: the value has left its neutral. */
    fun isLive(value: Float, neutral: Float): Boolean = value != neutral

    /**
     * The SIGNED value text for bipolar integers: 0 -> "0", 5 -> "+5", -30 -> "-30".
     * A bipolar value that moved reads its sign --- the direction is information.
     */
    fun signedValueText(value: Int): String = when {
        value > 0 -> "+$value"
        value < 0 -> value.toString()
        else -> "0"
    }

    /**
     * The value's position along the range as a 0..1 track fraction, clamped (a draft
     * or a neutral outside the range still draws inside the track, never off its ends).
     */
    fun fraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        val span = range.endInclusive - range.start
        if (span == 0f) return 0f
        return ((value - range.start) / span).coerceIn(0f, 1f)
    }

    /**
     * The notch guard: true only where the neutral is the range's geometric center
     * (bipolar rows like [-100, 100] at 0), where the double-tap target sits mid-track.
     * A unipolar row (0..100 at 0) must NOT draw the center mark: its neutral is an END.
     */
    fun isSymmetric(range: ClosedFloatingPointRange<Float>, neutral: Float): Boolean =
        range.start + range.endInclusive == 2f * neutral
}
