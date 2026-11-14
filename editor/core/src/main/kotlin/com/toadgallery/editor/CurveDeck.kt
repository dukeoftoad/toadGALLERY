package com.toadgallery.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
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

// Curve deck: square plot of the baked 256-entry LUT + faint histogram; drag/tap/long-press
// edit handles (rules in LilypadCurveDeckMath). Drag writes the draft, release commits once.

/** Channel chip labels; the display order matches the model (master, then R/G/B). */
private val CURVE_CHANNELS: List<Pair<CurveChannel, Int>> = listOf(
    CurveChannel.Master to R.string.editor_r68_curve_channel_master,
    CurveChannel.Red to R.string.editor_r68_curve_channel_red,
    CurveChannel.Green to R.string.editor_r68_curve_channel_green,
    CurveChannel.Blue to R.string.editor_r68_curve_channel_blue,
)

/**
 * The curve deck (see the file header). [curveDraft] is the live whole-curve draft (drag
 * ticks write it, [onFinish] sweeps it into ONE commit); [histogram] is the live preview
 * source, null before the first preview (the plot then draws without its faint mass).
 */
@Composable
internal fun CurveDeck(
    committed: EditRecipe,
    curveDraft: ToneCurves?,
    onCurveDraftChange: (ToneCurves?) -> Unit,
    onFinish: () -> Unit,
    channel: CurveChannel,
    onChannelChange: (CurveChannel) -> Unit,
    histogram: EditorHistogram?,
    exporting: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val curves = curveDraft ?: committed.toneCurves
    val points = LilypadCurveDeckMath.pointsFor(channel, curves)

    // Gesture coroutines are keyed on the exporting flag alone, so every value they read
    // must flow through rememberUpdatedState (fresh-at-read).
    val currentPoints by rememberUpdatedState(points)
    val currentCurves by rememberUpdatedState(curves)
    val currentChannel by rememberUpdatedState(channel)
    val currentOnChange by rememberUpdatedState(onCurveDraftChange)
    val currentOnFinish by rememberUpdatedState(onFinish)

    // Dragged handle index (-1 = none) and the previous signed distance from the diagonal
    // (y - x); its sign change is the neutral crossing that fires ZERO_TICK.
    var draggingIndex by remember { mutableStateOf(-1) }
    var previousDiagonal by remember { mutableStateOf(0.0) }

    /** Every draft write rides this wrapper: emit the next whole-curve state. */
    fun emitDraft(nextPoints: List<CurvePoint>) {
        currentOnChange(
            LilypadCurveDeckMath.withPoints(currentChannel, currentCurves, nextPoints),
        )
    }

    /** The dragged point moves, clamped by the pure rules, with the diagonal tick. */
    fun dragTo(xNorm: Double, yNorm: Double) {
        val index = draggingIndex
        if (index < 0) return
        val next = LilypadCurveDeckMath.movePoint(currentPoints, index, xNorm, yNorm)
        val diagonal = next[index].y - next[index].x
        if (LilypadSliderMath.crossedNeutral(
                previousDiagonal.toFloat(),
                diagonal.toFloat(),
                0f,
            )
        ) {
            // ZERO_TICK: the handle crossed the curve's neutral.
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        previousDiagonal = diagonal
        emitDraft(next)
    }

    /** A release-commit: the COMMIT_SOFT pulse, then the chassis's ONE sweep. */
    fun finishEdit() {
        if (draggingIndex >= 0) {
            draggingIndex = -1
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        currentOnFinish()
    }

    // A11y strings resolved outside semantics{} (stringResource is composable; semantics{} is not).
    val plotName = stringResource(R.string.editor_r68_curve_deck_a11y)
    val plotState = stringResource(R.string.editor_r68_curve_state_points, points.size)
    val doneA11y = stringResource(R.string.editor_r68_curve_done)
    val hint = stringResource(R.string.editor_r68_curve_hint)

    // Channel tints are content, not chrome: the red curve IS the red channel.
    val gridTint = MaterialTheme.colorScheme.outlineVariant
    val diagonalTint = MaterialTheme.colorScheme.outline
    val redTint = Color(0xFFF0524A)
    val greenTint = Color(0xFF57B560)
    val blueTint = Color(0xFF4C8BF5)
    val curveTint = when (channel) {
        CurveChannel.Master -> MaterialTheme.colorScheme.onBackground
        CurveChannel.Red -> redTint
        CurveChannel.Green -> greenTint
        CurveChannel.Blue -> blueTint
    }
    val handleTint = MaterialTheme.colorScheme.onBackground
    val emberTint = MaterialTheme.colorScheme.primary

    Column(modifier = modifier.fillMaxWidth()) {
        // 1. Channel chip row + the deck's own Done exit (the entry chip lives on the board it replaces).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
            ) {
                CURVE_CHANNELS.forEach { (chipChannel, labelRes) ->
                    FilterChip(
                        selected = channel == chipChannel,
                        onClick = { onChannelChange(chipChannel) },
                        enabled = !exporting,
                        label = { Text(stringResource(labelRes)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
            IconButton(
                onClick = onCollapse,
                enabled = !exporting,
                modifier = Modifier.semantics { contentDescription = doneA11y },
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        // 2. The plot: square (slope is content), plus tap/long-press then drag detectors —
        // the long-press consumes the down so a post-long-press slide grabs nothing.
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .alpha(if (exporting) 0.38f else 1f)
                .semantics {
                    contentDescription = plotName
                    stateDescription = plotState
                }
                .pointerInput(exporting) {
                    if (!exporting) {
                        // Screen position <-> the plot's normalized space.
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        fun toNorm(position: Offset) = Pair(
                            (position.x / w).coerceIn(0f, 1f).toDouble(),
                            (1f - position.y / h).coerceIn(0f, 1f).toDouble(),
                        )
                        fun handleAt(position: Offset): Int {
                            val pts = currentPoints
                            val xs = FloatArray(pts.size) { (pts[it].x * w).toFloat() }
                            val ys = FloatArray(pts.size) { (h - pts[it].y * h).toFloat() }
                            return LilypadCurveDeckMath.hitIndex(
                                xs,
                                ys,
                                position.x,
                                position.y,
                                LilypadCurveDeckMath.HIT_RADIUS_DP.dp.toPx(),
                            )
                        }
                        detectTapGestures(
                            // Tap in curve space inserts a point; a tap on a handle does nothing.
                            onTap = { position ->
                                if (handleAt(position) < 0) {
                                    val (x, y) = toNorm(position)
                                    val next = LilypadCurveDeckMath.insertPoint(
                                        currentPoints,
                                        x,
                                        y,
                                    )
                                    if (next != null) {
                                        emitDraft(next)
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                        currentOnFinish()
                                    }
                                }
                            },
                            // Long-press on a handle deletes it; never below two points.
                            onLongPress = { position ->
                                val index = handleAt(position)
                                if (index >= 0) {
                                    val next = LilypadCurveDeckMath.deletePoint(
                                        currentPoints,
                                        index,
                                    )
                                    if (next != null) {
                                        emitDraft(next)
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                        currentOnFinish()
                                    }
                                }
                            },
                        )
                    }
                }
                .pointerInput(exporting) {
                    if (!exporting) {
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        fun handleAt(position: Offset): Int {
                            val pts = currentPoints
                            val xs = FloatArray(pts.size) { (pts[it].x * w).toFloat() }
                            val ys = FloatArray(pts.size) { (h - pts[it].y * h).toFloat() }
                            return LilypadCurveDeckMath.hitIndex(
                                xs,
                                ys,
                                position.x,
                                position.y,
                                LilypadCurveDeckMath.HIT_RADIUS_DP.dp.toPx(),
                            )
                        }
                        detectDragGestures(
                            onDragStart = { position ->
                                val index = handleAt(position)
                                draggingIndex = index
                                if (index >= 0) {
                                    val p = currentPoints[index]
                                    previousDiagonal = p.y - p.x
                                }
                            },
                            onDrag = { change, _ ->
                                if (draggingIndex >= 0) {
                                    val x = (change.position.x / w).coerceIn(0f, 1f).toDouble()
                                    val y =
                                        (1f - change.position.y / h).coerceIn(0f, 1f).toDouble()
                                    dragTo(x, y)
                                    change.consume()
                                }
                            },
                            onDragEnd = { finishEdit() },
                            onDragCancel = { finishEdit() },
                        )
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            val hairline = 1.dp.toPx()
            // 2a. Faint histogram: channel masses under Screen blending, normalized by the peak.
            val hist = histogram
            if (hist != null && hist.peak > 0) {
                fun massPath(bins: IntArray): Path = Path().apply {
                    moveTo(0f, h)
                    for (bin in bins.indices) {
                        lineTo(bin * w / 255f, h - bins[bin].toFloat() / hist.peak * h)
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(massPath(hist.red), redTint, alpha = 0.14f, blendMode = BlendMode.Screen)
                drawPath(
                    massPath(hist.green),
                    greenTint,
                    alpha = 0.14f,
                    blendMode = BlendMode.Screen,
                )
                drawPath(massPath(hist.blue), blueTint, alpha = 0.14f, blendMode = BlendMode.Screen)
            }
            // 2b. Quarter grid + plot frame, hairlines.
            for (i in 1..3) {
                val x = w * i / 4f
                drawLine(gridTint, Offset(x, 0f), Offset(x, h), hairline)
                val y = h * i / 4f
                drawLine(gridTint, Offset(0f, y), Offset(w, y), hairline)
            }
            drawLine(gridTint, Offset(0f, 0f), Offset(w, 0f), hairline)
            drawLine(gridTint, Offset(0f, h), Offset(w, h), hairline)
            drawLine(gridTint, Offset(0f, 0f), Offset(0f, h), hairline)
            drawLine(gridTint, Offset(w, 0f), Offset(w, h), hairline)
            // 2c. Identity diagonal: the drag reference and ZERO_TICK's neutral.
            drawLine(diagonalTint, Offset(0f, h), Offset(w, 0f), hairline)
            // 2d. The curve: the baked LUT drawn as a polyline — the exact table the pipeline composes.
            val lut = LilypadCurveMath.bakeLut(points)
            val curve = Path()
            for (v in lut.indices) {
                val x = v * w / 255f
                val y = h - lut[v] / 255f * h
                if (v == 0) curve.moveTo(x, y) else curve.lineTo(x, y)
            }
            drawPath(
                curve,
                curveTint,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
            // 2e. Handles as ink dots; the dragged one wears the ember ring.
            points.forEachIndexed { index, p ->
                val center = Offset((p.x * w).toFloat(), (h - p.y * h).toFloat())
                drawCircle(handleTint, radius = 8.dp.toPx(), center = center)
                if (index == draggingIndex) {
                    drawCircle(
                        emberTint,
                        radius = 11.dp.toPx(),
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }
        }
        // 3. Hint line: the plot's three gestures.
        Text(
            text = hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
