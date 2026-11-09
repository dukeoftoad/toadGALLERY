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
// VERBATIM from `EditActivity.EditorContent`'s `PhotoScreen.Adjust` and
// `PhotoScreen.Color` branches (plus their private label/apply helpers,
// `bandNameRes` and `withBand`, whose only readers lived in these branches).
//
// The bodies are the r31/r32 adjust contracts and the r47 HSL band contract,
// unchanged; the chassis state arrives as explicit value + `on...Change`
// pairs. The committed Roborazzi chassis baselines are the regression proof.
// ---------------------------------------------------------------------------

/**
 * The Adjust panel, rebuilt as the r63c tune-rail (the approved proposal's
 * Ente pattern): the stacked LabeledSliders retire here --- the section
 * heading stays, the slider stack becomes [TuneRail]'s chip-ring row plus
 * ONE active slider. The contract is unchanged: the panel is a thin
 * adapter over the chassis state (committed + the ONE draft slot + commit);
 * every parameter in [ADJUST_SLIDERS] rides the same draft/commit pipeline
 * as before, and the a11y contract is the r32 one, verbatim (see
 * TuneRail.kt). The non-tonal panels keep the LabeledSlider kit.
 *
 * The bodies are the r31/r32 adjust contracts and the r47 HSL band contract,
 * unchanged; the chassis state arrives as explicit value + `on...Change`
 * pairs. The committed Roborazzi chassis baselines are the regression proof.
 */
@Composable
internal fun AdjustPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
) {

    // ---- The adjustment suite ----
    Text(
        text = stringResource(R.string.editor_section_adjust),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    TuneRail(
        specs = ADJUST_SLIDERS,
        committed = committed,
        draft = draft,
        onDraftChange = onDraftChange,
        exporting = exporting,
        commit = commit,
    )
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
