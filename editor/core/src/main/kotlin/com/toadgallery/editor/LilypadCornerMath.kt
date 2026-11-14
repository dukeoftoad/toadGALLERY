package com.toadgallery.editor

import kotlin.math.hypot

// ---------------------------------------------------------------------------
// r72: the craft polish round (Phase 4 of the editor rebirth plan,
// docs/editor/lilypad-design.md §5) --- the PERSPECTIVE CORNER DRAG's
// arithmetic as PURE DATA (the ADR-0011 split: LilypadCornerMathTest
// pins every rule on the JVM; the overlay and the chassis only READ
// these numbers).
//
// THE MODEL IT DRIVES. [PerspectiveQuad] is the recipe's perspective
// state: EIGHT pixel displacements, one (dx, dy) per corner, measured
// against the identity corners (0,0), (w-1,0), (w-1,h-1), (0,h-1) of
// the grid the warp runs on, clamped to +/- w/4 and +/- h/4. The r68
// keystone sliders carve a FIXED two-percent shape out of that freedom;
// the corner drag is the model's full surface --- any corner, any
// direction, one finger.
//
// THE DISPLAY GEOMETRY. The drag happens on the DISPLAYED image: the
// preview grid fit into the canvas box (scale s, offset o), where a
// source pixel p sits at o + p*s. The quad's DISPLACED corner of the
// pre-warp source sits at the identity corner PLUS its displacement;
// [cornerScreen] and its inverse [dragToQuad] carry that round trip.
// The preview the user warps IS the pre-warp source (the pipeline
// re-renders the warp live under the finger --- the zero-ms feedback
// rule), so the overlay draws the SOURCE's corners and the drag maps
// finger motion into source-pixel displacement, on the preview grid the
// commit lands (the keystone rows' own honesty: what the finger scrubbed
// is what the release lands, byte-for-byte).
//
// THE THREE RULES (the curve deck's, re-derived for four points):
//
//  - HIT-TESTING: a corner is grabbable within a fixed radius (the
//    24dp/48dp touch-target floor, the deck's own constant); the
//    NEAREST corner inside the radius wins, ties to the reading order
//    (TL, TR, BR, BL --- the quad's own field order).
//  - THE DRAG CLAMP: a displacement clamps into the engine's own +/-
//    w/4, +/- h/4 bounds ([PerspectiveQuad.clamped] --- the warp clips
//    there anyway; the drag must never SHOW a displacement the engine
//    would silently cut). This is the whole clamp: corners are
//    independent (the quad has no cross-corner invariant to guard).
//  - THE IDENTITY SNAP: a release within a small deadband of a zeroed
//    displacement snaps that corner home (the deck's diagonal tick, the
//    ProSlider's neutral --- the honest way back to "no perspective").
//
// THE READ-BACK. The recipe stores the QUAD, not the drag: the overlay
// redraws from the committed quad every composition (the keystone
// rows' read-back sibling), and the rows keep reading the
// keystone-shaped PART of a corner-dragged quad (r68's documented
// honest nearest reading --- the two surfaces compose, never fight).
// ---------------------------------------------------------------------------

/**
 * Which corner of the quad a gesture owns (the field order of
 * [PerspectiveQuad] itself: reading order around the frame).
 */
internal enum class QuadCorner {
    TopLeft, TopRight, BottomRight, BottomLeft;
}

/**
 * The perspective corner drag's pure arithmetic (the file header's
 * contract): the hit-test, the display<->grid geometry, and the drag
 * clamp with its identity snap.
 */
internal object LilypadCornerMath {

    /**
     * The corner grab radius, dp: a touch within this radius of a
     * corner grabs it (48dp of diameter --- the WCAG 2.5.5 floor, the
     * curve deck's HIT_RADIUS_DP verbatim); a touch outside every
     * radius is canvas-space, not corner-space.
     */
    const val HIT_RADIUS_DP = 24

    /**
     * The identity snap deadband, in normalized units of the clamp
     * bound: a released displacement under w/160 (resp. h/160) snaps
     * that corner's component home --- about 4 display pixels at the
     * bound's own scale, the deck's neutral neighborhood.
     */
    const val SNAP_DEADBAND = 1.0 / 160.0

    // ---- the identity corners on the source grid ----

    /** The identity corners of a [width] x [height] grid, reading order. */
    fun identityCorners(width: Int, height: Int): List<Pair<Double, Double>> = listOf(
        0.0 to 0.0,
        (width - 1).toDouble() to 0.0,
        (width - 1).toDouble() to (height - 1).toDouble(),
        0.0 to (height - 1).toDouble(),
    )

    /** The quad's displaced corners on the source grid, reading order. */
    fun quadCorners(quad: PerspectiveQuad, width: Int, height: Int): List<Pair<Double, Double>> {
        val ids = identityCorners(width, height)
        val dx = doubleArrayOf(quad.tlX, quad.trX, quad.brX, quad.blX)
        val dy = doubleArrayOf(quad.tlY, quad.trY, quad.brY, quad.blY)
        return ids.mapIndexed { i, (x, y) -> (x + dx[i]) to (y + dy[i]) }
    }

    // ---- hit-testing ----

    /**
     * HIT-TESTING in SOURCE-PIXEL space: the index (0..3, the reading
     * order) of the NEAREST corner within [radiusPx] of (x, y), ties to
     * the lower index, or -1 when every corner is out of reach. The
     * caller converts its display position to source pixels first (the
     * display geometry below); the radius is the px form of
     * [HIT_RADIUS_DP] scaled the same way.
     */
    fun hitCorner(
        corners: List<Pair<Double, Double>>,
        x: Double,
        y: Double,
        radiusPx: Double,
    ): Int {
        if (radiusPx <= 0.0) return -1
        var best = -1
        var bestDist = Double.MAX_VALUE
        for (i in corners.indices) {
            val d = hypot(corners[i].first - x, corners[i].second - y)
            if (d <= radiusPx && d < bestDist) {
                best = i
                bestDist = d
            }
        }
        return best
    }

    // ---- the drag clamp ----

    /**
     * THE DRAG CLAMP: [corner] of [quad] displaced to the source-grid
     * position (x, y) --- the displacement is the position's offset
     * from the corner's identity corner, clamped into the engine's own
     * +/- w/4, +/- h/4 bounds ([PerspectiveQuad.clamped]'s exact rule;
     * the drag never shows what the warp would cut). An out-of-range
     * corner index returns the quad verbatim.
     */
    fun dragCornerTo(
        quad: PerspectiveQuad,
        corner: QuadCorner,
        x: Double,
        y: Double,
        width: Int,
        height: Int,
    ): PerspectiveQuad {
        val ids = identityCorners(width, height)
        val index = corner.ordinal
        val (ix, iy) = ids[index]
        val mx = width / 4.0
        val my = height / 4.0
        val dx = (x - ix).coerceIn(-mx, mx)
        val dy = (y - iy).coerceIn(-my, my)
        return when (corner) {
            QuadCorner.TopLeft -> quad.copy(tlX = dx, tlY = dy)
            QuadCorner.TopRight -> quad.copy(trX = dx, trY = dy)
            QuadCorner.BottomRight -> quad.copy(brX = dx, brY = dy)
            QuadCorner.BottomLeft -> quad.copy(blX = dx, blY = dy)
        }
    }

    /**
     * THE IDENTITY SNAP: the released quad with [corner]'s displacement
     * zeroed when it landed inside the deadband ([SNAP_DEADBAND] of its
     * axis bound), verbatim otherwise --- the honest way back to no
     * perspective, one corner at a time (the ProSlider's neutral, the
     * deck's diagonal).
     */
    fun snapOnRelease(
        quad: PerspectiveQuad,
        corner: QuadCorner,
        width: Int,
        height: Int,
    ): PerspectiveQuad {
        val mx = width / 4.0 * SNAP_DEADBAND
        val my = height / 4.0 * SNAP_DEADBAND
        return when (corner) {
            QuadCorner.TopLeft ->
                if (kotlin.math.abs(quad.tlX) <= mx && kotlin.math.abs(quad.tlY) <= my) {
                    quad.copy(tlX = 0.0, tlY = 0.0)
                } else {
                    quad
                }
            QuadCorner.TopRight ->
                if (kotlin.math.abs(quad.trX) <= mx && kotlin.math.abs(quad.trY) <= my) {
                    quad.copy(trX = 0.0, trY = 0.0)
                } else {
                    quad
                }
            QuadCorner.BottomRight ->
                if (kotlin.math.abs(quad.brX) <= mx && kotlin.math.abs(quad.brY) <= my) {
                    quad.copy(brX = 0.0, brY = 0.0)
                } else {
                    quad
                }
            QuadCorner.BottomLeft ->
                if (kotlin.math.abs(quad.blX) <= mx && kotlin.math.abs(quad.blY) <= my) {
                    quad.copy(blX = 0.0, blY = 0.0)
                } else {
                    quad
                }
        }
    }
}
