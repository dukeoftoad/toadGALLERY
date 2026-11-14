package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0012 projection pins: the nine adjustable rows, ranges, uniform mapping and shader conformance. */
class VideoAdjustProjectionTest {

    // ---- the table conformance (ADR-0012 section 2/3) ----

    @Test
    fun `the adjustable set is exactly the nine ADR-0012 YES rows`() {
        assertEquals(
            listOf(
                "brightness", "contrast", "exposure", "highlights", "shadows",
                "saturation", "temperature", "tint", "vignette",
            ),
            VideoAdjustProjection.ADJUSTABLE,
        )
        assertEquals(9, VideoAdjustProjection.ADJUSTABLE.size)
        assertEquals(9, VideoAdjustProjection.ADJUSTABLE.toSet().size)
    }

    @Test
    fun `the off-video rows are pinned with their ADR-0012 citations`() {
        val off = VideoAdjustProjection.OFF_VIDEO
        assertEquals(
            setOf("hsl-per-channel", "sharpen", "lut-looks", "mosaic-blur-regions", "geometry", "ai-features"),
            off.keys,
        )
        assertTrue(off.getValue("hsl-per-channel").contains("ADR-0012 section 2"))
        assertTrue(off.getValue("ai-features").contains("ADR-0012 section 2"))
        assertTrue(off.getValue("sharpen").contains("absent from the ADR-0012"))
        assertTrue(off.getValue("lut-looks").contains("absent from the ADR-0012"))
        assertTrue(off.getValue("mosaic-blur-regions").contains("absent from the ADR-0012"))
        assertTrue(off.getValue("geometry").contains("follow-up"))
    }

    @Test
    fun `no YES row is also an off-video row (the boundary is disjoint)`() {
        val yes = VideoAdjustProjection.ADJUSTABLE.toSet()
        val off = VideoAdjustProjection.OFF_VIDEO.keys
        assertTrue(yes.intersect(off).isEmpty())
    }

    // ---- the ranges ----

    @Test
    fun `every parameter accepts the full range and refuses beyond it`() {
        assertTrue(VideoAdjustProjection.validate(VideoAdjustParams()) == null)
        for (value in listOf(-100, -1, 0, 1, 100)) {
            assertNull(
                "value $value must be in range",
                VideoAdjustProjection.validate(
                    VideoAdjustParams(
                        brightness = value,
                        contrast = value,
                        exposure = value,
                        highlights = value,
                        shadows = value,
                        saturation = value,
                        temperature = value,
                        tint = value,
                        vignette = value,
                    ),
                ),
            )
        }
        for (value in listOf(-101, 101, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val reason = VideoAdjustProjection.validate(
                VideoAdjustParams(brightness = value),
            )
            assertTrue("value $value must be refused", reason != null)
            assertTrue(reason!!.contains("brightness"))
        }
    }

    @Test
    fun `the neutral state is exactly all-zero`() {
        assertTrue(VideoAdjustParams().isNeutral)
        assertFalse(VideoAdjustParams(brightness = 1).isNeutral)
        assertFalse(VideoAdjustParams(vignette = -1).isNeutral)
        // every single-parameter lift breaks neutrality
        VideoAdjustParams(
            contrast = 1, exposure = 1, highlights = 1, shadows = 1,
            saturation = 1, temperature = 1, tint = 1,
        ).let { params ->
            for (name in VideoAdjustProjection.ADJUSTABLE) {
                when (name) {
                    "brightness", "vignette" -> Unit // covered by the two asserts above
                    else -> {
                        val lifted = when (name) {
                            "contrast" -> params.copy(contrast = 0)
                            "exposure" -> params.copy(exposure = 0)
                            "highlights" -> params.copy(highlights = 0)
                            "shadows" -> params.copy(shadows = 0)
                            "saturation" -> params.copy(saturation = 0)
                            "temperature" -> params.copy(temperature = 0)
                            "tint" -> params.copy(tint = 0)
                            else -> params
                        }
                        assertTrue("the $name lift must break neutrality", !lifted.isNeutral)
                    }
                }
            }
        }
    }

    // ---- the uniform mapping semantics ----

    private fun uniformValue(params: VideoAdjustParams, name: String): Float =
        VideoAdjustProjection.uniforms(params).first { it.name == name }.value

    @Test
    fun `neutral uniforms are exact identities`() {
        val uniforms = VideoAdjustProjection.uniforms(VideoAdjustParams())
        assertEquals(
            listOf(
                "uExposureGain" to 1.0f,
                "uBrightnessOffset" to 0.0f,
                "uContrastFactor" to 1.0f,
                "uHighlightsShift" to 0.0f,
                "uShadowsShift" to 0.0f,
                "uSaturationFactor" to 1.0f,
                "uTemperatureShift" to 0.0f,
                "uTintShift" to 0.0f,
                "uVignetteStrength" to 0.0f,
            ),
            uniforms.map { it.name to it.value },
        )
    }

    @Test
    fun `exposure maps to the EV gain rule`() {
        assertEquals(2.0f, uniformValue(VideoAdjustParams(exposure = 100), "uExposureGain"), 1e-6f)
        assertEquals(0.5f, uniformValue(VideoAdjustParams(exposure = -100), "uExposureGain"), 1e-6f)
        assertEquals(1.0f, uniformValue(VideoAdjustParams(exposure = 0), "uExposureGain"), 1e-9f)
    }

    @Test
    fun `brightness maps to the 2x55-per-100 offset`() {
        // +100 -> 255/255 = 1.0 (the full rule, normalized)
        assertEquals(1.0f, uniformValue(VideoAdjustParams(brightness = 100), "uBrightnessOffset"), 1e-6f)
        assertEquals(-1.0f, uniformValue(VideoAdjustParams(brightness = -100), "uBrightnessOffset"), 1e-6f)
    }

    @Test
    fun `contrast maps to the mid-grey-pivot factor`() {
        // formula at c=100: 2.55*100=255 percent
        val expected100 = (259.0 * (255.0 + 255.0)) / (255.0 * (259.0 - 255.0))
        assertEquals(
            expected100.toFloat(),
            uniformValue(VideoAdjustParams(contrast = 100), "uContrastFactor"),
            1e-3f,
        )
        assertEquals(1.0f, uniformValue(VideoAdjustParams(contrast = 0), "uContrastFactor"), 1e-9f)
    }

    @Test
    fun `highlights and shadows map to the half-scale shifts`() {
        // +100 -> 127.5/255 = 0.5 exactly
        assertEquals(0.5f, uniformValue(VideoAdjustParams(highlights = 100), "uHighlightsShift"), 1e-6f)
        assertEquals(0.5f, uniformValue(VideoAdjustParams(shadows = 100), "uShadowsShift"), 1e-6f)
        assertEquals(-0.5f, uniformValue(VideoAdjustParams(shadows = -100), "uShadowsShift"), 1e-6f)
    }

    @Test
    fun `saturation maps to the luma-pull factor`() {
        assertEquals(2.0f, uniformValue(VideoAdjustParams(saturation = 100), "uSaturationFactor"), 1e-6f)
        assertEquals(0.0f, uniformValue(VideoAdjustParams(saturation = -100), "uSaturationFactor"), 1e-6f)
    }

    @Test
    fun `temperature and tint map to the 255-over-400 shifts`() {
        assertEquals(0.25f, uniformValue(VideoAdjustParams(temperature = 100), "uTemperatureShift"), 1e-6f)
        assertEquals(-0.25f, uniformValue(VideoAdjustParams(temperature = -100), "uTemperatureShift"), 1e-6f)
        assertEquals(0.25f, uniformValue(VideoAdjustParams(tint = 100), "uTintShift"), 1e-6f)
        // the PixelOps integer shift at +100 is exactly 63/255
        assertEquals(63f / 255f, uniformValue(VideoAdjustParams(tint = 100), "uTintShift"), 1e-2f)
    }

    @Test
    fun `vignette maps to the k coefficient`() {
        assertEquals(1.0f, uniformValue(VideoAdjustParams(vignette = 100), "uVignetteStrength"), 1e-6f)
        assertEquals(-1.0f, uniformValue(VideoAdjustParams(vignette = -100), "uVignetteStrength"), 1e-6f)
    }

    // ---- the shader conformance: one shader, one uniform set ----

    @Test
    fun `the fragment shader declares exactly the mapped uniforms`() {
        val source = VideoAdjustProjection.fragmentShaderSource()
        for (name in VideoAdjustProjection.UNIFORM_NAMES) {
            assertEquals(
                "uniform '$name' must be declared exactly once",
                1,
                Regex("uniform [a-zA-Z0-9]+ $name;").findAll(source).count(),
            )
        }
        // no stray float uniforms beyond the mapped set
        val declared = Regex("uniform float ([a-zA-Z0-9]+);")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            VideoAdjustProjection.uniforms(VideoAdjustParams()).map { it.name }.toSet(),
            declared.toSet(),
        )
    }

    @Test
    fun `the shader is assembled from single-line strings (the house rule)`() {
        for (line in VideoAdjustProjection.FRAGMENT_SHADER_LINES) {
            assertFalse("shader lines must be single-line", line.contains("\n"))
        }
        for (line in VideoAdjustProjection.VERTEX_SHADER_LINES) {
            assertFalse("shader lines must be single-line", line.contains("\n"))
        }
    }

    @Test
    fun `the vertex shader feeds the texture coordinate the fragment shader samples`() {
        val vertex = VideoAdjustProjection.vertexShaderSource()
        val fragment = VideoAdjustProjection.fragmentShaderSource()
        assertTrue(vertex.contains("varying vec2 vTexCoord;"))
        assertTrue(fragment.contains("varying vec2 vTexCoord;"))
        assertTrue(vertex.contains("attribute vec4 aFramePosition;"))
        assertTrue(fragment.contains("uTexSampler"))
    }
}
