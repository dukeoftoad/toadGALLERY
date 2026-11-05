package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r37 region model + the pure kernels (the contract's
 * `RegionLayersTest`): the even-odd scanline mask (exact pinned masks:
 * a triangle, a self-crossing bowtie, a degenerate path), the mosaic
 * block average and the linear blur window (exact hand grids), the
 * sliding-window equivalence, and the pipeline stage integration.
 */
class RegionLayersTest {

    private fun pts(vararg xy: Double): List<RegionLayer.Point> =
        xy.toList().chunked(2).map { RegionLayer.Point(it[0], it[1]) }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ---- Model validation ----

    @Test
    fun `a well-formed region constructs with defaults`() {
        val region = RegionLayer(id = "r1", kind = RegionKind.MOSAIC, points = pts(0.2, 0.2, 0.8, 0.2, 0.2, 0.8))
        assertEquals(12, region.strengthPct)
    }

    @Test
    fun `out-of-range values are rejected at construction`() {
        val three = pts(0.1, 0.1, 0.9, 0.1, 0.5, 0.9)
        assertThrows(IllegalArgumentException::class.java) { RegionLayer(id = "", kind = RegionKind.MOSAIC, points = three) }
        assertThrows(IllegalArgumentException::class.java) { RegionLayer(id = "r1", kind = RegionKind.MOSAIC, points = three, strengthPct = 0) }
        assertThrows(IllegalArgumentException::class.java) { RegionLayer(id = "r1", kind = RegionKind.MOSAIC, points = three, strengthPct = 101) }
        assertThrows(IllegalArgumentException::class.java) {
            RegionLayer(id = "r1", kind = RegionKind.MOSAIC, points = pts(0.5, 0.5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegionLayer(id = "r1", kind = RegionKind.MOSAIC, points = List(RegionLayer.MAX_POINTS + 1) { RegionLayer.Point(0.5, 0.5) })
        }
        assertThrows(IllegalArgumentException::class.java) { RegionLayer.Point(1.5, 0.5) }
    }

    @Test
    fun `duplicate ids are rejected by the container and newId walks the r prefix`() {
        val a = RegionLayer(id = "r1", kind = RegionKind.MOSAIC, points = pts(0.2, 0.2, 0.8, 0.2, 0.2, 0.8))
        assertThrows(IllegalArgumentException::class.java) { RegionLayers(listOf(a, a)) }
        assertEquals("r1", RegionLayer.newId(emptyList()))
        assertEquals("r4", RegionLayer.newId(listOf("r1", "r3")))
        assertEquals("r2", RegionLayer.newId(listOf("t1", "s1", "d1", "r1")))
    }

    @Test
    fun `thin is the stroke family's rule verbatim`() {
        val points = (0 until 10).map { RegionLayer.Point(it / 10.0, 0.5) }
        val thinned = RegionLayer.thin(points, 3)
        assertEquals(listOf(0.0, 0.4, 0.8, 0.9), thinned.map { it.x })
        assertEquals(4, thinned.size) // <= cap + 1
    }

    // ---- The even-odd scanline mask ----

    @Test
    fun `a triangle masks its interior with strict pixel centres`() {
        // Pixel coords (2,2)-(8,2)-(2,8) over a 10x10 grid: the
        // hypotenuse x = 8 - (y - 2); expected per-row spans (computed
        // by hand): y=2: 2..6, y=3: 2..5, y=4: 2..4, y=5: 2..3, y=6: 2..2.
        val mask = RegionMask.build(pts(0.2, 0.2, 0.8, 0.2, 0.2, 0.8), 10, 10)
        var count = 0
        for (m in mask) if (m) count++
        assertEquals(15, count) // 5 + 4 + 3 + 2 + 1
        assertTrue(mask[2 * 10 + 2]) // (2,2) in
        assertTrue(mask[2 * 10 + 6]) // (6,2) in, centre 6.5 < 7.5
        org.junit.Assert.assertFalse(mask[2 * 10 + 7]) // (7,2) out, centre 7.5 not < 7.5
        org.junit.Assert.assertFalse(mask[1 * 10 + 2]) // (2,1) out
        org.junit.Assert.assertFalse(mask[7 * 10 + 2]) // (2,7) out (the pinch row: empty)
        assertTrue(mask[6 * 10 + 2]) // (2,6) in, the last row with area
    }

    @Test
    fun `a self-crossing bowtie masks both lobes and pinches at the middle`() {
        // (2,2)-(8,2)-(2,8)-(8,8) pixel coords over 10x10: rows 2 and 7
        // carry the lobes (x=3..6 and 3..6), rows 4-5 are the pinch
        // (empty), rows 3/6 carry x=4..5.
        val mask = RegionMask.build(pts(0.2, 0.2, 0.8, 0.2, 0.2, 0.8, 0.8, 0.8), 10, 10)
        var count = 0
        for (m in mask) if (m) count++
        assertEquals(12, count) // 4 + 2 + 0 + 0 + 2 + 4
        assertTrue(mask[2 * 10 + 3] && mask[2 * 10 + 6]) // lobe row 2
        assertTrue(mask[3 * 10 + 4] && mask[3 * 10 + 5]) // row 3
        org.junit.Assert.assertFalse(mask[4 * 10 + 4] || mask[4 * 10 + 5]) // the pinch
        assertTrue(mask[7 * 10 + 3] && mask[7 * 10 + 6]) // lobe row 7
    }

    @Test
    fun `a degenerate path masks nothing`() {
        val mask = RegionMask.build(pts(0.5, 0.5, 0.6, 0.5), 10, 10)
        assertEquals(100, mask.size)
        assertTrue(mask.none { it })
    }

    // ---- The kernels ----

    @Test
    fun `the strength mapping is integer floor with the floors`() {
        assertEquals(2, RegionEffects.mosaicCellPx(1, 1000)) // 1 px -> floor 2
        assertEquals(10, RegionEffects.mosaicCellPx(10, 1000))
        assertEquals(100, RegionEffects.mosaicCellPx(100, 1000))
        assertEquals(7, RegionEffects.mosaicCellPx(12, 600)) // 7.2 -> 7
        assertEquals(1, RegionEffects.blurRadiusPx(1, 1000)) // 1 px -> floor 1
        assertEquals(10, RegionEffects.blurRadiusPx(10, 1000))
        assertEquals(7, RegionEffects.blurRadiusPx(12, 600))
    }

    @Test
    fun `the mosaic block average is exact on a hand grid`() {
        // 6x6, cell 2 (strength 20 x minEdge 6 / 1000 -> floor 0 -> coerce
        // 2; wait: 20*6/1000 = 0.12 -> 0 -> 2). Every pixel masked; rgb =
        // (x*10, y*10, 7). Cell (0,0): pixels (0,0,7)(10,0,7)(0,10,7)(10,10,7)
        // -> avg (5,5,7) = 0xFF050507. Cell (2,0): r avg 25 -> 0x19.
        val w = 6
        val h = 6
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            argb(x * 10, y * 10, 7)
        }
        val mask = BooleanArray(w * h) { true }
        val cell = RegionEffects.mosaicCellPx(20, 6)
        assertEquals(2, cell)
        RegionEffects.applyMosaic(pixels, w, h, mask, cell)
        assertEquals(argb(5, 5, 7), pixels[0 * w + 0])
        assertEquals(argb(5, 5, 7), pixels[1 * w + 1]) // same cell
        assertEquals(argb(0x19, 5, 7), pixels[0 * w + 2]) // cell (2,0): r=25
        assertEquals(argb(0x19, 5, 7), pixels[1 * w + 3])
        assertEquals(argb(5, 0x19, 7), pixels[2 * w + 0]) // cell (0,2): g=25
        // The unmasked-pixel path: with the full mask every pixel is
        // rewritten; the averages above are the exact hand values.
    }

    @Test
    fun `the mosaic leaves unmasked pixels untouched`() {
        val w = 4
        val h = 2
        val pixels = IntArray(w * h) { i -> argb(i * 10, 0, 0) }
        val mask = BooleanArray(w * h) { false }
        mask[0] = true // only (0,0) masked: its cell average is itself
        RegionEffects.applyMosaic(pixels, w, h, mask, 2)
        assertEquals(argb(0, 0, 0), pixels[0])
        assertEquals(argb(10, 0, 0), pixels[1]) // untouched
    }

    @Test
    fun `the linear blur window is exact with edge clamps`() {
        // Width 8, one row; r = 1 (strength 10 x minEdge 8 / 1000 -> 0 ->
        // coerce 1). rgb = (x, 0, 0). Averages over [x-1, x+1] clamped:
        // x=0: [0,1]: (0+1)/2=0 (floor of 0.5); x=1: [0,2]: 1; x=2: 2;
        // x=7: [6,7]: (6+7)/2=6 (floor of 6.5).
        val w = 8
        val h = 1
        val pixels = IntArray(w * h) { i -> argb(i, 0, 0) }
        val mask = BooleanArray(w * h) { true }
        RegionEffects.applyLinearBlur(pixels, w, h, mask, 1)
        assertEquals(argb(0, 0, 0), pixels[0])
        assertEquals(argb(1, 0, 0), pixels[1])
        assertEquals(argb(2, 0, 0), pixels[2])
        assertEquals(argb(6, 0, 0), pixels[7])
    }

    @Test
    fun `the sliding window equals the per-pixel loop`() {
        // Deterministic pseudo-random grid (LCG), r=3, w=12, h=4; the
        // sliding window must equal a naive per-pixel reference exactly.
        val w = 12
        val h = 4
        var seed = 123456789L
        fun next(): Int {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed ushr 33) and 0xFF).toInt()
        }
        val src = IntArray(w * h) { argb(next(), next(), next()) }
        val mask = BooleanArray(w * h) { (it % 5) != 0 } // a deterministic sparse mask
        val r = 3
        // The sliding implementation.
        val bySliding = src.copyOf()
        RegionEffects.applyLinearBlur(bySliding, w, h, mask, r)
        // The naive reference.
        val byLoop = src.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                var sr = 0
                var sg = 0
                var sb = 0
                var count = 0
                for (dx in -r..r) {
                    val xx = x + dx
                    if (xx !in 0 until w) continue
                    val c = src[y * w + xx]
                    sr += (c shr 16) and 0xFF
                    sg += (c shr 8) and 0xFF
                    sb += c and 0xFF
                    count++
                }
                byLoop[y * w + x] = (byLoop[y * w + x] and 0xFF000000.toInt()) or
                    ((sr / count) shl 16) or ((sg / count) shl 8) or (sb / count)
            }
        }
        assertTrue(bySliding.contentEquals(byLoop))
    }

    // ---- The pipeline stage ----

    @Test
    fun `a regions-only recipe is not identity and the stage censors`() {
        val recipe = EditRecipe(
            mosaicRegions = RegionLayers(
                listOf(
                    RegionLayer(
                        id = "r1",
                        kind = RegionKind.MOSAIC,
                        strengthPct = 50,
                        points = pts(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0),
                    ),
                ),
            ),
        )
        assertTrue(!recipe.isIdentity)
        assertTrue(recipe.isGeometryIdentity && recipe.isAdjustmentIdentity && recipe.isFilterIdentity)
        // apply() censors: a 4x4 gradient, the whole-canvas mosaic at
        // strength 50 -> cell = 50*4/1000 = 0 -> coerce 2: 2x2 cells.
        val w = 4
        val h = 4
        val pixels = IntArray(w * h) { i -> argb((i % w) * 20, (i / w) * 20, 3) }
        val out = EditPipeline.apply(pixels, w, h, recipe)
        assertEquals(w, out.width)
        assertEquals(h, out.height)
        // Cell (0,0): r samples 0,20,0,20 -> avg 10; the first pixel now
        // reads the block average, not the gradient sample.
        assertEquals(argb(10, 10, 3), out.pixels[0])
        assertEquals(argb(10, 10, 3), out.pixels[5]) // (1,1): same cell
        // Cell (2,0): r samples 40,60,40,60 -> avg 50; g samples 0,0,20,20 -> 10.
        assertEquals(argb(50, 10, 3), out.pixels[1 * w + 2])
    }

    @Test
    fun `the stage order grew the regions tail`() {
        // r41: the AI pixel stages sit between sharpen and the regions ---
        // the regions stay FINAL (a mosaic over a retouched face is still a
        // mosaic); the assertion pins the new documented tail.
        assertTrue(
            EditPipeline.STAGE_ORDER.contains(
                "vignette -> sharpen -> AI erase -> AI sky -> AI bokeh -> AI retouch -> mosaic/blur regions",
            ),
        )
    }
}
