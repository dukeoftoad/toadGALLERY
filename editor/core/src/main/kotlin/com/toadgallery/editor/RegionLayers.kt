package com.toadgallery.editor

import kotlin.math.ceil
import kotlin.math.floor

/**
 * The mosaic/blur region system (r37, Phase 7 v2; the design contract is
 * `docs/phase-7/mosaic-regions.md`, decision set 1-6).
 *
 * Regions are a PIXEL stage, not a compositing pass: the user paints a
 * closed path, and the region under the path gets a mosaic (block
 * average) or a linear blur (horizontal box smear) at a strength, over
 * the FULLY graded pixels --- the stage runs AFTER sharpen, as the
 * pipeline's final pixel stage (`EditPipeline.apply`).
 *
 * Pure Kotlin, JVM-tested (the repo's ADR-0011 convention): the mask is
 * an even-odd scanline fill, the effects are integer-deterministic, and
 * both are pinned by exact hand-grids in `RegionLayersTest`.
 */
enum class RegionKind(val wireName: String) {
    MOSAIC("mosaic"),
    LINEAR_BLUR("linearBlur"),
}

data class RegionLayer(
    val id: String,
    val kind: RegionKind,
    /** 1..100 -> cellPx / radiusPx = max(2|1, strengthPct x minEdge / 1000). */
    val strengthPct: Int = DEFAULT_STRENGTH_PCT,
    /** 3..MAX_POINTS points, fractions of the output canvas; the painted closed path. */
    val points: List<Point>,
) {
    init {
        require(id.isNotBlank()) { "layer id must not be blank" }
        require(id.length <= MAX_ID_LENGTH) { "layer id longer than $MAX_ID_LENGTH chars" }
        require(strengthPct in 1..100) { "strengthPct must be in 1..100" }
        require(points.size in 3..MAX_POINTS) { "a region needs 3..$MAX_POINTS points" }
    }

    /** A region point: fractions of the output canvas (the family anchor convention). */
    data class Point(val x: Double, val y: Double) {
        init {
            require(x in 0.0..1.0) { "point x must be in 0..1" }
            require(y in 0.0..1.0) { "point y must be in 0..1" }
        }
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_POINTS = 512
        const val DEFAULT_STRENGTH_PCT = 12

        /**
         * The next free session id over `existing` ("r1", "r2", ...): the
         * family `newId` convention, the `r` prefix keeping the families
         * collision-free by construction.
         */
        fun newId(existing: Collection<String>): String {
            var max = 0
            for (id in existing) {
                val n = id.removePrefix("r").toIntOrNull()
                if (n != null && n > max) max = n
            }
            return "r${max + 1}"
        }

        /**
         * The commit-time thinning --- the stride rule the stroke family
         * defined (drawing-tool.md decision 3), duplicated here rather
         * than coupled: the families stay independent, the algorithm is
         * documented in both places (the stores' convention).
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
 * The recipe's additive region container: absent (null) = a pre-r37
 * sidecar or a clean session; empty is normalised BACK to absent by the
 * codec (the family rule).
 */
data class RegionLayers(val layers: List<RegionLayer>) {
    init {
        require(layers.size == layers.map { it.id }.toSet().size) { "duplicate region layer id" }
    }

    val isIdentity: Boolean get() = layers.isEmpty()

    companion object {
        val EMPTY = RegionLayers(emptyList())
    }
}

/**
 * The pure mask builder: an even-odd scanline fill of the painted
 * polygon over the stage's grid. Pixel (x, y) is IN when its centre
 * (x + 0.5, y + 0.5) lies strictly inside the path. Edges crossing the
 * centre line produce a sorted crossing list; centres strictly between
 * crossing PAIRS are in. Exactly testable (pinned masks in
 * `RegionLayersTest`).
 */
object RegionMask {

    fun build(points: List<RegionLayer.Point>, width: Int, height: Int): BooleanArray {
        require(width > 0 && height > 0) { "the grid must have positive dimensions" }
        val mask = BooleanArray(width * height)
        if (points.size < 3) return mask // a degenerate path masks nothing
        val px = DoubleArray(points.size)
        val py = DoubleArray(points.size)
        for (i in points.indices) {
            px[i] = points[i].x * width
            py[i] = points[i].y * height
        }
        val crossings = ArrayList<Double>(points.size)
        for (y in 0 until height) {
            val yc = y + 0.5
            crossings.clear()
            for (i in points.indices) {
                val j = (i + 1) % points.size
                val y0 = py[i]
                val y1 = py[j]
                // One-sided test: an edge counts when it strictly spans
                // the centre line in exactly one direction --- vertices
                // and horizontal edges never double-count.
                if ((y0 <= yc && y1 > yc) || (y1 <= yc && y0 > yc)) {
                    val t = (yc - y0) / (y1 - y0)
                    crossings.add(px[i] + t * (px[j] - px[i]))
                }
            }
            if (crossings.size < 2) continue
            crossings.sort()
            for (pair in 0 until crossings.size / 2) {
                val xa = crossings[2 * pair]
                val xb = crossings[2 * pair + 1]
                if (xb <= xa) continue
                // Centres xc = x + 0.5 strictly inside (xa, xb):
                // smallest x with x + 0.5 > xa, largest with x + 0.5 < xb.
                var x0 = floor(xa - 0.5).toInt() + 1
                if (x0 < 0) x0 = 0
                var x1 = ceil(xb - 0.5).toInt() - 1
                if (x1 > width - 1) x1 = width - 1
                for (x in x0..x1) {
                    mask[y * width + x] = true
                }
            }
        }
        return mask
    }
}

/**
 * The pure effect kernels (integer-deterministic, JVM-tested on hand
 * grids): the mosaic block average and the horizontal linear blur.
 * Multiple regions apply in list order; each pass reads a SNAPSHOT of
 * the grid it receives (its own writes never feed its own reads).
 */
object RegionEffects {

    /** The mosaic cell edge: max(2, strengthPct x minEdge / 1000), integer floor. */
    fun mosaicCellPx(strengthPct: Int, minEdge: Int): Int =
        (strengthPct * minEdge / 1000).coerceAtLeast(2)

    /** The linear-blur radius: max(1, strengthPct x minEdge / 1000), integer floor. */
    fun blurRadiusPx(strengthPct: Int, minEdge: Int): Int =
        (strengthPct * minEdge / 1000).coerceAtLeast(1)

    /**
     * The mosaic: cells of [cellPx] aligned to the grid origin; for each
     * cell, the masked pixels average (integer floor division by the
     * count) and the average writes back to exactly those pixels. Alpha
     * untouched. Reads happen per cell BEFORE any write in that cell
     * (a cell never feeds itself).
     */
    fun applyMosaic(pixels: IntArray, width: Int, height: Int, mask: BooleanArray, cellPx: Int) {
        require(cellPx >= 1) { "cellPx must be >= 1" }
        for (cy in 0 until height step cellPx) {
            for (cx in 0 until width step cellPx) {
                val xEnd = minOf(cx + cellPx, width)
                val yEnd = minOf(cy + cellPx, height)
                var rs = 0
                var gs = 0
                var bs = 0
                var count = 0
                for (y in cy until yEnd) {
                    val row = y * width
                    for (x in cx until xEnd) {
                        if (!mask[row + x]) continue
                        val c = pixels[row + x]
                        rs += (c shr 16) and 0xFF
                        gs += (c shr 8) and 0xFF
                        bs += c and 0xFF
                        count++
                    }
                }
                if (count == 0) continue
                val rAvg = rs / count
                val gAvg = gs / count
                val bAvg = bs / count
                for (y in cy until yEnd) {
                    val row = y * width
                    for (x in cx until xEnd) {
                        if (!mask[row + x]) continue
                        pixels[row + x] = (pixels[row + x] and 0xFF000000.toInt()) or
                            (rAvg shl 16) or (gAvg shl 8) or bAvg
                    }
                }
            }
        }
    }

    /**
     * The linear blur: for each masked pixel, the integer average of its
     * horizontal window [x-r, x+r] (clamped to the grid, divided by the
     * clamped length) over a SNAPSHOT of the incoming state, written
     * only to masked pixels. Sliding-window sums: O(row) per region
     * regardless of r (pinned equivalent to the per-pixel loop).
     */
    fun applyLinearBlur(pixels: IntArray, width: Int, height: Int, mask: BooleanArray, radiusPx: Int) {
        require(radiusPx >= 1) { "radiusPx must be >= 1" }
        val snapshot = pixels.copyOf()
        for (y in 0 until height) {
            val row = y * width
            var hasMasked = false
            for (x in 0 until width) {
                if (mask[row + x]) {
                    hasMasked = true
                    break
                }
            }
            if (!hasMasked) continue
            // The sliding window over the whole row: sum of src[x-r..x+r].
            var sumR = 0
            var sumG = 0
            var sumB = 0
            // Initialise the window at x = 0: [max(0, 0-r), min(w-1, 0+r)].
            val wStart0 = -radiusPx
            val wEnd0 = radiusPx
            for (i in wStart0..wEnd0) {
                if (i < 0 || i > width - 1) continue
                val c = snapshot[row + i]
                sumR += (c shr 16) and 0xFF
                sumG += (c shr 8) and 0xFF
                sumB += c and 0xFF
            }
            var winStart = wStart0
            var winEnd = wStart0 + 2 * radiusPx
            var winCount = 0
            for (i in winStart..winEnd) {
                if (i in 0 until width) winCount++
            }
            for (x in 0 until width) {
                if (mask[row + x]) {
                    pixels[row + x] = (pixels[row + x] and 0xFF000000.toInt()) or
                        ((sumR / winCount) shl 16) or ((sumG / winCount) shl 8) or (sumB / winCount)
                }
                // Slide: drop winStart, add winEnd + 1.
                if (winStart >= 0 && winStart <= width - 1) {
                    val outC = snapshot[row + winStart]
                    sumR -= (outC shr 16) and 0xFF
                    sumG -= (outC shr 8) and 0xFF
                    sumB -= outC and 0xFF
                    winCount--
                }
                winStart++
                winEnd++
                if (winEnd >= 0 && winEnd <= width - 1) {
                    val inC = snapshot[row + winEnd]
                    sumR += (inC shr 16) and 0xFF
                    sumG += (inC shr 8) and 0xFF
                    sumB += inC and 0xFF
                    winCount++
                }
            }
        }
    }
}

/**
 * The stage entry the pipeline calls: every region in list order over
 * the grid it receives (`EditPipeline.apply`, the final pixel stage).
 */
object RegionPass {

    fun applyAll(pixels: IntArray, width: Int, height: Int, regions: RegionLayers) {
        if (regions.isIdentity) return
        val minEdge = minOf(width, height)
        for (layer in regions.layers) {
            val mask = RegionMask.build(layer.points, width, height)
            when (layer.kind) {
                RegionKind.MOSAIC -> {
                    val cell = RegionEffects.mosaicCellPx(layer.strengthPct, minEdge)
                    RegionEffects.applyMosaic(pixels, width, height, mask, cell)
                }
                RegionKind.LINEAR_BLUR -> {
                    val radius = RegionEffects.blurRadiusPx(layer.strengthPct, minEdge)
                    RegionEffects.applyLinearBlur(pixels, width, height, mask, radius)
                }
            }
        }
    }
}
