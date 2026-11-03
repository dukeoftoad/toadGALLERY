package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ordered pipeline + the export format/quality control (r30). */
class PipelineExportTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    // ---- EditPipeline ----

    @Test
    fun `identity parameters render the exact input grid`() {
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        val result = EditPipeline.apply(grid, 3, 2, EditParameters())
        assertTrue(result.pixels.contentEquals(grid))
        assertEquals(3, result.width)
        assertEquals(2, result.height)
        // The input array is never mutated (preview-base reuse).
        assertTrue(grid.contentEquals(intArrayOf(1, 2, 3, 4, 5, 6)))
    }

    @Test
    fun `a brightness-only render matches the r29 LUT path`() {
        // Cross-check the r30 pipeline against the r29 hand path for a
        // brightness-only edit: identical bytes.
        val base = intArrayOf(argb(255, 10, 100, 200), argb(255, 128, 128, 128), argb(255, 250, 5, 60))
        val params = EditParameters(brightness = 50)
        val pipelined = EditPipeline.apply(base, 3, 1, params)
        val expected = base.copyOf()
        PixelAdjuster.applyTo(expected, PixelAdjuster.buildLut(50, 0))
        assertTrue(pipelined.pixels.contentEquals(expected))
    }

    @Test
    fun `geometry-only renders match the standalone ops`() {
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        // Quarter turn only.
        val rotated = EditPipeline.apply(grid, 3, 2, EditParameters(quarterTurns = 1))
        val expectRotate = PixelAdjuster.rotateQuarterTurns(grid, 3, 2, 1)
        assertTrue(rotated.pixels.contentEquals(expectRotate.pixels))
        assertEquals(expectRotate.width, rotated.width)
        // FlipH only.
        val flipped = EditPipeline.apply(grid, 3, 2, EditParameters(flipH = true))
        assertTrue(flipped.pixels.contentEquals(flipPixels(grid, 3, 2, true)))
        // Straighten 90 only: matches straightenPixels' expanded canvas.
        val straightened = EditPipeline.apply(grid, 3, 2, EditParameters(straightenDeg = 90.0))
        val expectStraighten = straightenPixels(grid, 3, 2, 90.0)
        assertTrue(straightened.pixels.contentEquals(expectStraighten.pixels))
        // Crop only: right two thirds of a 3x2.
        val cropped = EditPipeline.apply(grid, 3, 2, EditParameters(crop = CropRect(1.0 / 3.0, 0.0, 1.0, 1.0)))
        assertEquals(listOf(2, 3, 5, 6), cropped.pixels.toList())
        assertEquals(2, cropped.width)
        assertEquals(2, cropped.height)
    }

    @Test
    fun `crop happens before rotate (the fixed order)`() {
        // 4x3 grid of index pixels; crop the right half (2x3), then turn
        // once CCW: expected 3x2 = columns of the cropped half, bottom-up.
        val grid = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12) // 4x3
        val result = EditPipeline.apply(
            grid, 4, 3,
            EditParameters(crop = CropRect(0.5, 0.0, 1.0, 1.0), quarterTurns = 1),
        )
        // Cropped right half: [3 4 / 7 8 / 11 12]; CCW turn: [4 8 12 / 3 7 11].
        assertEquals(3, result.width)
        assertEquals(2, result.height)
        assertEquals(listOf(4, 8, 12, 3, 7, 11), result.pixels.toList())
    }

    @Test
    fun `vignette and sharpen ride the pixel stages before geometry`() {
        // A single-pixel-wide image avoids vignette asymmetry: 1x1 constant
        // grey with sharpen 50 stays constant (blur of flat = flat).
        val grid = intArrayOf(argb(255, 100, 100, 100))
        val result = EditPipeline.apply(grid, 1, 1, EditParameters(sharpness = 50))
        assertEquals(argb(255, 100, 100, 100), result.pixels[0])
    }

    // ---- ExportPlan: format + quality ----

    @Test
    fun `display names follow the chosen format extension`() {
        assertEquals("IMG_2024-edit.png", ExportPlan.displayNameFor("IMG_2024.jpg", ExportPlan.ExportFormat.PNG))
        assertEquals("IMG_2024-edit.webp", ExportPlan.displayNameFor("IMG_2024.png", ExportPlan.ExportFormat.WEBP))
        // Default (r29 compat): JPEG.
        assertEquals("IMG_2024-edit.jpg", ExportPlan.displayNameFor("IMG_2024.png"))
        assertEquals("photo-edit.jpg", ExportPlan.displayNameFor("photo.heic", ExportPlan.ExportFormat.JPEG))
    }

    @Test
    fun `the follow-source default maps source mimes honestly`() {
        assertEquals(ExportPlan.ExportFormat.PNG, ExportPlan.defaultFormatFor("image/png"))
        assertEquals(ExportPlan.ExportFormat.WEBP, ExportPlan.defaultFormatFor("image/webp"))
        // JPEG, HEIC (no encoder in Bitmap) and unknowns -> JPEG.
        assertEquals(ExportPlan.ExportFormat.JPEG, ExportPlan.defaultFormatFor("image/jpeg"))
        assertEquals(ExportPlan.ExportFormat.JPEG, ExportPlan.defaultFormatFor("image/heic"))
        assertEquals(ExportPlan.ExportFormat.JPEG, ExportPlan.defaultFormatFor(null))
    }

    @Test
    fun `quality is clamped and png is always lossless`() {
        assertEquals(100, ExportPlan.qualityFor(ExportPlan.ExportFormat.PNG, 40))
        assertEquals(50, ExportPlan.qualityFor(ExportPlan.ExportFormat.JPEG, 10))
        assertEquals(100, ExportPlan.qualityFor(ExportPlan.ExportFormat.JPEG, 120))
        assertEquals(95, ExportPlan.qualityFor(ExportPlan.ExportFormat.WEBP, 95))
    }

    @Test
    fun `the format-specific insert values carry the chosen mime`() {
        val values = ExportPlan.insertValuesForFormat("a-edit.png", ExportPlan.ExportFormat.PNG, api29Plus = true)
        assertEquals("a-edit.png", values["_display_name"])
        assertEquals("image/png", values["mime_type"])
        assertEquals(ExportPlan.RELATIVE_DIR, values["relative_path"])
        assertEquals(ExportPlan.IS_PENDING_TRUE, values["is_pending"])
        // The r29 two-arg shape stays JPEG-fixed (back-compat).
        val r29 = ExportPlan.insertValues("a-edit.jpg", api29Plus = true)
        assertEquals("image/jpeg", r29["mime_type"])
    }
}
