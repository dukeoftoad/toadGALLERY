package com.toadgallery.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

// The Adjust panel's live tonal charts (RGB histogram, parade, luminance), pure readers of
// EditorHistogram state. Channel colors are semantic content, not theme accents.

/**
 * The live tonal chart: three channel curves as filled paths under Screen blending,
 * normalized by the peak. Fixed 64dp height (keeps the panel's 300dp window intact);
 * an empty histogram draws nothing but still announces its state.
 */
@Composable
internal fun HistogramChart(
    histogram: EditorHistogram,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(R.string.editor_r66_histogram_a11y)
    val (shadowsPct, midtonesPct, highlightsPct) = histogram.zoneFractions()
    val state = stringResource(
        R.string.editor_a11y_r66_histogram_state,
        shadowsPct,
        midtonesPct,
        highlightsPct,
    )
    // Channel identity colors: semantic content, not theme accents.
    val redTint = Color(0xFFF0524A)
    val greenTint = Color(0xFF57B560)
    val blueTint = Color(0xFF4C8BF5)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics {
                contentDescription = name
                stateDescription = state
            },
    ) {
        val peak = histogram.peak
        if (peak <= 0) return@Canvas // the empty count: nothing to draw
        val w = size.width
        val h = size.height
        // One filled polyline per channel: bin index across the width, count/peak down, closed at bottom.
        fun channelPath(bins: IntArray): Path = Path().apply {
            moveTo(0f, h)
            for (bin in bins.indices) {
                lineTo(bin * w / 255f, h - bins[bin].toFloat() / peak * h)
            }
            lineTo(w, h)
            close()
        }
        drawPath(channelPath(histogram.red), redTint, alpha = 0.55f, blendMode = BlendMode.Screen)
        drawPath(channelPath(histogram.green), greenTint, alpha = 0.55f, blendMode = BlendMode.Screen)
        drawPath(channelPath(histogram.blue), blueTint, alpha = 0.55f, blendMode = BlendMode.Screen)
    }
}

// Scope modes: the overlaid RGB histogram, the Parade (three channel panels side by side),
// and the Luminance scope; the Zebra chip is an independent toggle over the photo.

/** The Adjust panel's scope chart modes; the Zebra overlay rides beside them as an independent toggle. */
internal enum class PhotoScopeMode { Histogram, Parade, Luminance }

/**
 * Scope-mode row: Histogram/Parade/Luminance radio chips, the Zebra toggle, and the curve
 * deck entry chip. The entry chip is a toggle-shaped ENTRY, not a chart mode — opening it
 * expands the deck to Full; gated behind [LilypadFlags.curveDeckEnabled] (off omits it).
 */
@Composable
internal fun ScopeRow(
    scopeMode: PhotoScopeMode,
    onScopeModeChange: (PhotoScopeMode) -> Unit,
    zebraActive: Boolean,
    onZebraActiveChange: (Boolean) -> Unit,
    enabled: Boolean,
    curveDeckOpen: Boolean = false,
    onCurveDeckOpenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(
            selected = scopeMode == PhotoScopeMode.Histogram,
            onClick = { onScopeModeChange(PhotoScopeMode.Histogram) },
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_r68_scope_histogram)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        FilterChip(
            selected = scopeMode == PhotoScopeMode.Parade,
            onClick = { onScopeModeChange(PhotoScopeMode.Parade) },
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_r68_scope_parade)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        // Third mode: the tonal spread free of colour (the master curve's scope).
        FilterChip(
            selected = scopeMode == PhotoScopeMode.Luminance,
            onClick = { onScopeModeChange(PhotoScopeMode.Luminance) },
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_scope_luminance)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        // Independent toggle, not a mode: zebra paints the photo, riding beside any chart.
        FilterChip(
            selected = zebraActive,
            onClick = { onZebraActiveChange(!zebraActive) },
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_r68_scope_zebra)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        // Curve deck entry: only ever expands the deck to Full; other deck states unaffected.
        if (LilypadFlags.curveDeckEnabled) {
            FilterChip(
                selected = curveDeckOpen,
                onClick = { onCurveDeckOpenChange(!curveDeckOpen) },
                enabled = enabled,
                label = { Text(stringResource(R.string.editor_r68_curve_chip)) },
            )
        }
    }
}

/**
 * The RGB parade: red/green/blue filled panels left to right, tonal axis black-to-white
 * within each third, normalized by the peak; hairline separators + quarter marks, no
 * labels. A11y mirrors the histogram: name + state (luma zones as percents).
 */
@Composable
internal fun ParadeChart(
    histogram: EditorHistogram,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(R.string.editor_r68_parade_a11y)
    val (shadowsPct, midtonesPct, highlightsPct) = histogram.zoneFractions()
    val state = stringResource(
        R.string.editor_a11y_r66_histogram_state,
        shadowsPct,
        midtonesPct,
        highlightsPct,
    )
    // Channel identity colors: semantic content, not theme accents (same as the histogram).
    val redTint = Color(0xFFF0524A)
    val greenTint = Color(0xFF57B560)
    val blueTint = Color(0xFF4C8BF5)
    val gridTint = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics {
                contentDescription = name
                stateDescription = state
            },
    ) {
        val peak = histogram.peak
        if (peak <= 0) return@Canvas // the empty count: nothing to draw
        val w = size.width
        val h = size.height
        val third = w / 3f
        // One filled polyline per channel inside its third; closed along the bottom.
        fun channelPath(bins: IntArray, x0: Float): Path = Path().apply {
            moveTo(x0, h)
            for (bin in bins.indices) {
                lineTo(x0 + bin * third / 255f, h - bins[bin].toFloat() / peak * h)
            }
            lineTo(x0 + third, h)
            close()
        }
        drawPath(channelPath(histogram.red, 0f), redTint, alpha = 0.75f)
        drawPath(channelPath(histogram.green, third), greenTint, alpha = 0.75f)
        drawPath(channelPath(histogram.blue, third * 2f), blueTint, alpha = 0.75f)
        // Hairline grid: channel separators + tonal quarter marks inside each third.
        val hairline = 1.dp.toPx()
        for (i in 1..2) {
            val x = third * i
            drawLine(gridTint, Offset(x, 0f), Offset(x, h), hairline)
        }
        for (panel in 0..2) {
            val x0 = third * panel
            for (quarter in 1..3) {
                val x = x0 + third * quarter / 4f
                drawLine(gridTint, Offset(x, 0f), Offset(x, h), hairline)
            }
        }
    }
}

/**
 * The luminance scope: one filled curve over Rec.601 luma bins, neutral paper-ink fill
 * (no channel identity here); quarter grid + peak normalization as in the histogram.
 */
@Composable
internal fun LuminanceChart(
    bins: IntArray,
    peak: Int,
    modifier: Modifier = Modifier,
) {
    val name = stringResource(R.string.editor_scope_luminance)
    // Hoisted out of the Canvas draw lambda (DrawScope is not composable).
    val gridTint = MaterialTheme.colorScheme.outlineVariant
    // Zone percents for a11y state are computed by the caller; this chart holds only bins.
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = name },
    ) {
        val w = size.width
        val h = size.height
        val hairline = 1.dp.toPx()
        // Hairline quarter grid, as in the histogram.
        for (quarter in 1..3) {
            val x = w * quarter / 4f
            drawLine(gridTint, Offset(x, 0f), Offset(x, h), hairline)
        }
        if (peak <= 0) return@Canvas // the empty count: nothing to draw
        val ink = Color(0xFFF2F1EE) // the Lilypad paper ink
        val path = Path().apply {
            moveTo(0f, h)
            for (bin in bins.indices) {
                lineTo(bin * w / 255f, h - bins[bin].toFloat() / peak * h)
            }
            lineTo(w, h)
            close()
        }
        drawPath(path, ink, alpha = 0.35f)
    }
}
