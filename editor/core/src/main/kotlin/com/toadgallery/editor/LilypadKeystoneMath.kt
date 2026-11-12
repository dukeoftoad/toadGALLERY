package com.toadgallery.editor

import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the KEYSTONE sliders' arithmetic as
// PURE DATA (the ADR-0011 split: LilypadKeystoneMathTest pins every rule
// on the JVM; the panel and the chassis only READ these numbers).
//
// THE MODEL THEY DRIVE. [PerspectiveQuad] is the recipe's perspective
// state: EIGHT pixel displacements, one (dx, dy) per corner, measured
// against the identity corners (0,0), (w-1,0), (w-1,h-1), (0,h-1) of the
// grid the warp runs on, clamped to +/- w/4 and +/- h/4. Two ProSlider
// rows (vertical + horizontal keystone, the r68 stand-in for the corner
// drag Phase 4 owns) have to drive that eight-number model with TWO
// signed percents --- so this object defines the FIXED SHAPE the percents
// carve out of the quad's full freedom:
//
//  - VERTICAL keystone [v] moves the TOP edge's two corners along X,
//    symmetric about the centre line, by v% of the X clamp bound (w/4):
//    tlX = +m, trX = -m. Positive pinches the top edge IN, and the warp
//    (which stretches the dragged quad onto the full output frame)
//    stretches the photo's top back OUT --- the correction for verticals
//    that converge upward (the shot taken from below). Negative lets the
//    top breathe wider. The bottom corners never move: fixing a top
//    convergence must not rock the foundation the camera stood on.
//  - HORIZONTAL keystone [h] is the same rule rotated a quarter turn: it
//    moves the LEFT edge's two corners along Y, by h% of the Y clamp
//    bound (h/4): tlY = +n, blY = -n. The right edge never moves.
//
// 0 in both IS the identity quad, exactly; 100 in one lands that edge's
// displacement exactly ON the engine's own clamp bound --- the slider's
// span IS the warp's legal span, no dead zones at either end.
//
// THE READ-BACK. The recipe stores the QUAD, not the percents, so the
// rows re-derive their positions from it: the top-left corner's X reads
// the vertical keystone, its Y the horizontal (the corner the two rules
// share). For a quad these rows wrote, that projection is EXACT; for any
// other quad (a hand-edited sidecar; a Phase 4 corner drag's output) it
// is the honest nearest reading --- the rows report the keystone-shaped
// part of what they see, coerced into the sliders' +/-100 window.
//
// THE GRIDS. The quad's displacements are PIXELS, so a quad belongs to
// the grid it was built for. The rows build against the PREVIEW grid
// (the decode the whole chassis works on --- what the finger scrubbed is
// what the commit lands, byte-for-byte); the export, which decodes the
// source at full resolution, scales the same quad up first
// ([scaledForExport]) so the two grids show the SAME warp. The percents
// are the resolution-independent truth both grids agree on: scaling a
// preview-grid quad by fullW/previewW is exactly building it from the
// same percents at fullW, by construction.
// ---------------------------------------------------------------------------

/**
 * The keystone sliders' pure arithmetic (the file header's contract): the
 * two-percent quad shape, its read-back, and the preview-to-export grid
 * scaling.
 */
internal object LilypadKeystoneMath {

    /** The sliders' span: the recipe's own Int-slider convention, bipolar. */
    const val KEYSTONE_MIN = -100

    /** The sliders' ceiling. */
    const val KEYSTONE_MAX = 100

    /**
     * The quad the two keystone percents carve out of the [width] x
     * [height] grid: the top edge pinched by [verticalPct] of the X clamp
     * bound, the left edge by [horizontalPct] of the Y clamp bound, the
     * other two edges undisplaced. Zero in both is
     * [PerspectiveQuad.IDENTITY], exactly; a non-positive [width] or
     * [height] (no base decoded yet) degenerates to the identity too ---
     * the only honest quad for a grid that does not exist.
     */
    fun quadFor(verticalPct: Int, horizontalPct: Int, width: Int, height: Int): PerspectiveQuad {
        val topShift = verticalPct / 100.0 * (width / 4.0)
        val leftShift = horizontalPct / 100.0 * (height / 4.0)
        return PerspectiveQuad(
            tlX = topShift,
            tlY = leftShift,
            trX = -topShift,
            trY = 0.0,
            brX = 0.0,
            brY = 0.0,
            blX = 0.0,
            blY = -leftShift,
        )
    }

    /**
     * The vertical keystone a [quad] reads as, against the [width] it was
     * built for: the top-left corner's X displacement as a percent of the
     * X clamp bound, rounded, coerced into the sliders' span (a
     * beyond-clamp displacement is dead motion the warp clips anyway ---
     * the honest displayable value is the bound itself). A non-positive
     * [width] reads 0.
     */
    fun verticalPctOf(quad: PerspectiveQuad, width: Int): Int {
        if (width <= 0) return 0
        return (quad.tlX / (width / 4.0) * 100.0).roundToInt()
            .coerceIn(KEYSTONE_MIN, KEYSTONE_MAX)
    }

    /**
     * The horizontal keystone a [quad] reads as, against the [height] it
     * was built for: the top-left corner's Y displacement as a percent of
     * the Y clamp bound ([verticalPctOf]'s rules, verbatim).
     */
    fun horizontalPctOf(quad: PerspectiveQuad, height: Int): Int {
        if (height <= 0) return 0
        return (quad.tlY / (height / 4.0) * 100.0).roundToInt()
            .coerceIn(KEYSTONE_MIN, KEYSTONE_MAX)
    }

    /**
     * The same warp, retargeted from the preview grid
     * ([previewWidth] x [previewHeight]) to the export grid
     * ([fullWidth] x [fullHeight]): every displacement scales by its own
     * axis ratio, so a quad built from percents at the preview scale
     * lands exactly where building the same percents at full scale would
     * (the ratio cancels the clamp bound). The identity quad returns
     * itself, exactly; a non-positive preview dimension (no reference
     * grid) returns the quad verbatim --- the honest degrade is today's
     * unscaled behaviour, never a invented one.
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
