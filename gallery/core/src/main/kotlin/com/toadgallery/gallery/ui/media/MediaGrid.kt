package com.toadgallery.gallery.ui.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toadgallery.gallery.data.MediaItem

/**
 * The uniform inter-cell gap shared by every media grid (the shared
 * [MediaGrid], the paged [MediaPagingGrid] timeline, and the album-detail
 * grid). r77.3: the field report asked for a visible gap between photos;
 * this single constant keeps the three grids in lockstep, so the timeline,
 * the search/picked staggered grid, and an album's detail all read with
 * the same breathing room between tiles.
 */
const val MEDIA_GRID_GAP_DP = 4

/**
 * The staggered media grid shared by the picked-photos and search
 * surfaces (Phase 3, issue #29; the paged Photos timeline uses
 * [MediaPagingGrid] with the same cell, and album detail has rendered its
 * own grid with the same contract since issue #64).
 *
 * Aspect-ratio-aware since issue #34: `LazyVerticalStaggeredGrid` sizes each
 * cell from the item's clamped MediaStore dimensions instead of forcing
 * uniform squares, so portrait shots take taller cells, wide panoramas
 * shorter ones, and each column packs independently. The video-duration
 * overlay and the GIF badge (issue #33) live inside [MediaThumb], not here.
 * Staggering applies to media grids only --- album folder covers on the
 * Albums tab stay square. Keys on `uri.toString()` so recomposition and
 * recycling survive position changes.
 *
 * The appearance settings slice (roadmap Phase 3) adds [uniformCells] for
 * the GRID layout (Ente-style): every cell renders a `1f` square via
 * [MediaThumb]'s `forceSquare`. No grouping parameter here by design: this
 * grid backs the picked-photos and search surfaces in v1, whose lists are
 * small enough that date headers would add noise, not orientation --- the
 * timeline grouping stays a [MediaPagingGrid] feature.
 *
 * Tap handling (issue #50): the optional [onItemTap] receives the item's
 * index within [items] plus the item itself; the search screen passes it
 * straight to the shell's viewer hand-off (the same `(window, index)`
 * shape as the paged grid, so both call sites feed the viewer identically).
 * The default `null` keeps the grid inert --- the pre-#50 render --- for any
 * caller with no viewer to open.
 *
 * Privacy: Coil is wired without its network artifact and the app holds no
 * INTERNET permission (ADR-0014), so every cell decodes from local content
 * URIs only (ADR-0006 / ADR-0009).
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    uniformCells: Boolean = false,
    onItemTap: ((index: Int, item: MediaItem) -> Unit)? = null,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(MEDIA_GRID_GAP_DP.dp),
        verticalItemSpacing = MEDIA_GRID_GAP_DP.dp,
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.uri.toString() },
        ) { index, item ->
            if (onItemTap != null) {
                Box(
                    modifier = Modifier.clickable { onItemTap(index, item) },
                ) {
                    MediaThumb(item = item, forceSquare = uniformCells)
                }
            } else {
                MediaThumb(item = item, forceSquare = uniformCells)
            }
        }
    }
}
