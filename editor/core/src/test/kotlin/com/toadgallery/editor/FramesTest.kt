package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame model + layout pass: selection validation, thickness formula, authored per-frame geometry. */
class FramesTest {

    // ---- Model validation ----

    @Test
    fun `a well-formed selection constructs with defaults`() {
        val selection = FrameSelection(frameId = "plain")
        assertEquals(20, selection.widthPct)
        assertEquals(0xFFFFFFFFL, selection.colorArgb)
    }

    @Test
    fun `out-of-range values are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { FrameSelection(frameId = "") }
        assertThrows(IllegalArgumentException::class.java) { FrameSelection(frameId = "plain", widthPct = 0) }
        assertThrows(IllegalArgumentException::class.java) { FrameSelection(frameId = "plain", widthPct = 101) }
        assertThrows(IllegalArgumentException::class.java) { FrameSelection(frameId = "plain", colorArgb = 0x1_00000000L) }
        assertThrows(IllegalArgumentException::class.java) { FrameSelection(frameId = "plain", colorArgb = -1L) }
    }

    // ---- The thickness formula ----

    @Test
    fun `thicknessPx is max of 1 and the integer floor`() {
        assertEquals(1, FrameLayout.thicknessPx(1, 1000)) // 1*1000/1000 = 1
        assertEquals(20, FrameLayout.thicknessPx(20, 1000)) // 20 px = 2% of a 1000 px edge
        assertEquals(7, FrameLayout.thicknessPx(12, 600)) // 7.2 -> floor 7
        assertEquals(100, FrameLayout.thicknessPx(100, 1000)) // 10% of the min edge
        assertEquals(1, FrameLayout.thicknessPx(1, 100)) // 0.1 -> floor 0 -> max 1
    }

    // ---- The authored geometry (exact hand canvases) ----

    @Test
    fun `plain is a solid border on all four sides`() {
        // 40x30 canvas: the min edge yields t=1; assert the border rects with it.
        val geo = FrameLayout.geometryFor(FrameSelection(frameId = "plain", widthPct = 20), 40, 30)!!
        val t = FrameLayout.thicknessPx(20, 30)
        assertEquals(1, t) // 20*30/1000 = 0.6 -> floor 0 -> max 1
        assertEquals(
            listOf(
                PixelRect(0, 0, 40, t),
                PixelRect(0, 30 - t, 40, 30),
                PixelRect(0, 0, t, 30),
                PixelRect(40 - t, 0, 40, 30),
            ),
            geo.rects,
        )
        assertTrue(geo.holes.isEmpty())
    }

    @Test
    fun `innerLine is the hairline ring inset by t`() {
        // widthPct 40 over 200x200 forces t=8; line = max(1, 8/4) = 2.
        val geo = FrameLayout.geometryFor(FrameSelection(frameId = "inner-line", widthPct = 40), 200, 200)!!
        val t = 8
        val line = 2
        assertEquals(
            listOf(
                PixelRect(t, t, 200 - t, t + line), // top
                PixelRect(t, 200 - t - line, 200 - t, 200 - t), // bottom
                PixelRect(t, t, t + line, 200 - t), // left
                PixelRect(200 - t - line, t, 200 - t, 200 - t), // right
            ),
            geo.rects,
        )
    }

    @Test
    fun `doubleLine is the plain border plus the ring`() {
        val geo = FrameLayout.geometryFor(FrameSelection(frameId = "double-line", widthPct = 40), 200, 200)!!
        assertEquals(8, geo.rects.size) // 4 border + 4 ring
    }

    @Test
    fun `film is the sprocket bands with punched holes`() {
        // t=8: hole = max(2, 8/2) = 4, first at x=4, every 8; bandOffset = 2.
        val geo = FrameLayout.geometryFor(FrameSelection(frameId = "film", widthPct = 40), 200, 200)!!
        assertEquals(
            listOf(PixelRect(0, 0, 200, 8), PixelRect(0, 192, 200, 200)),
            geo.rects,
        )
        // Holes at x=4+8k while x+4 <= 196: last at 188 -- 24 per band, 48 total.
        assertEquals(48, geo.holes.size)
        assertEquals(PixelRect(4, 2, 8, 6), geo.holes[0]) // first top hole
        assertEquals(PixelRect(188, 2, 192, 6), geo.holes[23]) // last top hole
        // The bottom band mirrors: y = 200 - 8 + 2 = 194.
        assertEquals(PixelRect(4, 194, 8, 198), geo.holes[24])
    }

    @Test
    fun `polaroid is three t sides and a 4t bottom`() {
        val geo = FrameLayout.geometryFor(FrameSelection(frameId = "polaroid", widthPct = 40), 200, 200)!!
        val t = 8
        assertEquals(
            listOf(
                PixelRect(0, 0, 200, t),
                PixelRect(0, 200 - 4 * t, 200, 200), // the 4t = 32 bottom band
                PixelRect(0, 0, t, 200),
                PixelRect(200 - t, 0, 200, 200),
            ),
            geo.rects,
        )
    }

    @Test
    fun `an unresolvable frameId yields no geometry`() {
        assertTrue(FrameLayout.geometryFor(FrameSelection(frameId = "ghost-frame"), 100, 100) == null)
        assertTrue(BundledFrames.geometry("ghost-frame") == null)
    }
}
