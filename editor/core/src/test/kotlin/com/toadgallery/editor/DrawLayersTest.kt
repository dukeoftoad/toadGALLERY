package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r36 stroke-layer model + the pure layout pass (the contract's
 * `DrawLayersTest`): the brush width, the r34 text alpha math, the
 * origin-offset identity, list order, single-point placement --- and
 * the two pure algorithms the round owns: the commit-time stride
 * thinning and the Chaikin corner-cutting (exact outputs).
 */
class DrawLayersTest {

    // ---- Model validation ----

    @Test
    fun `a well-formed layer constructs with defaults`() {
        val layer = DrawLayer(
            id = "d1",
            points = listOf(DrawLayer.Point(0.5, 0.5), DrawLayer.Point(0.6, 0.55)),
        )
        assertEquals(0xFFFFFFFFL, layer.colorArgb)
        assertEquals(3, layer.widthPct)
        assertEquals(100, layer.opacity)
        assertEquals(50, layer.smoothingPct)
    }

    @Test
    fun `out-of-range values are rejected at construction`() {
        val two = listOf(DrawLayer.Point(0.0, 0.0), DrawLayer.Point(1.0, 1.0))
        assertThrows(IllegalArgumentException::class.java) { DrawLayer(id = "", points = two) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer(id = "d1", points = two, colorArgb = 0x100000000L) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer(id = "d1", points = two, widthPct = 0) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer(id = "d1", points = two, widthPct = 101) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer(id = "d1", points = two, opacity = 101) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer(id = "d1", points = two, smoothingPct = -1) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer(id = "d1", points = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer(id = "d1", points = List(DrawLayer.MAX_POINTS + 1) { DrawLayer.Point(0.5, 0.5) }) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer.Point(1.5, 0.5) }
        assertThrows(IllegalArgumentException::class.java) { DrawLayer.Point(0.5, -0.1) }
    }

    @Test
    fun `duplicate layer ids are rejected by the container`() {
        val a = DrawLayer(id = "d1", points = listOf(DrawLayer.Point(0.5, 0.5)))
        assertThrows(IllegalArgumentException::class.java) { DrawLayers(listOf(a, a)) }
    }

    @Test
    fun `newId walks past the highest numeric suffix`() {
        assertEquals("d1", DrawLayer.newId(emptyList()))
        assertEquals("d4", DrawLayer.newId(listOf("d1", "d3")))
        assertEquals("d1", DrawLayer.newId(listOf("draft"))) // the transient draft id never counts
        assertEquals("d2", DrawLayer.newId(listOf("t1", "s1", "d1"))) // the families stay collision-free
    }

    // ---- The thinning stride (commit-time) ----

    @Test
    fun `thin keeps a short list verbatim`() {
        val pts = listOf(DrawLayer.Point(0.1, 0.1), DrawLayer.Point(0.2, 0.2))
        assertEquals(pts, DrawLayer.thin(pts, 512))
    }

    @Test
    fun `thin strides a long list with first and last always kept`() {
        // n=10, cap=3 -> stride=ceil(10/3)=4: indices 0, 4, 8, then last (9);
        // the x coordinates here are index/10, so 0.0, 0.4, 0.8, 0.9.
        val pts = (0 until 10).map { DrawLayer.Point(it / 10.0, it / 20.0) }
        val thinned = DrawLayer.thin(pts, 3)
        assertEquals(
            listOf(0.0, 0.4, 0.8, 0.9),
            thinned.map { it.x },
        )
        assertEquals(pts[0], thinned.first())
        assertEquals(pts.last(), thinned.last())
        assertTrue(thinned.size <= 3 + 1)
    }

    @Test
    fun `one over the cap thins to about half`() {
        val pts = (0 until 513).map { DrawLayer.Point(it / 513.0, 0.5) }
        val thinned = DrawLayer.thin(pts, 512)
        // stride=ceil(513/512)=2 -> indices 0,2,4,...,510 (256), then 512: 257 points.
        assertEquals(257, thinned.size)
        assertTrue(thinned.size <= DrawLayer.MAX_POINTS + 1)
        assertEquals(0.0, thinned.first().x, 0.0)
        assertEquals(512 / 513.0, thinned.last().x, 0.0)
    }

    // ---- The Chaikin passes ----

    @Test
    fun `chaikinPasses maps the slider to 0-4`() {
        assertEquals(0, DrawLayout.chaikinPasses(0))
        assertEquals(0, DrawLayout.chaikinPasses(12)) // round(0.48) = 0
        assertEquals(1, DrawLayout.chaikinPasses(13)) // round(0.52) = 1
        assertEquals(2, DrawLayout.chaikinPasses(50))
        assertEquals(4, DrawLayout.chaikinPasses(100))
    }

    @Test
    fun `smoothPolyline one pass is the exact corner cut`() {
        val input = listOf(0f to 0f, 1f to 0f, 1f to 1f)
        val out = DrawLayout.smoothPolyline(input, 1)
        assertEquals(
            listOf(
                0f to 0f, // endpoint preserved
                0.25f to 0f, // edge 1 Q = 0.75a + 0.25b
                0.75f to 0f, // edge 1 R = 0.25a + 0.75b
                1f to 0.25f, // edge 2 Q
                1f to 0.75f, // edge 2 R
                1f to 1f, // endpoint preserved
            ),
            out,
        )
    }

    @Test
    fun `smoothPolyline at zero passes and short lists is the identity`() {
        val input = listOf(0f to 0f, 1f to 0f, 1f to 1f)
        assertEquals(input, DrawLayout.smoothPolyline(input, 0))
        val two = listOf(0f to 0f, 1f to 1f)
        assertEquals(two, DrawLayout.smoothPolyline(two, 4)) // < 3 points: nothing to cut
    }

    @Test
    fun `smoothPolyline endpoints survive every pass count`() {
        val input = (0 until 9).map { it.toFloat() to (it * it).toFloat() }
        for (passes in 1..4) {
            val out = DrawLayout.smoothPolyline(input, passes)
            assertEquals(input.first(), out.first())
            assertEquals(input.last(), out.last())
            assertTrue(out.size >= input.size) // cutting ADDS points on the interior
        }
    }

    // ---- The layout pass ----

    @Test
    fun `widthPxFor is percent of the min edge`() {
        assertEquals(30f, DrawLayout.widthPxFor(3, 1000f), 0f)
        assertEquals(15f, DrawLayout.widthPxFor(3, 500f), 0f)
        assertEquals(100f, DrawLayout.widthPxFor(100, 100f), 0f)
    }

    @Test
    fun `alphaFor is the r34 text math`() {
        fun a(color: Long, opacity: Int) =
            DrawLayout.alphaFor(
                DrawLayer(
                    id = "d1",
                    points = listOf(DrawLayer.Point(0.5, 0.5)),
                    colorArgb = color,
                    opacity = opacity,
                ),
            )
        assertEquals(255, a(0xFFF44336L, 100))
        assertEquals(128, a(0xFFFFFFFFL, 50)) // (255*50+50)/100 = 128
        assertEquals(26, a(0xFF111111L, 10)) // (255*10+50)/100 = 26
    }

    @Test
    fun `place maps points at the origin offset and keeps list order`() {
        val layers = listOf(
            DrawLayer(
                id = "d1",
                widthPct = 5,
                points = listOf(DrawLayer.Point(0.0, 0.0), DrawLayer.Point(1.0, 1.0)),
            ),
            DrawLayer(id = "d2", widthPct = 5, points = listOf(DrawLayer.Point(0.5, 0.5))),
        )
        val export = DrawLayout.place(layers, 0f, 0f, 800f, 600f)
        assertEquals(listOf("d1", "d2"), export.map { it.layer.id }) // top LAST
        assertEquals(30f, export[0].widthPx, 0f) // 5% of the 600 min edge
        assertEquals(0f to 0f, export[0].pointsPx.first())
        assertEquals(800f to 600f, export[0].pointsPx.last())

        // The preview shape: the offset identity (preview ≡ export).
        val preview = DrawLayout.place(layers, 100f, 40f, 800f, 600f)
        assertEquals(export[0].widthPx, preview[0].widthPx, 0f)
        assertEquals(export[0].alpha, preview[0].alpha)
        assertEquals(100f to 40f, preview[0].pointsPx.first())
        assertEquals(900f to 640f, preview[0].pointsPx.last())
    }

    @Test
    fun `a single-point layer places as one mapped point -- the dot case`() {
        val layers = listOf(DrawLayer(id = "d1", points = listOf(DrawLayer.Point(0.25, 0.75))))
        val placed = DrawLayout.place(layers, 0f, 0f, 400f, 200f)
        assertEquals(1, placed[0].pointsPx.size)
        assertEquals(100f to 150f, placed[0].pointsPx[0])
    }

    @Test
    fun `place rejects a degenerate canvas`() {
        assertThrows(IllegalArgumentException::class.java) {
            DrawLayout.place(listOf(DrawLayer(id = "d1", points = listOf(DrawLayer.Point(0.5, 0.5)))), 0f, 0f, 0f, 100f)
        }
    }
}
