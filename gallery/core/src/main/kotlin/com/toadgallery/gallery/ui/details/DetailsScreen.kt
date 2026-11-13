package com.toadgallery.gallery.ui.details

import android.Manifest
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadgallery.gallery.R
import com.toadgallery.gallery.metadata.MetadataAvailability
import com.toadgallery.gallery.metadata.edit.EditableExifCodec
import com.toadgallery.gallery.metadata.edit.FieldEditResult
import com.toadgallery.gallery.metadata.edit.FieldInvalidReason
import com.toadgallery.gallery.metadata.edit.MediaEditCapability
import com.toadgallery.gallery.metadata.edit.MediaEditField
import com.toadgallery.gallery.metadata.edit.MetadataEditInput
import com.toadgallery.gallery.metadata.edit.WriteRefusalReason
import com.toadgallery.gallery.ui.details.DetailsPresentation.DetailRow
import com.toadgallery.gallery.ui.details.DetailsPresentation.DetailsField
import com.toadgallery.gallery.ui.details.DetailsPresentation.DetailsSection
import com.toadgallery.gallery.ui.details.DetailsPresentation.EditableFieldRow
import com.toadgallery.gallery.ui.media.LoadingState

/**
 * The Phase 4 metadata Details screen (roadmap deliverable "Metadata
 * Details view with read"): one item's dimensions, size, MIME type,
 * capture date with its timezone provenance, camera block, full exposure
 * triangle, GPS coordinates (decimal + DMS, display-only this round ---
 * the map view is a later round) and the metadata-block tree.
 *
 * r15 adds the WRITE half: an "Editable fields" section (one row per
 * roadmap field, its capability state narrated by the pure gate) and the
 * per-field editor dialog (live-validated by the pure codec, the same
 * pattern as the rename dialogs). The system dialogs the write path may
 * need --- the API 30+ write-grant request and the ACCESS_MEDIA_LOCATION
 * runtime request --- launch from here through the two activity-result
 * launchers below, exactly like the file-ops consent rounds.
 *
 * Every row's text comes from the pure [DetailsPresentation] mapper or is
 * an availability label resolved below, so the audited four-way states
 * ([MetadataAvailability]) stay visibly distinct: "None" is genuinely
 * absent, "Unreadable" means the block exists but is corrupt, "Not
 * supported for this format yet" is the format matrix's capability
 * statement --- never one ambiguous dash.
 *
 * The METADATA_BLOCKS section renders as a collapsible tree (collapsed by
 * default, matching how dense detail sheets behave): the EXIF block row
 * plus the IPTC/XMP headers, which honestly read "not extracted yet" ---
 * the wrappers are a later round and nothing here fakes them.
 *
 * Structure follows the repo's sub-screen chrome (Scaffold + TopAppBar +
 * back arrow, like AlbumDetail/Trash); the route is not top-level, so the
 * shell's bottom bar is hidden. The Compose surface itself is untested by
 * convention (ADR-0016): the mapping underneath is JVM-pinned in
 * `DetailsPresentationTest`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    mediaId: Long,
    uri: Uri,
    onBack: () -> Unit,
    viewModel: DetailsViewModel = viewModel(),
) {
    // The ViewModel is screen-scoped; the route arguments are its only
    // input and are (re)loaded on every argument change. The parsed
    // snapshot is cached inside the ViewModel for the screen's life.
    LaunchedEffect(mediaId, uri) { viewModel.load(mediaId, uri) }
    val state by viewModel.state.collectAsState()
    val editorState by viewModel.editor.collectAsState()

    // The two activity-result seams of the write path (r15), wired like
    // the file-ops consent launchers: the system write-grant dialog
    // (API 30+) answers through onWriteGrantResult, the location-consent
    // request (the GPS field's dimension) through onLocationConsentResult.
    val writeGrantLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onWriteGrantResult(result.resultCode == Activity.RESULT_OK)
    }
    val locationConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onLocationConsentResult(granted)
    }
    LaunchedEffect(Unit) {
        viewModel.editEvents.collect { event ->
            when (event) {
                is MetadataEditEvent.RequestWriteGrant -> writeGrantLauncher.launch(
                    IntentSenderRequest.Builder(event.intentSender).build(),
                )
                MetadataEditEvent.RequestLocationConsent -> locationConsentLauncher.launch(
                    Manifest.permission.ACCESS_MEDIA_LOCATION,
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.details_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        when (val s = state) {
            DetailsUiState.Loading -> LoadingState(tag = "details_state_loading")
            // The eligibility gate refused the item (vault, trashed,
            // permission-revoked): narrated, never "shown empty" --- an
            // empty page would imply the item has no metadata, which we
            // cannot know without reading (and must not read).
            DetailsUiState.Unavailable -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.details_unavailable_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.details_unavailable_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            is DetailsUiState.Ready -> DetailsRows(
                rows = s.rows,
                recordFound = s.recordFound,
                editRows = s.editRows,
                onEditField = viewModel::openEditor,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }

        // The editor dialog rides above whatever state the screen is in
        // (a post-write reload flickers through Loading; the editor is
        // closed by then, but the placement keeps any transient state from
        // dismissing a dialog mid-grant-round-trip).
        val openEditor = editorState as? MetadataEditorState.Open
        if (openEditor != null) {
            MetadataEditorDialog(
                state = openEditor,
                onSave = viewModel::submitEdit,
                onDismiss = viewModel::closeEditor,
                onRequestLocationConsent = viewModel::requestLocationConsent,
            )
        }
    }
}

/**
 * The ordered rows grouped under their section headers. The METADATA_BLOCKS
 * section collapses behind a header toggle (the "complete EXIF block"
 * tree); every other section renders inline. The r15 "Editable fields"
 * section follows the block tree (one row per roadmap field with its
 * honest capability). Grouping is a plain fold over the mapper's ordered
 * list --- the mapper owns the order, the composable only renders it.
 */
@Composable
private fun DetailsRows(
    rows: List<DetailRow>,
    recordFound: Boolean,
    editRows: List<EditableFieldRow>,
    onEditField: (MediaEditField) -> Unit,
    modifier: Modifier = Modifier,
) {
    var blocksExpanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        if (!recordFound) {
            item {
                Text(
                    text = stringResource(R.string.details_no_catalogue_record),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        var lastSection: DetailsSection? = null
        for (row in rows) {
            if (row.section != lastSection) {
                lastSection = row.section
                val isBlocks = row.section == DetailsSection.METADATA_BLOCKS
                item(key = "header_${row.section}") {
                    SectionHeader(
                        titleRes = sectionTitle(row.section),
                        expandable = isBlocks,
                        expanded = blocksExpanded,
                        onToggle = { blocksExpanded = !blocksExpanded },
                    )
                }
                if (isBlocks && !blocksExpanded) continue
            } else if (row.section == DetailsSection.METADATA_BLOCKS && !blocksExpanded) {
                continue
            }
            item(key = "${row.section}_${row.field}") { DetailRowItem(row = row) }
        }
        if (editRows.isNotEmpty()) {
            item(key = "header_edit") {
                SectionHeader(
                    titleRes = R.string.details_section_edit,
                    expandable = false,
                    expanded = false,
                    onToggle = {},
                )
            }
            for (editRow in editRows) {
                item(key = "edit_${editRow.field}") {
                    EditableFieldRowItem(row = editRow, onEdit = onEditField)
                }
            }
        }
    }
}

/** One section header; the metadata-blocks header doubles as the tree toggle. */
@Composable
private fun SectionHeader(
    @StringRes titleRes: Int,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (expandable) {
                    // r50 a11y fix: Role.Button + the expanded state ---
                    // TalkBack used to read the whole section header as
                    // plain text with no affordance or state.
                    Modifier
                        .clickable(onClick = onToggle)
                        .semantics { role = Role.Button }
                } else {
                    Modifier
                },
            )
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (expandable) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.details_blocks_collapse else R.string.details_blocks_expand,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

/** One label/value row; a null value renders the honest availability state. */
@Composable
private fun DetailRowItem(row: DetailRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(fieldLabel(row.field)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Text(
            text = row.value ?: stringResource(availabilityLabel(row.field, row.availability)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (row.value == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
}

/** The section header string for [section]. */
@StringRes
private fun sectionTitle(section: DetailsSection): Int = when (section) {
    DetailsSection.FILE -> R.string.details_section_file
    DetailsSection.CAPTURE_DATE -> R.string.details_section_capture_date
    DetailsSection.CAMERA -> R.string.details_section_camera
    DetailsSection.EXPOSURE -> R.string.details_section_exposure
    DetailsSection.GPS -> R.string.details_section_gps
    DetailsSection.METADATA_BLOCKS -> R.string.details_section_blocks
}

/** The label string for one field. */
@StringRes
private fun fieldLabel(field: DetailsField): Int = when (field) {
    DetailsField.MIME_TYPE -> R.string.details_label_mime_type
    DetailsField.FILE_SIZE -> R.string.details_label_file_size
    DetailsField.DIMENSIONS -> R.string.details_label_dimensions
    DetailsField.CAPTURE_DATE -> R.string.details_label_capture_date
    DetailsField.CAPTURE_ZONE -> R.string.details_label_capture_zone
    DetailsField.CAPTURE_SOURCE -> R.string.details_label_capture_source
    DetailsField.CAMERA_MAKE -> R.string.details_label_camera_make
    DetailsField.CAMERA_MODEL -> R.string.details_label_camera_model
    DetailsField.CAMERA_LENS -> R.string.details_label_camera_lens
    DetailsField.APERTURE -> R.string.details_label_aperture
    DetailsField.SHUTTER -> R.string.details_label_shutter
    DetailsField.ISO -> R.string.details_label_iso
    DetailsField.FOCAL_LENGTH -> R.string.details_label_focal_length
    DetailsField.FLASH -> R.string.details_label_flash
    DetailsField.GPS_DECIMAL -> R.string.details_label_gps_decimal
    DetailsField.GPS_DMS -> R.string.details_label_gps_dms
    DetailsField.EXIF_BLOCK -> R.string.details_label_exif_block
    DetailsField.IPTC_BLOCK -> R.string.details_label_iptc_block
    DetailsField.XMP_BLOCK -> R.string.details_label_xmp_block
}

/**
 * The honest state text for a row with no value. Each availability maps to
 * a DIFFERENT string --- the whole point of the audit's four-way contract.
 * The capture-date field's ABSENT text differs from the generic one: its
 * provenance (which sources were checked and why nothing resolved) is the
 * catalogue's story, told in the screen's own wording.
 */
@StringRes
private fun availabilityLabel(field: DetailsField, availability: MetadataAvailability): Int =
    when (availability) {
        MetadataAvailability.PRESENT ->
            R.string.details_state_extracted
        MetadataAvailability.ABSENT ->
            if (field == DetailsField.CAPTURE_DATE) {
                R.string.details_capture_date_absent
            } else {
                R.string.details_state_absent
            }
        MetadataAvailability.UNSUPPORTED ->
            R.string.details_state_unsupported
        MetadataAvailability.UNREADABLE ->
            R.string.details_state_unreadable
    }

// ------------------------------------------------------------------
// Editable fields (r15 write round)
// ------------------------------------------------------------------

/**
 * One "Editable fields" row: the field's label, its current value (or the
 * honest state text the capability dictates), and the edit affordance.
 * Only actionable capabilities are tappable --- [MediaEditCapability.EDITABLE]
 * opens the input dialog, [MediaEditCapability.CONSENT_REQUIRED] opens the
 * consent view; every refusal row stays plain and lets its state text do
 * the talking (an edit affordance on a read-only field would promise a
 * dialog the gate refuses to open).
 */
@Composable
private fun EditableFieldRowItem(row: EditableFieldRow, onEdit: (MediaEditField) -> Unit) {
    val actionable = row.capability == MediaEditCapability.EDITABLE ||
        row.capability == MediaEditCapability.CONSENT_REQUIRED
    val label = stringResource(editFieldLabel(row.field))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // r50 a11y fix: the editable row announces itself as a button.
            .then(
                if (actionable) {
                    Modifier
                        .clickable { onEdit(row.field) }
                        .semantics { role = Role.Button }
                } else {
                    Modifier
                }
            )
            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = editRowText(row),
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.valueText != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (actionable) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.cd_details_edit_field, label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

/**
 * The text under one editable-field row's label: the current value when
 * there is one, the honest "not set" / "current value unreadable" split
 * for an EDITABLE field with no value (see [EditableFieldRow.valuesReadable]),
 * and the capability's own refusal text for everything the gate refused.
 */
@Composable
private fun editRowText(row: EditableFieldRow): String = when {
    row.valueText != null -> row.valueText
    row.capability == MediaEditCapability.EDITABLE && !row.valuesReadable ->
        stringResource(R.string.details_edit_prefill_unavailable)
    row.capability == MediaEditCapability.EDITABLE ->
        stringResource(R.string.details_edit_state_not_set)
    else -> stringResource(editCapabilityLabel(row.capability))
}

/** The label string for one editable field. */
@StringRes
private fun editFieldLabel(field: MediaEditField): Int = when (field) {
    MediaEditField.DATE_TIME -> R.string.details_edit_field_date_time
    MediaEditField.GPS -> R.string.details_edit_field_gps
    MediaEditField.COPYRIGHT -> R.string.details_edit_field_copyright
    MediaEditField.COMMENT -> R.string.details_edit_field_comment
    MediaEditField.KEYWORDS -> R.string.details_edit_field_keywords
    MediaEditField.RATING -> R.string.details_edit_field_rating
    MediaEditField.COLOUR_LABEL -> R.string.details_edit_field_colour_label
}

/**
 * The honest state text per capability. The EDITABLE entry is only
 * reachable through [editRowText]'s explicit branches above (a set value
 * or one of the two no-value splits); it maps to the "not set" string so
 * the table stays total.
 */
@StringRes
private fun editCapabilityLabel(capability: MediaEditCapability): Int = when (capability) {
    MediaEditCapability.EDITABLE -> R.string.details_edit_state_not_set
    MediaEditCapability.READ_ONLY -> R.string.details_edit_state_read_only
    MediaEditCapability.UNSUPPORTED_FORMAT -> R.string.details_edit_state_unsupported_format
    MediaEditCapability.UNSUPPORTED_FIELD -> R.string.details_edit_state_unsupported_field
    MediaEditCapability.CONSENT_REQUIRED -> R.string.details_edit_state_consent_required
    MediaEditCapability.NOT_ELIGIBLE -> R.string.details_edit_state_not_eligible
}

/**
 * The per-field editor dialog (r15): live-validated by the pure
 * [EditableExifCodec] --- the confirm button is enabled only for a
 * [FieldEditResult.Valid], the same contract as the rename dialogs ---
 * and honest about the write's phase: in flight (inputs locked,
 * "Saving…"), refused (the reason's own text, input stays editable,
 * Retry re-submits), or the system grant dialog declined (terminal for
 * the session). For the GPS field without location consent it becomes
 * the consent view that offers the ACCESS_MEDIA_LOCATION request (the
 * same runtime flow the Settings row drives, launched through the
 * screen's permission launcher).
 */
@Composable
private fun MetadataEditorDialog(
    state: MetadataEditorState.Open,
    onSave: (MetadataEditInput) -> Unit,
    onDismiss: () -> Unit,
    onRequestLocationConsent: () -> Unit,
) {
    // Re-seeds when a NEW editor session opens (field + prefill change);
    // typing survives the phase round-trips (Saving -> Failed -> Retry)
    // because the state lives here, above the phase branches.
    var primary by remember(state.field, state.prefill) {
        mutableStateOf(state.prefill.primary)
    }
    var secondary by remember(state.field, state.prefill) {
        mutableStateOf(state.prefill.secondary)
    }

    if (state.capability == MediaEditCapability.CONSENT_REQUIRED) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onRequestLocationConsent) {
                    Text(stringResource(R.string.details_edit_action_allow_location))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.details_edit_action_close))
                }
            },
            title = { Text(stringResource(editFieldLabel(state.field))) },
            text = { Text(stringResource(R.string.details_edit_state_consent_required)) },
        )
        return
    }

    val input = MetadataEditInput(primary = primary, secondary = secondary)
    val check = remember(state.field, primary, secondary) {
        EditableExifCodec.writesFor(state.field, input)
    }
    val invalid = check as? FieldEditResult.Invalid
    val saving = state.phase == MetadataEditorPhase.Saving
    val inputsEnabled = !saving

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            when (state.phase) {
                MetadataEditorPhase.Input -> TextButton(
                    onClick = { onSave(input) },
                    enabled = check is FieldEditResult.Valid,
                ) {
                    Text(stringResource(R.string.details_edit_action_save))
                }
                MetadataEditorPhase.Saving -> Unit
                is MetadataEditorPhase.Failed -> TextButton(onClick = { onSave(input) }) {
                    Text(stringResource(R.string.details_edit_action_retry))
                }
                MetadataEditorPhase.Denied -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.details_edit_action_close))
            }
        },
        title = { Text(stringResource(editFieldLabel(state.field))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val phase = state.phase) {
                    is MetadataEditorPhase.Failed -> Text(
                        text = stringResource(writeRefusalText(phase.reason)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    MetadataEditorPhase.Denied -> Text(
                        text = stringResource(R.string.details_edit_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    MetadataEditorPhase.Input -> Unit
                    MetadataEditorPhase.Saving -> Text(
                        text = stringResource(R.string.details_edit_saving),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.valuesAvailable) {
                    // The writer's read-back failed: the prefill is empty
                    // NOT because the tags are unset --- say so.
                    Text(
                        text = stringResource(R.string.details_edit_prefill_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (state.field) {
                    MediaEditField.DATE_TIME -> {
                        OutlinedTextField(
                            value = primary,
                            onValueChange = { primary = it },
                            enabled = inputsEnabled,
                            label = { Text(stringResource(R.string.details_edit_label_datetime)) },
                            placeholder = { Text(stringResource(R.string.details_edit_hint_datetime)) },
                            isError = invalid?.reason == FieldInvalidReason.DATETIME,
                            singleLine = true,
                            supportingText = {
                                if (invalid?.reason == FieldInvalidReason.DATETIME) {
                                    Text(stringResource(R.string.details_edit_invalid_datetime))
                                }
                            },
                        )
                        OutlinedTextField(
                            value = secondary,
                            onValueChange = { secondary = it },
                            enabled = inputsEnabled,
                            label = { Text(stringResource(R.string.details_edit_label_offset)) },
                            placeholder = { Text(stringResource(R.string.details_edit_hint_offset)) },
                            isError = invalid?.reason == FieldInvalidReason.OFFSET,
                            singleLine = true,
                            supportingText = {
                                if (invalid?.reason == FieldInvalidReason.OFFSET) {
                                    Text(stringResource(R.string.details_edit_invalid_offset))
                                }
                            },
                        )
                    }
                    MediaEditField.GPS -> {
                        OutlinedTextField(
                            value = primary,
                            onValueChange = { primary = it },
                            enabled = inputsEnabled,
                            label = { Text(stringResource(R.string.details_edit_label_latitude)) },
                            isError = invalid?.reason == FieldInvalidReason.LATITUDE,
                            singleLine = true,
                            supportingText = {
                                if (invalid?.reason == FieldInvalidReason.LATITUDE) {
                                    Text(stringResource(R.string.details_edit_invalid_latitude))
                                }
                            },
                        )
                        OutlinedTextField(
                            value = secondary,
                            onValueChange = { secondary = it },
                            enabled = inputsEnabled,
                            label = { Text(stringResource(R.string.details_edit_label_longitude)) },
                            isError = invalid?.reason == FieldInvalidReason.LONGITUDE,
                            singleLine = true,
                            supportingText = {
                                if (invalid?.reason == FieldInvalidReason.LONGITUDE) {
                                    Text(stringResource(R.string.details_edit_invalid_longitude))
                                } else {
                                    Text(stringResource(R.string.details_edit_gps_clear_hint))
                                }
                            },
                        )
                    }
                    MediaEditField.COPYRIGHT, MediaEditField.COMMENT -> OutlinedTextField(
                        value = primary,
                        onValueChange = { primary = it },
                        enabled = inputsEnabled,
                        label = { Text(stringResource(editFieldLabel(state.field))) },
                        isError = invalid != null,
                    )
                    // The gate refuses these before an editor can open
                    // (UNSUPPORTED_FIELD); the codec's defensive refusal is
                    // the second line, never a dialog.
                    MediaEditField.KEYWORDS,
                    MediaEditField.RATING,
                    MediaEditField.COLOUR_LABEL,
                    -> Unit
                }
            }
        },
    )
}

/** The honest failure text per refusal reason (one string per truth). */
@StringRes
private fun writeRefusalText(reason: WriteRefusalReason): Int = when (reason) {
    WriteRefusalReason.READ_ONLY_PLATFORM ->
        R.string.details_edit_failed_read_only_platform
    WriteRefusalReason.READ_ONLY_STILL_BLOCKED ->
        R.string.details_edit_failed_read_only_still_blocked
    WriteRefusalReason.UNSUPPORTED_FORMAT ->
        R.string.details_edit_failed_unsupported_format
    WriteRefusalReason.IO -> R.string.details_edit_failed_io
}
