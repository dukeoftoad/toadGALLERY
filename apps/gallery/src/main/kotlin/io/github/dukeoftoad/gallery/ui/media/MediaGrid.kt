package io.github.dukeoftoad.gallery.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.dukeoftoad.gallery.data.MediaItem

/**
 * The staggered media grid shared by the Photos timeline and the album detail
 * view (Phase 3, issues #25 and #29).
 *
 * Aspect-ratio-aware since issue #34: `LazyVerticalStaggeredGrid` sizes each
 * cell from the item's clamped MediaStore dimensions instead of forcing
 * uniform squares, so portrait shots take taller cells, wide panoramas
 * shorter ones, and each column packs independently. The video-duration
 * overlay and the GIF badge (issue #33) live inside [MediaThumb], not here.
 * Staggering applies to media grids only --- album folder covers on the
 * Albums tab stay square.
 *
 * Keys on `uri.toString()` so recomposition and recycling survive position
 * changes. Thumbnails load via Coil `AsyncImage` over content URIs
 * (ADR-0006 / ADR-0009); privacy: Coil is wired without its network artifact,
 * so every cell decodes from local content URIs only.
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
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
            items = items,
            key = { item -> item.uri.toString() },
        ) { item ->
            MediaThumb(item = item)
        }
    }
}
