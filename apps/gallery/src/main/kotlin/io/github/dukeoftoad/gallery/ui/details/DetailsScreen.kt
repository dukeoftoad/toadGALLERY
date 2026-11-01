package io.github.dukeoftoad.gallery.ui.details

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import io.github.dukeoftoad.gallery.ui.details.DetailsPresentation.DetailRow
import io.github.dukeoftoad.gallery.ui.details.DetailsPresentation.DetailsField
import io.github.dukeoftoad.gallery.ui.details.DetailsPresentation.DetailsSection
import io.github.dukeoftoad.gallery.ui.media.LoadingState

/**
 * The Phase 4 metadata Details screen (roadmap deliverable "Metadata
 * Details view with read"): one item's dimensions, size, MIME type,
 * capture date with its timezone provenance, camera block, full exposure
 * triangle, GPS coordinates (decimal + DMS, display-only this round ---
 * the map view is a later round) and the metadata-block tree.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.details_title)) },
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        }
    }
}

/**
 * The ordered rows grouped under their section headers. The METADATA_BLOCKS
 * section collapses behind a header toggle (the "complete EXIF block"
 * tree); every other section renders inline. Grouping is a plain fold over
 * the mapper's ordered list --- the mapper owns the order, the composable
 * only renders it.
 */
@Composable
private fun DetailsRows(rows: List<DetailRow>, recordFound: Boolean, modifier: Modifier = Modifier) {
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
                    Modifier.clickable(onClick = onToggle)
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
