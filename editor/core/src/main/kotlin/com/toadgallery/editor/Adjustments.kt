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
 *    fixed documented order. r68 appends the master tone CURVE after
 *    shadows (the six-parameter overload below; the five-parameter form
 *    is frozen bit-for-bit).
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

    /**
     * The r68 curve extension (Phase 2 of the editor rebirth): the SAME
     * five frozen stages, then the recipe's MASTER tone curve composed
     * AFTER them --- lut[v] = curve(base[v]), one table lookup per entry,
     * exactly the two-pass apply a chain of LUT stages is. A null
     * [toneCurves] (a pre-r68 recipe, a clean session) or the identity
     * curve returns the five-stage table UNTOUCHED --- the byte-identical
     * guarantee the regression test pins. r71: the per-channel curves
     * compose through [buildChannels] (the three-LUT apply path --- Phase
     * 2's declared follow-up); THIS overload stays the master-only
     * table, byte-frozen as the r68 contract.
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
     * The r71 channel composition (Phase 2's declared follow-up): the
     * same five frozen stages, then the MASTER curve and the channel's
     * own curve composed after them --- redLut[v] =
     * redCurve(masterCurve(base[v])), one table lookup per entry per
     * stage, the chain-of-LUTs composition verbatim. A channel without
     * an honest curve (an empty list, or a degenerate list
     * [LilypadCurveMath.bakeLut] bakes to the identity ramp) reads back
     * the MASTER-composed table --- the r68 apply, per channel. A state
     * with no channel curves reproduces [build]'s one-table result in
     * all three tables --- the equivalence the drift pin holds.
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
     * The r74 channel overload: the seven-stage base (whites/blacks
     * composed into the per-channel table), then the master and channel
     * curves --- the r71 composition, verbatim, over the grown base.
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
     * The r74 seven-stage build: the r30 five frozen stages, then the
     * WHITES and BLACKS stage (the plan doc's slider list, the Lightroom
     * grammar): whites lifts ONLY the far bright end (a smoothstep mask
     * from 192..255), blacks lifts ONLY the far dark end (the mirrored
     * mask over 0..64), each at half scale so even +100 cannot clip a
     * stop past the r30 shadows/highlights behaviour. Both compose into
     * the SAME per-channel LUT --- O(1) per channel at render time, the
     * stage's own shape, kept when the tone family grew.
     *
     * BYTE-FROZEN GUARANTEE: whites = 0 AND blacks = 0 reproduces the
     * five-stage table EXACTLY (both masked adds are zero terms --- the
     * regression test pins the arrays equal); every pre-r74 recipe value
     * and test stays byte-compatible, the additive-versioning rule.
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
        // The exact-half-scale rule (the highlights/shadows precedent):
        // 127.5/100 carries no float dust at the extremes.
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
     * The r74 curve-composed overload: the seven-stage table, then the
     * recipe's MASTER tone curve composed after it --- the r68 two-pass
     * rule (lut[v] = curve(base[v])), extended by the same additive
     * step that grew the five-stage form. A null or identity curve
     * returns the seven-stage table untouched (the byte-identical
     * guarantee).
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
 * The r71 three-table tone state: one composed 256-entry LUT per colour
 * channel (see [LutBuilder.buildChannels]). The pipeline applies the
 * three tables in ONE pass ([PixelOps.applyChannelLuts]) --- the
 * per-channel stage's O(1)-per-channel shape, kept when the channels
 * finally earned their own tables.
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
     * Vibrance in [-100, 100] (r74, the plan doc's slider list): the
     * luma-WEIGHTED saturation --- the saturation grammar's own rule,
     * verbatim, except the pull factor is scaled per pixel by how
     * UNSATURATED the pixel already is (`1 - sat/255` over the channel
     * distances' max), so a vibrance push lifts a flat grey jacket far
     * more than an already-vivid flower. 0 = untouched (skipped, the
     * exact-identity rule); +100 at a fully grey pixel doubles its
     * distance from luma (the saturation +100 behaviour); +100 at a
     * fully saturated pixel is a no-op (nothing left to lift --- the
     * honest bound, pinned by tests).
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
            // The saturation measure 0..255: the max channel distance
            // from luma (a grey pixel scores 0, a saturated one 255).
            val sat = maxOf(dr, dg, db)
            // The per-pixel weight: LESS boost the more saturated
            // already (1 at grey, 0 at fully saturated).
            val weight = (255 - sat)
            val factorPct = 100 + (basePct - 100) * weight / 255
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
     * The r71 three-table apply (the per-channel stage's own shape, kept
     * when the channels earned separate tables): red through [ChannelToneLuts.red],
     * green through .green, blue through .blue, alpha preserved, ONE pass
     * over the pixels. Feeding three copies of one table reproduces
     * [applyLut] with it, byte-for-byte --- the equivalence pin.
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
