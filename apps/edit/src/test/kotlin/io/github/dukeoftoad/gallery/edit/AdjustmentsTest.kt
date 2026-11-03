package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Test

/** The LUT + pixel adjustment stages (r30, Phase 6 v1 slice 2). */
class AdjustmentsTest {

    // ---- LutBuilder ----

    @Test
    fun `all-zero parameters are the exact identity lut`() {
        val lut = LutBuilder.build(brightness = 0, contrast = 0, exposure = 0, highlights = 0, shadows = 0)
        for (v in 0..255) assertEquals("v=$v", v, lut[v])
    }

    @Test
    fun `positive exposure doubles at plus one stop`() {
        // +100 = +1 EV: every value doubles (clamped).
        val lut = LutBuilder.build(brightness = 0, contrast = 0, exposure = 100, highlights = 0, shadows = 0)
        assertEquals(0, lut[0])
        assertEquals(80, lut[40])
        assertEquals(200, lut[100])
        assertEquals(255, lut[160]) // 320 clamps
    }

    @Test
    fun `negative exposure halves at minus one stop`() {
        val lut = LutBuilder.build(brightness = 0, contrast = 0, exposure = -100, highlights = 0, shadows = 0)
        assertEquals(50, lut[100])
        assertEquals(128, lut[255]) // 127.5 rounds to 128
        assertEquals(0, lut[0])
    }

    @Test
    fun `exposure composes before brightness and contrast`() {
        // The r29 pinned composed value was 156 for b=+50,c=+50 at v=10.
        // With exposure=0 this must reproduce it EXACTLY (backward compat
        // with the r29 preview for zero new params).
        val lut = LutBuilder.build(brightness = 50, contrast = 50, exposure = 0, highlights = 0, shadows = 0)
        assertEquals(156, lut[10])
    }

    @Test
    fun `highlights move only the bright half`() {
        val lut = LutBuilder.build(brightness = 0, contrast = 0, exposure = 0, highlights = -100, shadows = 0)
        // Dark values are untouched (weight 0): v=0 stays 0.
        assertEquals(0, lut[0])
        // Pure white at -100 (max recovery, half scale): 255 - 127.5 -> 128.
        assertEquals(128, lut[255])
        // Mid-grey barely moves: weight = smoothstep(128,255,128) = 0.
        assertEquals(128, lut[128])
    }

    @Test
    fun `shadows move only the dark half`() {
        val lut = LutBuilder.build(brightness = 0, contrast = 0, exposure = 0, highlights = 0, shadows = 100)
        // Pure black lifted by the full half-scale: +127.5 -> 128.
        assertEquals(128, lut[0])
        // Bright values untouched: weight = 1 - smoothstep(0,128,v) = 0
        // for every v >= 128, so bright values pass through EXACTLY.
        assertEquals(255, lut[255])
        assertEquals(200, lut[200])
    }

    @Test
    fun `highlights and shadows are smooth at their boundaries`() {
        val lut = LutBuilder.build(brightness = 0, contrast = 0, exposure = 0, highlights = -100, shadows = 100)
        // Monotone across the fold: no bright/dark seam jumps.
        for (v in 1..255) {
            assertTrueMsg("v=$v", lut[v] >= lut[v - 1] - 8)
        }
    }

    @Test
    fun `smoothstep matches its contract`() {
        assertEquals(0.0, LutBuilder.smoothstep(0.0, 1.0, -1.0), 1e-9)
        assertEquals(0.0, LutBuilder.smoothstep(0.0, 1.0, 0.0), 1e-9)
        assertEquals(1.0, LutBuilder.smoothstep(0.0, 1.0, 1.0), 1e-9)
        assertEquals(1.0, LutBuilder.smoothstep(0.0, 1.0, 2.0), 1e-9)
        assertEquals(0.5, LutBuilder.smoothstep(0.0, 1.0, 0.5), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out-of-range lut parameters are rejected`() {
        LutBuilder.build(brightness = 0, contrast = 0, exposure = 101, highlights = 0, shadows = 0)
    }

    // ---- PixelOps: saturation ----

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `zero saturation is a no-op (stage skipped)`() {
        val pixels = intArrayOf(argb(255, 200, 100, 50))
        PixelOps.applySaturation(pixels, 0)
        assertEquals(argb(255, 200, 100, 50), pixels[0])
    }

    @Test
    fun `plus one hundred saturation doubles the distance from luma`() {
        // (200, 100, 50): lum = (200*299 + 100*587 + 50*114)/1000 = 124 (int).
        val pixels = intArrayOf(argb(255, 200, 100, 50))
        PixelOps.applySaturation(pixels, 100)
        val p = pixels[0]
        assertEquals(255, (p ushr 16) and 0xFF) // 124 + (200-124)*2 = 276 -> 255
        assertEquals(76, (p ushr 8) and 0xFF)   // 124 + (100-124)*2 = 76
        assertEquals(0, p and 0xFF)             // 124 + (50-124)*2 = -24 -> 0
        assertEquals(255, (p ushr 24) and 0xFF) // alpha untouched
    }

    @Test
    fun `minus one hundred saturation collapses to luma`() {
        val pixels = intArrayOf(argb(255, 200, 100, 50))
        PixelOps.applySaturation(pixels, -100)
        val p = pixels[0]
        val lum = 124
        assertEquals(lum, (p ushr 16) and 0xFF)
        assertEquals(lum, (p ushr 8) and 0xFF)
        assertEquals(lum, p and 0xFF)
    }

    // ---- PixelOps: temperature + tint ----

    @Test
    fun `zero temperature and tint is a no-op (stage skipped)`() {
        val pixels = intArrayOf(argb(255, 100, 150, 200))
        PixelOps.applyTemperatureTint(pixels, 0, 0)
        assertEquals(argb(255, 100, 150, 200), pixels[0])
    }

    @Test
    fun `positive temperature warms red up and blue down`() {
        val pixels = intArrayOf(argb(255, 100, 150, 200))
        PixelOps.applyTemperatureTint(pixels, 100, 0)
        val p = pixels[0]
        assertEquals(100 + 63, (p ushr 16) and 0xFF)
        assertEquals(150, (p ushr 8) and 0xFF) // green untouched by temperature
        assertEquals(200 - 63, p and 0xFF)
    }

    @Test
    fun `positive tint moves toward magenta (green down)`() {
        val pixels = intArrayOf(argb(255, 100, 150, 200))
        PixelOps.applyTemperatureTint(pixels, 0, 100)
        val p = pixels[0]
        assertEquals(100, (p ushr 16) and 0xFF)
        assertEquals(150 - 63, (p ushr 8) and 0xFF)
        assertEquals(200, p and 0xFF)
    }

    @Test
    fun `temperature and tint clamp at the channel bounds`() {
        val pixels = intArrayOf(argb(255, 250, 5, 250))
        PixelOps.applyTemperatureTint(pixels, 100, -100)
        val p = pixels[0]
        assertEquals(255, (p ushr 16) and 0xFF) // 250 + 63 clamps
        assertEquals(68, (p ushr 8) and 0xFF)   // 5 + 63
        assertEquals(187, p and 0xFF)           // 250 - 63
    }

    private fun assertTrueMsg(msg: String, condition: Boolean) {
        if (!condition) throw AssertionError(msg)
    }
}
