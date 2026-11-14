package com.toadgallery.editor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Region layers on the sidecar wire: legacy loads, lossless round-trip, skips, points tolerance, clamps. */
class RegionLayersRecipeTest {

    private fun valid(json: String): EditRecipe =
        (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe

    private fun pointsJson(vararg xy: Double): JSONArray {
        val arr = JSONArray()
        xy.toList().chunked(2).forEach { pair -> arr.put(JSONArray().put(pair[0]).put(pair[1])) }
        return arr
    }

    private fun regionJson(
        id: String = "r1",
        kind: String = "mosaic",
        strengthPct: Int = 12,
        points: JSONArray = pointsJson(0.2, 0.2, 0.8, 0.2, 0.5, 0.9),
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind)
        .put("strengthPct", strengthPct)
        .put("points", points)

    private fun recipeWithRegions(vararg regions: Any): String {
        val arr = JSONArray()
        regions.forEach { arr.put(it) }
        return JSONObject().put("schemaVersion", 1).put("mosaicRegions", arr).toString()
    }

    // ---- The absent key ----

    @Test
    fun `a pre-r37 sidecar loads with no regions`() {
        val restored = valid("{\"schemaVersion\":1,\"brightness\":10}")
        assertNull(restored.mosaicRegions)
        assertTrue(restored.isRegionIdentity)
        assertFalse(restored.isIdentity) // brightness rides through
    }

    @Test
    fun `a pre-r37 sidecar carrying text, stickers and strokes keeps them intact`() {
        // All three earlier containers are untouched by the regions key.
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "textLayers",
                JSONArray().put(JSONObject().put("id", "t1").put("text", "Hi").put("fontId", "caveat")),
            )
            .put(
                "stickerLayers",
                JSONArray().put(JSONObject().put("id", "s1").put("stickerId", "heart").put("sizePct", 25)),
            )
            .put(
                "drawLayers",
                JSONArray().put(
                    JSONObject()
                        .put("id", "d1")
                        .put("color", "FFF44336")
                        .put("points", pointsJson(0.1, 0.1, 0.9, 0.9)),
                ),
            )
            .toString()
        val restored = valid(json)
        assertNull(restored.mosaicRegions)
        assertEquals("Hi", restored.textLayers?.layers?.get(0)?.text)
        assertEquals("heart", restored.stickerLayers?.layers?.get(0)?.stickerId)
        assertEquals(2, restored.drawLayers?.layers?.get(0)?.points?.size)
    }

    // ---- The lossless round-trip ----

    @Test
    fun `a full region round-trip is lossless`() {
        val recipe = EditRecipe(
            saturation = 6,
            mosaicRegions = RegionLayers(
                listOf(
                    RegionLayer(
                        id = "r1",
                        kind = RegionKind.LINEAR_BLUR,
                        strengthPct = 40,
                        points = listOf(
                            RegionLayer.Point(0.1, 0.1),
                            RegionLayer.Point(0.9, 0.1),
                            RegionLayer.Point(0.5, 0.95),
                        ),
                    ),
                    RegionLayer(
                        id = "r2",
                        kind = RegionKind.MOSAIC,
                        points = listOf(
                            RegionLayer.Point(0.3, 0.3),
                            RegionLayer.Point(0.7, 0.3),
                            RegionLayer.Point(0.7, 0.7),
                            RegionLayer.Point(0.3, 0.7),
                        ),
                    ),
                ),
            ),
        )
        val restored = valid(EditRecipeCodec.toJson(recipe))
        assertEquals(recipe.mosaicRegions, restored.mosaicRegions)
        assertEquals(RegionKind.LINEAR_BLUR, restored.mosaicRegions?.layers?.get(0)?.kind)
        assertEquals(RegionKind.MOSAIC, restored.mosaicRegions?.layers?.get(1)?.kind)
        assertNull(restored.textLayers) // the earlier families stay absent
        assertFalse(restored.isIdentity)
    }

    @Test
    fun `an emptied list normalises back to absent`() {
        val recipe = EditRecipe(mosaicRegions = RegionLayers(emptyList()))
        val wire = EditRecipeCodec.toJson(recipe)
        assertFalse(wire.contains("mosaicRegions"))
        assertNull(valid(wire).mosaicRegions)
    }

    // ---- The kind + points tolerance ----

    @Test
    fun `an unknown kind is corrupt data and the layer is skipped`() {
        val restored = valid(
            recipeWithRegions(
                regionJson(id = "r1", kind = "gaussian"),
                regionJson(id = "r2", kind = "MOSAIC"), // case-sensitive wire name
                regionJson(id = "r3"), // fine (default mosaic)
            ),
        )
        assertEquals(listOf("r3"), restored.mosaicRegions?.layers?.map { it.id })
    }

    @Test
    fun `a layer whose points fall under three is skipped`() {
        val two = pointsJson(0.2, 0.2, 0.8, 0.2)
        val none = JSONArray().put(1).put(JSONObject())
        val restored = valid(
            recipeWithRegions(
                regionJson(id = "r1", points = two),
                regionJson(id = "r2", points = none),
                regionJson(id = "r3", points = pointsJson(0.0, 0.0, 1.0, 0.0, 0.5, 1.0)),
            ),
        )
        assertEquals(listOf("r3"), restored.mosaicRegions?.layers?.map { it.id })
    }

    @Test
    fun `malformed point entries are dropped, survivors clamp, the count caps`() {
        val points = JSONArray()
            .put(JSONArray().put(0.1).put(0.2)) // fine
            .put(JSONArray().put(0.3)) // not 2-element: dropped
            .put(5.0) // not an array: dropped (optJSONArray -> null -> continue)
            .put(JSONArray().put(9.0).put(-4.0)) // clamps to (1.0, 0.0)
            .put(JSONArray().put(0.5).put(0.5))
        val restored = valid(recipeWithRegions(regionJson(points = points)))
        val region = restored.mosaicRegions!!.layers[0]
        assertEquals(3, region.points.size)
        assertEquals(1.0, region.points[1].x, 0.0) // clamped from 9.0
        assertEquals(0.0, region.points[1].y, 0.0) // clamped from -4.0
        // The count caps at the model cap on read.
        val many = JSONArray()
        for (i in 0 until RegionLayer.MAX_POINTS + 60) {
            many.put(JSONArray().put(i / 700.0).put(0.5))
        }
        val capped = valid(recipeWithRegions(regionJson(points = many)))
        assertEquals(RegionLayer.MAX_POINTS, capped.mosaicRegions!!.layers[0].points.size)
    }

    // ---- Clamps, defaults, duplicates ----

    @Test
    fun `numerics coerce like every other slider`() {
        val restored = valid(
            recipeWithRegions(
                regionJson(strengthPct = 500),
                regionJson(id = "r2", strengthPct = 0),
            ),
        )
        assertEquals(100, restored.mosaicRegions!!.layers[0].strengthPct)
        assertEquals(1, restored.mosaicRegions!!.layers[1].strengthPct)
    }

    @Test
    fun `the default strength reads when absent`() {
        val minimal = valid(
            recipeWithRegions(
                JSONObject()
                    .put("id", "r9")
                    .put("kind", "linearBlur")
                    .put("points", pointsJson(0.2, 0.2, 0.8, 0.2, 0.5, 0.9)),
            ),
        )
        assertEquals(12, minimal.mosaicRegions!!.layers[0].strengthPct)
        assertEquals(RegionKind.LINEAR_BLUR, minimal.mosaicRegions!!.layers[0].kind)
    }

    @Test
    fun `duplicate ids are a structured parse failure`() {
        val result = EditRecipeCodec.fromJson(recipeWithRegions(regionJson(id = "r1"), regionJson(id = "r1")))
        assertTrue(result is EditRecipeCodec.ParseResult.Invalid)
    }

    @Test
    fun `blank ids and non-objects are skipped`() {
        val restored = valid(
            recipeWithRegions(
                regionJson(id = "   "),
                "not-an-object",
                regionJson(id = "r2"),
            ),
        )
        assertEquals(listOf("r2"), restored.mosaicRegions?.layers?.map { it.id })
    }

    // ---- isIdentity folding ----

    @Test
    fun `the identity getters compose across all four families`() {
        val all = EditRecipe(
            textLayers = TextLayers(listOf(TextLayer(id = "t1", text = "Hi", fontId = "fira-sans"))),
            stickerLayers = StickerLayers(listOf(StickerLayer(id = "s1", stickerId = "heart"))),
            drawLayers = DrawLayers(listOf(DrawLayer(id = "d1", points = listOf(DrawLayer.Point(0.5, 0.5))))),
            mosaicRegions = RegionLayers(
                listOf(
                    RegionLayer(
                        id = "r1",
                        kind = RegionKind.MOSAIC,
                        points = listOf(RegionLayer.Point(0.1, 0.1), RegionLayer.Point(0.9, 0.1), RegionLayer.Point(0.5, 0.9)),
                    ),
                ),
            ),
        )
        assertFalse(all.isIdentity)
        assertTrue(all.copy(mosaicRegions = null).isRegionIdentity)
        assertTrue(all.copy(textLayers = null, stickerLayers = null, drawLayers = null, mosaicRegions = null).isIdentity)
    }
}
