package io.github.dukeoftoad.gallery.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.data.MediaItem

/**
 * The paged media thumbnail grid for the Photos timeline (Phase 3, issue #28).
 *
 * Same visual contract as [MediaGrid] (uniform square thumbnails, 3 columns,
 * Coil `AsyncImage` over content URIs per ADR-0006 / ADR-0009), but the rows
 * come from Paging 3: `LazyPagingItems` instead of a materialized `List`.
 * The grid renders `itemCount` slots and Paging appends more windows as the
 * user scrolls; `key = itemKey { uri }` keeps recomposition (and the Coil
 * cache) keyed by the stable content URI, exactly as MediaGrid does.
 *
 * Privacy: mirrors [MediaGrid] --- every thumbnail decodes from a local
 * content URI and Coil is wired without its network artifact, so even a
 * hostile model value could not resolve to a remote fetch.
 */
@Composable
fun MediaPagingGrid(
    items: LazyPagingItems<MediaItem>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.uri.toString() },
        ) { index ->
            items[index]?.let { PagingThumb(item = it) }
        }
    }
}

/**
 * One square thumbnail cell of the paged timeline --- deliberately the same
 * cell as [MediaGrid]'s.
 *
 * The parallel grid-UX slice introduces the shared aspect-aware MediaThumb;
 * a follow-up integration swaps this placeholder cell in. Keeping the cell
 * isolated in this tiny composable is what makes that swap a one-line change
 * here (see the grid-UX integration notes on MediaGrid / MediaStoreSource).
 */
@Composable
private fun PagingThumb(item: MediaItem) {
    AsyncImage(
        model = item.uri,
        contentDescription = null, // decorative; the viewer will describe
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentScale = ContentScale.Crop,
    )
}
