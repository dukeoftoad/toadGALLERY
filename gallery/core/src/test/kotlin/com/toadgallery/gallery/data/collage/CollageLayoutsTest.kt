package com.toadgallery.gallery.data.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the collage creator's pure layout core (r27, the MI-style
 * organisational UX round; ADR-0016 --- [CollageLayouts] is pure Kotlin,
 * so the template catalogue's geometry and the render math are pinned
 * here instead of living implicitly in the preview canvas and the
 * exporter).
 *
 * What is pinned:
 * - EVERY template tiles the unit square exactly: slots stay in
 *   0.0..1.0, are well-formed (left < right, top < bottom), are pairwise
 *   DISJOINT, and their areas sum to 1 --- a future template that would
 *   lie fails the build instead of shipping a broken render;
 * - per-count availability: every photo count in MIN_PHOTOS..MAX_PHOTOS
 *   has at least one template (and the default is the first), counts
 *   outside the range honestly answer none --- the same 2..9 gate
 *   [CollageHandoff]'s consume applies (the hand-off's payload embeds
 *   `android.net.Uri`, so ITS one-shot mechanics cannot be JVM-tested;
 *   these bounds pin the range gate it enforces);
 * - unique template ids (the picker's LazyRow keys);
 * - [CollageLayouts.slotPixels]: one rounding rule per edge, shared
 *   normalized edges land on the SAME pixel boundary, outermost edges
 *   clamp to the canvas;
 * - [CollageLayouts.centerCropSource]: the largest centered sub-rectangle
 *   matching the destination's aspect, with the honest degenerate answers
 *   for empty bitmaps and zero-sized destinations.
 */
class CollageLayoutsTest {

    // ------------------------------------------------------------------
    // The catalogue: every template tiles the unit square exactly
    // ------------------------------------------------------------------

    @Test
    fun `the catalogue covers every supported photo count`() {
        (CollageLayouts.MIN_PHOTOS..CollageLayouts.MAX_PHOTOS).forEach { count ->
            val templates = CollageLayouts.templatesFor(count)
            assertTrue("at least one template for $count photos", templates.isNotEmpty())
            assertTrue(
                "every template of $count lays out exactly $count slots",
                templates.all { it.slotCount == count },
            )
            assertEquals(
                "the default for $count is the first picker entry",
                templates.first(),
                CollageLayouts.defaultFor(count),
            )
        }
    }

    @Test
    fun `counts outside the supported range honestly answer no templates`() {
        assertEquals(0, CollageLayouts.templatesFor(0).size)
        assertEquals(0, CollageLayouts.templatesFor(1).size)
        assertEquals(0, CollageLayouts.templatesFor(10).size)
        assertEquals(0, CollageLayouts.templatesFor(-3).size)
        assertEquals(null, CollageLayouts.defaultFor(1))
        assertEquals(null, CollageLayouts.defaultFor(10))
    }

    @Test
    fun `the supported range is exactly two to nine photos`() {
        assertEquals(2, CollageLayouts.MIN_PHOTOS)
        assertEquals(9, CollageLayouts.MAX_PHOTOS)
        // The bounds the CollageHandoff range gate enforces (see the
        // class KDoc: the hand-off's Uri payload cannot be constructed on
        // the plain JVM, so its gate's bounds are pinned HERE).
        assertTrue(2 in CollageLayouts.MIN_PHOTOS..CollageLayouts.MAX_PHOTOS)
        assertTrue(9 in CollageLayouts.MIN_PHOTOS..CollageLayouts.MAX_PHOTOS)
        assertTrue(1 !in CollageLayouts.MIN_PHOTOS..CollageLayouts.MAX_PHOTOS)
        assertTrue(10 !in CollageLayouts.MIN_PHOTOS..CollageLayouts.MAX_PHOTOS)
    }

    @Test
    fun `every template slot is well-formed and inside the unit square`() {
        CollageLayouts.all.forEach { template ->
            template.slots.forEachIndexed { index, slot ->
                assertTrue("${template.id} slot $index left >= 0", slot.left >= 0f)
                assertTrue("${template.id} slot $index top >= 0", slot.top >= 0f)
                assertTrue("${template.id} slot $index right <= 1", slot.right <= 1f)
                assertTrue("${template.id} slot $index bottom <= 1", slot.bottom <= 1f)
                assertTrue("${template.id} slot $index left < right", slot.left < slot.right)
                assertTrue("${template.id} slot $index top < bottom", slot.top < slot.bottom)
                assertTrue("${template.id} slot $index width > 0", slot.width > 0f)
                assertTrue("${template.id} slot $index height > 0", slot.height > 0f)
            }
        }
    }

    @Test
    fun `every template's slots are pairwise disjoint`() {
        CollageLayouts.all.forEach { template ->
            val slots = template.slots
            for (a in slots.indices) {
                for (b in a + 1 until slots.size) {
                    val first = slots[a]
                    val second = slots[b]
                    val overlapsHorizontally = first.left < second.right && second.left < first.right
                    val overlapsVertically = first.top < second.bottom && second.top < first.bottom
                    assertTrue(
                        "${template.id}: slots $a and $b overlap",
                        !(overlapsHorizontally && overlapsVertically),
                    )
                }
            }
        }
    }

    @Test
    fun `every template's slot areas sum to exactly one square`() {
        CollageLayouts.all.forEach { template ->
            val area = template.slots.sumOf { (it.width * it.height).toDouble() }
            assertEquals(
                "${template.id} tiles the unit square (area sum 1.0)",
                1.0,
                area,
                1e-4,
            )
        }
    }

    @Test
    fun `template ids are unique across the catalogue`() {
        val ids = CollageLayouts.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(17, CollageLayouts.all.size) // the v1 catalogue freeze
    }

    // ------------------------------------------------------------------
    // slotPixels: one rounding rule, shared edges, clamped canvas edges
    // ------------------------------------------------------------------

    @Test
    fun `slotPixels scales a normalized slot onto the canvas`() {
        val rect = CollageLayouts.slotPixels(CollageSlot(0f, 0f, 0.5f, 1f), 100, 200)
        assertEquals(PixelRect(0, 0, 50, 200), rect)
        assertEquals(50, rect.width)
        assertEquals(200, rect.height)
    }

    @Test
    fun `slots sharing a normalized edge share the same pixel boundary`() {
        // 0.5 of 101 pixels is 50.5, which rounds (half-up) to 51 for
        // BOTH the left slot's right edge and the right slot's left edge
        // --- no pixel-thin background sliver between neighbors.
        val left = CollageLayouts.slotPixels(CollageSlot(0f, 0f, 0.5f, 1f), 101, 101)
        val right = CollageLayouts.slotPixels(CollageSlot(0.5f, 0f, 1f, 1f), 101, 101)
        assertEquals(left.right, right.left)
        assertEquals(51, left.right)
        // The pair covers the canvas without overlap or gap.
        assertEquals(101, left.width + right.width)
    }

    @Test
    fun `the outermost edges clamp to the canvas`() {
        val full = CollageLayouts.slotPixels(CollageSlot(0f, 0f, 1f, 1f), 60, 60)
        assertEquals(PixelRect(0, 0, 60, 60), full)
        // 0.8 * 60 = 48 exactly; 1.0 * 60 = 60 (never 61).
        val partial = CollageLayouts.slotPixels(CollageSlot(0.8f, 0.8f, 1f, 1f), 60, 60)
        assertEquals(PixelRect(48, 48, 60, 60), partial)
    }

    // ------------------------------------------------------------------
    // centerCropSource: the shared center-crop rule
    // ------------------------------------------------------------------

    @Test
    fun `a landscape bitmap into a square slot crops the sides`() {
        val source = CollageLayouts.centerCropSource(
            bitmapWidth = 200,
            bitmapHeight = 100,
            destinationWidth = 50,
            destinationHeight = 50,
        )
        // Widest-matching band: 100x100, centered.
        assertEquals(PixelRect(50, 0, 150, 100), source)
    }

    @Test
    fun `a portrait bitmap into a square slot crops the top and bottom`() {
        val source = CollageLayouts.centerCropSource(
            bitmapWidth = 100,
            bitmapHeight = 200,
            destinationWidth = 50,
            destinationHeight = 50,
        )
        assertEquals(PixelRect(0, 50, 100, 150), source)
    }

    @Test
    fun `matching aspects keep the full frame`() {
        val source = CollageLayouts.centerCropSource(
            bitmapWidth = 100,
            bitmapHeight = 100,
            destinationWidth = 50,
            destinationHeight = 50,
        )
        assertEquals(PixelRect(0, 0, 100, 100), source)
    }

    @Test
    fun `a square bitmap into a wide destination crops to a centered band`() {
        // The 2:1 destination keeps the bitmap's middle 100x50 band ---
        // the same rule that crops a portrait photo in a wide slot.
        val source = CollageLayouts.centerCropSource(
            bitmapWidth = 100,
            bitmapHeight = 100,
            destinationWidth = 200,
            destinationHeight = 100,
        )
        assertEquals(PixelRect(0, 25, 100, 75), source)
    }

    @Test
    fun `an empty bitmap answers an empty source`() {
        assertEquals(
            PixelRect(0, 0, 0, 0),
            CollageLayouts.centerCropSource(0, 0, 50, 50),
        )
        assertEquals(
            PixelRect(0, 0, 0, 0),
            CollageLayouts.centerCropSource(-10, 100, 50, 50),
        )
    }

    @Test
    fun `a zero-sized destination answers the bitmap's full frame`() {
        assertEquals(
            PixelRect(0, 0, 200, 100),
            CollageLayouts.centerCropSource(200, 100, 0, 50),
        )
        assertEquals(
            PixelRect(0, 0, 200, 100),
            CollageLayouts.centerCropSource(200, 100, 50, -1),
        )
    }
}
