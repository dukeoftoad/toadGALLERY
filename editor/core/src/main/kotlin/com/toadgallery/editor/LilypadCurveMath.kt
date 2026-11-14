package com.toadgallery.editor

import kotlin.math.sqrt

// Tone-curve arithmetic as pure data (pinned on the JVM by LilypadCurveMathTest;
// the curve deck only reads these numbers). Monotone cubic Hermite with
// Fritsch-Carlson slope limiting — a natural spline would ring. The pipeline
// composes the master curve into every channel table, then the channel's own
// curve over it.

/**
 * The tone curve's pure arithmetic: validation, the tolerant sanitize, the
 * Fritsch-Carlson monotone Hermite evaluation, and the 256-entry LUT bake.
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
     * The STRICT contract (the UI's and the tests'): [MIN_POINTS]..[MAX_POINTS]
     * points, every x and y in 0..1, x strictly increasing. A failure here is a
     * programming error, not user input.
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
     * The TOLERANT read: coordinates coerce into 0..1, the list sorts by x,
     * duplicate-x points drop (Fritsch-Carlson divides by x gaps), the count
     * caps at [MAX_POINTS] keeping the smallest x's first. A curve reduced under
     * [MIN_POINTS] reads as empty (the caller decides what that means).
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
     * The Fritsch-Carlson tangent at every point: the secant average, flattened
     * wherever it would overshoot (the limiting rule IS the no-overshoot
     * guarantee). The caller hands over a SANITIZED list (sorted, distinct x, 2+).
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
            // A local extremum in the data flattens the tangent (the curve never rises past a turn).
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
                // Circle test: an aggressive tangent pair would overshoot the
                // segment's endpoints — scale both onto the safe circle (tau = 3 / sqrt(s)).
                val tau = 3.0 / sqrt(s)
                m[i] = tau * a * d[i]
                m[i + 1] = tau * b * d[i]
            }
        }
        return m
    }

    /**
     * The interpolant at [x], endpoint-clamped (below the first point's x reads
     * its y; above the last's, the last's). A degenerate list (under [MIN_POINTS]
     * after sanitize) reads as the identity.
     */
    fun evaluate(points: List<CurvePoint>, x: Double): Double {
        val p = sanitize(points)
        if (p.size < MIN_POINTS) return x.coerceIn(0.0, 1.0)
        return evaluateSanitized(p, slopes(p), x)
    }

    /**
     * The 256-entry LUT: tone v/255 through [evaluate], rounded and clamped to
     * a byte. The exact identity list returns the EXACT ramp by special case;
     * any other list passes exactly through its control points and clamps
     * outside their span.
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
     * The interpolant over an ALREADY-sanitized point list with pre-computed
     * tangents (the bake's inner loop); behaves exactly as [evaluate] documents.
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
        // y0-anchored Hermite bases: a FLAT segment (y1 == y0) evaluates to y0
        // exactly, where the textbook h00/h01 form leaves ±1ulp dust that a
        // 256-entry bake at a rounding boundary (0.3 * 255 = 76.4999...) turns
        // into a one-step LUT inversion. Endpoints stay exact the same way.
        val h10 = t * t * t - 2.0 * t * t + t
        val h01 = -2.0 * t * t * t + 3.0 * t * t
        val h11 = t * t * t - t * t
        return (y0 + h01 * (y1 - y0) + h10 * m[i] * span +
            h11 * m[i + 1] * span).coerceIn(0.0, 1.0)
    }
}
