package com.toadgallery.editor

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// AI tool panel (extracted from EditActivity): magic erase, sky replacement,
// bokeh, and opt-in face retouch; chassis state + ai runners arrive as params.

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
    // Gesture paints the seed; segment+inpaint runs once at gesture end, off
    // the UI thread, cancellable; mask + fill persist as one derived artifact.
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
                    // Entering Erase exits the other paint modes and drops
                    // every selection.
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
        // On-device quality is bounded; the hint says so plainly.
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
                // Artifact gone: the layer renders nothing (never dead controls).
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
    // No third-party sky bitmaps ship; chips show honest thumbs via the same
    // BundledSkies.render the stage uses. Opacity re-renders the stored mask —
    // no re-inference.
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
            SkyThumbChip(
                label = entry.displayName,
                selected = committed.sky?.skyId == entry.id,
                enabled = !exporting && !aiBusy,
                bundled = entry,
                imported = null,
                onPick = {
                    sweepPendingDrafts()
                    selectSky(entry.id, false)
                },
            )
        }
        importedSkies.keys.sorted().forEach { id ->
            SkyThumbChip(
                label = id,
                selected = committed.sky?.skyId == id,
                enabled = !exporting && !aiBusy,
                bundled = null,
                imported = importedSkies[id],
                onPick = {
                    sweepPendingDrafts()
                    selectSky(id, false)
                },
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
        // Live-draft: tick drafts, release commits; double-tap resets to 100 (full).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_ai_sky_opacity),
            label = stringResource(R.string.editor_ai_sky_opacity, skyOpacityLive),
            valueText = skyOpacityLive.toString(),
            value = skyOpacityLive.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("sky-opacity" to it) },
            onFinish = {
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
            resetValue = 100,
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
        // Re-detect against the CURRENT grid: a changed adjustment means a new
        // artifact key; the old one stays for the undo stack.
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
        // Live-draft: tick drafts, release commits; double-tap resets to 50 (medium).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_ai_bokeh_strength),
            label = stringResource(R.string.editor_ai_bokeh_strength, strengthLive),
            valueText = strengthLive.toString(),
            value = strengthLive.toFloat(),
            valueRange = 1f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("bokeh-strength" to it) },
            onFinish = {
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
            resetValue = 50,
        )
        val focalLive = draft?.takeIf { it.first == "bokeh-focal" }?.second
            ?: (bokehSelection.focalDepthNorm * 100).toInt()
        // Live-draft; double-tap resets to the mid-depth neutral (50).
        LabeledSlider(
            name = stringResource(R.string.editor_a11y_ai_bokeh_focal),
            label = stringResource(R.string.editor_ai_bokeh_focal, focalLive),
            valueText = focalLive.toString(),
            value = focalLive.toFloat(),
            valueRange = 0f..100f,
            enabled = !exporting,
            onChange = { onDraftChange("bokeh-focal" to it) },
            onFinish = {
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
            resetValue = 50,
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

    // ---- Face retouch: opt-in consent ----
    // Hidden under the permanent kill-switch. Consent-off refuses even with a
    // restored sidecar: the layer stays in the recipe, the stage skips.
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
                // Live-draft: tick drafts, release commits; double-tap resets to 0 (off).
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
                    resetValue = 0,
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
            // Default-off; a deny re-asks next use. PERMANENTLY_DISABLED only
            // via the Settings kill-switch.
            AssistChip(
                onClick = { onRetouchConsentDialogChange(true)},
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_ai_retouch_enable)) },
            )
        }

        // First-use consent: on-device only, no face data leaves the device;
        // a grant persists per install, a deny re-asks next use.
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
                            // Deny stays NOT_GRANTED; nothing persists (re-asks next use).
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
