package com.toadgallery.editor

import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// Layer-family tool panels (text, stickers): stateless chassis receiving explicit
// value + on...Change pairs; one selected layer at a time across the families.

@Composable
internal fun TextPanel(
    activity: EditActivity,
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    sweepPendingDrafts: () -> Unit,
    commitSelectedLayer: ((TextLayer) -> TextLayer) -> Unit,
    commitTextDraftIfPending: () -> Unit,
    textDraft: Pair<String, String>?,
    onTextDraftChange: (Pair<String, String>?) -> Unit,
    selectedTextLayerId: String?,
    onSelectTextLayerChange: (String?) -> Unit,
    selectedStickerLayerId: String?,
    onSelectStickerLayerChange: (String?) -> Unit,
    selectedRegionLayerId: String?,
    onSelectRegionLayerChange: (String?) -> Unit,
) {

    // ---- Text layers ----
    // Add chip -> layer list -> per-layer controls. Repositioning is the preview drag only
    // (no slider, so not TalkBack-reachable in v1).
    Text(
        text = stringResource(R.string.editor_section_text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        AssistChip(
            onClick = {
                sweepPendingDrafts()
                val existing = committed.textLayers?.layers ?: emptyList()
                val newLayer = TextLayer(
                    id = TextLayer.newId(existing.map { it.id }),
                    text = "",
                    fontId = BundledFonts.DEFAULT_ID,
                )
                commit(
                    committed.copy(textLayers = TextLayers(existing + newLayer)),
                )
                onSelectTextLayerChange(newLayer.id)
                // The new layer becomes the selection: one selected layer across the families.
                onSelectStickerLayerChange(null)
                onSelectRegionLayerChange(null)
            },
            enabled = !exporting,
            label = { Text(stringResource(R.string.editor_text_add)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        committed.textLayers?.layers?.forEachIndexed { index, layer ->
            FilterChip(
                selected = selectedTextLayerId == layer.id,
                onClick = {
                    if (selectedTextLayerId != layer.id) {
                        sweepPendingDrafts()
                        onSelectTextLayerChange(layer.id)
                        // One selection across the families.
                        onSelectStickerLayerChange(null)
                        onSelectRegionLayerChange(null)
                    }
                },
                enabled = !exporting,
                label = {
                    val fallback = stringResource(R.string.editor_text_layer, index + 1)
                    val label = layer.text.ifBlank { fallback }
                    Text(if (label.length > 16) label.take(15) + "…" else label)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    val selectedTextLayer = committed.textLayers?.layers
        ?.firstOrNull { it.id == selectedTextLayerId }
    if (selectedTextLayer != null) {
        val layerId = selectedTextLayer.id
        // Text field drafts locally (per-keystroke commits flood the undo stack); commits on
        // IME Done, target switch, add/remove, and before export.
        val fieldText = textDraft?.takeIf { it.first == layerId }?.second
            ?: selectedTextLayer.text
        val textContentName = stringResource(R.string.editor_a11y_text_content)
        val fieldHint = stringResource(R.string.editor_text_field_hint)
        OutlinedTextField(
            value = fieldText,
            onValueChange = { value ->
                onTextDraftChange(layerId to value.take(TextLayer.MAX_TEXT_LENGTH))
            },
            enabled = !exporting,
            singleLine = true,
            placeholder = { Text(fieldHint, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                capitalization = KeyboardCapitalization.Sentences,
            ),
            keyboardActions = KeyboardActions(onDone = { commitTextDraftIfPending() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = textContentName },
        )
        // Font chips preview the real bundled Typeface (one code path with the renderer);
        // an unresolvable fontId selects no chip.
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            BundledFonts.ORDERED.forEach { entry ->
                FontPreviewChip(
                    label = entry.displayName,
                    selected = selectedTextLayer.fontId == entry.id,
                    enabled = !exporting,
                    typeface = activity.fontFor(entry.id),
                    onPick = { commitSelectedLayer { it.copy(fontId = entry.id) } },
                )
            }
        }
        // -- size / opacity / rotation: the live-draft pattern.
        val sizeLive = draft?.takeIf { it.first == "text-size@$layerId" }?.second
            ?: selectedTextLayer.sizePct
        val sizeLabel = stringResource(R.string.editor_text_size, sizeLive)
        val sizeName = stringResource(R.string.editor_a11y_text_size)
        // Crafted slider row: live value pill; double-tap resets to the layer default.
        LabeledSlider(
            name = sizeName,
            label = sizeLabel,
            valueText = sizeLive.toString(),
            value = sizeLive.toFloat(),
            valueRange = 0f..300f,
            enabled = !exporting,
            onChange = { onDraftChange("text-size@$layerId" to it)},
            onFinish = {
                draft?.let { d ->
                    if (d.first == "text-size@$layerId") {
                        commitSelectedLayer { it.copy(sizePct = d.second.coerceIn(0, 300)) }
                    }
                }
                onDraftChange(null)
            },
            resetValue = TextLayer.DEFAULT_SIZE_PCT,
        )
        val opacityLive = draft?.takeIf { it.first == "text-opacity@$layerId" }?.second
            ?: selectedTextLayer.opacity
        val opacityLabel = stringResource(R.string.editor_text_opacity, opacityLive)
        val opacityName = stringResource(R.string.editor_a11y_text_opacity)
        LabeledSlider(
            name = opacityName,
            label = opacityLabel,
            valueText = opacityLive.toString(),
            value = opacityLive.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("text-opacity@$layerId" to it)},
            onFinish = {
                draft?.let { d ->
                    if (d.first == "text-opacity@$layerId") {
                        commitSelectedLayer { it.copy(opacity = d.second.coerceIn(0, 100)) }
                    }
                }
                onDraftChange(null)
            },
            resetValue = 100,
        )
        val rotationLive = draft?.takeIf { it.first == "text-rotation@$layerId" }?.second
            ?: selectedTextLayer.rotationDeg
        val rotationLabel = stringResource(R.string.editor_text_rotation, rotationLive)
        val rotationName = stringResource(R.string.editor_a11y_text_rotation)
        LabeledSlider(
            name = rotationName,
            label = rotationLabel,
            valueText = rotationLive.toString(),
            value = rotationLive.toFloat(),
            valueRange = -180f..180f,
            enabled = !exporting,
            onChange = { onDraftChange("text-rotation@$layerId" to it)},
            onFinish = {
                draft?.let { d ->
                    if (d.first == "text-rotation@$layerId") {
                        commitSelectedLayer {
                            it.copy(rotationDeg = d.second.coerceIn(-180, 180))
                        }
                    }
                }
                onDraftChange(null)
            },
            resetValue = 0,
        )
        // Color swatches: fixed named palette, a11y-labelled; radio semantics, one color at a time.
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TEXT_COLORS.forEach { (colorArgb, nameRes) ->
                ColorSwatch(
                    colorArgb = colorArgb,
                    selected = selectedTextLayer.colorArgb == colorArgb,
                    name = stringResource(nameRes),
                    enabled = !exporting,
                    onPick = {
                        commitSelectedLayer { it.copy(colorArgb = colorArgb) }
                    },
                )
            }
        }
        TextButton(
            onClick = {
                sweepPendingDrafts()
                val remaining = (committed.textLayers?.layers ?: emptyList())
                    .filterNot { it.id == layerId }
                commit(
                    committed.copy(
                        textLayers = if (remaining.isEmpty()) {
                            null
                        } else {
                            TextLayers(remaining)
                        },
                    ),
                )
                onSelectTextLayerChange(remaining.lastOrNull()?.id)
            },
            enabled = !exporting,
        ) {
            Text(stringResource(R.string.editor_text_remove))
        }
    }
}

@Composable
internal fun StickersPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    sweepPendingDrafts: () -> Unit,
    commitSelectedStickerLayer: ((StickerLayer) -> StickerLayer) -> Unit,
    importedStickers: Map<String, Bitmap>,
    stickerImportError: String?,
    stickerImportLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    selectedTextLayerId: String?,
    onSelectTextLayerChange: (String?) -> Unit,
    selectedStickerLayerId: String?,
    onSelectStickerLayerChange: (String?) -> Unit,
    selectedRegionLayerId: String?,
    onSelectRegionLayerChange: (String?) -> Unit,
) {

    // ---- Stickers ----
    // Registry chips ADD a layer at canvas centre; they are not selections (the layer chips
    // below carry it). One selection across the families; drag + per-layer controls follow it.
    Text(
        text = stringResource(R.string.editor_section_stickers),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        BundledStickers.ORDERED.forEach { entry ->
            FilterChip(
                selected = false,
                onClick = {
                    sweepPendingDrafts()
                    val existing = committed.stickerLayers?.layers ?: emptyList()
                    val newLayer = StickerLayer(
                        id = StickerLayer.newId(existing.map { it.id }),
                        stickerId = entry.id,
                    )
                    commit(
                        committed.copy(stickerLayers = StickerLayers(existing + newLayer)),
                    )
                    onSelectStickerLayerChange(newLayer.id)
                    onSelectTextLayerChange(null)
                    onSelectRegionLayerChange(null)
                },
                enabled = !exporting,
                label = { Text(entry.displayName) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        // User-imported stickers, id-sorted; keys are disjoint from the bundled set.
        importedStickers.keys.sorted().forEach { id ->
            FilterChip(
                selected = false,
                onClick = {
                    sweepPendingDrafts()
                    val existing = committed.stickerLayers?.layers ?: emptyList()
                    val newLayer = StickerLayer(
                        id = StickerLayer.newId(existing.map { it.id }),
                        stickerId = id,
                    )
                    commit(
                        committed.copy(stickerLayers = StickerLayers(existing + newLayer)),
                    )
                    onSelectStickerLayerChange(newLayer.id)
                    onSelectTextLayerChange(null)
                    onSelectRegionLayerChange(null)
                },
                enabled = !exporting,
                label = { Text(id) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        // Import action: SAF-pick a PNG/WebP (local documents only).
        AssistChip(
            onClick = { stickerImportLauncher.launch(arrayOf("image/png", "image/webp")) },
            enabled = !exporting,
            label = { Text(stringResource(R.string.editor_sticker_import)) },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    stickerImportError?.let { reason ->
        Text(
            text = stringResource(R.string.editor_sticker_import_failed, reason),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    // Layer chips show the sticker display name; raw id for imports or unresolvable ids.
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        committed.stickerLayers?.layers?.forEach { layer ->
            FilterChip(
                selected = selectedStickerLayerId == layer.id,
                onClick = {
                    if (selectedStickerLayerId != layer.id) {
                        sweepPendingDrafts()
                        onSelectStickerLayerChange(layer.id)
                        onSelectTextLayerChange(null)
                        onSelectRegionLayerChange(null)
                    }
                },
                enabled = !exporting,
                label = {
                    val label = BundledStickers.byId(layer.stickerId)?.displayName
                        ?: layer.stickerId
                    Text(if (label.length > 16) label.take(15) + "…" else label)
                },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    val selectedStickerLayer = committed.stickerLayers?.layers
        ?.firstOrNull { it.id == selectedStickerLayerId }
    if (selectedStickerLayer != null) {
        val layerId = selectedStickerLayer.id
        // size / opacity / rotation live-draft; keys carry the sticker- prefix so effectiveRecipe() routes them.
        val sizeLive = draft?.takeIf { it.first == "sticker-size@$layerId" }?.second
            ?: selectedStickerLayer.sizePct
        val sizeLabel = stringResource(R.string.editor_sticker_size, sizeLive)
        val sizeName = stringResource(R.string.editor_a11y_sticker_size)
        // Crafted slider row: live value pill; double-tap resets to the layer default.
        LabeledSlider(
            name = sizeName,
            label = sizeLabel,
            valueText = sizeLive.toString(),
            value = sizeLive.toFloat(),
            valueRange = 0f..300f,
            enabled = !exporting,
            onChange = { onDraftChange("sticker-size@$layerId" to it)},
            onFinish = {
                draft?.let { d ->
                    if (d.first == "sticker-size@$layerId") {
                        commitSelectedStickerLayer { it.copy(sizePct = d.second.coerceIn(0, 300)) }
                    }
                }
                onDraftChange(null)
            },
            resetValue = StickerLayer.DEFAULT_SIZE_PCT,
        )
        val opacityLive = draft?.takeIf { it.first == "sticker-opacity@$layerId" }?.second
            ?: selectedStickerLayer.opacity
        val opacityLabel = stringResource(R.string.editor_sticker_opacity, opacityLive)
        val opacityName = stringResource(R.string.editor_a11y_sticker_opacity)
        LabeledSlider(
            name = opacityName,
            label = opacityLabel,
            valueText = opacityLive.toString(),
            value = opacityLive.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("sticker-opacity@$layerId" to it)},
            onFinish = {
                draft?.let { d ->
                    if (d.first == "sticker-opacity@$layerId") {
                        commitSelectedStickerLayer { it.copy(opacity = d.second.coerceIn(0, 100)) }
                    }
                }
                onDraftChange(null)
            },
            resetValue = 100,
        )
        val rotationLive = draft?.takeIf { it.first == "sticker-rotation@$layerId" }?.second
            ?: selectedStickerLayer.rotationDeg
        val rotationLabel = stringResource(R.string.editor_sticker_rotation, rotationLive)
        val rotationName = stringResource(R.string.editor_a11y_sticker_rotation)
        LabeledSlider(
            name = rotationName,
            label = rotationLabel,
            valueText = rotationLive.toString(),
            value = rotationLive.toFloat(),
            valueRange = -180f..180f,
            enabled = !exporting,
            onChange = { onDraftChange("sticker-rotation@$layerId" to it)},
            onFinish = {
                draft?.let { d ->
                    if (d.first == "sticker-rotation@$layerId") {
                        commitSelectedStickerLayer {
                            it.copy(rotationDeg = d.second.coerceIn(-180, 180))
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
                val remaining = (committed.stickerLayers?.layers ?: emptyList())
                    .filterNot { it.id == layerId }
                commit(
                    committed.copy(
                        stickerLayers = if (remaining.isEmpty()) {
                            null
                        } else {
                            StickerLayers(remaining)
                        },
                    ),
                )
                onSelectStickerLayerChange(remaining.lastOrNull()?.id)
            },
            enabled = !exporting,
        ) {
            Text(stringResource(R.string.editor_sticker_remove))
        }
    }
}
