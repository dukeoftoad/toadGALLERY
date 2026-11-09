package com.toadgallery.editor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r36 stroke layers on the ADR-0011 sidecar wire (the contract's
 * round-trip set, the family conventions): a pre-r36 sidecar loads clean
 * (even one carrying r34 text AND r35 stickers --- the stacking proof),
 * the full round-trip is lossless, the points tolerate malformed
 * entries, and the clamps/defaults/duplicate rules hold --- a hand-edited
 * sidecar never loses its whole session.
 */
class DrawLayersRecipeTest {

    private fun valid(json: String): EditRecipe =
        (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe

    private fun pointsJson(vararg pairs: DoubleArray): JSONArray {
        val arr = JSONArray()
        pairs.forEach { arr.put(JSONArray().put(it[0]).put(it[1])) }
        return arr
    }

    private fun layerJson(
        id: String = "d1",
        color: String = "FFF44336",
        widthPct: Int = 5,
        opacity: Int = 90,
        smoothingPct: Int = 50,
        points: JSONArray = pointsJson(doubleArrayOf(0.1, 0.1), doubleArrayOf(0.9, 0.9)),
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("color", color)
        .put("widthPct", widthPct)
        .put("opacity", opacity)
        .put("smoothingPct", smoothingPct)
        .put("points", points)

    private fun recipeWithLayers(vararg layers: Any): String {
        val arr = JSONArray()
        layers.forEach { arr.put(it) }
        return JSONObject().put("schemaVersion", 1).put("drawLayers", arr).toString()
    }

    // ---- The absent key (every pre-r36 sidecar in the wild) ----

    @Test
    fun `a pre-r36 sidecar loads with no strokes`() {
        val restored = valid("{\"schemaVersion\":1,\"brightness\":10}")
        assertNull(restored.drawLayers)
        assertTrue(restored.isDrawIdentity)
        assertFalse(restored.isIdentity) // brightness rides through
    }

    @Test
    fun `a pre-r36 sidecar carrying r34 text and r35 stickers keeps them intact`() {
        // The stacking proof for round three: both earlier containers are
        // untouched by r36's key.
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "textLayers",
                JSONArray().put(
                    JSONObject().put("id", "t1").put("text", "Hi").put("fontId", "caveat"),
                ),
            )
            .put(
                "stickerLayers",
                JSONArray().put(
                    JSONObject().put("id", "s1").put("stickerId", "heart").put("sizePct", 25),
                ),
            )
            .toString()
        val restored = valid(json)
        assertNull(restored.drawLayers)
        assertEquals("Hi", restored.textLayers?.layers?.get(0)?.text)
        assertEquals("heart", restored.stickerLayers?.layers?.get(0)?.stickerId)
        assertEquals(25, restored.stickerLayers?.layers?.get(0)?.sizePct)
    }

    // ---- The lossless round-trip ----

    @Test
    fun `a full stroke round-trip is lossless`() {
        val recipe = EditRecipe(
            contrast = 8,
            drawLayers = DrawLayers(
                listOf(
                    DrawLayer(
                        id = "d1",
                        colorArgb = 0xFFF06292L,
                        widthPct = 7,
                        opacity = 80,
                        smoothingPct = 75,
                        points = listOf(
                            DrawLayer.Point(0.05, 0.1),
                            DrawLayer.Point(0.5, 0.5),
                            DrawLayer.Point(0.95, 0.88),
                        ),
                    ),
                    DrawLayer(
                        id = "d2",
                        colorArgb = 0xFF212121L,
                        points = listOf(DrawLayer.Point(0.5, 0.5)), // a dot
                    ),
                ),
            ),
        )
        val restored = valid(EditRecipeCodec.toJson(recipe))
        assertEquals(recipe.drawLayers, restored.drawLayers)
        // The earlier families stay absent on a stroke-only round-trip.
        assertNull(restored.textLayers)
        assertNull(restored.stickerLayers)
        assertFalse(restored.isIdentity)
    }

    @Test
    fun `an emptied list normalises back to absent`() {
        val recipe = EditRecipe(drawLayers = DrawLayers(emptyList()))
        val wire = EditRecipeCodec.toJson(recipe)
        assertFalse(wire.contains("drawLayers"))
        assertNull(valid(wire).drawLayers)
    }

    // ---- Points tolerance ----

    @Test
    fun `malformed point entries are dropped, survivors clamp`() {
        val points = JSONArray()
            .put(JSONArray().put(0.1).put(0.2)) // fine
            .put(JSONArray().put(0.3)) // not 2-element: dropped
            .put("nope") // not an array: dropped
            .put(JSONArray().put(JSONObject())) // non-numeric members -> NaN pair: dropped
            .put(JSONArray().put(5.0).put(-4.0)) // clamps to (1.0, 0.0)
        val restored = valid(recipeWithLayers(layerJson(points = points)))
        val stroke = restored.drawLayers!!.layers[0]
        assertEquals(2, stroke.points.size)
        assertEquals(0.1, stroke.points[0].x, 0.0)
        assertEquals(1.0, stroke.points[1].x, 0.0) // clamped from 5.0
        assertEquals(0.0, stroke.points[1].y, 0.0) // clamped from -4.0
    }

    @Test
    fun `a layer whose points all fail is skipped`() {
        val points = JSONArray().put(JSONArray().put(1)).put(JSONObject())
        val restored = valid(
            recipeWithLayers(
                layerJson(id = "d1", points = points),
                layerJson(id = "d2", points = points),
                layerJson(id = "d3"), // fine
            ),
        )
        assertEquals(listOf("d3"), restored.drawLayers?.layers?.map { it.id })
    }

    @Test
    fun `a layer without a points key is skipped`() {
        val noPoints = JSONObject()
            .put("id", "d1")
            .put("color", "FF000000")
            .put("widthPct", 5)
        val restored = valid(recipeWithLayers(noPoints))
        assertNull(restored.drawLayers)
    }

    @Test
    fun `the point count caps at the model cap on read`() {
        val many = JSONArray()
        for (i in 0 until DrawLayer.MAX_POINTS + 40) {
            many.put(JSONArray().put(i / 700.0).put(0.5))
        }
        val restored = valid(recipeWithLayers(layerJson(points = many)))
        assertEquals(DrawLayer.MAX_POINTS, restored.drawLayers!!.layers[0].points.size)
    }

    // ---- Clamps, defaults, duplicates ----

    @Test
    fun `numerics coerce like every other slider`() {
        val restored = valid(
            recipeWithLayers(
                layerJson(widthPct = 500, opacity = 200, smoothingPct = 400),
                layerJson(id = "d2", widthPct = 0, opacity = -5, smoothingPct = -10),
            ),
        )
        val first = restored.drawLayers!!.layers[0]
        assertEquals(100, first.widthPct)
        assertEquals(100, first.opacity)
        assertEquals(100, first.smoothingPct)
        val second = restored.drawLayers!!.layers[1]
        assertEquals(1, second.widthPct)
        assertEquals(0, second.opacity)
        assertEquals(0, second.smoothingPct)
    }

    @Test
    fun `a malformed color reads as the default and defaults read when absent`() {
        val restored = valid(recipeWithLayers(layerJson(color = "nothex")))
        assertEquals(0xFFFFFFFFL, restored.drawLayers!!.layers[0].colorArgb)
        val minimal = valid(recipeWithLayers(JSONObject().put("id", "d9").put("points", pointsJson(doubleArrayOf(0.5, 0.5)))))
        val layer = restored.drawLayers!!.layers[0]
        // defaults: width 3, opacity 100, smoothing 50 (read defaults)
        assertEquals(3, minimal.drawLayers!!.layers[0].widthPct)
        assertEquals(100, minimal.drawLayers!!.layers[0].opacity)
        assertEquals(50, minimal.drawLayers!!.layers[0].smoothingPct)
        assertEquals(5, layer.widthPct) // the layerJson default rode through
    }

    @Test
    fun `duplicate ids are a structured parse failure`() {
        val result = EditRecipeCodec.fromJson(recipeWithLayers(layerJson(id = "d1"), layerJson(id = "d1")))
        assertTrue(result is EditRecipeCodec.ParseResult.Invalid)
    }

    @Test
    fun `blank ids and non-objects are skipped`() {
        val restored = valid(
            recipeWithLayers(
                layerJson(id = "   "),
                "not-an-object",
                layerJson(id = "d2"),
            ),
        )
        assertEquals(listOf("d2"), restored.drawLayers?.layers?.map { it.id })
    }

    // ---- isIdentity folding ----

    @Test
    fun `the identity getters compose across all three families`() {
        val all = EditRecipe(
            textLayers = TextLayers(listOf(TextLayer(id = "t1", text = "Hi", fontId = "fira-sans"))),
            stickerLayers = StickerLayers(listOf(StickerLayer(id = "s1", stickerId = "heart"))),
            drawLayers = DrawLayers(listOf(DrawLayer(id = "d1", points = listOf(DrawLayer.Point(0.5, 0.5))))),
        )
        assertFalse(all.isIdentity)
        assertTrue(all.copy(drawLayers = null).isDrawIdentity)
        assertTrue(all.copy(textLayers = null, stickerLayers = null, drawLayers = null).isIdentity)
    }
}
