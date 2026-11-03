package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LUT adjustment math + the rotation grid math (the prototype's real "modify" step). */
class PixelAdjusterTest {

    @Test
    fun `identity parameters leave every channel value untouched`() {
        val lut = PixelAdjuster.buildLut(0, 0)
        for (v in 0..255) {
            assertEquals("v=$v", v, lut[v])
        }
    }

    @Test
    fun `positive brightness raises values and clamps at 255`() {
        val lut = PixelAdjuster.buildLut(100, 0)
        // +100 brightness = +255 shift: v=0 lands exactly on white...
        assertEquals(255, lut[0])
        assertEquals(255, lut[10])
        // ...and everything from there saturates.
        for (v in 0..255) assertEquals(255, lut[v])
    }

    @Test
    fun `negative brightness lowers values and clamps at 0`() {
        val lut = PixelAdjuster.buildLut(-100, 0)
        for (v in 0..255) assertEquals(0, lut[v])
    }

    @Test
    fun `moderate brightness shifts without clipping`() {
        val lut = PixelAdjuster.buildLut(50, 0)
        // 50 * 2.55 = 127.5 -> v=10 -> 137.5 -> rounds to 138.
        assertEquals(138, lut[10])
        // v=100 -> 227.5 -> 228.
        assertEquals(228, lut[100])
    }

    @Test
    fun `contrast pivots at mid-grey`() {
        val lut = PixelAdjuster.buildLut(0, 80)
        // The pivot survives every contrast factor (up to rounding).
        assertTrue("128 maps near itself", Math.abs(lut[128] - 128) <= 1)
        // Positive contrast widens: 0 darkens, 255 brightens.
        assertTrue(lut[0] < 64)
        assertTrue(lut[255] > 191)
    }

    @Test
    fun `negative contrast narrows toward mid-grey`() {
        val lut = PixelAdjuster.buildLut(0, -100)
        assertTrue("extreme negative contrast flattens toward 128", Math.abs(lut[0] - 128) < 64)
        assertTrue(Math.abs(lut[255] - 128) < 64)
    }

    @Test
    fun `brightness composes before contrast`() {
        // +50 brightness then +50 contrast differs from either alone; the
        // exact composition is pinned by a hand-computed value:
        // v=10 -> brightened 137.5 -> factor(+50) = (259*382.5)/(255*131.5) ~ 2.958
        // -> (137.5-128)*2.958+128 = 156.1 -> 156.
        val lut = PixelAdjuster.buildLut(50, 50)
        assertEquals(156, lut[10])
    }

    @Test
    fun `applyTo preserves alpha and maps channels through the lut`() {
        val lut = IntArray(256) { v -> if (v == 10) 200 else if (v == 20) 30 else v }
        val pixel = (0x80 shl 24) or (10 shl 16) or (20 shl 8) or 10
        val pixels = intArrayOf(pixel)
        PixelAdjuster.applyTo(pixels, lut)
        val out = pixels[0]
        assertEquals(0x80, (out ushr 24) and 0xFF) // alpha untouched
        assertEquals(200, (out ushr 16) and 0xFF)
        assertEquals(30, (out ushr 8) and 0xFF)
        assertEquals(200, out and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `brightness outside the range is rejected`() {
        PixelAdjuster.buildLut(101, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `contrast outside the range is rejected`() {
        PixelAdjuster.buildLut(0, -101)
    }

    // ---- Rotation ----

    @Test
    fun `zero turns return the input grid`() {
        val pixels = intArrayOf(1, 2, 3, 4, 5, 6)
        val rotated = PixelAdjuster.rotateQuarterTurns(pixels, 3, 2, 0)
        assertEquals(pixels, rotated.pixels)
        assertEquals(3, rotated.width)
        assertEquals(2, rotated.height)
    }

    @Test
    fun `one turn rotates counter-clockwise and swaps dimensions`() {
        // 3x2 grid: [1 2 3 / 4 5 6] -> CCW: [3 6 / 2 5 / 1 4] (2x3).
        val rotated = PixelAdjuster.rotateQuarterTurns(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, 1)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertEquals(listOf(3, 6, 2, 5, 1, 4), rotated.pixels.toList())
    }

    @Test
    fun `two turns rotate 180 degrees`() {
        val rotated = PixelAdjuster.rotateQuarterTurns(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, 2)
        assertEquals(3, rotated.width)
        assertEquals(listOf(6, 5, 4, 3, 2, 1), rotated.pixels.toList())
    }

    @Test
    fun `three turns rotate clockwise`() {
        // [1 2 3 / 4 5 6] -> 90° CW: [4 1 / 5 2 / 6 3] (2x3).
        val rotated = PixelAdjuster.rotateQuarterTurns(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, 3)
        assertEquals(2, rotated.width)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), rotated.pixels.toList())
    }

    @Test
    fun `four turns are the identity`() {
        val pixels = intArrayOf(1, 2, 3, 4, 5, 6)
        val rotated = PixelAdjuster.rotateQuarterTurns(pixels, 3, 2, 4)
        assertEquals(pixels.toList(), rotated.pixels.toList())
    }

    @Test
    fun `negative turns normalize`() {
        val viaNegative = PixelAdjuster.rotateQuarterTurns(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, -3)
        val viaPositive = PixelAdjuster.rotateQuarterTurns(intArrayOf(1, 2, 3, 4, 5, 6), 3, 2, 1)
        assertTrue(viaNegative.pixels.contentEquals(viaPositive.pixels))
    }

    @Test
    fun `clamp8 matches the documented rule`() {
        assertEquals(0, PixelAdjuster.clamp8(-1))
        assertEquals(0, PixelAdjuster.clamp8(0))
        assertEquals(255, PixelAdjuster.clamp8(255))
        assertEquals(255, PixelAdjuster.clamp8(300))
        assertEquals(42, PixelAdjuster.clamp8(42))
    }
}

/** The undo/redo session stack over whole recipes (contract 4.2). */
class EditSessionStateTest {

    private fun recipe(brightness: Int = 0, contrast: Int = 0, quarterTurns: Int = 0) =
        EditRecipe(brightness = brightness, contrast = contrast, quarterTurns = quarterTurns)

    @Test
    fun `a fresh session is at the identity and cannot undo`() {
        val session = EditSessionState()
        assertTrue(session.current.isIdentity)
        assertFalse(session.canUndo)
        assertFalse(session.canRedo)
    }

    @Test
    fun `commit then undo returns to identity then redo forward`() {
        val session = EditSessionState()
        session.commit(recipe(brightness = 40))
        assertTrue(session.canUndo)
        assertEquals(recipe(brightness = 40), session.undo())
        assertFalse(session.canUndo)
        assertTrue(session.current.isIdentity)
        assertTrue(session.canRedo)
        assertEquals(recipe(brightness = 40), session.redo())
        assertFalse(session.canRedo)
    }

    @Test
    fun `committing after undo truncates the redo tail`() {
        val session = EditSessionState()
        session.commit(recipe(brightness = 40))
        session.undo()
        session.commit(recipe(contrast = -30))
        assertFalse(session.canRedo)
        assertEquals(recipe(contrast = -30), session.current)
        // Undo goes to identity, NOT to the abandoned brightness=40 branch.
        assertEquals(EditRecipe(), session.undo())
    }

    @Test
    fun `depth is bounded and the oldest entries fall off`() {
        val session = EditSessionState(maxDepth = 4)
        for (b in 1..10) {
            session.commit(recipe(brightness = b))
        }
        assertEquals(recipe(brightness = 10), session.current)
        // Only maxDepth snapshots remain: undo walks 9, 8, 7, 6 --- then stops.
        assertEquals(9, session.undo()!!.brightness)
        assertEquals(8, session.undo()!!.brightness)
        assertEquals(7, session.undo()!!.brightness)
        assertEquals(6, session.undo()!!.brightness)
        assertFalse(session.canUndo)
    }

    @Test
    fun `identity detection covers rotation and the colour grade`() {
        assertFalse(recipe(quarterTurns = 2).isIdentity)
        assertFalse(EditRecipe(sharpness = 1).isIdentity)
        assertFalse(
            EditRecipe(hslBands = HslBands(red = HslBandAdjust(hue = 5))).isIdentity,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recipes outside the parameter range are rejected`() {
        EditSessionState().commit(EditRecipe(brightness = 1000))
    }
}

/** The save-copy export plan (pure policy). */
class ExportPlanTest {

    @Test
    fun `display names derive with the -edit suffix and the format extension`() {
        assertEquals(
            "IMG_2024-edit.jpg",
            ExportPlan.displayNameFor("IMG_2024.png", ExportFormat.JPEG),
        )
        assertEquals(
            "photo-edit.png",
            ExportPlan.displayNameFor("photo.jpeg", ExportFormat.PNG),
        )
        assertEquals(
            "IMG_2024-edit.heic",
            ExportPlan.displayNameFor("IMG_2024", ExportFormat.HEIC), // no extension
        )
    }

    @Test
    fun `hostile display names are sanitized`() {
        assertEquals(
            "evil-edit.jpg",
            ExportPlan.displayNameFor("../../evil.jpg", ExportFormat.JPEG),
        )
        assertEquals("x-edit.jpg", ExportPlan.displayNameFor("x:*?.jpg", ExportFormat.JPEG))
        assertEquals("image-edit.jpg", ExportPlan.displayNameFor("   ", ExportFormat.JPEG))
        assertEquals("image-edit.jpg", ExportPlan.displayNameFor(null, ExportFormat.JPEG))
    }

    @Test
    fun `api 29+ inserts carry the pending discipline, the relative path and the format mime`() {
        val values = ExportPlan.insertValues("a-edit.jpg", ExportFormat.JPEG, api29Plus = true)
        assertEquals("a-edit.jpg", values["_display_name"])
        assertEquals("image/jpeg", values["mime_type"])
        assertEquals("Pictures/toadGALLERY Edit", values["relative_path"])
        assertEquals(ExportPlan.IS_PENDING_TRUE, values["is_pending"])
        val heic = ExportPlan.insertValues("a-edit.heic", ExportFormat.HEIC, api29Plus = true)
        assertEquals("image/heic", heic["mime_type"])
        val finalize = ExportPlan.finalizeValues(api29Plus = true)
        assertEquals(ExportPlan.IS_PENDING_FALSE, finalize["is_pending"])
    }

    @Test
    fun `pre-q inserts carry no pending or path columns`() {
        val values = ExportPlan.insertValues("a-edit.jpg", ExportFormat.JPEG, api29Plus = false)
        assertFalse(values.containsKey("is_pending"))
        assertFalse(values.containsKey("relative_path"))
        assertTrue(ExportPlan.finalizeValues(api29Plus = false).isEmpty())
    }

    @Test
    fun `failure recovery cancels the pending row on q plus and leaves it on pre-q`() {
        assertEquals(ExportPlan.Recovery.CANCEL_PENDING_ROW, ExportPlan.failureRecovery(api29Plus = true))
        assertEquals(ExportPlan.Recovery.LEAVE_ROW, ExportPlan.failureRecovery(api29Plus = false))
    }

    @Test
    fun `the result echoes the applied policy wire name`() {
        assertEquals("preserve", ExportPlan.resultPolicy(MetadataPolicy.PRESERVE))
        assertEquals("strip_location", ExportPlan.resultPolicy(MetadataPolicy.STRIP_LOCATION))
        assertEquals("strip_all", ExportPlan.resultPolicy(MetadataPolicy.STRIP_ALL))
    }
}

/** The EXIF metadata policy plan (contract 4.4). */
class MetadataPolicyApplierTest {

    @Test
    fun `preserve copies everything`() {
        for (tag in listOf("GPSLatitude", "Make", "DateTimeOriginal", "FNumber")) {
            assertEquals(MetadataPolicyApplier.TagDecision.Copy, MetadataPolicyApplier.decide(MetadataPolicy.PRESERVE, tag))
        }
    }

    @Test
    fun `strip_location nulls every GPS tag and copies the rest`() {
        for (tag in MetadataPolicyApplier.LOCATION_TAGS) {
            assertEquals(
                "GPS tag '$tag' must strip",
                MetadataPolicyApplier.TagDecision.Strip,
                MetadataPolicyApplier.decide(MetadataPolicy.STRIP_LOCATION, tag),
            )
        }
        assertEquals(MetadataPolicyApplier.TagDecision.Copy, MetadataPolicyApplier.decide(MetadataPolicy.STRIP_LOCATION, "Make"))
        assertEquals(MetadataPolicyApplier.TagDecision.Copy, MetadataPolicyApplier.decide(MetadataPolicy.STRIP_LOCATION, "DateTimeOriginal"))
    }

    @Test
    fun `strip_all strips everything`() {
        for (tag in listOf("GPSLatitude", "Make", "DateTimeOriginal")) {
            assertEquals(MetadataPolicyApplier.TagDecision.Strip, MetadataPolicyApplier.decide(MetadataPolicy.STRIP_ALL, tag))
        }
    }

    @Test
    fun `location removal is narrated only for the stripping policies`() {
        assertFalse(MetadataPolicyApplier.removedLocation(MetadataPolicy.PRESERVE))
        assertTrue(MetadataPolicyApplier.removedLocation(MetadataPolicy.STRIP_LOCATION))
        assertTrue(MetadataPolicyApplier.removedLocation(MetadataPolicy.STRIP_ALL))
    }
}
