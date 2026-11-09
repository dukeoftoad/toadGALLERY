package com.toadgallery.editor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The colour-grade + detail stage (r30, Phase 6 v1 slice 3).
 *
 *  - [HslBands]: per-channel HSL --- eight hue bands (red 0, orange 30,
 *    yellow 60, green 120, aqua 180, blue 240, purple 280, magenta 320
 *    degrees), each with hue shift [-180, 180], saturation [-100, 100] and
 *    luminance [-100, 100]. A pixel's band weight is a cosine hat over +/-
 *    30 degrees around the band centre (0 at the edge, 1 at the centre);
 *    overlapping bands share the pixel smoothly. RGB <-> HSL round-trips
 *    the standard formulas; the identity (all-zero) state is SKIPPED
 *    entirely so it is exact.
 *  - [applyVignette]: radial darkening (positive) / brightening (negative)
 *    of the frame edges --- smoothstep mask from 35% radius out to the
 *    corner, at most +-80% at the extreme corner.
 *  - [applySharpen]: a 3x3 unsharp mask, amount [-100, 100] -> weight
 *    0..1.5; the blur is a replicate-edge 3x3 mean. Zero is skipped
 *    (exact identity); a constant image is an identity at ANY amount.
 */
object RgbHsl {

    /** RGB (0..255) -> HSL (h in [0,360), s in [0,1], l in [0,1]). */
    fun toHsl(r: Int, g: Int, b: Int): Triple<Double, Double, Double> {
        val rr = r / 255.0
        val gg = g / 255.0
        val bb = b / 255.0
        val max = maxOf(rr, gg, bb)
        val min = minOf(rr, gg, bb)
        val l = (max + min) / 2.0
        if (max == min) return Triple(0.0, 0.0, l) // achromatic
        val d = max - min
        val s = if (l > 0.5) d / (2.0 - max - min) else d / (max + min)
        val h = when (max) {
            rr -> 60.0 * ((gg - bb) / d)
            gg -> 60.0 * ((bb - rr) / d) + 120.0
            else -> 60.0 * ((rr - gg) / d) + 240.0
        }
        val hNorm = if (h < 0.0) h + 360.0 else h
        return Triple(hNorm, s, l)
    }

    /** HSL (h any, s in [0,1], l in [0,1]) -> RGB (0..255). */
    fun fromHsl(h: Double, s: Double, l: Double): IntArray {
        val hh = ((h % 360.0) + 360.0) % 360.0
        val c = (1.0 - kotlin.math.abs(2.0 * l - 1.0)) * s
        val x = c * (1.0 - kotlin.math.abs(((hh / 60.0) % 2.0) - 1.0))
        val m = l - c / 2.0
        val (r0, g0, b0) = when {
            hh < 60.0 -> Triple(c, x, 0.0)
            hh < 120.0 -> Triple(x, c, 0.0)
            hh < 180.0 -> Triple(0.0, c, x)
            hh < 240.0 -> Triple(0.0, x, c)
            hh < 300.0 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return intArrayOf(
            ((r0 + m) * 255.0).roundToInt().coerceIn(0, 255),
            ((g0 + m) * 255.0).roundToInt().coerceIn(0, 255),
            ((b0 + m) * 255.0).roundToInt().coerceIn(0, 255),
        )
    }
}

/** One band's adjustments: hue shift in degrees, saturation and luminance in [-100, 100]. */
data class HslBandAdjust(
    val hue: Int = 0,
    val saturation: Int = 0,
    val luminance: Int = 0,
) {
    val isIdentity: Boolean get() = hue == 0 && saturation == 0 && luminance == 0
}

/** The eight-band HSL state; all-zero is the exact identity (stage skipped). */
data class HslBands(
    val red: HslBandAdjust = HslBandAdjust(),
    val orange: HslBandAdjust = HslBandAdjust(),
    val yellow: HslBandAdjust = HslBandAdjust(),
    val green: HslBandAdjust = HslBandAdjust(),
    val aqua: HslBandAdjust = HslBandAdjust(),
    val blue: HslBandAdjust = HslBandAdjust(),
    val purple: HslBandAdjust = HslBandAdjust(),
    val magenta: HslBandAdjust = HslBandAdjust(),
) {
    val isIdentity: Boolean
        get() = red.isIdentity && orange.isIdentity && yellow.isIdentity && green.isIdentity &&
            aqua.isIdentity && blue.isIdentity && purple.isIdentity && magenta.isIdentity

    /** The bands in fixed (hue-ascending by centre) order with their centres. */
    fun ordered(): List<Pair<Int, HslBandAdjust>> = listOf(
        0 to red, 30 to orange, 60 to yellow, 120 to green,
        180 to aqua, 240 to blue, 280 to purple, 320 to magenta,
    )

    companion object {
        /** The circular hue distance in degrees, in [0, 180]. */
        fun hueDistance(a: Double, b: Double): Double {
            val d = kotlin.math.abs(a - b) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }

        /** The cosine hat weight: 1 at the centre, 0 past 30 degrees. */
        fun bandWeight(hue: Double, center: Int): Double {
            val d = hueDistance(hue, center.toDouble())
            if (d >= 30.0) return 0.0
            return cos(d * PI / 60.0)
        }
    }
}

/** Applies the HSL bands to every pixel (in place); skipped entirely at identity. */
fun applyHslBands(pixels: IntArray, bands: HslBands) {
    if (bands.isIdentity) return
    val ordered = bands.ordered().filter { !it.second.isIdentity }
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val (h0, s0, l0) = RgbHsl.toHsl(r, g, b)
        // Achromatic pixels (s0 == 0) carry no hue to shift: only the
        // luminance part of a band could matter, and the luma stages
        // (exposure/shadows/highlights) already cover flat pixels ---
        // skip them so grey never drifts coloured.
        if (s0 == 0.0) continue
        var hueDelta = 0.0
        var satFactorPct = 100.0
        var lumDelta = 0.0
        for ((center, adjust) in ordered) {
            val w = HslBands.bandWeight(h0, center)
            if (w == 0.0) continue
            hueDelta += w * adjust.hue
            satFactorPct += w * adjust.saturation
            lumDelta += w * (adjust.luminance / 100.0) * 0.25
        }
        val h1 = ((h0 + hueDelta) % 360.0 + 360.0) % 360.0
        val s1 = (s0 * (satFactorPct / 100.0)).coerceIn(0.0, 1.0)
        val l1 = (l0 + lumDelta).coerceIn(0.0, 1.0)
        val rgb = RgbHsl.fromHsl(h1, s1, l1)
        pixels[i] = (a shl 24) or (rgb[0] shl 16) or (rgb[1] shl 8) or rgb[2]
    }
}

/** Vignette in [-100, 100]: positive darkens the frame edges, negative brightens them. */
fun applyVignette(pixels: IntArray, width: Int, height: Int, strength: Int) {
    require(strength in -100..100)
    if (strength == 0) return
    val cx = (width - 1) / 2.0
    val cy = (height - 1) / 2.0
    val maxDist = kotlin.math.hypot(cx, cy)
    val k = strength / 100.0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val r = kotlin.math.hypot(x - cx, y - cy) / maxDist
            val mask = LutBuilder.smoothstep(0.35, 1.0, r)
            if (mask == 0.0) continue
            val factor = (1.0 - 0.8 * k * mask).coerceIn(0.0, 2.0)
            val i = y * width + x
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val nr = (((p ushr 16) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
            val ng = (((p ushr 8) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
            val nb = ((p and 0xFF) * factor).roundToInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }
}

/** Unsharp mask in [-100, 100] -> weight 0..1.5 over a replicate-edge 3x3 mean. */
fun applySharpen(pixels: IntArray, width: Int, height: Int, amount: Int) {
    require(amount in -100..100)
    if (amount == 0) return
    val k = amount / 100.0 * 1.5
    // The 3x3 mean blur into a scratch buffer.
    val blur = IntArray(pixels.size)
    for (y in 0 until height) {
        val yUp = (y - 1).coerceAtLeast(0)
        val yDown = (y + 1).coerceAtMost(height - 1)
        for (x in 0 until width) {
            val xLeft = (x - 1).coerceAtLeast(0)
            val xRight = (x + 1).coerceAtMost(width - 1)
            var sumR = 0
            var sumG = 0
            var sumB = 0
            for (yy in intArrayOf(yUp, y, yDown)) {
                for (xx in intArrayOf(xLeft, x, xRight)) {
                    val p = pixels[yy * width + xx]
                    sumR += (p ushr 16) and 0xFF
                    sumG += (p ushr 8) and 0xFF
                    sumB += p and 0xFF
                }
            }
            val i = y * width + x
            blur[i] = (
                (0xFF shl 24) or
                    (sumR / 9 shl 16) or
                    (sumG / 9 shl 8) or
                    (sumB / 9)
                )
        }
    }
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val br = (blur[i] ushr 16) and 0xFF
        val bg = (blur[i] ushr 8) and 0xFF
        val bb = blur[i] and 0xFF
        val nr = (r + k * (r - br)).roundToInt().coerceIn(0, 255)
        val ng = (g + k * (g - bg)).roundToInt().coerceIn(0, 255)
        val nb = (b + k * (b - bb)).roundToInt().coerceIn(0, 255)
        pixels[i] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
    }
}
