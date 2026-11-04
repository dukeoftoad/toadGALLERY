package io.github.dukeoftoad.gallery.edit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r38 frame on the ADR-0011 sidecar wire (the contract's
 * round-trip set, the look's tolerance shape): a pre-r38 sidecar loads
 * clean (even one carrying ALL FOUR earlier containers --- the round-five
 * stacking proof), the lossless round-trip, the unknown-frameId skip,
 * clamps and defaults.
 */
class FramesRecipeTest {

    private fun valid(json: String): EditRecipe =
        (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe

    // ---- The absent key (every pre-r38 sidecar in the wild) ----

    @Test
    fun `a pre-r38 sidecar loads with no frame`() {
        val restored = valid("{\"schemaVersion\":1,\"brightness\":10}")
        assertNull(restored.frame)
        assertTrue(restored.isFrameIdentity)
        assertFalse(restored.isIdentity) // brightness rides through
    }

    @Test
    fun `a pre-r38 sidecar carrying all four earlier containers keeps them intact`() {
        // The round-five stacking proof: every earlier container is
        // untouched by r38's key.
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "textLayers",
                org.json.JSONArray().put(
                    org.json.JSONObject().put("id", "t1").put("text", "Hi").put("fontId", "caveat"),
                ),
            )
            .put(
                "stickerLayers",
                org.json.JSONArray().put(
                    org.json.JSONObject().put("id", "s1").put("stickerId", "heart"),
                ),
            )
            .put(
                "drawLayers",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("id", "d1")
                        .put("color", "FFF44336")
                        .put(
                            "points",
                            org.json.JSONArray()
                                .put(org.json.JSONArray().put(0.1).put(0.1))
                                .put(org.json.JSONArray().put(0.9).put(0.9)),
                        ),
                ),
            )
            .put(
                "mosaicRegions",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("id", "r1")
                        .put("kind", "mosaic")
                        .put(
                            "points",
                            org.json.JSONArray()
                                .put(org.json.JSONArray().put(0.2).put(0.2))
                                .put(org.json.JSONArray().put(0.8).put(0.2))
                                .put(org.json.JSONArray().put(0.5).put(0.9)),
                        ),
                ),
            )
            .toString()
        val restored = valid(json)
        assertNull(restored.frame)
        assertEquals(1, restored.textLayers?.layers?.size)
        assertEquals(1, restored.stickerLayers?.layers?.size)
        assertEquals(1, restored.drawLayers?.layers?.size)
        assertEquals(1, restored.mosaicRegions?.layers?.size)
        assertEquals("Hi", restored.textLayers?.layers?.get(0)?.text)
        assertEquals(RegionKind.MOSAIC, restored.mosaicRegions?.layers?.get(0)?.kind)
    }

    // ---- The lossless round-trip ----

    @Test
    fun `a full frame round-trip is lossless`() {
        val recipe = EditRecipe(
            exposure = 4,
            frame = FrameSelection(
                frameId = "film",
                widthPct = 35,
                colorArgb = 0xFFFDD835L,
            ),
        )
        val restored = valid(EditRecipeCodec.toJson(recipe))
        assertEquals(recipe.frame, restored.frame)
        assertEquals("film", restored.frame?.frameId)
        assertEquals(35, restored.frame?.widthPct)
        assertEquals(0xFFFDD835L, restored.frame?.colorArgb)
        assertNull(restored.textLayers) // the earlier families stay absent
        assertFalse(restored.isIdentity)
    }

    // ---- The tolerance contract (the look's shape) ----

    @Test
    fun `an unresolvable frameId round-trips verbatim and renders nothing`() {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("frame", JSONObject().put("frameId", "ghost-frame").put("widthPct", 30))
            .toString()
        val restored = valid(json)
        assertEquals("ghost-frame", restored.frame?.frameId)
        assertTrue(BundledFrames.geometry("ghost-frame") == null) // the render-time degrade
        // The id still survives the next round-trip untouched.
        val again = valid(EditRecipeCodec.toJson(restored))
        assertEquals("ghost-frame", again.frame?.frameId)
    }

    @Test
    fun `a blank frameId reads as no frame`() {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("frame", JSONObject().put("frameId", "   ").put("widthPct", 30))
            .toString()
        assertNull(valid(json).frame)
    }

    @Test
    fun `numerics coerce and defaults read when absent`() {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("frame", JSONObject().put("frameId", "polaroid").put("widthPct", 500))
            .toString()
        val restored = valid(json)
        assertEquals(100, restored.frame?.widthPct) // clamped from 500
        // Defaults: width 20, colour white.
        val minimal = valid(
            JSONObject()
                .put("schemaVersion", 1)
                .put("frame", JSONObject().put("frameId", "plain"))
                .toString(),
        )
        assertEquals(20, minimal.frame?.widthPct)
        assertEquals(0xFFFFFFFFL, minimal.frame?.colorArgb)
        // A malformed colour reads as the default; zero clamps to 1.
        val odd = valid(
            JSONObject()
                .put("schemaVersion", 1)
                .put("frame", JSONObject().put("frameId", "plain").put("widthPct", 0).put("color", "nothex"))
                .toString(),
        )
        assertEquals(1, odd.frame?.widthPct)
        assertEquals(0xFFFFFFFFL, odd.frame?.colorArgb)
    }

    // ---- isIdentity folding ----

    @Test
    fun `the identity getters compose across all five families`() {
        val all = EditRecipe(
            textLayers = TextLayers(listOf(TextLayer(id = "t1", text = "Hi", fontId = "fira-sans"))),
            stickerLayers = StickerLayers(listOf(StickerLayer(id = "s1", stickerId = "heart"))),
            drawLayers = DrawLayers(listOf(DrawLayer(id = "d1", points = listOf(DrawLayer.Point(0.5, 0.5))))),
            mosaicRegions = RegionLayers(
                listOf(RegionLayer(id = "r1", kind = RegionKind.MOSAIC, points = listOf(RegionLayer.Point(0.1, 0.1), RegionLayer.Point(0.9, 0.1), RegionLayer.Point(0.5, 0.9)))),
            ),
            frame = FrameSelection(frameId = "plain"),
        )
        assertFalse(all.isIdentity)
        assertTrue(all.copy(frame = null).isFrameIdentity)
        assertTrue(
            all.copy(
                textLayers = null, stickerLayers = null, drawLayers = null,
                mosaicRegions = null, frame = null,
            ).isIdentity,
        )
    }
}
