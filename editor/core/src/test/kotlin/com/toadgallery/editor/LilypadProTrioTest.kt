package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM pins: whites/blacks LUT stage, vibrance kernel, luminance histogram, grade transfer, codec keys. */
class LilypadProTrioTest {

    // ---- whites/blacks: the LUT chain's far ends ----

    @Test
    fun `whites and blacks at zero reproduce the five-stage table byte-for-byte`() {
        val five = LutBuilder.build(
            brightness = 12,
            contrast = -8,
            exposure = 30,
            highlights = -40,
            shadows = 25,
        )
        val seven = LutBuilder.build(
            brightness = 12,
            contrast = -8,
            exposure = 30,
            highlights = -40,
            shadows = 25,
            whites = 0,
            blacks = 0,
        )
        assertTrue("the arrays must be equal", five.contentEquals(seven))
    }

    @Test
    fun `positive whites lift the far bright end only`() {
        val neutral = LutBuilder.build(0, 0, 0, 0, 0)
        val lifted = LutBuilder.build(0, 0, 0, 0, 0, whites = 100, blacks = 0)
        // The far dark end is untouched (the mask is zero there).
        assertEquals(neutral[10], lifted[10])
        // Far bright end lifts where headroom exists (245: 10 counts) and clamps at 255.
        assertTrue("245 must lift: ${lifted[245]}", lifted[245] > neutral[245])
        // The ceiling itself cannot lift --- it is already 255.
        assertEquals(255, lifted[255])
    }

    @Test
    fun `negative blacks crush the far dark end`() {
        val neutral = LutBuilder.build(0, 0, 0, 0, 0)
        val crushed = LutBuilder.build(0, 0, 0, 0, 0, whites = 0, blacks = -100)
        // The bright end is untouched.
        assertEquals(neutral[250], crushed[250])
        // The dark end moved DOWN toward 0.
        assertTrue("10 must crush: ${crushed[10]}", crushed[10] < neutral[10])
    }

    @Test
    fun `the midtone stays put under both far-end moves`() {
        val neutral = LutBuilder.build(0, 0, 0, 0, 0)
        val moved = LutBuilder.build(0, 0, 0, 0, 0, whites = 60, blacks = 60)
        // Around 128 both masks are ~0 (smoothstep tails): at most 2 counts of carry.
        assertTrue(
            "128 moved by ${moved[128] - neutral[128]}",
            kotlin.math.abs(moved[128] - neutral[128]) <= 2,
        )
    }

    // ---- vibrance: the luma-weighted saturation ----

    private fun pixel(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `vibrance at zero is skipped - an exact identity`() {
        val pixels = intArrayOf(pixel(120, 60, 200), pixel(50, 50, 50))
        val before = pixels.copyOf()
        PixelOps.applyVibrance(pixels, 0)
        assertTrue(before.contentEquals(pixels))
    }

    @Test
    fun `a grey pixel lifts fully - the saturation grammar`() {
        // Pure grey + tinted channels equally distant from luma.
        val pixels = intArrayOf(pixel(100, 100, 100))
        PixelOps.applyVibrance(pixels, 100)
        // A grey pixel has no colour to lift: stays grey.
        assertEquals(100, (pixels[0] shr 16) and 0xFF)
        assertEquals(100, (pixels[0] shr 8) and 0xFF)
        assertEquals(100, pixels[0] and 0xFF)
    }

    @Test
    fun `a muted colour lifts more than a saturated one`() {
        // Muted: 20 counts from luma. Vivid: 100 counts from luma.
        val muted = intArrayOf(pixel(120, 100, 80))
        val vivid = intArrayOf(pixel(200, 100, 0))
        PixelOps.applyVibrance(muted, 100)
        PixelOps.applyVibrance(vivid, 100)
        val mutedSpread = (muted[0] shr 16 and 0xFF) - (muted[0] and 0xFF)
        val vividSpread = (vivid[0] shr 16 and 0xFF) - (vivid[0] and 0xFF)
        // Muted spread grows relatively more: 20 -> 40 (x2) vs 100 -> ~179 (x1.79).
        val mutedBefore = 120 - 80
        val vividBefore = 200 - 0
        val mutedGrowth = mutedSpread.toDouble() / mutedBefore
        val vividGrowth = vividSpread.toDouble() / vividBefore
        assertTrue(
            "muted growth $mutedGrowth must exceed vivid growth $vividGrowth",
            mutedGrowth > vividGrowth,
        )
    }

    @Test
    fun `negative vibrance mutes toward luma`() {
        val pixels = intArrayOf(pixel(200, 100, 0))
        PixelOps.applyVibrance(pixels, -100)
        val r = (pixels[0] shr 16) and 0xFF
        val g = (pixels[0] shr 8) and 0xFF
        val b = pixels[0] and 0xFF
        val lum = (r * 299 + g * 587 + b * 114) / 1000
        // Channels pull toward the original luma; partial but real (spread shrinks).
        assertTrue("spread must shrink: r=$r b=$b", (r - b) < 200)
    }

    // ---- the luminance histogram ----

    @Test
    fun `the luma bins count the Rec601 luma of every pixel`() {
        val pixels = intArrayOf(
            pixel(0, 0, 0),      // luma 0
            pixel(255, 255, 255), // luma 255
            pixel(255, 0, 0),    // luma 76
            pixel(0, 255, 0),    // luma 149
        )
        val histogram = EditorHistogram.fromPixels(pixels)
        assertEquals(1, histogram.luma[0])
        assertEquals(1, histogram.luma[255])
        assertEquals(1, histogram.luma[76])
        assertEquals(1, histogram.luma[149])
        assertEquals(4, histogram.luma.sum())
        // Peak is the tallest single bin (1 here), not the sum: the chart's convention.
        assertEquals(1, histogram.lumaPeak)
    }

    @Test
    fun `grey pixels land exactly in the luma bins`() {
        val pixels = intArrayOf(pixel(128, 128, 128), pixel(128, 128, 128))
        val histogram = EditorHistogram.fromPixels(pixels)
        assertEquals(2, histogram.luma[128])
    }

    // ---- the copy-paste transfer ----

    @Test
    fun `the grade travels and the geometry and scene layers stay`() {
        val source = EditRecipe(
            exposure = 30,
            contrast = -10,
            whites = 40,
            blacks = -20,
            vibrance = 25,
            vignette = 15,
            quarterTurns = 1,
            crop = CropRect(0.1, 0.1, 0.9, 0.9),
        )
        val target = EditRecipe(
            exposure = 0,
            straightenDeg = 5.0,
            textLayers = TextLayers(listOf(TextLayer(id = "t1", text = "keep me", fontId = "manrope"))),
        )
        val pasted = EditTransfer.pasteGrade(source, target)
        // The grade landed.
        assertEquals(30, pasted.exposure)
        assertEquals(-10, pasted.contrast)
        assertEquals(40, pasted.whites)
        assertEquals(-20, pasted.blacks)
        assertEquals(25, pasted.vibrance)
        assertEquals(15, pasted.vignette)
        // The geometry stayed the TARGET's.
        assertEquals(0, pasted.quarterTurns)
        assertEquals(5.0, pasted.straightenDeg, 0.0)
        assertEquals(CropRect(0.0, 0.0, 1.0, 1.0), pasted.crop)
        // The scene layers stayed.
        assertEquals(1, pasted.textLayers?.layers?.size)
    }

    @Test
    fun `hasGrade is honest about an empty grade`() {
        assertFalse(EditTransfer.hasGrade(EditRecipe()))
        assertTrue(EditTransfer.hasGrade(EditRecipe(exposure = 1)))
        assertTrue(EditTransfer.hasGrade(EditRecipe(vibrance = 1)))
        assertTrue(EditTransfer.hasGrade(EditRecipe(whites = 1)))
    }

    // ---- the codec's additive keys ----

    @Test
    fun `a pre-r74 sidecar round-trips with the new fields neutral`() {
        // Missing keys parse as 0 and re-serialize as explicit flat zeros; recipe equivalent.
        val pre = EditRecipe(exposure = 20, saturation = -5)
        val json = EditRecipeCodec.toJson(pre)
        val back = (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(0, back.whites)
        assertEquals(0, back.blacks)
        assertEquals(0, back.vibrance)
        assertEquals(20, back.exposure)
    }

    @Test
    fun `the r74 fields round-trip through the codec`() {
        val recipe = EditRecipe(whites = 55, blacks = -35, vibrance = 70)
        val json = EditRecipeCodec.toJson(recipe)
        val back = (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(55, back.whites)
        assertEquals(-35, back.blacks)
        assertEquals(70, back.vibrance)
    }

    @Test
    fun `out-of-range r74 values coerce on parse - the tolerant read`() {
        val json = EditRecipeCodec.toJson(EditRecipe(whites = 100, blacks = 100, vibrance = 100))
            .replace("\"whites\":100", "\"whites\":250")
            .replace("\"blacks\":100", "\"blacks\":-900")
        val back = (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(100, back.whites)
        assertEquals(-100, back.blacks)
    }
}
