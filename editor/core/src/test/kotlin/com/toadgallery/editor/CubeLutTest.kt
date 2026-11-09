package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LUT filter engine (r33, Phase 7 v2): the `.cube` parser, the
 * trilinear sampler and the intensity blend. Every interpolation value
 * below is hand-computed (the repo has no local toolchain --- ADR-0013
 * --- so the assertions carry their arithmetic in the comments).
 */
class CubeLutTest {

    // ---- helpers ----

    /** A size-2 identity table: corner (r,g,b) holds the value (r,g,b). */
    private fun identityCubeText(): String = buildString {
        appendLine("TITLE \"identity\"")
        appendLine("LUT_3D_SIZE 2")
        // R fastest: (0,0,0) (1,0,0) (0,1,0) (1,1,0) (0,0,1) (1,0,1) (0,1,1) (1,1,1)
        for (b in 0..1) for (g in 0..1) for (r in 0..1) {
            appendLine("$r $g $b")
        }
    }

    private fun parseOk(text: String, id: String = ""): CubeLut =
        when (val result = CubeLutParser.parse(text, id)) {
            is CubeLutParser.Result.Valid -> result.lut
            is CubeLutParser.Result.Invalid ->
                throw AssertionError("expected valid parse, got: ${result.reason}")
        }

    private fun parseInvalid(text: String): String =
        when (val result = CubeLutParser.parse(text)) {
            is CubeLutParser.Result.Valid ->
                throw AssertionError("expected invalid parse, got a LUT of size ${result.lut.size}")
            is CubeLutParser.Result.Invalid -> result.reason
        }

    // ---- parser: the happy path ----

    @Test
    fun `parses title size domain and the full table`() {
        val text = buildString {
            appendLine("# a comment")
            appendLine("TITLE \"my look\"")
            appendLine("LUT_3D_SIZE 2")
            appendLine("DOMAIN_MIN 0.1 0.2 0.3")
            appendLine("DOMAIN_MAX 0.9 0.8 0.7")
            appendLine()
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("0.5 0.5 0.5")
        }
        val lut = parseOk(text, id = "my-look")
        assertEquals("my-look", lut.id)
        assertEquals("my look", lut.title)
        assertEquals(2, lut.size)
        assertEquals(0.1, lut.domainMinR, 1e-12)
        assertEquals(0.2, lut.domainMinG, 1e-12)
        assertEquals(0.3, lut.domainMinB, 1e-12)
        assertEquals(0.9, lut.domainMaxR, 1e-12)
        assertEquals(0.8, lut.domainMaxG, 1e-12)
        assertEquals(0.7, lut.domainMaxB, 1e-12)
        // All eight samples are 0.5 --- any probe returns exactly 0.5.
        val out = lut.sample(0.5, 0.5, 0.5)
        assertEquals(0.5, out[0], 1e-9)
        assertEquals(0.5, out[1], 1e-9)
        assertEquals(0.5, out[2], 1e-9)
    }

    @Test
    fun `defaults when title and domain are absent`() {
        val lut = parseOk("LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n")
        assertEquals("", lut.title)
        assertEquals(0.0, lut.domainMinR, 0.0)
        assertEquals(1.0, lut.domainMaxG, 0.0)
        assertEquals(1.0, lut.domainMaxB, 0.0)
    }

    // ---- parser: rejection ----

    @Test
    fun `missing LUT_3D_SIZE is invalid`() {
        assertTrue(parseInvalid("TITLE \"x\"\n0 0 0\n").contains("LUT_3D_SIZE"))
    }

    @Test
    fun `1D LUTs are rejected as a different tool`() {
        assertTrue(parseInvalid("LUT_1D_SIZE 256\n").contains("LUT_1D_SIZE"))
    }

    @Test
    fun `size bounds are enforced`() {
        assertTrue(parseInvalid("LUT_3D_SIZE 1\n").contains("outside"))
        assertTrue(parseInvalid("LUT_3D_SIZE 65\n").contains("outside"))
    }

    @Test
    fun `too few data values is invalid`() {
        val text = "LUT_3D_SIZE 2\n" + (0 until 23).joinToString("\n") { "0.1 0.2 0.3" }
        assertTrue(parseInvalid(text).contains("found 69"))
    }

    @Test
    fun `too many data values is invalid`() {
        val text = "LUT_3D_SIZE 2\n" + (0 until 9).joinToString("\n") { "0.1 0.2 0.3" }
        assertTrue(parseInvalid(text).contains("found 27"))
    }

    @Test
    fun `a data line with four values is invalid`() {
        val text = "LUT_3D_SIZE 2\n0 0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n"
        assertTrue(parseInvalid(text).contains("exactly three"))
    }

    @Test
    fun `a non-numeric data value is invalid`() {
        val text = "LUT_3D_SIZE 2\n0 0 zero\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n0 0 0\n"
        assertTrue(parseInvalid(text).contains("not a number"))
    }

    @Test
    fun `data before LUT_3D_SIZE is invalid`() {
        assertTrue(parseInvalid("0 0 0\nLUT_3D_SIZE 2\n").contains("before LUT_3D_SIZE"))
    }

    @Test
    fun `duplicate LUT_3D_SIZE is invalid`() {
        val text = "LUT_3D_SIZE 2\nLUT_3D_SIZE 2\n" +
            (0 until 8).joinToString("\n") { "0 0 0" }
        assertTrue(parseInvalid(text).contains("duplicate LUT_3D_SIZE"))
    }

    @Test
    fun `an unquoted TITLE is invalid`() {
        assertTrue(parseInvalid("TITLE my look\nLUT_3D_SIZE 2\n").contains("double-quoted"))
    }

    @Test
    fun `a degenerate domain is invalid`() {
        val text = "LUT_3D_SIZE 2\nDOMAIN_MIN 0.5 0 0\nDOMAIN_MAX 0.5 1 1\n" +
            (0 until 8).joinToString("\n") { "0 0 0" }
        assertTrue(parseInvalid(text).contains("degenerate"))
    }

    @Test
    fun `RANGE lines are accepted and ignored`() {
        val text = "LUT_3D_SIZE 2\nRANGE_MIN 0 0 0\nRANGE_MAX 1 1 1\n" +
            (0 until 8).joinToString("\n") { "0.25 0.5 0.75" }
        val lut = parseOk(text)
        // The constant table wins over any declared range.
        val out = lut.sample(0.9, 0.9, 0.9)
        assertEquals(0.25, out[0], 1e-9)
        assertEquals(0.5, out[1], 1e-9)
        assertEquals(0.75, out[2], 1e-9)
    }

    // ---- sampler: interpolation arithmetic (hand-computed) ----

    @Test
    fun `an identity cube samples every input back exactly`() {
        val lut = parseOk(identityCubeText())
        val probes = listOf(
            Triple(0.0, 0.0, 0.0), Triple(1.0, 1.0, 1.0),
            Triple(0.25, 0.5, 0.75), Triple(0.9, 0.1, 0.6),
            Triple(0.33, 0.33, 0.33),
        )
        for ((r, g, b) in probes) {
            val out = lut.sample(r, g, b)
            assertEquals("r=$r", r, out[0], 1e-9)
            assertEquals("g=$g", g, out[1], 1e-9)
            assertEquals("b=$b", b, out[2], 1e-9)
        }
    }

    @Test
    fun `the R index increments fastest in the table order`() {
        // Line 2 of the table (index 1 = grid (1,0,0)) is pure red; a
        // green-fastest table would put a different colour there.
        val text = "LUT_3D_SIZE 2\n" +
            "0 0 0\n" + // (0,0,0)
            "1 0 0\n" + // (1,0,0) <- R moved first
            "0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n"
        val lut = parseOk(text)
        val out = lut.sample(1.0, 0.0, 0.0)
        assertEquals(1.0, out[0], 1e-9)
        assertEquals(0.0, out[1], 1e-9)
        assertEquals(0.0, out[2], 1e-9)
    }

    @Test
    fun `a size-3 midpoint is the average of its neighbours`() {
        // A size-3 table whose R channel ramps 0, 0.5, 1 along R: the
        // grid point (1,0,0) must hold 0.5 and sample(0.5, 0, 0) lands
        // exactly on it.
        val text = buildString {
            appendLine("LUT_3D_SIZE 3")
            for (b in 0..2) for (g in 0..2) for (r in 0..2) {
                appendLine("${r / 2.0} 0 0")
            }
        }
        val lut = parseOk(text)
        val out = lut.sample(0.5, 0.0, 0.0)
        assertEquals(0.5, out[0], 1e-9)
        assertEquals(0.0, out[1], 1e-9)
        assertEquals(0.0, out[2], 1e-9)
    }

    @Test
    fun `trilinear blends all three axes at an off-grid point`() {
        // Size-2 table: the (0,0,0) corner is black, EVERY other corner
        // is white. sample(0.5, 0.5, 0.5) = 1 - 0.5*0.5*0.5 = 0.875 per
        // channel (the corner weight decomposition).
        val text = "LUT_3D_SIZE 2\n" +
            "0 0 0\n1 1 1\n1 1 1\n1 1 1\n1 1 1\n1 1 1\n1 1 1\n1 1 1\n"
        val lut = parseOk(text)
        val out = lut.sample(0.5, 0.5, 0.5)
        assertEquals(0.875, out[0], 1e-9)
        assertEquals(0.875, out[1], 1e-9)
        assertEquals(0.875, out[2], 1e-9)
    }

    @Test
    fun `a custom domain positions inputs inside the grid`() {
        // DOMAIN 0.25..0.75 on R: input 0.5 sits at the R grid centre.
        // The identity table then returns 0.5 there.
        val text = buildString {
            appendLine("LUT_3D_SIZE 2")
            appendLine("DOMAIN_MIN 0.25 0.0 0.0")
            appendLine("DOMAIN_MAX 0.75 1.0 1.0")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$r $g $b")
        }
        val lut = parseOk(text)
        val out = lut.sample(0.5, 0.0, 0.0)
        assertEquals(0.5, out[0], 1e-9)
        // Inputs below the domain floor clamp to the first sample.
        val clamped = lut.sample(0.0, 0.0, 0.0)
        assertEquals(0.0, clamped[0], 1e-9)
    }

    @Test
    fun `out-of-range table values clamp at sample time`() {
        // Extended-range tables are author data; the OUTPUT range is
        // engine policy: [-0.5, 1.5] table entries clamp to [0, 1].
        val text = "LUT_3D_SIZE 2\n" +
            "-0.5 -0.5 -0.5\n1.5 1.5 1.5\n1.5 1.5 1.5\n1.5 1.5 1.5\n" +
            "1.5 1.5 1.5\n1.5 1.5 1.5\n1.5 1.5 1.5\n1.5 1.5 1.5\n"
        val lut = parseOk(text)
        val low = lut.sample(0.0, 0.0, 0.0)
        assertEquals(0.0, low[0], 1e-9)
        val high = lut.sample(1.0, 1.0, 1.0)
        assertEquals(1.0, high[0], 1e-9)
    }

    // ---- the intensity blend on pixels ----

    @Test
    fun `zero intensity never touches the pixels`() {
        val lut = parseOk(identityCubeText())
        val pixels = intArrayOf(0xFF123456.toInt(), 0xFF89ABCD.toInt(), 0x80102030.toInt())
        val before = pixels.copyOf()
        lut.applyTo(pixels, 0.0)
        assertTrue(pixels.contentEquals(before))
    }

    @Test
    fun `full intensity applies the look and preserves alpha`() {
        // The look: everything maps to pure red (the whole table is red).
        val text = "LUT_3D_SIZE 2\n" + (0 until 8).joinToString("\n") { "1 0 0" }
        val lut = parseOk(text)
        val pixels = intArrayOf(0xFF4080C0.toInt(), 0x80204060.toInt())
        lut.applyTo(pixels, 1.0)
        assertEquals(0xFFFF0000.toInt(), pixels[0])
        // Alpha 0x80 survives; colour is the full look.
        assertEquals(0x80FF0000.toInt(), pixels[1])
    }

    @Test
    fun `half intensity is the straight lerp`() {
        // Look = pure red; input colour (0, 255, 255); t = 0.5:
        // out = in + (look - in) * 0.5 = (0.5, 0.5, 0.5) = 128 (0x80).
        val text = "LUT_3D_SIZE 2\n" + (0 until 8).joinToString("\n") { "1 0 0" }
        val lut = parseOk(text)
        val pixels = intArrayOf(0xFF00FFFF.toInt())
        lut.applyTo(pixels, 0.5)
        val r = (pixels[0] shr 16) and 0xFF
        val g = (pixels[0] shr 8) and 0xFF
        val b = pixels[0] and 0xFF
        assertEquals(128, r)
        assertEquals(128, g)
        assertEquals(128, b)
    }

    @Test
    fun `intensity above 1 clamps to the full look`() {
        val text = "LUT_3D_SIZE 2\n" + (0 until 8).joinToString("\n") { "1 0 0" }
        val lut = parseOk(text)
        val pixels = intArrayOf(0xFF00FFFF.toInt())
        lut.applyTo(pixels, 2.0)
        assertEquals(0xFFFF0000.toInt(), pixels[0])
    }
}
