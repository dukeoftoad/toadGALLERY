package io.github.dukeoftoad.gallery.edit.video

/**
 * The nine ADR-0012 YES rows as one pure parameter struct (r42, decision 6):
 * brightness, contrast, exposure, highlights, shadows, saturation,
 * temperature, tint, vignette --- every range [-100..100], 0 = neutral, the
 * Phase 6 semantics VERBATIM. The parameter SET is reused, NOT the photo
 * pipeline implementation (ADR-0012 section 3): this module holds the
 * projection onto GL uniforms + the validation, and BOTH realizations
 * (the ExoPlayer preview and the Transformer export effect) consume the
 * same [VideoAdjustProjection.uniforms] list against the same
 * [VideoAdjustProjection.FRAGMENT_SHADER_LINES] source --- one model, two
 * realizations, no drift.
 *
 * HSL per-channel, sharpen, LUT looks, mosaic/blur regions, geometry and
 * every AI feature are OFF-video in v1 --- see [VideoAdjustProjection.OFF_VIDEO].
 */
data class VideoAdjustParams(
    val brightness: Int = 0,
    val contrast: Int = 0,
    val exposure: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val saturation: Int = 0,
    val temperature: Int = 0,
    val tint: Int = 0,
    val vignette: Int = 0,
) {
    /** True when every parameter is 0 --- the shader is an exact identity. */
    val isNeutral: Boolean
        get() = brightness == 0 && contrast == 0 && exposure == 0 && highlights == 0 &&
            shadows == 0 && saturation == 0 && temperature == 0 && tint == 0 && vignette == 0
}

/** One named float uniform the shader consumes (normalized units). */
data class VideoAdjustUniform(val name: String, val value: Float)

/**
 * The projection: the Phase 6 per-parameter semantics mapped onto the
 * shader's normalized [0,1] colour space, plus the table conformance record.
 */
object VideoAdjustProjection {

    /** The shared parameter range: [-100, 100], 0 = neutral (Phase 6). */
    const val MIN_VALUE: Int = -100
    const val MAX_VALUE: Int = 100

    /**
     * The NINE ADR-0012 YES rows, in the fixed uniform order the shader
     * declares. A table-conformance test pins exactly this set.
     */
    val ADJUSTABLE: List<String> = listOf(
        "brightness", "contrast", "exposure", "highlights", "shadows",
        "saturation", "temperature", "tint", "vignette",
    )

    /**
     * The OFF-video rows with their ADR-0012 citations --- the honest
     * boundary, pinned by the same conformance test so nothing creeps in.
     */
    val OFF_VIDEO: Map<String, String> = linkedMapOf(
        "hsl-per-channel" to "off-video (ADR-0012 section 2, explicit)",
        "sharpen" to "off-video (absent from the ADR-0012 adjustment set)",
        "lut-looks" to "off-video (absent from the ADR-0012 adjustment set)",
        "mosaic-blur-regions" to "off-video (absent from the ADR-0012 adjustment set)",
        "geometry" to "off-video (not in the task text; a recorded follow-up)",
        "ai-features" to "off-video (ADR-0012 section 2, explicit)",
    )

    /** Total validation: null when valid, the honest reason otherwise. */
    fun validate(params: VideoAdjustParams): String? {
        for (name in ADJUSTABLE) {
            val value = valueOf(params, name)
            if (value < MIN_VALUE || value > MAX_VALUE) {
                return "video adjustment '$name' must be in [$MIN_VALUE..$MAX_VALUE], was $value"
            }
        }
        return null
    }

    /**
     * The uniform mapping over the Phase 6 semantics, each normalized to the
     * shader's [0,1] space:
     *
     *  - exposure e: gain = 2^(e/100) (the EV rule);
     *  - brightness b: offset = 2.55 * b / 255;
     *  - contrast c: the r29 mid-grey-pivot factor (pivot 128/255);
     *  - highlights h / shadows s: the half-scale 127.5-shifts masked by
     *    smoothstep over the bright/dark half (normalized by 255);
     *  - saturation: the luma-pull factor (100 + sat) / 100;
     *  - temperature / tint: the +/- 63/255 channel shifts (255/400 scaled);
     *  - vignette: k = strength / 100 (the shader applies
     *    1 - 0.8 * k * smoothstep(0.35, 1.0, radius)).
     *
     * Every neutral value is an exact identity in the shader's arithmetic
     * (gain 1.0, offsets 0.0, factors 1.0), so a neutral parameter set is
     * an exact no-op without any stage skipping.
     */
    fun uniforms(params: VideoAdjustParams): List<VideoAdjustUniform> = listOf(
        VideoAdjustUniform("uExposureGain", Math.pow(2.0, params.exposure / 100.0).toFloat()),
        VideoAdjustUniform("uBrightnessOffset", (params.brightness * 2.55f / 255f)),
        VideoAdjustUniform(
            "uContrastFactor",
            contrastFactor(params.contrast).toFloat(),
        ),
        VideoAdjustUniform("uHighlightsShift", (params.highlights * 127.5f / 100f / 255f)),
        VideoAdjustUniform("uShadowsShift", (params.shadows * 127.5f / 100f / 255f)),
        VideoAdjustUniform("uSaturationFactor", ((100 + params.saturation) / 100f)),
        VideoAdjustUniform("uTemperatureShift", (params.temperature / 400f)),
        VideoAdjustUniform("uTintShift", (params.tint / 400f)),
        VideoAdjustUniform("uVignetteStrength", (params.vignette / 100f)),
    )

    /** The r29 contrast factor (identical formula to LutBuilder, normalized pivot). */
    internal fun contrastFactor(contrast: Int): Double {
        val contrastPercent = contrast * 2.55
        return (259.0 * (contrastPercent + 255.0)) / (255.0 * (259.0 - contrastPercent))
    }

    private fun valueOf(params: VideoAdjustParams, name: String): Int = when (name) {
        "brightness" -> params.brightness
        "contrast" -> params.contrast
        "exposure" -> params.exposure
        "highlights" -> params.highlights
        "shadows" -> params.shadows
        "saturation" -> params.saturation
        "temperature" -> params.temperature
        "tint" -> params.tint
        "vignette" -> params.vignette
        else -> throw IllegalArgumentException("not an adjustable row: $name")
    }

    /**
     * The single fragment shader both realizations compile (GLSL ES 2.0 ---
     * the media3 effect pipeline's own shader level). One shader, one
     * uniform set: preview and export agree by construction. The lines are
     * a plain list (the house single-line-string rule) joined at use time.
     */
    val FRAGMENT_SHADER_LINES: List<String> = listOf(
        "precision highp float;",
        "uniform sampler2D uTexSampler;",
        "uniform float uExposureGain;",
        "uniform float uBrightnessOffset;",
        "uniform float uContrastFactor;",
        "uniform float uHighlightsShift;",
        "uniform float uShadowsShift;",
        "uniform float uSaturationFactor;",
        "uniform float uTemperatureShift;",
        "uniform float uTintShift;",
        "uniform float uVignetteStrength;",
        "varying vec2 vTexCoord;",
        "const float PIVOT = 128.0 / 255.0;",
        "float smoothstep01(float edge0, float edge1, float x) {",
        "  float t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);",
        "  return t * t * (3.0 - 2.0 * t);",
        "}",
        "void main() {",
        "  vec3 c = texture2D(uTexSampler, vTexCoord).rgb;",
        // exposure -> brightness -> contrast, the LutBuilder order.
        "  c = c * uExposureGain + vec3(uBrightnessOffset);",
        "  c = (c - vec3(PIVOT)) * uContrastFactor + vec3(PIVOT);",
        // highlights/shadows: the per-channel masked half-scale shifts
        // (the LutBuilder masks ride the CHANNEL value, not the luma).
        "  vec3 pre = clamp(c, 0.0, 1.0);",
        "  vec3 hlMask = vec3(0.0);",
        "  hlMask.r = smoothstep01(PIVOT, 1.0, pre.r);",
        "  hlMask.g = smoothstep01(PIVOT, 1.0, pre.g);",
        "  hlMask.b = smoothstep01(PIVOT, 1.0, pre.b);",
        "  vec3 shMask = vec3(0.0);",
        "  shMask.r = 1.0 - smoothstep01(0.0, PIVOT, pre.r);",
        "  shMask.g = 1.0 - smoothstep01(0.0, PIVOT, pre.g);",
        "  shMask.b = 1.0 - smoothstep01(0.0, PIVOT, pre.b);",
        "  c = c + uHighlightsShift * hlMask + uShadowsShift * shMask;",
        // saturation: the luma pull (the PixelOps weights).
        "  float lum = dot(clamp(c, 0.0, 1.0), vec3(0.299, 0.587, 0.114));",
        "  c = vec3(lum) + (c - vec3(lum)) * uSaturationFactor;",
        // temperature (r up, b down) + tint (green down = magenta up) ---
        // the PixelOps channel shifts verbatim.
        "  c.r = c.r + uTemperatureShift;",
        "  c.b = c.b - uTemperatureShift;",
        "  c.g = c.g - uTintShift;",
        // vignette: the radial smoothstep mask, 35% radius out to the corner.
        "  vec2 centre = vec2(0.5, 0.5);",
        "  float radius = length(vTexCoord - centre) / length(centre);",
        "  float mask = smoothstep01(0.35, 1.0, radius);",
        "  c = c * (1.0 - 0.8 * uVignetteStrength * mask);",
        "  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);",
        "}",
    )

    /** The joined fragment-shader source (the GL programs compile this). */
    fun fragmentShaderSource(): String = FRAGMENT_SHADER_LINES.joinToString("\n")

    /**
     * The vertex shader (GLSL ES 2.0): the media3 effect pipeline's
     * normalized-coordinate quad --- four vertices, texture coordinates
     * mapped from NDC. No transformation matrices (no geometry on video).
     */
    val VERTEX_SHADER_LINES: List<String> = listOf(
        "attribute vec4 aFramePosition;",
        "varying vec2 vTexCoord;",
        "void main() {",
        "  gl_Position = aFramePosition;",
        "  vTexCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);",
        "}",
    )

    /** The joined vertex-shader source. */
    fun vertexShaderSource(): String = VERTEX_SHADER_LINES.joinToString("\n")

    /**
     * The uniform names in the order [uniforms] emits them --- the
     * conformance tests assert the shader declares exactly these.
     */
    val UNIFORM_NAMES: List<String> =
        listOf("uTexSampler") + uniforms(VideoAdjustParams()).map { it.name }
}
