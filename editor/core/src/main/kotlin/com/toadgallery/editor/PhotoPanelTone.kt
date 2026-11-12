package com.toadgallery.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// The r57 panel seam (F-58a): the tone family's tool panels, extracted
// from `EditActivity.EditorContent`'s `PhotoScreen.Adjust` and
// `PhotoScreen.Color` branches.
//
// r67, the Lilypad foundation round (docs/editor/lilypad-design.md): the
// Adjust panel becomes the full ProSlider BOARD --- every parameter of
// [ADJUST_SLIDERS] visible at once, each row a ProSlider with its live
// mono value pill (the B1 fix of the rebirth audit: the r63c one-slider
// rail hid nine of the ten values behind a chip row; a photographer
// adjusting exposure could not see what contrast or temperature were
// set to). The TuneRail chassis this panel hosted retires with the
// round; the r32 draft contract (drag ticks draft, release commits and
// clears) and the r32 a11y contract (name + formatted state on every
// row) ride unchanged, and the r66 live histogram still leads the
// board.
//
// The Color panel keeps its r47 HSL band contract untouched this round
// (the band rows' ProSlider migration is the pro-surfaces round, C5).
// ---------------------------------------------------------------------------

/**
 * The Adjust panel, rebuilt as the r67 Lilypad board: the r66 live
 * histogram at the top, then ONE ProSlider row per [ADJUST_SLIDERS]
 * parameter --- exposure through sharpness, the code's existing order
// IS the board order. The panel stays a thin adapter over the chassis
// state (committed + the ONE draft slot + commit); the a11y contract is
// the r32 one, verbatim (name = contentDescription, formatted value =
 * stateDescription, plus the adjustability progressBarRangeInfo the
 * ProSlider carries).
 *
 * The bodies are the r31/r32 adjust contracts, unchanged; the chassis
 * state arrives as explicit value + `on...Change` pairs. The committed
 * Roborazzi chassis baselines are the regression proof (re-recorded as
 * the round's deliberate baseline pass).
 */
@Composable
internal fun AdjustPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    previewHistogram: EditorHistogram? = null,
) {
    // r66: the live tonal readout --- the board leads with the histogram
    // the way every serious photo editor does, drawn from the SAME
    // pipelined pixels the canvas shows (see EditorHistogram's header
    // for the wiring). Absent (never a placeholder) until the first
    // preview lands.
    previewHistogram?.let { histogram ->
        HistogramChart(histogram = histogram)
    }
    // r67: the BOARD --- one ProSlider per parameter, every value visible
    // at once. Each row reads the committed recipe (or its own draft slot
    // while live) and commits through the same draft/finish sequence the
    // rail used: every drag tick writes the chassis draft slot (the
    // preview re-renders live), release commits through the caller's
    // `commit` and clears the draft.
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
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    selectedBand: String,
    onSelectedBandChange: (String) -> Unit,
) {

    // ---- Per-channel HSL: band selector + three sliders ----
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
                // r47 ux fix: the band chips stayed live during an
                // export while every other control disables --- one
                // consistent frozen state.
                enabled = !exporting,
                label = { Text(stringResource(bandNameRes(name))) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    val band = HSL_BANDS.first { it.first == selectedBand }.second(committed.hslBands)
    val bandLabel = stringResource(bandNameRes(selectedBand))
    HslBandSlider(
        name = stringResource(
            R.string.editor_a11y_band_control,
            bandLabel,
            stringResource(R.string.editor_a11y_hue),
        ),
        label = stringResource(R.string.editor_hsl_hue, band.hue),
        value = band.hue.toFloat(),
        range = -180f..180f,
        enabled = !exporting,
        onCommit = { v ->
            commit(
                committed.copy(
                    hslBands = committed.hslBands.withBand(
                        selectedBand,
                        band.copy(hue = v),
                    ),
                ),
            )
        },
    )
    HslBandSlider(
        name = stringResource(
            R.string.editor_a11y_band_control,
            bandLabel,
            stringResource(R.string.editor_a11y_saturation),
        ),
        label = stringResource(R.string.editor_hsl_saturation, band.saturation),
        value = band.saturation.toFloat(),
        range = -100f..100f,
        enabled = !exporting,
        onCommit = { v ->
            commit(
                committed.copy(
                    hslBands = committed.hslBands.withBand(
                        selectedBand,
                        band.copy(saturation = v),
                    ),
                ),
            )
        },
    )
    HslBandSlider(
        name = stringResource(
            R.string.editor_a11y_band_control,
            bandLabel,
            stringResource(R.string.editor_a11y_luminance),
        ),
        label = stringResource(R.string.editor_hsl_luminance, band.luminance),
        value = band.luminance.toFloat(),
        range = -100f..100f,
        enabled = !exporting,
        onCommit = { v ->
            commit(
                committed.copy(
                    hslBands = committed.hslBands.withBand(
                        selectedBand,
                        band.copy(luminance = v),
                    ),
                ),
            )
        },
    )
}


/** The HSL band's display name (r31); ids are the band keys of HSL_BANDS. */
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

/** The band-apply helper the r31 Color panel commits through (verbatim). */
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
