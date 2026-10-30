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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.data.MediaItem

/**
 * The media thumbnail grid shared by the Photos timeline and the album
 * detail view (Phase 3, issues #25 and #29).
 *
 * Uniform square thumbnails, 3 columns, Coil `AsyncImage` over content URIs
 * (ADR-0006 / ADR-0009). The staggered aspect-aware grid is issue #34;
 * video/GIF overlays are issue #33; both layer on this composable.
 *
 * Privacy: Coil is wired without its network artifact, so every thumbnail
 * decodes from local content URIs only.
 */
@Composable
fun MediaGrid(
    items: List<MediaItem>,
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
            items = items,
            key = { item -> item.uri.toString() },
        ) { item ->
            AsyncImage(
                model = item.uri,
                contentDescription = null, // decorative; the viewer will describe
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
        }
    }
}
