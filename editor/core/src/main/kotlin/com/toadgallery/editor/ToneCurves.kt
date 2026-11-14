package com.toadgallery.editor

// The recipe's curve layer: control points in normalized image space (x = input tone,
// y = output tone, 0..1). Null toneCurves = identity; an all-identity non-null state
// normalizes back to null at commit. [LilypadCurveMath] sorts/guards; lists keep stored order.

/** One tone-curve control point: normalized input [x] to output [y], both 0..1. */
data class CurvePoint(val x: Double, val y: Double)

/**
 * The recipe's curve state: master + per-channel curves (composed master-then-channel).
 * Point lists are the user's data in stored order; [LilypadCurveMath] sorts and guards.
 */
data class ToneCurves(
    /** The master curve; the pipeline composes it after the shadows stage. */
    val master: List<CurvePoint>,
    /** The red channel's curve, composed over the master. */
    val red: List<CurvePoint> = emptyList(),
    /** The green channel's curve, composed over the master. */
    val green: List<CurvePoint> = emptyList(),
    /** The blue channel's curve, composed over the master. */
    val blue: List<CurvePoint> = emptyList(),
) {
    /**
     * The exact identity: master == the two identity points and no channel curve
     * (a degenerate under-two-point master bakes to the identity LUT anyway).
     */
    val isIdentity: Boolean
        get() = master == IDENTITY_MASTER && red.isEmpty() && green.isEmpty() && blue.isEmpty()

    /**
     * Whether any per-channel curve is in play: the pipeline's switch between the master
     * path and the channel path. An under-two-point channel bakes to the identity ramp.
     */
    val hasChannelCurves: Boolean
        get() = red.size >= LilypadCurveMath.MIN_POINTS ||
            green.size >= LilypadCurveMath.MIN_POINTS ||
            blue.size >= LilypadCurveMath.MIN_POINTS

    companion object {
        /** The canonical two-point identity diagonal; [LilypadCurveMath.bakeLut] returns the exact ramp for it. */
        val IDENTITY_MASTER: List<CurvePoint> = listOf(
            CurvePoint(0.0, 0.0),
            CurvePoint(1.0, 1.0),
        )
    }
}
