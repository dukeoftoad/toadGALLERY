package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r66 histogram's JVM pins (ADR-0011: the counting is pure integer
 * arithmetic, so it is tested here and only the drawing rides the
 * screenshot harness).
 *
 * The pins are the properties the chart and its a11y state lean on: the
 * bins account for every pixel exactly once per channel, the pure-colour
 * and zone-boundary arithmetic is exact, the percents always sum to
 * exactly 100 (the a11y sentence can never read 99% of a picture), the
 * count is deterministic, and an empty input degrades instead of
 * throwing.
 */
class EditorHistogramTest {

    @Test
    fun `every pixel lands in exactly one bin per channel`() {
        val pixels = intArrayOf(
            0xFF000000.toInt(),
            0xFF805030.toInt(),
            0xFF3E2A6E.toInt(),
            0xFFFFFFFF.toInt(),
        )
        val h = EditorHistogram.fromPixels(pixels)
        assertEquals(pixels.size, h.red.sum())
        assertEquals(pixels.size, h.green.sum())
        assertEquals(pixels.size, h.blue.sum())
        assertEquals(pixels.size, h.pixelCount)
    }

    @Test
    fun `a pure red pixel pins its three channel bins`() {
        val h = EditorHistogram.fromPixels(intArrayOf(0xFFFF0000.toInt()))
        assertEquals(1, h.red[255])
        assertEquals(1, h.green[0])
        assertEquals(1, h.blue[0])
        assertEquals(1, h.peak)
    }

    @Test
    fun `the luma zones split at the documented thirds`() {
        // Grays carry luma == their own value (the 299+587+114 weights
        // sum to 1000), so exact boundary values are one gray pixel
        // each: 84 shadows, 85 and 170 midtones, 171 highlights.
        val h = EditorHistogram.fromPixels(
            intArrayOf(
                0xFF545454.toInt(), // luma 84 -> shadows
                0xFF555555.toInt(), // luma 85 -> midtones
                0xFFAAAAAA.toInt(), // luma 170 -> midtones
                0xFFABABAB.toInt(), // luma 171 -> highlights
            ),
        )
        val (shadows, midtones, highlights) = h.zoneFractions()
        assertEquals(25, shadows)
        assertEquals(50, midtones)
        assertEquals(25, highlights)
    }

    @Test
    fun `the zone percents always sum to exactly 100`() {
        // 7 pixels: 1 shadow, 6 highlights --- the floor division leaves
        // a remainder only the midtone share can absorb (14 + ? + 85).
        val pixels = IntArray(7) { i -> if (i == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val (shadows, midtones, highlights) = EditorHistogram.fromPixels(pixels).zoneFractions()
        assertEquals(100, shadows + midtones + highlights)
        assertEquals(14, shadows)
        assertEquals(85, highlights)
        assertEquals(1, midtones)
    }

    @Test
    fun `the count is deterministic`() {
        val pixels = intArrayOf(0xFF123456.toInt(), 0xFF654321.toInt(), 0xFFABCDEF.toInt())
        val a = EditorHistogram.fromPixels(pixels)
        val b = EditorHistogram.fromPixels(pixels)
        assertTrue(a.red.contentEquals(b.red))
        assertTrue(a.green.contentEquals(b.green))
        assertTrue(a.blue.contentEquals(b.blue))
        assertEquals(a.zoneFractions(), b.zoneFractions())
    }

    @Test
    fun `an empty count degrades to an empty histogram`() {
        val h = EditorHistogram.fromPixels(IntArray(0))
        assertEquals(0, h.pixelCount)
        assertEquals(0, h.peak)
        assertEquals(Triple(0, 0, 0), h.zoneFractions())
    }
}
