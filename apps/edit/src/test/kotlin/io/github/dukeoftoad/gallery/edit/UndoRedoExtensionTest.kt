package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The undo/redo extension capstone (r38; the roadmap's last Phase 7
 * line, made executable): "Undo/redo extended to cover the new layer
 * types." The mechanism is the r30 snapshot stack over WHOLE recipes;
 * every round pinned its per-commit granularity; this test walks a
 * realistic MIXED session --- one layer of each of the five families,
 * a commit sequence across every family's controls, undo all the way
 * back to the launch state, redo all the way forward --- asserting the
 * EXACT recipe at every step.
 */
class UndoRedoExtensionTest {

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

    @Test
    fun `a mixed five-family session walks undo and redo exactly`() {
        val launch = EditRecipe()
        val session = EditSessionState(launch)

        // 1. Add one layer of each family, in family order, one snapshot
        //    per add (the per-commit granularity every round pinned).
        val withText = launch.copy(
            textLayers = TextLayers(listOf(textLayer("t1"))),
        )
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
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        assertEquals(withFrame, session.current)

        // 2. A per-control edit inside every family, one snapshot each.
        val textEdited = withFrame.copy(
            textLayers = TextLayers(listOf(textLayer("t1", size = 44))),
        )
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

        // 3. Undo ALL the way back to the launch state, asserting the
        //    exact recipe at every step.
        assertEquals(frameEdited, session.current)
        assertEquals(regionEdited, session.undo())
        assertEquals(strokeEdited, session.undo())
        assertEquals(stickerEdited, session.undo())
        assertEquals(textEdited, session.undo())
        assertEquals(withFrame, session.undo())
        assertEquals(withRegion, session.undo())
        assertEquals(withStroke, session.undo())
        assertEquals(withSticker, session.undo())
        assertEquals(withText, session.undo())
        assertEquals(launch, session.undo())
        assertFalse(session.canUndo)
        assertEquals(launch, session.current)

        // 4. Redo ALL the way forward --- the same ten steps, same order.
        assertEquals(withText, session.redo())
        assertEquals(withSticker, session.redo())
        assertEquals(withStroke, session.redo())
        assertEquals(withRegion, session.redo())
        assertEquals(withFrame, session.redo())
        assertEquals(textEdited, session.redo())
        assertEquals(stickerEdited, session.redo())
        assertEquals(strokeEdited, session.redo())
        assertEquals(regionEdited, session.redo())
        assertEquals(frameEdited, session.redo())
        assertFalse(session.canRedo)
        assertEquals(frameEdited, session.current)
    }

    @Test
    fun `the depth cap bounds the mixed session without losing the current state`() {
        val session = EditSessionState(EditRecipe(), maxDepth = 4)
        var recipe = EditRecipe()
        for (i in 1..12) {
            recipe = recipe.copy(
                textLayers = TextLayers(listOf(textLayer("t$i"))),
            )
            session.commit(recipe)
        }
        // 12 commits over a 4-deep cap: undo four times to the floor.
        var undos = 0
        while (session.canUndo) {
            session.undo()
            undos++
        }
        assertEquals(4, undos) // the launch state is the first to go: the cap is honest
        // The current state after the walk is still a real recipe.
        assertTrue(session.current.textLayers?.layers?.size == 1)
    }

    @Test
    fun `the sidecar round-trip preserves the mixed session for the next launch`() {
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
        )
        val wire = EditRecipeCodec.toJson(mixed)
        val restored = (EditRecipeCodec.fromJson(wire) as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(mixed, restored)
        assertFalse(restored.isIdentity)
    }
}
