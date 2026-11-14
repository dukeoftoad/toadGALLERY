package com.toadgallery.editor

// The timeline-viewport math (ADR-0011 split): Float arithmetic, zero Compose/Android
// imports, JVM-pinned; composable is a thin shell. pxPerMs = viewportWidth/durationMs * zoom.

/** The zoomable timeline's viewport state: which zoom, which instant centered. */
internal data class TimelineViewport(
    /** The px-per-ms multiplier, [LilypadTimelineMath.ZOOM_MIN]..[LilypadTimelineMath.ZOOM_MAX]; 1f = the whole timeline fits. */
    val zoom: Float,
    /** The timeline instant at the viewport's horizontal center (ms). */
    val centerMs: Float,
)

/**
 * The pinch-zoom / pan geometry (pure). Every function is total: degenerate durations
 * (<= 0) pin to safe identities instead of throwing, and a NaN zoom clamps to [ZOOM_MIN].
 */
internal object LilypadTimelineMath {

    /** The fit-all zoom: the whole timeline in the viewport. */
    const val ZOOM_MIN: Float = 1f

    /**
     * The zoom ceiling: enough for frame-adjacent work without letting a stray pinch
     * turn the strip into a single-frame microscope.
     */
    const val ZOOM_MAX: Float = 16f

    /** The zoom detents a released pinch snaps to (the dial's detent lesson). */
    val ZOOM_DETENTS: FloatArray = floatArrayOf(1f, 2f, 4f, 8f, 16f)

    /** A zoom within this ratio of a detent snaps to it on release. */
    const val DETENT_SNAP_RATIO: Float = 0.12f

    /** The strip's pixels per millisecond at a zoom (0f on a degenerate duration). */
    fun pxPerMs(durationMs: Float, viewportWidthPx: Float, zoom: Float): Float {
        if (durationMs <= 0f) return 0f
        return viewportWidthPx / durationMs * zoom
    }

    /** A NaN zoom pins to fit (the volume-NaN discipline); everything else clamps. */
    fun clampZoom(zoom: Float): Float {
        if (zoom.isNaN()) return ZOOM_MIN
        return zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    /**
     * The half-window in ms: how far the viewport reaches from its center. At zoom 1 it
     * is half the timeline; it shrinks linearly as the zoom grows.
     */
    fun halfSpanMs(durationMs: Float, viewportWidthPx: Float, zoom: Float): Float {
        if (durationMs <= 0f) return 0f
        return durationMs / (2f * zoom)
    }

    /**
     * Keep the window inside the timeline: at fit the center pins to the middle; zoomed
     * in, it clamps so no window edge may leave the `[0, durationMs]` span. A degenerate
     * duration pins to `durationMs / 2`.
     */
    fun clampCenter(centerMs: Float, durationMs: Float, viewportWidthPx: Float, zoom: Float): Float {
        if (durationMs <= 0f) return durationMs / 2f
        val half = halfSpanMs(durationMs, viewportWidthPx, zoom)
        if (half * 2f >= durationMs) return durationMs / 2f
        return centerMs.coerceIn(half, durationMs - half)
    }

    /**
     * THE anchored zoom: change the zoom while `focalMs` stays at the same viewport
     * pixel (the pinch centroid): `center2 = focal - (focal - center1) * z1 / z2`. The
     * clamped center can still shift the focal when the zoom runs against a bound.
     */
    fun anchoredZoom(
        viewport: TimelineViewport,
        newZoom: Float,
        focalMs: Float,
        durationMs: Float,
        viewportWidthPx: Float,
    ): TimelineViewport {
        val z2 = clampZoom(newZoom)
        val z1 = if (viewport.zoom.isNaN()) ZOOM_MIN else viewport.zoom
        val rawCenter = if (z2 == z1) {
            viewport.centerMs
        } else {
            focalMs - (focalMs - viewport.centerMs) * z1 / z2
        }
        return TimelineViewport(
            zoom = z2,
            centerMs = clampCenter(rawCenter, durationMs, viewportWidthPx, z2),
        )
    }

    /**
     * Pan by pixels: dragging the content `panPx` right moves the window the OTHER way
     * in time (`center -= panPx / pxPerMs` at the current zoom).
     */
    fun pannedCenter(viewport: TimelineViewport, panPx: Float, durationMs: Float, viewportWidthPx: Float): Float {
        val ppm = pxPerMs(durationMs, viewportWidthPx, viewport.zoom)
        if (ppm <= 0f) return clampCenter(viewport.centerMs, durationMs, viewportWidthPx, viewport.zoom)
        return clampCenter(viewport.centerMs - panPx / ppm, durationMs, viewportWidthPx, viewport.zoom)
    }

    /** The timeline instant at a viewport x (total; callers clamp for seeking). */
    fun msAt(xPx: Float, viewport: TimelineViewport, durationMs: Float, viewportWidthPx: Float): Float {
        val ppm = pxPerMs(durationMs, viewportWidthPx, viewport.zoom)
        return viewport.centerMs + (xPx - viewportWidthPx / 2f) / ppm
    }

    /** The viewport x of a timeline instant (the inverse of [msAt]). */
    fun pxAt(ms: Float, viewport: TimelineViewport, durationMs: Float, viewportWidthPx: Float): Float {
        val ppm = pxPerMs(durationMs, viewportWidthPx, viewport.zoom)
        return (ms - viewport.centerMs) * ppm + viewportWidthPx / 2f
    }

    /**
     * A released pinch snaps onto a detent when it landed within [DETENT_SNAP_RATIO] of
     * one (relative to the detent); otherwise the zoom stays clamped where the fingers left it.
     */
    fun snapZoomToDetent(zoom: Float): Float {
        val z = clampZoom(zoom)
        for (detent in ZOOM_DETENTS) {
            if (kotlin.math.abs(z - detent) <= detent * DETENT_SNAP_RATIO) return detent
        }
        return z
    }

    /**
     * The zoom chip's label: one decimal, `x`-suffixed, root-locale digits
     * (`1.0x` ... `16.0x`).
     */
    fun zoomLabel(zoom: Float): String =
        String.format(java.util.Locale.ROOT, "%.1fx", clampZoom(zoom))
}
