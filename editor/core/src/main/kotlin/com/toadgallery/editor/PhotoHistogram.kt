package com.toadgallery.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
