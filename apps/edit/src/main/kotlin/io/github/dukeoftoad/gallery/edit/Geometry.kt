package io.github.dukeoftoad.gallery.edit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The geometry toolset (r30, Phase 6 v1 slice 1). Pure grid math over the
 * same IntArray-ARGB pixels [PixelAdjuster] works on; every op is
 * identity-exact at its zero state and JVM-tested:
 *
 *  - [CropRect]: a normalized (0..1) crop rectangle with clamping,
 *    reordering and a minimal-span floor, convertible to a pixel rect.
 *  - [CropAspect]: preset ratios (FREE / ORIGINAL / 1:1 / 4:3 / 3:4 /
 *    16:9 / 9:16) fitted as the largest centered rect of that ratio
 *    inside a box.
 *  - [flipPixels]: horizontal / vertical mirroring.
 *  - [straightenPixels]: arbitrary-angle rotation (positive = clockwise,
 *    the straighten-slider convention) around the image centre on an
 *    EXPANDED canvas (the bounding box of the rotated corners), corners
 *    outside the source filling transparent, interior sampled bilinearly.
 *  - [PerspectiveQuad] + [perspectiveWarp]: the quad-pull correction ---
 *    four corner displacements (clamped to a quarter of the corresponding
 *    dimension); the warped quad is stretched to the full output frame
 *    (the crop-with-perspective semantics: the dragged region becomes the
 *    frame), sampled bilinearly.
 *
 * Pixel-centre convention (documented, consistent): pixel (x, y) has its
 * centre AT (x, y); the canvas centre is ((w-1)/2, (h-1)/2). This makes
 * every zero-state EXACTLY the identity (a 0-degree straighten or an
 * identity quad re-samples every pixel onto itself).
 */

/** The shared geometry result: an output grid that may change dimensions. */
data class GeometryResult(val pixels: IntArray, val width: Int, val height: Int)

// ---------------------------------------------------------------------------
// Crop
// ---------------------------------------------------------------------------

/** A normalized crop rect: every coordinate in [0, 1] over the image. */
data class CropRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {

    /**
     * Clamps into [0, 1], reorders so left <= right and top <= bottom, and
     * enforces a minimal span (1/512) so no degenerate rect survives.
     */
    fun normalized(): CropRect {
        var l = left.coerceIn(0.0, 1.0)
        var t = top.coerceIn(0.0, 1.0)
        var r = right.coerceIn(0.0, 1.0)
        var b = bottom.coerceIn(0.0, 1.0)
        if (l > r) { val tmp = l; l = r; r = tmp }
        if (t > b) { val tmp = t; t = b; b = tmp }
        if (r - l < MIN_SPAN) r = (l + MIN_SPAN).coerceAtMost(1.0)
        if (b - t < MIN_SPAN) b = (t + MIN_SPAN).coerceAtMost(1.0)
        return CropRect(l, t, r, b)
    }

    /** The pixel rect (x0, y0) inclusive .. (x1, y1) exclusive over w x h. */
    fun toPixels(width: Int, height: Int): PixelRect {
        val n = normalized()
        val x0 = (n.left * width).roundToInt().coerceIn(0, width - 1)
        val y0 = (n.top * height).roundToInt().coerceIn(0, height - 1)
        val x1 = (n.right * width).roundToInt().coerceIn(x0 + 1, width)
        val y1 = (n.bottom * height).roundToInt().coerceIn(y0 + 1, height)
        return PixelRect(x0, y0, x1, y1)
    }

    companion object {
        /** The smallest surviving normalized span (never a zero-size crop). */
        const val MIN_SPAN: Double = 1.0 / 512.0
    }
}

/** An integer pixel rect: [x0, x1) x [y0, y1). */
data class PixelRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width: Int get() = x1 - x0
    val height: Int get() = y1 - y0
}

/** Aspect presets for crop fitting. */
enum class CropAspect(val ratio: Double?) {
    FREE(null),
    ORIGINAL(null),
    SQUARE(1.0),
    RATIO_4_3(4.0 / 3.0),
    RATIO_3_4(3.0 / 4.0),
    RATIO_16_9(16.0 / 9.0),
    RATIO_9_16(9.0 / 16.0);

    /**
     * The largest centered rect of this aspect inside [box]. FREE returns
     * the box untouched; ORIGINAL uses the image's own ratio.
     */
    fun fit(box: PixelRect, imageWidth: Int, imageHeight: Int): PixelRect {
        val target: Double = when (this) {
            FREE -> return box
            ORIGINAL -> imageWidth.toDouble() / imageHeight.toDouble()
            else -> ratio!!
        }
        val boxW = box.width
        val boxH = box.height
        var w = boxW.toDouble()
        var h = w / target
        if (h > boxH) {
            h = boxH.toDouble()
            w = h * target
        }
        val wInt = w.toInt().coerceIn(1, boxW)
        val hInt = h.toInt().coerceIn(1, boxH)
        val cx = (box.x0 + box.x1) / 2
        val cy = (box.y0 + box.y1) / 2
        val x0 = (cx - wInt / 2).coerceAtLeast(box.x0)
        val y0 = (cy - hInt / 2).coerceAtLeast(box.y0)
        return PixelRect(x0, y0, (x0 + wInt).coerceAtMost(box.x1), (y0 + hInt).coerceAtMost(box.y1))
    }
}

/** Cuts [rect] out of the grid; returns a rect-sized grid. */
fun cropPixels(pixels: IntArray, width: Int, height: Int, rect: PixelRect): GeometryResult {
    require(rect.x0 >= 0 && rect.y0 >= 0 && rect.x1 <= width && rect.y1 <= height) {
        "crop rect outside the image"
    }
    val outW = rect.width
    val outH = rect.height
    val out = IntArray(outW * outH)
    for (y in 0 until outH) {
        val srcRow = (rect.y0 + y) * width
        val dstRow = y * outW
        System.arraycopy(pixels, srcRow + rect.x0, out, dstRow, outW)
    }
    return GeometryResult(out, outW, outH)
}

// ---------------------------------------------------------------------------
// Flip
// ---------------------------------------------------------------------------

/** Mirrors the grid horizontally or vertically; returns a new grid. */
fun flipPixels(pixels: IntArray, width: Int, height: Int, horizontal: Boolean): IntArray {
    val out = IntArray(pixels.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val srcIndex = y * width + x
            val dstIndex = if (horizontal) {
                y * width + (width - 1 - x)
            } else {
                (height - 1 - y) * width + x
            }
            out[dstIndex] = pixels[srcIndex]
        }
    }
    return out
}

// ---------------------------------------------------------------------------
// Straighten
// ---------------------------------------------------------------------------

/**
 * Bilinear sample at continuous coordinates with edge clamping.
 * [x] in [0, w-1], [y] in [0, h-1] (callers guard the outside case).
 */
fun bilinearSample(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
    val ix = x.toInt().coerceIn(0, width - 2)
    val iy = y.toInt().coerceIn(0, height - 2)
    val fx = (x - ix).coerceIn(0.0, 1.0)
    val fy = (y - iy).coerceIn(0.0, 1.0)
    val i00 = pixels[iy * width + ix]
    val i10 = pixels[iy * width + ix + 1]
    val i01 = pixels[(iy + 1) * width + ix]
    val i11 = pixels[(iy + 1) * width + ix + 1]
    fun mix(a: Int, b: Int, t: Double, shift: Int): Int {
        val av = (a ushr shift) and 0xFF
        val bv = (b ushr shift) and 0xFF
        return (av + ((bv - av) * t).roundToInt()).coerceIn(0, 255)
    }
    val a = mix(i00, i10, fx, 24)
    val r = mix(i00, i10, fx, 16)
    val g = mix(i00, i10, fx, 8)
    val b = mix(i00, i10, fx, 0)
    val a2 = mix(i01, i11, fx, 24)
    val r2 = mix(i01, i11, fx, 16)
    val g2 = mix(i01, i11, fx, 8)
    val b2 = mix(i01, i11, fx, 0)
    return (
        (mix2(a, a2, fy) shl 24) or
            (mix2(r, r2, fy) shl 16) or
            (mix2(g, g2, fy) shl 8) or
            mix2(b, b2, fy)
        )
}

private fun mix2(a: Int, b: Int, t: Double): Int =
    (a + ((b - a) * t).roundToInt()).coerceIn(0, 255)

/**
 * Straighten: rotates by [angleDeg] degrees (positive = CLOCKWISE, the
 * straighten-slider convention) around the image centre, onto an expanded
 * canvas --- the bounding box of the rotated corners, rounded UP so no
 * rotated pixel is ever cut. Outside-the-source points fill transparent;
 * inside points are sampled bilinearly. 0 degrees is EXACTLY the identity
 * (same grid, same pixels).
 */
fun straightenPixels(pixels: IntArray, width: Int, height: Int, angleDeg: Double): GeometryResult {
    if (angleDeg == 0.0) return GeometryResult(pixels, width, height)
    val rad = angleDeg * PI / 180.0
    val cosT = cos(rad)
    val sinT = sin(rad)
    val ac = abs(cosT)
    val as = abs(sinT)
    val outW = (width * ac + height * as).let { kotlin.math.ceil(it).toInt().coerceAtLeast(1) }
    val outH = (width * as + height * ac).let { kotlin.math.ceil(it).toInt().coerceAtLeast(1) }
    val cx = (width - 1) / 2.0
    val cy = (height - 1) / 2.0
    val ocx = (outW - 1) / 2.0
    val ocy = (outH - 1) / 2.0
    val out = IntArray(outW * outH)
    for (oy in 0 until outH) {
        for (ox in 0 until outW) {
            // Inverse map: rotate the output centre back by -theta.
            val dx = ox - ocx
            val dy = oy - ocy
            val sx = cx + dx * cosT + dy * sinT
            val sy = cy - dx * sinT + dy * cosT
            out[oy * outW + ox] = if (sx < 0.0 || sx > width - 1.0 || sy < 0.0 || sy > height - 1.0) {
                0 // transparent fill outside the source
            } else {
                bilinearSample(pixels, width, height, sx, sy)
            }
        }
    }
    return GeometryResult(out, outW, outH)
}

// ---------------------------------------------------------------------------
// Perspective
// ---------------------------------------------------------------------------

/**
 * The perspective quad: four corner DISPLACEMENTS in pixels, each from the
 * image's own corresponding corner. Displacements are clamped to a quarter
 * of the corresponding dimension before use ([clamped]) --- a quad that
 * could fold or escape the image is unreachable by construction.
 */
data class PerspectiveQuad(
    val tlX: Double, val tlY: Double,
    val trX: Double, val trY: Double,
    val brX: Double, val brY: Double,
    val blX: Double, val blY: Double,
) {
    /** Clamps every displacement into +/- w/4 (x) and +/- h/4 (y). */
    fun clamped(width: Int, height: Int): PerspectiveQuad {
        val mx = width / 4.0
        val my = height / 4.0
        fun c(v: Double, m: Double) = v.coerceIn(-m, m)
        return PerspectiveQuad(
            c(tlX, mx), c(tlY, my),
            c(trX, mx), c(trY, my),
            c(brX, mx), c(brY, my),
            c(blX, mx), c(blY, my),
        )
    }

    /**
     * The bilinear map of the unit square onto the (displaced) quad, in
     * image pixel coordinates: (u, v) in [0,1]^2 -> the quad's point.
     * The identity quad maps (u, v) to (u*(w-1), v*(h-1)) exactly.
     */
    fun point(u: Double, v: Double, width: Int, height: Int): Pair<Double, Double> {
        val x0 = tlX
        val y0 = tlY
        val x1 = (width - 1).toDouble() + trX
        val y1 = trY
        val x2 = (width - 1).toDouble() + brX
        val y2 = (height - 1).toDouble() + brY
        val x3 = blX
        val y3 = (height - 1).toDouble() + blY
        val bu = 1.0 - u
        val bv = 1.0 - v
        val px = bu * bv * x0 + u * bv * x1 + u * v * x2 + bu * v * x3
        val py = bu * bv * y0 + u * bv * y1 + u * v * y2 + bu * v * y3
        return px to py
    }

    companion object {
        val IDENTITY = PerspectiveQuad(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Perspective correction: the warped quad (the dragged region) is
 * stretched to the FULL output frame --- output (x, y) samples the quad's
 * bilinear map at (x/(w-1), y/(h-1)). The identity quad is EXACTLY the
 * identity transform (every output pixel re-samples its own source
 * pixel); a displaced quad puts the dragged corners exactly at the
 * output corners.
 */
fun perspectiveWarp(
    pixels: IntArray,
    width: Int,
    height: Int,
    quad: PerspectiveQuad,
): GeometryResult {
    val q = quad.clamped(width, height)
    if (q == PerspectiveQuad.IDENTITY) return GeometryResult(pixels, width, height)
    val out = IntArray(width * height)
    for (y in 0 until height) {
        val v = if (height == 1) 0.0 else y.toDouble() / (height - 1)
        for (x in 0 until width) {
            val u = if (width == 1) 0.0 else x.toDouble() / (width - 1)
            val (sx, sy) = q.point(u, v, width, height)
            out[y * width + x] = if (sx < 0.0 || sx > width - 1.0 || sy < 0.0 || sy > height - 1.0) {
                0
            } else {
                bilinearSample(pixels, width, height, sx, sy)
            }
        }
    }
    return GeometryResult(out, width, height)
}
