package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r41 undo/redo extension capstone: the r38 mixed-session test grown
 * across ALL NINE families --- one layer/selection of each family, a
 * commit sequence across every family's controls, undo all the way back
 * to the launch state, redo all the way forward --- asserting the EXACT
 * recipe at every step. The mechanism is the r30 snapshot stack; every
 * family pinned its per-commit granularity; the AI families' controls
 * (the sky opacity, the bokeh strength/focal, the retouch trio) commit
 * one snapshot each, exactly like the frame width's single-selection
 * keys.
 */
class UndoRedoAiExtensionTest {

    private fun textLayer(id: String, size: Int = 20) =
        TextLayer(id = id, text = "Hello $id", fontId = "caveat", sizePct = size)

    private fun strokePoints() = listOf(
        DrawLayer.Point(0.1, 0.1),
        DrawLayer.Point(0.5, 0.5),
        DrawLayer.Point(0.9, 0.9),
    )

    private fun regionPoints() = listOf(
        RegionLayer.Point(0.2, 0.2),
        RegionLayer.Point(0.8, 0.2),
        RegionLayer.Point(0.5, 0.9),
    )

    private fun erasePoints() = listOf(
        AiEraseLayer.Point(0.2, 0.2),
        AiEraseLayer.Point(0.9, 0.2),
        AiEraseLayer.Point(0.5, 0.9),
    )

    @Test
    fun `a mixed nine-family session walks undo and redo exactly`() {
        val launch = EditRecipe()
        val session = EditSessionState(launch)

        // 1. Add one layer/selection of each family, in family order, one
        //    snapshot per add (the per-commit granularity every round
        //    pinned).
        val withText = launch.copy(textLayers = TextLayers(listOf(textLayer("t1"))))
        session.commit(withText)
        val withSticker = withText.copy(
            stickerLayers = StickerLayers(listOf(StickerLayer(id = "s1", stickerId = "heart", sizePct = 25))),
        )
        session.commit(withSticker)
        val withStroke = withSticker.copy(
            drawLayers = DrawLayers(
                listOf(DrawLayer(id = "d1", colorArgb = 0xFFF06292L, widthPct = 6, points = strokePoints())),
            ),
        )
        session.commit(withStroke)
        val withRegion = withStroke.copy(
            mosaicRegions = RegionLayers(
                listOf(RegionLayer(id = "r1", kind = RegionKind.MOSAIC, strengthPct = 15, points = regionPoints())),
            ),
        )
        session.commit(withRegion)
        val withFrame = withRegion.copy(
            frame = FrameSelection(frameId = "film", widthPct = 30, colorArgb = 0xFFFDD835L),
        )
        session.commit(withFrame)
        val withErase = withFrame.copy(
            aiErase = AiEraseLayers(
                listOf(AiEraseLayer(id = "e1", points = erasePoints(), artifactRef = "e1-1a2b3c4d")),
            ),
        )
        session.commit(withErase)
        val withSky = withErase.copy(
            sky = SkySelection(maskRef = "sky-90ab", skyId = "dusk", opacityPct = 100),
        )
        session.commit(withSky)
        val withBokeh = withSky.copy(
            bokeh = BokehSelection(depthRef = "bokeh-77f", blurStrengthPct = 40, focalDepthNorm = 0.5),
        )
        session.commit(withBokeh)
        val withRetouch = withBokeh.copy(
            retouch = RetouchSelection(boxesRef = "ret-0f1e", smoothingPct = 0),
        )
        session.commit(withRetouch)
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        assertEquals(withRetouch, session.current)

        // 2. A per-control edit inside every family, one snapshot each.
        val textEdited = withRetouch.copy(textLayers = TextLayers(listOf(textLayer("t1", size = 44))))
        session.commit(textEdited)
        val stickerEdited = textEdited.copy(
            stickerLayers = StickerLayers(
                listOf(StickerLayer(id = "s1", stickerId = "heart", sizePct = 25, rotationDeg = 30)),
            ),
        )
        session.commit(stickerEdited)
        val strokeEdited = stickerEdited.copy(
            drawLayers = DrawLayers(
                listOf(DrawLayer(id = "d1", colorArgb = 0xFFF06292L, widthPct = 6, smoothingPct = 100, points = strokePoints())),
            ),
        )
        session.commit(strokeEdited)
        val regionEdited = strokeEdited.copy(
            mosaicRegions = RegionLayers(
                listOf(RegionLayer(id = "r1", kind = RegionKind.LINEAR_BLUR, strengthPct = 15, points = regionPoints())),
            ),
        )
        session.commit(regionEdited)
        val frameEdited = regionEdited.copy(
            frame = FrameSelection(frameId = "film", widthPct = 55, colorArgb = 0xFFFDD835L),
        )
        session.commit(frameEdited)
        val eraseEdited = frameEdited.copy(
            aiErase = AiEraseLayers(
                listOf(AiEraseLayer(id = "e1", points = erasePoints(), artifactRef = "e1-2b3c4d5e")),
            ),
        )
        session.commit(eraseEdited)
        val skyEdited = eraseEdited.copy(sky = eraseEdited.sky?.copy(opacityPct = 70))
        session.commit(skyEdited)
        val bokehEdited = skyEdited.copy(
            bokeh = skyEdited.bokeh?.copy(blurStrengthPct = 65, focalDepthNorm = 0.3),
        )
        session.commit(bokehEdited)
        val retouchEdited = bokehEdited.copy(
            retouch = bokehEdited.retouch?.copy(smoothingPct = 30, eyeBrightenPct = 15, faceSlimPct = 10),
        )
        session.commit(retouchEdited)

        // 3. Undo ALL the way back to the launch state, asserting the
        //    exact recipe at every step.
        assertEquals(retouchEdited, session.current)
        assertEquals(bokehEdited, session.undo())
        assertEquals(skyEdited, session.undo())
        assertEquals(eraseEdited, session.undo())
        assertEquals(frameEdited, session.undo())
        assertEquals(regionEdited, session.undo())
        assertEquals(strokeEdited, session.undo())
        assertEquals(stickerEdited, session.undo())
        assertEquals(textEdited, session.undo())
        assertEquals(withRetouch, session.undo())
        assertEquals(withBokeh, session.undo())
        assertEquals(withSky, session.undo())
        assertEquals(withErase, session.undo())
        assertEquals(withFrame, session.undo())
        assertEquals(withRegion, session.undo())
        assertEquals(withStroke, session.undo())
        assertEquals(withSticker, session.undo())
        assertEquals(withText, session.undo())
        assertEquals(launch, session.undo())
        assertFalse(session.canUndo)
        assertEquals(launch, session.current)

        // 4. Redo ALL the way forward --- the same eighteen steps, same order.
        assertEquals(withText, session.redo())
        assertEquals(withSticker, session.redo())
        assertEquals(withStroke, session.redo())
        assertEquals(withRegion, session.redo())
        assertEquals(withFrame, session.redo())
        assertEquals(withErase, session.redo())
        assertEquals(withSky, session.redo())
        assertEquals(withBokeh, session.redo())
        assertEquals(withRetouch, session.redo())
        assertEquals(textEdited, session.redo())
        assertEquals(stickerEdited, session.redo())
        assertEquals(strokeEdited, session.redo())
        assertEquals(regionEdited, session.redo())
        assertEquals(frameEdited, session.redo())
        assertEquals(eraseEdited, session.redo())
        assertEquals(skyEdited, session.redo())
        assertEquals(bokehEdited, session.redo())
        assertEquals(retouchEdited, session.redo())
        assertFalse(session.canRedo)
        assertEquals(retouchEdited, session.current)
    }

    @Test
    fun `the sidecar round-trip preserves the nine-family session for the next launch`() {
        // The ADR-0011 promise: every committed change persists; the
        // restored sidecar reproduces the exact mixed recipe.
        val mixed = EditRecipe(
            textLayers = TextLayers(listOf(textLayer("t1"))),
            stickerLayers = StickerLayers(listOf(StickerLayer(id = "s1", stickerId = "heart", sizePct = 25))),
            drawLayers = DrawLayers(
                listOf(DrawLayer(id = "d1", colorArgb = 0xFFF06292L, widthPct = 6, points = strokePoints())),
            ),
            mosaicRegions = RegionLayers(
                listOf(RegionLayer(id = "r1", kind = RegionKind.MOSAIC, strengthPct = 15, points = regionPoints())),
            ),
            frame = FrameSelection(frameId = "polaroid", widthPct = 25),
            aiErase = AiEraseLayers(
                listOf(AiEraseLayer(id = "e1", points = erasePoints(), artifactRef = "e1-1a2b3c4d")),
            ),
            sky = SkySelection(maskRef = "sky-90ab", skyId = "day-clear", opacityPct = 90),
            bokeh = BokehSelection(depthRef = "bokeh-77f", blurStrengthPct = 55, focalDepthNorm = 0.4),
            retouch = RetouchSelection(boxesRef = "ret-0f1e", smoothingPct = 25, eyeBrightenPct = 10, faceSlimPct = 5),
        )
        val wire = EditRecipeCodec.toJson(mixed)
        val restored = (EditRecipeCodec.fromJson(wire) as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(mixed, restored)
        assertFalse(restored.isIdentity)
        assertFalse(restored.isAiIdentity)
    }

    @Test
    fun `undoing a re-applied erase restores the previous artifact key`() {
        // The store's append-only promise: the earlier artifact id is
        // still present in the recipe after the undo (the coherence the
        // content-hash keys buy).
        val session = EditSessionState(EditRecipe(), maxDepth = 8)
        val first = EditRecipe(
            aiErase = AiEraseLayers(
                listOf(AiEraseLayer(id = "e1", points = erasePoints(), artifactRef = "e1-11111111")),
            ),
        )
        session.commit(first)
        val reApplied = first.copy(
            aiErase = AiEraseLayers(
                listOf(AiEraseLayer(id = "e1", points = erasePoints(), artifactRef = "e1-22222222")),
            ),
        )
        session.commit(reApplied)
        assertEquals("e1-22222222", session.current.aiErase?.layers?.get(0)?.artifactRef)
        assertEquals("e1-11111111", session.undo()?.aiErase?.layers?.get(0)?.artifactRef)
    }
}
