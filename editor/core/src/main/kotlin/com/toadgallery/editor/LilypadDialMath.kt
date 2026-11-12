package com.toadgallery.editor

import kotlin.math.PI
import kotlin.math.atan2

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the straighten DIAL's arithmetic as
// PURE DATA: Float math, no compose, no android (the ADR-0011 split ---
// LilypadDialMathTest pins every rule on the JVM; LilypadDial.kt only
// reads these numbers).
//
// THE ANGLE CONVENTION. A dial angle is measured like a clock's: 0 at
// twelve o'clock, POSITIVE CLOCKWISE, in degrees --- the convention a
// thumb already knows from every rotary knob it has ever turned. The
// straighten value IS its dial angle (0 deg = level, +8 = eight degrees
// clockwise), so the mapping is the identity and the dial cannot lie
// about which way the photo will turn.
//
// THE SCRUB. The drag is POSITION-RELATIVE and ROTARY (the ProSlider
// lesson, bent round a circle): the finger's ANGULAR DELTA around the
// dial's center maps to the value span at a fixed reduction --- one full
// 360 deg turn of the finger covers the whole [RANGE_MIN_DEG, RANGE_MAX_DEG]
// span (90 deg of straighten), a 4:1 reduction that makes the coarse
// +/-45 deg range placeable in one comfortable thumb arc. The delta is
// the SHORTEST signed way round (a finger crossing twelve never reads as
// a 350 deg trip the other way), the anchor is the value the drag
// started from, and [fine] re-runs the same scrub at the ProSlider's own
// [LilypadSliderMath.FINE_GAIN] --- parity by construction, not by copy.
//
// THE DETENT. Zero is magnetic: a value within [DETENT_WINDOW_DEG] of
// level snaps home, so a thumb hunting for straight finds it instead of
// parking at 0.7 deg. The window is in FLOAT space on purpose --- it has
// to bite before the integer rounding the draft slot performs, or it
// would never fire at all.
// ---------------------------------------------------------------------------

/**
 * The straighten dial's pure arithmetic (the file header's contract):
 * the finger-to-angle vector read, the shortest-way-round delta, the
 * rotary scrub, and the zero detent.
 */
internal object LilypadDialMath {

    /** The dial's floor --- the recipe's own straighten validation bound. */
    const val RANGE_MIN_DEG = -45f

    /** The dial's ceiling --- the recipe's own straighten validation bound. */
    const val RANGE_MAX_DEG = 45f

    /** One full finger turn, degrees --- the scrub's denominator. */
    const val FULL_CIRCLE_DEG = 360f

    /**
     * The detent's magnetic window, degrees: values this close to level
     * snap home. Applied in FLOAT space, before the draft slot's integer
     * rounding --- the detent must bite where the thumb actually is.
     */
    const val DETENT_WINDOW_DEG = 1.5f

    /**
     * The dial angle of the vector (dx, dy) in SCREEN coordinates (y
     * grows down): 0 at straight up, POSITIVE CLOCKWISE, in (-180, 180].
     * The inverse of the drawing rule (x = sin, y = -cos) --- a finger
     * right of center reads +90, left of center -90, exactly the way the
     * thumb reads the knob.
     */
    fun vectorAngleDeg(dx: Float, dy: Float): Float {
        val radians = atan2(dx.toDouble(), (-dy).toDouble())
        val angle = (radians * 180.0 / PI).toFloat()
        // The atan2 range is (-180, 180] except at the seam below -180,
        // where a straight-down finger can read either half turn;
        // normalizing the seam to +180 keeps its sign stable.
        return if (angle <= -180f) angle + FULL_CIRCLE_DEG else angle
    }

    /**
     * The SHORTEST signed way from [fromDeg] to [toDeg], in [-180, 180):
     * +20 means twenty degrees clockwise, -20 counterclockwise --- a
     * finger crossing twelve o'clock reads as the 20 deg hop it made,
     * never the 340 deg tour it did not. An exactly-half-turn move reads
     * -180 (either direction is honest there; the sign is just stable).
     *
     * The wrap is a FLOOR-mod, not Kotlin's sign-preserving %: the pure
     * function answers ANY pair of angles (a caller may hand it a 720
     * or a -450 it accumulated itself), and a negative dividend must
     * never smuggle a second negative turn into the answer.
     */
    fun shortestDeltaDeg(fromDeg: Float, toDeg: Float): Float {
        val wrapped =
            (((toDeg - fromDeg) % FULL_CIRCLE_DEG) + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG
        return if (wrapped >= FULL_CIRCLE_DEG / 2f) {
            wrapped - FULL_CIRCLE_DEG
        } else {
            wrapped
        }
    }

    /**
     * The rotary scrub: the finger's angular delta over one full circle
     * maps to the range span, applied to the [startValue] the drag
     * anchored at and clamped into [range] (a scrub never escapes its
     * range). [fine] multiplies the traveled span by
     * [LilypadSliderMath.FINE_GAIN] --- the ProSlider's own 10%, the
     * surgical touch for a horizon that is ALMOST level.
     */
    fun scrub(
        startValue: Float,
        startAngleDeg: Float,
        currentAngleDeg: Float,
        range: ClosedFloatingPointRange<Float>,
        fine: Boolean = false,
    ): Float {
        val span = range.endInclusive - range.start
        if (span == 0f) {
            // A degenerate range has nowhere to scrub to; the anchored
            // value, clamped home, is the only honest answer.
            return startValue.coerceIn(range.start, range.endInclusive)
        }
        val gain = if (fine) LilypadSliderMath.FINE_GAIN else 1f
        val traveled = shortestDeltaDeg(startAngleDeg, currentAngleDeg) / FULL_CIRCLE_DEG * span * gain
        return (startValue + traveled).coerceIn(range.start, range.endInclusive)
    }

    /**
     * The zero detent: a value within [DETENT_WINDOW_DEG] of level snaps
     * home; everything else passes through untouched. Pure --- the
     * ZERO_TICK haptic that accompanies a real crossing is the
     * composable's business ([LilypadSliderMath.crossedNeutral], the
     * ProSlider's own predicate).
     */
    fun snappedToDetent(value: Float): Float =
        if (value >= -DETENT_WINDOW_DEG && value <= DETENT_WINDOW_DEG) 0f else value
}
