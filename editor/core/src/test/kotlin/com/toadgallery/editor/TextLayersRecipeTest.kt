package com.toadgallery.editor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r34 text layers on the ADR-0011 sidecar wire (the contract's
 * round-trip set): a pre-r34 sidecar loads clean, the full round-trip is
 * lossless, an unresolvable fontId round-trips verbatim + degrades at
 * render, and the tolerant-read rules (clamps, skips, defaults) hold ---
 * a hand-edited sidecar never loses its whole session.
 */
class TextLayersRecipeTest {

    private fun valid(json: String): EditRecipe =
        (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe

    private fun layerJson(
        id: String = "t1",
        text: String = "Hello",
        fontId: String = "fira-sans",
        sizePct: Int = 20,
        color: String = "FFFFFFFF",
        opacity: Int = 100,
        rotationDeg: Int = 0,
        centerX: Double = 0.5,
        centerY: Double = 0.5,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("fontId", fontId)
        .put("sizePct", sizePct)
        .put("color", color)
        .put("opacity", opacity)
        .put("rotationDeg", rotationDeg)
        .put("centerX", centerX)
        .put("centerY", centerY)

    private fun recipeWithLayers(vararg layers: Any): String {
        val arr = JSONArray()
        layers.forEach { arr.put(it) }
        return JSONObject().put("schemaVersion", 1).put("textLayers", arr).toString()
    }

    // ---- The absent key (every pre-r34 sidecar in the wild) ----

    @Test
    fun `a pre-r34 sidecar loads with no text layers`() {
        val restored = valid("{\"schemaVersion\":1,\"brightness\":10}")
        assertNull(restored.textLayers)
        assertTrue(restored.isTextIdentity)
        // brightness 10 rides through untouched --- the recipe is NOT
        // identity (only its TEXT half is); the absent key corrupts nothing.
        assertFalse(restored.isIdentity)
    }

    @Test
    fun `an empty textLayers array reads as absent`() {
        val restored = valid(recipeWithLayers())
        assertNull(restored.textLayers)
    }

    // ---- The full round-trip ----

    @Test
    fun `a recipe with two text layers round-trips losslessly`() {
        val recipe = EditRecipe(
            brightness = 10,
            lut = LutSelection(lutId = "warm-film", intensity = 70),
            textLayers = TextLayers(
                listOf(
                    TextLayer(
                        id = "t1", text = "Hello toad", fontId = "playfair-display",
                        sizePct = 150, colorArgb = 0xFF1E88E5L, opacity = 40,
                        rotationDeg = -45, centerX = 0.25, centerY = 0.75,
                    ),
                    TextLayer(
                        id = "t2", text = "", fontId = "space-mono",
                        sizePct = 0, colorArgb = 0x80F44336L, opacity = 0,
                        rotationDeg = 180, centerX = 1.0, centerY = 0.0,
                    ),
                ),
            ),
        )
        val restored = valid(EditRecipeCodec.toJson(recipe))
        assertEquals(recipe.textLayers, restored.textLayers)
        assertEquals(10, restored.brightness)
        assertEquals(recipe.lut, restored.lut)
        assertFalse(restored.isIdentity) // text makes the recipe non-identity
    }

    @Test
    fun `a clean session writes no textLayers key`() {
        val json = EditRecipeCodec.toJson(EditRecipe())
        assertFalse("the key must be absent for a clean session", json.contains("textLayers"))
        // And a session whose layers were all deleted normalises back to absent.
        val emptied = EditRecipeCodec.toJson(
            EditRecipe(textLayers = TextLayers(emptyList())),
        )
        assertFalse(emptied.contains("textLayers"))
    }

    // ---- The tolerance contract (fontId) ----

    @Test
    fun `an unresolvable fontId round-trips verbatim and degrades at render`() {
        val restored = valid(recipeWithLayers(layerJson(fontId = "ghost-font")))
        val layer = restored.textLayers!!.layers.single()
        assertEquals("ghost-font", layer.fontId) // verbatim: the recipe is the user's data
        assertEquals(BundledFonts.DEFAULT_ID, BundledFonts.effectiveId(layer.fontId)) // the render face
        // And it survives a SECOND round-trip unchanged.
        val twice = valid(EditRecipeCodec.toJson(restored))
        assertEquals("ghost-font", twice.textLayers!!.layers.single().fontId)
    }

    @Test
    fun `a blank fontId reads as the default face`() {
        val restored = valid(recipeWithLayers(layerJson(fontId = "  ")))
        assertEquals(BundledFonts.DEFAULT_ID, restored.textLayers!!.layers.single().fontId)
    }

    // ---- The tolerant reads ----

    @Test
    fun `out-of-range values clamp like every other slider`() {
        val restored = valid(
            recipeWithLayers(
                layerJson(sizePct = 500, opacity = -20, rotationDeg = 270, centerX = 1.7, centerY = -0.5),
            ),
        )
        val layer = restored.textLayers!!.layers.single()
        assertEquals(300, layer.sizePct)
        assertEquals(0, layer.opacity)
        assertEquals(180, layer.rotationDeg)
        assertEquals(1.0, layer.centerX, 0.0)
        assertEquals(0.0, layer.centerY, 0.0)
    }

    @Test
    fun `a malformed color reads as the default color, a well-formed one round-trips`() {
        val restored = valid(
            recipeWithLayers(layerJson(color = "not-hex"), layerJson(id = "t2", color = "80f44336")),
        )
        val (broken, fine) = restored.textLayers!!.layers
        assertEquals(0xFFFFFFFFL, broken.colorArgb)
        assertEquals(0x80F44336L, fine.colorArgb)
        // The written form is 8 uppercase hex digits.
        val json = EditRecipeCodec.toJson(restored)
        assertTrue("color must serialise as AARRGGBB", json.contains("\"color\":\"80F44336\""))
    }

    @Test
    fun `a layer without identity is skipped, others survive`() {
        val restored = valid(
            recipeWithLayers(
                layerJson(id = "  "), // blank id: cannot be re-targeted
                layerJson(id = "t2", text = "keep"),
                "not-an-object", // a non-object entry: unknown shape
            ),
        )
        assertEquals(listOf("t2"), restored.textLayers!!.layers.map { it.id })
    }

    @Test
    fun `an oversized text truncates at the model cap instead of losing the session`() {
        val restored = valid(
            recipeWithLayers(layerJson(text = "x".repeat(TextLayer.MAX_TEXT_LENGTH + 500))),
        )
        assertEquals(TextLayer.MAX_TEXT_LENGTH, restored.textLayers!!.layers.single().text.length)
    }

    @Test
    fun `duplicate layer ids are a structured parse failure, not a corrupt session`() {
        // The container rejects duplicates; the codec surfaces it as an
        // Invalid (the sidecar then self-heals away per the store rules).
        val parsed = EditRecipeCodec.fromJson(
            recipeWithLayers(layerJson(id = "t1"), layerJson(id = "t1", text = "dupe")),
        )
        assertTrue(parsed is EditRecipeCodec.ParseResult.Invalid)
    }

    @Test
    fun `missing per-layer fields default honestly`() {
        val restored = valid(
            JSONObject()
                .put("schemaVersion", 1)
                .put("textLayers", JSONArray().put(JSONObject().put("id", "t9").put("text", "y").put("fontId", "caveat")))
                .toString(),
        )
        val layer = restored.textLayers!!.layers.single()
        assertEquals(TextLayer.DEFAULT_SIZE_PCT, layer.sizePct)
        assertEquals(0xFFFFFFFFL, layer.colorArgb)
        assertEquals(100, layer.opacity)
        assertEquals(0, layer.rotationDeg)
        assertEquals(0.5, layer.centerX, 0.0)
        assertEquals(0.5, layer.centerY, 0.0)
    }
}
