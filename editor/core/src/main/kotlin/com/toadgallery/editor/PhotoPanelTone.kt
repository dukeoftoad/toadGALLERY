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
// r68, the pro-surfaces round (C5): the Color panel's three band
// controls join the board --- ProSlider rows led by the band's hue
// swatch, drafted through the chassis slot (the text layers' own key
// shape, "hsl-<field>@<band>", merged live by effectiveRecipe) and
// committed ONCE on release; the per-tick-committing HslBandSlider
// retires with the round. The r47 band contract itself --- eight bands,
// hue/saturation/luminance, bipolar zeros --- rides verbatim underneath.
// ---------------------------------------------------------------------------

/**
 * The Adjust panel, rebuilt as the r67 Lilypad board: the r66 live
 * histogram at the top, then ONE ProSlider row per [ADJUST_SLIDERS]
 * parameter --- exposure through sharpness, the code's existing order
 * IS the board order. The panel stays a thin adapter over the chassis
 * state (committed + the ONE draft slot + commit); the a11y contract is
 * the r32 one, verbatim (name = contentDescription, formatted value =
 * stateDescription, plus the adjustability progressBarRangeInfo the
 * ProSlider carries).
 *
 * The bodies are the r31/r32 adjust contracts, unchanged; the chassis
 * state arrives as explicit value + `on...Change` pairs. The committed
 * Roborazzi chassis baselines are the regression proof (re-recorded as
 * the round's deliberate baseline pass).
 *
 * r68: the board leads with the SCOPE ROW --- the Histogram/Parade chart
 * modes plus the Zebra overlay toggle ([ScopeRow]) --- and the chart
 * under it follows the mode. The states arrive as explicit pairs, the
 * host's hoisting pattern; the chassis owns them because the zebra
 * paints the CANVAS (the panel only owns its toggle chip). The scope
 * row also carries the CURVE entry chip ([curveDeckOpen]): the curve
 * deck is the Deck's Full state, and its entry point belongs to the
 * scope neighborhood --- the tonal instruments lead the tonal board.
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
    // r68: the scope row leads the board (the tonal readout is a CHOICE
    // of scope now), and the chart below follows the mode. The Curve
    // chip rides the row (LilypadFlags.curveDeckEnabled is the reviewed
    // revert seam; off reads as the r68 scope row without it).
    ScopeRow(
        scopeMode = scopeMode,
        onScopeModeChange = onScopeModeChange,
        zebraActive = zebraActive,
        onZebraActiveChange = onZebraActiveChange,
        enabled = !exporting,
        curveDeckOpen = curveDeckOpen,
        onCurveDeckOpenChange = onCurveDeckOpenChange,
    )
    // r66/r68: the live tonal readout, drawn from the SAME pipelined
    // pixels the canvas shows (see EditorHistogram's header for the
    // wiring). Absent (never a placeholder) until the first preview
    // lands. The mode picks the chart: the overlaid RGB histogram, or
    // the side-by-side parade.
    previewHistogram?.let { histogram ->
        when (scopeMode) {
            PhotoScopeMode.Histogram -> HistogramChart(histogram = histogram)
            PhotoScopeMode.Parade -> ParadeChart(histogram = histogram)
        }
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
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    selectedBand: String,
    onSelectedBandChange: (String) -> Unit,
) {

    // ---- Per-channel HSL: band selector + three rows ----
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

    // r68 (C5): the three band controls as ProSlider rows, each led by
    // the band's hue swatch (the disc identifies the BAND --- the row
    // itself names the field). The draft keys follow the text layers'
    // own shape, "hsl-<field>@<band>", so effectiveRecipe merges the
    // drag live exactly like a text slider's; release commits ONE
    // recipe through the same finish sequence every row uses.
    val hueKey = "hsl-hue@$selectedBand"
    val satKey = "hsl-sat@$selectedBand"
    val lumKey = "hsl-lum@$selectedBand"
    val hueLive = if (draft?.first == hueKey) draft.second else band.hue
    val satLive = if (draft?.first == satKey) draft.second else band.saturation
    val lumLive = if (draft?.first == lumKey) draft.second else band.luminance

    /**
     * The row's release: the drafted field lands as ONE commit (the
     * band's committed state with the released value), then the draft
     * slot clears --- the r32 finish sequence, verbatim.
     */
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
 * The eight bands' swatch hues (r68, C5): DISPLAY-ONLY colour --- the
 * disc identifies the band the rows below edit; the VALUES the rows
 * carry are the recipe's own, and nothing about the swatch enters the
 * engine. A missing band name degrades to the neutral ink, never to a
 * crash.
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
 * The band's hue disc (r68, C5): a 20dp circle in the 8dp-chip shape
 * language, ringed by the hairline outline role --- colour as
 * identification, the one place the Lilypad rules let a saturated hue
 * sit outside the photo.
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

/**
 * The band-apply helper (the r31 Color panel's own, verbatim): stays
 * FILE-PRIVATE --- EditActivity carries its own twin beside HSL_BANDS
 * (the r31 original's location), and the two file-local copies are the
 * one shape both writers share without a cross-file surface.
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
