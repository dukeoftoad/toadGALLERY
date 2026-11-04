package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r35 sticker-layer model + the pure layout pass (the contract's
 * `StickerLayersTest`): the size box, the centre anchor, the origin
 * offset, the list order, the opacity-only alpha --- and the
 * preview ≡ export identity at the layout level (the r34 convention).
 */
class StickerLayersTest {

    // ---- Model validation (the structured-parse-failure backstop) ----

    @Test
    fun `a well-formed layer constructs with defaults`() {
        val layer = StickerLayer(id = "s1", stickerId = "heart")
        assertEquals(15, layer.sizePct)
        assertEquals(100, layer.opacity)
        assertEquals(0, layer.rotationDeg)
        assertEquals(0.5, layer.centerX, 0.0)
        assertEquals(0.5, layer.centerY, 0.0)
    }

    @Test
    fun `out-of-range values are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "", stickerId = "heart") }
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "s1", stickerId = "") }
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "s1", stickerId = "heart", sizePct = 301) }
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "s1", stickerId = "heart", sizePct = -1) }
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "s1", stickerId = "heart", opacity = 101) }
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "s1", stickerId = "heart", rotationDeg = 181) }
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "s1", stickerId = "heart", rotationDeg = -181) }
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "s1", stickerId = "heart", centerX = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { StickerLayer(id = "s1", stickerId = "heart", centerY = -0.1) }
    }

    @Test
    fun `duplicate layer ids are rejected by the container`() {
        val a = StickerLayer(id = "s1", stickerId = "heart")
        assertThrows(IllegalArgumentException::class.java) { StickerLayers(listOf(a, a)) }
    }

    @Test
    fun `newId walks past the highest numeric suffix`() {
        assertEquals("s1", StickerLayer.newId(emptyList()))
        assertEquals("s4", StickerLayer.newId(listOf("s1", "s3")))
        assertEquals("s1", StickerLayer.newId(listOf("layer")))
        assertEquals("s10", StickerLayer.newId(listOf("s9", "s2")))
    }

    @Test
    fun `the s prefix never collides with the text family's t prefix`() {
        // The families key their newIds independently; the same numeric
        // suffix in both is legal by construction.
        assertEquals("s2", StickerLayer.newId(listOf("t1", "s1")))
        assertEquals("t2", TextLayer.newId(listOf("t1", "s1")))
    }

    // ---- The size box (percent of the min edge, the text em-size convention) ----

    @Test
    fun `sizePxFor is percent of the min edge`() {
        assertEquals(150f, StickerLayout.sizePxFor(15, 1000f), 0f)
        assertEquals(75f, StickerLayout.sizePxFor(15, 500f), 0f) // portrait: min edge 500
        assertEquals(0f, StickerLayout.sizePxFor(0, 1000f), 0f)
        assertEquals(300f, StickerLayout.sizePxFor(300, 100f), 0f)
    }

    // ---- The paint alpha (opacity-only, half-up) ----

    @Test
    fun `alphaFor maps the opacity slider to 0-255 half-up`() {
        fun a(opacity: Int) = StickerLayout.alphaFor(StickerLayer(id = "s1", stickerId = "heart", opacity = opacity))
        assertEquals(255, a(100))
        assertEquals(128, a(50)) // (50*255+50)/100 = 128, not 127
        assertEquals(26, a(10)) // (10*255+50)/100 = 26, not 25
        assertEquals(0, a(0))
    }

    // ---- Placement: anchors, origin, order ----

    @Test
    fun `place maps fraction anchors onto the canvas at the origin offset`() {
        val layers = listOf(
            StickerLayer(id = "s1", stickerId = "heart", sizePct = 20, centerX = 0.25, centerY = 0.75),
        )
        // The export shape: origin 0 over an 800x600 output canvas.
        val export = StickerLayout.place(layers, 0f, 0f, 800f, 600f)
        assertEquals(1, export.size)
        assertEquals(120f, export[0].sizePx, 0f) // 20% of the 600 min edge
        assertEquals(200f, export[0].centerX, 0f) // 0.25 * 800
        assertEquals(450f, export[0].centerY, 0f) // 0.75 * 600

        // The preview shape: the same output canvas carried at an origin
        // inside a larger post-geometry frame --- the offset identity
        // (preview ≡ export by construction, the r34 test's sticker twin).
        val preview = StickerLayout.place(layers, 120f, 40f, 800f, 600f)
        assertEquals(export[0].sizePx, preview[0].sizePx, 0f)
        assertEquals(export[0].centerX + 120f, preview[0].centerX, 0f)
        assertEquals(export[0].centerY + 40f, preview[0].centerY, 0f)
        assertEquals(export[0].alpha, preview[0].alpha)
    }

    @Test
    fun `place keeps list order and carries the verbatim layer`() {
        val layers = listOf(
            StickerLayer(id = "s1", stickerId = "heart"),
            StickerLayer(id = "s2", stickerId = "ghost-import", opacity = 40),
        )
        val placed = StickerLayout.place(layers, 0f, 0f, 1000f, 1000f)
        assertEquals(listOf("s1", "s2"), placed.map { it.layer.id }) // draw order: top LAST
        assertEquals("ghost-import", placed[1].layer.stickerId) // verbatim: resolution is the compositor's
        assertEquals(102, placed[1].alpha) // (40*255+50)/100
    }

    @Test
    fun `place rejects a degenerate canvas`() {
        assertThrows(IllegalArgumentException::class.java) {
            StickerLayout.place(listOf(StickerLayer(id = "s1", stickerId = "heart")), 0f, 0f, 0f, 100f)
        }
    }
}
