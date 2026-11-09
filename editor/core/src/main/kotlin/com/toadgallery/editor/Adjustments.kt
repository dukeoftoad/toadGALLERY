package com.toadgallery.editor

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The adjustment suite's first two stages (r30, Phase 6 v1 slice 2).
 *
 *  - [LutBuilder]: the PER-CHANNEL stage --- every parameter is a pure
 *    function of one channel value, so the whole stage composes into a
 *    single 256-entry LUT applied once per pixel (O(1) per channel at
 *    render time regardless of how many parameters are non-zero):
 *    exposure -> brightness -> contrast -> highlights -> shadows, in that
 *    fixed documented order.
 *  - [PixelOps]: the FULL-PIXEL stage --- parameters that need the whole
 *    pixel (saturation needs the pixel's luma; temperature/tint cross
 *    channels). Applied per pixel; stages with a zero parameter are
 *    SKIPPED entirely so the identity is exact, never approximated.
 *
 * All ranges are [-100, 100] with 0 = neutral, matching the r29
 * brightness/contrast conventions.
 */
object LutBuilder {

    /** The stage's composition order, exported for tests and the recipe docs. */
    const val ORDER: String = "exposure -> brightness -> contrast -> highlights -> shadows"

    /**
     * Builds the composed LUT.
     *
     *  - exposure e: EV = e/100 stops, v' = v * 2^EV (0 = +0 EV, identity).
     *  - brightness b: v' = v + 2.55*b (the r29 rule).
     *  - contrast c: the r29 mid-grey-pivot factor.
     *  - highlights h: v' = v + h * 2.55 * 0.5 * S(128, 255, v) ---
     *    only the bright half moves (S = smoothstep), scaled by half so
     *    even +100 cannot clip pure white past a stop.
     *  - shadows s: v' = v + s * 2.55 * 0.5 * (1 - S(0, 128, v)) --- only
     *    the dark half moves.
     */
    fun build(
        brightness: Int,
        contrast: Int,
        exposure: Int,
        highlights: Int,
        shadows: Int,
    ): IntArray {
        require(brightness in -100..100 && contrast in -100..100 && exposure in -100..100)
        require(highlights in -100..100 && shadows in -100..100)
        val ev = exposure / 100.0
        val gain = 2.0.pow(ev)
        val contrastPercent = contrast * 2.55
        val contrastFactor = (259.0 * (contrastPercent + 255.0)) / (255.0 * (259.0 - contrastPercent))
        return IntArray(256) { v ->
            var out = v * gain
            out += brightness * 2.55
            out = (out - 128.0) * contrastFactor + 128.0
            // 127.5/100 is numerically EXACT (127.5 = 255/2 is representable)
            // where s * 2.55 * 0.5 carries binary float dust (2.55 is not
            // representable; 100 * 2.55 * 0.5 = 127.49999... rounds to 127,
            // one off the documented +127.5 half-scale). Same scaling, exact
            // at the slider extremes.
            val hlWeight = smoothstep(128.0, 255.0, out)
            out += highlights * 127.5 / 100.0 * hlWeight
            val shWeight = 1.0 - smoothstep(0.0, 128.0, out)
            out += shadows * 127.5 / 100.0 * shWeight
            out.roundToInt().coerceIn(0, 255)
        }
    }

    /** The smoothstep used by the masked shifts: 0 at edge0, 1 at edge1, smooth between. */
    fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        if (x <= edge0) return 0.0
        if (x >= edge1) return 1.0
        val t = (x - edge0) / (edge1 - edge0)
        return t * t * (3.0 - 2.0 * t)
    }
}

/**
 * The full-pixel stage: saturation (luma pull), temperature (warm/cool)
 * and tint (magenta/green). Zero parameters are skipped for exact identity.
 */
object PixelOps {

    /**
     * Saturation in [-100, 100]: 0 = untouched; +100 doubles the distance
     * from luma; -100 collapses to luma. Integer math throughout:
     * v' = lum + (v - lum) * (100 + sat) / 100.
     */
    fun applySaturation(pixels: IntArray, saturation: Int) {
        require(saturation in -100..100)
        if (saturation == 0) return
        val factorPct = 100 + saturation
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            val a = (p ushr 24) and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val nr = lum + ((r - lum) * factorPct) / 100
            val ng = lum + ((g - lum) * factorPct) / 100
            val nb = lum + ((b - lum) * factorPct) / 100
            pixels[i] = (a shl 24) or (nr.coerceIn(0, 255) shl 16) or (ng.coerceIn(0, 255) shl 8) or nb.coerceIn(0, 255)
        }
    }

    /**
     * Temperature in [-100, 100] (positive = WARMER: red up, blue down) and
     * tint in [-100, 100] (positive = MAGENTA: green down; negative =
     * greener). Max shift at the extremes: 255/4 ~ 64 per channel.
     */
    fun applyTemperatureTint(pixels: IntArray, temperature: Int, tint: Int) {
        require(temperature in -100..100 && tint in -100..100)
        if (temperature == 0 && tint == 0) return
        val tShift = (temperature * 255) / 400 // +/- 63
        val tintShift = (tint * 255) / 400
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = ((p ushr 16) and 0xFF) + tShift
            val g = ((p ushr 8) and 0xFF) - tintShift
            val b = (p and 0xFF) - tShift
            pixels[i] = (
                (a shl 24) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or
                    b.coerceIn(0, 255)
                )
        }
    }
}
