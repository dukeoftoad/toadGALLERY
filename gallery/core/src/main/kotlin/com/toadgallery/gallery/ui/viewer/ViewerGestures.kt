package com.toadgallery.gallery.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged

/**
 * The viewer's pinch-zoom/pan detector (issue #50 follow-up slice),
 * file-private to the viewer package.
 *
 * ## Why not `detectTransformGestures`
 *
 * That stock detector engages on single-finger drags too (a one-finger pan
 * crosses its touch slop) and then consumes every position change. PhotoPage
 * lives INSIDE the viewer's `HorizontalPager`, and a child that consumes
 * drags starves the pager's scrollable --- swiping between photos, the
 * viewer's primary navigation, would silently stop working at 1x. This
 * detector splits behavior by pointer count instead, which is exactly what a
 * gallery viewer needs:
 *
 *  - two or more pointers: a pinch. Zoom (spread ratio) and pan (midpoint
 *    delta) are computed between consecutive events of the SAME pointer
 *    pair and consumed, so pinching works at ANY zoom level --- including
 *    zooming in from 1x. The baseline is re-armed whenever the tracked pair
 *    changes, so a third finger joining or the tracked finger lifting
 *    cannot spike the zoom ratio;
 *  - one pointer while [canPan] reports the page zoomed: a pan. It engages
 *    only after the platform touch slop so plain taps keep flowing to the
 *    sibling `detectTapGestures` (chrome toggle + double-tap zoom), then
 *    consumes like any drag;
 *  - one pointer at 1x: nothing is consumed or emitted, so the pager
 *    receives the drag and swipes between pages exactly as before this
 *    slice.
 *
 * The consumption split is belt-and-braces with the pager lock: while a photo
 * is zoomed, `ViewerScreen` also sets `userScrollEnabled = false` on the
 * pager (the documented v1 trade-off), so a zoomed photo's horizontal drags
 * can never start a page swipe even at the pan-clamp edges.
 *
 * Video pages never install this detector: media3's `PlayerView` owns its
 * gestures.
 */
internal suspend fun PointerInputScope.detectPanZoomGestures(
    canPan: () -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var mode = GestureMode.Idle
        var slopPan = Offset.Zero
        var previousCentroid: Offset? = null
        var previousSpread = 0f
        var pinchFirst: PointerId? = null
        var pinchSecond: PointerId? = null
        while (true) {
            val event = awaitPointerEvent()
            // Another handler claimed the stream (e.g. the pager started
            // dragging while we were idle): end this gesture, don't fight it.
            if (event.changes.any { change -> change.isConsumed }) break
            val pressed = event.changes.filter { change -> change.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val first = pressed[0]
                val second = pressed[1]
                val centroid = midpointOf(first, second)
                val spread = (first.position - second.position).getDistance()
                val centroidBefore = previousCentroid
                // Only compare against a baseline measured on the SAME two
                // pointers: a different pair (finger swap, one of the tracked
                // fingers lifted while others remain) would produce a
                // meaningless spread ratio for one event. Re-arm instead.
                if (
                    centroidBefore != null &&
                    previousSpread > 0f &&
                    pinchFirst == first.id &&
                    pinchSecond == second.id
                ) {
                    mode = GestureMode.Pinch
                    onGesture(
                        centroid,
                        centroid - centroidBefore,
                        spread / previousSpread,
                    )
                    pressed.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
                previousCentroid = centroid
                previousSpread = spread
                pinchFirst = first.id
                pinchSecond = second.id
                slopPan = Offset.Zero
            } else {
                val change = pressed[0]
                when {
                    mode == GestureMode.Pan -> {
                        if (change.positionChanged()) {
                            onGesture(change.position, change.positionChange(), 1f)
                            change.consume()
                        }
                    }
                    mode == GestureMode.Pinch && canPan() -> {
                        // One finger left a pinch while still zoomed: continue
                        // as a pan without crossing touch slop again.
                        mode = GestureMode.Pan
                        if (change.positionChanged()) {
                            onGesture(change.position, change.positionChange(), 1f)
                            change.consume()
                        }
                    }
                    canPan() -> {
                        slopPan += change.positionChange()
                        if (slopPan.getDistance() > touchSlop) {
                            mode = GestureMode.Pan
                            onGesture(change.position, slopPan, 1f)
                            change.consume()
                            slopPan = Offset.Zero
                        }
                    }
                    else -> {
                        // Single finger at 1x: the pager owns the drag.
                    }
                }
                previousCentroid = null
                previousSpread = 0f
                pinchFirst = null
                pinchSecond = null
            }
        }
    }
}

/** What the in-flight gesture is doing; drives the consumption rules above. */
private enum class GestureMode {
    /** Fingers down, nothing engaged yet. */
    Idle,

    /** A single-finger pan past touch slop. */
    Pan,

    /** A two-plus-finger pinch. */
    Pinch,
}

/** The midpoint of the two pointers a pinch is measured between. */
private fun midpointOf(a: PointerInputChange, b: PointerInputChange): Offset =
    Offset(
        x = (a.position.x + b.position.x) / 2f,
        y = (a.position.y + b.position.y) / 2f,
    )
