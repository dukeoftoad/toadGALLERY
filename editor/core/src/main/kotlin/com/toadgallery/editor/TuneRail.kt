package com.toadgallery.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// The r63c tune-rail kit (the approved proposal, section 2 card 2 + section 4
// "Adjust panel (r63c)" row): the Ente tune pattern read from their editor
// source (image_editor_tune_bar.dart) and rebuilt on this repo's contracts.
//
// The Adjust panel's stacked LabeledSliders retire in favor of ONE rail:
//
//  - a horizontal row of parameter chips, each wearing a circular PROGRESS
//    RING that renders its parameter's normalized value at a glance (the
//    conic arc = value/2 percent of the circle: -100 sweeps half a turn
//    counterclockwise from the top, +100 half a turn clockwise, 0 leaves
//    the bare track);
//  - exactly ONE active slider below the row (M3 Slider, the theme's accent
//    fill, the r56 value pill on the leading edge) --- tapping a chip
//    activates its parameter;
//  - tapping the ALREADY-ACTIVE chip toggles the value between 0 and its
//    last non-zero value (the Ente `_lastValues` trick: the memory is a
//    tiny UI-layer map, written at toggle-off time from the live value and
//    consulted at toggle-on --- it is never recipe state).
//
// This kit replaces the Adjust panel's sliders ONLY (PhotoPanelTone.kt);
// the non-tonal panels keep the r56 LabeledSlider kit untouched.
//
// Zero pipeline change: the r32 draft contract rides verbatim --- every
// drag tick writes the chassis draft slot (`onDraftChange`, the preview
// re-renders live), release commits through the caller's `commit`, and the
// chip toggle is the old slider's double-tap-to-reset sequence (draft
// write -> commit -> draft clear) re-targeted at the chip tap. The a11y
// contract is the r32 one, verbatim: name = contentDescription, formatted
// value = stateDescription, on BOTH the chips and the active slider.
// ---------------------------------------------------------------------------

/**
 * One parameter chip: the 34dp progress ring (the 15dp glyph stands in for
 * Ente's icon --- this round adds no drawable assets, so the ring renders
 * the parameter's initial from the existing a11y string) over the 8sp
 * label. The selected state is carried by `selected` + the radio role (the
 * ColorSwatch convention: one active parameter among the rail's set); the
 * a11y contract is the r32 slider's, verbatim: name = contentDescription,
 * formatted value = stateDescription. The chip's visible texts are cleared
 * from the semantics tree (presentation only) so TalkBack reads exactly
 * one name and one state per chip, like the sliders they replace.
 */
@Composable
internal fun TuneRailChip(
    name: String,
    glyph: String,
    valueText: String,
    value: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    // A local with a distinct name: inside the semantics {} lambda the
    // receiver's own `selected` property shadows this parameter, so an
    // unqualified `selected = selected` reads as self-assignment (the
    // ColorSwatch note, verbatim).
    val isSelected = selected
    val labelTint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics {
                this.selected = isSelected
                contentDescription = name
                stateDescription = valueText
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            TuneRing(value = value)
            Text(
                text = glyph,
                fontSize = 12.sp,
                color = labelTint,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
        Text(
            text = name,
            fontSize = 8.sp,
            maxLines = 1,
            color = labelTint,
            modifier = Modifier
                .padding(top = 2.dp)
                .clearAndSetSemantics { },
        )
    }
}

/**
 * The chip's progress ring: a full track circle in the hairline role with
 * the accent arc over it --- the conic arc is value/2 percent of the circle
 * (the approved spec row), i.e. `value * 1.8f` degrees swept from the top;
 * positive values sweep clockwise, negative counterclockwise, zero draws
 * nothing (the bare track IS the zero state). Magnitude is honest to the
 * range: the arc is half a turn at either extreme of [-100, 100].
 */
@Composable
private fun TuneRing(value: Int) {
    val trackTint = MaterialTheme.colorScheme.outlineVariant
    val arcTint = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(34.dp)) {
        val stroke = 3.dp.toPx()
        val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(
            color = trackTint,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        val sweep = value * 1.8f
        if (sweep != 0f) {
            drawArc(
                color = arcTint,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * The rail's ONE active slider row: the r56 value pill on the leading edge
 * (the approved spec row), the M3 Slider with the theme's accent fill, and
 * the r32 a11y contract verbatim --- name = contentDescription, formatted
 * value = stateDescription --- plus the old slider's double-tap-to-neutral
 * (resetValue = 0 in the r56 kit): double-tapping the active slider returns
 * THAT parameter to zero (draft write -> finish, the exact r56 sequence).
 * Caller-owned value, exactly like LabeledSlider: every tick calls
 * [onChange], the release calls [onFinish].
 *
 * r66: the neutral notch rides the track's exact center (the
 * [NeutralNotch] contract in PhotoControls.kt) --- the rail's fixed
 * [-100, 100] range is symmetric around zero, so the center IS the zero
 * the double-tap returns to, and the symmetric-zero guard keeps an
 * asymmetric future range honest.
 */
@Composable
internal fun TuneRailSlider(
    name: String,
    state: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onFinish: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        // r66: the Box carries the row weight so the slider's slot can
        // host the neutral notch overlay at the track's exact center
        // (the layout is byte-identical --- the Box only adds the paint).
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Slider(
                value = value,
                onValueChange = { onChange(it.toInt()) },
                onValueChangeFinished = onFinish,
                valueRange = valueRange,
                enabled = enabled,
                modifier = Modifier
                    .semantics {
                        contentDescription = name
                        stateDescription = state
                    }
                    .pointerInput(enabled) {
                        if (enabled) {
                            detectTapGestures(
                                onDoubleTap = {
                                    onChange(0)
                                    onFinish?.invoke()
                                },
                            )
                        }
                    },
            )
            if (valueRange.start + valueRange.endInclusive == 0f) {
                NeutralNotch(modifier = Modifier.matchParentSize())
            }
        }
    }
}

/**
 * The assembled rail: the chip row for [specs] (the existing ADJUST_SLIDERS
 * list --- the code's existing panel order IS the chip order) plus the one
 * active slider below it. Owns exactly two pieces of UI-layer state, both
 * session-local (the tool panel dies with its tool screen --- the chassis
 * Crossfade disposes it on switch/exit --- which is the same lifetime as
 * the tool Reset's entry snapshot):
 *
 *  - the ACTIVE parameter (defaults to the first spec, so the panel always
 *    renders exactly one slider --- the approved mockup's shape);
 *  - the `_lastValues` memory: per-parameter last non-zero value, written
 *    ONLY at toggle-off time from the live value and consulted at
 *    toggle-on. It is never recipe state and never touches the pipeline;
 *    a parameter that was never toggled off restores nothing.
 *
 * The toggle sequence on the active chip is the r56 double-tap contract
 * re-targeted: write the draft, commit the new value through the caller,
 * clear the draft. Tapping a param that is already 0 with no memory is an
 * honest no-op (the chip stays active, nothing commits).
 */
@Composable
internal fun TuneRail(
    specs: List<SliderSpec>,
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
) {
    var activeKey by remember { mutableStateOf(specs.first().key) }
    // The Ente _lastValues trick (UI layer only --- see the file header).
    var lastNonZero by remember { mutableStateOf(mapOf<String, Int>()) }

    Column {
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (spec in specs) {
                val committedValue = spec.get(committed)
                val live = if (draft?.first == spec.key) draft.second else committedValue
                val name = stringResource(spec.a11yNameRes)
                TuneRailChip(
                    name = name,
                    // The in-ring glyph: the parameter's initial (the icon
                    // stand-in --- this round adds no drawable assets).
                    glyph = name.firstOrNull()?.toString() ?: "",
                    valueText = stringResource(spec.label, live),
                    value = live,
                    selected = spec.key == activeKey,
                    enabled = !exporting,
                    onClick = {
                        if (spec.key == activeKey) {
                            // Tap-to-toggle-zero on the already-active chip.
                            val next = if (live != 0) 0 else (lastNonZero[spec.key] ?: 0)
                            if (next != live) {
                                if (live != 0) {
                                    lastNonZero = lastNonZero + (spec.key to live)
                                }
                                onDraftChange(Pair(spec.key, next))
                                commit(spec.set(committed, next))
                                onDraftChange(null)
                            }
                        } else {
                            // Activation: reveal this parameter's slider.
                            activeKey = spec.key
                        }
                    },
                )
            }
        }
        val activeSpec = specs.first { it.key == activeKey }
        val activeCommitted = activeSpec.get(committed)
        val activeLive = if (draft?.first == activeKey) draft.second else activeCommitted
        TuneRailSlider(
            name = stringResource(activeSpec.a11yNameRes),
            state = stringResource(activeSpec.label, activeLive),
            valueText = activeLive.toString(),
            value = activeLive.toFloat(),
            valueRange = -100f..100f,
            enabled = !exporting,
            onChange = { value -> onDraftChange(Pair(activeKey, value)) },
            onFinish = {
                val d = draft
                if (d != null && d.first == activeKey) {
                    commit(activeSpec.set(committed, d.second))
                }
                onDraftChange(null)
            },
        )
    }
}
