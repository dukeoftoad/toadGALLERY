package io.github.dukeoftoad.gallery.ui.viewer

/**
 * Pure zoom/pan transform state for the viewer's photo pages (issue #50
 * follow-up slice: pinch-zoom, pan and double-tap zoom).
 *
 * Like [ViewerWindow], this is deliberately a plain-JVM class with zero
 * Android and zero Compose imports: every rule that makes the zoom feel
 * gallery-standard --- the [MAX_SCALE] ceiling, the "no pan at 1x" lock, the
 * Fit-letterboxed pan clamp that never lets the photo be dragged off-screen,
 * and the double-tap boost anchored on the tap point --- is pure float math
 * that `ZoomableStateTest` pins on the plain JVM (ADR-0016: logic tests need
 * no emulator, no Robolectric). [ViewerScreen] owns one instance per photo
 * page and mirrors the values into Compose state after every gesture
 * callback, so this class stays framework-free exactly the way the
 * `MediaItem`-free parts of the viewer do.
 *
 * ## Transform model
 *
 * The photo renders through Coil `AsyncImage` with `ContentScale.Fit` inside
 * a full-viewport page, and `Modifier.graphicsLayer` applies [scale] /
 * [offsetX] / [offsetY] with the default `TransformOrigin.Center`: the zoom
 * is applied around the VIEWPORT CENTER while a pinch gesture anchors on its
 * CENTROID. [onTransform] reconciles the two --- it multiplies the current
 * offsets by the effective zoom factor (so an already-panned photo keeps its
 * relative position while zooming) and compensates for the centroid
 * displacement so the content point under the fingers stays under them:
 *
 * `offset' = offset * zoomEffective + (centroid - viewportCenter) * (1 - zoomEffective) + pan`
 *
 * ## Pan clamping (the Fit contract)
 *
 * `ContentScale.Fit` uniformly scales the content by the smaller of the two
 * per-axis viewport/content ratios and centers it, letterboxing the rest.
 * [clampToBounds] recomputes that fitted rectangle (the viewer-side twin of
 * `data/MediaGeometry.kt`, whose helpers serve the staggered grid's CLAMPED
 * cell aspect ratios; the viewer must not clamp a photo's aspect, so the fit
 * math lives here, unclamped). At scale `s` the scaled rectangle may translate
 * at most `((s * fitted) - viewport) / 2` per axis before its edge would
 * cross the viewport edge: a zoomed photo can pan up to its edge but never
 * off-screen, and because a fitted rectangle always fits inside the viewport,
 * both offsets collapse to `0` at [MIN_SCALE] --- zooming back out always
 * re-centers.
 *
 * Unknown content dimensions (MediaStore rows can lack the width/height
 * columns; `0` means unknown, see `MediaItem`) degrade per axis to the
 * viewport's own size: the clamp is then slightly permissive for letterboxed
 * content but the photo still cannot escape the screen, and real photo rows
 * essentially always carry dimensions.
 *
 * Video pages are out of scope by design: the media3 `PlayerView` owns its
 * gestures and its own pinch behavior, so only photo pages hold zoom state.
 */
class ZoomableState {

    /** Current uniform zoom, always within `[MIN_SCALE, MAX_SCALE]`. */
    var scale: Float = MIN_SCALE
        private set

    /** Horizontal translation in pixels; always `0` at [MIN_SCALE]. */
    var offsetX: Float = 0f
        private set

    /** Vertical translation in pixels; always `0` at [MIN_SCALE]. */
    var offsetY: Float = 0f
        private set

    /**
     * Whether the photo is meaningfully zoomed for interaction purposes
     * (past [ZOOMED_EPSILON]). The pager's scroll lock and the single-finger
     * pan both key off this instead of a raw `scale > 1f`: a pinch-out that
     * settles at `1.0000001f` is visually at rest, and a strict compare there
     * would strand the viewer with a locked pager and a photo that cannot
     * visibly move. Both consumers must use the SAME predicate, otherwise a
     * nearly-rest photo could consume drags while the pager stays enabled
     * (starving page swipes).
     */
    val isZoomed: Boolean
        get() = scale > ZOOMED_EPSILON

    /**
     * Applies one pinch/pan increment from `detectPanZoomGestures`
     * (`panX`/`panY` and `centroidX`/`centroidY` in viewport pixels,
     * `zoomFactor` as a multiplicative delta like 1.02f). See the class KDoc
     * for the anchor math; the result is clamped so the scaled, fitted
     * content rectangle never leaves the viewport. A non-finite
     * [zoomFactor] (defensive: a degenerate two-finger spread) is ignored.
     */
    fun onTransform(
        panX: Float,
        panY: Float,
        zoomFactor: Float,
        centroidX: Float,
        centroidY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ) {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        if (!zoomFactor.isFinite()) return
        val newScale = (scale * zoomFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        // When the pinch is clamped at either bound there is no actual zoom,
        // so the centroid compensation must not fire either --- only pan
        // remains. Otherwise zoom by the effective ratio the user got.
        val zoomEffective = if (newScale != scale && scale > 0f) newScale / scale else 1f
        val rawX =
            offsetX * zoomEffective +
                (centroidX - viewportWidth / 2f) * (1f - zoomEffective) +
                panX
        val rawY =
            offsetY * zoomEffective +
                (centroidY - viewportHeight / 2f) * (1f - zoomEffective) +
                panY
        scale = newScale
        offsetX = rawX
        offsetY = rawY
        clampToBounds(viewportWidth, viewportHeight, contentWidth, contentHeight)
    }

    /**
     * Toggles zoom around the tap point (issue #50 follow-up): a clearly
     * zoomed photo (scale above [ZOOMED_EPSILON]) resets to 1x centered;
     * otherwise the photo zooms to exactly [DOUBLE_TAP_BOOST] anchored on
     * [tapX]/[tapY], reusing [onTransform]'s anchor math so the content under
     * the tap stays under the tap, clamped to the content bounds.
     */
    fun doubleTap(
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
        tapX: Float,
        tapY: Float,
    ) {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        if (scale > ZOOMED_EPSILON) {
            reset()
            return
        }
        // Dividing the boost by the current scale lands the state on exactly
        // DOUBLE_TAP_BOOST even from a hair-above-1x starting point.
        onTransform(
            panX = 0f,
            panY = 0f,
            zoomFactor = DOUBLE_TAP_BOOST / scale,
            centroidX = tapX,
            centroidY = tapY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
    }

    /** Back to 1x, centered: the state a freshly opened page starts in. */
    fun reset() {
        scale = MIN_SCALE
        offsetX = 0f
        offsetY = 0f
    }

    /**
     * Clamps the offsets so the scaled, `ContentScale.Fit`-letterboxed
     * content rectangle stays connected to the viewport. See the class KDoc;
     * at [MIN_SCALE] the fitted rectangle always fits, so both offsets go to
     * `0` (zooming out re-centers).
     */
    private fun clampToBounds(
        viewportWidth: Float,
        viewportHeight: Float,
        contentWidth: Float,
        contentHeight: Float,
    ) {
        if (scale <= MIN_SCALE) {
            offsetX = 0f
            offsetY = 0f
            return
        }
        // ContentScale.Fit: uniform scale = min of the per-axis ratios; an
        // unknown (<= 0) dimension degrades to the viewport's own size.
        val fittedContentWidth = if (contentWidth > 0f) contentWidth else viewportWidth
        val fittedContentHeight = if (contentHeight > 0f) contentHeight else viewportHeight
        val fitScale = minOf(
            viewportWidth / fittedContentWidth,
            viewportHeight / fittedContentHeight,
        )
        val maxX = ((fitScale * fittedContentWidth) * scale - viewportWidth) / 2f
        val maxY = ((fitScale * fittedContentHeight) * scale - viewportHeight) / 2f
        offsetX = if (maxX > 0f) offsetX.coerceIn(-maxX, maxX) else 0f
        offsetY = if (maxY > 0f) offsetY.coerceIn(-maxY, maxY) else 0f
    }

    companion object {
        /** The floor: below this the photo is at rest, fitted and centered. */
        const val MIN_SCALE = 1f

        /** The pinch ceiling: 5x is plenty for inspecting photo detail. */
        const val MAX_SCALE = 5f

        /** Where a double-tap lands when zooming in from rest. */
        const val DOUBLE_TAP_BOOST = 2.5f

        /**
         * Scales within `[MIN_SCALE, this]` count as "at rest" for
         * [doubleTap]: float pinch noise around 1x must not turn a
         * zoom-in tap into a reset.
         */
        const val ZOOMED_EPSILON = 1.01f
    }
}
