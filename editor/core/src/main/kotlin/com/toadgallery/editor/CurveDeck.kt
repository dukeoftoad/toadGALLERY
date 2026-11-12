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

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the CURVE DECK, the surface the
// LilypadDeck's Full state (78%) was reserved for in r67.
//
// THE SHAPE. One square plot: the 256-entry BAKED curve over the edited
// channel's points (LilypadCurveMath.bakeLut --- the exact table the
// pipeline composes, so the deck draws the truth the export renders,
// not an approximating polyline) over a FAINT rendering of the live
// histogram (the Adjust board's own source, drawn into the same
// normalized tone axis, so the curve reads against the photo's mass),
// a hairline quarter grid, and the identity diagonal. Control points
// are handles: drag one (clamped between its neighbors --- the bake's
// strictly-increasing invariant holds by construction), tap curve-space
// to insert, long-press a handle to delete (never below two points).
// Every rule is pure arithmetic in LilypadCurveDeckMath, JVM-pinned;
// this file only draws and gestures.
//
// THE CHANNELS. Master/R/G/B chips (FilterChips --- the r52 ember pill
// when selected) switch which curve the plot edits; the model carries
// all four and the wire round-trips them, while the ENGINE composes the
// master curve this round (the per-channel apply path is the sanctioned
// next step; see LilypadCurveMath's header). The chips drive the model
// anyway: a channel curve stays honest in the sidecar across the round
// that turns it on.
//
// THE DRAFT CONTRACT (the r32 one, verbatim in shape): every drag tick
// writes the whole-curve draft (zero-ms --- the preview re-renders
// live, the baked LUT following the finger), release commits ONE
// recipe through the chassis's sweep ([onFinish]); a tap-insert or a
// long-press-delete is a discrete gesture and commits the same way.
// The channel switch commits NOTHING (a view preference, like the
// scope mode).
//
// HAPTICS, per the LilypadTokens vocabulary only: ZERO_TICK when a
// dragged handle crosses the identity diagonal (the curve's neutral ---
// the light TextHandleMove stand-in the ProSlider documents), and
// COMMIT_SOFT when a draft lands (the TextHandleMove stand-in; the
// platform exposes no dedicated draft-landing id, the same honest
// substitution ProSlider.kt pins for ZERO_TICK).
//
// A11Y: the plot announces its NAME (contentDescription) and its STATE
// (the point count --- the r66 chart contract; there is no honest way
// to speak a spline, but "Curve: 3 points" is its shape's summary, and
// the channel chips speak the selection). The chips and the Done
// affordance carry their own button semantics.
// ---------------------------------------------------------------------------

/**
 * The channel chips' labels (the display order IS the model's: master,
 * then the three channels).
 */
private val CURVE_CHANNELS: List<Pair<CurveChannel, Int>> = listOf(
    CurveChannel.Master to R.string.editor_r68_curve_channel_master,
    CurveChannel.Red to R.string.editor_r68_curve_channel_red,
    CurveChannel.Green to R.string.editor_r68_curve_channel_green,
    CurveChannel.Blue to R.string.editor_r68_curve_channel_blue,
)

/**
 * The r68 curve deck (see the file header): the square plot with its
 * baked curve, faint histogram, grid, diagonal and handles, led by the
 * channel chip row and closed by [onCollapse]. [committed] is the
 * recipe the deck edits; [curveDraft] is the live whole-curve draft (a
 * drag tick writes it, [onFinish] sweeps it into ONE recipe commit);
 * [histogram] is the Adjust board's live source (null before the first
 * preview lands --- the plot draws without its faint mass, never a
 * fake one).
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

    // Fresh-at-read refs for the gesture coroutines (the ProSlider
    // pattern): the detectors are keyed on the exporting flag alone, so
    // every value they read must flow through rememberUpdatedState ---
    // a parent's newer composition is the one the gesture must see.
    val currentPoints by rememberUpdatedState(points)
    val currentCurves by rememberUpdatedState(curves)
    val currentChannel by rememberUpdatedState(channel)
    val currentOnChange by rememberUpdatedState(onCurveDraftChange)
    val currentOnFinish by rememberUpdatedState(onFinish)

    // The dragged handle's index (-1 = none) and the ZERO_TICK tracker:
    // the dragged point's signed distance from the identity diagonal
    // (y - x), whose sign change IS the curve's neutral crossing.
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

    // The plot's a11y pair, resolved OUTSIDE any semantics{} lambda
    // (stringResource is composable; semantics{} is not).
    val plotName = stringResource(R.string.editor_r68_curve_deck_a11y)
    val plotState = stringResource(R.string.editor_r68_curve_state_points, points.size)
    val doneA11y = stringResource(R.string.editor_r68_curve_done)
    val hint = stringResource(R.string.editor_r68_curve_hint)

    // The quiet chrome + the semantic colors. The channel tints are
    // CONTENT, not chrome (the histogram chart's exemption, verbatim):
    // the red curve IS the red channel.
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
        // 1. THE CHANNEL ROW: the four chips (the r52 ember pill marks
        // the selection) and the deck's own Done --- the Full state's
        // exit, because the entry chip lives on the board the Full
        // state replaces.
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
        // 2. THE PLOT: square (a curve's slope is content --- a
        // non-square plot would lie about it), the semantics pair, and
        // the two gesture detectors (tap/long-press, then drag --- the
        // ProSlider's proven stack: the tap detector cancels once the
        // drag detector consumes past slop, and a long-press consumes
        // the down so a post-long-press slide grabs nothing).
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
                            // A tap in curve-space GROWS the curve at the
                            // tapped spot; a tap on a handle is its
                            // long-press's little sibling and does nothing.
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
                            // A long-press on a handle deletes it (the pure
                            // floor refuses to go below two points --- a
                            // no-op long-press, never a broken curve).
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
            // 2a. The faint histogram: the three channel curves as
            // filled masses under Screen blending, normalized by the
            // histogram's own peak (the chart's arithmetic, at a
            // whisper --- the curve is the figure, the mass the ground).
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
            // 2b. The quarter grid + the plot's frame (the parade's
            // hairline language).
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
            // 2c. The identity diagonal: the reference every drag is
            // read against (and the ZERO_TICK's neutral).
            drawLine(diagonalTint, Offset(0f, h), Offset(w, 0f), hairline)
            // 2d. THE CURVE: the 256-entry baked LUT as one polyline
            // --- the exact table the pipeline composes, not an
            // approximation of it.
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
            // 2e. The handles: the control points as ink dots, the
            // dragged one wearing the ember ring (the ProSlider's
            // drag-time language, verbatim).
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
        // 3. The hint: the plot's three gestures in one reed line ---
        // the affordances a pro surface owes a first-time finger.
        Text(
            text = hint,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
