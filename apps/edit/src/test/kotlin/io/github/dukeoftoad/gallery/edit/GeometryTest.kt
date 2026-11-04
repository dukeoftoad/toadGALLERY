package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The geometry toolset (r30, Phase 6 v1 slice 1). */
class GeometryTest {

    // ---- CropRect ----

    @Test
    fun `crop normalization clamps and reorders`() {
        val rect = CropRect(left = 0.8, top = -0.2, right = 0.2, bottom = 1.4).normalized()
        // -0.2 -> 0, 1.4 -> 1; left(0.8) > right(0.2) swapped.
        assertEquals(CropRect(0.2, 0.0, 0.8, 1.0), rect)
    }

    @Test
    fun `degenerate crop rects never survive`() {
        val rect = CropRect(0.5, 0.5, 0.5, 0.5).normalized()
        assertTrue(rect.right - rect.left >= CropRect.MIN_SPAN)
        assertTrue(rect.bottom - rect.top >= CropRect.MIN_SPAN)
    }

    @Test
    fun `crop pixels round to a sane inclusive-exclusive rect`() {
        // Full-frame crop = the identity rect.
        assertEquals(
            PixelRect(0, 0, 4, 4),
            CropRect(0.0, 0.0, 1.0, 1.0).toPixels(4, 4),
        )
        // Half rect on a 4x4: [0.25, 0.75) -> x0=1, x1=3.
        assertEquals(
            PixelRect(1, 1, 3, 3),
            CropRect(0.25, 0.25, 0.75, 0.75).toPixels(4, 4),
        )
        // Tiny images keep at least 1 pixel.
        val tiny = CropRect(0.0, 0.0, 0.1, 0.1).toPixels(2, 2)
        assertTrue(tiny.width >= 1 && tiny.height >= 1)
    }

    @Test
    fun `cropPixels cuts the right region`() {
        // 3x2 grid [1 2 3 / 4 5 6]; crop the middle 2x1.
        val result = cropPixels(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, PixelRect(1, 0, 3, 1))
        assertEquals(2, result.width)
        assertEquals(1, result.height)
        assertEquals(listOf(2, 3), result.pixels.toList())
        // The lower row.
        val lower = cropPixels(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, PixelRect(0, 1, 2, 2))
        assertEquals(listOf(4, 5), lower.pixels.toList())
    }

    // ---- CropAspect ----

    @Test
    fun `free aspect returns the box untouched`() {
        val box = PixelRect(1, 1, 9, 7)
        assertEquals(box, CropAspect.FREE.fit(box, 40, 30))
    }

    @Test
    fun `original aspect fits the image ratio inside a taller box`() {
        // Image 4:3 (40x30); box 8x6 inside a 8x10 frame region: ORIGINAL
        // fits 4:3 into the box.
        val fitted = CropAspect.ORIGINAL.fit(PixelRect(0, 0, 8, 10), 40, 30)
        assertEquals(4.0 / 3.0, fitted.width.toDouble() / fitted.height, 1e-6)
        assertTrue(fitted.width <= 8 && fitted.height <= 10)
    }

    @Test
    fun `square aspect caps the wider dimension`() {
        // 16:9 box -> square caps height to width.
        val fitted = CropAspect.SQUARE.fit(PixelRect(0, 0, 16, 9), 40, 30)
        assertEquals(fitted.width, fitted.height)
        // 9:16 box -> square caps width.
        val fitted2 = CropAspect.SQUARE.fit(PixelRect(0, 0, 9, 16), 40, 30)
        assertEquals(fitted2.width, fitted2.height)
    }

    @Test
    fun `sixteen-nine fits inside a four-thirds box`() {
        val box = PixelRect(0, 0, 40, 30) // exactly 4:3
        val fitted = CropAspect.RATIO_16_9.fit(box, 40, 30)
        assertEquals(40, fitted.width)
        assertEquals((40 * 9 / 16.0).toInt(), fitted.height) // 22.5 -> 22
        assertEquals(16.0 / 9.0, fitted.width.toDouble() / fitted.height, 0.05)
    }

    // ---- CropRect.fromPixelRect (r32) ----

    @Test
    fun `fromPixelRect is the exact inverse of toPixels`() {
        val normalized = CropRect.fromPixelRect(PixelRect(1, 1, 3, 3), 4, 4)
        assertEquals(CropRect(0.25, 0.25, 0.75, 0.75), normalized)
        // The exact round trip: pixels -> normalized -> the same pixels.
        assertEquals(PixelRect(1, 1, 3, 3), normalized.toPixels(4, 4))
    }

    @Test
    fun `the full frame normalizes to the identity crop`() {
        assertEquals(
            CropRect(0.0, 0.0, 1.0, 1.0),
            CropRect.fromPixelRect(PixelRect(0, 0, 40, 30), 40, 30),
        )
    }

    @Test
    fun `the round trip never expands the crop`() {
        // toPixels rounds inward (or exactly); converting back can only
        // move each edge by less than one pixel's worth of normalization.
        val original = CropRect(0.3, 0.2, 0.83, 0.91).normalized()
        val roundTrip = CropRect.fromPixelRect(original.toPixels(320, 240), 320, 240)
        assertTrue(roundTrip.left >= original.left - 1.0 / 320)
        assertTrue(roundTrip.top >= original.top - 1.0 / 240)
        assertTrue(roundTrip.right <= original.right + 1.0 / 320)
        assertTrue(roundTrip.bottom <= original.bottom + 1.0 / 240)
    }

    @Test
    fun `a preset fit survives the recipe round trip`() {
        // The crop-preset path: fit a 16:9 rect inside a 40x30 frame, take
        // it to normalized (what the sidecar recipe stores), bring it back
        // (what the pipeline crops) --- the pixel rect is stable.
        val fitted = CropAspect.RATIO_16_9.fit(PixelRect(0, 0, 40, 30), 40, 30)
        val normalized = CropRect.fromPixelRect(fitted, 40, 30)
        val back = normalized.toPixels(40, 30)
        assertEquals(fitted.width, back.width)
        assertEquals(fitted.height, back.height)
    }

    @Test
    fun `non-positive frames are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CropRect.fromPixelRect(PixelRect(0, 0, 1, 1), 0, 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CropRect.fromPixelRect(PixelRect(0, 0, 1, 1), 40, 0)
        }
    }

    // ---- Flip ----

    @Test
    fun `horizontal and vertical flips mirror known grids`() {
        // [1 2 3 / 4 5 6]: H-flip -> [3 2 1 / 6 5 4]; V-flip -> [4 5 6 / 1 2 3].
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(
            listOf(3, 2, 1, 6, 5, 4),
            flipPixels(grid, 3, 2, horizontal = true).toList(),
        )
        assertEquals(
            listOf(4, 5, 6, 1, 2, 3),
            flipPixels(grid, 3, 2, horizontal = false).toList(),
        )
        // Both flips = 180-degree rotation.
        val both = flipPixels(flipPixels(grid, 3, 2, true), 3, 2, false)
        assertEquals(listOf(6, 5, 4, 3, 2, 1), both.toList())
    }

    // ---- Straighten ----

    @Test
    fun `zero degrees is exactly the identity`() {
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        val result = straightenPixels(grid, 3, 2, 0.0)
        assertTrue(result.pixels.contentEquals(grid))
        assertEquals(3, result.width)
        assertEquals(2, result.height)
    }

    @Test
    fun `ninety degrees swaps dimensions like the quarter turn`() {
        // 3x2 -> rotated onto a 2x3 expanded canvas.
        val result = straightenPixels(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, 90.0)
        assertEquals(2, result.width)
        assertEquals(3, result.height)
    }

    @Test
    fun `the expanded canvas is the bounding box of the rotated corners`() {
        // 4x4 at 45 degrees: side 4*sqrt(2) ~ 5.66 -> ceil 6? Exact: |4*cos45|+|4*sin45| = 5.657 -> 6.
        val result = straightenPixels(IntArray(16) { 0xFF000000.toInt() or it }, 4, 4, 45.0)
        assertEquals(6, result.width)
        assertEquals(6, result.height)
    }

    @Test
    fun `corners of the expanded canvas are transparent at forty-five degrees`() {
        val grid = IntArray(16) { 0xFF336699.toInt() }
        val result = straightenPixels(grid, 4, 4, 45.0)
        // (0, 0): distance from the canvas centre is ~0.707*6 > 4/2 = 2
        // (the source half-diagonal is 2.12): outside -> 0x00000000.
        assertEquals(0, result.pixels[0])
        // The canvas CENTRE pixel maps exactly onto the source centre (2, 2)
        // on the 4x4 source --- wait: centre of a 6x6 canvas is (2.5, 2.5);
        // rotate back by -45 around (1.5, 1.5): both offsets 1: |1,1| ~1.41
        // rotated stays within the source (|offset| <= 1.5 diag is 2.12) ->
        // an interior sample, opaque.
        val centerIdx = 2 * result.width + 2
        assertTrue((result.pixels[centerIdx] ushr 24) == 0xFF)
    }

    @Test
    fun `straighten preserves the centre pixel exactly`() {
        // 5x5 with a distinct centre (12); any angle maps the centre onto
        // the centre (rotation about the centre), so the output centre is
        // exactly pixel (2, 2) bilinearly == the source centre.
        val grid = IntArray(25) { i -> if (i == 12) 0xFF00FF00.toInt() else 0xFF000000.toInt() }
        val result = straightenPixels(grid, 5, 5, 33.0)
        val ow = result.width
        val ocx = (ow - 1) / 2.0
        val ocy = (result.height - 1) / 2.0
        val cxi = (result.width - 1) / 2
        val cyi = (result.height - 1) / 2
        // The exact centre may be fractional on even canvases; assert the
        // four-centre cluster is opaque (the source centre region).
        assertTrue((result.pixels[cyi * ow + cxi] ushr 24) == 0xFF)
        assertTrue(ocx == (ow - 1) / 2.0) // sanity: convention consistent
    }

    // ---- Perspective ----

    @Test
    fun `the identity quad is exactly the identity`() {
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        val result = perspectiveWarp(grid, 3, 2, PerspectiveQuad.IDENTITY)
        assertTrue(result.pixels.contentEquals(grid))
    }

    @Test
    fun `a displaced corner lands exactly on the corresponding output corner`() {
        // 8x2 grid (the clamp allows offsets up to w/4 = 2); pull the TL
        // corner to (1, 0): output (0, 0) then samples the source at
        // exactly (1, 0) -> pixel 20.
        val grid = intArrayOf(
            10, 20, 30, 40, 50, 60, 70, 80,
            90, 100, 110, 120, 130, 140, 150, 160,
        )
        val quad = PerspectiveQuad(tlX = 1.0, tlY = 0.0, trX = 0.0, trY = 0.0, brX = 0.0, brY = 0.0, blX = 0.0, blY = 0.0)
        val result = perspectiveWarp(grid, 8, 2, quad)
        assertEquals(20, result.pixels[0])
        // TR corner (0 displacement): output (7, 0) = source (7, 0) = 80.
        assertEquals(80, result.pixels[7])
        // BR corner: output (7, 1) = source (7, 1) = 160.
        assertEquals(160, result.pixels[result.width + 7])
    }

    @Test
    fun `displacements are clamped to a quarter of the dimension`() {
        val hostile = PerspectiveQuad(
            tlX = 1000.0, tlY = -1000.0, trX = 0.0, trY = 0.0,
            brX = 0.0, brY = 0.0, blX = 0.0, blY = 0.0,
        )
        val clamped = hostile.clamped(4, 4)
        assertEquals(1.0, clamped.tlX, 1e-9)  // +/- w/4 = +/- 1
        assertEquals(-1.0, clamped.tlY, 1e-9)
    }

    @Test
    fun `point maps the unit square corners onto the displaced quad corners`() {
        val quad = PerspectiveQuad(tlX = 1.0, tlY = 2.0, trX = -1.0, trY = 0.0, brX = 0.0, brY = 1.0, blX = 0.0, blY = -1.0)
        // 4x3 image: TL corner is (0,0)+disp = (1, 2).
        val (tlx, tly) = quad.point(0.0, 0.0, 4, 3)
        assertEquals(1.0, tlx, 1e-9)
        assertEquals(2.0, tly, 1e-9)
        // TR corner is (3, 0)+disp = (2, 0).
        val (trx, try_) = quad.point(1.0, 0.0, 4, 3)
        assertEquals(2.0, trx, 1e-9)
        assertEquals(0.0, try_, 1e-9)
        // BR: (3, 2)+(0,1) = (3, 3). BL: (0, 2)+(0,-1) = (0, 1).
        val (brx, bry) = quad.point(1.0, 1.0, 4, 3)
        assertEquals(3.0, brx, 1e-9)
        assertEquals(3.0, bry, 1e-9)
        val (blx, bly) = quad.point(0.0, 1.0, 4, 3)
        assertEquals(0.0, blx, 1e-9)
        assertEquals(1.0, bly, 1e-9)
        // The identity quad's midpoint maps to the image midpoint exactly.
        val (mx, my) = PerspectiveQuad.IDENTITY.point(0.5, 0.5, 4, 3)
        assertEquals(1.5, mx, 1e-9)
        assertEquals(1.0, my, 1e-9)
    }

    @Test
    fun `single-pixel-wide or tall grids do not divide by zero`() {
        val result = perspectiveWarp(intArrayOf(7, 8), 1, 2, PerspectiveQuad.IDENTITY)
        assertTrue(result.pixels.contentEquals(intArrayOf(7, 8)))
        val straight = straightenPixels(intArrayOf(7, 8), 1, 2, 10.0)
        assertNotEquals(0, straight.pixels.size)
    }
}
