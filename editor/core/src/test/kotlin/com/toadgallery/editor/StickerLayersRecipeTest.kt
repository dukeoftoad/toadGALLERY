package com.toadgallery.editor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sticker layers on the sidecar wire: older sidecars load clean, the round-trip is lossless,
 * unresolvable ids survive verbatim, and tolerant reads never lose a whole session.
 */
class StickerLayersRecipeTest {

    private fun valid(json: String): EditRecipe =
        (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe

    private fun layerJson(
        id: String = "s1",
        stickerId: String = "heart",
        sizePct: Int = 15,
        opacity: Int = 100,
        rotationDeg: Int = 0,
        centerX: Double = 0.5,
        centerY: Double = 0.5,
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("stickerId", stickerId)
        .put("sizePct", sizePct)
        .put("opacity", opacity)
        .put("rotationDeg", rotationDeg)
        .put("centerX", centerX)
        .put("centerY", centerY)

    private fun recipeWithLayers(vararg layers: Any): String {
        val arr = JSONArray()
        layers.forEach { arr.put(it) }
        return JSONObject().put("schemaVersion", 1).put("stickerLayers", arr).toString()
    }

    // ---- The absent key (older sidecars in the wild) ----

    @Test
    fun `a pre-r35 sidecar loads with no sticker layers`() {
        val restored = valid("{\"schemaVersion\":1,\"brightness\":10}")
        assertNull(restored.stickerLayers)
        assertTrue(restored.isStickerIdentity)
        // brightness rides through: NOT identity overall (only its STICKER half is); the absent key corrupts nothing.
        assertFalse(restored.isIdentity)
    }

    @Test
    fun `a pre-r35 sidecar carrying r34 text layers keeps them intact`() {
        // The stacking proof: the text container is untouched by the sticker key.
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "textLayers",
                JSONArray().put(
                    JSONObject()
                        .put("id", "t1")
                        .put("text", "Hello")
                        .put("fontId", "caveat")
                        .put("sizePct", 24)
                        .put("color", "FFF06292")
                        .put("opacity", 90)
                        .put("rotationDeg", -10)
                        .put("centerX", 0.4)
                        .put("centerY", 0.3),
                ),
            )
            .toString()
        val restored = valid(json)
        assertNull(restored.stickerLayers)
        assertEquals(1, restored.textLayers?.layers?.size)
        assertEquals("Hello", restored.textLayers?.layers?.get(0)?.text)
        assertFalse(restored.isIdentity) // the text half is real
    }

    // ---- The lossless round-trip ----

    @Test
    fun `a full sticker round-trip is lossless`() {
        val recipe = EditRecipe(
            brightness = 5,
            stickerLayers = StickerLayers(
                listOf(
                    StickerLayer(
                        id = "s1",
                        stickerId = "heart",
                        sizePct = 40,
                        opacity = 80,
                        rotationDeg = 25,
                        centerX = 0.3,
                        centerY = 0.7,
                    ),
                    StickerLayer(id = "s2", stickerId = "beach-day", sizePct = 22, centerX = 0.6, centerY = 0.2),
                ),
            ),
        )
        val wire = EditRecipeCodec.toJson(recipe)
        val restored = valid(wire)
        assertEquals(2, restored.stickerLayers?.layers?.size)
        assertEquals(
            recipe.stickerLayers,
            restored.stickerLayers,
        )
        // The text container stays absent on a sticker-only round-trip.
        assertNull(restored.textLayers)
        assertFalse(restored.isIdentity)
    }

    @Test
    fun `an emptied list normalises back to absent`() {
        val recipe = EditRecipe(stickerLayers = StickerLayers(emptyList()))
        val wire = EditRecipeCodec.toJson(recipe)
        assertFalse(wire.contains("stickerLayers"))
        assertNull(valid(wire).stickerLayers)
    }

    // ---- The tolerance contract (the image-shaped delta) ----

    @Test
    fun `an unresolvable stickerId round-trips verbatim and renders nothing`() {
        val restored = valid(recipeWithLayers(layerJson(stickerId = "ghost-import")))
        assertEquals("ghost-import", restored.stickerLayers?.layers?.get(0)?.stickerId)
        // Render-time resolution: no such shape in the registry, no such bitmap in the store ---
        // the compositor skips it; the id still survives the next round-trip untouched.
        val again = valid(EditRecipeCodec.toJson(restored))
        assertEquals("ghost-import", again.stickerLayers?.layers?.get(0)?.stickerId)
    }

    @Test
    fun `a blank stickerId entry is skipped alongside a blank id`() {
        val restored = valid(
            recipeWithLayers(
                layerJson(id = "s1", stickerId = "   "),
                layerJson(id = "", stickerId = "heart"),
                JSONObject().put("id", "s9"), // no stickerId key at all
                "not-an-object",
                layerJson(id = "s2", stickerId = "star"),
            ),
        )
        assertEquals(listOf("s2"), restored.stickerLayers?.layers?.map { it.id })
    }

    @Test
    fun `numerics coerce like every other slider`() {
        val restored = valid(
            recipeWithLayers(
                layerJson(sizePct = 999, opacity = 200, rotationDeg = 720, centerX = 3.0, centerY = -2.0),
                layerJson(id = "s2", sizePct = -50, opacity = -1, rotationDeg = -400, centerX = -0.5, centerY = 9.0),
            ),
        )
        val first = restored.stickerLayers!!.layers[0]
        assertEquals(300, first.sizePct)
        assertEquals(100, first.opacity)
        assertEquals(180, first.rotationDeg) // 720 % 360 == 0? no: 720 raw -> clamped at 180
        assertEquals(1.0, first.centerX, 0.0)
        assertEquals(0.0, first.centerY, 0.0)
        val second = restored.stickerLayers!!.layers[1]
        assertEquals(0, second.sizePct)
        assertEquals(0, second.opacity)
        assertEquals(-180, second.rotationDeg)
        assertEquals(0.0, second.centerX, 0.0)
        assertEquals(1.0, second.centerY, 0.0)
    }

    @Test
    fun `defaults read when keys are absent`() {
        val restored = valid(recipeWithLayers(JSONObject().put("id", "s1").put("stickerId", "pin")))
        val layer = restored.stickerLayers!!.layers[0]
        assertEquals(15, layer.sizePct)
        assertEquals(100, layer.opacity)
        assertEquals(0, layer.rotationDeg)
        assertEquals(0.5, layer.centerX, 0.0)
        assertEquals(0.5, layer.centerY, 0.0)
    }

    @Test
    fun `duplicate ids are a structured parse failure`() {
        val result = EditRecipeCodec.fromJson(recipeWithLayers(layerJson(id = "s1"), layerJson(id = "s1")))
        assertTrue(result is EditRecipeCodec.ParseResult.Invalid)
    }

    @Test
    fun `an over-long id or stickerId truncates at the model cap`() {
        val longId = "s".repeat(100)
        val longStickerId = "k".repeat(100)
        val restored = valid(recipeWithLayers(layerJson(id = longId, stickerId = longStickerId)))
        assertEquals(StickerLayer.MAX_ID_LENGTH.toLong(), restored.stickerLayers!!.layers[0].id.length.toLong())
        assertEquals(StickerLayer.MAX_ID_LENGTH.toLong(), restored.stickerLayers!!.layers[0].stickerId.length.toLong())
    }

    // ---- isIdentity folding ----

    @Test
    fun `the identity getters compose across the families`() {
        val both = EditRecipe(
            textLayers = TextLayers(listOf(TextLayer(id = "t1", text = "Hi", fontId = "fira-sans"))),
            stickerLayers = StickerLayers(listOf(StickerLayer(id = "s1", stickerId = "heart"))),
        )
        assertFalse(both.isIdentity)
        assertFalse(both.isTextIdentity)
        assertFalse(both.isStickerIdentity)

        val textOnly = both.copy(stickerLayers = null)
        assertFalse(textOnly.isIdentity)
        assertTrue(textOnly.isStickerIdentity)

        val clean = both.copy(textLayers = null, stickerLayers = null)
        assertTrue(clean.isIdentity)
    }
}
