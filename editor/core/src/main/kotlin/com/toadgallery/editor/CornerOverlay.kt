package com.toadgallery.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

// The perspective corner overlay: displaced quad outline + handles over the source fit box,
// with the corner drag gesture (draft per tick, snap + ONE commit on release).

/**
 * [quad] is the committed-or-drafted quad to draw; [onDraftChange] receives every clamped
 * drag tick; [onCommit] receives the released, snapped quad (ONE commit per gesture).
 * [sourceWidth]/[sourceHeight] are the preview grid's dimensions; null-size renders nothing.
 */
@Composable
internal fun CornerOverlay(
    quad: PerspectiveQuad,
    sourceWidth: Int,
    sourceHeight: Int,
    exporting: Boolean,
    onDraftChange: (PerspectiveQuad) -> Unit,
    onCommit: (PerspectiveQuad) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sourceWidth < 1 || sourceHeight < 1) return
    val haptics = LocalHapticFeedback.current

    // Dragged corner (-1 = none); a drag leaving the identity deadband fires one ZERO_TICK.
    var draggingCorner by remember { mutableStateOf(-1) }

    // Fresh-at-read refs for the gesture coroutine (the deck's pattern).
    val currentQuad by rememberUpdatedState(quad)
    val currentWidth by rememberUpdatedState(sourceWidth)
    val currentHeight by rememberUpdatedState(sourceHeight)
    val currentOnChange by rememberUpdatedState(onDraftChange)
    val currentOnCommit by rememberUpdatedState(onCommit)

    // A11y strings resolved outside semantics{} (stringResource is composable; semantics{} is not).
    val overlayName = stringResource(R.string.editor_r72_corner_overlay_a11y)
    val displaced = listOf(
        quad.tlX != 0.0 || quad.tlY != 0.0,
        quad.trX != 0.0 || quad.trY != 0.0,
        quad.brX != 0.0 || quad.brY != 0.0,
        quad.blX != 0.0 || quad.blY != 0.0,
    ).count { it }
    val overlayState = stringResource(R.string.editor_r72_corner_state, displaced)

    val handleTint = Color.White
    val emberTint = Color(0xFFFF6B45)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics {
                contentDescription = overlayName
                stateDescription = overlayState
            }
            .pointerInput(exporting) {
                if (exporting) return@pointerInput
                val grabPx = LilypadCornerMath.HIT_RADIUS_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // The fit mapping: scale + offset of the source inside this box.
                    val scale = minOf(
                        size.width.toFloat() / currentWidth,
                        size.height.toFloat() / currentHeight,
                    )
                    if (scale <= 0f) return@awaitEachGesture
                    val offX = (size.width - currentWidth * scale) / 2f
                    val offY = (size.height - currentHeight * scale) / 2f
                    fun toSource(position: Offset): Pair<Double, Double> = Pair(
                        ((position.x - offX) / scale).toDouble(),
                        ((position.y - offY) / scale).toDouble(),
                    )
                    fun cornerAt(position: Offset): Int {
                        val (x, y) = toSource(position)
                        val corners = LilypadCornerMath.quadCorners(
                            currentQuad, currentWidth, currentHeight,
                        )
                        return LilypadCornerMath.hitCorner(corners, x, y, (grabPx / scale).toDouble())
                    }

                    val index = cornerAt(down.position)
                    if (index < 0) return@awaitEachGesture
                    val corner = QuadCorner.entries[index]
                    draggingCorner = index
                    down.consume()
                    var snappedTickArmed = true
                    // The gesture's own last-drafted quad; the release snaps THIS (a
                    // recomposition between tick and release must not change the outcome).
                    var liveQuad = currentQuad
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) break
                            val (x, y) = toSource(change.position)
                            liveQuad = LilypadCornerMath.dragCornerTo(
                                liveQuad, corner, x, y, currentWidth, currentHeight,
                            )
                            currentOnChange(liveQuad)
                            // ZERO_TICK on leaving the identity neighborhood (armed at down,
                            // rearmed after a tick).
                            val dx = when (corner) {
                                QuadCorner.TopLeft -> liveQuad.tlX
                                QuadCorner.TopRight -> liveQuad.trX
                                QuadCorner.BottomRight -> liveQuad.brX
                                QuadCorner.BottomLeft -> liveQuad.blX
                            }
                            val dy = when (corner) {
                                QuadCorner.TopLeft -> liveQuad.tlY
                                QuadCorner.TopRight -> liveQuad.trY
                                QuadCorner.BottomRight -> liveQuad.brY
                                QuadCorner.BottomLeft -> liveQuad.blY
                            }
                            val nearHome =
                                kotlin.math.abs(dx) <= currentWidth / 4.0 * LilypadCornerMath.SNAP_DEADBAND &&
                                    kotlin.math.abs(dy) <= currentHeight / 4.0 * LilypadCornerMath.SNAP_DEADBAND
                            if (!nearHome && snappedTickArmed) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                snappedTickArmed = false
                            }
                            change.consume()
                        }
                    } finally {
                        // Release: snap, COMMIT_SOFT pulse, ONE commit; the finally covers
                        // the cancel path (a draft is never silently lost).
                        val released = LilypadCornerMath.snapOnRelease(
                            liveQuad, corner, currentWidth, currentHeight,
                        )
                        draggingCorner = -1
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnCommit(released)
                    }
                }
            },
    ) {
        val scale = minOf(
            size.width / currentWidth.toFloat(),
            size.height / currentHeight.toFloat(),
        )
        if (scale <= 0f) return@Canvas
        val offX = (size.width - currentWidth * scale) / 2f
        val offY = (size.height - currentHeight * scale) / 2f
        fun toScreen(x: Double, y: Double) =
            Offset(offX + x.toFloat() * scale, offY + y.toFloat() * scale)

        val corners = LilypadCornerMath.quadCorners(currentQuad, currentWidth, currentHeight)
        val pts = corners.map { (x, y) -> toScreen(x, y) }

        // The displaced quad: white 2dp outline.
        val outline = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }
        drawPath(
            outline,
            handleTint,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )

        // Handles: 16dp accent circles; the dragged one wears the white ring.
        pts.forEachIndexed { index, center ->
            drawCircle(emberTint, radius = 8.dp.toPx(), center = center)
            if (index == draggingCorner) {
                drawCircle(
                    handleTint,
                    radius = 11.dp.toPx(),
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}
