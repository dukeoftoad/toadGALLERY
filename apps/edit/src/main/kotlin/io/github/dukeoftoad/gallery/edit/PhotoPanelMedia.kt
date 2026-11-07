package io.github.dukeoftoad.gallery.edit

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
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

// ---------------------------------------------------------------------------
// The r57 panel seam (F-58a): the media-registry family's tool panels,
// extracted VERBATIM from `EditActivity.EditorContent`'s `PhotoScreen.Looks`
// and `PhotoScreen.Frames` branches (plus `lookNameRes`, whose only reader
// lived in the Looks branch).
//
// The bodies are the r33 looks and r38 frames contracts --- the r56 honest
// thumbs ride inside `LookThumbChip`/`FrameThumbChip` (PhotoControls.kt) ---
// unchanged; the chassis state arrives as explicit value + `on...Change`
// pairs. The committed Roborazzi chassis baselines are the regression proof.
// ---------------------------------------------------------------------------

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
) {

    // ---- Filters (r33: the bundled .cube looks at an intensity) ----
    // One None chip + one chip per look that PARSED; the preview and
    // the export resolve the selection through the same shared cache,
    // so what the user sees is what the pipeline exports. A selection
    // this build cannot resolve (a version-skewed sidecar) selects no
    // chip --- the tolerance contract, shown honestly.
    Text(
        text = stringResource(R.string.editor_section_filters),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    val selection = committed.lut
    val bundledIds = remember { BundledLuts.ORDERED.map { it.id }.toSet() }
    val importedIds = looks.keys.filter { it !in bundledIds }.sorted()
    // r56: the look chips carry HONEST thumbnails --- the
    // reference strip rendered through the SAME pipeline the
    // preview and the export use (renderLookThumb), so what a
    // chip shows is what the look does. No bitmap asset, no
    // second rendering path.
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        LookThumbChip(
            label = stringResource(R.string.editor_filter_none),
            selected = selection == null,
            enabled = !exporting,
            lut = null,
            onPick = { commit(committed.copy(lut = null)) },
        )
        BundledLuts.ORDERED
            .filter { looks.containsKey(it.id) }
            .forEach { entry ->
                LookThumbChip(
                    label = stringResource(lookNameRes(entry.id)),
                    selected = selection?.lutId == entry.id,
                    enabled = !exporting,
                    lut = looks[entry.id],
                    onPick = {
                        // A new look keeps the current intensity
                        // (continuity while auditioning looks).
                        commit(
                            committed.copy(
                                lut = LutSelection(
                                    lutId = entry.id,
                                    intensity = selection?.intensity ?: 100,
                                ),
                            ),
                        )
                    },
                )
            }
        // The user-imported looks (r33 extension), id-sorted; the
        // store's collision rule makes the subtraction exact. The
        // imported CubeLut renders through the same thumb path.
        importedIds.forEach { id ->
            val look = looks.getValue(id)
            LookThumbChip(
                label = look.title.ifBlank { id },
                selected = selection?.lutId == id,
                enabled = !exporting,
                lut = look,
                onPick = {
                    commit(
                        committed.copy(
                            lut = LutSelection(
                                lutId = id,
                                intensity = selection?.intensity ?: 100,
                            ),
                        ),
                    )
                },
            )
        }
        // The import action: SAF-pick a .cube (the zero-network
        // editor reads local documents only).
        AssistChip(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            enabled = !exporting,
            label = { Text(stringResource(R.string.editor_filter_import)) },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    importError?.let { reason ->
        Text(
            text = stringResource(R.string.editor_filter_import_failed, reason),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    if (selection != null && looks.containsKey(selection.lutId)) {
        // The intensity slider, live-dragged like every other
        // slider (r32 pattern): the draft previews, release commits.
        val live =
            if (draft?.first == "lut-intensity") draft!!.second else selection.intensity
        val intensityLabel = stringResource(R.string.editor_filter_intensity, live)
        val intensityName = stringResource(R.string.editor_a11y_filter_intensity)
        // r56: the crafted slider row (the live value pill; the
        // r32 draft contract verbatim, plus the double-tap reset
        // to full intensity).
        LabeledSlider(
            name = intensityName,
            label = intensityLabel,
            valueText = live.toString(),
            value = live.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange(Pair("lut-intensity", it))},
            onFinish = {
                draft?.let { d ->
                    if (d.first == "lut-intensity" && committed.lut != null) {
                        commit(committed.copy(lut = committed.lut!!.copy(intensity = d.second)))
                    }
                }
                onDraftChange(null)
            },
            resetValue = 100,
        )
        // An IMPORTED look can be removed (bundled looks are part
        // of the app); removal clears the selection and reloads
        // the registry. A sidecar still naming the removed look
        // degrades to no-look by the tolerance contract.
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

    // ---- Frames (r38: the single mounting; the closing round) ----
    // The contract's UI shape (frames.md decision 6): the look
    // row's conventions --- the None chip, the bundled five, the
    // imported chips, the SAF import chip; the width slider +
    // colour swatches for a PARAMETRIC selection (the live-draft
    // pattern, single-selection keys like the intensity slider);
    // the honest no-controls note for an imported one (the bitmap
    // is the frame). No paint mode, no gesture: a frame needs no
    // dragging.
    Text(
        text = stringResource(R.string.editor_section_frames),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    // r56: the frame chips carry HONEST thumbnails --- the REAL
    // parametric geometry composited by FrameCompositor (the
    // preview/export path) over the reference strip, tinted with
    // the CURRENT frame color so auditioning a color re-renders
    // every thumb. Imported frames show their own bitmap.
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
                    // A new frame keeps the current width and
                    // colour (continuity while auditioning, the
                    // look row's rule).
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
        // The user-imported frames, id-sorted; the store's collision
        // rule makes the keys disjoint from the bundled set.
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
            // -- The parametric controls: width (the single-selection
            //    draft key) + the swatches (immediate commits).
            val widthLive = draft?.takeIf { it.first == "frame-width" }?.second
                ?: frameSelection.widthPct
            val widthName = stringResource(R.string.editor_a11y_frame_width)
            val widthLabel = stringResource(R.string.editor_frame_width, widthLive)
            // r56: the crafted slider row (the live value pill; the
            // draft contract verbatim, double-tap back to the default
            // width).
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
            // -- An imported frame: the bitmap is the frame; the
            //    width/colour controls do not apply, and the UI
            //    says so instead of showing dead controls.
            Text(
                text = stringResource(R.string.editor_frame_imported_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // An unresolvable id (a version skew, a deleted import)
        // selects NO chip --- the tolerance contract, shown
        // honestly exactly like an unknown look id.
    }
}


/** The bundled look's display name (r33); ids are the BundledLuts registry ids. */
private fun lookNameRes(id: String): Int = when (id) {
    "warm-film" -> R.string.editor_filter_warm_film
    "cool-chrome" -> R.string.editor_filter_cool_chrome
    "silver-mono" -> R.string.editor_filter_silver_mono
    "matte-fade" -> R.string.editor_filter_matte_fade
    "vivid-punch" -> R.string.editor_filter_vivid_punch
    else -> R.string.editor_filter_none
}
