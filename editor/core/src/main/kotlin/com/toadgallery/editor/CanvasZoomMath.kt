package com.toadgallery.editor

import kotlin.math.abs

/**
 * F-72 (the r63 proposal's deferred canvas craft, landed r65): the photo
 * editor's MAIN-canvas zoom math --- pinch to scale the preview between
 * [MIN_SCALE] and [MAX_SCALE] (the proposal's "clamped 1x-4x"), pan while
 * zoomed, double-tap to fit (back to 1x, re-centered). Pure arithmetic on
 * purpose (ADR-0011): the geometry the gestures drive is JVM-tested in
 * [CanvasZoomMathTest], and the Compose layer (EditActivity) only holds
 * the two state floats and replays [step]'s answer through a graphicsLayer.
 *
 * The transform contract matches Compose's graphicsLayer defaults (pivot =
 * the layer's center, translation applied after scale):
 *
 *     screen = center + scale * (content - center) + offset
 *
 * so a pinch about [centroid] must answer an offset that keeps the content
 * point under the fingers under the fingers (minus the user's own pan),
 * then clamps so the content can never leave the frame: at scale 1 the
 * offset is forced to zero (the fit pose), and beyond that each axis is
 * bounded to the overflow the zoom creates ((scale-1) * size / 2).
 */
internal object CanvasZoomMath {

    /** The fit pose --- double-tap always returns here. */
    const val MIN_SCALE = 1f

    /** The proposal's zoom ceiling (F-72: "clamped 1x-4x"). */
    const val MAX_SCALE = 4f

    /** Clamps a raw pinch scale into [MIN_SCALE], [MAX_SCALE]. */
    fun clampScale(raw: Float): Float = raw.coerceIn(MIN_SCALE, MAX_SCALE)

    /**
     * Clamps one offset axis for [scale] over a canvas [sizePx] wide/tall:
     * at (or below) the fit pose the only legal offset is zero; beyond it
     * the axis may move by at most the overflow the zoom created. A zero
     * size (the first frame, before the canvas reports its size) clamps to
     * zero --- no pan can be meaningful on a not-yet-measured canvas.
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
     * `floatArrayOf(newScale, newOffsetX, newOffsetY)` --- the values the
     * caller stores and feeds back on the next event.
     *
     * Derivation (from the transform contract above): the content point
     * currently under the centroid is
     * `content = center + (centroid - offset - center) / scale`; keeping it
     * under the pan-shifted centroid after the scale change gives
     * `offset' = centroid + pan - center - (newScale / scale) *
     * (centroid - offset - center)`. The per-axis clamp then wins over the
     * raw answer (dragging into the bound parks the pose there instead of
     * rubber-banding).
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
