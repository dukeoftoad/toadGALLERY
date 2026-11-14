package com.toadgallery.editor

import kotlin.math.PI
import kotlin.math.atan2

// The straighten dial's arithmetic as pure data (Float only; LilypadDialMathTest
// pins it). Dial angles are clock-like: 0 at twelve, POSITIVE CLOCKWISE, and the
// straighten value IS the dial angle (0 deg = level). The scrub is
// position-relative and ROTARY: one full 360 deg finger turn covers the whole
// 90 deg span (a 4:1 reduction), anchored at the drag's start value; [fine] runs
// the same scrub at LilypadSliderMath.FINE_GAIN. Zero is magnetic: a value
// within [DETENT_WINDOW_DEG] of level snaps home, in FLOAT space — it must bite
// before the draft slot's integer rounding or it would never fire.

/**
 * The straighten dial's pure arithmetic: the finger-to-angle vector read, the
 * shortest-way-round delta, the rotary scrub, and the zero detent.
 */
internal object LilypadDialMath {

    /** The dial's floor --- the recipe's own straighten validation bound. */
    const val RANGE_MIN_DEG = -45f

    /** The dial's ceiling --- the recipe's own straighten validation bound. */
    const val RANGE_MAX_DEG = 45f

    /** One full finger turn, degrees --- the scrub's denominator. */
    const val FULL_CIRCLE_DEG = 360f

    /**
     * The detent's magnetic window, degrees: values this close to level snap
     * home. Applied in FLOAT space, before the draft slot's integer rounding.
     */
    const val DETENT_WINDOW_DEG = 1.5f

    /**
     * The dial angle of the vector (dx, dy) in SCREEN coordinates (y grows
     * down): 0 at straight up, POSITIVE CLOCKWISE, in (-180, 180]. The inverse
     * of the drawing rule (x = sin, y = -cos).
     */
    fun vectorAngleDeg(dx: Float, dy: Float): Float {
        val radians = atan2(dx.toDouble(), (-dy).toDouble())
        val angle = (radians * 180.0 / PI).toFloat()
        // A straight-down finger can read either half turn; normalize the
        // seam to +180 to keep its sign stable.
        return if (angle <= -180f) angle + FULL_CIRCLE_DEG else angle
    }

    /**
     * The SHORTEST signed way from [fromDeg] to [toDeg], in [-180, 180): a
     * finger crossing twelve o'clock reads as the 20 deg hop it made, never the
     * 340 deg tour it did not; an exactly-half-turn move reads -180. The wrap is
     * a FLOOR-mod, not Kotlin's sign-preserving %: the function must answer any
     * pair of angles (callers may hand it accumulated 720s or -450s).
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
     * The rotary scrub: the finger's angular delta over one full circle maps to
     * the range span, applied to the anchored [startValue] and clamped into
     * [range]. [fine] multiplies the traveled span by [LilypadSliderMath.FINE_GAIN].
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
            // A degenerate range has nowhere to scrub to; return the anchored value, clamped.
            return startValue.coerceIn(range.start, range.endInclusive)
        }
        val gain = if (fine) LilypadSliderMath.FINE_GAIN else 1f
        val traveled = shortestDeltaDeg(startAngleDeg, currentAngleDeg) / FULL_CIRCLE_DEG * span * gain
        return (startValue + traveled).coerceIn(range.start, range.endInclusive)
    }

    /**
     * The zero detent: a value within [DETENT_WINDOW_DEG] of level snaps home;
     * everything else passes through. The ZERO_TICK haptic is the composable's
     * business ([LilypadSliderMath.crossedNeutral]).
     */
    fun snappedToDetent(value: Float): Float =
        if (value >= -DETENT_WINDOW_DEG && value <= DETENT_WINDOW_DEG) 0f else value
}
