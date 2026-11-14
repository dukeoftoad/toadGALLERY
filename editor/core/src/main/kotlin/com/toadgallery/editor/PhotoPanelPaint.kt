package com.toadgallery.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// The paint family's tool panels. Paint modes (Draw/Region/Erase) are mutually exclusive;
// plain state tunes the NEXT stroke/region, a selected layer re-binds the controls via live-draft.

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

    // ---- Drawing (the freehand tool) ----
    // Draw mode ON tunes the NEXT stroke (plain state; the live gesture previews via a
    // transient draft layer); a selected layer re-binds the SAME controls (live-draft).
    // The two states are exclusive; ONE selection at a time across ALL families.
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
                    // Entering paint mode drops every selection and exits the other
                    // paint modes (Draw/Region/Erase are mutually exclusive).
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
                        onRegionModeChange(false) // so are the paint modes
                        onEraseModeChange(false)
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
        // -- The brush (tunes the NEXT stroke): plain state applied at commit;
        //    the live gesture previews it through the draft layer.
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
        // LabeledSlider: label + live value pill + double-tap-to-neutral;
        // every tick writes the chassis state (plain state, no draft).
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
        )
    } else if (selectedDrawLayer != null) {
        val layerId = selectedDrawLayer.id
        // -- Per-layer re-edit (live-draft; the "draw-" key prefix routes
        //    through effectiveRecipe()). Controls re-bind to the layer.
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
        // Per-layer live-draft: tick drafts, release commits through
        // commitSelectedDrawLayer, double-tap returns to the control's neutral.
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_draw_width),
            label = stringResource(R.string.editor_draw_width, widthLive),
            valueText = widthLive.toString(),
            value = widthLive.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("draw-width@$layerId" to it) },
            onFinish = {
                draft?.let { d ->
                    if (d.first == "draw-width@$layerId") {
                        commitSelectedDrawLayer { it.copy(widthPct = d.second.coerceIn(1, 100)) }
                    }
                }
                onDraftChange(null)
            },
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
                draft?.let { d ->
                    if (d.first == "draw-opacity@$layerId") {
                        commitSelectedDrawLayer { it.copy(opacity = d.second.coerceIn(0, 100)) }
                    }
                }
                onDraftChange(null)
            },
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
                draft?.let { d ->
                    if (d.first == "draw-smoothing@$layerId") {
                        commitSelectedDrawLayer {
                            it.copy(smoothingPct = d.second.coerceIn(0, 100))
                        }
                    }
                }
                onDraftChange(null)
            },
            resetValue = 0,
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

    // ---- Regions (the mosaic/blur pixel stage) ----
    // Region-mode chip (mutually exclusive with Draw mode) + region layer chips.
    // Paint mode tunes the NEXT region (kind + strength, plain state, previewed live
    // through the transient draft region); a selected region re-binds the same
    // controls (live-draft). Previews AT PAINT TIME: the draft region rides the
    // effective recipe through the same pipeline stage the export runs.
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
                    // Entering region paint mode drops every selection and exits
                    // the other paint modes.
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
        // Plain state: every tick writes the chassis regionStrength (no draft).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_region_strength),
            label = stringResource(R.string.editor_region_strength, regionStrength),
            valueText = regionStrength.toString(),
            value = regionStrength.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onRegionStrengthChange(it.coerceIn(1, 100)) },
            resetValue = 50,
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
        // Per-layer live-draft: tick drafts, release commits through
        // commitSelectedRegionLayer, double-tap returns to the control's neutral.
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_region_strength),
            label = stringResource(R.string.editor_region_strength, strengthLive),
            valueText = strengthLive.toString(),
            value = strengthLive.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("region-strength@$layerId" to it) },
            onFinish = {
                draft?.let { d ->
                    if (d.first == "region-strength@$layerId") {
                        commitSelectedRegionLayer { it.copy(strengthPct = d.second.coerceIn(1, 100)) }
                    }
                }
                onDraftChange(null)
            },
            resetValue = 50,
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
