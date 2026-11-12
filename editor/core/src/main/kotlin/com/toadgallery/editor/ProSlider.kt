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

// ---------------------------------------------------------------------------
// The r67 Lilypad ProSlider (the C1 card of the Lilypad foundation round,
// docs/editor/lilypad-design.md): the photo editor's flagship adjustment
// row, the surface that replaces BOTH of its ancestors --- the r56
// LabeledSlider-on-tone rows (label + pill over a stock M3 Slider) and the
// r63c TuneRail's ONE active slider. The design premise is Lilypad's: the
// photo is the hero, the chrome is quiet, and the ember (primary) appears
// only where it MEANS something.
//
// The C1 contract, top to bottom (the arithmetic lives in
// LilypadSliderMath, JVM-pinned in LilypadSliderMathTest):
//
//  - DRAG ANYWHERE ON THE ROW: the gestures live on the ROOT column, not
//    on a 20dp track strip --- the whole row (label line + track + the
//    6dp breathing padding, >= 48dp of touch height) is draggable, and the
//    scrub is POSITION-RELATIVE (the Snapseed lesson): the finger's DELTA
//    over the row width maps to the range span and shifts the value the
//    drag started from, so no thumb-sized target ever has to be hit. The
//    interaction pattern is re-derived from behavior, not ported --- this
//    repo is GPL-3.0 and every line here is original.
//  - LONG-PRESS FINE MODE: press and hold, then drag --- the same finger
//    motion moves the value at LilypadSliderMath.FINE_GAIN (10%), the
//    surgical touch for exposure and temperature micro-edits.
//  - DOUBLE-TAP TO NEUTRAL: the row's [neutral] (0 for bipolar rows, the
//    low end for intensity-like rows) resets with the draft-write ->
//    finish sequence the r56 kit established.
//  - HAPTIC ZERO TICK: every emission rides ONE wrapper ([emit] below) ---
//    when the drag moves the value from one side of the neutral to the
//    other (or onto it), the light TextHandleMove haptic fires (the P0
//    stand-in for the ZERO_TICK haptic id; the platform exposes no
//    dedicated zero-crossing id).
//  - EMBER-WHEN-LIVE PILL: the value pill burns (primary + onPrimary) the
//    moment the value leaves its neutral, and rests as a quiet raised pad
//    (surfaceVariant + reed text) at neutral. The number sets in
//    LilypadValueStyle (mono 12sp/600) --- a tool readout, not prose.
//
// The contracts that ride unchanged from the controls it replaces:
//  - r32 a11y: the row announces its NAME (contentDescription) and its
//    formatted VALUE (stateDescription), PLUS progressBarRangeInfo so
//    TalkBack keeps the adjustability the M3 Slider provided;
//  - the commit-on-release draft contract: every drag tick calls
//    [onChange] (the caller updates its draft, the preview re-renders
//    live), and release/abort and the double-tap reset call [onFinish]
//    (the caller commits and clears). The value is CALLER-OWNED, exactly
//    like LabeledSlider/TuneRailSlider before it.
//
// Drawing (one Canvas, in order): the hairline full track (outlineVariant,
// 4dp, round caps) -> the fill from NEUTRAL to thumb (onBackground) -> the
// r66 center notch (2dp x 12dp, outline) only where the range is symmetric
// around the neutral -> the 18dp onBackground thumb, with a 2dp ember ring
// at 22dp diameter while a drag is live. Disabled rows drop to the M3
// convention's 38% alpha and install no gesture detectors.
//
// (r67 recovery note: the r67 working tree was lost mid-round; this file
// was restored verbatim from the round's read-back --- the lost-tree
// recovery documented in the round's delivery report.)
// ---------------------------------------------------------------------------

/**
 * The Lilypad ProSlider: one adjustment row --- [label] and the
 * [valueText] pill on the first line, the hand-drawn track under them, and
 * the whole row as the gesture surface. All geometry and thresholds come
 * from [LilypadSliderMath]; all colors from the theme's color scheme
 * (quiet neutrals; primary only for the live pill and the drag ring).
 *
 * The drag anchors, the crossing tracker, and the drag-active flag are
 * UI-layer session state (the row dies with its panel); the VALUE itself
 * is caller-owned, per the r32 draft contract: every tick -> [onChange],
 * release/abort and the double-tap reset -> [onFinish].
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
    // The gesture anchors (declared BEFORE the modifiers that capture
    // them): where the drag started, in value space and in finger space,
    // plus the drag-active flag that lights the thumb's ember ring.
    var dragStartValue by remember { mutableStateOf(0f) }
    var dragStartX by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // The ZERO_TICK tracker: the last value THIS row emitted (or synced
    // from an external change), so the next emission can be compared
    // against its immediate predecessor, not against a stale composition.
    var previousValue by remember { mutableStateOf(value) }
    // The pointerInput coroutines below are keyed on (enabled,
    // valueRange, neutral) --- deliberately NOT on `value`, which changes
    // every drag tick --- so a directly captured parameter would read the
    // composition the block launched in, not the latest one. The
    // rememberUpdatedState indirection (the canonical pointerInput
    // pattern) keeps the drag anchors, the finish callback and the
    // emissions reading the CURRENT composition's values.
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinish by rememberUpdatedState(onFinish)
    // External value changes (a tool Reset, a draft swap) never pass
    // through this row's onChange; without this sync the crossing tracker
    // would compare the next drag against a value that no longer exists
    // and fire (or swallow) a dishonest tick.
    LaunchedEffect(value) { previousValue = value }

    // Every value emission rides the SAME wrapper (the C1 haptic rule):
    // a change that moves the value from one side of the neutral to the
    // other (or onto it) fires the light tick, then the value hands off
    // to the caller. The double-tap reset is an emission too --- landing
    // on the neutral from either side is the loudest ZERO_TICK of all.
    fun emit(next: Float) {
        if (LilypadSliderMath.crossedNeutral(previousValue, next, neutral)) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        previousValue = next
        currentOnChange(next)
    }

    // The quiet chrome + the one ember role (hoisted out of the draw
    // lambda, the TuneRing/NeutralNotch convention).
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
            // The M3 disabled convention: present but dimmed, and (below)
            // with no gesture detectors installed.
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                contentDescription = name
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(current = value, range = valueRange)
            }
            // Double-tap: back to the row's neutral (the r56 reset
            // sequence re-targeted: draft write -> finish).
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
            // Relative scrub: a horizontal drag ANYWHERE on the row maps
            // its delta over the row width to the range span, anchored at
            // the value the drag started from.
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
            // Fine mode: press and hold, then drag --- the same scrub at
            // 10% gain (FINE_GAIN inside [LilypadSliderMath.scrub]).
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
            // The 48dp touch floor: the gestures above see the FULL row
            // (label line ~20dp + track 20dp + this 12dp of breathing
            // room = ~52dp), not a 20dp strip. The padding rides AFTER
            // the gesture modifiers on purpose --- it shrinks the
            // content, not the touch surface.
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
            // THE EMBER-WHEN-LIVE RULE: the pill burns ember (primary +
            // onPrimary) the moment the value leaves its neutral; at
            // neutral it is a quiet raised pad (surfaceVariant + reed).
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
            // 2. The fill: neutral -> thumb, in the ink role (a zero-length
            // fill at neutral draws nothing --- the quiet state IS bare
            // track plus thumb).
            if (thumbX != neutralX) {
                drawLine(
                    color = fillTint,
                    start = Offset(minOf(neutralX, thumbX), centerY),
                    end = Offset(maxOf(neutralX, thumbX), centerY),
                    strokeWidth = trackStroke,
                    cap = StrokeCap.Round,
                )
            }
            // 3. The center notch: the r66 NeutralNotch language (2dp x
            //    12dp hairline in the outline role), only where the neutral
            //    is the range's geometric center --- the mark that makes the
            //    double-tap target discoverable.
            if (LilypadSliderMath.isSymmetric(valueRange, neutral)) {
                val notchHalf = 6.dp.toPx()
                drawLine(
                    color = notchTint,
                    start = Offset(neutralX, centerY - notchHalf),
                    end = Offset(neutralX, centerY + notchHalf),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            // 4. The thumb: an 18dp ink dot at the value's position,
            //    with a 2dp ember ring at 22dp diameter while a drag (normal
            //    or fine) is live --- the one moment the row burns.
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
