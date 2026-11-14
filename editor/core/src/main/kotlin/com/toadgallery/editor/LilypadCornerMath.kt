package com.toadgallery.editor

import kotlin.math.hypot

// The perspective corner drag's arithmetic as PURE DATA (JVM-pinned by LilypadCornerMathTest;
// the overlay and chassis only READ these numbers). [PerspectiveQuad] is the recipe's state:
// eight pixel displacements, one (dx, dy) per corner, measured against the identity corners
// (0,0), (w-1,0), (w-1,h-1), (0,h-1), clamped to +/- w/4, +/- h/4. The keystone sliders carve
// a fixed two-percent shape; the corner drag is the model's full surface. The drag happens on
// the DISPLAYED image (source pixel p sits at o + p*s); the preview IS the pre-warp source
// (the pipeline re-renders the warp live), so the drag maps finger motion into source-pixel
// displacement on the preview grid the commit lands.
//
// THE THREE RULES:
//  - HIT-TESTING: the NEAREST corner within the radius wins; ties to reading order (TL, TR, BR, BL).
//  - DRAG CLAMP: displacement clamps to the engine's +/- w/4, +/- h/4 (PerspectiveQuad.clamped):
//    the drag never shows what the warp would cut. Corners are independent.
//  - IDENTITY SNAP: a release within the deadband of a zeroed displacement snaps the corner home.
//
// READ-BACK: the recipe stores the QUAD, not the drag; the keystone rows keep reading the
// keystone-shaped part of a corner-dragged quad (the two surfaces compose, never fight).

/** Which corner of the quad a gesture owns ([PerspectiveQuad]'s field order, reading order around the frame). */
internal enum class QuadCorner {
    TopLeft, TopRight, BottomRight, BottomLeft;
}

/**
 * The corner drag's pure arithmetic: the hit-test, the display<->grid geometry, and the
 * drag clamp with its identity snap.
 */
internal object LilypadCornerMath {

    /**
     * The corner grab radius, dp (48dp of diameter, the WCAG 2.5.5 touch-target floor);
     * a touch outside every radius is canvas-space, not corner-space.
     */
    const val HIT_RADIUS_DP = 24

    /**
     * The identity snap deadband, normalized to the clamp bound: a released displacement
     * under w/160 (resp. h/160) snaps that corner's component home (~4 display pixels).
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
     * HIT-TESTING in SOURCE-PIXEL space: the index (0..3, reading order) of the NEAREST
     * corner within [radiusPx] of (x, y), ties to the lower index, or -1 when every corner
     * is out of reach. The caller converts its display position to source pixels first;
     * [radiusPx] is [HIT_RADIUS_DP] scaled the same way.
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
     * THE DRAG CLAMP: [corner] displaced to source-grid (x, y) --- the displacement is the
     * offset from the identity corner, clamped into +/- w/4, +/- h/4 ([PerspectiveQuad.clamped]'s
     * exact rule; the drag never shows what the warp would cut). An out-of-range corner index
     * returns the quad verbatim.
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
     * THE IDENTITY SNAP: the released quad with [corner]'s displacement zeroed when it landed
     * inside the deadband ([SNAP_DEADBAND] of its axis bound), verbatim otherwise --- the way
     * back to no perspective, one corner at a time.
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
