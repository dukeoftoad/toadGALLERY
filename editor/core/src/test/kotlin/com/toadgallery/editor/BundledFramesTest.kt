package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled-frame conformance suite (r38; the `BundledStickersTest`
 * pattern for parametric code): the registry's self-consistency and the
 * authored geometry's exactness (the functions ARE the specification;
 * `FramesTest` pins their outputs on hand canvases, this suite pins the
 * registry shape).
 */
class BundledFramesTest {

    @Test
    fun `the registry is unique, ordered and self-consistent`() {
        assertEquals(5, BundledFrames.ORDERED.size)
        val ids = BundledFrames.ORDERED.map { it.id }
        assertTrue("ids must be unique", ids.size == ids.toSet().size)
        for (entry in BundledFrames.ORDERED) {
            assertEquals(entry, BundledFrames.byId(entry.id))
            assertNotNull("geometry for ${entry.id} must resolve", BundledFrames.geometry(entry.id))
        }
        assertNull(BundledFrames.byId("no-such-frame"))
        assertNull(BundledFrames.geometry("no-such-frame"))
    }

    @Test
    fun `the registry pins the five r38 slots`() {
        assertEquals(
            listOf("plain", "inner-line", "double-line", "film", "polaroid"),
            BundledFrames.ORDERED.map { it.id },
        )
        // The chip labels are the frames' proper names (not localized).
        assertEquals(
            listOf("Plain", "Inner line", "Double line", "Film", "Polaroid"),
            BundledFrames.ORDERED.map { it.displayName },
        )
    }

    @Test
    fun `every authored geometry is hole-free except film`() {
        for (entry in BundledFrames.ORDERED) {
            val geo = BundledFrames.geometry(entry.id)!!.invoke(4, 100, 80)
            if (entry.id == "film") {
                assertTrue("film punches holes", geo.holes.isNotEmpty())
                assertTrue("film carries only the two bands", geo.rects.size == 2)
            } else {
                assertTrue("${entry.id} punches no holes", geo.holes.isEmpty())
                assertTrue("${entry.id} carries rects", geo.rects.isNotEmpty())
            }
        }
    }

    @Test
    fun `the geometry is pure over its integers`() {
        // Same inputs, same outputs --- the function is a value.
        val a = BundledFrames.geometry("film")!!.invoke(8, 200, 150)
        val b = BundledFrames.geometry("film")!!.invoke(8, 200, 150)
        assertEquals(a, b)
    }
}
