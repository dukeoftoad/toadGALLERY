package com.toadgallery.editor

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the curve DECK's arithmetic as PURE
// DATA (the ADR-0011 split: LilypadCurveDeckMathTest pins every rule on
// the JVM; CurveDeck.kt only READS these numbers).
//
// THE SEAM. [LilypadCurveMath] owns the CURVE --- validation, the
// monotone bake, the LUT. This object owns the EDITOR around it: which
// channel a gesture targets, the commit normalization, and the three
// rules every touch on the plot surface obeys:
//
//  - HIT-TESTING: a handle is grabbable within a fixed radius (the
//    24dp/48dp touch-target floor); the NEAREST handle inside the radius
//    wins, ties to the lower index. A touch outside every radius is
//    curve-space, not handle-space.
//  - INSERTION: a tap in curve-space grows the curve by one point at the
//    tapped position --- but only strictly BETWEEN neighbors (a curve
//    whose tap landed at or beyond an end cannot grow that way; the ends
//    are the user's to drag, not to appear from a stray tap) and only
//    under the model's 12-point cap.
//  - THE DRAG CLAMP: a dragged point may never cross its neighbors ---
//    x clamps into the open interval between them (a hairline gap each
//    side keeps the strictly-increasing invariant the bake divides by),
//    and the outermost points clamp to the 0..1 domain's edges. A drag
//    cannot collapse the curve, so the editor constructs valid curves BY
//    CONSTRUCTION and [LilypadCurveMath.validate] stays a programming
//    error, never a user-facing one.
//
// THE CHANNELS. The model carries four curves (master + R/G/B); the
// engine composes the MASTER this round (the per-channel apply path is
// the forward state's documented next step --- see LilypadCurveMath's
// header). The deck still EDITS all four --- the wire shape is complete,
// the sidecar round-trips a channel curve, and the round that turns the
// channels on in the pipeline needs no migration. [pointsFor] seeds an
// empty channel with the identity diagonal (an untouched channel reads
// as the straight line it is); [withPoints] writes one channel verbatim.
//
// THE COMMIT NORMALIZATION. [normalize] is the null-for-identity rule at
// the recipe's edge: a curve layer that came back to the diagonal (or a
// channel that did) leaves NO residue --- null for a fully-identity
// state, an empty channel list for an identity channel --- so a
// played-with-then-straightened curve round-trips byte-clean against a
// pre-curve session. The structure is honest about what it keeps: only
// the lists that still differ from the identity survive.
// ---------------------------------------------------------------------------

/**
 * Which of the model's four curves the deck is editing (the channel
 * chips' radio value; a view preference, never recipe state --- the
 * switch commits nothing).
 */
internal enum class CurveChannel {
    Master, Red, Green, Blue;
}

/**
 * The curve deck's pure arithmetic (see the file header for the
 * contract): channel access, the commit normalization, and the
 * hit/insert/clamp rules the plot surface's gestures obey.
 */
internal object LilypadCurveDeckMath {

    /**
     * The handle grab radius, dp: a touch within this radius of a handle
     * grabs it (48dp of diameter --- the WCAG 2.5.5 touch-target floor,
     * the swatch precedent); a touch outside every radius is curve-space.
     */
    const val HIT_RADIUS_DP = 24

    /**
     * The hairline x-gap a dragged point keeps from its neighbors, in
     * normalized units: 1/255, one LUT bin. Strictly-increasing x is the
     * invariant the Fritsch-Carlson slopes divide by, so no drag may
     * ever produce two points a sanitize would have to merge.
     */
    const val MIN_X_GAP = 1.0 / 255.0

    // ---- the channels ------------------------------------------------------

    /**
     * The channel's point list for editing: the stored list, or the
     * identity diagonal for a channel that never carried one (an empty
     * channel reads as the straight line it is). Never empty --- the
     * deck always has two points to show.
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
     * The curve state with [channel]'s list set to [points], verbatim
     * (the commit normalization is [normalize]'s job, at the recipe's
     * edge --- the draft carries what the gesture drew). A null
     * [curves] (a clean session) starts from the identity master.
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
     * The commit normalization (the null-for-identity rule): an identity
     * channel list flattens to empty, and a state that came all the way
     * back to the identity flattens to NULL --- no residue on the wire,
     * nothing in the pipeline's gate. Two deliberate reads of the
     * diagonal: a curve whose EVERY point sits exactly ON the identity
     * line (played with, dragged back) IS the identity however many
     * points it still carries, so it flattens to null; an EMPTY master
     * (the codec's tolerant degenerate shape) instead REPAIRS to the
     * canonical two-point diagonal and stays a STATE --- the deck's own
     * editable identity, never a null the caller would mistake for
     * "never touched".
     */
    fun normalize(curves: ToneCurves): ToneCurves? {
        val master = curves.master.ifEmpty { LilypadCurveMath.IDENTITY }
        val out = ToneCurves(
            master = if (master.all { it.y == it.x }) LilypadCurveMath.IDENTITY else master,
            red = curves.red.identityToEmpty(),
            green = curves.green.identityToEmpty(),
            blue = curves.blue.identityToEmpty(),
        )
        // The empty-master repair stays a state (see the KDoc); every
        // other all-the-way-back curve leaves no residue.
        if (curves.master.isEmpty()) return out
        return if (out.isIdentity) null else out
    }

    /** The identity list flattens to empty; every other list survives verbatim. */
    private fun List<CurvePoint>.identityToEmpty(): List<CurvePoint> =
        if (isEmpty() || this == LilypadCurveMath.IDENTITY) emptyList() else this

    // ---- the plot surface's three rules -------------------------------------

    /**
     * HIT-TESTING: the index of the NEAREST handle within [radiusPx] of
     * the touch (ties to the lower index), or -1 when every handle is
     * out of reach --- curve-space. The coordinates are PIXELS (the
     * surface converts its normalized points before asking); the radius
     * is the px form of [HIT_RADIUS_DP]. A non-positive radius answers
     * -1 (a disabled surface grabs nothing).
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
     * INSERTION: the index a point at normalized x would take, or -1
     * when the curve cannot grow that way --- at or beyond either end's
     * x (the ends are the user's to drag, not to appear from a stray
     * tap) or at the model's 12-point cap. The returned index is the
     * position AFTER the last point left of x (1..size-1), so the
     * inserted list stays sorted by construction.
     */
    fun insertIndex(points: List<CurvePoint>, x: Double): Int {
        if (points.size >= LilypadCurveMath.MAX_POINTS) return -1
        if (x <= points.first().x || x >= points.last().x) return -1
        var i = 0
        while (i < points.size && points[i].x < x) i++
        return i
    }

    /**
     * A tap's insert: the curve plus one point at the tapped (x, y)
     * (coerced into 0..1), in sorted position --- or null when
     * [insertIndex] refuses (an end tap, a full curve). This is the ONE
     * way the deck grows a curve.
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
     * A long-press's delete: the curve minus the point at [index] --- or
     * null when the curve is already at its floor ([MIN_POINTS]: a curve
     * needs two points to be a curve; the deck NEVER deletes below it)
     * or the index is outside the list. Deleting a middle point is safe
     * by construction (the neighbors were already strictly ordered).
     */
    fun deletePoint(points: List<CurvePoint>, index: Int): List<CurvePoint>? {
        if (points.size <= LilypadCurveMath.MIN_POINTS) return null
        if (index < 0 || index >= points.size) return null
        return points.filterIndexed { i, _ -> i != index }
    }

    /**
     * THE DRAG CLAMP: the curve with [index]'s point moved toward (x, y)
     * --- x clamped into the open interval between its neighbors (each
     * side keeping [MIN_X_GAP] of strictly-increasing daylight; the
     * outermost points clamp to the 0..1 domain instead), y clamped to
     * 0..1. A degenerate neighborhood (neighbors closer than two gaps,
     * unreachable through the deck's own inserts but total anyway) keeps
     * the point where it is --- a drag may never collapse the curve, so
     * the editor constructs valid curves BY CONSTRUCTION. An out-of-range
     * index is a no-op returning the list unchanged.
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
