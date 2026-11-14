package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** History-strip naming pins: per-family semantic diffs, revert priority order, honest generic fallback. */
class LilypadHistoryNamesTest {

    private val base = EditRecipe()

    private fun diff(next: EditRecipe): LilypadHistoryNames.Change =
        LilypadHistoryNames.changeBetween(base, next)

    // ---- the adjustment sliders (the everyday gesture) ----

    @Test
    fun `a slider commit names the slider and its new value`() {
        assertEquals(
            LilypadHistoryNames.Slider("exposure", 35),
            diff(base.copy(exposure = 35)),
        )
        assertEquals(
            LilypadHistoryNames.Slider("temperature", -12),
            diff(base.copy(temperature = -12)),
        )
        // The board's LAST slider too (the order is ADJUST_SLIDERS's own).
        assertEquals(
            LilypadHistoryNames.Slider("sharpness", 40),
            diff(base.copy(sharpness = 40)),
        )
    }

    @Test
    fun `the curve layer names the state's point count`() {
        val three = ToneCurves(
            listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.62), CurvePoint(1.0, 1.0)),
        )
        assertEquals(
            LilypadHistoryNames.Curve(3),
            diff(base.copy(toneCurves = three)),
        )
        // A straightened-back curve reads as the honest zero.
        assertEquals(
            LilypadHistoryNames.Curve(0),
            diff(base.copy(toneCurves = ToneCurves(LilypadCurveMath.IDENTITY))),
        )
    }

    @Test
    fun `the curve count totals the channels' non-identity points`() {
        val curves = ToneCurves(
            master = LilypadCurveMath.IDENTITY,
            red = listOf(CurvePoint(0.0, 0.0), CurvePoint(1.0, 0.9)),
            blue = listOf(CurvePoint(0.0, 0.1), CurvePoint(1.0, 1.0)),
        )
        assertEquals(4, LilypadHistoryNames.curvePointCount(curves))
        assertEquals(0, LilypadHistoryNames.curvePointCount(null))
        assertEquals(0, LilypadHistoryNames.curvePointCount(ToneCurves(LilypadCurveMath.IDENTITY)))
    }

    @Test
    fun `the look names the selection, a deselection included`() {
        assertEquals(
            LilypadHistoryNames.Look("warm-film", 80),
            diff(base.copy(lut = LutSelection("warm-film", 80))),
        )
        // A deselection from a selected state (the None card): empty id, dialed-out intensity.
        assertEquals(
            LilypadHistoryNames.Look("", 0),
            LilypadHistoryNames.changeBetween(
                base.copy(lut = LutSelection("warm-film", 80)),
                base,
            ),
        )
    }

    // ---- the geometry family ----

    @Test
    fun `the geometry family names itself`() {
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Rotate),
            diff(base.copy(quarterTurns = 1)),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Flip),
            diff(base.copy(horizontalFlip = true)),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Flip),
            diff(base.copy(verticalFlip = true)),
        )
        assertEquals(
            LilypadHistoryNames.Straighten(7),
            diff(base.copy(straightenDeg = 7.4)),
        )
        assertEquals(
            LilypadHistoryNames.Straighten(-12),
            diff(base.copy(straightenDeg = -12.4)),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Crop),
            diff(base.copy(crop = CropRect(0.1, 0.1, 0.9, 0.9))),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Perspective),
            diff(base.copy(perspective = PerspectiveQuad(4.0, 0.0, -4.0, 0.0, 0.0, 0.0, 0.0, 0.0))),
        )
    }

    // ---- the colour grade and the layer families ----

    @Test
    fun `the colour grade and the layer families name themselves`() {
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Color),
            diff(base.copy(hslBands = HslBands(blue = HslBandAdjust(hue = 10)))),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Text),
            diff(base.copy(textLayers = TextLayers(listOf(TextLayer("t1", "hi", "manrope-400"))))),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Stickers),
            diff(base.copy(stickerLayers = StickerLayers(listOf(StickerLayer("s1", "smile"))))),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Draw),
            diff(
                base.copy(
                    drawLayers = DrawLayers(
                        listOf(DrawLayer("d1", points = listOf(DrawLayer.Point(0.1, 0.1)))),
                    ),
                ),
            ),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Regions),
            diff(
                base.copy(
                    mosaicRegions = RegionLayers(
                        listOf(
                            RegionLayer(
                                "r1",
                                RegionKind.MOSAIC,
                                points = listOf(
                                    RegionLayer.Point(0.1, 0.1),
                                    RegionLayer.Point(0.4, 0.1),
                                    RegionLayer.Point(0.4, 0.4),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Frame),
            diff(base.copy(frame = FrameSelection(frameId = "classic"))),
        )
    }

    // ---- the AI containers ----

    @Test
    fun `the AI containers name themselves`() {
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Erase),
            diff(
                base.copy(
                    aiErase = AiEraseLayers(
                        listOf(
                            AiEraseLayer(
                                "e1",
                                points = listOf(
                                    AiEraseLayer.Point(0.1, 0.1),
                                    AiEraseLayer.Point(0.4, 0.1),
                                    AiEraseLayer.Point(0.4, 0.4),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Sky),
            diff(base.copy(sky = SkySelection(maskRef = "m", skyId = "golden-hour"))),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Bokeh),
            diff(base.copy(bokeh = BokehSelection(depthRef = "d", blurStrengthPct = 30))),
        )
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Retouch),
            diff(base.copy(retouch = RetouchSelection(boxesRef = "b", smoothingPct = 20))),
        )
    }

    // ---- the priority and the fallback ----

    @Test
    fun `a wide revert names the adjustment family first`() {
        // A Reset-shaped commit: sliders, curve, look and geometry revert at once; adjustments read first.
        val edited = base.copy(
            exposure = 35,
            contrast = -10,
            toneCurves = ToneCurves(
                listOf(CurvePoint(0.0, 0.0), CurvePoint(0.5, 0.62), CurvePoint(1.0, 1.0)),
            ),
            lut = LutSelection("warm-film", 80),
            straightenDeg = 4.0,
        )
        assertEquals(
            LilypadHistoryNames.Slider("exposure", 0),
            LilypadHistoryNames.changeBetween(edited, base),
        )
    }

    @Test
    fun `an identical diff is the honest generic`() {
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Edit),
            LilypadHistoryNames.changeBetween(base, base.copy()),
        )
    }

    @Test
    fun `a schema-only difference no family describes falls back honestly`() {
        // schemaVersion rides the recipe; nothing else changes. No family describes it, so the
        // generic chip answers --- never a wrong specific label.
        assertEquals(
            LilypadHistoryNames.Plain(LilypadHistoryNames.Family.Edit),
            LilypadHistoryNames.changeBetween(base, base.copy(schemaVersion = 1)),
        )
    }
}
