package com.toadgallery.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

// The straighten dial, the ProSlider's rotary sibling (interaction parity, arithmetic
// in LilypadDialMath, JVM-pinned in LilypadDialMathTest).

/** The dial's diameter: the generous rotary target (>= the 48dp floor the rows obey). */
private const val DIAL_SIZE_DP = 112

/**
 * The straighten dial: circular track, ember value arc, twelve-o'clock detent tick,
 * centered mono readout. All arithmetic from [LilypadDialMath]. The neutral is 0 by
 * construction (a detent dial is inherently bipolar). The value is caller-owned:
 * every tick -> [onChange]; release/abort and double-tap reset -> [onFinish].
 */
@Composable
internal fun LilypadDial(
    name: String,
    label: String,
    state: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onFinish: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // Gesture anchors: drag start in value space + finger-angle space (the scrub
    // measures the shortest-way-round delta against THIS, never an absolute).
    var dragStartValue by remember { mutableStateOf(0f) }
    var dragStartAngle by remember { mutableStateOf(0f) }
    // Zero-tick tracker: last emitted/synced value, so the next emission compares to its predecessor.
    var previousValue by remember { mutableStateOf(value) }
    // pointerInput is keyed on (enabled, valueRange), NOT `value` (changes every
    // tick); rememberUpdatedState keeps the callbacks reading the current composition.
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinish by rememberUpdatedState(onFinish)
    // External value changes (Reset, draft swap) bypass onChange; this sync keeps the
    // crossing tracker from comparing against a stale value.
    LaunchedEffect(value) { previousValue = value }

    /** Every emission: detent snap first (float space, before caller rounding), then
     * the light tick on a neutral crossing, then the handoff to the caller. */
    fun emit(next: Float) {
        val snapped = LilypadDialMath.snappedToDetent(next)
        if (LilypadSliderMath.crossedNeutral(previousValue, snapped, 0f)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        previousValue = snapped
        currentOnChange(snapped)
    }

    /** A release-commit: the COMMIT_SOFT pulse, then the caller's ONE commit. */
    fun finishDrag() {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        currentOnFinish?.invoke()
    }

    // Chrome tints + the ember role, hoisted out of the draw lambda (the ProSlider convention).
    val live = LilypadSliderMath.isLive(value, 0f)
    val trackTint = MaterialTheme.colorScheme.outlineVariant
    val arcTint = MaterialTheme.colorScheme.primary
    val tickTint = MaterialTheme.colorScheme.outline
    val readoutTint = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground

    Column(
        modifier = modifier
            .fillMaxWidth()
            // M3 disabled convention: dimmed, and no gesture detectors installed (below).
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(DIAL_SIZE_DP.dp)
                // A11y: name, formatted value, and M3 Slider adjustability (as ProSlider wires).
                .semantics {
                    contentDescription = name
                    stateDescription = state
                    progressBarRangeInfo = ProgressBarRangeInfo(current = value, range = valueRange)
                }
                // Double-tap: back to level (draft write -> finish).
                .pointerInput(enabled) {
                    if (enabled) {
                        detectTapGestures(
                            onDoubleTap = {
                                emit(0f)
                                currentOnFinish?.invoke()
                            },
                        )
                    }
                }
                // Relative rotary scrub: any-direction drag anywhere on the dial maps its
                // angular delta (shortest way round) to the value span, anchored at drag start.
                .pointerInput(enabled, valueRange) {
                    if (enabled) {
                        fun angleAt(position: Offset): Float {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            return LilypadDialMath.vectorAngleDeg(
                                dx = position.x - cx,
                                dy = position.y - cy,
                            )
                        }
                        detectDragGestures(
                            onDragStart = { position ->
                                dragStartValue = currentValue
                                dragStartAngle = angleAt(position)
                            },
                            onDrag = { change, _ ->
                                emit(
                                    LilypadDialMath.scrub(
                                        startValue = dragStartValue,
                                        startAngleDeg = dragStartAngle,
                                        currentAngleDeg = angleAt(change.position),
                                        range = valueRange,
                                    ),
                                )
                            },
                            onDragEnd = { finishDrag() },
                            onDragCancel = { finishDrag() },
                        )
                    }
                }
                // Fine mode: press and hold, then drag --- the same scrub
                // at 10% gain (FINE_GAIN inside [LilypadDialMath.scrub]).
                .pointerInput(enabled, valueRange) {
                    if (enabled) {
                        fun angleAt(position: Offset): Float {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            return LilypadDialMath.vectorAngleDeg(
                                dx = position.x - cx,
                                dy = position.y - cy,
                            )
                        }
                        detectDragGesturesAfterLongPress(
                            onDragStart = { position ->
                                dragStartValue = currentValue
                                dragStartAngle = angleAt(position)
                            },
                            onDrag = { change, _ ->
                                emit(
                                    LilypadDialMath.scrub(
                                        startValue = dragStartValue,
                                        startAngleDeg = dragStartAngle,
                                        currentAngleDeg = angleAt(change.position),
                                        range = valueRange,
                                        fine = true,
                                    ),
                                )
                            },
                            onDragEnd = { finishDrag() },
                            onDragCancel = { finishDrag() },
                        )
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val trackStroke = 4.dp.toPx()
                // Track rides the inscribed circle, half a stroke in from the edge (line stays whole).
                val radius = size.minDimension / 2f - trackStroke / 2f
                // 1. The full track: a hairline circle.
                drawCircle(
                    color = trackTint,
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(width = trackStroke),
                )
                // 2. The value's arc: ember, from twelve o'clock to the value's own dial
                //    angle (the value IS its angle; zero length draws nothing --- bare track
                //    at level). Compose arcs start at three o'clock, hence the -90 shift.
                if (value != 0f) {
                    drawArc(
                        color = arcTint,
                        startAngle = -90f,
                        sweepAngle = value,
                        useCenter = false,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(radius * 2f, radius * 2f),
                        style = Stroke(width = trackStroke, cap = StrokeCap.Round),
                    )
                }
                // 3. The centre detent tick: 2dp x 12dp hairline across the track at twelve
                //    o'clock (NeutralNotch language); makes double-tap discoverable.
                val tickHalf = 6.dp.toPx()
                drawLine(
                    color = tickTint,
                    start = Offset(cx, cy - radius - tickHalf),
                    end = Offset(cx, cy - radius + tickHalf),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            // 4. The readout: value style (mono 12sp/600, a tool readout), centered in the
            //    circle, burning ember when the value leaves level.
            Text(
                text = valueText,
                color = readoutTint,
                style = LilypadValueStyle,
            )
        }
        // 5. The label: the reed caption under the instrument.
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
