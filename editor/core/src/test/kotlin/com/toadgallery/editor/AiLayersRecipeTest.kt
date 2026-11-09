package com.toadgallery.editor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r41 AI containers on the ADR-0011 sidecar wire (the contract's
 * round-trip set): a pre-r41 sidecar loads clean (even one carrying ALL
 * FIVE earlier containers --- the round-nine stacking proof), the
 * lossless round-trips, the unresolvable-ref degradations, clamps and
 * defaults, the malformed-container skips, and the model validation.
 */
class AiLayersRecipeTest {

    private fun valid(json: String): EditRecipe =
        (EditRecipeCodec.fromJson(json) as EditRecipeCodec.ParseResult.Valid).recipe

    private fun pts(vararg xy: Double): List<AiEraseLayer.Point> =
        xy.toList().chunked(2).map { AiEraseLayer.Point(it[0], it[1]) }

    // ---- The absent keys (every pre-r41 sidecar in the wild) ----

    @Test
    fun `a pre-r41 sidecar loads with no AI containers`() {
        val restored = valid("{\"schemaVersion\":1,\"brightness\":10}")
        assertNull(restored.aiErase)
        assertNull(restored.sky)
        assertNull(restored.bokeh)
        assertNull(restored.retouch)
        assertTrue(restored.isAiIdentity)
        assertFalse(restored.isIdentity) // brightness rides through
    }

    @Test
    fun `a pre-r41 sidecar carrying all five earlier containers keeps them intact`() {
        // The round-nine stacking proof: every earlier container is
        // untouched by r41's keys.
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
            .put("frame", JSONObject().put("frameId", "film"))
            .toString()
        val restored = valid(json)
        assertNull(restored.aiErase)
        assertNull(restored.sky)
        assertNull(restored.bokeh)
        assertNull(restored.retouch)
        assertEquals(1, restored.textLayers?.layers?.size)
        assertEquals(1, restored.stickerLayers?.layers?.size)
        assertEquals(1, restored.drawLayers?.layers?.size)
        assertEquals(1, restored.mosaicRegions?.layers?.size)
        assertEquals("film", restored.frame?.frameId)
    }

    // ---- The lossless round-trips ----

    @Test
    fun `a full four-container round-trip is lossless`() {
        val recipe = EditRecipe(
            exposure = 4,
            aiErase = AiEraseLayers(
                listOf(
                    AiEraseLayer(id = "e1", points = pts(0.2, 0.2, 0.8, 0.2, 0.5, 0.9), artifactRef = "e1-1a2b3c4d"),
                ),
            ),
            sky = SkySelection(maskRef = "sky-90ab", skyId = "dusk", opacityPct = 80),
            bokeh = BokehSelection(depthRef = "bokeh-77f", blurStrengthPct = 55, focalDepthNorm = 0.35),
            retouch = RetouchSelection(boxesRef = "ret-0f1e", smoothingPct = 30, eyeBrightenPct = 15, faceSlimPct = 10),
        )
        val restored = valid(EditRecipeCodec.toJson(recipe))
        assertEquals(recipe.aiErase, restored.aiErase)
        assertEquals(recipe.sky, restored.sky)
        assertEquals(recipe.bokeh, restored.bokeh)
        assertEquals(recipe.retouch, restored.retouch)
        assertEquals("e1-1a2b3c4d", restored.aiErase?.layers?.get(0)?.artifactRef)
        assertEquals("dusk", restored.sky?.skyId)
        assertEquals(80, restored.sky?.opacityPct)
        assertEquals(0.35, restored.bokeh?.focalDepthNorm!!, 0.0)
        assertEquals(30, restored.retouch?.smoothingPct)
        assertNull(restored.textLayers) // the earlier families stay absent
        assertFalse(restored.isIdentity)
        assertFalse(restored.isAiIdentity)
    }

    // ---- The tolerance contracts ----

    @Test
    fun `an unresolvable artifact ref round-trips verbatim and renders nothing`() {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "aiErase",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("id", "e7")
                        .put("artifactRef", "ghost-artifact")
                        .put(
                            "points",
                            org.json.JSONArray()
                                .put(org.json.JSONArray().put(0.1).put(0.1))
                                .put(org.json.JSONArray().put(0.9).put(0.1))
                                .put(org.json.JSONArray().put(0.5).put(0.9)),
                        ),
                ),
            )
            .put("sky", JSONObject().put("maskRef", "ghost-mask").put("skyId", "ghost-sky"))
            .toString()
        val restored = valid(json)
        assertEquals("ghost-artifact", restored.aiErase?.layers?.get(0)?.artifactRef)
        assertEquals("ghost-mask", restored.sky?.maskRef)
        assertEquals("ghost-sky", restored.sky?.skyId)
        assertTrue(BundledSkies.byId("ghost-sky") == null) // the render-time degrade
        // The refs still survive the next round-trip untouched.
        val again = valid(EditRecipeCodec.toJson(restored))
        assertEquals("ghost-artifact", again.aiErase?.layers?.get(0)?.artifactRef)
        assertEquals("ghost-mask", again.sky?.maskRef)
    }

    @Test
    fun `a blank skyId reads as no sky and a blank id is the list family skip`() {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("sky", JSONObject().put("maskRef", "m1").put("skyId", "   "))
            .put(
                "aiErase",
                org.json.JSONArray()
                    .put(
                        org.json.JSONObject()
                            .put("id", "  ")
                            .put(
                                "points",
                                org.json.JSONArray()
                                    .put(org.json.JSONArray().put(0.1).put(0.1))
                                    .put(org.json.JSONArray().put(0.9).put(0.1))
                                    .put(org.json.JSONArray().put(0.5).put(0.9)),
                            ),
                    )
                    .put(
                        org.json.JSONObject()
                            .put("id", "e1")
                            .put("artifactRef", "")
                            .put(
                                "points",
                                org.json.JSONArray()
                                    .put(org.json.JSONArray().put(0.1).put(0.1))
                                    .put(org.json.JSONArray().put(0.9).put(0.1))
                                    .put(org.json.JSONArray().put(0.5).put(0.9)),
                            ),
                    ),
            )
            .toString()
        val restored = valid(json)
        assertNull(restored.sky) // blank skyId = no sky selection
        // The blank id was skipped; the blank-ref layer is KEPT (the
        // unresolvable-ref rule --- it round-trips, renders nothing).
        assertEquals(listOf("e1"), restored.aiErase?.layers?.map { it.id })
        assertEquals("", restored.aiErase?.layers?.get(0)?.artifactRef)
    }

    @Test
    fun `numerics coerce and defaults read when absent`() {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("sky", JSONObject().put("skyId", "dusk").put("opacityPct", 500))
            .put("bokeh", JSONObject().put("blurStrengthPct", 0).put("focalDepthNorm", 2.5))
            .put("retouch", JSONObject().put("smoothingPct", -5).put("eyeBrightenPct", 999))
            .toString()
        val restored = valid(json)
        assertEquals(100, restored.sky?.opacityPct) // clamped from 500
        assertEquals(1, restored.bokeh?.blurStrengthPct) // clamped from 0 into 1..100
        assertEquals(1.0, restored.bokeh?.focalDepthNorm!!, 0.0) // clamped from 2.5
        assertEquals(0, restored.retouch?.smoothingPct) // clamped from -5
        assertEquals(100, restored.retouch?.eyeBrightenPct) // clamped from 999
        // Defaults: opacity 100, strength 40, focal 0.5, the retouch trio 0.
        val minimal = valid(
            JSONObject()
                .put("schemaVersion", 1)
                .put("sky", JSONObject().put("skyId", "dusk"))
                .put("bokeh", JSONObject().put("depthRef", "d"))
                .put("retouch", JSONObject().put("boxesRef", "b"))
                .toString(),
        )
        assertEquals(100, minimal.sky?.opacityPct)
        assertEquals(40, minimal.bokeh?.blurStrengthPct)
        assertEquals(0.5, minimal.bokeh?.focalDepthNorm!!, 0.0)
        assertEquals(0, minimal.retouch?.smoothingPct)
        assertEquals(0, minimal.retouch?.eyeBrightenPct)
        assertEquals(0, minimal.retouch?.faceSlimPct)
    }

    @Test
    fun `an empty erase array normalises to absent and a short seed is the skip`() {
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put(
                "aiErase",
                org.json.JSONArray()
                    .put(
                        org.json.JSONObject().put("id", "e1").put(
                            "points",
                            org.json.JSONArray()
                                .put(org.json.JSONArray().put(0.5).put(0.5))
                                .put(org.json.JSONArray().put(0.6).put(0.6)),
                        ),
                    ),
            )
            .put(
                "sky",
                JSONObject().put("skyId", "day-clear").put("maskRef", "m").put("opacityPct", 70),
            )
            .toString()
        val restored = valid(json)
        // The two-point seed was skipped (an erase needs an area) and the
        // array normalised to absent.
        assertNull(restored.aiErase)
        assertEquals(70, restored.sky?.opacityPct)
    }

    // ---- Model validation ----

    @Test
    fun `out-of-range values are rejected at construction`() {
        val three = pts(0.1, 0.1, 0.9, 0.1, 0.5, 0.9)
        assertThrows(IllegalArgumentException::class.java) { AiEraseLayer(id = "", points = three) }
        assertThrows(IllegalArgumentException::class.java) { AiEraseLayer(id = "e1", points = pts(0.5, 0.5)) }
        assertThrows(IllegalArgumentException::class.java) {
            AiEraseLayer(id = "e1", points = List(AiEraseLayer.MAX_POINTS + 1) { AiEraseLayer.Point(0.5, 0.5) })
        }
        assertThrows(IllegalArgumentException::class.java) { AiEraseLayer.Point(1.5, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { SkySelection(maskRef = "m", skyId = "", opacityPct = 50) }
        assertThrows(IllegalArgumentException::class.java) { SkySelection(maskRef = "m", skyId = "dusk", opacityPct = 101) }
        assertThrows(IllegalArgumentException::class.java) { BokehSelection(depthRef = "d", blurStrengthPct = 0) }
        assertThrows(IllegalArgumentException::class.java) { BokehSelection(depthRef = "d", focalDepthNorm = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { RetouchSelection(boxesRef = "b", smoothingPct = 101) }
        assertThrows(IllegalArgumentException::class.java) { RetouchSelection(boxesRef = "b", faceSlimPct = -1) }
    }

    @Test
    fun `duplicate ids are rejected by the container and newId walks the e prefix`() {
        val a = AiEraseLayer(id = "e1", points = pts(0.2, 0.2, 0.8, 0.2, 0.2, 0.8))
        assertThrows(IllegalArgumentException::class.java) { AiEraseLayers(listOf(a, a)) }
        assertEquals("e1", AiEraseLayer.newId(emptyList()))
        assertEquals("e4", AiEraseLayer.newId(listOf("e1", "e3")))
        assertEquals("e2", AiEraseLayer.newId(listOf("t1", "s1", "d1", "r1", "e1")))
        // The families stay collision-free by construction: each family's
        // newId counts only its OWN prefix, so a cross-family id list is
        // invisible to the other family (the prefixes partition the space).
        assertEquals("r1", RegionLayer.newId(listOf("e1")))
        assertEquals("e1", AiEraseLayer.newId(listOf("r1")))
    }

    @Test
    fun `thin is the stroke family's rule verbatim`() {
        val points = (0 until 10).map { AiEraseLayer.Point(it / 10.0, 0.5) }
        val thinned = AiEraseLayer.thin(points, 3)
        assertEquals(listOf(0.0, 0.4, 0.8, 0.9), thinned.map { it.x })
        assertEquals(4, thinned.size) // <= cap + 1
    }

    // ---- isIdentity folding ----

    @Test
    fun `the identity getters compose across all nine families`() {
        val all = EditRecipe(
            textLayers = TextLayers(listOf(TextLayer(id = "t1", text = "Hi", fontId = "fira-sans"))),
            stickerLayers = StickerLayers(listOf(StickerLayer(id = "s1", stickerId = "heart"))),
            drawLayers = DrawLayers(listOf(DrawLayer(id = "d1", points = listOf(DrawLayer.Point(0.5, 0.5))))),
            mosaicRegions = RegionLayers(
                listOf(RegionLayer(id = "r1", kind = RegionKind.MOSAIC, points = listOf(RegionLayer.Point(0.1, 0.1), RegionLayer.Point(0.9, 0.1), RegionLayer.Point(0.5, 0.9)))),
            ),
            frame = FrameSelection(frameId = "plain"),
            aiErase = AiEraseLayers(listOf(AiEraseLayer(id = "e1", points = pts(0.1, 0.1, 0.9, 0.1, 0.5, 0.9)))),
            sky = SkySelection(maskRef = "m", skyId = "dusk"),
            bokeh = BokehSelection(depthRef = "d"),
            retouch = RetouchSelection(boxesRef = "b", smoothingPct = 20),
        )
        assertFalse(all.isIdentity)
        assertFalse(all.isAiIdentity)
        assertTrue(all.copy(sky = null).isSkyIdentity)
        assertTrue(all.copy(retouch = null).isRetouchIdentity)
        assertTrue(
            all.copy(
                aiErase = null, sky = null, bokeh = null, retouch = null,
            ).isAiIdentity,
        )
        // Empty erase normalises to identity like the region family.
        assertTrue(
            all.copy(aiErase = AiEraseLayers(emptyList())).isAiEraseIdentity,
        )
    }
}
