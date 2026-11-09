package com.toadgallery.editor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r41 inference seam over FAKES (the contract's seam set): the
 * dedicated degradations propagate unchanged, the honest no-sky refusal,
 * the derived masks (the erase refinement + the sky mask) over hand
 * saliency maps, the yunet decode over hand outputs (with NMS), the
 * cited preprocessing constants, the migan polarity, the cancellation,
 * and the commit coordinator end-to-end.
 */
class EditAiEngineTest {

    // ---- the fakes ----

    /** A scriptable fake: every run answers a preset; calls are recorded. */
    private class FakeInference(
        var saliencyResult: AiRun<IntArray> = AiRun.Unavailable(EditAiDegradation.MODELS_MISSING_FROM_BUILD),
        var inpaintResult: AiRun<IntArray> = AiRun.Unavailable(EditAiDegradation.MODELS_MISSING_FROM_BUILD),
        var depthResult: AiRun<IntArray> = AiRun.Unavailable(EditAiDegradation.MODELS_MISSING_FROM_BUILD),
        var facesResult: AiRun<List<FaceBoxes.Box>> = AiRun.Unavailable(EditAiDegradation.MODELS_MISSING_FROM_BUILD),
    ) : EditAiInference {
        var lastMask8: ByteArray? = null
        var saliencyCalls = 0
        var inpaintCalls = 0

        override fun saliencyMap(
            working: IntArray,
            width: Int,
            height: Int,
            progress: AiProgress,
        ): AiRun<IntArray> {
            saliencyCalls++
            return saliencyResult
        }

        override fun inpaintFill(
            working: IntArray,
            width: Int,
            height: Int,
            mask8: ByteArray,
            progress: AiProgress,
        ): AiRun<IntArray> {
            inpaintCalls++
            lastMask8 = mask8
            return inpaintResult
        }

        override fun depthMap(
            working: IntArray,
            width: Int,
            height: Int,
            progress: AiProgress,
        ): AiRun<IntArray> = depthResult

        override fun faceBoxes(
            working: IntArray,
            width: Int,
            height: Int,
            progress: AiProgress,
        ): AiRun<List<FaceBoxes.Box>> = facesResult
    }

    private val progress = object : AiProgress {
        override fun report(fraction: Int) {}
        override fun isCancelled(): Boolean = false
    }

    private val cancelled = object : AiProgress {
        override fun report(fraction: Int) {}
        override fun isCancelled(): Boolean = true
    }

    /** A 320x320 hand saliency map: the subject is rows/cols 100..220 at 255, else 0. */
    private fun subjectSaliency(): IntArray {
        val size = EditAiModels.SALIENCY_SIZE
        val map = IntArray(size * size) { 0 }
        for (y in 100..220) {
            for (x in 100..220) {
                map[y * size + x] = 255
            }
        }
        return map
    }

    // ---- the dedicated degradations ----

    @Test
    fun `a missing-model run answers its own kind and never a generic failure`() {
        val fake = FakeInference()
        val erase = EditAiCommit.erase(fake, IntArray(36), 6, 6, seedPoints(), progress)
        assertTrue(erase is AiRun.Unavailable)
        assertEquals(EditAiDegradation.MODELS_MISSING_FROM_BUILD, (erase as AiRun.Unavailable).kind)
        val sky = EditAiCommit.sky(fake, IntArray(36), 6, 6, progress)
        assertEquals(EditAiDegradation.MODELS_MISSING_FROM_BUILD, (sky as AiRun.Unavailable).kind)
        val depth = EditAiCommit.depth(fake, IntArray(36), 6, 6, progress)
        assertEquals(EditAiDegradation.MODELS_MISSING_FROM_BUILD, (depth as AiRun.Unavailable).kind)
        val faces = EditAiCommit.faces(fake, IntArray(36), 6, 6, progress)
        assertEquals(EditAiDegradation.MODELS_MISSING_FROM_BUILD, (faces as AiRun.Unavailable).kind)
    }

    @Test
    fun `a broken-runtime run propagates from inside the chain`() {
        // The inpaint stage breaks while the saliency stage succeeds: the
        // erase chain answers RUNTIME_BROKEN (the second leg's degradation).
        val fake = FakeInference(
            saliencyResult = AiRun.Ok(subjectSaliency()),
            inpaintResult = AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN),
        )
        val erase = EditAiCommit.erase(fake, IntArray(36), 6, 6, seedPoints(), progress)
        assertEquals(EditAiDegradation.RUNTIME_BROKEN, (erase as AiRun.Unavailable).kind)
    }

    @Test
    fun `a cancelled commit answers cancelled, never an error`() {
        val fake = FakeInference(saliencyResult = AiRun.Ok(subjectSaliency()))
        val erase = EditAiCommit.erase(fake, IntArray(36), 6, 6, seedPoints(), cancelled)
        assertEquals(EditAiDegradation.CANCELLED, (erase as AiRun.Unavailable).kind)
        assertEquals(0, fake.saliencyCalls) // never started
    }

    // ---- the honest no-sky refusal ----

    @Test
    fun `a saliency map with no subject refuses no-sky`() {
        val empty = IntArray(EditAiModels.SALIENCY_SIZE * EditAiModels.SALIENCY_SIZE) { 0 }
        val refused = EditAiDerive.skyMask(64, 64, empty)
        assertEquals(EditAiDegradation.NO_SKY_FOUND, (refused as AiRun.Unavailable).kind)
    }

    @Test
    fun `the sky mask is the region above the subject's top edge minus the subject`() {
        // Working 64x64; the subject (320 rows/cols 100..220) maps to the
        // working grid's rows/cols ~20..44; the sky is everything strictly
        // above the subject's top row, minus the subject (which cannot be
        // above its own top).
        val ok = EditAiDerive.skyMask(64, 64, subjectSaliency()) as AiRun.Ok
        val sky = ok.value
        val mask = sky.maskAt(64, 64)
        // Row 0 is sky everywhere (the subject starts ~row 20).
        for (x in intArrayOf(0, 17, 32, 63)) {
            assertTrue("row 0 at x=$x", mask[0 * 64 + x])
        }
        // The subject's rows are not sky.
        assertFalse(mask[30 * 64 + 30])
        // The count: every row above the subject's top, full width.
        var count = 0
        for (m in mask) if (m) count++
        assertEquals(64 * 20, count)
    }

    // ---- the erase refinement ----

    @Test
    fun `the refined erase mask is the seed intersected with the saliency subject`() {
        // Seed: the (2..4)x(2..4) block on 6x6; subject (320 100..220)
        // maps to (2..3)x(2..3) on 6x6 -> refined is (2..3)x(2..3).
        val seed = BooleanArray(36) { i ->
            val x = i % 6
            val y = i / 6
            x in 2..4 && y in 2..4
        }
        val refined = EditAiDerive.refinedEraseMask(seed, 6, 6, subjectSaliency())
        val mask = refined.maskAt(6, 6)
        assertTrue(mask[2 * 6 + 2]) // in both
        assertTrue(mask[3 * 6 + 3])
        assertFalse(mask[4 * 6 + 4]) // seed only
        assertFalse(mask[2 * 6 + 4]) // seed only (col 4)
        assertFalse(mask[0]) // in neither
        var count = 0
        for (m in mask) if (m) count++
        assertEquals(4, count)
    }

    @Test
    fun `a seed that finds no subject degrades to the seed itself`() {
        // The saliency subject misses the seed entirely -> the honest
        // degrade is the SEED: the fill covers what the user painted.
        val empty = IntArray(EditAiModels.SALIENCY_SIZE * EditAiModels.SALIENCY_SIZE) { 0 }
        val fake = FakeInference(
            saliencyResult = AiRun.Ok(empty),
            inpaintResult = AiRun.Ok(IntArray(36) { 0xFF0ABCDEF.toInt() }),
        )
        val seedPoints = listOf(
            AiEraseLayer.Point(0.2, 0.2),
            AiEraseLayer.Point(0.9, 0.2),
            AiEraseLayer.Point(0.5, 0.9),
        )
        val result = EditAiCommit.erase(fake, IntArray(36), 6, 6, seedPoints, progress) as AiRun.Ok
        // The mask is the seed mask (the painted triangle), the fill the
        // fake's grid, the patch cropped to the seed's bbox.
        val seed = RegionMask.build(seedPoints.map { RegionLayer.Point(it.x, it.y) }, 6, 6)
        assertTrue(result.value.first.maskAt(6, 6).contentEquals(seed))
        assertTrue(result.value.second != null)
    }

    // ---- the coordinator end-to-end + the migan polarity ----

    @Test
    fun `the erase chain hands migan the cited polarity and crops the patch`() {
        val fake = FakeInference(
            saliencyResult = AiRun.Ok(subjectSaliency()),
            inpaintResult = AiRun.Ok(IntArray(36) { i -> 0xFF000000.toInt() or (i * 100) }),
        )
        val result = EditAiCommit.erase(fake, IntArray(36), 6, 6, seedPoints(), progress) as AiRun.Ok
        assertEquals(1, fake.saliencyCalls)
        assertEquals(1, fake.inpaintCalls)
        // The polarity: the refined hole pixels are 0, everything else 255.
        val mask8 = fake.lastMask8!!
        val refined = result.value.first.maskAt(6, 6)
        for (i in refined.indices) {
            val expected = if (refined[i]) EditAiModels.INPAINT_HOLE else EditAiModels.INPAINT_KEEP
            assertEquals("at $i", expected.toByte(), mask8[i])
        }
        assertEquals(36, mask8.size)
        // The patch covers the refined mask's bbox, from the fake's grid.
        val patch = result.value.second!!
        assertTrue(patch.width in 1..6 && patch.height in 1..6)
        assertEquals(
            0xFF000000.toInt() or (2 * 6 + 2) * 100,
            patch.pixels[0], // the patch's top-left is the bbox's top-left
        )
    }

    // ---- the yunet decode ----

    @Test
    fun `the yunet decode reads the anchor arithmetic and the fractions`() {
        // stride 8, cells 80; cell (row=10, col=20) -> k=820 carries the
        // only passing score; bbox (0.5, 0.5, 10, 20): cx=(20.5)*8=164,
        // cy=(10.5)*8=84, w=80, h=160 -> x1=124, y1=4, x2=204, y2=164.
        val size = EditAiModels.FACES_SIZE
        val cells8 = size / 8
        val cls8 = FloatArray(cells8 * cells8)
        val obj8 = FloatArray(cells8 * cells8)
        val bbox8 = FloatArray(cells8 * cells8 * 4)
        val k = 10 * cells8 + 20
        cls8[k] = 0.9f
        obj8[k] = 0.8f // score 0.72 > 0.6
        bbox8[4 * k] = 0.5f
        bbox8[4 * k + 1] = 0.5f
        bbox8[4 * k + 2] = 10f
        bbox8[4 * k + 3] = 20f
        val zeros = FloatArray(cells8 * cells8)
        val boxes = EditAiDerive.decodeFaces(
            cls8, zeros, zeros, obj8, zeros, zeros,
            bbox8, FloatArray(cells8 * cells8 * 4), FloatArray(cells8 * cells8 * 4),
        )
        assertEquals(1, boxes.size)
        assertEquals(124.0 / 640.0, boxes[0].left, 1e-9)
        assertEquals(4.0 / 640.0, boxes[0].top, 1e-9)
        assertEquals(204.0 / 640.0, boxes[0].right, 1e-9)
        assertEquals(164.0 / 640.0, boxes[0].bottom, 1e-9)
    }

    @Test
    fun `the yunet NMS suppresses the overlapping lower-score box`() {
        // Two adjacent cells producing ~0.82-IoU boxes: the higher score
        // keeps the slot, the lower is suppressed.
        val size = EditAiModels.FACES_SIZE
        val cells8 = size / 8
        val cls8 = FloatArray(cells8 * cells8)
        val obj8 = FloatArray(cells8 * cells8)
        val bbox8 = FloatArray(cells8 * cells8 * 4)
        val k1 = 10 * cells8 + 20
        val k2 = 10 * cells8 + 21
        cls8[k1] = 0.9f; obj8[k1] = 0.8f
        cls8[k2] = 0.7f; obj8[k2] = 0.95f // 0.665 > 0.6
        for (k in intArrayOf(k1, k2)) {
            bbox8[4 * k] = 0.5f
            bbox8[4 * k + 1] = 0.5f
            bbox8[4 * k + 2] = 10f
            bbox8[4 * k + 3] = 20f
        }
        val zeros = FloatArray(cells8 * cells8)
        val boxes = EditAiDerive.decodeFaces(
            cls8, zeros, zeros, obj8, zeros, zeros,
            bbox8, FloatArray(cells8 * cells8 * 4), FloatArray(cells8 * cells8 * 4),
        )
        assertEquals(1, boxes.size)
        // The kept box is k1's (score 0.72 > 0.665).
        assertEquals(124.0 / 640.0, boxes[0].left, 1e-9)
    }

    // ---- the cited preprocessing constants ----

    @Test
    fun `the saliency tensor is RGB over 255 with the ImageNet mean and std in CHW`() {
        // One red, one green pixel: the R plane first, then G, then B.
        val grid = intArrayOf(
            (0xFF shl 24) or (255 shl 16) or (0 shl 8) or 0,
            (0xFF shl 24) or (0 shl 16) or (255 shl 8) or 0,
        )
        val tensor = EditAiPreprocess.saliencyTensor(grid)
        assertEquals(6, tensor.size)
        assertEquals((1.0f - 0.485f) / 0.229f, tensor[0], 1e-4f) // R of px0
        assertEquals((0.0f - 0.485f) / 0.229f, tensor[1], 1e-4f) // R of px1
        assertEquals((0.0f - 0.456f) / 0.224f, tensor[2], 1e-4f) // G of px0
        assertEquals((1.0f - 0.456f) / 0.224f, tensor[3], 1e-4f) // G of px1
        assertEquals((0.0f - 0.406f) / 0.225f, tensor[4], 1e-4f) // B of px0
        assertEquals((0.0f - 0.406f) / 0.225f, tensor[5], 1e-4f) // B of px1
    }

    @Test
    fun `the faces tensor is BGR at the raw 0-255 scale`() {
        val grid = intArrayOf((0xFF shl 24) or (10 shl 16) or (20 shl 8) or 30)
        val tensor = EditAiPreprocess.facesTensor(grid)
        assertEquals(30f, tensor[0], 0f) // B first
        assertEquals(20f, tensor[1], 0f) // G
        assertEquals(10f, tensor[2], 0f) // R last
    }

    @Test
    fun `the migan mask carries the cited polarity`() {
        val hole = booleanArrayOf(true, false, true)
        val mask8 = EditAiPreprocess.miganMask(hole)
        assertEquals(0.toByte(), mask8[0])
        assertEquals(255.toByte(), mask8[1])
        assertEquals(0.toByte(), mask8[2])
    }

    @Test
    fun `the nearest resize is the deterministic floor mapping`() {
        val working = intArrayOf(0, 1, 2, 3) // 2x2
        val resized = EditAiPreprocess.resize(working, 2, 2, 4, 4)
        // (x,y) samples (x*2/4, y*2/4) = (x/2, y/2).
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3), resized)
    }

    // ---- helpers ----

    private fun seedPoints(): List<AiEraseLayer.Point> = listOf(
        AiEraseLayer.Point(0.2, 0.2),
        AiEraseLayer.Point(0.9, 0.2),
        AiEraseLayer.Point(0.5, 0.9),
    )
}
