package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference-strip pins: the thumbnail gradient is pure arithmetic --- determinism, exact dims,
 * and the neutral-zone gray ramp / hue-sweep contract the look/frame chips preview with.
 */
class PhotoControlsTest {

    @Test
    fun `the reference strip has the exact requested raster size`() {
        val pixels = thumbGradientPixels(THUMB_WIDTH, THUMB_HEIGHT)
        assertEquals(THUMB_WIDTH * THUMB_HEIGHT, pixels.size)
        // An arbitrary off-catalog raster works too (size-parametric; constants are the chip's choice).
        assertEquals(7 * 5, thumbGradientPixels(7, 5).size)
    }

    @Test
    fun `the reference strip is deterministic`() {
        val a = thumbGradientPixels(THUMB_WIDTH, THUMB_HEIGHT)
        val b = thumbGradientPixels(THUMB_WIDTH, THUMB_HEIGHT)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `the neutral zone is a gray ramp (tone response shows on neutrals)`() {
        val width = THUMB_WIDTH
        val height = THUMB_HEIGHT
        val pixels = thumbGradientPixels(width, height)
        val neutralTop = height * 2 / 3
        // Every bottom-third pixel is gray (R == G == B); the ramp spans dark-left to light-right.
        for (y in neutralTop until height) {
            for (x in 0 until width) {
                val argb = pixels[y * width + x]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                assertEquals("row $y col $x: R==G", r, g)
                assertEquals("row $y col $x: G==B", g, b)
            }
        }
        val left = pixels[neutralTop * width] and 0xFF
        val right = pixels[neutralTop * width + width - 1] and 0xFF
        assertTrue("the gray ramp spans: $left < $right", left < right)
    }

    @Test
    fun `the sweep zone carries color (a look's hue response shows)`() {
        val width = THUMB_WIDTH
        val pixels = thumbGradientPixels(width, THUMB_HEIGHT)
        // The top row is the hue sweep: some channel differs across the row (colored, not gray).
        val colored = (0 until width).count { x ->
            val argb = pixels[x]
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            (r != g) || (g != b)
        }
        assertTrue("the sweep row must be colored: $colored/$width", colored > width / 2)
    }
}
