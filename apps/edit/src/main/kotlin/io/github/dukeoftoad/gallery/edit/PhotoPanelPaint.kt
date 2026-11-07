package io.github.dukeoftoad.gallery.edit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
<<<<<<< HEAD
=======
import androidx.compose.material3.Slider
>>>>>>> origin/main
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
<<<<<<< HEAD
=======
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
>>>>>>> origin/main
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// The r57 panel seam (F-58a): the paint family's tool panels, extracted
// VERBATIM from `EditActivity.EditorContent`'s `PhotoScreen.Draw` and
// `PhotoScreen.Regions` branches.
//
// The bodies are the r36 draw and r37/r38 region contracts --- the paint-mode
// duality (plain state tunes the NEXT stroke/region; a selected layer
// re-binds the same controls through the live-draft pattern; Draw/Region/
// Erase mutually exclusive) --- unchanged; the chassis state arrives as
// explicit value + `on...Change` pairs. The committed Roborazzi chassis
// baselines are the regression proof.
<<<<<<< HEAD
//
// r58b (F-58b): every raw Slider in both panels converts to the r56
// `LabeledSlider` --- label + live value pill + double-tap-to-neutral.
// The r36 plain-state contract (every tick writes the chassis state) and
// the r36 per-layer live-draft contract (tick writes the draft, release
// commits through `commitSelectedDrawLayer`/`commitSelectedRegionLayer`)
// are verbatim; only the control's VISUAL shape changes (the pill makes
// the number readable, the double-tap replaces the drag-back-to-zero hunt).
=======
>>>>>>> origin/main
// ---------------------------------------------------------------------------

@Composable
internal fun DrawPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    sweepPendingDrafts: () -> Unit,
    commitSelectedDrawLayer: ((DrawLayer) -> DrawLayer) -> Unit,
    drawMode: Boolean,
    onDrawModeChange: (Boolean) -> Unit,
    regionMode: Boolean,
    onRegionModeChange: (Boolean) -> Unit,
    eraseMode: Boolean,
    onEraseModeChange: (Boolean) -> Unit,
    brushColor: Long,
    onBrushColorChange: (Long) -> Unit,
    brushWidth: Int,
    onBrushWidthChange: (Int) -> Unit,
    brushOpacity: Int,
    onBrushOpacityChange: (Int) -> Unit,
    brushSmoothing: Int,
    onBrushSmoothingChange: (Int) -> Unit,
    selectedTextLayerId: String?,
    onSelectTextLayerChange: (String?) -> Unit,
    selectedStickerLayerId: String?,
    onSelectStickerLayerChange: (String?) -> Unit,
    selectedDrawLayerId: String?,
    onSelectDrawLayerChange: (String?) -> Unit,
    selectedRegionLayerId: String?,
    onSelectRegionLayerChange: (String?) -> Unit,
) {

    // ---- Drawing (r36: the freehand tool) ----
    // The contract's UI shape (drawing-tool.md decision 6): the
    // Draw-mode chip + the stroke layer chips, then the controls
    // under the duality --- Draw mode ON tunes the NEXT stroke
    // (the brush, plain state; the live gesture previews through
    // a transient draft layer); a selected layer re-binds the SAME
    // controls to it through the live-draft pattern. The two states
    // are exclusive; ONE selection at a time across ALL the
    // families (the r35 contract extended).
    Text(
        text = stringResource(R.string.editor_section_drawing),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(
            selected = drawMode,
            onClick = {
                sweepPendingDrafts()
                onDrawModeChange(!drawMode)
                if (drawMode) {
                    // Entering paint mode drops every selection ---
                    // painting owns the gesture; the user keeps
                    // painting across strokes. The r37 twin grew to
                    // THREE paint modes in r41 (Draw/Region/Erase):
                    // mutually exclusive, enforced at every toggle.
                    onRegionModeChange(false)
                    onEraseModeChange(false)
                    onSelectTextLayerChange(null)
                    onSelectStickerLayerChange(null)
                    onSelectDrawLayerChange(null)
                    onSelectRegionLayerChange(null)
                }
            },
            enabled = !exporting,
            label = { Text(stringResource(R.string.editor_draw_mode)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        committed.drawLayers?.layers?.forEachIndexed { index, layer ->
            FilterChip(
                selected = selectedDrawLayerId == layer.id,
                onClick = {
                    if (selectedDrawLayerId != layer.id) {
                        sweepPendingDrafts()
                        onSelectDrawLayerChange(layer.id)
                        onDrawModeChange(false) // the duality is exclusive
                        onRegionModeChange(false) // and so are the paint modes
                        onEraseModeChange(false) // all three of them (r41)
                        onSelectTextLayerChange(null)
                        onSelectStickerLayerChange(null)
                        onSelectRegionLayerChange(null)
                    }
                },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_draw_layer, index + 1)) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    val selectedDrawLayer = committed.drawLayers?.layers
        ?.firstOrNull { it.id == selectedDrawLayerId }
    if (drawMode) {
        // -- The brush (tunes the NEXT stroke): the swatches and
        //    sliders are plain state, applied at commit; the live
        //    gesture previews them through the draft layer.
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TEXT_COLORS.forEach { (colorArgb, nameRes) ->
                ColorSwatch(
                    colorArgb = colorArgb,
                    selected = brushColor == colorArgb,
                    name = stringResource(nameRes),
                    enabled = !exporting,
                    onPick = { onBrushColorChange(colorArgb)},
                )
            }
        }
<<<<<<< HEAD
        // r58b: the crafted LabeledSlider (r56) --- label + live value
        // pill + double-tap-to-neutral. The r36 plain-state contract is
        // verbatim (every tick writes the chassis state; no draft).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_draw_width),
            label = stringResource(R.string.editor_draw_width, brushWidth),
            valueText = brushWidth.toString(),
            value = brushWidth.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onBrushWidthChange(it.coerceIn(1, 100)) },
            resetValue = 10,
        )
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_draw_opacity),
            label = stringResource(R.string.editor_draw_opacity, brushOpacity),
            valueText = brushOpacity.toString(),
            value = brushOpacity.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onBrushOpacityChange(it.coerceIn(0, 100)) },
            resetValue = 100,
        )
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_draw_smoothing),
            label = stringResource(R.string.editor_draw_smoothing, brushSmoothing),
            valueText = brushSmoothing.toString(),
            value = brushSmoothing.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onBrushSmoothingChange(it.coerceIn(0, 100)) },
            resetValue = 0,
=======
        val widthName = stringResource(R.string.editor_a11y_draw_width)
        val widthLabel = stringResource(R.string.editor_draw_width, brushWidth)
        Text(
            text = widthLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = brushWidth.toFloat(),
            onValueChange = { onBrushWidthChange(it.toInt().coerceIn(1, 100))},
            valueRange = 1f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = widthName
                stateDescription = widthLabel
            },
        )
        val opacityName = stringResource(R.string.editor_a11y_draw_opacity)
        val opacityLabel = stringResource(R.string.editor_draw_opacity, brushOpacity)
        Text(
            text = opacityLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = brushOpacity.toFloat(),
            onValueChange = { onBrushOpacityChange(it.toInt().coerceIn(0, 100))},
            valueRange = 0f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = opacityName
                stateDescription = opacityLabel
            },
        )
        val smoothingName = stringResource(R.string.editor_a11y_draw_smoothing)
        val smoothingLabel = stringResource(R.string.editor_draw_smoothing, brushSmoothing)
        Text(
            text = smoothingLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = brushSmoothing.toFloat(),
            onValueChange = { onBrushSmoothingChange(it.toInt().coerceIn(0, 100))},
            valueRange = 0f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = smoothingName
                stateDescription = smoothingLabel
            },
>>>>>>> origin/main
        )
    } else if (selectedDrawLayer != null) {
        val layerId = selectedDrawLayer.id
        // -- Per-layer re-edit (the live-draft pattern; the keys
        //    carry the draw- prefix so effectiveRecipe() routes
        //    them). The swatches and sliders re-bind to the layer.
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TEXT_COLORS.forEach { (colorArgb, nameRes) ->
                ColorSwatch(
                    colorArgb = colorArgb,
                    selected = selectedDrawLayer.colorArgb == colorArgb,
                    name = stringResource(nameRes),
                    enabled = !exporting,
                    onPick = {
                        commitSelectedDrawLayer { it.copy(colorArgb = colorArgb) }
                    },
                )
            }
        }
        val widthLive = draft?.takeIf { it.first == "draw-width@$layerId" }?.second
            ?: selectedDrawLayer.widthPct
<<<<<<< HEAD
        // r58b: the per-layer live-draft pattern on the crafted LabeledSlider;
        // the r36 contract is verbatim (tick drafts, release commits through
        // commitSelectedDrawLayer, double-tap returns to the control's neutral).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_draw_width),
            label = stringResource(R.string.editor_draw_width, widthLive),
            valueText = widthLive.toString(),
            value = widthLive.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("draw-width@$layerId" to it) },
            onFinish = {
=======
        val widthName = stringResource(R.string.editor_a11y_draw_width)
        val widthLabel = stringResource(R.string.editor_draw_width, widthLive)
        Text(
            text = widthLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = widthLive.toFloat(),
            onValueChange = { onDraftChange("draw-width@$layerId" to it.toInt())},
            onValueChangeFinished = {
>>>>>>> origin/main
                draft?.let { d ->
                    if (d.first == "draw-width@$layerId") {
                        commitSelectedDrawLayer { it.copy(widthPct = d.second.coerceIn(1, 100)) }
                    }
                }
                onDraftChange(null)
            },
<<<<<<< HEAD
            resetValue = 10,
        )
        val opacityLive = draft?.takeIf { it.first == "draw-opacity@$layerId" }?.second
            ?: selectedDrawLayer.opacity
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_draw_opacity),
            label = stringResource(R.string.editor_draw_opacity, opacityLive),
            valueText = opacityLive.toString(),
            value = opacityLive.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("draw-opacity@$layerId" to it) },
            onFinish = {
=======
            valueRange = 1f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = widthName
                stateDescription = widthLabel
            },
        )
        val opacityLive = draft?.takeIf { it.first == "draw-opacity@$layerId" }?.second
            ?: selectedDrawLayer.opacity
        val opacityName = stringResource(R.string.editor_a11y_draw_opacity)
        val opacityLabel = stringResource(R.string.editor_draw_opacity, opacityLive)
        Text(
            text = opacityLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = opacityLive.toFloat(),
            onValueChange = { onDraftChange("draw-opacity@$layerId" to it.toInt())},
            onValueChangeFinished = {
>>>>>>> origin/main
                draft?.let { d ->
                    if (d.first == "draw-opacity@$layerId") {
                        commitSelectedDrawLayer { it.copy(opacity = d.second.coerceIn(0, 100)) }
                    }
                }
                onDraftChange(null)
            },
<<<<<<< HEAD
            resetValue = 100,
        )
        val smoothingLive = draft?.takeIf { it.first == "draw-smoothing@$layerId" }?.second
            ?: selectedDrawLayer.smoothingPct
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_draw_smoothing),
            label = stringResource(R.string.editor_draw_smoothing, smoothingLive),
            valueText = smoothingLive.toString(),
            value = smoothingLive.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("draw-smoothing@$layerId" to it) },
            onFinish = {
=======
            valueRange = 0f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = opacityName
                stateDescription = opacityLabel
            },
        )
        val smoothingLive = draft?.takeIf { it.first == "draw-smoothing@$layerId" }?.second
            ?: selectedDrawLayer.smoothingPct
        val smoothingName = stringResource(R.string.editor_a11y_draw_smoothing)
        val smoothingLabel = stringResource(R.string.editor_draw_smoothing, smoothingLive)
        Text(
            text = smoothingLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = smoothingLive.toFloat(),
            onValueChange = { onDraftChange("draw-smoothing@$layerId" to it.toInt())},
            onValueChangeFinished = {
>>>>>>> origin/main
                draft?.let { d ->
                    if (d.first == "draw-smoothing@$layerId") {
                        commitSelectedDrawLayer {
                            it.copy(smoothingPct = d.second.coerceIn(0, 100))
                        }
                    }
                }
                onDraftChange(null)
            },
<<<<<<< HEAD
            resetValue = 0,
=======
            valueRange = 0f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = smoothingName
                stateDescription = smoothingLabel
            },
>>>>>>> origin/main
        )
        TextButton(
            onClick = {
                sweepPendingDrafts()
                val remaining = (committed.drawLayers?.layers ?: emptyList())
                    .filterNot { it.id == layerId }
                commit(
                    committed.copy(
                        drawLayers = if (remaining.isEmpty()) {
                            null
                        } else {
                            DrawLayers(remaining)
                        },
                    ),
                )
                onSelectDrawLayerChange(remaining.lastOrNull()?.id)
            },
            enabled = !exporting,
        ) {
            Text(stringResource(R.string.editor_draw_remove))
        }
    }
}

@Composable
internal fun RegionsPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    sweepPendingDrafts: () -> Unit,
    commitSelectedRegionLayer: ((RegionLayer) -> RegionLayer) -> Unit,
    drawMode: Boolean,
    onDrawModeChange: (Boolean) -> Unit,
    regionMode: Boolean,
    onRegionModeChange: (Boolean) -> Unit,
    eraseMode: Boolean,
    onEraseModeChange: (Boolean) -> Unit,
    regionKind: RegionKind,
    onRegionKindChange: (RegionKind) -> Unit,
    regionStrength: Int,
    onRegionStrengthChange: (Int) -> Unit,
    selectedTextLayerId: String?,
    onSelectTextLayerChange: (String?) -> Unit,
    selectedStickerLayerId: String?,
    onSelectStickerLayerChange: (String?) -> Unit,
    selectedDrawLayerId: String?,
    onSelectDrawLayerChange: (String?) -> Unit,
    selectedRegionLayerId: String?,
    onSelectRegionLayerChange: (String?) -> Unit,
) {

    // ---- Regions (r37: the mosaic/blur pixel stage) ----
    // The r37 UI section, landed here as the recorded debt fix (the
    // r37 insertion script wrote the gradle file but never the
    // activity --- the section existed only in a Python variable;
    // the merged r37 carried the model, the recipe, the pipeline
    // stage and the gesture scaffolding without this surface, and
    // regionMode was unreachable. The correction rides r38; the
    // r37 delivery record gains the note).
    //
    // The contract's UI shape (mosaic-regions.md decision 6): the
    // Region-mode chip (mutually exclusive with Draw mode --- one
    // paint mode at a time, enforced at both toggles), the region
    // layer chips, and the controls under the duality: paint mode
    // tunes the NEXT region (kind chips + strength slider, plain
    // state, previewed live through the transient draft region);
    // a selected region re-binds the same controls through the
    // live-draft pattern. The effect previews AT PAINT TIME: the
    // draft region rides the effective recipe through the same
    // pipeline stage the export runs.
    Text(
        text = stringResource(R.string.editor_section_regions),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(
            selected = regionMode,
            onClick = {
                sweepPendingDrafts()
                onRegionModeChange(!regionMode)
                if (regionMode) {
                    // Entering region paint mode drops every
                    // selection and exits the other paint modes;
                    // the user keeps painting across regions.
                    onDrawModeChange(false)
                    onEraseModeChange(false)
                    onSelectTextLayerChange(null)
                    onSelectStickerLayerChange(null)
                    onSelectDrawLayerChange(null)
                    onSelectRegionLayerChange(null)
                }
            },
            enabled = !exporting,
            label = { Text(stringResource(R.string.editor_region_mode)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        committed.mosaicRegions?.layers?.forEachIndexed { index, layer ->
            FilterChip(
                selected = selectedRegionLayerId == layer.id,
                onClick = {
                    if (selectedRegionLayerId != layer.id) {
                        sweepPendingDrafts()
                        onSelectRegionLayerChange(layer.id)
                        onRegionModeChange(false)
                        onDrawModeChange(false)
                        onEraseModeChange(false)
                        onSelectTextLayerChange(null)
                        onSelectStickerLayerChange(null)
                        onSelectDrawLayerChange(null)
                    }
                },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_region_layer, index + 1)) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    val selectedRegionLayer = committed.mosaicRegions?.layers
        ?.firstOrNull { it.id == selectedRegionLayerId }
    if (regionMode) {
        // -- The next region's kind + strength (plain state; the
        //    live gesture previews them through the draft region).
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(
                selected = regionKind == RegionKind.MOSAIC,
                onClick = { onRegionKindChange(RegionKind.MOSAIC)},
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_region_mosaic)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = regionKind == RegionKind.LINEAR_BLUR,
                onClick = { onRegionKindChange(RegionKind.LINEAR_BLUR)},
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_region_blur)) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
<<<<<<< HEAD
        // r58b: the crafted LabeledSlider on the r37 plain-state contract
        // (every tick writes the chassis regionStrength; no draft).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_region_strength),
            label = stringResource(R.string.editor_region_strength, regionStrength),
            valueText = regionStrength.toString(),
            value = regionStrength.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onRegionStrengthChange(it.coerceIn(1, 100)) },
            resetValue = 50,
=======
        val strengthName = stringResource(R.string.editor_a11y_region_strength)
        val strengthLabel = stringResource(R.string.editor_region_strength, regionStrength)
        Text(
            text = strengthLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = regionStrength.toFloat(),
            onValueChange = { onRegionStrengthChange(it.toInt().coerceIn(1, 100))},
            valueRange = 1f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = strengthName
                stateDescription = strengthLabel
            },
>>>>>>> origin/main
        )
    } else if (selectedRegionLayer != null) {
        val layerId = selectedRegionLayer.id
        // -- Per-region re-edit (the live-draft pattern; chips
        //    commit immediately, the strength slider drafts).
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(
                selected = selectedRegionLayer.kind == RegionKind.MOSAIC,
                onClick = {
                    commitSelectedRegionLayer { it.copy(kind = RegionKind.MOSAIC) }
                },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_region_mosaic)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = selectedRegionLayer.kind == RegionKind.LINEAR_BLUR,
                onClick = {
                    commitSelectedRegionLayer { it.copy(kind = RegionKind.LINEAR_BLUR) }
                },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_region_blur)) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        val strengthLive = draft?.takeIf { it.first == "region-strength@$layerId" }?.second
            ?: selectedRegionLayer.strengthPct
<<<<<<< HEAD
        // r58b: the per-layer live-draft pattern on the crafted LabeledSlider;
        // the r38 contract is verbatim (tick drafts, release commits through
        // commitSelectedRegionLayer, double-tap returns to the control's neutral).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_region_strength),
            label = stringResource(R.string.editor_region_strength, strengthLive),
            valueText = strengthLive.toString(),
            value = strengthLive.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("region-strength@$layerId" to it) },
            onFinish = {
=======
        val strengthName = stringResource(R.string.editor_a11y_region_strength)
        val strengthLabel = stringResource(R.string.editor_region_strength, strengthLive)
        Text(
            text = strengthLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = strengthLive.toFloat(),
            onValueChange = { onDraftChange("region-strength@$layerId" to it.toInt())},
            onValueChangeFinished = {
>>>>>>> origin/main
                draft?.let { d ->
                    if (d.first == "region-strength@$layerId") {
                        commitSelectedRegionLayer { it.copy(strengthPct = d.second.coerceIn(1, 100)) }
                    }
                }
                onDraftChange(null)
            },
<<<<<<< HEAD
            resetValue = 50,
=======
            valueRange = 1f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = strengthName
                stateDescription = strengthLabel
            },
>>>>>>> origin/main
        )
        TextButton(
            onClick = {
                sweepPendingDrafts()
                val remaining = (committed.mosaicRegions?.layers ?: emptyList())
                    .filterNot { it.id == layerId }
                commit(
                    committed.copy(
                        mosaicRegions = if (remaining.isEmpty()) {
                            null
                        } else {
                            RegionLayers(remaining)
                        },
                    ),
                )
                onSelectRegionLayerChange(remaining.lastOrNull()?.id)
            },
            enabled = !exporting,
        ) {
            Text(stringResource(R.string.editor_region_remove))
        }
    }
}
