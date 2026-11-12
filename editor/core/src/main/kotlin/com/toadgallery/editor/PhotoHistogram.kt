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

// ---------------------------------------------------------------------------
// The r66 histogram chart (the honest-feedback round): the Adjust panel's
// live tonal readout, drawn from [EditorHistogram]'s bins.
//
// The shape is the classic photo-editor RGB histogram --- three filled
// channel curves composited with Screen blending, so overlaps read as
// their additive colours (red over green reads yellow, exactly like the
// Lightroom/Snapseed charts users already know) instead of as mud. The
// channel colours are SEMANTIC CONTENT, not chrome: the red curve IS the
// red channel --- the same exemption the r56 reference strip's sweep
// stops carry (only EditorTheme.kt + VideoEditTheme hold ACCENT hexes; a
// chart whose red channel were themed green would be a lie).
//
// The a11y contract is the r32 one applied to a read-only chart: the
// Canvas announces its NAME (contentDescription) and its STATE
// (stateDescription --- the three luma zones as whole percents, computed
// by the JVM-tested EditorHistogram.zoneFractions), so a TalkBack user
// hears the tonal distribution a sighted user sees. There is no honest
// way to speak a curve; the zones are its summary.
//
// Zero pipeline change: the chart is a pure READER of state the preview
// render already produces (see EditorHistogram's header for where the
// pixels come from); it renders nothing, commits nothing, and is absent
// (never a placeholder) until the first preview lands.
// ---------------------------------------------------------------------------

/**
 * The live tonal chart: [histogram]'s three channel curves as filled
 * paths under Screen blending, normalized by the histogram's own peak.
 * Fixed 64dp height (the chip-row height language --- a taller chart
 * would push the tune rail out of the panel's 300dp window); width
 * fills the panel. An empty histogram (peak 0) draws nothing but still
 * announces its (empty) state.
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
    // The channel identity colours (semantic content --- see the file
    // header; NOT theme accents).
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
        // One polyline per channel: bin index mapped across the width,
        // the bin count (over the peak) down from the top, closed along
        // the bottom. 256 vertices --- the bins ARE the curve.
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

// ---------------------------------------------------------------------------
// r68: the photo pro round's scopes (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md). The Adjust panel leads with a scope-mode
// row --- Histogram / Parade / Zebra --- and the chart below it follows the
// mode: the r66 overlaid RGB histogram, or the PARADE (the three channel
// bin arrays side by side, the colorist's scope: a channel's TONAL SPREAD
// reads at a glance, and a color cast shows as one panel's mass sitting
// higher or lower than its siblings'). The Zebra chip is an independent
// TOGGLE (it overlays the photo itself, not the panel); the chart modes
// are a radio pair.
// ---------------------------------------------------------------------------

/** The Adjust panel's scope chart modes; the Zebra overlay rides beside them as an independent toggle. */
internal enum class PhotoScopeMode { Histogram, Parade }

/**
 * The scope-mode row (r68): the Histogram/Parade radio pair plus the Zebra
 * toggle, in the FilterChip language the panel rows already use. Selected
 * chips read as the r52 ember pill through the theme's selection mechanism
 * (zero per-chip overrides, the deck's convention).
 *
 * The row's end carries the CURVE entry chip (r68): the curve deck is the
 * LilypadDeck's Full state, entered from the scope neighborhood where the
 * tonal instruments live. The chip is a toggle-shaped ENTRY, not a chart
 * mode --- opening it expands the deck to Full and swaps in the curve
 * editor --- and it rides behind [LilypadFlags.curveDeckEnabled] (the
 * reviewed revert seam; off reads as the scope row without it).
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
        // An independent toggle, not a mode: the zebra paints the PHOTO,
        // so it rides beside whichever chart the panel shows.
        FilterChip(
            selected = zebraActive,
            onClick = { onZebraActiveChange(!zebraActive) },
            enabled = enabled,
            label = { Text(stringResource(R.string.editor_r68_scope_zebra)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        // The curve deck's entry: the Full-state surface, opened from
        // the scope row it belongs to (the tonal board's fourth
        // instrument). Collapsed/Half deck states keep their current
        // content behavior --- the chip only ever EXPANDS to Full.
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
 * The RGB parade (r68): the three channel bin arrays as side-by-side
 * filled panels --- red, green, blue left to right, the tonal axis
 * running left (black) to right (white) inside each third, the count
 * normalized by the histogram's own peak. The Lilypad frame around the
 * data: a reed hairline grid (the tonal quarter marks inside each third,
 * hairline separators between them), the channel tints as the panels'
 * fills. No labels --- the bins ARE the axis, exactly like the histogram
 * the chart shares its row with.
 *
 * The a11y contract is the histogram's, verbatim: the chart announces its
 * NAME and its STATE (the three luma zones as whole percents --- there is
 * no honest way to speak three curves; the zones are their summary).
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
    // The channel identity colors (semantic content --- the histogram's,
    // verbatim; NOT theme accents).
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
        // One polyline per channel inside its third: bin index mapped
        // across the third's width, the bin count (over the peak) down
        // from the top, closed along the bottom.
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
        // The reed hairline grid: the two channel separators plus the
        // tonal quarter marks inside each third (bins 64, 128, 192).
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
