package com.toadgallery.editor

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The adjustment suite's first two stages.
 *
 *  - [LutBuilder]: the PER-CHANNEL stage --- every parameter is a pure function of
 *    one channel value, composed into a single 256-entry LUT applied once per pixel:
 *    exposure -> brightness -> contrast -> highlights -> shadows, then the master
 *    tone curve (the five-parameter form is frozen bit-for-bit).
 *  - [PixelOps]: the FULL-PIXEL stage (saturation needs luma; temperature/tint
 *    cross channels); stages with a zero parameter are SKIPPED so identity is exact.
 *
 * All ranges are [-100, 100] with 0 = neutral.
 */
object LutBuilder {

    /** The stage's composition order, exported for tests and the recipe docs. */
    const val ORDER: String = "exposure -> brightness -> contrast -> highlights -> shadows -> curve"

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
     *  - exposure e: EV = e/100 stops, v' = v * 2^EV (0 = identity).
     *  - brightness b: v' = v + 2.55*b.
     *  - contrast c: mid-grey-pivot factor.
     *  - highlights h: v' = v + h * 2.55 * 0.5 * S(128, 255, v) --- only the bright
     *    half moves (S = smoothstep), half-scaled so +100 cannot clip pure white.
     *  - shadows s: v' = v + s * 2.55 * 0.5 * (1 - S(0, 128, v)) --- only the dark half.
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
        // 127.5/100 is numerically exact; s * 2.55 * 0.5 carries float dust
        // (100 * 2.55 * 0.5 rounds to 127, one off the +127.5 half-scale).
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

    /**
     * The five frozen stages, then the recipe's MASTER tone curve after them ---
     * lut[v] = curve(base[v]). A null [toneCurves] or identity curve returns the
     * five-stage table untouched; per-channel curves compose via [buildChannels].
     */
    fun build(
        brightness: Int,
        contrast: Int,
        exposure: Int,
        highlights: Int,
        shadows: Int,
        toneCurves: ToneCurves?,
    ): IntArray {
        val base = build(brightness, contrast, exposure, highlights, shadows)
        if (toneCurves == null || toneCurves.isIdentity) return base
        val curve = LilypadCurveMath.bakeLut(toneCurves.master)
        return IntArray(base.size) { v -> curve[base[v]] }
    }

    /**
     * The five frozen stages, then MASTER curve and the channel's own curve ---
     * redLut[v] = redCurve(masterCurve(base[v])). A channel without a usable curve
     * (empty list, or degenerate list baked to identity) reads back the
     * MASTER-composed table; no channel curves reproduces [build]'s one-table result.
     */
    fun buildChannels(
        brightness: Int,
        contrast: Int,
        exposure: Int,
        highlights: Int,
        shadows: Int,
        toneCurves: ToneCurves,
    ): ChannelToneLuts = buildChannels(
        brightness, contrast, exposure, highlights, shadows, 0, 0, toneCurves,
    )

    /**
     * The seven-stage base (whites/blacks composed in), then the master and
     * channel curves --- the same composition as the six-parameter overload.
     */
    fun buildChannels(
        brightness: Int,
        contrast: Int,
        exposure: Int,
        highlights: Int,
        shadows: Int,
        whites: Int,
        blacks: Int,
        toneCurves: ToneCurves,
    ): ChannelToneLuts {
        val base = build(brightness, contrast, exposure, highlights, shadows, whites, blacks)
        val master = LilypadCurveMath.bakeLut(toneCurves.master)
        val mastered = if (toneCurves.master == LilypadCurveMath.IDENTITY) {
            base
        } else {
            IntArray(base.size) { v -> master[base[v]] }
        }
        fun withChannel(points: List<CurvePoint>): IntArray {
            if (points.size < LilypadCurveMath.MIN_POINTS) return mastered
            val channel = LilypadCurveMath.bakeLut(points)
            return IntArray(mastered.size) { v -> channel[mastered[v]] }
        }
        return ChannelToneLuts(
            red = withChannel(toneCurves.red),
            green = withChannel(toneCurves.green),
            blue = withChannel(toneCurves.blue),
        )
    }

    /**
     * The five frozen stages, then WHITES and BLACKS: whites lifts only the far
     * bright end (smoothstep mask 192..255), blacks only the far dark end (mirror
     * mask 0..64), each at half scale so +100 cannot clip a stop. whites = 0 AND
     * blacks = 0 reproduces the five-stage table EXACTLY (byte-compatibility rule).
     */
    fun build(
        brightness: Int,
        contrast: Int,
        exposure: Int,
        highlights: Int,
        shadows: Int,
        whites: Int,
        blacks: Int,
    ): IntArray {
        val base = build(brightness, contrast, exposure, highlights, shadows)
        if (whites == 0 && blacks == 0) return base
        // Exact-half-scale rule: 127.5/100 carries no float dust at the extremes.
        val wScale = whites * HALF_D / SLIDER_SPAN_D
        val bScale = blacks * HALF_D / SLIDER_SPAN_D
        return IntArray(base.size) { v ->
            var out = base[v].toDouble()
            // Whites: the far bright end, smoothstep 192 -> 255.
            out += wScale * smoothstep(WHITES_FLOOR_D, BYTE_MAX_D, out)
            // Blacks: the far dark end, the mirrored mask over 0 -> 64.
            out += bScale * (1.0 - smoothstep(0.0, BLACKS_CEILING_D, out))
            out.roundToInt().coerceIn(0, BYTE_MAX)
        }
    }

    /** The whites mask's floor: 192 (the far bright quarter). */
    private const val WHITES_FLOOR_D = 192.0

    /** The blacks mask's ceiling: 64 (the far dark quarter). */
    private const val BLACKS_CEILING_D = 64.0

    /**
     * The seven-stage table, then the recipe's MASTER tone curve after it
     * (lut[v] = curve(base[v])). A null or identity curve returns it untouched.
     */
    fun build(
        brightness: Int,
        contrast: Int,
        exposure: Int,
        highlights: Int,
        shadows: Int,
        whites: Int,
        blacks: Int,
        toneCurves: ToneCurves?,
    ): IntArray {
        val base = build(brightness, contrast, exposure, highlights, shadows, whites, blacks)
        if (toneCurves == null || toneCurves.isIdentity) return base
        val curve = LilypadCurveMath.bakeLut(toneCurves.master)
        return IntArray(base.size) { v -> curve[base[v]] }
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
 * One composed 256-entry LUT per colour channel (see [LutBuilder.buildChannels]);
 * the pipeline applies all three in ONE pass ([PixelOps.applyChannelLuts]).
 */
class ChannelToneLuts(
    /** The red channel's composed table (five sliders + master + red curve). */
    val red: IntArray,
    /** The green channel's composed table. */
    val green: IntArray,
    /** The blue channel's composed table. */
    val blue: IntArray,
) {
    init {
        require(red.size == 256 && green.size == 256 && blue.size == 256) {
            "channel LUTs must have 256 entries each, got ${red.size}/${green.size}/${blue.size}"
        }
    }
}

/**
 * The full-pixel stage: saturation (luma pull), temperature (warm/cool), tint
 * (magenta/green); zero parameters are skipped for exact identity. Also hosts
 * [applyLut] (the per-channel stage [LutBuilder] produces). Pixel layout: `0xAARRGGBB`.
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
     * Vibrance in [-100, 100]: luma-weighted saturation --- the pull factor is
     * scaled per pixel by how unsaturated it already is (`1 - sat/255`, sat = max
     * channel distance from luma). 0 = untouched (skipped); +100 at a fully grey
     * pixel doubles its luma distance; +100 at a fully saturated pixel is a no-op.
     */
    fun applyVibrance(pixels: IntArray, vibrance: Int) {
        require(vibrance in SLIDER_MIN..SLIDER_MAX) { "vibrance=$vibrance out of [-100, 100]" }
        if (vibrance == 0) return
        val basePct = 100 + vibrance
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr ALPHA_SHIFT) and CHANNEL_MASK
            val r = (p ushr RED_SHIFT) and CHANNEL_MASK
            val g = (p ushr GREEN_SHIFT) and CHANNEL_MASK
            val b = p and CHANNEL_MASK
            val lum = (r * LUMA_R + g * LUMA_G + b * LUMA_B) / LUMA_DIV
            val dr = kotlin.math.abs(r - lum)
            val dg = kotlin.math.abs(g - lum)
            val db = kotlin.math.abs(b - lum)
            // Saturation measure 0..255: max channel distance from luma (grey = 0, vivid = 255).
            val sat = maxOf(dr, dg, db)
            // Per-pixel weight: less boost the more saturated already (1 at grey, 0 at full).
            val weight = (255 - sat)
            val factorPct = 100 + (basePct - 100) * weight / 255
            val nr = (lum + ((r - lum) * factorPct) / 100).coerceIn(0, CHANNEL_MAX)
            val ng = (lum + ((g - lum) * factorPct) / 100).coerceIn(0, CHANNEL_MAX)
            val nb = (lum + ((b - lum) * factorPct) / 100).coerceIn(0, CHANNEL_MAX)
            pixels[i] = (a shl ALPHA_SHIFT) or (nr shl RED_SHIFT) or (ng shl GREEN_SHIFT) or nb
        }
    }

    /**
     * Temperature in [-100, 100] (positive = warmer: red up, blue down); tint
     * (positive = magenta, negative = greener). Max shift at extremes: ~64/channel.
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
     * Applies a pre-built 256-entry LUT to R, G, B (alpha preserved); feed it the
     * [LutBuilder.build] result. All LUT entries must be clamped to [0, 255].
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
     * Three-table apply: red through [ChannelToneLuts.red], green through .green,
     * blue through .blue (alpha preserved, ONE pass). Feeding three copies of one
     * table reproduces [applyLut] byte-for-byte.
     */
    fun applyChannelLuts(pixels: IntArray, luts: ChannelToneLuts) {
        val rLut = luts.red
        val gLut = luts.green
        val bLut = luts.blue
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p and ALPHA_MASK
            val r = rLut[(p ushr RED_SHIFT) and CHANNEL_MASK]
            val g = gLut[(p ushr GREEN_SHIFT) and CHANNEL_MASK]
            val b = bLut[p and CHANNEL_MASK]
            pixels[i] = a or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
        }
    }

    /**
     * Convenience: [applyLut], then [applySaturation], then [applyTemperatureTint];
     * all-neutral defaults make every stage a no-op (guaranteed identity).
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
