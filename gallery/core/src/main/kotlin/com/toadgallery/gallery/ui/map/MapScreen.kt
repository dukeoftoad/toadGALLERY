package com.toadgallery.gallery.ui.map

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import com.toadgallery.gallery.data.media.identity.MediaIdentity
import com.toadgallery.gallery.ui.StateScreen
import com.toadgallery.gallery.ui.media.LoadingState
import com.toadgallery.gallery.ui.viewer.ViewerInfoFormat
import com.toadgallery.gallery.ui.overlays.PhotoLightboxOverlay
import com.toadgallery.gallery.ui.theme.ToadShapes
import com.toadgallery.gallery.ui.screens.map.ToadMapHeader
import com.toadgallery.gallery.ui.screens.map.ToadMapMemoryCard
import com.toadgallery.gallery.ui.screens.map.ToadMapMode
import com.toadgallery.gallery.ui.theme.ToadSpacing
import java.util.Locale

/**
 * The Map tab (r16 round, Phase 4 still-open item "on-device map view").
 *
 * The same three-state discipline as Photos/Albums, decided by the pure
 * [MapContentPolicy] (JVM-pinned) instead of ViewModel control flow:
 *  - Loading -> [LoadingState] tagged `map_state_loading`
 *  - ConsentOff -> the frozen [StateScreen] layout tagged
 *    `map_state_consent`, with an action that routes to the Settings tab
 *    (the r14 ACCESS_MEDIA_LOCATION row lives there)
 *  - Empty/Ready -> the offline [WorldMapCanvas] (empty shows the frozen
 *    empty layout tagged `map_state_empty` while the first locations are
 *    pending; Ready draws the map).
 *
 * A tap on a dot (or cluster) opens [MapLocationSheet] listing that
 * location's items in timeline order; tapping an item hands the SAME
 * shell-scoped viewer window hand-off the grid screens use
 * (`viewerViewModel.open(window, index)`), with the cluster's items as
 * the window.
 *
 * Privacy (ADR-0014): no tile fetch, no network --- the map is the
 * bundled outline plus the catalogue's persisted coordinates.
 */
@Composable
fun MapScreen(
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MapViewModel = viewModel(),
    /** r76: the memory card's lightbox actions (favorites/vault/edit/delete). */
    onToggleFavorite: (MediaItem) -> Unit = {},
    onHideToVault: (MediaItem) -> Unit = {},
    onEdit: (MediaItem) -> Unit = {},
    onDelete: (MediaItem) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val outline by viewModel.outline.collectAsState()

    // ON_RESUME: re-check consent cheaply so a grant made in Settings (or
    // the system settings app) flips the map's state when the user
    // returns --- the same refresh the r14 Details gate relies on.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshConsent()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The tapped cluster's rows --- the sheet's content. `null` = closed.
    var sheetRows by remember { mutableStateOf<List<CatalogueLocationRow>?>(null) }

    when (val s = state) {
        MapContentState.Loading -> LoadingState(tag = "map_state_loading")

        MapContentState.ConsentOff -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("map_state_consent"),
            ) {
                StateScreen(
                    iconRes = R.drawable.ic_nav_map,
                    titleRes = R.string.map_state_consent_title,
                    bodyRes = R.string.map_state_consent_body,
                    actionRes = R.string.map_state_consent_action,
                    onAction = onOpenSettings,
                )
            }
        }

        is MapContentState.Empty -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("map_state_empty"),
            ) {
                StateScreen(
                    iconRes = R.drawable.ic_nav_map,
                    titleRes = R.string.map_state_empty_title,
                    bodyRes = R.string.map_state_empty_body,
                )
                if (s.pendingUngrantedRows > 0) {
                    PendingReindexFootnote(count = s.pendingUngrantedRows)
                }
            }
        }

        is MapContentState.Ready -> {
            // r76: the redesign's map shell --- the header + mode toggle
            // above the canvas, the selected-location memory card below.
            // The mode is session-local UI state (the canvas renders the
            // same clusters either way; HEATMAP only changes the visual).
            var mapMode by remember { mutableStateOf(ToadMapMode.PINS) }
            var selectedRow by remember(s.rows) { mutableStateOf(s.rows.firstOrNull()) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("map_state_ready"),
            ) {
                ToadMapHeader(
                    geotaggedCount = s.rows.size,
                    mode = mapMode,
                    onModeChange = { mapMode = it },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    WorldMapCanvas(
                        outline = outline,
                        rows = s.rows,
                        heatmap = mapMode == ToadMapMode.HEATMAP,
                        contentDescription = stringResource(R.string.map_canvas_description),
                        onClusterTapped = { tapped ->
                            selectedRow = tapped.firstOrNull()
                            sheetRows = tapped
                        },
                    )
                    if (s.pendingUngrantedRows > 0) {
                        PendingReindexFootnote(count = s.pendingUngrantedRows)
                    }
                }
                selectedRow?.let { row ->
                    // r76: the card's expand opens the lightbox overlay
                    // (the redesign's quick viewer); the memory-grid rows
                    // inside the sheet keep the full viewer hand-off.
                    var showLightbox by remember { mutableStateOf(false) }
                    ToadMapMemoryCard(
                        thumbnailUri = row.canonicalUri,
                        locationLabel = row.displayName ?: "Unknown place",
                        title = row.displayName ?: "Geotagged memory",
                        dateLine = java.text.DateFormat.getDateInstance(
                            java.text.DateFormat.MEDIUM,
                        ).format(java.util.Date(row.resolvedCaptureDateMillis ?: row.dateAddedMillis)) +
                            " • Tap to inspect EXIF",
                        onOpen = { showLightbox = true },
                        modifier = Modifier.padding(16.dp),
                    )
                    if (showLightbox) {
                        val item = row.asViewerItem()
                        PhotoLightboxOverlay(
                            item = item,
                            dateLine = java.text.DateFormat.getDateInstance(
                                java.text.DateFormat.MEDIUM,
                            ).format(
                                java.util.Date(
                                    row.resolvedCaptureDateMillis ?: row.dateAddedMillis,
                                ),
                            ),
                            locationLine = row.displayName,
                            isFavorite = row.isFavorite,
                            tagsLine = "Local EXIF • no cloud tags",
                            exifLine = (row.displayName ?: "MediaStore row") +
                                " • " + ViewerInfoFormat.dimensions(row.width, row.height),
                            onBack = { showLightbox = false },
                            onToggleFavorite = { onToggleFavorite(item) },
                            onHideToVault = { onHideToVault(item) },
                            onEdit = { onEdit(item) },
                            onDelete = { onDelete(item) },
                        )
                    }
                }
            }
        }
    }

    sheetRows?.let { rows ->
        MapLocationSheet(
            rows = rows,
            onDismiss = { sheetRows = null },
            onOpenItem = { index ->
                onOpenViewer(rows.map { it.asViewerItem() }, index)
            },
        )
    }
}

/**
 * The honest "scanned while location access was off" footnote (r16):
 * surfaced whenever rows carry the withheld provenance, per
 * [LocationConsentReindexPolicy][com.toadgallery.gallery.data.media
 * .location.LocationConsentReindexPolicy]'s documented v1 semantics ---
 * those items resolve their location on their next content change, and
 * the grant-triggered full re-pass lands with the Phase 5 Places index.
 * Surfacing the count (not hiding it in a log line) is the "wire the
 * trivial part" half of that policy. A [BoxScope] extension because the
 * footnote self-places at its parent Box's bottom center (the `align`
 * modifier exists only inside a Box scope).
 */
@Composable
private fun BoxScope.PendingReindexFootnote(count: Int) {
    Text(
        text = pluralStringResource(R.plurals.map_note_pending_locations, count, count),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = ToadSpacing.md),
    )
}

/**
 * The location sheet (r16): a Material 3 [ModalBottomSheet] (the same
 * sheet idiom the viewer's info sheet uses) listing one location's items
 * in timeline order. One row per item: thumbnail (the content URI ---
 * Coil's app-wide loader decodes it on-device), display name (the
 * viewer's formatter, reused --- honest `null`s become the URI), and the
 * effective date as the timeline would order it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapLocationSheet(
    rows: List<CatalogueLocationRow>,
    onDismiss: () -> Unit,
    onOpenItem: (index: Int) -> Unit,
) {
    // A date is localized prose; feed the pure formatter the current
    // configuration's locale (the viewer sheet's pattern).
    val locale: Locale = LocalConfiguration.current.locales[0]

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ToadSpacing.xxl, end = ToadSpacing.xxl)
                .padding(bottom = ToadSpacing.xxxl),
        ) {
            Text(
                text = stringResource(R.string.map_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = pluralStringResource(R.plurals.map_sheet_item_count, rows.size, rows.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = ToadSpacing.xs),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ToadSpacing.lg),
            ) {
                itemsIndexed(
                    items = rows,
                    key = { _, row -> row.localMediaKey },
                ) { index, row ->
                    MapLocationRow(
                        row = row,
                        locale = locale,
                        onClick = { onOpenItem(index) },
                    )
                }
            }
        }
    }
}

/** One sheet row: thumbnail, name, effective date. */
@Composable
private fun MapLocationRow(
    row: CatalogueLocationRow,
    locale: Locale,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ToadShapes.small))
            .clickable(onClick = onClick)
            .padding(vertical = ToadSpacing.sm),
    ) {
        AsyncImage(
            // The content Uri string, re-parsed here: every other
            // thumbnail surface hands Coil the Uri object, so the loader's
            // dispatch (video frames, GIF first frames) behaves identically.
            model = Uri.parse(row.canonicalUri),
            contentDescription = null, // decorative; the label carries meaning
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(ToadShapes.small)),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = ToadSpacing.md),
        ) {
            Text(
                text = ViewerInfoFormat.displayName(row.displayName, row.canonicalUri)
                    ?: row.canonicalUri,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = effectiveDateText(row, locale) ?: row.mimeType,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The row's effective instant as localized prose, or `null` when neither
 * a resolved capture date nor a date_added exists (the viewer formatter's
 * honest `null`; the caller degrades to the MIME type).
 */
private fun effectiveDateText(row: CatalogueLocationRow, locale: Locale): String? {
    val effectiveSec = (row.resolvedCaptureDateMillis ?: row.dateAddedMillis) / 1000
    return ViewerInfoFormat.dateAdded(effectiveSec, locale)
}

/**
 * Row -> viewer item: the SAME mapping the catalogue-backed timeline
 * uses ([catalogueTimelineItem][com.toadgallery.gallery.ui.photos
 * .catalogueTimelineItem]'s field flow): the MediaStore id out of the
 * stable key, the canonical Uri, the effective capture instant, and the
 * display/shape ride-alongs the viewer's cells and overlays render from.
 */
private fun CatalogueLocationRow.asViewerItem(): MediaItem = MediaItem(
    id = MediaIdentity.fromKeyString(localMediaKey)?.mediaStoreId ?: 0L,
    uri = Uri.parse(canonicalUri),
    mimeType = mimeType,
    dateAddedSec = dateAddedMillis / 1000,
    isVideo = mimeType.startsWith("video/"),
    width = width,
    height = height,
    durationMs = durationMs,
    isFavorite = isFavorite,
    captureDateMillis = resolvedCaptureDateMillis,
)
