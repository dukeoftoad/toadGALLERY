package com.toadgallery.editor

import kotlin.math.abs

/**
 * The photo editor's main-canvas zoom math --- pinch to scale between [MIN_SCALE] and
 * [MAX_SCALE], pan while zoomed, double-tap to fit. Pure arithmetic (ADR-0011), JVM-
 * tested in [CanvasZoomMathTest]; the Compose layer only holds two state floats and
 * replays [step]'s answer through a graphicsLayer.
 *
 * Transform contract (Compose graphicsLayer defaults: pivot = center, translation after
 * scale): `screen = center + scale * (content - center) + offset`. A pinch about
 * [centroid] answers the offset that keeps the content point under the fingers (minus
 * the user's own pan), clamped so the content never leaves the frame: at scale 1 the
 * offset is 0, beyond it each axis bounds to (scale-1) * size / 2.
 */
internal object CanvasZoomMath {

    /** The fit pose --- double-tap always returns here. */
    const val MIN_SCALE = 1f

    /** The zoom ceiling: clamped 1x-4x. */
    const val MAX_SCALE = 4f

    /** Clamps a raw pinch scale into [MIN_SCALE], [MAX_SCALE]. */
    fun clampScale(raw: Float): Float = raw.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * Clamps one offset axis for [scale] over a canvas [sizePx] wide/tall: at (or below)
     * the fit pose the only legal offset is zero; beyond it the axis may move by at most
     * the overflow the zoom created. A zero size (unmeasured canvas) clamps to zero.
     */
    fun clampOffset(offset: Float, scale: Float, sizePx: Float): Float {
        if (scale <= MIN_SCALE || sizePx <= 0f) return 0f
        val limit = (scale - 1f) * sizePx / 2f
        return offset.coerceIn(-limit, limit)
    }

    /**
     * One pinch step. [scale]/[offsetX]/[offsetY] are the CURRENT pose;
     * [zoomChange]/[panX]/[panY] the gesture's incremental zoom and pan;
     * [centroidX]/[centroidY] the pinch centroid in canvas coordinates;
     * [widthPx]/[heightPx] the canvas size. Returns
     * `floatArrayOf(newScale, newOffsetX, newOffsetY)` for the caller to store and feed back.
     *
     * Derivation: the content point under the centroid is
     * `content = center + (centroid - offset - center) / scale`; keeping it under the
     * pan-shifted centroid after the scale change gives
     * `offset' = centroid + pan - center - (newScale / scale) * (centroid - offset - center)`.
     * The per-axis clamp wins over the raw answer (dragging into the bound parks the pose).
     */
    fun step(
        scale: Float,
        offsetX: Float,
        offsetY: Float,
        zoomChange: Float,
        panX: Float,
        panY: Float,
        centroidX: Float,
        centroidY: Float,
        widthPx: Float,
        heightPx: Float,
    ): FloatArray {
        val newScale = clampScale(scale * zoomChange)
        val applied = if (scale <= 0f) 1f else newScale / scale
        val centerX = widthPx / 2f
        val centerY = heightPx / 2f
        val relX = centroidX - offsetX - centerX
        val relY = centroidY - offsetY - centerY
        val rawX = centroidX + panX - centerX - applied * relX
        val rawY = centroidY + panY - centerY - applied * relY
        return floatArrayOf(
            newScale,
            clampOffset(rawX, newScale, widthPx),
            clampOffset(rawY, newScale, heightPx),
        )
    }

    /** True when the pose is the fit pose (no zoom, no pan). */
    fun isIdentity(scale: Float, offsetX: Float, offsetY: Float): Boolean =
        scale == MIN_SCALE && abs(offsetX) < 0.01f && abs(offsetY) < 0.01f
}
