package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The r69 viewport-geometry pins: the anchored zoom's focal invariant, the
 * center clamps, the px/ms round trip, the detent snap, and the defensive
 * identities (NaN, degenerate durations).
 */
private const val DURATION = 60_000f
private const val WIDTH = 1_000f

class LilypadTimelineMathTest {

    private fun viewport(zoom: Float, centerMs: Float = DURATION / 2f) =
        TimelineViewport(LilypadTimelineMath.clampZoom(zoom), centerMs)

    // ------------------------------------------------------------------ //
    // the fit invariant
    // ------------------------------------------------------------------ //

    @Test
    fun `zoom one fits the whole timeline with its center pinned`() {
        val v = viewport(1f, 30_000f)
        assertEquals(30_000f, LilypadTimelineMath.clampCenter(v.centerMs, DURATION, WIDTH, 1f), 1e-3f)
        // The timeline's ends land exactly on the viewport's ends.
        assertEquals(0f, LilypadTimelineMath.pxAt(0f, viewport(1f), DURATION, WIDTH), 1e-3f)
        assertEquals(WIDTH, LilypadTimelineMath.pxAt(DURATION, viewport(1f), DURATION, WIDTH), 1e-3f)
    }

    @Test
    fun `clamping a fit zoom's center never moves it off the middle`() {
        assertEquals(30_000f, LilypadTimelineMath.clampCenter(0f, DURATION, WIDTH, 1f), 1e-3f)
        assertEquals(30_000f, LilypadTimelineMath.clampCenter(60_000f, DURATION, WIDTH, 1f), 1e-3f)
    }

    // ------------------------------------------------------------------ //
    // the anchored zoom
    // ------------------------------------------------------------------ //

    @Test
    fun `an anchored zoom keeps the focal instant at the same pixel`() {
        val before = viewport(1f)
        val focal = 42_000f
        val focalPxBefore = LilypadTimelineMath.pxAt(focal, before, DURATION, WIDTH)
        val after = LilypadTimelineMath.anchoredZoom(before, 4f, focal, DURATION, WIDTH)
        val focalPxAfter = LilypadTimelineMath.pxAt(focal, after, DURATION, WIDTH)
        assertEquals(focalPxBefore, focalPxAfter, 1e-2f)
    }

    @Test
    fun `an anchored zoom out and back restores the viewport`() {
        val start = viewport(2f, 20_000f)
        val focal = 45_000f
        val zoomed = LilypadTimelineMath.anchoredZoom(start, 8f, focal, DURATION, WIDTH)
        val back = LilypadTimelineMath.anchoredZoom(zoomed, 2f, focal, DURATION, WIDTH)
        assertEquals(start.zoom, back.zoom, 1e-4f)
        assertEquals(start.centerMs, back.centerMs, 1e-1f)
    }

    @Test
    fun `a zoom past the ceiling clamps to the ceiling`() {
        val out = LilypadTimelineMath.anchoredZoom(viewport(8f), 99f, 30_000f, DURATION, WIDTH)
        assertEquals(LilypadTimelineMath.ZOOM_MAX, out.zoom, 1e-4f)
    }

    @Test
    fun `a zoom below fit clamps to fit`() {
        val out = LilypadTimelineMath.anchoredZoom(viewport(4f), 0.2f, 30_000f, DURATION, WIDTH)
        assertEquals(LilypadTimelineMath.ZOOM_MIN, out.zoom, 1e-4f)
        assertEquals(30_000f, out.centerMs, 1e-1f)
    }

    // ------------------------------------------------------------------ //
    // the center clamps
    // ------------------------------------------------------------------ //

    @Test
    fun `a zoomed window may not pan past either end`() {
        // At 4x the half window is 7 500 ms, so the center is pinned
        // inside [7 500, 52 500].
        assertEquals(7_500f, LilypadTimelineMath.clampCenter(-5_000f, DURATION, WIDTH, 4f), 1e-3f)
        assertEquals(52_500f, LilypadTimelineMath.clampCenter(70_000f, DURATION, WIDTH, 4f), 1e-3f)
        assertEquals(
            30_000f,
            LilypadTimelineMath.clampCenter(30_000f, DURATION, WIDTH, 4f),
            1e-3f,
        )
    }

    @Test
    fun `panning moves the window against the finger and clamps`() {
        val v = viewport(4f, 30_000f)
        // 100 px at 4x is 1 500 ms: dragging right shows earlier content.
        assertEquals(28_500f, LilypadTimelineMath.pannedCenter(v, 100f, DURATION, WIDTH), 1e-3f)
        assertEquals(31_500f, LilypadTimelineMath.pannedCenter(v, -100f, DURATION, WIDTH), 1e-3f)
        // A huge pan stops at the window bounds, never beyond.
        assertEquals(7_500f, LilypadTimelineMath.pannedCenter(v, 1_000_000f, DURATION, WIDTH), 1e-3f)
        assertEquals(52_500f, LilypadTimelineMath.pannedCenter(v, -1_000_000f, DURATION, WIDTH), 1e-3f)
    }

    // ------------------------------------------------------------------ //
    // the px/ms round trip
    // ------------------------------------------------------------------ //

    @Test
    fun `px and ms invert each other at every zoom`() {
        for (zoom in listOf(1f, 1.7f, 2f, 3.3f, 8f, 16f)) {
            val v = viewport(zoom, 21_000f)
            for (ms in listOf(0f, 5_000f, 21_000f, 33_500.5f, 60_000f)) {
                val px = LilypadTimelineMath.pxAt(ms, v, DURATION, WIDTH)
                val back = LilypadTimelineMath.msAt(px, v, DURATION, WIDTH)
                assertEquals("zoom $zoom ms $ms", ms, back, 1e-2f)
            }
        }
    }

    // ------------------------------------------------------------------ //
    // the detent snap
    // ------------------------------------------------------------------ //

    @Test
    fun `a released pinch near a detent snaps to it`() {
        assertEquals(2f, LilypadTimelineMath.snapZoomToDetent(1.9f), 1e-4f)
        assertEquals(4f, LilypadTimelineMath.snapZoomToDetent(3.9f), 1e-4f)
        assertEquals(16f, LilypadTimelineMath.snapZoomToDetent(15.6f), 1e-4f)
        assertEquals(1f, LilypadTimelineMath.snapZoomToDetent(1.05f), 1e-4f)
    }

    @Test
    fun `a zoom between detents stays where the fingers left it`() {
        assertEquals(3f, LilypadTimelineMath.snapZoomToDetent(3f), 1e-4f)
        assertEquals(5.5f, LilypadTimelineMath.snapZoomToDetent(5.5f), 1e-4f)
    }

    @Test
    fun `an out-of-bounds zoom clamps before the snap decides`() {
        assertEquals(16f, LilypadTimelineMath.snapZoomToDetent(40f), 1e-4f)
        assertEquals(1f, LilypadTimelineMath.snapZoomToDetent(0.3f), 1e-4f)
    }

    // ------------------------------------------------------------------ //
    // the defensive identities
    // ------------------------------------------------------------------ //

    @Test
    fun `a NaN zoom pins to fit`() {
        assertEquals(LilypadTimelineMath.ZOOM_MIN, LilypadTimelineMath.clampZoom(Float.NaN), 1e-4f)
        val out = LilypadTimelineMath.anchoredZoom(
            viewport(2f), Float.NaN, 30_000f, DURATION, WIDTH,
        )
        assertEquals(LilypadTimelineMath.ZOOM_MIN, out.zoom, 1e-4f)
    }

    @Test
    fun `a degenerate duration collapses to safe identities`() {
        assertEquals(0f, LilypadTimelineMath.pxPerMs(0f, WIDTH, 4f), 1e-6f)
        assertEquals(0f, LilypadTimelineMath.pxPerMs(-10f, WIDTH, 4f), 1e-6f)
        assertEquals(0f, LilypadTimelineMath.clampCenter(500f, 0f, WIDTH, 4f), 1e-6f)
        val out = LilypadTimelineMath.anchoredZoom(viewport(1f), 8f, 100f, 0f, WIDTH)
        assertEquals(8f, out.zoom, 1e-4f)
    }

    // ------------------------------------------------------------------ //
    // the chip label
    // ------------------------------------------------------------------ //

    @Test
    fun `the zoom label is one decimal with an x suffix`() {
        assertEquals("1.0x", LilypadTimelineMath.zoomLabel(1f))
        assertEquals("2.0x", LilypadTimelineMath.zoomLabel(2f))
        assertEquals("3.4x", LilypadTimelineMath.zoomLabel(3.4f))
        assertEquals("16.0x", LilypadTimelineMath.zoomLabel(99f))
    }
}
