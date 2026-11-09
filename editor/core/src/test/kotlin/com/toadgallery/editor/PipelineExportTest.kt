package com.toadgallery.editor

import com.toadgallery.editor.contract.MetadataPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pipeline + export control (r30): the recipe-based [EditPipeline]
 * stage order, the [EditGeometryPlan]/pipeline frame agreement, and the
 * export format surface (JPEG/PNG/WebP --- the honest set the platform
 * Bitmap can actually encode; HEIC is decode-only, so it is not offered).
 */
class PipelineExportTest {

    // ---- EditPipeline: identity ----

    @Test
    fun `the identity recipe returns the exact input grid`() {
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        val result = EditPipeline.apply(grid, 3, 2, EditRecipe())
        assertTrue(result.pixels.contentEquals(grid))
        assertEquals(3, result.width)
        assertEquals(2, result.height)
    }

    // ---- EditPipeline: geometry order (flip -> straighten -> perspective -> turns -> crop) ----

    @Test
    fun `quarter turns match the standalone op`() {
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        val pipelined = EditPipeline.apply(grid, 3, 2, EditRecipe(quarterTurns = 1))
        val standalone = PixelAdjuster.rotateQuarterTurns(grid, 3, 2, 1)
        assertTrue(pipelined.pixels.contentEquals(standalone.pixels))
        assertEquals(standalone.width, pipelined.width)
        assertEquals(standalone.height, pipelined.height)
    }

    @Test
    fun `flips match the standalone ops`() {
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        val h = EditPipeline.apply(grid, 3, 2, EditRecipe(horizontalFlip = true))
        assertTrue(h.pixels.contentEquals(flipPixels(grid, 3, 2, horizontal = true)))
        val v = EditPipeline.apply(grid, 3, 2, EditRecipe(verticalFlip = true))
        assertTrue(v.pixels.contentEquals(flipPixels(grid, 3, 2, horizontal = false)))
    }

    @Test
    fun `straighten matches the standalone op`() {
        // 30 degrees: within the straighten contract (the +/-45 fine
        // adjustment; 90-degree turns belong to quarterTurns).
        val grid = intArrayOf(1, 2, 3, 4, 5, 6)
        val pipelined = EditPipeline.apply(grid.copyOf(), 3, 2, EditRecipe(straightenDeg = 30.0))
        val standalone = straightenPixels(grid, 3, 2, 30.0)
        assertTrue(pipelined.pixels.contentEquals(standalone.pixels))
        assertEquals(standalone.width, pipelined.width)
    }

    @Test
    fun `crop is applied over the post-turn frame (the documented order)`() {
        // 4x3 grid [1..12]; turn once CCW -> 3x4 [4 8 12 / 3 7 11 / 2 6 10 /
        // 1 5 9]; then crop the right half of the TURNED frame -> the right
        // column: [12, 11, 10, 9] as a 1x4 grid.
        val grid = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val pipelined = EditPipeline.apply(
            grid, 4, 3,
            EditRecipe(quarterTurns = 1, crop = CropRect(0.5, 0.0, 1.0, 1.0)),
        )
        assertEquals(1, pipelined.width)
        assertEquals(4, pipelined.height)
        assertEquals(listOf(12, 11, 10, 9), pipelined.pixels.toList())
    }

    @Test
    fun `a tone-only recipe matches the composed lut path`() {
        val base = intArrayOf(0xFF0A64C8.toInt(), 0xFF808080.toInt(), 0xFFFA053C.toInt())
        val recipe = EditRecipe(brightness = 50)
        // The pipeline mutates its input in place --- the expected array is
        // snapshotted BEFORE the call, then driven by the standalone path.
        val expected = base.copyOf()
        val pipelined = EditPipeline.apply(base, 3, 1, recipe)
        PixelAdjuster.applyTo(expected, LutBuilder.build(brightness = 50, contrast = 0, exposure = 0, highlights = 0, shadows = 0))
        assertTrue(pipelined.pixels.contentEquals(expected))
    }

    @Test
    fun `a saturation-only recipe matches the standalone pixel stage`() {
        val base = intArrayOf(0xFFC86432.toInt())
        // Same in-place discipline: snapshot before the pipeline runs.
        val expected = base.copyOf()
        val pipelined = EditPipeline.apply(base, 1, 1, EditRecipe(saturation = 100))
        PixelOps.applySaturation(expected, 100)
        assertTrue(pipelined.pixels.contentEquals(expected))
    }

    // ---- EditGeometryPlan ----

    @Test
    fun `the plan agrees with the pipeline's pre-crop frame`() {
        val grid = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
        val recipe = EditRecipe(quarterTurns = 1, crop = CropRect(0.5, 0.0, 1.0, 1.0))
        val pipelined = EditPipeline.apply(grid, 4, 3, recipe)
        val plan = EditGeometryPlan.plan(recipe, 4, 3)
        // The plan's post-geometry frame is the pipeline's frame BEFORE the
        // crop; the crop rect over that frame is what the pipeline cut.
        assertEquals(3, plan.postGeometryWidth)
        assertEquals(4, plan.postGeometryHeight)
        assertEquals(2, plan.cropPixelRect.x0) // 0.5 * 3 rounds to 2 (of 3 wide)
        assertEquals(pipelined.width, plan.cropPixelRect.width)
        assertEquals(pipelined.height, plan.cropPixelRect.height)
    }

    @Test
    fun `the plan swaps dimensions only for odd turns`() {
        val straight = EditGeometryPlan.plan(EditRecipe(), 4, 3)
        assertEquals(4, straight.postGeometryWidth)
        assertEquals(3, straight.postGeometryHeight)
        val turned = EditGeometryPlan.plan(EditRecipe(quarterTurns = 1), 4, 3)
        assertEquals(3, turned.postGeometryWidth)
        assertEquals(4, turned.postGeometryHeight)
        val turnedTwice = EditGeometryPlan.plan(EditRecipe(quarterTurns = 2), 4, 3)
        assertEquals(4, turnedTwice.postGeometryWidth)
    }

    // ---- ExportFormat / ExportRequest ----

    @Test
    fun `the export format surface is the honest encodable set`() {
        // JPEG, PNG, WebP. HEIC is decode-only in the platform Bitmap ---
        // offering it would be a lie, so it is absent.
        assertEquals(3, ExportFormat.entries.size)
        assertEquals("image/jpeg", ExportFormat.JPEG.mimeType)
        assertEquals("image/png", ExportFormat.PNG.mimeType)
        assertEquals("image/webp", ExportFormat.WEBP.mimeType)
        assertEquals("jpg", ExportFormat.JPEG.extension)
        assertEquals("webp", ExportFormat.WEBP.extension)
        assertNull(ExportFormat.fromWire("HEIC"))
        assertEquals(ExportFormat.WEBP, ExportFormat.fromWire("WEBP"))
    }

    @Test
    fun `export requests validate their quality`() {
        ExportRequest(EditRecipe(), ExportFormat.JPEG, 95, MetadataPolicy.PRESERVE)
        try {
            ExportRequest(EditRecipe(), ExportFormat.JPEG, 0, MetadataPolicy.PRESERVE)
            throw AssertionError("quality 0 must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- ExportPlan ----

    @Test
    fun `insert values carry the format mime and the pending discipline`() {
        val values = ExportPlan.insertValues("a-edit.webp", ExportFormat.WEBP, api29Plus = true)
        assertEquals("a-edit.webp", values["_display_name"])
        assertEquals("image/webp", values["mime_type"])
        assertEquals(ExportPlan.RELATIVE_DIR, values["relative_path"])
        assertEquals(ExportPlan.IS_PENDING_TRUE, values["is_pending"])
        val preQ = ExportPlan.insertValues("a-edit.jpg", ExportFormat.JPEG, api29Plus = false)
        assertTrue(!preQ.containsKey("is_pending") && !preQ.containsKey("relative_path"))
        assertEquals(ExportFormat.JPEG, ExportPlan.DEFAULT_FORMAT)
        assertEquals(95, ExportPlan.DEFAULT_QUALITY)
    }

    @Test
    fun `display names follow the chosen format extension`() {
        assertEquals("IMG_2024-edit.png", ExportPlan.displayNameFor("IMG_2024.jpg", ExportFormat.PNG))
        assertEquals("photo-edit.webp", ExportPlan.displayNameFor("photo.heic", ExportFormat.WEBP))
        assertEquals("image-edit.jpg", ExportPlan.displayNameFor(null, ExportFormat.JPEG))
    }

    // ---- EditPipeline: the r33 LUT filter stage ----

    /** A size-2 all-red look: any input maps to (1, 0, 0). */
    private fun redLook(id: String): CubeLut =
        when (val parsed = CubeLutParser.parse("LUT_3D_SIZE 2\n" + (0 until 8).joinToString("\n") { "1 0 0" }, id)) {
            is CubeLutParser.Result.Valid -> parsed.lut
            is CubeLutParser.Result.Invalid -> throw AssertionError(parsed.reason)
        }

    @Test
    fun `a paired look applies over the graded pixels`() {
        // 2x1 grey grid; the look is id-matched at full intensity.
        val grey = 0xFF808080.toInt()
        val grid = intArrayOf(grey, grey)
        val recipe = EditRecipe(lut = LutSelection(lutId = "test-red", intensity = 100))
        val result = EditPipeline.apply(grid, 2, 1, recipe, filter = redLook("test-red"))
        assertEquals(0xFFFF0000.toInt(), result.pixels[0])
        assertEquals(0xFFFF0000.toInt(), result.pixels[1])
    }

    @Test
    fun `a mismatched or missing look degrades to no-look, never a crash`() {
        val grey = 0xFF808080.toInt()
        val base = intArrayOf(grey, grey)
        val recipe = EditRecipe(lut = LutSelection(lutId = "test-red", intensity = 100))

        // The id does not pair up with the resolved look.
        val mismatched = EditPipeline.apply(base.copyOf(), 2, 1, recipe, filter = redLook("a-different-id"))
        assertTrue(mismatched.pixels.contentEquals(base))

        // No look resolved at all (the call-site default).
        val unresolved = EditPipeline.apply(base.copyOf(), 2, 1, recipe)
        assertTrue(unresolved.pixels.contentEquals(base))
    }

    @Test
    fun `a dialed-out look takes the identity fast path`() {
        // Intensity 0 with everything else identity: the exact-input
        // fast path --- the same array instance comes back.
        val grid = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())
        val recipe = EditRecipe(lut = LutSelection(lutId = "test-red", intensity = 0))
        val result = EditPipeline.apply(grid, 2, 1, recipe, filter = redLook("test-red"))
        assertTrue(result.pixels.contentEquals(grid))
    }

    @Test
    fun `the look applies after the HSL stage and before vignette`() {
        // Stage-order pin (the documented 12-stage order): the look
        // grades the GRADED pixels. A half-intensity look over a pure
        // green input through a look whose table is all-red: the colour
        // stages leave (0,255,0) untouched (all zero), so the result is
        // the straight half-lerp (128, 128, 0) --- proving the look ran
        // at its own stage with the pre-look colour as its input.
        val green = 0xFF00FF00.toInt()
        val grid = intArrayOf(green)
        val recipe = EditRecipe(lut = LutSelection(lutId = "test-red", intensity = 50))
        val result = EditPipeline.apply(grid, 1, 1, recipe, filter = redLook("test-red"))
        assertEquals(128, (result.pixels[0] shr 16) and 0xFF)
        assertEquals(128, (result.pixels[0] shr 8) and 0xFF)
        assertEquals(0, result.pixels[0] and 0xFF)
    }
}
