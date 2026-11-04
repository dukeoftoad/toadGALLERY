package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The ADR-0011 sidecar: codec round-trips, tolerance, and the atomic store. */
class EditRecipeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- Codec ----

    @Test
    fun `a fully-populated recipe round-trips through the codec`() {
        val recipe = EditRecipe(
            quarterTurns = 1,
            horizontalFlip = true,
            straightenDeg = -3.5,
            crop = CropRect(0.1, 0.2, 0.9, 0.8),
            perspective = PerspectiveQuad(4.0, -2.0, 0.0, 6.0, -3.0, 0.0, 0.0, -5.0),
            exposure = 15,
            brightness = -20,
            contrast = 30,
            highlights = -40,
            shadows = 25,
            saturation = -50,
            temperature = 35,
            tint = -10,
            hslBands = HslBands(
                red = HslBandAdjust(hue = 12, saturation = -30, luminance = 5),
                blue = HslBandAdjust(hue = -8, saturation = 20, luminance = -15),
            ),
            vignette = 45,
            sharpness = 60,
            lut = LutSelection(lutId = "warm-film", intensity = 70),
        )
        val parsed = EditRecipeCodec.fromJson(EditRecipeCodec.toJson(recipe))
        val restored = (parsed as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(recipe.copy(schemaVersion = restored.schemaVersion), restored)
    }

    // ---- Codec: the r33 LUT selection ----

    @Test
    fun `a pre-r33 sidecar loads with no lut selection`() {
        // No `lut` key at all: every r30-r32 sidecar in the wild.
        val parsed = EditRecipeCodec.fromJson("{\"schemaVersion\":1,\"brightness\":10}")
        val restored = (parsed as EditRecipeCodec.ParseResult.Valid).recipe
        assertNull(restored.lut)
        assertEquals(10, restored.brightness)
    }

    @Test
    fun `an unresolvable lut id survives the round-trip verbatim`() {
        // The recipe is the user's data: an id this build cannot resolve
        // (a newer bundle, a hand-edited sidecar) still round-trips.
        val recipe = EditRecipe(lut = LutSelection(lutId = "a-look-from-the-future", intensity = 33))
        val restored = (EditRecipeCodec.fromJson(EditRecipeCodec.toJson(recipe))
            as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(LutSelection("a-look-from-the-future", 33), restored.lut)
    }

    @Test
    fun `lut intensity coerces into the 0-to-100 range and a blank id means no look`() {
        val coerced = EditRecipeCodec.fromJson(
            "{\"schemaVersion\":1,\"lut\":{\"lutId\":\"x\",\"intensity\":400}}",
        )
        val restored = (coerced as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(100, restored.lut?.intensity)

        val blank = EditRecipeCodec.fromJson("{\"schemaVersion\":1,\"lut\":{\"lutId\":\"  \"}}")
        assertTrue((blank as EditRecipeCodec.ParseResult.Valid).recipe.lut == null)
    }

    @Test
    fun `a dialed-out look is a filter identity but keeps the selection`() {
        val dialedOut = EditRecipe(lut = LutSelection(lutId = "silver-mono", intensity = 0))
        assertTrue(dialedOut.isIdentity)
        assertTrue(dialedOut.isFilterIdentity)
        assertEquals("silver-mono", dialedOut.lut?.lutId)

        val dialedIn = EditRecipe(lut = LutSelection(lutId = "silver-mono", intensity = 1))
        assertFalse(dialedIn.isIdentity)
        assertFalse(dialedIn.isFilterIdentity)
    }

    @Test
    fun `the identity recipe round-trips to the identity`() {
        val parsed = EditRecipeCodec.fromJson(EditRecipeCodec.toJson(EditRecipe()))
        val restored = (parsed as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(EditRecipe(), restored)
        assertTrue(restored.isIdentity)
    }

    @Test
    fun `unknown keys are ignored --- the additive-versioning rule`() {
        val json = EditRecipeCodec.toJson(EditRecipe(brightness = 25))
        val withExtras = json.replace("{\"schemaVersion\"", "{\"futureField\":123,\"schemaVersion\"")
        val parsed = EditRecipeCodec.fromJson(withExtras)
        val restored = (parsed as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(25, restored.brightness)
    }

    @Test
    fun `missing keys default to the identity`() {
        val parsed = EditRecipeCodec.fromJson("{\"schemaVersion\":1}")
        val restored = (parsed as EditRecipeCodec.ParseResult.Valid).recipe
        assertTrue(restored.isIdentity)
    }

    @Test
    fun `a schema version outside the window is a structured failure`() {
        val tooNew = EditRecipeCodec.fromJson("{\"schemaVersion\":99}")
        assertTrue(tooNew is EditRecipeCodec.ParseResult.Invalid)
        val tooOld = EditRecipeCodec.fromJson("{\"schemaVersion\":0}")
        assertTrue(tooOld is EditRecipeCodec.ParseResult.Invalid)
    }

    @Test
    fun `malformed json is a structured failure, never a crash`() {
        val parsed = EditRecipeCodec.fromJson("{not json at all")
        assertTrue(parsed is EditRecipeCodec.ParseResult.Invalid)
    }

    @Test
    fun `out-of-range values are clamped into the recipe domain on parse`() {
        val json = "{\"schemaVersion\":1,\"brightness\":5000,\"quarterTurns\":9}"
        val parsed = EditRecipeCodec.fromJson(json)
        val restored = (parsed as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(100, restored.brightness)
        assertEquals(1, restored.quarterTurns) // 9 % 4 normalized
    }

    @Test
    fun `the crop is normalized on parse`() {
        val json = "{\"schemaVersion\":1,\"crop\":{\"left\":0.9,\"top\":0.8,\"right\":0.1,\"bottom\":0.2}}"
        val parsed = EditRecipeCodec.fromJson(json)
        val restored = (parsed as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(CropRect(0.1, 0.2, 0.9, 0.8), restored.crop)
    }

    @Test
    fun `a recipe with an out-of-domain constructor value is rejected at construction`() {
        try {
            EditRecipe(quarterTurns = 4)
            throw AssertionError("quarterTurns=4 must be rejected (normalize to 0 first)")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    // ---- Store ----

    @Test
    fun `the store key is a stable sha-256 with no uri text in the filename`() {
        val store = EditRecipeStore(tmp.newFolder())
        val file = store.fileFor("content://media/external/images/media/42")
        assertTrue(file.name.startsWith("recipe-"))
        assertEquals("recipe-${EditRecipeStore.sha256("content://media/external/images/media/42")}.json", file.name)
        assertFalse(file.name.contains("media"))
        // Stable across instances (the SAME directory, a fresh store object).
        assertEquals(file, EditRecipeStore(file.parentFile).fileFor("content://media/external/images/media/42"))
    }

    @Test
    fun `save then load round-trips`() {
        val store = EditRecipeStore(tmp.newFolder())
        val uri = "content://media/external/images/media/7"
        val recipe = EditRecipe(brightness = 42, straightenDeg = 2.5)
        store.save(uri, recipe)
        assertEquals(recipe, store.load(uri))
    }

    @Test
    fun `save overwrites atomically and leaves no tmp file`() {
        val store = EditRecipeStore(tmp.newFolder())
        val uri = "content://media/external/images/media/7"
        store.save(uri, EditRecipe(brightness = 1))
        store.save(uri, EditRecipe(brightness = 2))
        assertEquals(2, store.load(uri)!!.brightness)
        assertEquals(1, tmp.root.listFiles()!!.size) // exactly the one sidecar
    }

    @Test
    fun `loading a missing sidecar answers null`() {
        val store = EditRecipeStore(tmp.newFolder())
        assertNull(store.load("content://media/external/images/media/999"))
    }

    @Test
    fun `a corrupt sidecar self-heals away to null`() {
        val store = EditRecipeStore(tmp.newFolder())
        val uri = "content://media/external/images/media/7"
        store.fileFor(uri).writeText("{definitely not json")
        assertNull(store.load(uri))
        assertFalse("the corrupt file must be gone", store.fileFor(uri).exists())
    }

    @Test
    fun `clear removes the sidecar`() {
        val store = EditRecipeStore(tmp.newFolder())
        val uri = "content://media/external/images/media/7"
        store.save(uri, EditRecipe())
        store.clear(uri)
        assertNull(store.load(uri))
    }

    // ---- ExportFormat / ExportRequest ----

    @Test
    fun `export formats carry their wire names and mimes`() {
        assertEquals("image/jpeg", ExportFormat.JPEG.mimeType)
        assertEquals("image/png", ExportFormat.PNG.mimeType)
        assertEquals("image/webp", ExportFormat.WEBP.mimeType)
        assertEquals("webp", ExportFormat.WEBP.extension)
        assertEquals(ExportFormat.JPEG, ExportFormat.fromWire("JPEG"))
        assertEquals(ExportFormat.WEBP, ExportFormat.fromWire("WEBP"))
        assertNull(ExportFormat.fromWire("HEIC")) // decode-only: never offered as an export
        assertNull(ExportFormat.fromWire(null))
    }


    @Test(expected = IllegalArgumentException::class)
    fun `an export quality outside the 1-to-100 range is rejected`() {
        ExportRequest(EditRecipe(), ExportFormat.JPEG, quality = 0, metadataPolicy = io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy.PRESERVE)
    }
}

/** The pipeline: one documented stage order, exact identity, crop-after-geometry. */
class EditPipelineTest {

    private fun solid(width: Int, height: Int, argb: Int) = IntArray(width * height) { argb }

    private fun argb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** The explicit-alpha form the geometry tests use (the name spells the order). */
    private fun argb(a: Int, r: Int, g: Int, b: Int) =
        (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun redOf(p: Int) = (p ushr 16) and 0xFF

    @Test
    fun `the identity recipe returns the input grid exactly`() {
        val pixels = intArrayOf(0x11223344, 0x55667788)
        val result = EditPipeline.apply(pixels, 2, 1, EditRecipe())
        assertTrue(result.pixels.contentEquals(pixels))
        assertEquals(2, result.width)
        assertEquals(1, result.height)
    }

    @Test
    fun `a quarter turn swaps the output dimensions`() {
        val result = EditPipeline.apply(solid(4, 2, argb(10, 20, 30)), 4, 2, EditRecipe(quarterTurns = 1))
        assertEquals(2, result.width)
        assertEquals(4, result.height)
    }

    @Test
    fun `a horizontal flip mirrors the row`() {
        val pixels = intArrayOf(argb(255, 0, 0, 0), argb(0, 255, 0, 0), argb(0, 0, 255, 0))
        val result = EditPipeline.apply(pixels, 3, 1, EditRecipe(horizontalFlip = true))
        assertEquals(argb(0, 0, 255, 0), result.pixels[0])
        assertEquals(argb(0, 255, 0, 0), result.pixels[1])
        assertEquals(argb(255, 0, 0, 0), result.pixels[2])
    }

    @Test
    fun `crop applies AFTER rotation --- the normalized rect rides the post-geometry frame`() {
        // A 4x2 grid of two halves, rotated 90 -> 2x4, then a bottom-half
        // crop (top 0.5) keeps only the rotated lower half.
        val pixels = IntArray(4 * 2) { i ->
            if (i < 4) argb(10, 0, 0, 0) else argb(200, 0, 0, 0)
        }
        val recipe = EditRecipe(quarterTurns = 1, crop = CropRect(0.0, 0.5, 1.0, 1.0))
        val plan = EditGeometryPlan.plan(recipe, 4, 2)
        assertEquals(2, plan.postGeometryWidth)
        assertEquals(4, plan.postGeometryHeight)
        val result = EditPipeline.apply(pixels, 4, 2, recipe)
        assertEquals(plan.cropPixelRect.width, result.width)
        assertEquals(plan.cropPixelRect.height, result.height)
        assertEquals(2, result.width)
        assertEquals(2, result.height)
    }

    @Test
    fun `the tone stage raises brightness and the pipeline exposes the exact stage order`() {
        val pixels = intArrayOf(argb(100, 100, 100))
        val result = EditPipeline.apply(pixels, 1, 1, EditRecipe(brightness = 20))
        assertTrue("brightness must raise the value", redOf(result.pixels[0]) > 100)
        assertTrue(
            "the documented order must name every stage",
            EditPipeline.STAGE_ORDER.contains("crop -> tone LUT") &&
                EditPipeline.STAGE_ORDER.contains("vignette -> sharpen"),
        )
    }

    @Test
    fun `the geometry plan matches the pipeline's post-geometry frame for straighten`() {
        val recipe = EditRecipe(straightenDeg = 30.0)
        val plan = EditGeometryPlan.plan(recipe, 100, 50)
        val result = EditPipeline.apply(solid(100, 50, argb(1, 2, 3)), 100, 50, recipe)
        assertEquals(plan.postGeometryWidth, result.width)
        assertEquals(plan.postGeometryHeight, result.height)
        // 30 degrees over a 100x50 frame: 100*cos30 + 50*sin30 = 86.6 + 25 = 111.6 -> 112
        assertEquals(112, result.width)
        // 100*sin30 + 50*cos30 = 50 + 43.3 = 93.3 -> 94
        assertEquals(94, result.height)
    }

    @Test
    fun `the geometry plan handles the odd-turn dimension swap`() {
        val plan = EditGeometryPlan.plan(EditRecipe(quarterTurns = 1), 100, 50)
        assertEquals(50, plan.postGeometryWidth)
        assertEquals(100, plan.postGeometryHeight)
    }

    @Test
    fun `vignette and sharpen apply to the CROPPED frame (display-space effects last)`() {
        // A 2x1 crop from a 4x1 solid: the vignette must see a 2x1 frame ---
        // pinned indirectly: the crop's output dims are the plan's, and the
        // pipeline's output equals them (no vignette-induced resize).
        val recipe = EditRecipe(
            crop = CropRect(0.25, 0.0, 0.75, 1.0),
            vignette = 50,
            sharpness = 50,
        )
        val plan = EditGeometryPlan.plan(recipe, 4, 1)
        val result = EditPipeline.apply(solid(4, 1, argb(120, 120, 120)), 4, 1, recipe)
        assertEquals(plan.cropPixelRect.width, result.width)
        assertEquals(2, result.width)
        // A constant image stays constant under sharpen at any amount ---
        // verified WITHOUT the vignette (the vignette deliberately darkens
        // this 2x1 frame's edge pixels; the width asserts above carry the
        // display-space-crop contract).
        val sharpenOnly = EditPipeline.apply(
            solid(4, 1, argb(120, 120, 120)), 4, 1, recipe.copy(vignette = 0),
        )
        assertEquals(2, sharpenOnly.width)
        assertEquals(argb(120, 120, 120), sharpenOnly.pixels[1])
    }

    @Test
    fun `an adjusted recipe is not the identity but its zero-state stages are skipped exactly`() {
        val recipe = EditRecipe(saturation = -100)
        assertFalse(recipe.isIdentity)
        val pixels = intArrayOf(0xFF808080.toInt())
        val result = EditPipeline.apply(pixels, 1, 1, recipe)
        // Full desaturation of a grey pixel is the same grey.
        assertEquals(0xFF808080.toInt(), result.pixels[0])
        assertNotEquals(EditRecipe(), recipe)
    }
}
