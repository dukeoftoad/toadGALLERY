package com.toadgallery.editor

import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the tone curve's arithmetic as PURE
// DATA (the ADR-0011 split: pure logic tested pure ---
// LilypadCurveMathTest pins every rule on the JVM; the curve deck only
// READS these numbers).
//
// THE INTERPOLANT, and why MONOTONE. The curve bakes through a MONOTONE
// CUBIC HERMITE with Fritsch-Carlson slope limiting: segment slopes are
// clamped so the interpolant never overshoots the data between control
// points. A natural spline would ring --- a photographer lifting midtones
// by +0.1 would see the curve dip BELOW a neighbouring point's y on the
// way there, an oscillation the user never drew and cannot fight with
// more points. Monotone interpolation draws exactly the shape the points
// describe: monotone data yields a monotone curve (pinned by test), and
// deliberately decreasing segments (a creative inversion) still interpolate
// smoothly.
//
// THE BAKE. [bakeLut] samples the interpolant at 256 evenly spaced input
// tones --- the same LUT shape [LutBuilder] produces, so the curve composes
// into the existing per-channel stage with one table lookup per entry.
// Endpoint clamping is honest and total: an input tone below the first
// point's x reads the first point's y (and above the last, the last's) ---
// a curve that starts at (0.2, 0.1) pins every darker tone to 0.1. The
// exact identity list bakes to the EXACT 0..255 ramp (a special case, not
// floating-point luck: Hermite basis cancellation would leave rounding
// dust).
//
// THE GUARDS. [validate] is the strict contract the editor UI and the
// tests pin (2..[MAX_POINTS] points, every coordinate in 0..1, strictly
// increasing x). [sanitize] is the tolerant read path (the codec's
// convention): coerce into the domain, sort by x, drop duplicate-x
// stragglers, cap the count --- a hand-edited sidecar never loses its
// whole session to one bad point.
//
// THE CHANNELS, COMPOSED SINCE r71: the pipeline's channel path composes
// the master curve into every channel table, then the channel's own
// curve over it (LutBuilder.buildChannels + PixelOps.applyChannelLuts
// --- the three-LUT apply path Phase 2 declared as its follow-up). The
// channel lists ride the model and the wire exactly as r68 wrote them;
// this round only turned the engine on. No migration: an r68 sidecar
// that carried channel curves renders today what it promised then.
// ---------------------------------------------------------------------------

/**
 * The tone curve's pure arithmetic: validation, the tolerant sanitize, the
 * Fritsch-Carlson monotone Hermite evaluation, and the 256-entry LUT bake
 * (see the file header for the contract).
 */
internal object LilypadCurveMath {

    /** The fewest points that describe a curve (a line needs two). */
    const val MIN_POINTS = 2

    /** The most points a curve may carry (the model cap; the codec caps here too). */
    const val MAX_POINTS = 12

    /** The baked table's size --- the [LutBuilder] LUT shape. */
    const val LUT_SIZE = 256

    /** The canonical identity point list (the straight diagonal). */
    val IDENTITY: List<CurvePoint> = ToneCurves.IDENTITY_MASTER

    /**
     * The STRICT contract (the UI's and the tests'): [points] counts
     * [MIN_POINTS]..[MAX_POINTS], every x and y sits in 0..1, and the x's
     * are strictly increasing. Throws [IllegalArgumentException] naming the
     * breach --- the editor constructs valid curves by construction, so a
     * failure here is a programming error, not user input.
     */
    fun validate(points: List<CurvePoint>) {
        require(points.size in MIN_POINTS..MAX_POINTS) {
            "a curve needs $MIN_POINTS..$MAX_POINTS points, got ${points.size}"
        }
        var previousX = Double.NEGATIVE_INFINITY
        for ((index, p) in points.withIndex()) {
            require(p.x in 0.0..1.0) { "point $index x=${p.x} outside [0, 1]" }
            require(p.y in 0.0..1.0) { "point $index y=${p.y} outside [0, 1]" }
            require(p.x > previousX) { "point $index x=${p.x} does not advance past $previousX" }
            previousX = p.x
        }
    }

    /**
     * The TOLERANT read (the codec's convention): coordinates coerce into
     * 0..1, the list sorts by x, a point whose x repeats the previous one
     * drops (Fritsch-Carlson divides by x gaps), and the count caps at
     * [MAX_POINTS] keeping the smallest x's first. Deterministic for any
     * input; a curve reduced under [MIN_POINTS] points reads as empty (the
     * caller decides what an empty curve means).
     */
    fun sanitize(points: List<CurvePoint>): List<CurvePoint> {
        if (points.isEmpty()) return emptyList()
        val coerced = ArrayList<CurvePoint>(points.size)
        for (p in points) {
            coerced.add(CurvePoint(p.x.coerceIn(0.0, 1.0), p.y.coerceIn(0.0, 1.0)))
        }
        val sorted = coerced.sortedWith(compareBy({ it.x }, { it.y }))
        val out = ArrayList<CurvePoint>(minOf(sorted.size, MAX_POINTS))
        var previousX = Double.NEGATIVE_INFINITY
        for (p in sorted) {
            if (p.x <= previousX) continue
            out.add(p)
            previousX = p.x
            if (out.size >= MAX_POINTS) break
        }
        return out
    }

    /**
     * The Fritsch-Carlson tangent at every point: the secant average,
     * flattened wherever it would overshoot. Exposed for the tests (the
     * limiting rule IS the no-overshoot guarantee); the caller hands over a
     * SANITIZED list (sorted, distinct x, 2+ points).
     */
    internal fun slopes(points: List<CurvePoint>): DoubleArray {
        val n = points.size
        val d = DoubleArray(n - 1) { i ->
            (points[i + 1].y - points[i].y) / (points[i + 1].x - points[i].x)
        }
        val m = DoubleArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            // A local extremum in the DATA flattens the tangent there ---
            // the curve may not keep rising past a point it turns at.
            m[i] = if (d[i - 1] * d[i] <= 0.0) 0.0 else (d[i - 1] + d[i]) / 2.0
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0.0) {
                m[i] = 0.0
                m[i + 1] = 0.0
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val s = a * a + b * b
            if (s > 9.0) {
                // The circle test: a tangent pair this aggressive would
                // overshoot the segment's endpoints --- scale both back
                // onto the safe circle (tau = 3 / sqrt(s)).
                val tau = 3.0 / sqrt(s)
                m[i] = tau * a * d[i]
                m[i + 1] = tau * b * d[i]
            }
        }
        return m
    }

    /**
     * The interpolant at [x], endpoint-clamped: below the first point's x
     * reads the first point's y, above the last's the last's (a curve that
     * does not span 0..1 pins the tones outside its reach). Between points
     * the monotone Hermite runs on [slopes]' tangents. A degenerate list
     * (under [MIN_POINTS] points after sanitize) reads as the identity.
     */
    fun evaluate(points: List<CurvePoint>, x: Double): Double {
        val p = sanitize(points)
        if (p.size < MIN_POINTS) return x.coerceIn(0.0, 1.0)
        return evaluateSanitized(p, slopes(p), x)
    }

    /**
     * The 256-entry LUT: input tone v/255 through [evaluate], rounded and
     * clamped to a byte. The exact identity list returns the EXACT ramp
     * (lut[v] == v for every v) by special case --- the honest guarantee
     * the 5-parameter LutBuilder regression leans on. Any other list still
     * passes EXACTLY through its control points (the Hermite bases pin
     * both endpoints of every segment) and clamps outside their span.
     */
    fun bakeLut(points: List<CurvePoint>): IntArray {
        if (points == IDENTITY) return IntArray(LUT_SIZE) { it }
        val p = sanitize(points)
        if (p.size < MIN_POINTS) return IntArray(LUT_SIZE) { it }
        val m = slopes(p)
        val lut = IntArray(LUT_SIZE)
        for (v in 0 until LUT_SIZE) {
            val y = evaluateSanitized(p, m, v / (LUT_SIZE - 1).toDouble())
            lut[v] = (y * 255.0)
                .let { kotlin.math.round(it).toInt() }
                .coerceIn(0, 255)
        }
        return lut
    }

    /**
     * The interpolant over an ALREADY-sanitized point list with
     * pre-computed tangents (the bake's inner loop; [evaluate] is the
     * one-shot public form). Endpoint clamping and the Hermite bases
     * behave exactly as [evaluate] documents.
     */
    private fun evaluateSanitized(p: List<CurvePoint>, m: DoubleArray, x: Double): Double {
        if (x <= p.first().x) return p.first().y
        if (x >= p.last().x) return p.last().y
        var i = 0
        // Linear scan is honest here: at most MAX_POINTS segments.
        while (i < p.size - 2 && x > p[i + 1].x) i++
        val x0 = p[i].x
        val x1 = p[i + 1].x
        val y0 = p[i].y
        val y1 = p[i + 1].y
        val span = x1 - x0
        val t = (x - x0) / span
        // The bases in the y0-ANCHORED form: y = y0 + h01*(y1-y0) +
        // h10*m[i]*span + h11*m[i+1]*span. Algebraically the textbook
        // h00*y0 + h01*y1 pair, but numerically its superior twin: a
        // FLAT segment (y1 == y0) evaluates to y0 EXACTLY (the h01 term
        // cancels to a literal 0.0, and the slopes are already pinned
        // zero by the d == 0 rule), where the h00/h01 split leaves
        // +-1ulp dust --- dust a 256-entry bake at a rounding boundary
        // (0.3 * 255 = 76.4999...) turns into a one-step LUT inversion.
        // The endpoints stay exact the same way: t = 0 collapses to y0,
        // t = 1 to y0 + (y1 - y0).
        val h10 = t * t * t - 2.0 * t * t + t
        val h01 = -2.0 * t * t * t + 3.0 * t * t
        val h11 = t * t * t - t * t
        return (y0 + h01 * (y1 - y0) + h10 * m[i] * span +
            h11 * m[i + 1] * span).coerceIn(0.0, 1.0)
    }
}
