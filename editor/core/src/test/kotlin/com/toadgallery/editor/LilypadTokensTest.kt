package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Lilypad token ladder's pins: luminance-ordered ladder, WCAG contrast floors,
 * canonical opaque ARGB colors, physical motion, documented type scale, four-primitive haptics.
 */
class LilypadTokensTest {

    // ---- The WCAG arithmetic (the test's own; pure functions of the bits) ----

    private fun linear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    }

    private fun relativeLuminance(argb: Long): Float {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        return 0.2126f * linear(r) + 0.7152f * linear(g) + 0.0722f * linear(b)
    }

    private fun contrastRatio(a: Long, b: Long): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    // ---- The ladder ---

    @Test
    fun `the ladder climbs strictly by luminance`() {
        val ladder = listOf(
            LilypadTokens.POND,
            LilypadTokens.PAD,
            LilypadTokens.PAD_RAISED,
            LilypadTokens.HAIRLINE,
            LilypadTokens.REED,
            LilypadTokens.PAPER,
        )
        for (i in 1 until ladder.size) {
            assertTrue(
                "step ${ladder[i - 1]} must be darker than ${ladder[i]}",
                relativeLuminance(ladder[i - 1]) < relativeLuminance(ladder[i]),
            )
        }
    }

    @Test
    fun `paper on pad meets the body-text floor`() {
        assertTrue(contrastRatio(LilypadTokens.PAPER, LilypadTokens.PAD) >= 7.0f)
    }

    @Test
    fun `reed on pad meets the muted-text floor`() {
        assertTrue(contrastRatio(LilypadTokens.REED, LilypadTokens.PAD) >= 4.5f)
    }

    @Test
    fun `ember and its on-tone meet the accent-text floor`() {
        assertTrue(contrastRatio(LilypadTokens.EMBER, LilypadTokens.ON_EMBER) >= 4.5f)
    }

    @Test
    fun `ember stays legible on the sheet`() {
        assertTrue(contrastRatio(LilypadTokens.EMBER, LilypadTokens.PAD) >= 3.0f)
    }

    // ---- The format canon ---

    @Test
    fun `every color is a canonical opaque ARGB`() {
        val colors = listOf(
            LilypadTokens.POND,
            LilypadTokens.PAD,
            LilypadTokens.PAD_RAISED,
            LilypadTokens.HAIRLINE,
            LilypadTokens.REED,
            LilypadTokens.PAPER,
            LilypadTokens.EMBER,
            LilypadTokens.ON_EMBER,
            LilypadTokens.Roles.OUTLINE,
            LilypadTokens.Roles.EMBER_DEEP,
            LilypadTokens.Roles.ON_EMBER_CONTAINER,
            LilypadTokens.Roles.EMBER_DIM,
            LilypadTokens.Roles.STEEL,
            LilypadTokens.Roles.ON_STEEL,
            LilypadTokens.Roles.STEEL_DEEP,
            LilypadTokens.Roles.PAPER_SOFT,
        )
        for (c in colors) {
            assertEquals("alpha of 0x${c.toString(16)} must be FF", 0xFFL, (c shr 24) and 0xFF)
            assertTrue("0x${c.toString(16)} must be a 6-digit ARGB", c in 0xFF000000L..0xFFFFFFFFL)
        }
    }

    // ---- Motion ---

    @Test
    fun `motion is physical`() {
        assertEquals(240, LilypadTokens.MOTION_STANDARD_MS)
        assertEquals(180, LilypadTokens.MOTION_TOOL_FADE_MS)
        assertEquals(8, LilypadTokens.MOTION_PARALLAX_DP)
        assertTrue(LilypadTokens.MOTION_SHEET_DAMPING > 0f)
        assertTrue(LilypadTokens.MOTION_SHEET_DAMPING < 1f)
        assertTrue(LilypadTokens.MOTION_SHEET_STIFFNESS > 0f)
    }

    // ---- Type ---

    @Test
    fun `the type scale is the documented four stops`() {
        assertEquals(96, LilypadTokens.TYPE_DISPLAY_SP)
        assertEquals(800, LilypadTokens.TYPE_DISPLAY_WEIGHT)
        assertEquals(30, LilypadTokens.TYPE_SECTION_SP)
        assertEquals(800, LilypadTokens.TYPE_SECTION_WEIGHT)
        assertEquals(15, LilypadTokens.TYPE_BODY_SP)
        assertEquals(400, LilypadTokens.TYPE_BODY_WEIGHT)
        assertEquals(12, LilypadTokens.TYPE_VALUE_SP)
        assertEquals(600, LilypadTokens.TYPE_VALUE_WEIGHT)
    }

    // ---- Shape ---

    @Test
    fun `the shape family is three corners`() {
        assertEquals(16, LilypadTokens.SHEET_CORNER_DP)
        assertEquals(12, LilypadTokens.CONTROL_CORNER_DP)
        assertEquals(8, LilypadTokens.CHIP_CORNER_DP)
    }

    // ---- Haptics ---

    @Test
    fun `the haptic vocabulary is exactly four primitives`() {
        assertEquals(
            listOf("ZERO_TICK", "DETENT_DOUBLE", "COMMIT_SOFT", "EXPORT_SUCCESS"),
            LilypadTokens.Haptic.entries.map { it.name },
        )
    }
}
