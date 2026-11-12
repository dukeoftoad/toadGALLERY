package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r71 channel composition's JVM pins (Phase 2's declared follow-up,
 * docs/editor/lilypad-design.md §5): the three-table build (master-then-
 * channel per channel), the equivalence pins (a no-channel state
 * reproduces the r68 one-table apply; three copies of one table
 * reproduce applyLut), the pipeline's routing, and the honest degrades
 * (degenerate channel lists, the forward-state sidecar that now
 * renders).
 *
 * Pure JVM, no compose/android (ADR-0011) --- this class is one of the
 * Lilypad-prefixed set the editor CI filter runs.
 */
class LilypadChannelCurveTest {

    private val identity = LilypadCurveMath.IDENTITY

    private fun curve(vararg pairs: Pair<Double, Double>): List<CurvePoint> =
        pairs.map { CurvePoint(it.first, it.second) }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    // ---- hasChannelCurves: the pipeline's switch ----

    @Test
    fun `the identity state has no channel curves in play`() {
        assertFalse(ToneCurves(identity).hasChannelCurves)
        assertFalse(
            ToneCurves(curve(0.0 to 0.1, 1.0 to 1.0)).hasChannelCurves,
        )
    }

    @Test
    fun `an honest channel curve is in play, a degenerate one is not`() {
        assertTrue(
            ToneCurves(identity, red = curve(0.0 to 0.0, 1.0 to 0.9)).hasChannelCurves,
        )
        // Under two points: bakeLut's identity ramp --- no honest curve.
        assertFalse(
            ToneCurves(identity, red = curve(0.5 to 0.5)).hasChannelCurves,
        )
        // An empty list is the untouched channel, never a curve.
        assertFalse(
            ToneCurves(identity, green = emptyList(), blue = emptyList()).hasChannelCurves,
        )
    }

    // ---- buildChannels: the composition ----

    @Test
    fun `a state with no channel curves reproduces the master-only table in all three`() {
        val curves = ToneCurves(curve(0.0 to 0.0, 0.5 to 0.62, 1.0 to 1.0))
        val oneTable = LutBuilder.build(
            brightness = 10, contrast = -20, exposure = 5, highlights = -5, shadows = 15,
            toneCurves = curves,
        )
        val three = LutBuilder.buildChannels(
            brightness = 10, contrast = -20, exposure = 5, highlights = -5, shadows = 15,
            toneCurves = curves,
        )
        assertTrue(oneTable.contentEquals(three.red))
        assertTrue(oneTable.contentEquals(three.green))
        assertTrue(oneTable.contentEquals(three.blue))
    }

    @Test
    fun `the channel curve composes OVER the master, not before it`() {
        // Pure lift master (y = x + 0.2, clamped) + a pure halving red
        // curve: red[v] = redCurve(masterCurve(v)), NOT masterCurve
        // (redCurve(v)) --- the composition reads the master's output.
        val master = curve(0.0 to 0.2, 0.8 to 1.0)
        val red = curve(0.0 to 0.0, 1.0 to 0.5)
        val three = LutBuilder.buildChannels(
            brightness = 0, contrast = 0, exposure = 0, highlights = 0, shadows = 0,
            toneCurves = ToneCurves(master, red = red),
        )
        val masterLut = LilypadCurveMath.bakeLut(master)
        val redLut = LilypadCurveMath.bakeLut(red)
        for (v in 0..255) {
            assertEquals("v=$v", redLut[masterLut[v]], three.red[v])
        }
        // And the untouched channels read the master alone.
        assertTrue(masterLut.contentEquals(three.green))
        assertTrue(masterLut.contentEquals(three.blue))
    }

    @Test
    fun `the five sliders feed the chain first`() {
        // exposure +100 doubles before any curve reads a value: red[v] =
        // redCurve(masterCurve(2v clamped)) --- the stage order, pinned.
        val red = curve(0.0 to 0.0, 1.0 to 0.5)
        val three = LutBuilder.buildChannels(
            brightness = 0, contrast = 0, exposure = 100, highlights = 0, shadows = 0,
            toneCurves = ToneCurves(identity, red = red),
        )
        val base = LutBuilder.build(brightness = 0, contrast = 0, exposure = 100, highlights = 0, shadows = 0)
        val redLut = LilypadCurveMath.bakeLut(red)
        for (v in 0..255) {
            assertEquals("v=$v", redLut[base[v]], three.red[v])
        }
    }

    @Test
    fun `a degenerate channel list reads the master-composed table`() {
        // Under two points there is no channel curve; the honest read is
        // the r68 apply, per channel (never a crash, never an invented
        // shape).
        val master = curve(0.0 to 0.1, 1.0 to 0.9)
        val three = LutBuilder.buildChannels(
            brightness = 0, contrast = 0, exposure = 0, highlights = 0, shadows = 0,
            toneCurves = ToneCurves(master, red = curve(0.5 to 0.5)),
        )
        val masterLut = LilypadCurveMath.bakeLut(master)
        assertTrue(masterLut.contentEquals(three.red))
        assertTrue(masterLut.contentEquals(three.green))
    }

    @Test
    fun `the identity master with a channel curve composes the channel alone`() {
        val red = curve(0.0 to 0.1, 1.0 to 1.0)
        val three = LutBuilder.buildChannels(
            brightness = 0, contrast = 0, exposure = 0, highlights = 0, shadows = 0,
            toneCurves = ToneCurves(identity, red = red),
        )
        val redLut = LilypadCurveMath.bakeLut(red)
        assertTrue(redLut.contentEquals(three.red))
        // The identity master leaves the other channels at the ramp.
        for (v in 0..255) {
            assertEquals(v, three.green[v])
            assertEquals(v, three.blue[v])
        }
    }

    // ---- applyChannelLuts: the three-table pass ----

    @Test
    fun `three copies of one table reproduce applyLut byte-for-byte`() {
        val lut = LutBuilder.build(
            brightness = 30, contrast = 10, exposure = -15, highlights = 20, shadows = -10,
            toneCurves = ToneCurves(curve(0.0 to 0.05, 0.5 to 0.55, 1.0 to 1.0)),
        )
        val viaOne = intArrayOf(
            argb(255, 10, 128, 250), argb(255, 200, 5, 77), argb(0, 1, 2, 3),
        )
        val viaThree = viaOne.copyOf()
        PixelOps.applyLut(viaOne, lut)
        PixelOps.applyChannelLuts(viaThree, ChannelToneLuts(lut, lut, lut))
        assertTrue(viaOne.contentEquals(viaThree))
    }

    @Test
    fun `each channel reads its own table and alpha survives`() {
        // Three different ramps: red identity, green pinned to 42, blue
        // pinned to 200 --- the pixel's channels must each read their own
        // table, alpha untouched.
        val redLut = IntArray(256) { it }
        val greenLut = IntArray(256) { 42 }
        val blueLut = IntArray(256) { 200 }
        val pixels = intArrayOf(argb(217, 0, 255, 13))
        PixelOps.applyChannelLuts(pixels, ChannelToneLuts(redLut, greenLut, blueLut))
        assertEquals(argb(217, 0, 42, 200), pixels[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a short table is rejected at construction`() {
        ChannelToneLuts(IntArray(256), IntArray(255), IntArray(256))
    }

    // ---- the pipeline routing ----

    @Test
    fun `the pipeline takes the channel path only when a channel curve is in play`() {
        val base = intArrayOf(
            0xFF0A64C8.toInt(), 0xFF808080.toInt(), 0xFFFA053C.toInt(),
        )
        // Master-only: byte-identical to the r68 one-table apply (the
        // frozen path --- the drift the routing must never introduce).
        val masterOnly = base.copyOf()
        val expectedMasterOnly = base.copyOf()
        EditPipeline.apply(
            masterOnly, 3, 1,
            EditRecipe(
                toneCurves = ToneCurves(curve(0.0 to 0.0, 0.5 to 0.62, 1.0 to 1.0)),
            ),
        )
        PixelAdjuster.applyTo(
            expectedMasterOnly,
            LutBuilder.build(
                brightness = 0, contrast = 0, exposure = 0, highlights = 0, shadows = 0,
                toneCurves = ToneCurves(curve(0.0 to 0.0, 0.5 to 0.62, 1.0 to 1.0)),
            ),
        )
        assertTrue(masterOnly.contentEquals(expectedMasterOnly))

        // With a red curve: the red channel moves where the master-only
        // path left it (the channel path is LIVE, and the green/blue
        // channels still match the master-only apply).
        val withRed = base.copyOf()
        EditPipeline.apply(
            withRed, 3, 1,
            EditRecipe(
                toneCurves = ToneCurves(
                    curve(0.0 to 0.0, 0.5 to 0.62, 1.0 to 1.0),
                    red = curve(0.0 to 0.0, 1.0 to 0.8),
                ),
            ),
        )
        var redMoved = false
        for (i in base.indices) {
            val expectedGreenBlue = (expectedMasterOnly[i] and 0xFF00FFFF.toInt())
            assertEquals("green/blue drifted at $i", expectedGreenBlue, withRed[i] and 0xFF00FFFF.toInt())
            if (((withRed[i] ushr 16) and 0xFF) != ((expectedMasterOnly[i] ushr 16) and 0xFF)) {
                redMoved = true
            }
        }
        assertTrue("the red curve must move at least one red value", redMoved)
    }

    @Test
    fun `an r68 forward-state sidecar renders its channel curves today`() {
        // The exact wire shape r68 round-tripped as forward state: a
        // green curve that lifts the shadows. No migration, no flag ---
        // the same JSON renders the colour it promised.
        val sidecar = EditRecipeCodec.fromJson(
            "{\"schemaVersion\":1,\"toneCurves\":{\"master\":[[0.0,0.0],[1.0,1.0]]," +
                "\"green\":[[0.0,0.3],[0.5,0.6],[1.0,1.0]]}}",
        )
        val restored = (sidecar as EditRecipeCodec.ParseResult.Valid).recipe
        val curves = restored.toneCurves!!
        assertTrue(curves.hasChannelCurves)
        val pixels = intArrayOf(argb(255, 0, 0, 0))
        EditPipeline.apply(pixels, 1, 1, restored)
        // Green at input 0 reads the green curve's floor (0.3 * 255 = 76.5,
        // the bake's kotlin.math.round --- Math.rint, half-to-EVEN --- lands
        // on 76); red and blue stay at the identity ramp's 0.
        assertEquals(76, (pixels[0] ushr 8) and 0xFF)
        assertEquals(0, (pixels[0] ushr 16) and 0xFF)
        assertEquals(0, pixels[0] and 0xFF)
    }

    @Test
    fun `a channel-only state breaks the recipe identity`() {
        // ToneCurves(identity, red = curve) is NOT the identity: the
        // pipeline's gate opens for it (isIdentity == false).
        val channelOnly = EditRecipe(
            toneCurves = ToneCurves(identity, blue = curve(0.0 to 0.0, 1.0 to 0.7)),
        )
        assertFalse(channelOnly.isAdjustmentIdentity)
        assertFalse(channelOnly.isIdentity)
    }
}
