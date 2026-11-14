package com.toadgallery.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

// Lilypad ProSlider: adjustment row with drag-anywhere position-relative scrub,
// long-press fine mode, double-tap-to-neutral and a zero-crossing haptic tick.

/**
 * The Lilypad ProSlider: [label] + the [valueText] pill on the first line, the
 * hand-drawn track under them, and the whole row as the gesture surface. All
 * geometry and thresholds come from [LilypadSliderMath]; colors from the theme
 * (primary only for the live pill and the drag ring). The value is caller-owned:
 * every tick -> [onChange]; release/abort and the double-tap reset -> [onFinish].
 */
@Composable
internal fun ProSlider(
    name: String,
    label: String,
    state: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    neutral: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    onFinish: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    // Gesture anchors (declared before the modifiers that capture them):
    // drag start in value + finger space, plus the drag-active flag.
    var dragStartValue by remember { mutableStateOf(0f) }
    var dragStartX by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // Last value this row emitted or synced, so the haptic compares against the
    // immediate predecessor, not a stale composition.
    var previousValue by remember { mutableStateOf(value) }
    // pointerInput below is keyed on (enabled, valueRange, neutral) — NOT on
    // `value`, which changes every tick — so rememberUpdatedState keeps the
    // anchors, finish callback and emissions reading the current composition.
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinish by rememberUpdatedState(onFinish)
    // External value changes (tool Reset, draft swap) bypass this row's onChange;
    // sync so the crossing tracker never compares against a value that no longer
    // exists.
    LaunchedEffect(value) { previousValue = value }

    // Every emission rides this wrapper: crossing the neutral (or landing on
    // it) fires the light tick, then the value hands off to the caller.
    fun emit(next: Float) {
        if (LilypadSliderMath.crossedNeutral(previousValue, next, neutral)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        previousValue = next
        currentOnChange(next)
    }

    // Colors hoisted out of the draw lambda.
    val live = LilypadSliderMath.isLive(value, neutral)
    val pillColor =
        if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val pillTextColor =
        if (live) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val trackTint = MaterialTheme.colorScheme.outlineVariant
    val fillTint = MaterialTheme.colorScheme.onBackground
    val notchTint = MaterialTheme.colorScheme.outline
    val thumbTint = MaterialTheme.colorScheme.onBackground
    val emberTint = MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            // M3 disabled convention: dimmed, and no gesture detectors below.
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                contentDescription = name
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(current = value, range = valueRange)
            }
            // Double-tap: back to the row's neutral (emit + finish).
            .pointerInput(enabled, neutral) {
                if (enabled) {
                    detectTapGestures(
                        onDoubleTap = {
                            emit(neutral)
                            currentOnFinish?.invoke()
                        },
                    )
                }
            }
            // Relative scrub: a drag anywhere on the row maps its delta over
            // the row width to the range span, anchored at the start value.
            .pointerInput(enabled, valueRange, neutral) {
                if (enabled) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragStartValue = currentValue
                            dragStartX = offset.x
                            dragging = true
                        },
                        onHorizontalDrag = { change, _ ->
                            emit(
                                LilypadSliderMath.scrub(
                                    startValue = dragStartValue,
                                    startPx = dragStartX,
                                    currentPx = change.position.x,
                                    trackWidthPx = size.width.toFloat(),
                                    range = valueRange,
                                ),
                            )
                        },
                        onDragEnd = {
                            dragging = false
                            currentOnFinish?.invoke()
                        },
                        onDragCancel = {
                            dragging = false
                            currentOnFinish?.invoke()
                        },
                    )
                }
            }
            // Fine mode: long-press then drag — the same scrub at 10% gain
            // (FINE_GAIN inside [LilypadSliderMath.scrub]).
            .pointerInput(enabled, valueRange, neutral) {
                if (enabled) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            dragStartValue = currentValue
                            dragStartX = offset.x
                            dragging = true
                        },
                        onDrag = { change, _ ->
                            emit(
                                LilypadSliderMath.scrub(
                                    startValue = dragStartValue,
                                    startPx = dragStartX,
                                    currentPx = change.position.x,
                                    trackWidthPx = size.width.toFloat(),
                                    range = valueRange,
                                    fine = true,
                                ),
                            )
                        },
                        onDragEnd = {
                            dragging = false
                            currentOnFinish?.invoke()
                        },
                        onDragCancel = {
                            dragging = false
                            currentOnFinish?.invoke()
                        },
                    )
                }
            }
            // 48dp touch floor: the gestures above see the full row (~52dp),
            // not a 20dp strip. Padding rides AFTER the gesture modifiers —
            // it shrinks the content, not the touch surface.
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            // Ember-when-live: the pill burns when off-neutral; quiet raised pad
            // at neutral.
            Surface(
                shape = RoundedCornerShape(LilypadTokens.CHIP_CORNER_DP.dp),
                color = pillColor,
            ) {
                Text(
                    text = valueText,
                    color = pillTextColor,
                    style = LilypadValueStyle,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        ) {
            val centerY = size.height / 2f
            val trackStroke = 4.dp.toPx()
            // 1. The full track: a hairline across the whole row.
            drawLine(
                color = trackTint,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackStroke,
                cap = StrokeCap.Round,
            )
            val neutralX = LilypadSliderMath.fraction(neutral, valueRange) * size.width
            val thumbX = LilypadSliderMath.fraction(value, valueRange) * size.width
            // 2. The fill: neutral -> thumb (zero-length at neutral draws nothing).
            if (thumbX != neutralX) {
                drawLine(
                    color = fillTint,
                    start = Offset(minOf(neutralX, thumbX), centerY),
                    end = Offset(maxOf(neutralX, thumbX), centerY),
                    strokeWidth = trackStroke,
                    cap = StrokeCap.Round,
                )
            }
            // 3. The center notch, only where the neutral is the range's
            //    geometric center — the double-tap target's discovery mark.
            if (LilypadSliderMath.isSymmetric(valueRange, neutral)) {
                val notchHalf = 6.dp.toPx()
                drawLine(
                    color = notchTint,
                    start = Offset(neutralX, centerY - notchHalf),
                    end = Offset(neutralX, centerY + notchHalf),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            // 4. The thumb: ink dot, with a 2dp ember ring at 22dp diameter
            //    while a drag is live — the one moment the row burns.
            val thumbCenter = Offset(thumbX, centerY)
            drawCircle(color = thumbTint, radius = 9.dp.toPx(), center = thumbCenter)
            if (dragging) {
                drawCircle(
                    color = emberTint,
                    radius = 11.dp.toPx(),
                    center = thumbCenter,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}
