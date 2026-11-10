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

    private const val SLIDER_MIN = -100
    private const val SLIDER_MAX = 100
    private const val SLIDER_SPAN_D = 100.0
    private const val BYTE_MAX = 255
    private const val BYTE_MAX_D = 255.0
    private const val PIVOT_D = 128.0
    private const val HALF_D = 127.5
    private const val PER_STEP = 2.55

    /**
     * Builds the composed 256-entry LUT.
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
        require(brightness in SLIDER_MIN..SLIDER_MAX) { "brightness=$brightness out of [-100, 100]" }
        require(contrast in SLIDER_MIN..SLIDER_MAX) { "contrast=$contrast out of [-100, 100]" }
        require(exposure in SLIDER_MIN..SLIDER_MAX) { "exposure=$exposure out of [-100, 100]" }
        require(highlights in SLIDER_MIN..SLIDER_MAX) { "highlights=$highlights out of [-100, 100]" }
        require(shadows in SLIDER_MIN..SLIDER_MAX) { "shadows=$shadows out of [-100, 100]" }

        // Loop-invariant coefficients (computed once, not per-entry).
        val gain = 2.0.pow(exposure / SLIDER_SPAN_D)
        val brightnessOffset = brightness * PER_STEP
        val contrastPercent = contrast * PER_STEP
        val contrastFactor = (259.0 * (contrastPercent + BYTE_MAX_D)) /
            (BYTE_MAX_D * (259.0 - contrastPercent))
        // 127.5/100 is numerically EXACT (127.5 = 255/2 is representable)
        // where s * 2.55 * 0.5 carries binary float dust (2.55 is not
        // representable; 100 * 2.55 * 0.5 = 127.49999... rounds to 127,
        // one off the documented +127.5 half-scale). Same scaling, exact
        // at the slider extremes.
        val hlScale = highlights * HALF_D / SLIDER_SPAN_D
        val shScale = shadows * HALF_D / SLIDER_SPAN_D

        return IntArray(256) { v ->
            var out = v * gain + brightnessOffset
            out = (out - PIVOT_D) * contrastFactor + PIVOT_D
            out += hlScale * smoothstep(PIVOT_D, BYTE_MAX_D, out)
            out += shScale * (1.0 - smoothstep(0.0, PIVOT_D, out))
            out.roundToInt().coerceIn(0, BYTE_MAX)
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
 *
 * Also hosts [applyLut] --- the per-channel stage that [LutBuilder] produces.
 *
 * Pixel layout is the packed ARGB format used throughout the editor:
 * `0xAARRGGBB`.
 */
object PixelOps {

    private const val SLIDER_MIN = -100
    private const val SLIDER_MAX = 100
    private const val CHANNEL_MAX = 255
    private const val CHANNEL_MASK = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    /** `0xFF000000` in two's complement 32-bit --- mask that keeps the alpha byte. */
    private const val ALPHA_MASK = -0x1000000

    private const val LUMA_R = 299
    private const val LUMA_G = 587
    private const val LUMA_B = 114
    private const val LUMA_DIV = 1000

    private const val TEMP_SCALE = 255
    private const val TEMP_DIV = 400

    /**
     * Saturation in [-100, 100]: 0 = untouched; +100 doubles the distance
     * from luma; -100 collapses to luma. Integer math throughout:
     * v' = lum + (v - lum) * (100 + sat) / 100.
     */
    fun applySaturation(pixels: IntArray, saturation: Int) {
        require(saturation in SLIDER_MIN..SLIDER_MAX) { "saturation=$saturation out of [-100, 100]" }
        if (saturation == 0) return
        val factorPct = 100 + saturation
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr ALPHA_SHIFT) and CHANNEL_MASK
            val r = (p ushr RED_SHIFT) and CHANNEL_MASK
            val g = (p ushr GREEN_SHIFT) and CHANNEL_MASK
            val b = p and CHANNEL_MASK
            val lum = (r * LUMA_R + g * LUMA_G + b * LUMA_B) / LUMA_DIV
            val nr = (lum + ((r - lum) * factorPct) / 100).coerceIn(0, CHANNEL_MAX)
            val ng = (lum + ((g - lum) * factorPct) / 100).coerceIn(0, CHANNEL_MAX)
            val nb = (lum + ((b - lum) * factorPct) / 100).coerceIn(0, CHANNEL_MAX)
            pixels[i] = (a shl ALPHA_SHIFT) or (nr shl RED_SHIFT) or (ng shl GREEN_SHIFT) or nb
        }
    }

    /**
     * Temperature in [-100, 100] (positive = WARMER: red up, blue down) and
     * tint in [-100, 100] (positive = MAGENTA: green down; negative =
     * greener). Max shift at the extremes: 255/4 ~ 64 per channel.
     */
    fun applyTemperatureTint(pixels: IntArray, temperature: Int, tint: Int) {
        require(temperature in SLIDER_MIN..SLIDER_MAX) { "temperature=$temperature out of [-100, 100]" }
        require(tint in SLIDER_MIN..SLIDER_MAX) { "tint=$tint out of [-100, 100]" }
        if (temperature == 0 && tint == 0) return
        val tShift = (temperature * TEMP_SCALE) / TEMP_DIV // +/- 63
        val tintShift = (tint * TEMP_SCALE) / TEMP_DIV
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr ALPHA_SHIFT) and CHANNEL_MASK
            val r = (((p ushr RED_SHIFT) and CHANNEL_MASK) + tShift).coerceIn(0, CHANNEL_MAX)
            val g = (((p ushr GREEN_SHIFT) and CHANNEL_MASK) - tintShift).coerceIn(0, CHANNEL_MAX)
            val b = ((p and CHANNEL_MASK) - tShift).coerceIn(0, CHANNEL_MAX)
            pixels[i] = (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
    }

    /**
     * Applies a pre-built 256-entry LUT to every pixel's R, G and B channel
     * (alpha preserved). This is the per-channel stage; feed it the array
     * returned by [LutBuilder.build]. All LUT entries must already be
     * clamped to [0, 255] (as [LutBuilder.build] guarantees).
     */
    fun applyLut(pixels: IntArray, lut: IntArray) {
        require(lut.size == 256) { "LUT must have 256 entries, got ${lut.size}" }
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p and ALPHA_MASK
            val r = lut[(p ushr RED_SHIFT) and CHANNEL_MASK]
            val g = lut[(p ushr GREEN_SHIFT) and CHANNEL_MASK]
            val b = lut[p and CHANNEL_MASK]
            pixels[i] = a or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
    }

    /**
     * Full pipeline convenience: applies [applyLut], then [applySaturation],
     * then [applyTemperatureTint]. Order matches the documented stage
     * ordering; passing all-neutral defaults makes every stage a no-op,
     * so this is a guaranteed identity in that case.
     */
    fun applyAll(
        pixels: IntArray,
        lut: IntArray,
        saturation: Int = 0,
        temperature: Int = 0,
        tint: Int = 0,
    ) {
        applyLut(pixels, lut)
        applySaturation(pixels, saturation)
        applyTemperatureTint(pixels, temperature, tint)
    }
}
