package io.github.dukeoftoad.gallery.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueLocationRow
import io.github.dukeoftoad.gallery.data.places.PlaceMediaHandoff
import io.github.dukeoftoad.gallery.data.places.PlaceRegion
import io.github.dukeoftoad.gallery.data.places.PlacesContentState
import io.github.dukeoftoad.gallery.data.places.PlacesRegionGrid
import io.github.dukeoftoad.gallery.data.places.PlacesRegionLabels
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.LoadingState

/**
 * The Places browse screen (r27): the library's coarse offline region
 * buckets as a card grid --- cover (the region's newest photo), the
 * offline coordinate-band label, and the count.
 *
 * ## The states (the pure PlacesContentPolicy's output)
 *  - Loading -> [LoadingState];
 *  - ConsentOff -> the consent state whose action routes to the Settings
 *    tab (the map's consent copy discipline);
 *  - Empty -> the honest "no GPS yet" state plus the same pending
 *    re-index footnote the map carries;
 *  - Ready -> the region grid, regions in the deterministic
 *    newest-first order the pure [PlacesRegionGrid] emits.
 *
 * ## The tap (the hand-off pattern)
 * A region card stashes its request through the one-shot
 * [PlaceMediaHandoff] (the r17 PhotosFilterHandoff pattern verbatim) and
 * hands the region's key to the shell's `onOpenPlace`, which navigates
 * to the region's media grid. ZERO reverse geocoding anywhere: the
 * labels are [PlacesRegionLabels]' pure coordinate-band math (see that
 * object's KDoc for why band labels are the honest choice).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    onBack: () -> Unit,
    onOpenPlace: (regionKey: String) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PlacesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    // ON_RESUME: re-check consent cheaply (the map's discipline) so a
    // grant made in Settings flips this surface on the user's return.
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.places_title),
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
                is PlacesContentState.Loading ->
                    LoadingState(tag = "places_state_loading")

                is PlacesContentState.ConsentOff ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("places_state_consent"),
                    ) {
                        StateScreen(
                            iconRes = R.drawable.ic_nav_map,
                            titleRes = R.string.places_state_consent_title,
                            bodyRes = R.string.places_state_consent_body,
                            actionRes = R.string.places_state_consent_action,
                            onAction = onOpenSettings,
                        )
                    }

                is PlacesContentState.Empty ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("places_state_empty"),
                    ) {
                        StateScreen(
                            iconRes = R.drawable.ic_nav_map,
                            titleRes = R.string.places_state_empty_title,
                            bodyRes = R.string.places_state_empty_body,
                        )
                        if (s.pendingUngrantedRows > 0) {
                            Text(
                                text = pluralStringResource(
                                    R.plurals.places_note_pending_locations,
                                    s.pendingUngrantedRows,
                                    s.pendingUngrantedRows,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 24.dp, start = 32.dp, end = 32.dp),
                            )
                        }
                    }

                is PlacesContentState.Ready -> {
                    // The region buckets: deterministic pure math over the
                    // Ready rows (see the ViewModel's KDoc for why the
                    // derivation lives in the composition).
                    val regions = remember(s.rows) { PlacesRegionGrid.bucket(s.rows) }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("places_state_ready"),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = regions,
                            key = { it.regionKey },
                        ) { region ->
                            PlaceRegionCard(
                                region = region,
                                cover = s.rows.getOrNull(region.coverIndex),
                                onClick = {
                                    PlaceMediaHandoff.request(region.regionKey)
                                    onOpenPlace(region.regionKey)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One region card (r27): the region's NEWEST photo as the cover, the
 * offline coordinate-band label, and the item count --- the same
 * card anatomy as the Albums tab's folder cards, so the two surfaces
 * read as one product.
 */
@Composable
internal fun PlaceRegionCard(
    region: PlaceRegion,
    cover: CatalogueLocationRow?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 4.dp)
            .testTag("places_region_card"),
    ) {
        AsyncImage(
            model = cover?.canonicalUri,
            contentDescription = stringResource(R.string.cd_place_cover),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
        Text(
            text = PlacesRegionLabels.regionLabel(region),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
        Text(
            text = pluralStringResource(
                R.plurals.places_region_count,
                region.count,
                region.count,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
        )
    }
}
