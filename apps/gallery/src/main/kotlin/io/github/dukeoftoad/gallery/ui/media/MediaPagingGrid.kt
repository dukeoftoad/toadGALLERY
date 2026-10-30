package io.github.dukeoftoad.gallery.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import io.github.dukeoftoad.gallery.data.MediaItem

/**
 * The paged staggered media grid for the Photos timeline (Phase 3, issues
 * #28 + #34).
 *
 * Same visual contract as [MediaGrid] --- aspect-aware staggered cells via
 * [MediaThumb] (which also carries the video-duration overlay and the GIF
 * badge, issue #33), `columns` columns, 2.dp gaps, `uri.toString()` keys ---
 * but the rows come from Paging 3: [LazyPagingItems] instead of a
 * materialized `List`. The grid renders `itemCount` slots and Paging appends
 * more windows as the user scrolls; `key = itemKey { uri }` keeps
 * recomposition (and the Coil cache) keyed by the stable content URI,
 * exactly as [MediaGrid] does, so cell, key and spacing behavior can never
 * diverge between the two grids.
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
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalItemSpacing = 2.dp,
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.uri.toString() },
        ) { index ->
            items[index]?.let { MediaThumb(item = it) }
        }
    }
}
