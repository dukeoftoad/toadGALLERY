package com.toadgallery.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// The tone family's tool panels: Adjust = full ProSlider board (every parameter
// visible at once), Color = HSL band rows; draft-on-tick, commit-on-release.

/**
 * The Adjust panel: live histogram at top, then ONE ProSlider row per
 * [ADJUST_SLIDERS] parameter (exposure through sharpness). Thin adapter over the
 * chassis state (committed + the ONE draft slot + commit); a11y: name =
 * contentDescription, formatted value = stateDescription. Leads with the scope row
 * (chart modes + zebra toggle, chassis-owned because the zebra paints the canvas)
 * and the curve-deck entry chip.
 */
@Composable
internal fun AdjustPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    previewHistogram: EditorHistogram? = null,
    scopeMode: PhotoScopeMode = PhotoScopeMode.Histogram,
    onScopeModeChange: (PhotoScopeMode) -> Unit = {},
    zebraActive: Boolean = false,
    onZebraActiveChange: (Boolean) -> Unit = {},
    curveDeckOpen: Boolean = false,
    onCurveDeckOpenChange: (Boolean) -> Unit = {},
) {
    // Scope row leads the board; the chart below follows the mode. The Curve chip
    // rides the row (LilypadFlags.curveDeckEnabled gates it; off = no chip).
    ScopeRow(
        scopeMode = scopeMode,
        onScopeModeChange = onScopeModeChange,
        zebraActive = zebraActive,
        onZebraActiveChange = onZebraActiveChange,
        enabled = !exporting,
        curveDeckOpen = curveDeckOpen,
        onCurveDeckOpenChange = onCurveDeckOpenChange,
    )
    // Live tonal readout from the SAME pipelined pixels the canvas shows (see
    // EditorHistogram's header). Absent until the first preview lands, never a placeholder.
    previewHistogram?.let { histogram ->
        when (scopeMode) {
            PhotoScopeMode.Histogram -> HistogramChart(histogram = histogram)
            PhotoScopeMode.Parade -> ParadeChart(histogram = histogram)
            PhotoScopeMode.Luminance -> LuminanceChart(
                bins = histogram.luma,
                peak = histogram.lumaPeak,
            )
        }
    }
    // The board: one ProSlider per parameter, every value visible at once. Each row
    // drafts per tick (preview re-renders live); release commits and clears the draft.
    ADJUST_SLIDERS.forEach { spec ->
        val committedValue = spec.get(committed)
        val live = if (draft?.first == spec.key) draft.second else committedValue
        ProSlider(
            name = stringResource(spec.a11yNameRes),
            label = stringResource(spec.a11yNameRes),
            state = stringResource(spec.label, live),
            valueText = LilypadSliderMath.signedValueText(live),
            value = live.toFloat(),
            valueRange = -100f..100f,
            neutral = 0f,
            enabled = !exporting,
            onChange = { v -> onDraftChange(spec.key to v.toInt()) },
            onFinish = {
                val d = draft
                if (d != null && d.first == spec.key) {
                    commit(spec.set(committed, d.second))
                }
                onDraftChange(null)
            },
        )
    }
}

@Composable
internal fun ColorPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    selectedBand: String,
    onSelectedBandChange: (String) -> Unit,
) {

    // Per-channel HSL: band selector + three rows.
    Text(
        text = stringResource(R.string.editor_section_hsl),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        HSL_BANDS.forEach { (name, _) ->
            FilterChip(
                selected = selectedBand == name,
                onClick = { onSelectedBandChange(name)},
                // Band chips disable during export like every other control (one frozen state).
                enabled = !exporting,
                label = { Text(stringResource(bandNameRes(name))) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    val band = HSL_BANDS.first { it.first == selectedBand }.second(committed.hslBands)
    val bandLabel = stringResource(bandNameRes(selectedBand))

    // Three band ProSlider rows, each led by the band's hue swatch. Draft keys follow
    // "hsl-<field>@<band>" so effectiveRecipe merges live; release commits ONE recipe.
    val hueKey = "hsl-hue@$selectedBand"
    val satKey = "hsl-sat@$selectedBand"
    val lumKey = "hsl-lum@$selectedBand"
    val hueLive = if (draft?.first == hueKey) draft.second else band.hue
    val satLive = if (draft?.first == satKey) draft.second else band.saturation
    val lumLive = if (draft?.first == lumKey) draft.second else band.luminance

    /** Release: the drafted field lands as ONE commit, then the draft slot clears. */
    fun commitBand(key: String, apply: (Int, HslBandAdjust) -> HslBandAdjust) {
        val d = draft
        if (d != null && d.first == key) {
            commit(
                committed.copy(
                    hslBands = committed.hslBands.withBand(
                        selectedBand,
                        apply(d.second, band),
                    ),
                ),
            )
        }
        onDraftChange(null)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        HslBandSwatch(bandName = selectedBand)
        ProSlider(
            name = stringResource(
                R.string.editor_a11y_band_control,
                bandLabel,
                stringResource(R.string.editor_a11y_hue),
            ),
            label = stringResource(R.string.editor_a11y_hue),
            state = stringResource(R.string.editor_hsl_hue, hueLive),
            valueText = LilypadSliderMath.signedValueText(hueLive),
            value = hueLive.toFloat(),
            valueRange = -180f..180f,
            neutral = 0f,
            enabled = !exporting,
            onChange = { v -> onDraftChange(hueKey to v.toInt()) },
            onFinish = { commitBand(hueKey) { v, b -> b.copy(hue = v) } },
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HslBandSwatch(bandName = selectedBand)
        ProSlider(
            name = stringResource(
                R.string.editor_a11y_band_control,
                bandLabel,
                stringResource(R.string.editor_a11y_saturation),
            ),
            label = stringResource(R.string.editor_a11y_saturation),
            state = stringResource(R.string.editor_hsl_saturation, satLive),
            valueText = LilypadSliderMath.signedValueText(satLive),
            value = satLive.toFloat(),
            valueRange = -100f..100f,
            neutral = 0f,
            enabled = !exporting,
            onChange = { v -> onDraftChange(satKey to v.toInt()) },
            onFinish = { commitBand(satKey) { v, b -> b.copy(saturation = v) } },
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HslBandSwatch(bandName = selectedBand)
        ProSlider(
            name = stringResource(
                R.string.editor_a11y_band_control,
                bandLabel,
                stringResource(R.string.editor_a11y_luminance),
            ),
            label = stringResource(R.string.editor_a11y_luminance),
            state = stringResource(R.string.editor_hsl_luminance, lumLive),
            valueText = LilypadSliderMath.signedValueText(lumLive),
            value = lumLive.toFloat(),
            valueRange = -100f..100f,
            neutral = 0f,
            enabled = !exporting,
            onChange = { v -> onDraftChange(lumKey to v.toInt()) },
            onFinish = { commitBand(lumKey) { v, b -> b.copy(luminance = v) } },
            modifier = Modifier
                .padding(start = 10.dp)
                .weight(1f),
        )
    }
}

/**
 * Display-only swatch hues: the disc identifies the band; nothing here enters the
 * engine. A missing band name degrades to the neutral ink.
 */
private val HSL_SWATCHES: Map<String, Color> = mapOf(
    "red" to Color(0xFFE53935),
    "orange" to Color(0xFFFB8C00),
    "yellow" to Color(0xFFFDD835),
    "green" to Color(0xFF43A047),
    "aqua" to Color(0xFF00ACC1),
    "blue" to Color(0xFF1E88E5),
    "purple" to Color(0xFF8E24AA),
    "magenta" to Color(0xFFD81B60),
)

/**
 * The band's hue disc: 20dp circle, hairline outline ring --- colour as identification.
 */
@Composable
private fun HslBandSwatch(bandName: String) {
    val tint = HSL_SWATCHES[bandName] ?: Color.White
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(tint)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}


/** The HSL band's display name; ids are the band keys of HSL_BANDS. */
private fun bandNameRes(name: String): Int = when (name) {
    "red" -> R.string.editor_band_red
    "orange" -> R.string.editor_band_orange
    "yellow" -> R.string.editor_band_yellow
    "green" -> R.string.editor_band_green
    "aqua" -> R.string.editor_band_aqua
    "blue" -> R.string.editor_band_blue
    "purple" -> R.string.editor_band_purple
    else -> R.string.editor_band_magenta
}

/**
 * Band-apply helper, deliberately file-private: EditActivity carries its own twin
 * beside HSL_BANDS --- two file-local copies, no cross-file surface.
 */
private fun HslBands.withBand(name: String, band: HslBandAdjust): HslBands = when (name) {
    "red" -> copy(red = band)
    "orange" -> copy(orange = band)
    "yellow" -> copy(yellow = band)
    "green" -> copy(green = band)
    "aqua" -> copy(aqua = band)
    "blue" -> copy(blue = band)
    "purple" -> copy(purple = band)
    else -> copy(magenta = band)
}
