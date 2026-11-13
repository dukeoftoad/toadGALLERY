package com.toadgallery.gallery.ui.map

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import com.toadgallery.gallery.data.geo.EquirectangularProjection
import com.toadgallery.gallery.data.media.identity.MediaIdentity
import com.toadgallery.gallery.ui.LocalToadToast
import com.toadgallery.gallery.ui.StateScreen
import com.toadgallery.gallery.ui.media.LoadingState
import com.toadgallery.gallery.ui.viewer.ViewerInfoFormat
import com.toadgallery.gallery.ui.screens.map.ToadMapHeader
import com.toadgallery.gallery.ui.screens.map.ToadMapMemoryCard
import com.toadgallery.gallery.ui.screens.map.ToadMapMode
import com.toadgallery.gallery.ui.screens.map.ToadMapPill
import com.toadgallery.gallery.ui.theme.ToadSpacing
import com.toadgallery.gallery.ui.theme.ToadTheme
import java.util.Locale
import kotlin.math.abs

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
 * A tap on a thumbnail pin SELECTS its cluster (the prototype's
 * `selectMapPin`: the pin scales up behind its pulse ring, the memory
 * card and the coordinates pill follow). The card's expand hands the
 * SAME shell-scoped viewer window hand-off the grid screens use
 * (`viewerViewModel.open(window, index)`), with the SELECTED CLUSTER's
 * items as the window --- the whole location stays swipeable in the
 * real full-screen viewer (the r77.6 prototype conversion replaced the
 * r16 bottom sheet: the prototype's map page has no sheet, and the
 * viewer window covers its browse-the-location job with full EXIF and
 * honestly-wired actions).
 *
 * Privacy (ADR-0014): no tile fetch, no network --- the map is the
 * bundled outline plus the catalogue's persisted coordinates.
 */
@Composable
fun MapScreen(
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MapViewModel = viewModel(),
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
            // r77.6: the prototype's FULL map page --- the header + mode
            // toggle, the rounded bordered canvas card hosting the
            // thumbnail pins + the coordinates pill, and the
            // selected-location memory card below. The mode is
            // session-local UI state (HEATMAP swaps the pins for the
            // thermal glow blobs; the canvas draws the same clusters
            // either way); selection is session-local too --- a pin tap
            // re-points the card, the pill and the pulse ring, exactly
            // like the prototype's selectMapPin.
            var mapMode by remember { mutableStateOf(ToadMapMode.PINS) }
            val toast = LocalToadToast.current
            // The clusters are the shared shape of this page: the pins
            // position by them, the card/pill read the selected one.
            // Same pure grid-bucket math the canvas always used
            // (ADR-0016, JVM-pinned), hoisted here so one computation
            // feeds every surface.
            val clusters = remember(s.rows) {
                MapClusterGrid.cluster(
                    s.rows.map { EquirectangularProjection.project(it.longitude, it.latitude) },
                )
            }
            var selectedRow by remember(s.rows) { mutableStateOf(s.rows.firstOrNull()) }
            val selectedCluster = remember(clusters, selectedRow) {
                selectedRow?.let { row ->
                    clusters.firstOrNull { cluster ->
                        cluster.memberIndexes.any { s.rows[it].localMediaKey == row.localMediaKey }
                    }
                } ?: clusters.firstOrNull()
            }
            val palette = ToadTheme.palette
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
                // The prototype's map canvas card: `rounded-3xl` +
                // `border-strong` + `bg-surface` + shadow, everything
                // (backdrop, pins, pill) clipped inside the frame.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .shadow(6.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(palette.bgSurface)
                        .border(1.dp, palette.borderStrong, RoundedCornerShape(24.dp)),
                ) {
                    WorldMapCanvas(
                        outline = outline,
                        rows = s.rows,
                        clusters = clusters,
                        heatmap = mapMode == ToadMapMode.HEATMAP,
                        selectedKey = selectedRow?.localMediaKey,
                        contentDescription = stringResource(R.string.map_canvas_description),
                        onClusterTapped = { tapped ->
                            // The prototype's selectMapPin: selection
                            // only --- the memory card below is the door
                            // out to the viewer.
                            selectedRow = tapped.firstOrNull()
                        },
                    )
                    selectedCluster?.let { cluster ->
                        val representative = s.rows[cluster.memberIndexes.first()]
                        // The prototype's "Map Missing GPS Quick Helper
                        // Pill" --- showing the SELECTED pin's REAL
                        // coordinates, and (when the honest
                        // pending-reindex backlog exists) a tap that
                        // narrates it through the shell's ONE toast
                        // banner, the prototype's showToast() spot.
                        ToadMapPill(
                            text = coordinateLine(representative),
                            onClick = if (s.pendingUngrantedRows > 0) {
                                {
                                    toast.show(
                                        pluralStringResource(
                                            R.plurals.map_note_pending_locations,
                                            s.pendingUngrantedRows,
                                            s.pendingUngrantedRows,
                                        ),
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp),
                        )
                    }
                }
                selectedCluster?.let { cluster ->
                    val representative = s.rows[cluster.memberIndexes.first()]
                    // r76 polish, kept and extended: the card's expand
                    // opens the REAL full-screen viewer through the SAME
                    // shell hand-off the grid screens use --- and since
                    // r77.6 the window is the SELECTED CLUSTER's items,
                    // so the whole location stays swipeable with the
                    // viewer's honestly-wired favorites / vault / edit /
                    // delete actions plus full EXIF (the r76 first cut
                    // forked a presentation-only lightbox whose four
                    // action buttons were unwired no-ops; routing to the
                    // viewer gives the map the same real surface every
                    // grid has).
                    ToadMapMemoryCard(
                        thumbnailUri = representative.canonicalUri,
                        locationLabel = coordinateLine(representative),
                        title = ViewerInfoFormat.displayName(
                            representative.displayName,
                            representative.canonicalUri,
                        ) ?: "Geotagged memory",
                        dateLine = java.text.DateFormat.getDateInstance(
                            java.text.DateFormat.MEDIUM,
                        ).format(
                            java.util.Date(
                                representative.resolvedCaptureDateMillis
                                    ?: representative.dateAddedMillis,
                            ),
                        ) + " • Tap to inspect EXIF",
                        onOpen = {
                            onOpenViewer(
                                cluster.memberIndexes.map { s.rows[it].asViewerItem() },
                                0,
                            )
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
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
 * The selected pin's coordinates as the pill's / location label's
 * "17.43°N • 78.48°E" line (the prototype pill's format, carrying the
 * REAL catalogue coordinates --- no reverse-geocoded city names exist
 * on-device, and none are invented here).
 */
private fun coordinateLine(row: CatalogueLocationRow): String =
    String.format(
        Locale.US,
        "%.2f°%s • %.2f°%s",
        abs(row.latitude),
        if (row.latitude >= 0) "N" else "S",
        abs(row.longitude),
        if (row.longitude >= 0) "E" else "W",
    )

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
