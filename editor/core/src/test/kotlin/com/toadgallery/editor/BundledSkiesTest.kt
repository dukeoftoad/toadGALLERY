package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bundled-sky conformance: registry self-consistency, deterministic renders, exact integer banding. */
class BundledSkiesTest {

    @Test
    fun `the registry is unique, ordered and self-consistent`() {
        assertEquals(5, BundledSkies.ORDERED.size)
        val ids = BundledSkies.ORDERED.map { it.id }
        assertTrue("ids must be unique", ids.size == ids.toSet().size)
        for (entry in BundledSkies.ORDERED) {
            assertEquals(entry, BundledSkies.byId(entry.id))
            assertNotNull("render for ${entry.id} must resolve", BundledSkies.render(entry.id, 8, 8))
        }
        assertNull(BundledSkies.byId("no-such-sky"))
        assertNull(BundledSkies.render("no-such-sky", 8, 8))
    }

    @Test
    fun `the registry pins the five r41 slots`() {
        assertEquals(
            listOf("day-clear", "dusk", "night", "overcast", "golden"),
            BundledSkies.ORDERED.map { it.id },
        )
        // The chip labels are the skies' proper names (not localized).
        assertEquals(
            listOf("Day clear", "Dusk", "Night", "Overcast", "Golden hour"),
            BundledSkies.ORDERED.map { it.displayName },
        )
    }

    @Test
    fun `the render is pure over its integers`() {
        // Same inputs, same outputs --- the render is a value.
        val a = BundledSkies.render("night", 64, 32)!!
        val b = BundledSkies.render("night", 64, 32)!!
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `the vertical banding is the exact integer interpolation`() {
        // day-clear: top 0xFF2E7DD1, bottom 0xFF9CCBF0. At h=4 rows carry t = 0, 64, 128, 192.
        // Row 0 is top; row 1: top + (bottom-top)*64/256 = exact midpoint / 4.
        val w = 8
        val h = 4
        val render = BundledSkies.render("day-clear", w, h)!!
        assertEquals(w * h, render.size)
        val top = 0xFF2E7DD1.toInt()
        val bottom = 0xFF9CCBF0.toInt()
        fun expect(y: Int): Int {
            val t = y * 256 / h
            val r = ((top shr 16) and 0xFF) + (((bottom shr 16) and 0xFF) - ((top shr 16) and 0xFF)) * t / 256
            val g = ((top shr 8) and 0xFF) + (((bottom shr 8) and 0xFF) - ((top shr 8) and 0xFF)) * t / 256
            val b = (top and 0xFF) + ((bottom and 0xFF) - (top and 0xFF)) * t / 256
            return (0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                assertEquals("at ($x,$y)", expect(y), render[y * w + x])
            }
        }
        assertEquals(0xFF2E7DD1.toInt(), render[0]) // row 0 is exactly the top colour
    }

    @Test
    fun `the night field carries its fixed star pattern`() {
        val w = 64
        val h = 32
        val render = BundledSkies.render("night", w, h)!!
        var stars = 0
        var pattern = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if ((x * 31 + y * 17) % 211 == 0) pattern++
                if (render[y * w + x] == 0xFFECF0FF.toInt()) stars++
            }
        }
        assertTrue("the star count equals the pattern count", pattern > 0 && stars == pattern)
        // A star pixel's neighbours are band pixels (the pattern is sparse).
        assertEquals(0xFFECF0FF.toInt(), render[0 * w + 0]) // (0,0): 0 % 211 == 0
    }

    @Test
    fun `every render is fully opaque and every sky is distinct`() {
        val renders = BundledSkies.ORDERED.map { it.id to BundledSkies.render(it.id, 16, 8)!! }
        for ((_, pixels) in renders) {
            for (c in pixels) {
                assertTrue("the alpha must be opaque", (c.toInt() ushr 24) == 0xFF)
            }
        }
        // The five skies are pairwise distinct at a non-trivial size.
        for (i in renders.indices) {
            for (j in i + 1 until renders.size) {
                assertTrue(
                    "${renders[i].first} must differ from ${renders[j].first}",
                    !renders[i].second.contentEquals(renders[j].second),
                )
            }
        }
    }
}
