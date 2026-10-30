package io.github.dukeoftoad.gallery.ui.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.Album
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.rememberMediaPermissionRequester

/**
 * Albums tab (Phase 3, issue #29).
 *
 * Same three-state model as Photos (Loading / PermissionNeeded / Ready).
 * `Ready` renders a two-column grid of folder cards: cover thumbnail (newest
 * item in the bucket, URI-first per ADR-0006), folder name, item count.
 * Tapping a card navigates to the album detail view (`AlbumDetailScreen`),
 * which reuses the shared `MediaGrid`.
 */
@Composable
fun AlbumsScreen(
    onOpenAlbum: (bucketId: Long) -> Unit,
    viewModel: AlbumsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    val requester = rememberMediaPermissionRequester(
        onResult = { granted ->
            if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
        }
    )

    LaunchedEffect(Unit) {
        requester.launch()
    }

    when (val s = state) {
        is AlbumsState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is AlbumsState.PermissionNeeded ->
            io.github.dukeoftoad.gallery.ui.media.MediaPermissionState(onGrant = requester::launch)
        is AlbumsState.Ready -> {
            if (s.albums.isEmpty()) {
                StateScreen(
                    iconRes = R.drawable.ic_nav_albums,
                    titleRes = R.string.state_albums_empty_title,
                    bodyRes = R.string.state_albums_empty_body,
                )
            } else {
                AlbumGrid(albums = s.albums, onOpenAlbum = onOpenAlbum)
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    onOpenAlbum: (bucketId: Long) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = albums,
            key = { it.bucketId },
        ) { album ->
            AlbumCard(
                album = album,
                onClick = { onOpenAlbum(album.bucketId) },
            )
        }
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = album.coverUri,
            contentDescription = null, // decorative; the label below carries meaning
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        )
        Text(
            text = album.bucketName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
        Text(
            text = album.itemCount.let { count ->
                androidx.compose.ui.res.pluralStringResource(
                    R.plurals.album_item_count, count, count
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )
    }
}
