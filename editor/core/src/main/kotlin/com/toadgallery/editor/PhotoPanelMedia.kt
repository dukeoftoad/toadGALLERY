package com.toadgallery.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Media-registry tool panels: the Looks card shelf (thumbs render through the real preview/
// export pipeline) and the Frames chip row. Import machinery + draft contracts unchanged.

@Composable
internal fun LooksPanel(
    activity: EditActivity,
    uiScope: CoroutineScope,
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    looks: Map<String, CubeLut>,
    onLooksVersionChange: () -> Unit,
    importError: String?,
    importLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    previewBase: Bitmap? = null,
) {

    // ---- The card shelf ----
    // None card + one per parsed look; preview and export resolve through the same shared
    // cache, so the shown thumb is what exports. An unresolvable selection selects no card.

    // Import action: SAF-pick a .cube (local documents only).
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        AssistChip(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !exporting,
            label = { Text(stringResource(R.string.editor_filter_import)) },
        )
    }
    importError?.let { reason ->
        Text(
            text = stringResource(R.string.editor_filter_import_failed, reason),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }

    val selection = committed.lut
    val bundledIds = remember { BundledLuts.ORDERED.map { it.id }.toSet() }
    val importedIds = looks.keys.filter { it !in bundledIds }.sorted()
    // Compare raster: plain base rendered once, shared by every card's press; null base -> null.
    val plainThumb = remember(previewBase) { renderPlainCardThumb(previewBase) }

    // Shelf order: None, bundled looks (ORDERED), imported (id-sorted).
    val shelf: List<Pair<String, CubeLut?>> =
        listOf(stringResource(R.string.editor_filter_none) to null) +
            BundledLuts.ORDERED
                .filter { looks.containsKey(it.id) }
                .map { entry -> stringResource(lookNameRes(entry.id)) to looks.getValue(entry.id) } +
            importedIds.map { id ->
                val look = looks.getValue(id)
                (look.title.ifBlank { id }) to look
            }

    // Two-column non-lazy grid (small count; the deck column already scrolls); a lone last
    // card keeps its half via a trailing Spacer.
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shelf.chunked(2).forEach { rowCards ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, lut) in rowCards) {
                    LookCard(
                        label = label,
                        selected = if (lut == null) {
                            selection == null
                        } else {
                            selection?.lutId == lut.id
                        },
                        enabled = !exporting,
                        thumb = if (lut == null) {
                            // The None card previews the photo itself (no look = plain original).
                            plainThumb
                        } else {
                            remember(lut.id, previewBase) {
                                renderLookCardThumb(lut, previewBase)
                            }
                        },
                        pressedThumb = if (lut == null) null else plainThumb,
                        onPick = {
                            if (lut == null) {
                                commit(committed.copy(lut = null))
                            } else {
                                // A new look keeps the current intensity.
                                commit(
                                    committed.copy(
                                        lut = LutSelection(
                                            lutId = lut.id,
                                            intensity = selection?.intensity ?: 100,
                                        ),
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowCards.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
    if (selection != null && looks.containsKey(selection.lutId)) {
        // Intensity slider: draft previews, release commits; double-tap resets to 100.
        val live =
            if (draft?.first == "lut-intensity") draft!!.second else selection.intensity
        val intensityLabel = stringResource(R.string.editor_filter_intensity, live)
        val intensityName = stringResource(R.string.editor_a11y_filter_intensity)
        ProSlider(
            name = intensityName,
            label = intensityName,
            state = intensityLabel,
            valueText = stringResource(R.string.editor_r67_percent_value, live),
            value = live.toFloat(),
            valueRange = 0f..100f,
            neutral = 100f,
            enabled = !exporting,
            onChange = { onDraftChange(Pair("lut-intensity", it.toInt())) },
            onFinish = {
                draft?.let { d ->
                    if (d.first == "lut-intensity" && committed.lut != null) {
                        commit(committed.copy(lut = committed.lut!!.copy(intensity = d.second)))
                    }
                }
                onDraftChange(null)
            },
        )
        // Only imported looks are removable; removal clears the selection and reloads the
        // registry. A sidecar naming the removed look degrades to no-look.
        if (selection.lutId !in bundledIds) {
            TextButton(
                onClick = {
                    val removedId = selection.lutId
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            activity.importedLutStore.delete(removedId)
                        }
                        onLooksVersionChange()
                        commit(committed.copy(lut = null))
                    }
                },
                enabled = !exporting,
            ) {
                Text(stringResource(R.string.editor_filter_remove))
            }
        }
    }
}

@Composable
internal fun FramesPanel(
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    sweepPendingDrafts: () -> Unit,
    importedFrames: Map<String, Bitmap>,
    frameImportError: String?,
    frameImportLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
) {

    // ---- Frames ----
    // None chip, bundled chips, imported chips, SAF import; width slider + swatches for
    // parametric frames. An imported frame is just its bitmap (no controls, no gestures).
    Text(
        text = stringResource(R.string.editor_section_frames),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    // Frame chips carry honest thumbnails: real FrameCompositor geometry tinted with the
    // current frame color (auditioning a color re-renders). Imported frames show their bitmap.
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FrameThumbChip(
            label = stringResource(R.string.editor_frame_none),
            selected = committed.frame == null,
            enabled = !exporting,
            thumb = null,
            onPick = { sweepPendingDrafts(); commit(committed.copy(frame = null)) },
        )
        BundledFrames.ORDERED.forEach { entry ->
            val thumbColor = committed.frame?.colorArgb ?: FrameSelection.DEFAULT_COLOR
            FrameThumbChip(
                label = entry.displayName,
                selected = committed.frame?.frameId == entry.id,
                enabled = !exporting,
                thumb = remember(entry.id, thumbColor) { renderFrameThumb(entry.id, thumbColor) },
                onPick = {
                    sweepPendingDrafts()
                    // A new frame keeps the current width and colour.
                    commit(
                        committed.copy(
                            frame = FrameSelection(
                                frameId = entry.id,
                                widthPct = committed.frame?.widthPct ?: FrameSelection.DEFAULT_WIDTH_PCT,
                                colorArgb = committed.frame?.colorArgb ?: FrameSelection.DEFAULT_COLOR,
                            ),
                        ),
                    )
                },
            )
        }
        // User-imported frames, id-sorted; keys are disjoint from the bundled set.
        importedFrames.keys.sorted().forEach { id ->
            FrameThumbChip(
                label = id,
                selected = committed.frame?.frameId == id,
                enabled = !exporting,
                thumb = importedFrames[id],
                onPick = {
                    sweepPendingDrafts()
                    commit(
                        committed.copy(
                            frame = FrameSelection(
                                frameId = id,
                                widthPct = committed.frame?.widthPct ?: FrameSelection.DEFAULT_WIDTH_PCT,
                                colorArgb = committed.frame?.colorArgb ?: FrameSelection.DEFAULT_COLOR,
                            ),
                        ),
                    )
                },
            )
        }
        AssistChip(
            onClick = { frameImportLauncher.launch(arrayOf("image/png", "image/webp")) },
            enabled = !exporting,
            label = { Text(stringResource(R.string.editor_frame_import)) },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    frameImportError?.let { reason ->
        Text(
            text = stringResource(R.string.editor_frame_import_failed, reason),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    val frameSelection = committed.frame
    if (frameSelection != null) {
        if (BundledFrames.byId(frameSelection.frameId) != null) {
            // Parametric controls: width (single-selection draft key) + swatches (immediate commits).
            val widthLive = draft?.takeIf { it.first == "frame-width" }?.second
                ?: frameSelection.widthPct
            val widthName = stringResource(R.string.editor_a11y_frame_width)
            val widthLabel = stringResource(R.string.editor_frame_width, widthLive)
            // Crafted slider row: live value pill; double-tap resets to the default width.
            LabeledSlider(
                name = widthName,
                label = widthLabel,
                valueText = widthLive.toString(),
                value = widthLive.toFloat(),
                valueRange = 1f..100f,
                enabled = !exporting,
                onChange = { onDraftChange("frame-width" to it)},
                onFinish = {
                    draft?.let { d ->
                        if (d.first == "frame-width" && committed.frame != null) {
                            commit(
                                committed.copy(
                                    frame = committed.frame!!.copy(widthPct = d.second.coerceIn(1, 100)),
                                ),
                            )
                        }
                    }
                    onDraftChange(null)
                },
                resetValue = FrameSelection.DEFAULT_WIDTH_PCT,
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                TEXT_COLORS.forEach { (colorArgb, nameRes) ->
                    ColorSwatch(
                        colorArgb = colorArgb,
                        selected = frameSelection.colorArgb == colorArgb,
                        name = stringResource(nameRes),
                        enabled = !exporting,
                        onPick = {
                            commit(
                                committed.copy(
                                    frame = frameSelection.copy(colorArgb = colorArgb),
                                ),
                            )
                        },
                    )
                }
            }
        } else if (importedFrames.containsKey(frameSelection.frameId)) {
            // An imported frame: the bitmap is the frame; no width/colour controls.
            Text(
                text = stringResource(R.string.editor_frame_imported_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // An unresolvable id (version skew, deleted import) selects no chip.
    }
}


/** The bundled look's display name; ids are the BundledLuts registry ids. */
private fun lookNameRes(id: String): Int = when (id) {
    "warm-film" -> R.string.editor_filter_warm_film
    "cool-chrome" -> R.string.editor_filter_cool_chrome
    "silver-mono" -> R.string.editor_filter_silver_mono
    "matte-fade" -> R.string.editor_filter_matte_fade
    "vivid-punch" -> R.string.editor_filter_vivid_punch
    else -> R.string.editor_filter_none
}
