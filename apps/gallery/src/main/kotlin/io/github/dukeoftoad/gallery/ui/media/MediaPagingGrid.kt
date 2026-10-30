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
 * #28 + #34; append-state footer since #38).
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
 * ## Footer slot (issue #38, finding 2)
 * [footer] is an optional trailing composable rendered as the grid's LAST
 * item (`key = "paging_footer"`, so it can never collide with the
 * uri-keyed media items). The Photos timeline passes a slot that reads
 * `items.loadState.append`: a small centered spinner while the next window
 * loads, an honest error + retry row when an append fails, and nothing at
 * all once the append settles (the item stays composed but empty --- the
 * cheapest correct behavior for a conditional footer).
 *
 * Privacy: mirrors [MediaGrid] --- every thumbnail decodes from a local
 * content URI. Coil 2.x ships its HTTP fetcher inside the default
 * `coil-compose` registry (there is no separate network artifact to exclude
 * until Coil 3), so the offline guarantee does NOT rest on Coil's
 * packaging: it rests on the app holding no INTERNET permission ---
 * enforced by the ADR-0014 merged-manifest gate and proven by
 * `PrivacyEgressTest` --- which makes the OS refuse every socket a hostile
 * model value could ever provoke.
 */
@Composable
fun MediaPagingGrid(
    items: LazyPagingItems<MediaItem>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    footer: (@Composable () -> Unit)? = null,
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
        // Append-state footer slot (issue #38): the caller decides what (if
        // anything) each append LoadState renders; a null footer keeps the
        // item list byte-identical to the pre-#38 grid.
        footer?.let { footerContent ->
            item(key = "paging_footer") { footerContent() }
        }
    }
}
