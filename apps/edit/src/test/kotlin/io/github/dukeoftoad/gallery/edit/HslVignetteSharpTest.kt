package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HSL / vignette / sharpen stage (r30, Phase 6 v1 slice 3). */
class HslVignetteSharpTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    // ---- RgbHsl ----

    @Test
    fun `rgb to hsl hits the canonical primaries`() {
        // Pure red: h=0, s=1, l=0.5.
        val red = RgbHsl.toHsl(255, 0, 0)
        assertEquals(0.0, red.first, 1e-9)
        assertEquals(1.0, red.second, 1e-9)
        assertEquals(0.5, red.third, 1e-9)
        // Pure green: 120 degrees. Pure blue: 240.
        assertEquals(120.0, RgbHsl.toHsl(0, 255, 0).first, 1e-9)
        assertEquals(240.0, RgbHsl.toHsl(0, 0, 255).first, 1e-9)
        // Grey is achromatic with l = the level.
        val grey = RgbHsl.toHsl(128, 128, 128)
        assertEquals(0.0, grey.first, 1e-9)
        assertEquals(0.0, grey.second, 1e-9)
        assertEquals(128 / 255.0, grey.third, 1e-9)
    }

    @Test
    fun `hsl to rgb inverts the forward map for primaries`() {
        assertEquals(255, RgbHsl.fromHsl(0.0, 1.0, 0.5)[0])
        assertEquals(0, RgbHsl.fromHsl(0.0, 1.0, 0.5)[1])
        assertEquals(0, RgbHsl.fromHsl(0.0, 1.0, 0.5)[2])
        val green = RgbHsl.fromHsl(120.0, 1.0, 0.5)
        assertEquals(0, green[0]); assertEquals(255, green[1]); assertEquals(0, green[2])
        val white = RgbHsl.fromHsl(0.0, 0.0, 1.0)
        assertEquals(255, white[0]); assertEquals(255, white[1]); assertEquals(255, white[2])
    }

    // ---- HslBands ----

    @Test
    fun `band weights are one at centres and zero past thirty degrees`() {
        assertEquals(1.0, HslBands.bandWeight(0.0, 0), 1e-9)
        assertEquals(0.0, HslBands.bandWeight(30.0, 0), 1e-9)
        assertEquals(0.0, HslBands.bandWeight(31.0, 0), 1e-9)
        // Circular: 350 degrees is 10 from red's 0.
        assertEquals(HslBands.bandWeight(10.0, 0), HslBands.bandWeight(350.0, 0), 1e-9)
        // Adjacent bands share the 30-degree point 50/50-ish (both non-zero).
        assertTrue(HslBands.bandWeight(25.0, 0) > 0.0)
        assertTrue(HslBands.bandWeight(25.0, 30) > 0.0)
        assertTrue(HslBands.bandWeight(25.0, 60) == 0.0)
    }

    @Test
    fun `hue distance is circular`() {
        assertEquals(0.0, HslBands.hueDistance(0.0, 360.0), 1e-9)
        assertEquals(20.0, HslBands.hueDistance(350.0, 10.0), 1e-9)
        assertEquals(180.0, HslBands.hueDistance(0.0, 180.0), 1e-9)
    }

    @Test
    fun `identity bands are a no-op (stage skipped)`() {
        val pixels = intArrayOf(argb(255, 200, 100, 50), argb(255, 10, 20, 30))
        applyHslBands(pixels, HslBands())
        assertEquals(argb(255, 200, 100, 50), pixels[0])
        assertEquals(argb(255, 10, 20, 30), pixels[1])
    }

    @Test
    fun `achromatic pixels never drift coloured`() {
        // A grey pixel with a hue-shifting red band stays exactly grey.
        val pixels = intArrayOf(argb(255, 128, 128, 128))
        applyHslBands(pixels, HslBands(red = HslBandAdjust(hue = 60)))
        assertEquals(argb(255, 128, 128, 128), pixels[0])
    }

    @Test
    fun `a red-hue shift moves pure red toward orange but keeps primaries saturated`() {
        // Pure red with red.hue = +10: hue 10 -> the green channel rises
        // from 0, red stays maxed, blue stays 0 (a pure-ish hue rotation).
        val pixels = intArrayOf(argb(255, 255, 0, 0))
        applyHslBands(pixels, HslBands(red = HslBandAdjust(hue = 10)))
        val p = pixels[0]
        assertEquals(255, (p ushr 16) and 0xFF)
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        assertTrue("green must rise from 0", g > 0)
        assertEquals(0, b)
        // And the result stays fully saturated (s = 1 preserved): max-min
        // full scale.
        assertTrue((p ushr 16 and 0xFF) - b == 255)
    }

    @Test
    fun `red saturation minus one hundred desaturates to pure grey`() {
        val pixels = intArrayOf(argb(255, 255, 0, 0))
        applyHslBands(pixels, HslBands(red = HslBandAdjust(saturation = -100)))
        val p = pixels[0]
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        assertEquals(r, g)
        assertEquals(r, b)
    }

    @Test
    fun `blue luminance plus lifts the blue band`() {
        // A pure blue pixel, blue.lum = +100 -> l rises from 0.5 by 0.25.
        val pixels = intArrayOf(argb(255, 0, 0, 255))
        applyHslBands(pixels, HslBands(blue = HslBandAdjust(luminance = 100)))
        val p = pixels[0]
        // All channels rise; blue stays dominant.
        assertTrue((p and 0xFF) > (p ushr 16 and 0xFF))
        assertTrue((p and 0xFF) > 128)
    }

    // ---- Vignette ----

    @Test
    fun `zero vignette is a no-op (stage skipped)`() {
        val pixels = intArrayOf(argb(255, 100, 100, 100))
        applyVignette(pixels, 1, 1, 0)
        assertEquals(argb(255, 100, 100, 100), pixels[0])
    }

    @Test
    fun `positive vignette darkens corners and leaves the centre alone`() {
        // 5x5 uniform mid-grey; strength +100: corner factor 1 - 0.8 =
        // 0.2, centre (mask 0) untouched.
        val w = 5
        val h = 5
        val pixels = IntArray(w * h) { argb(255, 100, 100, 100) }
        applyVignette(pixels, w, h, 100)
        val centre = pixels[2 * w + 2]
        assertEquals(100, (centre ushr 16) and 0xFF) // mask 0 at r ~0.28
        val corner = pixels[0]
        assertEquals((100 * 0.2).toInt(), (corner ushr 16) and 0xFF) // 20
    }

    @Test
    fun `negative vignette brightens corners`() {
        val w = 5
        val h = 5
        val pixels = IntArray(w * h) { argb(255, 100, 100, 100) }
        applyVignette(pixels, w, h, -100)
        val corner = pixels[0]
        val v = (corner ushr 16) and 0xFF
        assertTrue("corner brightened: $v", v > 100)
        assertTrue("capped at 2x: $v", v <= 200)
    }

    // ---- Sharpen ----

    @Test
    fun `zero sharpen is a no-op (stage skipped)`() {
        val pixels = intArrayOf(argb(255, 100, 90, 80))
        applySharpen(pixels, 1, 1, 0)
        assertEquals(argb(255, 100, 90, 80), pixels[0])
    }

    @Test
    fun `a constant image is an identity at any amount`() {
        val pixels = IntArray(9) { argb(255, 77, 77, 77) }
        applySharpen(pixels, 3, 3, 100)
        for (i in 0 until 9) {
            assertEquals(77, (pixels[i] ushr 16) and 0xFF)
            assertEquals(77, (pixels[i] ushr 8) and 0xFF)
            assertEquals(77, pixels[i] and 0xFF)
        }
    }

    @Test
    fun `an edge gets amplified by positive sharpen`() {
        // 3x1 [0, 255, 0]: blur = [85, 85, 85]; k = 1.5 at +100.
        val pixels = intArrayOf(argb(255, 0, 0, 0), argb(255, 255, 255, 255), argb(255, 0, 0, 0))
        applySharpen(pixels, 3, 1, 100)
        // Ends: 0 + 1.5*(0-85) -> clamp 0. Middle: 255 + 1.5*(255-85) -> 255.
        assertEquals(0, (pixels[0] ushr 16) and 0xFF)
        assertEquals(255, (pixels[1] ushr 16) and 0xFF)
        assertEquals(0, (pixels[2] ushr 16) and 0xFF)
        // And a WEAK edge partially sharpens: 3x1 [0, 100, 0]:
        // blur = 300/9 = 33 (Int division); k=1.5: middle = 100+1.5*67 =
        // 200.5 -> rounds to 201.
        val soft = intArrayOf(argb(255, 0, 0, 0), argb(255, 100, 100, 100), argb(255, 0, 0, 0))
        applySharpen(soft, 3, 1, 100)
        assertEquals(201, (soft[1] ushr 16) and 0xFF)
        assertEquals(0, (soft[0] ushr 16) and 0xFF)
    }
}
