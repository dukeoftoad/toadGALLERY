package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r41 AI pixel kernels (the contract's hand-grid set, the r37
 * discipline verbatim): the erase paste, the sky cover-crop blend, the
 * depth-driven variable blur (with the prefix-sum/pass equivalence
 * pinned against a naive reference), the retouch smoothing/brighten/slim
 * --- plus the pipeline tail assertions.
 */
class AiRenderTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ---- The erase paste ----

    @Test
    fun `the erase paste maps the patch over the masked block exactly`() {
        // 6x6; the mask is the 3x3 block (2..4)x(2..4); the patch is 3x3
        // with placement fractions covering the same block's centres.
        val w = 6
        val h = 6
        val maskBool = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            x in 2..4 && y in 2..4
        }
        val mask = FractionRleMask.fromMaskAt(maskBool, w, h)
        val patch = FillPatch(
            pixels = IntArray(9) { i -> argb(i * 10, i, 7) },
            width = 3,
            height = 3,
            left = 2.5 / 6.0,
            top = 2.5 / 6.0,
            right = 4.5 / 6.0,
            bottom = 4.5 / 6.0,
        )
        val pixels = IntArray(w * h) { argb(0, 0, 0) }
        AiKernels.pasteEraseFill(pixels, w, h, mask, patch)
        // The block's corners map to the patch's matching corners.
        assertEquals(argb(0, 0, 7), pixels[2 * w + 2]) // u=v=0 -> patch(0,0)
        assertEquals(argb(80, 8, 7), pixels[4 * w + 4]) // u=v=1 -> patch(2,2), index 8
        // (2,4): the mask's bottom-left -> the patch's bottom-left (0,2), index 6.
        assertEquals(argb(60, 6, 7), pixels[4 * w + 2])
        // The centre maps to the patch's centre.
        assertEquals(argb(40, 4, 7), pixels[3 * w + 3]) // u=v=0.5 -> patch(1,1), index 4
        // Unmasked pixels untouched; the alpha forced opaque.
        assertEquals(argb(0, 0, 0), pixels[0])
        assertEquals(argb(0, 0, 0), pixels[5 * w + 5])
    }

    // ---- The sky cover-crop blend ----

    @Test
    fun `the sky blend cover-crops the source and blends at the opacity`() {
        // 8x8 grid; the mask is the top half (rows 0..3, all columns).
        // The sky source is 8x2 (relatively wider than the 8x4 box): the
        // cover-crop keeps its columns 2..5.
        val w = 8
        val h = 8
        val maskBool = BooleanArray(w * h) { i -> (i / w) < 4 }
        val mask = FractionRleMask.fromMaskAt(maskBool, w, h)
        val source = IntArray(8 * 2) { i ->
            val x = i % 8
            val y = i / 8
            argb(x * 10, y * 10, 0)
        }
        val pixels = IntArray(w * h) { argb(0, 0, 0) }
        AiKernels.applySkyBlend(pixels, w, h, mask, SkySource.Pixels(source, 8, 2), 50)
        // (0,0): sx = 2 + (0*4)/8 = 2, sy = 0 + (0*2)/4 = 0 -> sky(20,0)
        // out = (0*50 + 20*50)/100 = 10.
        assertEquals(argb(10, 0, 0), pixels[0])
        // (4,0): sx = 2 + (4*4)/8 = 4, sy = 0 -> sky(40,0) -> out 20.
        assertEquals(argb(20, 0, 0), pixels[4])
        // (7,3): sx = 2 + (7*4)/8 = 5, sy = 0 + (3*2)/4 = 1 -> sky(50,10)
        // out r = 50/2 = 25, g = 10/2 = 5.
        assertEquals(argb(25, 5, 0), pixels[3 * w + 7])
        // The unmasked bottom half is untouched.
        assertEquals(argb(0, 0, 0), pixels[4 * w + 0])
        assertEquals(argb(0, 0, 0), pixels[7 * w + 7])
    }

    @Test
    fun `a parametric sky blends its own render at full opacity`() {
        // The parametric path renders at the box size (the cover-crop is
        // the identity), so at opacity 100 the masked pixels equal the
        // authored render exactly.
        val w = 8
        val h = 8
        val maskBool = BooleanArray(w * h) { i -> (i / w) < 4 }
        val mask = FractionRleMask.fromMaskAt(maskBool, w, h)
        val pixels = IntArray(w * h) { argb(0, 0, 0) }
        AiKernels.applySkyBlend(pixels, w, h, mask, SkySource.Parametric("dusk"), 100)
        val rendered = BundledSkies.render("dusk", 8, 4)!!
        for (y in 0 until 4) {
            for (x in 0 until 8) {
                assertEquals("at ($x,$y)", rendered[y * 8 + x], pixels[y * w + x])
            }
        }
        // The unmasked bottom half is untouched.
        assertEquals(argb(0, 0, 0), pixels[4 * w + 0])
    }

    // ---- The depth-driven variable blur ----

    @Test
    fun `the variable blur is exact on the hand grid with the focal band sharp`() {
        // 8x8; depth grid 8x8 (identity mapping): row 0 carries d=255
        // (dist 255 from focal 0 -> r=1), every other row d=0 (r=0,
        // untouched). maxRadius = 100*8/1000 -> 0 -> coerce 1.
        val w = 8
        val h = 8
        val depthValues = ByteArray(w * h) { i -> if (i / w == 0) 255.toByte() else 0.toByte() }
        val depth = DepthGrid(depthValues, w, h)
        val pixels = IntArray(w * h) { i -> argb((i % w) * 8, 0, 0) }
        AiKernels.applyVariableBlur(pixels, w, h, depth, 100, 0.0)
        // Row 0, pass 1 (horizontal, r=1, window [x-1,x+1] clamped):
        // x=0: (0+8)/2=4; x=1: (0+8+16)/3=8; ... x=7: (48+56)/2=52.
        // Pass 2 (vertical, window rows [0,1]): row 1 is untouched (8x).
        // Final: x -> floor((P[x] + 8x)/2): [2, 8, 16, 24, 32, 40, 48, 54].
        assertEquals(argb(2, 0, 0), pixels[0])
        assertEquals(argb(8, 0, 0), pixels[1])
        assertEquals(argb(16, 0, 0), pixels[2])
        assertEquals(argb(24, 0, 0), pixels[3])
        assertEquals(argb(32, 0, 0), pixels[4])
        assertEquals(argb(40, 0, 0), pixels[5])
        assertEquals(argb(48, 0, 0), pixels[6])
        assertEquals(argb(54, 0, 0), pixels[7])
        // Rows 1..7 untouched (the focal band: d=0=focal -> r=0).
        for (y in 1 until h) {
            for (x in 0 until w) {
                assertEquals("at ($x,$y)", argb(x * 8, 0, 0), pixels[y * w + x])
            }
        }
    }

    @Test
    fun `the prefix-sum passes equal the naive per-pixel loops`() {
        // A 24x20 deterministic grid (LCG), a rich depth pattern, radius
        // maxRadius = 100*20/1000 = 2; the separable prefix-sum kernel must
        // equal a naive per-pixel horizontal-then-vertical reference exactly.
        val w = 24
        val h = 20
        var seed = 987654321L
        fun next(): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0xFF).toInt()
        }
        val src = IntArray(w * h) { argb(next(), next(), next()) }
        val depthValues = ByteArray(w * h) { i -> (((i % w) * 7 + (i / w) * 13) % 256).toByte() }
        val depth = DepthGrid(depthValues, w, h)
        val byKernel = src.copyOf()
        AiKernels.applyVariableBlur(byKernel, w, h, depth, 100, 0.5)
        // The naive reference: H pass then V pass, per-pixel windows over
        // snapshots, integer floor averages.
        val byLoop = src.copyOf()
        val maxRadius = 2
        val focal8 = 128
        fun radiusAt(x: Int, y: Int): Int {
            val d = depthValues[y * w + x].toInt() and 0xFF
            val dist = if (d > focal8) d - focal8 else focal8 - d
            return maxRadius * dist / 255
        }
        val snapH = byLoop.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = radiusAt(x, y)
                if (r == 0) continue
                val lo = (x - r).coerceAtLeast(0)
                val hi = (x + r).coerceAtMost(w - 1)
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in lo..hi) {
                    val c = snapH[y * w + i]
                    sr += (c shr 16) and 0xFF
                    sg += (c shr 8) and 0xFF
                    sb += c and 0xFF
                }
                val count = hi - lo + 1
                byLoop[y * w + x] = (byLoop[y * w + x] and 0xFF000000.toInt()) or
                    (((sr / count) and 0xFF) shl 16) or (((sg / count) and 0xFF) shl 8) or ((sb / count) and 0xFF)
            }
        }
        val snapV = byLoop.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = radiusAt(x, y)
                if (r == 0) continue
                val lo = (y - r).coerceAtLeast(0)
                val hi = (y + r).coerceAtMost(h - 1)
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in lo..hi) {
                    val c = snapV[i * w + x]
                    sr += (c shr 16) and 0xFF
                    sg += (c shr 8) and 0xFF
                    sb += c and 0xFF
                }
                val count = hi - lo + 1
                byLoop[y * w + x] = (byLoop[y * w + x] and 0xFF000000.toInt()) or
                    (((sr / count) and 0xFF) shl 16) or (((sg / count) and 0xFF) shl 8) or ((sb / count) and 0xFF)
            }
        }
        assertTrue(byKernel.contentEquals(byLoop))
    }

    // ---- The retouch kernels ----

    @Test
    fun `the eye-band brighten hits the band rows only`() {
        // 10x10; one box (0.2,0.2)-(0.8,0.8) -> px x0=2..x1=8, y0=2..y1=8
        // (bw=bh=7); the band rows are 2+(7*20)/100=3 .. 2+(7*45)/100=5
        // (exclusive) -> rows 3..4, columns 2..8.
        val w = 10
        val h = 10
        val boxes = FaceBoxes(listOf(FaceBoxes.Box(0.2, 0.2, 0.8, 0.8)))
        val pixels = IntArray(w * h) { argb(10, 10, 10) }
        AiKernels.applyRetouch(
            pixels, w, h, boxes,
            RetouchSelection(boxesRef = "b", eyeBrightenPct = 100),
        )
        // delta = 100*255/200 = 127 -> 10+127 = 137 inside the band.
        assertEquals(argb(137, 137, 137), pixels[3 * w + 5])
        assertEquals(argb(137, 137, 137), pixels[4 * w + 8])
        // Row 2 (above the band) and row 5 (below it) are untouched.
        assertEquals(argb(10, 10, 10), pixels[2 * w + 5])
        assertEquals(argb(10, 10, 10), pixels[5 * w + 5])
        // Outside the box's columns is untouched even in the band rows.
        assertEquals(argb(10, 10, 10), pixels[3 * w + 1])
    }

    @Test
    fun `the slim warp samples from further out inside the box`() {
        // The same 10x10 box (x 2..8); slim 100 -> sampleX = 2 + (x-2)*2
        // clamped to 8: columns 2,3,4,5,6,7,8 sample 2,4,6,8,8,8,8.
        val w = 10
        val h = 10
        val boxes = FaceBoxes(listOf(FaceBoxes.Box(0.2, 0.2, 0.8, 0.8)))
        val pixels = IntArray(w * h) { i -> argb((i % w) * 10, 0, 0) }
        AiKernels.applyRetouch(
            pixels, w, h, boxes,
            RetouchSelection(boxesRef = "b", faceSlimPct = 100),
        )
        for (y in 2..8) {
            assertEquals("col 2 at y=$y", argb(20, 0, 0), pixels[y * w + 2]) // sample 2
            assertEquals("col 3 at y=$y", argb(40, 0, 0), pixels[y * w + 3]) // sample 4
            assertEquals("col 4 at y=$y", argb(60, 0, 0), pixels[y * w + 4]) // sample 6
            assertEquals("col 6 at y=$y", argb(80, 0, 0), pixels[y * w + 6]) // sample 8 (clamped)
            assertEquals("col 8 at y=$y", argb(80, 0, 0), pixels[y * w + 8]) // sample 8 (clamped)
        }
        // Outside the box's rows/columns is untouched.
        assertEquals(argb(10, 0, 0), pixels[0 * w + 1])
        assertEquals(argb(50, 0, 0), pixels[9 * w + 5])
    }

    @Test
    fun `the retouch smoothing smears a spike into its neighbourhood`() {
        // The same box; smoothing 40 -> radius = 40*7/1000 -> 0 -> coerce 1.
        // A single 49-valued spike at (5,5) over a zero region: the two
        // passes smear it to a 3x3 block of 5 (16/3 and 49/3 floors).
        val w = 10
        val h = 10
        val boxes = FaceBoxes(listOf(FaceBoxes.Box(0.2, 0.2, 0.8, 0.8)))
        val pixels = IntArray(w * h) { argb(0, 0, 0) }
        pixels[5 * w + 5] = argb(49, 0, 0)
        AiKernels.applyRetouch(
            pixels, w, h, boxes,
            RetouchSelection(boxesRef = "b", smoothingPct = 40),
        )
        assertEquals(argb(5, 0, 0), pixels[5 * w + 5]) // 49/3=16 after H, 16/3=5 after V
        assertEquals(argb(5, 0, 0), pixels[4 * w + 4]) // (0+0+16)/3
        assertEquals(argb(5, 0, 0), pixels[6 * w + 6])
        assertEquals(argb(5, 0, 0), pixels[4 * w + 6])
        // Distant pixels inside the box stay zero (the blur is bounded).
        assertEquals(argb(0, 0, 0), pixels[2 * w + 2])
        // Outside the box the spike never existed; nothing to assert there.
    }

    // ---- The pipeline tail ----

    @Test
    fun `the stage order grew the AI tail before the regions`() {
        assertTrue(
            EditPipeline.STAGE_ORDER.contains(
                "vignette -> sharpen -> AI erase -> AI sky -> AI bokeh -> AI retouch -> mosaic/blur regions",
            ),
        )
    }

    @Test
    fun `an AI-stages-only recipe is not identity and the stage runs`() {
        val w = 6
        val h = 6
        val maskBool = BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            x in 2..4 && y in 2..4
        }
        val mask = FractionRleMask.fromMaskAt(maskBool, w, h)
        val patch = FillPatch(
            pixels = IntArray(9) { argb(200, 200, 200) },
            width = 3,
            height = 3,
            left = 2.5 / 6.0,
            top = 2.5 / 6.0,
            right = 4.5 / 6.0,
            bottom = 4.5 / 6.0,
        )
        val recipe = EditRecipe(
            aiErase = AiEraseLayers(
                listOf(
                    AiEraseLayer(
                        id = "e1",
                        points = listOf(
                            AiEraseLayer.Point(0.2, 0.2),
                            AiEraseLayer.Point(0.9, 0.2),
                            AiEraseLayer.Point(0.5, 0.9),
                        ),
                        artifactRef = "e1-deadbeef",
                    ),
                ),
            ),
        )
        assertFalse(recipe.isIdentity)
        assertFalse(recipe.isAiIdentity)
        assertTrue(recipe.isGeometryIdentity && recipe.isAdjustmentIdentity && recipe.isRegionIdentity)
        val inputs = EditAiStageInputs(
            eraseMasks = mapOf("e1" to mask),
            eraseFills = mapOf("e1" to patch),
            skyMask = null,
            skySource = null,
            depth = null,
            faceBoxes = null,
            retouchConsentGranted = false,
        )
        val pixels = IntArray(w * h) { argb(0, 0, 0) }
        val out = EditPipeline.apply(pixels, w, h, recipe, ai = inputs)
        assertEquals(argb(200, 200, 200), out.pixels[3 * w + 3])
        assertEquals(argb(200, 200, 200), out.pixels[2 * w + 2])
        assertEquals(argb(0, 0, 0), out.pixels[0])
        // The absent-bundle degrade: with no resolved inputs the erase
        // layer renders NOTHING (the tolerance contract). A fresh black
        // grid --- the apply above mutated `pixels` in place (the house
        // pipeline contract: the stages write the grid they receive).
        val degraded = EditPipeline.apply(
            IntArray(w * h) { argb(0, 0, 0) },
            w,
            h,
            recipe,
            ai = EditAiStageInputs.ABSENT,
        )
        for (i in degraded.pixels.indices) {
            assertEquals("at $i", argb(0, 0, 0), degraded.pixels[i])
        }
    }

    @Test
    fun `the retouch stage refuses under consent-off even with a restored sidecar`() {
        // The layer stays verbatim in the recipe; the stage skips.
        val w = 10
        val h = 10
        val boxes = FaceBoxes(listOf(FaceBoxes.Box(0.2, 0.2, 0.8, 0.8)))
        val recipe = EditRecipe(
            retouch = RetouchSelection(boxesRef = "ret-1", eyeBrightenPct = 100),
        )
        val pixels = IntArray(w * h) { argb(10, 10, 10) }
        val inputsOff = EditAiStageInputs(
            eraseMasks = emptyMap(),
            eraseFills = emptyMap(),
            skyMask = null,
            skySource = null,
            depth = null,
            faceBoxes = boxes,
            retouchConsentGranted = false,
        )
        val refused = EditPipeline.apply(pixels, w, h, recipe, ai = inputsOff)
        assertEquals(argb(10, 10, 10), refused.pixels[3 * w + 5]) // untouched
        assertEquals("ret-1", recipe.retouch?.boxesRef) // verbatim in the recipe
        val inputsOn = EditAiStageInputs(
            eraseMasks = emptyMap(),
            eraseFills = emptyMap(),
            skyMask = null,
            skySource = null,
            depth = null,
            faceBoxes = boxes,
            retouchConsentGranted = true,
        )
        val applied = EditPipeline.apply(pixels.copyOf(), w, h, recipe, ai = inputsOn)
        assertEquals(argb(137, 137, 137), applied.pixels[3 * w + 5]) // 10 + 127
    }
}
