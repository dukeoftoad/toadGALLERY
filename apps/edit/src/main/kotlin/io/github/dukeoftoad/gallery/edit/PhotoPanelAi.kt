package io.github.dukeoftoad.gallery.edit

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// The r57 panel seam (F-58a): the AI tool panel, extracted VERBATIM from
// `EditActivity.EditorContent`'s `PhotoScreen.Ai` branch.
//
// The body is the r39-r41 AI contract --- magic erase (the third paint
// mode), sky replacement (detect once, then pure re-renders), bokeh
// (estimate once), face retouch (the OPT-IN consent machine) --- unchanged;
// the chassis state and the three ai runner closures (`runBokehEstimate`,
// `runRetouchDetect`, `selectSky`) arrive as explicit value + callback
// parameters. The committed Roborazzi chassis baselines are the regression
// proof.
<<<<<<< HEAD
//
// r58b (F-58b): every raw Slider in the panel converts to the r56
// `LabeledSlider` --- label + live value pill + double-tap-to-neutral.
// The r39-r41 live-draft contracts are verbatim (tick writes the named
// draft, release commits through `commit`); only the control's VISUAL shape
// changes. Six sliders converted: sky-opacity, bokeh-strength, bokeh-focal,
// retouch-smoothing, retouch-eyes, retouch-slim.
=======
>>>>>>> origin/main
// ---------------------------------------------------------------------------

@Composable
internal fun AiPanel(
    activity: EditActivity,
    committed: EditRecipe,
    draft: Pair<String, Int>?,
    onDraftChange: (Pair<String, Int>?) -> Unit,
    exporting: Boolean,
    commit: (EditRecipe) -> Unit,
    sweepPendingDrafts: () -> Unit,
    drawMode: Boolean,
    onDrawModeChange: (Boolean) -> Unit,
    regionMode: Boolean,
    onRegionModeChange: (Boolean) -> Unit,
    eraseMode: Boolean,
    onEraseModeChange: (Boolean) -> Unit,
    selectedTextLayerId: String?,
    onSelectTextLayerChange: (String?) -> Unit,
    selectedStickerLayerId: String?,
    onSelectStickerLayerChange: (String?) -> Unit,
    selectedDrawLayerId: String?,
    onSelectDrawLayerChange: (String?) -> Unit,
    selectedRegionLayerId: String?,
    onSelectRegionLayerChange: (String?) -> Unit,
    selectedEraseLayerId: String?,
    onSelectEraseLayerChange: (String?) -> Unit,
    aiBusy: Boolean,
    importedSkies: Map<String, Bitmap>,
    skyImportError: String?,
    skyImportLauncher: ManagedActivityResultLauncher<Array<String>, Uri?>,
    retouchConsent: RetouchConsent,
    onRetouchConsentChange: (RetouchConsent) -> Unit,
    retouchConsentDialog: Boolean,
    onRetouchConsentDialogChange: (Boolean) -> Unit,
    runBokehEstimate: () -> Unit,
    runRetouchDetect: () -> Unit,
    selectSky: (String, Boolean) -> Unit,
) {
    // ---- Magic erase: the third paint mode ----
    // The r37 region section's shape: the mode chip (ONE paint
    // mode at a time across Draw/Region/Erase, enforced at all
    // three toggles), the layer chips, and the controls under
    // the mode. The erase gesture paints the seed; the inference
    // (segmentation + inpainting) runs ONCE at gesture end ---
    // off the UI thread, cancellable, honest progress --- and the
    // refined mask + fill persist as ONE derived artifact.
    Text(
        text = stringResource(R.string.editor_section_ai_erase),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(
            selected = eraseMode,
            onClick = {
                sweepPendingDrafts()
                onEraseModeChange(!eraseMode)
                if (eraseMode) {
                    // The duality's rule, third member: entering
                    // Erase paint mode exits the other two and
                    // drops every selection.
                    onDrawModeChange(false)
                    onRegionModeChange(false)
                    onSelectTextLayerChange(null)
                    onSelectStickerLayerChange(null)
                    onSelectDrawLayerChange(null)
                    onSelectRegionLayerChange(null)
                }
            },
            enabled = !exporting && !aiBusy,
            label = { Text(stringResource(R.string.editor_ai_erase_mode)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        committed.aiErase?.layers?.forEachIndexed { index, layer ->
            FilterChip(
                selected = selectedEraseLayerId == layer.id,
                onClick = {
                    if (selectedEraseLayerId != layer.id) {
                        sweepPendingDrafts()
                        onSelectEraseLayerChange(layer.id)
                        onDrawModeChange(false)
                        onRegionModeChange(false)
                        onEraseModeChange(false)
                        onSelectTextLayerChange(null)
                        onSelectStickerLayerChange(null)
                        onSelectDrawLayerChange(null)
                        onSelectRegionLayerChange(null)
                    }
                },
                enabled = !exporting && !aiBusy,
                label = { Text(stringResource(R.string.editor_ai_erase_layer, index + 1)) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
    if (eraseMode) {
        // The honest bounds note (ADR-0010: on-device quality is
        // bounded; the strings say so without apologising).
        Text(
            text = stringResource(R.string.editor_ai_erase_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    } else {
        val selectedEraseLayer = committed.aiErase?.layers
            ?.firstOrNull { it.id == selectedEraseLayerId }
        if (selectedEraseLayer != null) {
            val layerId = selectedEraseLayer.id
            if (selectedEraseLayer.artifactRef.isNotEmpty() &&
                !activity.aiStore.contains(
                    EditAiStore.ArtifactKind.MASK,
                    selectedEraseLayer.artifactRef,
                )
            ) {
                // The honest degraded note: the layer's artifact is
                // gone, it renders nothing (never dead controls).
                Text(
                    text = stringResource(R.string.editor_ai_degraded_layer),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(
                onClick = {
                    sweepPendingDrafts()
                    val remaining = (committed.aiErase?.layers ?: emptyList())
                        .filterNot { it.id == layerId }
                    commit(
                        committed.copy(
                            aiErase = if (remaining.isEmpty()) {
                                null
                            } else {
                                AiEraseLayers(remaining)
                            },
                        ),
                    )
                    onSelectEraseLayerChange(remaining.lastOrNull()?.id)
                },
                enabled = !exporting,
            ) {
                Text(stringResource(R.string.editor_ai_erase_remove))
            }
        }
    }

    // ---- Sky replacement: detect once, then the registry ----
    // The frames section's shape: None, the bundled five
    // (PROJECT-AUTHORED parametric skies --- no third-party sky
    // bitmaps ship in this APK), the imports (the
    // ImportedFrameStore contract verbatim), the import chip. A
    // sky chip that finds no resolvable mask runs the detection
    // first (once, cancellable); a re-detect is a deliberate
    // re-apply. The opacity slider re-renders from the stored
    // mask --- no re-inference.
    Text(
        text = stringResource(R.string.editor_section_ai_sky),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(
            selected = committed.sky == null,
            onClick = { sweepPendingDrafts(); commit(committed.copy(sky = null)) },
            enabled = !exporting && !aiBusy,
            label = { Text(stringResource(R.string.editor_ai_sky_none)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        BundledSkies.ORDERED.forEach { entry ->
            FilterChip(
                selected = committed.sky?.skyId == entry.id,
                onClick = {
                    sweepPendingDrafts()
                    selectSky(entry.id, false)
                },
                enabled = !exporting && !aiBusy,
                label = { Text(entry.displayName) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        importedSkies.keys.sorted().forEach { id ->
            FilterChip(
                selected = committed.sky?.skyId == id,
                onClick = {
                    sweepPendingDrafts()
                    selectSky(id, false)
                },
                enabled = !exporting && !aiBusy,
                label = { Text(id) },
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        AssistChip(
            onClick = { skyImportLauncher.launch(arrayOf("image/png", "image/webp")) },
            enabled = !exporting && !aiBusy,
            label = { Text(stringResource(R.string.editor_ai_sky_import)) },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    skyImportError?.let { reason ->
        Text(
            text = stringResource(R.string.editor_ai_sky_import_failed, reason),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
    val skySelection = committed.sky
    if (skySelection != null) {
        val skyOpacityLive = draft?.takeIf { it.first == "sky-opacity" }?.second
            ?: skySelection.opacityPct
<<<<<<< HEAD
        // r58b: the crafted LabeledSlider on the r39 sky-opacity live-draft
        // contract (tick drafts, release commits, double-tap to 100% full).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_ai_sky_opacity),
            label = stringResource(R.string.editor_ai_sky_opacity, skyOpacityLive),
            valueText = skyOpacityLive.toString(),
            value = skyOpacityLive.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("sky-opacity" to it) },
            onFinish = {
=======
        val skyOpacityName = stringResource(R.string.editor_a11y_ai_sky_opacity)
        val skyOpacityLabel = stringResource(R.string.editor_ai_sky_opacity, skyOpacityLive)
        Text(
            text = skyOpacityLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = skyOpacityLive.toFloat(),
            onValueChange = { onDraftChange("sky-opacity" to it.toInt())},
            onValueChangeFinished = {
>>>>>>> origin/main
                draft?.let { d ->
                    if (d.first == "sky-opacity" && committed.sky != null) {
                        commit(
                            committed.copy(
                                sky = committed.sky!!.copy(opacityPct = d.second.coerceIn(0, 100)),
                            ),
                        )
                    }
                }
                onDraftChange(null)
            },
<<<<<<< HEAD
            resetValue = 100,
=======
            valueRange = 0f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = skyOpacityName
                stateDescription = skyOpacityLabel
            },
>>>>>>> origin/main
        )
        if (BundledSkies.byId(skySelection.skyId) == null &&
            importedSkies.containsKey(skySelection.skyId)
        ) {
            Text(
                text = stringResource(R.string.editor_ai_sky_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (skySelection.maskRef.isNotEmpty() &&
            !activity.aiStore.contains(EditAiStore.ArtifactKind.MASK, skySelection.maskRef)
        ) {
            Text(
                text = stringResource(R.string.editor_ai_degraded_layer),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        // A deliberate re-apply: re-detect the sky against the
        // CURRENT working grid (a changed adjustment means a new
        // artifact key; the old one stays for the undo stack).
        TextButton(
            onClick = { sweepPendingDrafts(); selectSky(skySelection.skyId, true) },
            enabled = !exporting && !aiBusy,
        ) {
            Text(stringResource(R.string.editor_ai_sky_redetect))
        }
    }

    // ---- Bokeh: estimate the depth once, then pure re-renders ----
    Text(
        text = stringResource(R.string.editor_section_ai_bokeh),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
        FilterChip(
            selected = committed.bokeh == null,
            onClick = { sweepPendingDrafts(); commit(committed.copy(bokeh = null)) },
            enabled = !exporting && !aiBusy,
            label = { Text(stringResource(R.string.editor_ai_bokeh_none)) },
            modifier = Modifier.padding(end = 8.dp),
        )
        AssistChip(
            onClick = { sweepPendingDrafts(); runBokehEstimate() },
            enabled = !exporting && !aiBusy,
            label = {
                Text(
                    stringResource(
                        if (committed.bokeh == null) {
                            R.string.editor_ai_bokeh_estimate
                        } else {
                            R.string.editor_ai_bokeh_reestimate
                        },
                    ),
                )
            },
            modifier = Modifier.padding(end = 8.dp),
        )
    }
    val bokehSelection = committed.bokeh
    if (bokehSelection != null) {
        val strengthLive = draft?.takeIf { it.first == "bokeh-strength" }?.second
            ?: bokehSelection.blurStrengthPct
<<<<<<< HEAD
        // r58b: the crafted LabeledSlider on the r40 bokeh-strength live-draft
        // contract (tick drafts, release commits, double-tap to 50% medium).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_ai_bokeh_strength),
            label = stringResource(R.string.editor_ai_bokeh_strength, strengthLive),
            valueText = strengthLive.toString(),
            value = strengthLive.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("bokeh-strength" to it) },
            onFinish = {
=======
        val strengthName = stringResource(R.string.editor_a11y_ai_bokeh_strength)
        val strengthLabel = stringResource(R.string.editor_ai_bokeh_strength, strengthLive)
        Text(
            text = strengthLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = strengthLive.toFloat(),
            onValueChange = { onDraftChange("bokeh-strength" to it.toInt())},
            onValueChangeFinished = {
>>>>>>> origin/main
                draft?.let { d ->
                    if (d.first == "bokeh-strength" && committed.bokeh != null) {
                        commit(
                            committed.copy(
                                bokeh = committed.bokeh!!.copy(
                                    blurStrengthPct = d.second.coerceIn(1, 100),
                                ),
                            ),
                        )
                    }
                }
                onDraftChange(null)
            },
<<<<<<< HEAD
            resetValue = 50,
        )
        val focalLive = draft?.takeIf { it.first == "bokeh-focal" }?.second
            ?: (bokehSelection.focalDepthNorm * 100).toInt()
        // r58b: the bokeh-focal live-draft contract on the crafted LabeledSlider
        // (double-tap to 50, the mid-depth neutral).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_ai_bokeh_focal),
            label = stringResource(R.string.editor_ai_bokeh_focal, focalLive),
            valueText = focalLive.toString(),
            value = focalLive.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("bokeh-focal" to it) },
            onFinish = {
=======
            valueRange = 1f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = strengthName
                stateDescription = strengthLabel
            },
        )
        val focalLive = draft?.takeIf { it.first == "bokeh-focal" }?.second
            ?: (bokehSelection.focalDepthNorm * 100).toInt()
        val focalName = stringResource(R.string.editor_a11y_ai_bokeh_focal)
        val focalLabel = stringResource(R.string.editor_ai_bokeh_focal, focalLive)
        Text(
            text = focalLabel,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = focalLive.toFloat(),
            onValueChange = { onDraftChange("bokeh-focal" to it.toInt())},
            onValueChangeFinished = {
>>>>>>> origin/main
                draft?.let { d ->
                    if (d.first == "bokeh-focal" && committed.bokeh != null) {
                        commit(
                            committed.copy(
                                bokeh = committed.bokeh!!.copy(
                                    focalDepthNorm = d.second.coerceIn(0, 100) / 100.0,
                                ),
                            ),
                        )
                    }
                }
                onDraftChange(null)
            },
<<<<<<< HEAD
            resetValue = 50,
=======
            valueRange = 0f..100f,
            enabled = !exporting,
            modifier = Modifier.semantics {
                contentDescription = focalName
                stateDescription = focalLabel
            },
>>>>>>> origin/main
        )
        if (bokehSelection.depthRef.isNotEmpty() &&
            !activity.aiStore.contains(EditAiStore.ArtifactKind.DEPTH, bokehSelection.depthRef)
        ) {
            Text(
                text = stringResource(R.string.editor_ai_degraded_layer),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    // ---- Face retouch: OPT-IN (ADR-0010 section 5) ----
    // Hidden entirely under the permanent kill-switch; before a
    // grant the only entry is the enable chip that opens the
    // first-use consent dialog (what it does / on-device only /
    // no face data leaves the device). Under consent-off the
    // STAGE refuses even with a restored sidecar --- the layer
    // stays verbatim in the recipe, the stage skips.
    if (retouchConsent != RetouchConsent.PERMANENTLY_DISABLED) {
        Text(
            text = stringResource(R.string.editor_section_ai_retouch),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        if (retouchConsent == RetouchConsent.GRANTED) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                FilterChip(
                    selected = committed.retouch == null,
                    onClick = { sweepPendingDrafts(); commit(committed.copy(retouch = null)) },
                    enabled = !exporting && !aiBusy,
                    label = { Text(stringResource(R.string.editor_ai_retouch_none)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
                AssistChip(
                    onClick = { sweepPendingDrafts(); runRetouchDetect() },
                    enabled = !exporting && !aiBusy,
                    label = { Text(stringResource(R.string.editor_ai_retouch_detect)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            val retouchSelection = committed.retouch
            if (retouchSelection != null) {
                val smoothingLive = draft?.takeIf { it.first == "retouch-smoothing" }?.second
                    ?: retouchSelection.smoothingPct
<<<<<<< HEAD
                // r58b: the crafted LabeledSlider on the r41 retouch live-draft
                // contracts (tick drafts, release commits, double-tap to 0 off).
                LabeledSlider(
                    name = stringResource(R.string.editor_a11y_ai_retouch_smoothing),
                    label = stringResource(
                        R.string.editor_ai_retouch_smoothing,
                        smoothingLive,
                    ),
                    valueText = smoothingLive.toString(),
                    value = smoothingLive.toFloat(),
                    valueRange = 0f..100f,
                    enabled = !exporting,
                    onChange = { onDraftChange("retouch-smoothing" to it) },
                    onFinish = {
=======
                val smoothingName = stringResource(R.string.editor_a11y_ai_retouch_smoothing)
                val smoothingLabel = stringResource(
                    R.string.editor_ai_retouch_smoothing,
                    smoothingLive,
                )
                Text(
                    text = smoothingLabel,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = smoothingLive.toFloat(),
                    onValueChange = { onDraftChange("retouch-smoothing" to it.toInt())},
                    onValueChangeFinished = {
>>>>>>> origin/main
                        draft?.let { d ->
                            if (d.first == "retouch-smoothing" && committed.retouch != null) {
                                commit(
                                    committed.copy(
                                        retouch = committed.retouch!!.copy(
                                            smoothingPct = d.second.coerceIn(0, 100),
                                        ),
                                    ),
                                )
                            }
                        }
                        onDraftChange(null)
                    },
<<<<<<< HEAD
                    resetValue = 0,
                )
                val eyesLive = draft?.takeIf { it.first == "retouch-eyes" }?.second
                    ?: retouchSelection.eyeBrightenPct
                LabeledSlider(
                    name = stringResource(R.string.editor_a11y_ai_retouch_eyes),
                    label = stringResource(R.string.editor_ai_retouch_eyes, eyesLive),
                    valueText = eyesLive.toString(),
                    value = eyesLive.toFloat(),
                    valueRange = 0f..100f,
                    enabled = !exporting,
                    onChange = { onDraftChange("retouch-eyes" to it) },
                    onFinish = {
=======
                    valueRange = 0f..100f,
                    enabled = !exporting,
                    modifier = Modifier.semantics {
                        contentDescription = smoothingName
                        stateDescription = smoothingLabel
                    },
                )
                val eyesLive = draft?.takeIf { it.first == "retouch-eyes" }?.second
                    ?: retouchSelection.eyeBrightenPct
                val eyesName = stringResource(R.string.editor_a11y_ai_retouch_eyes)
                val eyesLabel = stringResource(R.string.editor_ai_retouch_eyes, eyesLive)
                Text(
                    text = eyesLabel,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = eyesLive.toFloat(),
                    onValueChange = { onDraftChange("retouch-eyes" to it.toInt())},
                    onValueChangeFinished = {
>>>>>>> origin/main
                        draft?.let { d ->
                            if (d.first == "retouch-eyes" && committed.retouch != null) {
                                commit(
                                    committed.copy(
                                        retouch = committed.retouch!!.copy(
                                            eyeBrightenPct = d.second.coerceIn(0, 100),
                                        ),
                                    ),
                                )
                            }
                        }
                        onDraftChange(null)
                    },
<<<<<<< HEAD
                    resetValue = 0,
                )
                val slimLive = draft?.takeIf { it.first == "retouch-slim" }?.second
                    ?: retouchSelection.faceSlimPct
                LabeledSlider(
                    name = stringResource(R.string.editor_a11y_ai_retouch_slim),
                    label = stringResource(R.string.editor_ai_retouch_slim, slimLive),
                    valueText = slimLive.toString(),
                    value = slimLive.toFloat(),
                    valueRange = 0f..100f,
                    enabled = !exporting,
                    onChange = { onDraftChange("retouch-slim" to it) },
                    onFinish = {
=======
                    valueRange = 0f..100f,
                    enabled = !exporting,
                    modifier = Modifier.semantics {
                        contentDescription = eyesName
                        stateDescription = eyesLabel
                    },
                )
                val slimLive = draft?.takeIf { it.first == "retouch-slim" }?.second
                    ?: retouchSelection.faceSlimPct
                val slimName = stringResource(R.string.editor_a11y_ai_retouch_slim)
                val slimLabel = stringResource(R.string.editor_ai_retouch_slim, slimLive)
                Text(
                    text = slimLabel,
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = slimLive.toFloat(),
                    onValueChange = { onDraftChange("retouch-slim" to it.toInt())},
                    onValueChangeFinished = {
>>>>>>> origin/main
                        draft?.let { d ->
                            if (d.first == "retouch-slim" && committed.retouch != null) {
                                commit(
                                    committed.copy(
                                        retouch = committed.retouch!!.copy(
                                            faceSlimPct = d.second.coerceIn(0, 100),
                                        ),
                                    ),
                                )
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
                        contentDescription = slimName
                        stateDescription = slimLabel
                    },
>>>>>>> origin/main
                )
                if (retouchSelection.boxesRef.isNotEmpty() &&
                    !activity.aiStore.contains(
                        EditAiStore.ArtifactKind.BOXES,
                        retouchSelection.boxesRef,
                    )
                ) {
                    Text(
                        text = stringResource(R.string.editor_ai_degraded_layer),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            // Default-off: the single opt-in entry. A deny keeps
            // it (the next use re-asks); the never is the
            // Settings kill-switch.
            AssistChip(
                onClick = { onRetouchConsentDialogChange(true)},
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_ai_retouch_enable)) },
            )
        }

        // The first-use consent dialog (ADR-0010 section 5): the
        // text states what the model does, that it runs on-device
        // only, and that no face data leaves the device. A grant
        // persists per install; a deny re-asks on the next use.
        if (retouchConsentDialog && retouchConsent == RetouchConsent.NOT_GRANTED) {
            AlertDialog(
                onDismissRequest = { onRetouchConsentDialogChange(false)},
                title = { Text(stringResource(R.string.editor_ai_retouch_consent_title)) },
                text = { Text(stringResource(R.string.editor_ai_retouch_consent_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val next = RetouchConsentMachine.onGrant(retouchConsent)
                            if (activity.retouchConsentStore.save(next)) {
                                onRetouchConsentChange(next)
                            }
                            onRetouchConsentDialogChange(false)
                        },
                    ) {
                        Text(stringResource(R.string.editor_ai_retouch_consent_grant))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            // A deny stays NOT_GRANTED (the next
                            // use re-asks); nothing persists.
                            onRetouchConsentChange(RetouchConsentMachine.onDeny(retouchConsent))
                            onRetouchConsentDialogChange(false)
                        },
                    ) {
                        Text(stringResource(R.string.editor_ai_retouch_consent_deny))
                    }
                },
            )
        }
    }
}
