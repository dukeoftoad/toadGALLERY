package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the tone curve bake guarantees, sanitize, JSON round-trip, and the LutBuilder curve composition. */
class LilypadCurveMathTest {

    private val identity = LilypadCurveMath.IDENTITY

    private fun curve(vararg pairs: Pair<Double, Double>): List<CurvePoint> =
        pairs.map { CurvePoint(it.first, it.second) }

    // ---- the identity bake ----

    @Test
    fun `the identity curve bakes the exact ramp`() {
        val lut = LilypadCurveMath.bakeLut(identity)
        assertEquals(256, lut.size)
        for (v in 0..255) assertEquals("v=$v", v, lut[v])
    }

    @Test
    fun `a degenerate list bakes the identity`() {
        // Under two points there is no curve; the honest table is the ramp.
        val lut = LilypadCurveMath.bakeLut(curve(0.5 to 0.5))
        for (v in 0..255) assertEquals(v, lut[v])
    }

    // ---- control points and clamping ----

    @Test
    fun `the interpolant passes exactly through its control points`() {
        val points = curve(0.0 to 0.0, 0.25 to 0.4, 0.75 to 0.6, 1.0 to 1.0)
        for (p in points) {
            assertEquals(p.y, LilypadCurveMath.evaluate(points, p.x), 1e-12)
        }
    }

    @Test
    fun `the bake hits the pinned endpoints when the curve spans the domain`() {
        val points = curve(0.0 to 0.05, 0.5 to 0.5, 1.0 to 0.95)
        val lut = LilypadCurveMath.bakeLut(points)
        assertEquals(13, lut[0])   // 0.05 * 255 = 12.75 -> 13
        assertEquals(242, lut[255]) // 0.95 * 255 = 242.25 -> 242
    }

    @Test
    fun `endpoint clamping pins the tones outside the curve's span`() {
        val points = curve(0.2 to 0.1, 0.8 to 0.9)
        assertEquals(0.1, LilypadCurveMath.evaluate(points, 0.0), 1e-12)
        assertEquals(0.1, LilypadCurveMath.evaluate(points, 0.2), 1e-12)
        assertEquals(0.9, LilypadCurveMath.evaluate(points, 0.8), 1e-12)
        assertEquals(0.9, LilypadCurveMath.evaluate(points, 1.0), 1e-12)
        val lut = LilypadCurveMath.bakeLut(points)
        assertEquals(26, lut[0]) // below the span: the first point's y (25.5 rounds up)
        assertEquals(230, lut[255]) // above the span: the last point's y (229.5 rounds up)
    }

    // ---- monotonicity (the Fritsch-Carlson no-overshoot rule) ----

    @Test
    fun `monotone points bake a monotone lut`() {
        val monotoneSets = listOf(
            curve(0.0 to 0.0, 0.5 to 0.62, 1.0 to 1.0),
            curve(0.0 to 0.1, 0.25 to 0.12, 0.5 to 0.5, 0.9 to 0.98, 1.0 to 1.0),
            curve(0.0 to 0.3, 0.3 to 0.3, 0.6 to 0.31, 1.0 to 0.32),
        )
        for (points in monotoneSets) {
            val lut = LilypadCurveMath.bakeLut(points)
            for (v in 0 until 255) {
                assertTrue("lut inverted at $v for $points", lut[v] <= lut[v + 1])
            }
        }
    }

    @Test
    fun `the interpolant never overshoots a segment's endpoints`() {
        // A steep middle lift: between (0.25, 0.2) and (0.75, 0.9) the curve must stay inside
        // [0.2, 0.9] at every sample (no natural-spline oscillation).
        val points = curve(0.0 to 0.0, 0.25 to 0.2, 0.75 to 0.9, 1.0 to 1.0)
        for (i in 0..100) {
            val x = 0.25 + (0.75 - 0.25) * i / 100.0
            val y = LilypadCurveMath.evaluate(points, x)
            assertTrue("overshoot below at x=$x: $y", y >= 0.2 - 1e-9)
            assertTrue("overshoot above at x=$x: $y", y <= 0.9 + 1e-9)
        }
    }

    @Test
    fun `a local extremum flattens the tangent there`() {
        // Rises then plateaus then falls: the tangent at the turn is zero.
        val points = curve(0.0 to 0.0, 0.5 to 0.8, 1.0 to 0.4)
        val m = LilypadCurveMath.slopes(points)
        assertEquals(0.0, m[1], 1e-12)
    }

    @Test
    fun `a deliberately decreasing curve still bakes`() {
        // A creative inversion (the film-negative look) is valid data.
        val points = curve(0.0 to 1.0, 0.5 to 0.5, 1.0 to 0.0)
        val lut = LilypadCurveMath.bakeLut(points)
        assertEquals(255, lut[0])
        assertEquals(0, lut[255])
        // And it is monotone DECREASING: the interpolant honors the drawn direction.
        for (v in 0 until 255) assertTrue(lut[v] >= lut[v + 1])
    }

    // ---- validation and sanitize ----

    @Test
    fun `validate accepts an honest curve and rejects the breaches`() {
        LilypadCurveMath.validate(identity)
        LilypadCurveMath.validate(curve(0.0 to 0.0, 0.5 to 0.5, 1.0 to 1.0))
        assertThrows<IllegalArgumentException> {
            LilypadCurveMath.validate(curve(0.0 to 0.0))
        }
        assertThrows<IllegalArgumentException> {
            LilypadCurveMath.validate((0..12).map { CurvePoint(it / 12.0, 0.5) })
        }
        assertThrows<IllegalArgumentException> {
            LilypadCurveMath.validate(curve(0.0 to 1.2, 1.0 to 1.0))
        }
        assertThrows<IllegalArgumentException> {
            LilypadCurveMath.validate(curve(-0.1 to 0.0, 1.0 to 1.0))
        }
        assertThrows<IllegalArgumentException> {
            // unsorted: the second point does not advance past the first
            LilypadCurveMath.validate(curve(0.5 to 0.5, 0.2 to 0.2))
        }
    }

    @Test
    fun `sanitize coerces, sorts, de-duplicates and caps`() {
        val messy = listOf(
            CurvePoint(1.0, 1.0),
            CurvePoint(-0.2, 0.0),  // coerces to 0
            CurvePoint(0.5, 1.7),   // coerces its y to 1
            CurvePoint(0.5, 0.25),  // duplicate x: the smaller y keeps the slot
            CurvePoint(0.25, 0.25),
        )
        val clean = LilypadCurveMath.sanitize(messy)
        assertEquals(
            listOf(CurvePoint(0.0, 0.0), CurvePoint(0.25, 0.25), CurvePoint(0.5, 0.25), CurvePoint(1.0, 1.0)),
            clean,
        )
    }

    @Test
    fun `sanitize caps at the model cap keeping the smallest x`() {
        val many = (0..20).map { CurvePoint(it / 20.0, 0.5) }
        val capped = LilypadCurveMath.sanitize(many)
        assertEquals(LilypadCurveMath.MAX_POINTS, capped.size)
        assertEquals(0.0, capped.first().x, 1e-12)
    }

    // ---- the recipe JSON round-trip ----

    @Test
    fun `a curve recipe round-trips through the codec`() {
        val curves = ToneCurves(
            master = curve(0.0 to 0.0, 0.5 to 0.62, 1.0 to 1.0),
            red = curve(0.0 to 0.0, 1.0 to 0.9),
        )
        val recipe = EditRecipe(exposure = 10, toneCurves = curves)
        val restored = (EditRecipeCodec.fromJson(EditRecipeCodec.toJson(recipe))
            as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(curves, restored.toneCurves)
        assertEquals(10, restored.exposure)
    }

    @Test
    fun `a clean session writes no curve key and a pre-r68 sidecar loads without one`() {
        // Byte-clean: the key exists only for a non-null state.
        assertFalse(EditRecipeCodec.toJson(EditRecipe()).contains("toneCurves"))
        val parsed = EditRecipeCodec.fromJson("{\"schemaVersion\":1,\"brightness\":10}")
        val restored = (parsed as EditRecipeCodec.ParseResult.Valid).recipe
        assertNull(restored.toneCurves)
        assertEquals(10, restored.brightness)
    }

    @Test
    fun `the curve read is tolerant`() {
        // Out-of-domain coords coerce; non-2-element entries drop; a degenerate master reads as absent.
        val coerced = EditRecipeCodec.fromJson(
            "{\"schemaVersion\":1,\"toneCurves\":{\"master\":[[-0.5,0.0],[0.5,7.0],[1.0,1.0]]}}",
        )
        val restored = (coerced as EditRecipeCodec.ParseResult.Valid).recipe
        assertEquals(curve(0.0 to 0.0, 0.5 to 1.0, 1.0 to 1.0), restored.toneCurves?.master)

        val broken = EditRecipeCodec.fromJson(
            "{\"schemaVersion\":1,\"toneCurves\":{\"master\":[[0.5],\"x\"]}}",
        )
        assertNull((broken as EditRecipeCodec.ParseResult.Valid).recipe.toneCurves)
    }

    // ---- the identity integration ----

    @Test
    fun `isAdjustmentIdentity integrates the curve`() {
        assertTrue(EditRecipe().isAdjustmentIdentity)
        assertTrue(EditRecipe(toneCurves = ToneCurves(identity)).isAdjustmentIdentity)
        assertTrue(EditRecipe(toneCurves = ToneCurves(identity)).isIdentity)
        val curved = EditRecipe(toneCurves = ToneCurves(curve(0.0 to 0.0, 0.5 to 0.6, 1.0 to 1.0)))
        assertFalse(curved.isAdjustmentIdentity)
        assertFalse(curved.isIdentity)
    }

    // ---- the five-parameter regression (the frozen reference table) ----

    @Test
    fun `a null curve is byte-identical to the five-parameter build`() {
        val pinned = LutBuilder.build(brightness = 50, contrast = 50, exposure = 0, highlights = 0, shadows = 0)
        // The pinned composed value: 156 for b=+50, c=+50 at v=10.
        assertEquals(156, pinned[10])
        val withNull = LutBuilder.build(
            brightness = 50, contrast = 50, exposure = 0, highlights = 0, shadows = 0,
            toneCurves = null,
        )
        assertTrue(pinned.contentEquals(withNull))
    }

    @Test
    fun `the identity curve composes to the same table`() {
        val base = LutBuilder.build(brightness = -20, contrast = 30, exposure = 15, highlights = -40, shadows = 25)
        val withIdentity = LutBuilder.build(
            brightness = -20, contrast = 30, exposure = 15, highlights = -40, shadows = 25,
            toneCurves = ToneCurves(identity),
        )
        assertTrue(base.contentEquals(withIdentity))
    }

    @Test
    fun `the curve composes after the five stages`() {
        // A pure lift curve (y = x + 0.2 clamped): lut[v] = curve(base[v]),
        // not curve(v) --- the composition reads the FIVE-STAGE output.
        val lift = curve(0.0 to 0.2, 0.8 to 1.0)
        val base = LutBuilder.build(brightness = 0, contrast = 0, exposure = 0, highlights = 0, shadows = 0)
        val composed = LutBuilder.build(
            brightness = 0, contrast = 0, exposure = 0, highlights = 0, shadows = 0,
            toneCurves = ToneCurves(lift),
        )
        val liftLut = LilypadCurveMath.bakeLut(lift)
        for (v in 0..255) {
            assertEquals("v=$v", liftLut[base[v]], composed[v])
        }
        assertNotEquals(base[200], composed[200])
    }

    @Test
    fun `the stage order documents the curve`() {
        assertEquals(
            "exposure -> brightness -> contrast -> highlights -> shadows -> curve",
            LutBuilder.ORDER,
        )
    }
}

/** The expect-exception shape, private to this file (JUnit4 + reified sugar). */
private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try {
        block()
        error("expected ${T::class.simpleName} but no exception was thrown")
    } catch (expected: Throwable) {
        if (expected !is T) throw expected
    }
}
