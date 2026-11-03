package io.github.dukeoftoad.gallery.edit

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The versioned JSON sidecar recipe (r30; ADR-0011): round-trips, tolerant
 * loads, corrupt-file rejection, and the atomic store. Runs on the JVM
 * against the REAL org.json (test classpath only --- the release APK ships
 * the platform org.json; the same classpath-only convention as the
 * gallery's r23 JSON tests).
 */
class EditRecipeTest {

    private lateinit var dir: File
    private lateinit var store: SidecarStore

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sidecars").toFile()
        store = SidecarStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a full parameter set round-trips exactly`() {
        val params = EditParameters(
            brightness = 12, contrast = -30, exposure = 40, highlights = -50, shadows = 60,
            saturation = 25, temperature = -10, tint = 15, vignette = -35, sharpness = 45,
            hsl = HslBands(
                red = HslBandAdjust(hue = 5, saturation = 10, luminance = -20),
                blue = HslBandAdjust(hue = -12, saturation = 30),
            ),
            quarterTurns = 2, straightenDeg = 7.5, flipH = true, flipV = false,
            crop = CropRect(0.1, 0.2, 0.8, 0.9),
            perspective = PerspectiveQuad(3.0, -2.0, 1.0, 0.0, 0.0, 2.0, -1.0, 0.5),
        )
        val json = EditRecipe.toJson(params, "content://media/external/images/media/42")
        val loaded = EditRecipe.fromJson(json)
        assertEquals(params, loaded)
    }

    @Test
    fun `the identity round-trips`() {
        val json = EditRecipe.toJson(EditParameters(), null)
        assertEquals(EditParameters(), EditRecipe.fromJson(json))
    }

    @Test
    fun `the source uri rides along`() {
        val json = EditRecipe.toJson(EditParameters(brightness = 5), "content://media/external/images/media/7")
        assertEquals("content://media/external/images/media/7", json.opt("source"))
        val back = EditRecipe.fromJson(json)!!
        // The source is recipe metadata, not a parameter: reloading keeps
        // the parameters; the caller matches sidecars by URI separately.
        assertEquals(5, back.brightness)
    }

    @Test
    fun `a null crop serializes as json null and loads back as null`() {
        val json = EditRecipe.toJson(EditParameters(), null)
        assertEquals(JSONObject.NULL, json.getJSONObject("geometry").get("crop"))
        assertNull(EditRecipe.fromJson(json)!!.crop)
    }

    @Test
    fun `missing keys fall back to defaults (tolerant load)`() {
        val minimal = JSONObject()
            .put("recipe_version", 1)
        assertEquals(EditParameters(), EditRecipe.fromJson(minimal))
    }

    @Test
    fun `an unknown recipe version rejects the file`() {
        val future = JSONObject().put("recipe_version", 99)
        assertNull(EditRecipe.fromJson(future))
    }

    @Test
    fun `an out-of-range value rejects the file (never a poisoned session)`() {
        val hostile = EditRecipe.toJson(EditParameters(), null)
        hostile.getJSONObject("adjust").put("brightness", 1000)
        assertNull(EditRecipe.fromJson(hostile))
        val hostile2 = EditRecipe.toJson(EditParameters(), null)
        hostile2.getJSONObject("geometry").put("straighten_deg", 90.0)
        assertNull(EditRecipe.fromJson(hostile2))
    }

    @Test
    fun `structurally invalid json rejects the file`() {
        // An empty object parses but fails the version check.
        assertNull(EditRecipe.fromJson(JSONObject()))
        // A non-object "adjust" value degrades to defaults (optJSONObject
        // tolerance), NOT a rejection --- the documented load contract.
        val bad = EditRecipe.toJson(EditParameters(), null)
        bad.remove("adjust")
        bad.put("adjust", "nope")
        assertEquals(EditParameters(), EditRecipe.fromJson(bad))
        // An "hsl" entry holding nonsense numbers rejects via validate().
        val hostileHsl = EditRecipe.toJson(EditParameters(), null)
        hostileHsl.getJSONObject("hsl").getJSONObject("red").put("hue", 999)
        assertNull(EditRecipe.fromJson(hostileHsl))
    }

    // ---- The store ----

    @Test
    fun `the store saves, loads and round-trips a recipe`() {
        val source = "content://media/external/images/media/99"
        val params = EditParameters(brightness = 33, contrast = -11, quarterTurns = 1)
        val ok = store.save(source, EditRecipe.toJson(params, source))
        assertTrue(ok)
        assertEquals(params, store.loadRecipe(source))
    }

    @Test
    fun `the store keys distinct sources to distinct files`() {
        val a = "content://media/external/images/media/1"
        val b = "content://media/external/images/media/2"
        store.save(a, EditRecipe.toJson(EditParameters(brightness = 1), a))
        store.save(b, EditRecipe.toJson(EditParameters(brightness = 2), b))
        assertEquals(1, store.loadRecipe(a)!!.brightness)
        assertEquals(2, store.loadRecipe(b)!!.brightness)
        assertNotEquals(store.fileFor(a).name, store.fileFor(b).name)
    }

    @Test
    fun `an absent sidecar loads as null`() {
        assertNull(store.loadRecipe("content://media/external/images/media/404"))
    }

    @Test
    fun `a corrupt sidecar loads as null, not a crash`() {
        val source = "content://media/external/images/media/13"
        store.save(source, EditRecipe.toJson(EditParameters(), source))
        // Corrupt the file on disk.
        store.fileFor(source).writeText("{ broken")
        assertNull(store.load(source))
        assertNull(store.loadRecipe(source))
    }

    @Test
    fun `saving over an existing sidecar replaces it atomically`() {
        val source = "content://media/external/images/media/21"
        store.save(source, EditRecipe.toJson(EditParameters(brightness = 10), source))
        assertTrue(store.save(source, EditRecipe.toJson(EditParameters(brightness = 20), source)))
        assertEquals(20, store.loadRecipe(source)!!.brightness)
        // No .tmp litter left behind.
        val tmpFiles = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue(tmpFiles.isEmpty())
    }

    @Test
    fun `sha256 keys are stable and hex`() {
        val hex = SidecarStore.sha256Hex("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
        )
        assertEquals(hex, SidecarStore.sha256Hex("abc"))
        assertTrue(hex.all { it in "0123456789abcdef" })
    }
}
