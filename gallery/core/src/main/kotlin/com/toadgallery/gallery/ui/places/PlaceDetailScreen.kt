package com.toadgallery.gallery.ui.places

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import com.toadgallery.gallery.data.media.identity.MediaIdentity
import com.toadgallery.gallery.data.places.PlacesRegionLabels
import com.toadgallery.gallery.ui.StateScreen
import com.toadgallery.gallery.ui.media.LoadingState
import com.toadgallery.gallery.ui.media.MediaGrid

/**
 * One Place region's media grid (r27): the bucket's rows as the shared
 * staggered grid, the shell's viewer hand-off on tap --- the
 * PersonDetailScreen contract applied to regions.
 *
 * ## The viewer hand-off (the honest synthesis)
 * Row taps open the EXISTING full-screen viewer through the shell's
 * `(window, index)` hand-off. The synthesized [MediaItem]s are the SAME
 * mapping the r16 map's location sheet uses (`asViewerItem` there):
 * every field comes from the catalogue's own row --- the MediaStore id
 * out of the stable key, the canonical Uri, the effective capture
 * instant, and the display/shape ride-alongs the viewer's cells and
 * overlays render from --- so the window is fully honest (the
 * reimplemented-here-not-shared rationale is the viewer rename dialog's:
 * the map's helper is private to its screen and file ownership keeps it
 * that way; the copy is 15 lines and pinned by the same field-for-field
 * review).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailScreen(
    regionKey: String,
    onBack: () -> Unit,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit = { _, _ -> },
) {
    // The Application is process-scoped; capturing it for the ViewModel
    // factory is the standard Android pattern (outlives the composition).
    val context = LocalContext.current
    val viewModel: PlaceDetailViewModel = viewModel(
        factory = PlaceDetailViewModel.factory(
            application = context.applicationContext as android.app.Application,
            regionKey = regionKey,
        )
    )
    val state by viewModel.state.collectAsState()

    // The live-data truth: the region is gone (every geotagged row left
    // the bucket). Pop back --- never fake stale content.
    LaunchedEffect(state) {
        if (state is PlaceDetailState.Missing) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val ready = state as? PlaceDetailState.Ready
                    Text(
                        text = if (ready != null) {
                            PlacesRegionLabels.regionLabel(ready.region)
                        } else {
                            stringResource(R.string.place_detail_fallback_title)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val s = state) {
                is PlaceDetailState.Loading ->
                    LoadingState(tag = "place_detail_state_loading")

                is PlaceDetailState.Missing ->
                    // A one-frame transition state on the way to onBack().
                    LoadingState(tag = "place_detail_state_missing")

                is PlaceDetailState.Error -> PlaceDetailMessage(
                    titleRes = R.string.place_detail_error_title,
                    // The failure's own text, surfaced verbatim.
                    body = s.message,
                    actionRes = R.string.state_action_retry,
                    onAction = viewModel::retry,
                    tag = "place_detail_state_error",
                )

                is PlaceDetailState.Ready -> {
                    if (s.rows.isEmpty()) {
                        // Defensive: a Ready region always has members (it
                        // was bucketed from them); a race with the next
                        // catalogue write lands here for one frame before
                        // the Missing pop.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("place_detail_state_empty"),
                        ) {
                            StateScreen(
                                iconRes = R.drawable.ic_nav_map,
                                titleRes = R.string.place_detail_empty_title,
                                bodyRes = R.string.place_detail_empty_body,
                            )
                        }
                    } else {
                        val items = s.rows.map { it.asViewerItem() }
                        MediaGrid(
                            items = items,
                            modifier = Modifier.testTag("place_detail_state_grid"),
                            onItemTap = { index, _ ->
                                onOpenViewer(items, index)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The detail screen's message state (error): the PeopleStateMessage
 * layout --- title, the failure's own verbatim body, retry ---
 * reimplemented here rather than shared for the same reason the viewer's
 * rename dialog is: the people file owns its copy, and this surface keeps
 * its own.
 */
@Composable
private fun PlaceDetailMessage(
    titleRes: Int,
    body: String,
    actionRes: Int,
    onAction: () -> Unit,
    tag: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(tag)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        TextButton(
            onClick = onAction,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(stringResource(actionRes))
        }
    }
}

/**
 * Row -> viewer item: the SAME mapping the r16 map's location sheet uses
 * (see the screen KDoc's hand-off section).
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
