package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The colour-grade + detail stage: per-channel HSL, vignette, unsharp mask. */
class HslVignetteSharpTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun redOf(p: Int) = (p ushr 16) and 0xFF
    private fun greenOf(p: Int) = (p ushr 8) and 0xFF
    private fun blueOf(p: Int) = p and 0xFF
    private fun alphaOf(p: Int) = (p ushr 24) and 0xFF

    // ---- RgbHsl round-trips ----

    @Test
    fun `primary colours round-trip through HSL`() {
        for ((r, g, b) in listOf(
            Triple(255, 0, 0), Triple(0, 255, 0), Triple(0, 0, 255),
            Triple(255, 255, 0), Triple(0, 255, 255), Triple(255, 0, 255),
        )) {
            val (h, s, l) = RgbHsl.toHsl(r, g, b)
            val rgb = RgbHsl.fromHsl(h, s, l)
            assertEquals("r of ($r,$g,$b)", r, rgb[0])
            assertEquals("g of ($r,$g,$b)", g, rgb[1])
            assertEquals("b of ($r,$g,$b)", b, rgb[2])
        }
    }

    @Test
    fun `grey pixels are achromatic with zero saturation`() {
        val (h, s, l) = RgbHsl.toHsl(128, 128, 128)
        assertEquals(0.0, s, 1e-9)
        assertEquals(128.0 / 255.0, l, 1e-9)
        // Hue of an achromatic pixel is defined 0.
        assertEquals(0.0, h, 1e-9)
    }

    @Test
    fun `red hue is 0 and blue is 240`() {
        assertEquals(0.0, RgbHsl.toHsl(255, 0, 0).first, 1e-6)
        assertEquals(240.0, RgbHsl.toHsl(0, 0, 255).first, 1e-6)
        assertEquals(120.0, RgbHsl.toHsl(0, 255, 0).first, 1e-6)
    }

    // ---- Band weights ----

    @Test
    fun `band weight is one at the centre and zero past thirty degrees`() {
        assertEquals(1.0, HslBands.bandWeight(0.0, 0), 1e-9)
        assertEquals(0.0, HslBands.bandWeight(31.0, 0), 1e-9)
        assertEquals(0.0, HslBands.bandWeight(30.0, 0), 1e-9)
        assertTrue(HslBands.bandWeight(10.0, 0) in 0.0..1.0)
    }

    @Test
    fun `hue distance wraps the circle`() {
        assertEquals(20.0, HslBands.hueDistance(350.0, 10.0), 1e-9)
        assertEquals(180.0, HslBands.hueDistance(0.0, 180.0), 1e-9)
        assertEquals(0.0, HslBands.hueDistance(240.0, 240.0), 1e-9)
    }

    // ---- applyHslBands ----

    @Test
    fun `an all-zero band state is skipped exactly`() {
        val pixels = intArrayOf(argb(200, 10, 20, 30), argb(255, 250, 128, 5))
        val copy = pixels.copyOf()
        applyHslBands(pixels, HslBands())
        assertTrue(pixels.contentEquals(copy))
    }

    @Test
    fun `achromatic pixels never drift coloured`() {
        val grey = argb(255, 128, 128, 128)
        val pixels = intArrayOf(grey)
        applyHslBands(
            pixels,
            HslBands(red = HslBandAdjust(hue = 90, saturation = 50, luminance = 40)),
        )
        assertEquals(grey, pixels[0])
    }

    @Test
    fun `a pure red pixel is shifted by the red band's hue`() {
        // Red pixel, red band +120 hue -> lands in green territory.
        val pixels = intArrayOf(argb(255, 255, 0, 0))
        applyHslBands(pixels, HslBands(red = HslBandAdjust(hue = 120)))
        val (h, s, l) = RgbHsl.toHsl(redOf(pixels[0]), greenOf(pixels[0]), blueOf(pixels[0]))
        assertEquals(120.0, h, 30.0) // within the green band's hat
        assertEquals(1.0, s, 0.01)   // saturation preserved at full
        assertEquals(0.5, l, 0.02)   // luminance preserved at mid
    }

    @Test
    fun `the red band's saturation slider desaturates red pixels only`() {
        val pixels = intArrayOf(argb(255, 255, 0, 0), argb(255, 0, 255, 0))
        applyHslBands(pixels, HslBands(red = HslBandAdjust(saturation = -100)))
        val (h0, s0, _) = RgbHsl.toHsl(redOf(pixels[0]), greenOf(pixels[0]), blueOf(pixels[0]))
        assertEquals(0.0, s0, 0.02) // red fully desaturated
        assertEquals(255, greenOf(pixels[1])) // the green pixel is untouched by the red band
        val (_, s1, _) = RgbHsl.toHsl(redOf(pixels[1]), greenOf(pixels[1]), blueOf(pixels[1]))
        assertEquals(1.0, s1, 0.01)
    }

    @Test
    fun `alpha is preserved through the band stage`() {
        val pixels = intArrayOf(argb(77, 255, 0, 0))
        applyHslBands(pixels, HslBands(red = HslBandAdjust(hue = 45)))
        assertEquals(77, alphaOf(pixels[0]))
    }

    // ---- applyVignette ----

    @Test
    fun `zero vignette is skipped exactly`() {
        val pixels = intArrayOf(argb(255, 200, 100, 50))
        val copy = pixels.copyOf()
        applyVignette(pixels, 3, 3, 0)
        assertTrue(pixels.contentEquals(copy))
    }

    @Test
    fun `positive vignette darkens corners and leaves the centre untouched`() {
        val w = 101
        val h = 101
        val pixels = IntArray(w * h) { argb(255, 200, 200, 200) }
        applyVignette(pixels, w, h, 80)
        val center = pixels[50 * w + 50]
        assertEquals(200, redOf(center)) // inside the 35% inner radius: mask 0
        val corner = pixels[0]
        assertTrue("corner must darken", redOf(corner) < 200)
        assertTrue("alpha untouched", alphaOf(corner) == 255)
    }

    @Test
    fun `negative vignette brightens the corners`() {
        val w = 101
        val h = 101
        val pixels = IntArray(w * h) { argb(255, 100, 100, 100) }
        applyVignette(pixels, w, h, -80)
        val corner = pixels[0]
        assertTrue("negative strength must brighten", redOf(corner) > 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `vignette outside the range is rejected`() {
        applyVignette(IntArray(9), 3, 3, 101)
    }

    // ---- applySharpen ----

    @Test
    fun `zero sharpen is skipped exactly`() {
        val pixels = intArrayOf(argb(255, 10, 20, 30), argb(255, 40, 50, 60))
        val copy = pixels.copyOf()
        applySharpen(pixels, 2, 1, 0)
        assertTrue(pixels.contentEquals(copy))
    }

    @Test
    fun `a constant image is an identity at any amount`() {
        val w = 8
        val h = 8
        val pixels = IntArray(w * h) { argb(255, 90, 90, 90) }
        val copy = pixels.copyOf()
        applySharpen(pixels, w, h, 100)
        assertTrue("constant image must survive full sharpen", pixels.contentEquals(copy))
    }

    @Test
    fun `a step edge gains contrast under positive sharpen`() {
        val w = 8
        val h = 4
        // Left half dark, right half light.
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < 4) argb(255, 40, 40, 40) else argb(255, 200, 200, 200)
        }
        applySharpen(pixels, w, h, 100)
        val darkSide = pixels[2 * w + 3] // just left of the edge
        val lightSide = pixels[2 * w + 4] // just right of the edge
        assertTrue("dark side must darken", redOf(darkSide) <= 40)
        assertTrue("light side must lighten", redOf(lightSide) >= 200)
        assertTrue("contrast must grow", redOf(lightSide) - redOf(darkSide) >= 160)
    }

    @Test
    fun `negative sharpen blurs toward the local mean`() {
        val w = 8
        val h = 4
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < 4) argb(255, 40, 40, 40) else argb(255, 200, 200, 200)
        }
        applySharpen(pixels, w, h, -100)
        val darkSide = redOf(pixels[2 * w + 3])
        val lightSide = redOf(pixels[2 * w + 4])
        // Negative unsharp pulls both sides toward the local mean (3x3 means are 93 and 146; a
        // strong negative amount overshoots, so the honest assertion is "moved substantially toward 120").
        assertTrue("dark side must rise toward the mean", darkSide in 41..125)
        assertTrue("light side must fall toward the mean", lightSide in 115..199)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sharpen outside the range is rejected`() {
        applySharpen(IntArray(16), 4, 4, -101)
    }
}
