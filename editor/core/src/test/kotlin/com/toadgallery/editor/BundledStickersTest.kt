package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bundled-sticker conformance: registry self-consistency, structural validity of every
 * shape, and spot coordinates pinning the authored trigonometry (>= 0.3 from a .5 boundary).
 */
class BundledStickersTest {

    // ---- Registry ----

    @Test
    fun `the registry is unique, ordered and self-consistent`() {
        assertEquals(8, BundledStickers.ORDERED.size)
        val ids = BundledStickers.ORDERED.map { it.id }
        assertTrue("ids must be unique", ids.size == ids.toSet().size)
        for (entry in BundledStickers.ORDERED) {
            assertEquals(entry, BundledStickers.byId(entry.id))
            assertNotNull("shape for ${entry.id} must resolve", BundledStickers.shape(entry.id))
        }
        assertNull(BundledStickers.byId("no-such-sticker"))
        assertNull(BundledStickers.shape("no-such-sticker"))
    }

    @Test
    fun `the registry pins the eight r35 slots`() {
        assertEquals(
            listOf(
                "heart", "star", "smiley", "bubble",
                "bolt", "flower", "pin", "crown",
            ),
            BundledStickers.ORDERED.map { it.id },
        )
        // The chip labels are the stickers' proper names (not localized).
        assertEquals(
            listOf(
                "Heart", "Star", "Smiley", "Speech bubble",
                "Lightning", "Flower", "Location pin", "Crown",
            ),
            BundledStickers.ORDERED.map { it.displayName },
        )
    }

    // ---- Structural validity (every committed shape) ----

    @Test
    fun `every shape is structurally valid`() {
        for (entry in BundledStickers.ORDERED) {
            val shape = BundledStickers.shape(entry.id)!!
            assertTrue("${entry.id} needs at least one part", shape.parts.isNotEmpty())
            for (part in shape.parts) {
                assertTrue("${entry.id}: part colors are opaque 0xFF", (part.colorArgb ushr 24) == 0xFFL)
                assertTrue("${entry.id}: a part carries at least one subpath", part.ops.isNotEmpty())
                var subpaths = 0
                var open = false
                for (op in part.ops) {
                    when (op) {
                        is StickerShape.Op.MoveTo -> {
                            assertTrue("${entry.id}: MoveTo inside an open subpath", !open)
                            open = true
                        }
                        is StickerShape.Op.LineTo, is StickerShape.Op.CubicTo ->
                            assertTrue("${entry.id}: geometry outside a subpath", open)
                        is StickerShape.Op.Close -> {
                            assertTrue("${entry.id}: Close outside a subpath", open)
                            open = false
                            subpaths++
                        }
                    }
                }
                assertTrue("${entry.id}: a part must end every subpath with Close", !open)
                assertTrue("${entry.id}: a part drew at least one subpath", subpaths >= 1)
            }
        }
    }

    @Test
    fun `invalid shapes are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            StickerShape(listOf(StickerShape.Part(0xFFE53935L, listOf(StickerShape.Op.MoveTo(101, 50))))) // outside the box
        }
        assertThrows(IllegalArgumentException::class.java) {
            StickerShape(listOf(StickerShape.Part(0x80E53935L, listOf(StickerShape.Op.MoveTo(50, 50), StickerShape.Op.LineTo(60, 60), StickerShape.Op.Close)))) // non-opaque
        }
        assertThrows(IllegalArgumentException::class.java) {
            StickerShape(listOf(StickerShape.Part(0xFFE53935L, listOf(StickerShape.Op.LineTo(50, 50))))) // geometry outside a subpath
        }
        assertThrows(IllegalArgumentException::class.java) {
            StickerShape(emptyList()) // no parts
        }
    }

    // ---- Spot coordinates (the authored trigonometry, safe margins) ----

    @Test
    fun `the star polygon pins the authored radii`() {
        val star = BundledStickers.shape("star")!!
        val ops = star.parts[0].ops
        // Outer at -90°: (50,2); -18°: (96,35) --- 95.6 rounds with 0.4
        // margin; inner at -54°: (62,34) --- 61.8 rounds with 0.2+ margin.
        assertEquals(StickerShape.Op.MoveTo(50, 2), ops[0])
        assertEquals(StickerShape.Op.LineTo(62, 34), ops[1])
        assertEquals(StickerShape.Op.LineTo(96, 35), ops[2])
        assertEquals(StickerShape.Op.LineTo(69, 44), ops[3])
        assertEquals(StickerShape.Op.LineTo(78, 89), ops[4])
        assertEquals(StickerShape.Op.LineTo(50, 70), ops[5])
        assertEquals(StickerShape.Op.LineTo(22, 89), ops[6])
        assertEquals(StickerShape.Op.LineTo(31, 44), ops[7])
        assertEquals(StickerShape.Op.LineTo(4, 35), ops[8])
        assertEquals(StickerShape.Op.LineTo(38, 34), ops[9])
        assertEquals(StickerShape.Op.Close, ops.last())
    }

    @Test
    fun `the smiley face pins the 24-gon and its parts draw top-last`() {
        val smiley = BundledStickers.shape("smiley")!!
        assertEquals(4, smiley.parts.size) // face, eye, eye, mouth --- in order, top LAST
        // 24-gon r=46 about (50,50): theta=0 -> (96,50) exact (cos(0)=1);
        // theta=90 -> (50,96) exact (sin(90)=1) --- the BOTTOM point (y down).
        val face = smiley.parts[0].ops
        assertEquals(StickerShape.Op.MoveTo(96, 50), face[0])
        assertEquals(StickerShape.Op.LineTo(50, 96), face[6])
        // The smile arc: 8 points from 20°..160°, then Close chords it.
        val mouth = smiley.parts[3].ops
        assertEquals(9, mouth.size) // MoveTo + 7 LineTo + Close
        assertEquals(StickerShape.Op.MoveTo(78, 60), mouth[0]) // 30cos(20°)=28.2 -> 78.2 -> 78 (0.2 margin is tight but honest: 78.19)
        assertEquals(StickerShape.Op.Close, mouth.last())
    }

    @Test
    fun `the pin teardrop continues to the tip and tops with the inner circle`() {
        val pin = BundledStickers.shape("pin")!!
        assertEquals(2, pin.parts.size)
        val head = pin.parts[0].ops
        // 24-gon + LineTo(50,96) + Close: the tip is the second-to-last op.
        assertEquals(StickerShape.Op.LineTo(50, 96), head[head.size - 2])
        assertEquals(StickerShape.Op.Close, head.last())
        // The inner white circle is a 12-gon r=11 about (50,40): theta=0 -> (61,40) exact.
        assertEquals(StickerShape.Op.MoveTo(61, 40), pin.parts[1].ops[0])
    }

    @Test
    fun `the flower carries five petal subpaths in one part`() {
        val flower = BundledStickers.shape("flower")!!
        assertEquals(2, flower.parts.size)
        val petalPart = flower.parts[0]
        // 5 subpaths, each a 12-gon: MoveTo + 11 LineTo + Close = 13 ops.
        assertEquals(65, petalPart.ops.size)
        assertEquals(5, petalPart.ops.count { it is StickerShape.Op.MoveTo })
        // The yellow centre draws after the petals (top LAST).
        assertEquals(13, flower.parts[1].ops.size)
    }

    // ---- Palette sanity ----

    @Test
    fun `the palette is the authored five`() {
        val palette = BundledStickers.ORDERED
            .flatMap { BundledStickers.shape(it.id)!!.parts }
            .map { it.colorArgb }
            .toSet()
        assertTrue(palette.containsAll(listOf(0xFFE53935L, 0xFFFDD835L, 0xFFF06292L, 0xFF212121L, 0xFFFFFFFFL)))
        assertEquals(5, palette.size) // no stray colors
    }
}
