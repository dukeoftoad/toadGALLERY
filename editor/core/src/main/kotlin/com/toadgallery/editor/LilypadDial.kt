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

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the STRAIGHTEN DIAL, the ProSlider's
// rotary sibling. The straighten row was the one adjustment whose value is
// itself an ANGLE; a linear track has to fake that with a scaled axis,
// while a dial can simply BE the instrument --- the value reads as the arc
// it burns from twelve o'clock, and the photo turns the way the finger did.
//
// THE C1 CONTRACT, bent round a circle (the interaction parity the design
// doc asks of the sibling --- every rule below is ProSlider's own, with the
// arithmetic's angle twin in LilypadDialMath, JVM-pinned in
// LilypadDialMathTest):
//
//  - DRAG ANYWHERE ON THE DIAL: the gesture surface is the whole circle
//    (112dp, the generous rotary target), and the scrub is ANGULAR and
//    POSITION-RELATIVE --- the finger's shortest-way-round delta around
//    the dial's centre maps to the value span at a fixed 4:1 reduction
//    (one full finger turn covers the whole +/-45 span), anchored at the
//    value the drag started from. No knob-sized target, no dead zones:
//    grab the rim, grab the middle, twist.
//  - LONG-PRESS FINE MODE: press and hold, then drag --- the same scrub at
//    LilypadSliderMath.FINE_GAIN (10%), the surgical touch for a horizon
//    that is ALMOST level.
//  - DOUBLE-TAP TO NEUTRAL: back to 0 deg, the draft-write -> finish
//    sequence the r56 kit established.
//  - HAPTIC ZERO TICK on the DETENT CROSSING: the zero detent is magnetic
//    (LilypadDialMath.snappedToDetent pulls values within 1.5 deg of level
//    home), and every emission rides ONE wrapper whose crossedNeutral
//    check fires the light TextHandleMove tick --- the P0 stand-in for
//    ZERO_TICK, exactly as ProSlider pins it. COMMIT_SOFT (the TextHandleMove
//    stand-in the curve deck pins) lands when a released drag commits.
//    DETENT_DOUBLE stays silent this round: the vocabulary's snap-point
//    pulse has no platform stand-in, and conflating it with either of the
//    two honest ids would muddy all three (the history strip's own call).
//  - EMBER-WHEN-LIVE: the value's arc and the centered readout burn ember
//    the moment the value leaves level; at level the arc is zero-length
//    (nothing drawn --- the quiet state IS bare track) and the readout
//    rests in the ink tone.
//
// The contracts that ride unchanged from the row it replaces:
//  - r32 a11y: the dial announces its NAME (contentDescription) and its
//    formatted VALUE (stateDescription), PLUS progressBarRangeInfo so
//    TalkBack keeps the adjustability the M3 Slider provided;
//  - the commit-on-release draft contract: every drag tick calls
//    [onChange] (the caller writes its draft, the preview re-renders
//    live), release/abort and the double-tap reset call [onFinish] (the
//    caller commits and clears). The value is CALLER-OWNED.
//
// Drawing (one Canvas, in order): the hairline full-circle track
// (outlineVariant, 4dp, the ProSlider track's own stroke) -> the ember arc
// from twelve o'clock (0 deg) to the value's own angle, round-capped,
// skipped at level -> the centre detent tick at twelve o'clock (2dp x 12dp
// in the outline role, the r66 NeutralNotch language --- the mark that
// makes the double-tap target discoverable). The mono readout (the value
// style over the bundled JetBrains Mono) centers in the circle; the label
// reads under it in reed. Disabled dials drop to the M3 convention's 38%
// alpha and install no gesture detectors.
// ---------------------------------------------------------------------------

/** The dial's diameter: the generous rotary target (>= the 48dp floor the rows obey). */
private const val DIAL_SIZE_DP = 112

/**
 * The Lilypad straighten dial (see the file header): the circular track
 * with its ember value arc, the twelve o'clock detent tick, the centered
 * mono readout and the reed label under it. All arithmetic comes from
 * [LilypadDialMath]; all colors from the theme's scheme. The neutral is 0
 * by construction --- a dial with a detent is inherently bipolar, so the
 * detent IS the neutral and no unipolar caller exists for this shape.
 *
 * The drag anchors, the crossing tracker and the drag-active flag are
 * UI-layer session state; the VALUE is caller-owned, per the r32 draft
 * contract: every tick -> [onChange], release/abort and the double-tap
 * reset -> [onFinish].
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
    // The gesture anchors: where the drag started, in value space and in
    // finger-angle space (the angle the finger's vector read when the
    // drag began --- the scrub measures its shortest-way-round delta
    // against THIS, never against a recomputed absolute).
    var dragStartValue by remember { mutableStateOf(0f) }
    var dragStartAngle by remember { mutableStateOf(0f) }
    // The ZERO_TICK tracker (the ProSlider's own): the last value THIS
    // dial emitted (or synced from an external change), so the next
    // emission compares against its immediate predecessor.
    var previousValue by remember { mutableStateOf(value) }
    // The pointerInput coroutines are keyed on (enabled, valueRange) ---
    // deliberately NOT on `value`, which changes every drag tick --- so
    // the canonical rememberUpdatedState indirection keeps the anchors,
    // the finish callback and the emissions reading the CURRENT
    // composition's values.
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnFinish by rememberUpdatedState(onFinish)
    // External value changes (a tool Reset, a draft swap) never pass
    // through this dial's onChange; without this sync the crossing
    // tracker would compare the next drag against a value that no longer
    // exists (the ProSlider's own honesty rule).
    LaunchedEffect(value) { previousValue = value }

    /** Every value emission rides the SAME wrapper (the C1 haptic rule):
     * the detent snaps first (float space, BEFORE the caller's integer
     * rounding --- the detent must bite where the thumb actually is),
     * then a move that crosses or lands on level fires the light tick,
     * then the value hands off to the caller. */
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

    // The quiet chrome + the one ember role (hoisted out of the draw
    // lambda, the ProSlider convention).
    val live = LilypadSliderMath.isLive(value, 0f)
    val trackTint = MaterialTheme.colorScheme.outlineVariant
    val arcTint = MaterialTheme.colorScheme.primary
    val tickTint = MaterialTheme.colorScheme.outline
    val readoutTint = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground

    Column(
        modifier = modifier
            .fillMaxWidth()
            // The M3 disabled convention: present but dimmed, and (below)
            // with no gesture detectors installed.
            .alpha(if (enabled) 1f else 0.38f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(DIAL_SIZE_DP.dp)
                // r32 a11y: the NAME, the formatted VALUE, and the
                // adjustability the M3 Slider provided (the ProSlider's
                // semantics wiring, verbatim).
                .semantics {
                    contentDescription = name
                    stateDescription = state
                    progressBarRangeInfo = ProgressBarRangeInfo(current = value, range = valueRange)
                }
                // Double-tap: back to level (the r56 reset sequence
                // re-targeted: draft write -> finish).
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
                // Relative rotary scrub: an ANY-DIRECTION drag anywhere on
                // the dial maps its angular delta (shortest way round the
                // centre) to the value span, anchored at the value the
                // drag started from.
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
                // The track rides the box's inscribed circle, a half
                // stroke in from the edge so the 4dp line stays whole.
                val radius = size.minDimension / 2f - trackStroke / 2f
                // 1. The full track: a hairline circle.
                drawCircle(
                    color = trackTint,
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(width = trackStroke),
                )
                // 2. The value's arc: ember, from twelve o'clock (0 deg)
                //    to the value's OWN dial angle (the value IS its
                //    angle --- the identity the math pins, so the arc
                //    cannot lie about which way the photo will turn). A
                //    zero-length arc draws nothing: the quiet state at
                //    level is bare track, exactly the ProSlider's rule.
                //    Compose's arc space starts at three o'clock and runs
                //    clockwise, so the dial angle shifts by -90.
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
                // 3. The centre detent tick: the r66 NeutralNotch language
                //    (2dp x 12dp hairline in the outline role) laid ACROSS
                //    the track at twelve o'clock --- the mark that makes
                //    the double-tap target discoverable.
                val tickHalf = 6.dp.toPx()
                drawLine(
                    color = tickTint,
                    start = Offset(cx, cy - radius - tickHalf),
                    end = Offset(cx, cy - radius + tickHalf),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            // 4. The readout: the value style (mono 12sp/600 --- a tool
            //    readout, not prose), centered in the circle, burning
            //    ember the moment the value leaves level.
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
