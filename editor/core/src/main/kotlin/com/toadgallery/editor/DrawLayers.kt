package com.toadgallery.editor

import kotlin.math.ceil

/**
 * The freehand stroke-layer system. Pure Kotlin, JVM-tested (ADR-0011): only the
 * Paint/Canvas pass lives in `DrawRender.kt`, sharing this layout math for preview and
 * export --- one code path. One gesture = one layer = one undo snapshot; points are not
 * editable in v1. The recipe container is additive: a missing `drawLayers` key loads
 * byte-clean; empty normalises back to absent.
 */
data class DrawLayer(
    val id: String,
    /** Full 0xAARRGGBB (the alpha byte is authored data; the render multiplies it with [opacity]). */
    val colorArgb: Long = DEFAULT_COLOR,
    /** 1..100, the brush width as a percent of the OUTPUT canvas min-edge. */
    val widthPct: Int = DEFAULT_WIDTH_PCT,
    /** 0..100. */
    val opacity: Int = 100,
    /** 0..100 -> round(pct/25) Chaikin corner-cutting passes at place time. */
    val smoothingPct: Int = DEFAULT_SMOOTHING_PCT,
    /** 1..MAX_POINTS points, fractions of the output canvas; one point renders as a dot. */
    val points: List<Point>,
) {
    init {
        require(id.isNotBlank()) { "layer id must not be blank" }
        require(id.length <= MAX_ID_LENGTH) { "layer id longer than $MAX_ID_LENGTH chars" }
        require(colorArgb in 0..0xFFFFFFFFL) { "colorArgb outside the unsigned ARGB range" }
        require(widthPct in 1..100) { "widthPct must be in 1..100" }
        require(opacity in 0..100) { "opacity must be in 0..100" }
        require(smoothingPct in 0..100) { "smoothingPct must be in 0..100" }
        require(points.size in 1..MAX_POINTS) { "a stroke needs 1..$MAX_POINTS points" }
    }

    /** A stroke point: fractions of the output canvas (the family anchor convention). */
    data class Point(val x: Double, val y: Double) {
        init {
            require(x in 0.0..1.0) { "point x must be in 0..1" }
            require(y in 0.0..1.0) { "point y must be in 0..1" }
        }
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_POINTS = 512
        const val DEFAULT_COLOR = 0xFFFFFFFFL
        const val DEFAULT_WIDTH_PCT = 3
        const val DEFAULT_SMOOTHING_PCT = 50

        /**
         * The next free session id over `existing` ("d1", "d2", ...): the family
         * `newId` convention, the `d` prefix keeping the families collision-free.
         */
        fun newId(existing: Collection<String>): String {
            var max = 0
            for (id in existing) {
                val n = id.removePrefix("d").toIntOrNull()
                if (n != null && n > max) max = n
            }
            return "d${max + 1}"
        }

        /**
         * The commit-time thinning: a live gesture can sample at frame rate, so the
         * COMMIT stores a stride-thinned list --- index 0, every `stride`-th index below
         * `n-1`, then `n-1`; <= cap+1 points, deterministic, endpoints always kept.
         */
        fun thin(points: List<Point>, cap: Int): List<Point> {
            require(cap >= 1) { "the thinning cap must be >= 1" }
            val n = points.size
            if (n <= cap) return points
            val stride = ceil(n / cap.toDouble()).toInt()
            val out = ArrayList<Point>(cap + 1)
            out.add(points[0])
            var i = stride
            while (i < n - 1) {
                out.add(points[i])
                i += stride
            }
            out.add(points[n - 1])
            return out
        }
    }
}

/**
 * The recipe's additive stroke container: absent (null) = a clean or pre-stroke
 * session; empty is normalised BACK to absent by the codec.
 */
data class DrawLayers(val layers: List<DrawLayer>) {
    init {
        require(layers.size == layers.map { it.id }.toSet().size) { "duplicate stroke layer id" }
    }

    val isIdentity: Boolean get() = layers.isEmpty()

    companion object {
        val EMPTY = DrawLayers(emptyList())
    }
}

/**
 * The pure layout pass (JVM-tested): turns the recipe's fraction-space strokes into
 * pixel-space draw specs IN LIST ORDER (layers render in list order, top LAST).
 * Smoothing runs HERE in pixel space as Chaikin corner-cutting (pass count from the
 * smoothing slider); a single-point layer draws as a dot of the brush width.
 */
object DrawLayout {

    /** One layer resolved into pixel space, ready to draw (verbatim layer included). */
    data class Placed(
        val layer: DrawLayer,
        /** The brush width in output pixels. */
        val widthPx: Float,
        /** 0..255 paint alpha: the color's alpha byte x the opacity slider. */
        val alpha: Int,
        /**
         * The SMOOTHED polyline in pixel space (or the single mapped point for a dot);
         * the compositor executes this verbatim.
         */
        val pointsPx: List<Pair<Float, Float>>,
    )

    /** The brush width: percent of the canvas min-edge, the family size convention. */
    fun widthPxFor(widthPct: Int, canvasMinEdge: Float): Float = widthPct / 100f * canvasMinEdge

    /**
     * The paint alpha: the color's OWN alpha byte scaled by the opacity slider,
     * rounded half-up ((a*opacity + 50) / 100, the text family's math).
     */
    fun alphaFor(layer: DrawLayer): Int {
        val colorAlpha = ((layer.colorArgb ushr 24) and 0xFFL).toInt()
        return (colorAlpha * layer.opacity + 50) / 100
    }

    /** The Chaikin pass count: round(pct/25), coerced to 0..4. */
    fun chaikinPasses(smoothingPct: Int): Int =
        kotlin.math.round(smoothingPct / 25.0).toInt().coerceIn(0, 4)

    /**
     * Chaikin corner-cutting on an OPEN polyline, endpoints preserved: each edge
     * (a, b) emits 0.75a + 0.25b then 0.25a + 0.75b; output is [P0, Q0, R0, ..., Plast].
     * The affine fraction->pixel map commutes with it, so pixel-space smoothing is exact.
     */
    fun smoothPolyline(points: List<Pair<Float, Float>>, passes: Int): List<Pair<Float, Float>> {
        if (passes <= 0 || points.size < 3) return points
        var current = points
        repeat(passes) {
            if (current.size < 3) return@repeat
            val next = ArrayList<Pair<Float, Float>>(current.size * 2)
            next.add(current[0])
            for (i in 0 until current.size - 1) {
                val a = current[i]
                val b = current[i + 1]
                next.add(0.75f * a.first + 0.25f * b.first to 0.75f * a.second + 0.25f * b.second)
                next.add(0.25f * a.first + 0.75f * b.first to 0.25f * a.second + 0.75f * b.second)
            }
            next.add(current[current.size - 1])
            current = next
        }
        return current
    }

    fun place(
        layers: List<DrawLayer>,
        originX: Float,
        originY: Float,
        canvasW: Float,
        canvasH: Float,
    ): List<Placed> {
        require(canvasW > 0f && canvasH > 0f) { "canvas must have positive dimensions" }
        val minEdge = minOf(canvasW, canvasH)
        return layers.map { layer ->
            val mapped = layer.points.map { p ->
                (originX + p.x * canvasW).toFloat().coerceIn(originX, originX + canvasW) to
                    (originY + p.y * canvasH).toFloat().coerceIn(originY, originY + canvasH)
            }
            val passes = chaikinPasses(layer.smoothingPct)
            Placed(
                layer = layer,
                widthPx = widthPxFor(layer.widthPct, minEdge),
                alpha = alphaFor(layer),
                pointsPx = if (mapped.size == 1) mapped else smoothPolyline(mapped, passes),
            )
        }
    }
}
