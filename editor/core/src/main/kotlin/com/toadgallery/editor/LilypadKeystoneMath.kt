package com.toadgallery.editor

import kotlin.math.roundToInt

// Keystone sliders' arithmetic as pure data (LilypadKeystoneMathTest pins it).
// VERTICAL keystone moves the TOP edge's corners along X (tlX = +m, trX = -m,
// m = v% of the w/4 clamp): positive pinches the top IN and the warp stretches
// it back OUT (fixes verticals converging upward); the bottom never moves.
// HORIZONTAL is the same rule a quarter turn for the LEFT edge. 0 = identity
// exactly; +/-100 lands on the warp's clamp bound. The recipe stores the QUAD:
// rows re-derive percents from the top-left corner (exact for quads these rows
// wrote, honest nearest reading otherwise). Built on the PREVIEW grid; export
// scales up via [scaledForExport] — percents are the resolution-independent truth.

/**
 * The keystone sliders' pure arithmetic: the two-percent quad shape, its
 * read-back, and the preview-to-export grid scaling.
 */
internal object LilypadKeystoneMath {

    /** The sliders' span: the recipe's own Int-slider convention, bipolar. */
    const val KEYSTONE_MIN = -100

    /** The sliders' ceiling. */
    const val KEYSTONE_MAX = 100

    /**
     * The quad the two keystone percents carve out of the [width] x [height]
     * grid: the top edge pinched by [verticalPct] of the X clamp bound, the left
     * edge by [horizontalPct] of the Y clamp bound, the other edges undisplaced.
     * Zero in both is [PerspectiveQuad.IDENTITY], exactly; a non-positive grid
     * degenerates to the identity too.
     */
    fun quadFor(verticalPct: Int, horizontalPct: Int, width: Int, height: Int): PerspectiveQuad {
        // The identity here also avoids signed-zero dust: -0.6 * 0.0 = -0.0,
        // which Double equals() distinguishes from the identity's +0.0.
        if (width <= 0 || height <= 0) return PerspectiveQuad.IDENTITY
        val topShift = verticalPct / 100.0 * (width / 4.0)
        val leftShift = horizontalPct / 100.0 * (height / 4.0)
        // Signed-zero hygiene: negating a zero shift would manufacture -0.0.
        val topPinch = if (topShift == 0.0) 0.0 else -topShift
        val leftPinch = if (leftShift == 0.0) 0.0 else -leftShift
        return PerspectiveQuad(
            tlX = topShift,
            tlY = leftShift,
            trX = topPinch,
            trY = 0.0,
            brX = 0.0,
            brY = 0.0,
            blX = 0.0,
            blY = leftPinch,
        )
    }

    /**
     * The vertical keystone a [quad] reads as, against the [width] it was built
     * for: the top-left corner's X displacement as a percent of the X clamp
     * bound, coerced into the sliders' span (a beyond-clamp displacement is dead
     * motion the warp clips anyway). A non-positive [width] reads 0.
     */
    fun verticalPctOf(quad: PerspectiveQuad, width: Int): Int {
        if (width <= 0) return 0
        return (quad.tlX / (width / 4.0) * 100.0).roundToInt()
            .coerceIn(KEYSTONE_MIN, KEYSTONE_MAX)
    }

    /**
     * The horizontal keystone a [quad] reads as: the top-left corner's Y
     * displacement as a percent of the Y clamp bound ([verticalPctOf]'s rules).
     */
    fun horizontalPctOf(quad: PerspectiveQuad, height: Int): Int {
        if (height <= 0) return 0
        return (quad.tlY / (height / 4.0) * 100.0).roundToInt()
            .coerceIn(KEYSTONE_MIN, KEYSTONE_MAX)
    }

    /**
     * Retargets a quad from the preview grid ([previewWidth] x [previewHeight])
     * to the export grid: every displacement scales by its own axis ratio, so a
     * quad built from percents at the preview scale lands exactly where building
     * the same percents at full scale would. The identity returns itself; a
     * non-positive preview dimension returns the quad verbatim.
     */
    fun scaledForExport(
        quad: PerspectiveQuad,
        previewWidth: Int,
        previewHeight: Int,
        fullWidth: Int,
        fullHeight: Int,
    ): PerspectiveQuad {
        if (quad == PerspectiveQuad.IDENTITY) return quad
        if (previewWidth <= 0 || previewHeight <= 0) return quad
        val sx = fullWidth.toDouble() / previewWidth.toDouble()
        val sy = fullHeight.toDouble() / previewHeight.toDouble()
        return PerspectiveQuad(
            tlX = quad.tlX * sx,
            tlY = quad.tlY * sy,
            trX = quad.trX * sx,
            trY = quad.trY * sy,
            brX = quad.brX * sx,
            brY = quad.brY * sy,
            blX = quad.blX * sx,
            blY = quad.blY * sy,
        )
    }
}
