package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text-layer model + layout pass: anchor fractions to pixel rects, center rotation, clamping,
 * draw order, opacity, and the preview == export identity.
 */
class TextLayersTest {

    // ---- Model validation (the structured-parse-failure backstop) ----

    @Test
    fun `a well-formed layer constructs with defaults`() {
        val layer = TextLayer(id = "t1", text = "Hello", fontId = "fira-sans")
        assertEquals(20, layer.sizePct)
        assertEquals(0xFFFFFFFFL, layer.colorArgb)
        assertEquals(100, layer.opacity)
        assertEquals(0, layer.rotationDeg)
        assertEquals(0.5, layer.centerX, 0.0)
        assertEquals(0.5, layer.centerY, 0.0)
    }

    @Test
    fun `out-of-range values are rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "", text = "x", fontId = "fira-sans") }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "") }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", sizePct = 301) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", sizePct = -1) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", colorArgb = 0x100000000L) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", colorArgb = -1L) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", opacity = 101) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", rotationDeg = 181) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", rotationDeg = -181) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", centerX = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { TextLayer(id = "t1", text = "x", fontId = "fira-sans", centerY = -0.1) }
    }

    @Test
    fun `a text longer than the cap is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextLayer(id = "t1", text = "x".repeat(TextLayer.MAX_TEXT_LENGTH + 1), fontId = "fira-sans")
        }
    }

    @Test
    fun `duplicate layer ids are rejected by the container`() {
        val a = TextLayer(id = "t1", text = "a", fontId = "fira-sans")
        assertThrows(IllegalArgumentException::class.java) { TextLayers(listOf(a, a)) }
    }

    @Test
    fun `newId walks past the highest numeric suffix`() {
        assertEquals("t1", TextLayer.newId(emptyList()))
        assertEquals("t4", TextLayer.newId(listOf("t1", "t3")))
        assertEquals("t1", TextLayer.newId(listOf("layer")))
        assertEquals("t10", TextLayer.newId(listOf("t9", "t2")))
    }

    // ---- The em size (percent of the canvas min-edge) ----

    @Test
    fun `sizePxFor is percent of the min edge`() {
        assertEquals(200f, TextLayout.sizePxFor(20, 1000f), 0f)
        assertEquals(100f, TextLayout.sizePxFor(20, 500f), 0f) // portrait: min edge 500 -> 20% of 500
        assertEquals(600f, TextLayout.sizePxFor(150, 400f), 0f)
        assertEquals(0f, TextLayout.sizePxFor(0, 1000f), 0f)
        assertEquals(300f, TextLayout.sizePxFor(300, 100f), 0f)
    }

    // ---- The paint alpha (color alpha byte x opacity, half-up) ----

    @Test
    fun `alphaFor multiplies the color alpha by the opacity`() {
        fun a(color: Long, opacity: Int) =
            TextLayout.alphaFor(TextLayer(id = "t", text = "x", fontId = "f", colorArgb = color, opacity = opacity))
        assertEquals(255, a(0xFFFFFFFFL, 100))
        assertEquals(128, a(0xFFFFFFFFL, 50)) // (255*50+50)/100 = 128, not 127
        assertEquals(0, a(0xFFFFFFFFL, 0))
        assertEquals(128, a(0x80FFFFFFL, 100)) // the authored alpha byte rides at full opacity
        assertEquals(0, a(0x00FFFFFFL, 100))
        assertEquals(1, a(0x0AFFFFFFL, 10)) // (10*10+50)/100 = 1
    }

    // ---- place(): anchors, origin, order, degradation ----

    private val bottom = TextLayer(id = "t1", text = "bottom", fontId = "caveat", sizePct = 10)
    private val top = TextLayer(id = "t2", text = "top", fontId = "ghost-font", sizePct = 30)

    @Test
    fun `place maps fractions onto the output canvas at origin zero`() {
        val specs = TextLayout.place(listOf(bottom, top), 0f, 0f, 1000f, 500f)
        assertEquals(2, specs.size)
        // bottom: 10% of min edge 500 = 50px em; center at the anchor.
        assertEquals(500f, specs[0].centerX, 0f)
        assertEquals(250f, specs[0].centerY, 0f)
        assertEquals(50f, specs[0].sizePx, 0f)
        // top: 30% of 500 = 150px em.
        assertEquals(150f, specs[1].sizePx, 0f)
    }

    @Test
    fun `place honours the origin for a crop region inside a larger frame`() {
        // The preview's output canvas: the crop rect (100,50)..(900,450).
        val specs = TextLayout.place(listOf(bottom), 100f, 50f, 800f, 400f)
        assertEquals(100f + 400f, specs[0].centerX, 0f)
        assertEquals(50f + 200f, specs[0].centerY, 0f)
        assertEquals(40f, specs[0].sizePx, 0f) // 10% of min edge 400
    }

    @Test
    fun `the draw order is the list order and the layer rides verbatim`() {
        val specs = TextLayout.place(listOf(bottom, top), 0f, 0f, 100f, 100f)
        assertEquals(listOf("t1", "t2"), specs.map { it.layer.id })
        assertTrue(specs[1].layer === top)
    }

    @Test
    fun `an unresolvable fontId degrades to the default face in the spec`() {
        val specs = TextLayout.place(listOf(top), 0f, 0f, 100f, 100f)
        assertEquals("ghost-font", specs[0].layer.fontId) // verbatim: the recipe is the user's data
        assertEquals(BundledFonts.DEFAULT_ID, specs[0].resolvedFontId) // the render face degrades
    }

    @Test
    fun `the rotation is the layer's own angle, carried in the spec`() {
        val rotated = top.copy(rotationDeg = -45)
        val spec = TextLayout.place(listOf(rotated), 0f, 0f, 100f, 100f).single()
        // The anchor is the rotation CENTER: the spec's center is the anchor regardless of the angle.
        assertEquals(50f, spec.centerX, 0f)
        assertEquals(50f, spec.centerY, 0f)
        assertEquals(-45, spec.layer.rotationDeg)
    }

    @Test
    fun `a non-positive canvas is a programming error, not a silent draw`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextLayout.place(listOf(bottom), 0f, 0f, 0f, 100f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextLayout.place(listOf(bottom), 0f, 0f, 100f, -1f)
        }
    }

    // ---- preview ≡ export identity (the layout level of the contract) ----

    @Test
    fun `a full-canvas crop makes the preview specs equal the export specs`() {
        // Export's bitmap IS the crop (origin 0); preview renders the same crop at offset (x0, y0).
        // Spec centers differ by exactly the offset (same physical point), with identical size, alpha, font.
        val layers = listOf(bottom, top, top.copy(centerX = 0.25, centerY = 0.75, opacity = 40))
        val export = TextLayout.place(layers, 0f, 0f, 800f, 400f)
        val preview = TextLayout.place(layers, 137f, 91f, 800f, 400f)
        assertEquals(export.size, preview.size)
        for (i in layers.indices) {
            assertEquals(export[i].centerX + 137f, preview[i].centerX, 1e-4f)
            assertEquals(export[i].centerY + 91f, preview[i].centerY, 1e-4f)
            assertEquals(export[i].sizePx, preview[i].sizePx, 0f)
            assertEquals(export[i].alpha, preview[i].alpha)
            assertEquals(export[i].resolvedFontId, preview[i].resolvedFontId)
        }
    }
}
