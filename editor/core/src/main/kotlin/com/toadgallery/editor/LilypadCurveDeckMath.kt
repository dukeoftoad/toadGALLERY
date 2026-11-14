package com.toadgallery.editor

// The curve deck's pure arithmetic (ADR-0011 split: JVM tests pin every rule,
// CurveDeck.kt only reads these numbers).

/**
 * Which of the model's four curves the deck is editing (the channel chips' radio
 * value; a view preference, never recipe state --- the switch commits nothing).
 */
internal enum class CurveChannel {
    Master, Red, Green, Blue;
}

/**
 * Channel access, the commit normalization, and the hit/insert/clamp rules the
 * plot surface's gestures obey.
 */
internal object LilypadCurveDeckMath {

    /**
     * The handle grab radius, dp: a touch within this radius grabs a handle (48dp
     * diameter, the WCAG 2.5.5 touch-target floor); outside every radius is curve-space.
     */
    const val HIT_RADIUS_DP = 24

    /**
     * The hairline x-gap a dragged point keeps from its neighbors: 1/255, one LUT bin
     * (strictly-increasing x is the invariant the Fritsch-Carlson slopes divide by).
     */
    const val MIN_X_GAP = 1.0 / 255.0

    // The channels.

    /**
     * The channel's point list for editing: the stored list, or the identity diagonal
     * for a channel that never carried one. Never empty --- the deck always shows two points.
     */
    fun pointsFor(channel: CurveChannel, curves: ToneCurves?): List<CurvePoint> {
        val raw = when (channel) {
            CurveChannel.Master -> curves?.master
            CurveChannel.Red -> curves?.red
            CurveChannel.Green -> curves?.green
            CurveChannel.Blue -> curves?.blue
        }
        return raw?.ifEmpty { LilypadCurveMath.IDENTITY } ?: LilypadCurveMath.IDENTITY
    }

    /**
     * The curve state with [channel]'s list set to [points], verbatim (normalization is
     * [normalize]'s job at the recipe's edge). A null [curves] starts from identity master.
     */
    fun withPoints(
        channel: CurveChannel,
        curves: ToneCurves?,
        points: List<CurvePoint>,
    ): ToneCurves {
        val base = curves ?: ToneCurves(LilypadCurveMath.IDENTITY)
        return when (channel) {
            CurveChannel.Master -> base.copy(master = points)
            CurveChannel.Red -> base.copy(red = points)
            CurveChannel.Green -> base.copy(green = points)
            CurveChannel.Blue -> base.copy(blue = points)
        }
    }

    /**
     * The commit normalization (the null-for-identity rule): an identity channel list
     * flattens to empty, a fully-identity state flattens to NULL --- no residue on the
     * wire. A curve whose every point sits exactly on the diagonal IS the identity
     * however many points it carries; an EMPTY master instead repairs to the canonical
     * two-point diagonal and stays a state (editable, never mistaken for "never touched").
     */
    fun normalize(curves: ToneCurves): ToneCurves? {
        val master = curves.master.ifEmpty { LilypadCurveMath.IDENTITY }
        val out = ToneCurves(
            master = if (master.all { it.y == it.x }) LilypadCurveMath.IDENTITY else master,
            red = curves.red.identityToEmpty(),
            green = curves.green.identityToEmpty(),
            blue = curves.blue.identityToEmpty(),
        )
        // The empty-master repair stays a state; other identities flatten to null.
        if (curves.master.isEmpty()) return out
        return if (out.isIdentity) null else out
    }

    /** The identity list flattens to empty; every other list survives verbatim. */
    private fun List<CurvePoint>.identityToEmpty(): List<CurvePoint> =
        if (isEmpty() || this == LilypadCurveMath.IDENTITY) emptyList() else this

    // The plot surface's three rules.

    /**
     * HIT-TESTING: the index of the NEAREST handle within [radiusPx] (ties to the lower
     * index), or -1 when every handle is out of reach --- curve-space. Coordinates are
     * PIXELS; a non-positive radius answers -1 (a disabled surface grabs nothing).
     */
    fun hitIndex(xs: FloatArray, ys: FloatArray, x: Float, y: Float, radiusPx: Float): Int {
        if (radiusPx <= 0f) return -1
        val n = minOf(xs.size, ys.size)
        if (n == 0) return -1
        val r2 = radiusPx * radiusPx
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until n) {
            val dx = xs[i] - x
            val dy = ys[i] - y
            val d2 = dx * dx + dy * dy
            if (d2 <= r2 && d2 < bestDist) {
                best = i
                bestDist = d2
            }
        }
        return best
    }

    /**
     * INSERTION: the index a point at normalized x would take, or -1 when the curve
     * cannot grow that way (at/beyond either end's x, or at the 12-point cap). The index
     * is the position AFTER the last point left of x, so the list stays sorted.
     */
    fun insertIndex(points: List<CurvePoint>, x: Double): Int {
        if (points.size >= LilypadCurveMath.MAX_POINTS) return -1
        if (x <= points.first().x || x >= points.last().x) return -1
        var i = 0
        while (i < points.size && points[i].x < x) i++
        return i
    }

    /**
     * A tap's insert: the curve plus one point at the tapped (x, y) (coerced into 0..1),
     * in sorted position --- or null when [insertIndex] refuses. The ONE way a curve grows.
     */
    fun insertPoint(points: List<CurvePoint>, x: Double, y: Double): List<CurvePoint>? {
        val at = insertIndex(points, x)
        if (at < 0) return null
        val tapped = CurvePoint(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
        return buildList {
            addAll(points.subList(0, at))
            add(tapped)
            addAll(points.subList(at, points.size))
        }
    }

    /**
     * A long-press's delete: the curve minus the point at [index], or null at the
     * [MIN_POINTS] floor (a curve needs two points) or an out-of-range index.
     */
    fun deletePoint(points: List<CurvePoint>, index: Int): List<CurvePoint>? {
        if (points.size <= LilypadCurveMath.MIN_POINTS) return null
        if (index < 0 || index >= points.size) return null
        return points.filterIndexed { i, _ -> i != index }
    }

    /**
     * THE DRAG CLAMP: the curve with [index]'s point moved toward (x, y) --- x clamped
     * into the open interval between its neighbors ([MIN_X_GAP] each side; outermost
     * points clamp to 0..1), y clamped to 0..1. A degenerate neighborhood keeps the point
     * where it is --- a drag may never collapse the curve (valid curves BY CONSTRUCTION).
     * Out-of-range index is a no-op.
     */
    fun movePoint(points: List<CurvePoint>, index: Int, x: Double, y: Double): List<CurvePoint> {
        if (index < 0 || index >= points.size) return points
        val lowerX = if (index > 0) points[index - 1].x + MIN_X_GAP else 0.0
        val upperX = if (index < points.size - 1) points[index + 1].x - MIN_X_GAP else 1.0
        if (lowerX > upperX) return points
        val clamped = CurvePoint(x.coerceIn(lowerX, upperX), y.coerceIn(0.0, 1.0))
        return points.mapIndexed { i, p -> if (i == index) clamped else p }
    }
}
