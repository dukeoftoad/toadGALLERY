package io.github.dukeoftoad.gallery.ui.albums

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.MediaGrid

/**
 * Album detail view (Phase 3, issue #29).
 *
 * A sub-screen without the bottom navigation bar (hidden by the shell for
 * non-top-level routes): top app bar with the bucket name + back arrow, and
 * the shared `MediaGrid` filtered to the bucket's contents. Entered only
 * after the Albums tab loaded with granted access; a mid-session revoke
 * surfaces as an empty grid (defensive query in the ViewModel).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    bucketId: Long,
    onBack: () -> Unit,
) {
    // The Application is process-scoped; capturing it for the ViewModel
    // factory is the standard Android pattern (outlives the composition).
    val context = LocalContext.current
    val viewModel: AlbumDetailViewModel = viewModel(
        factory = AlbumDetailViewModel.factory(
            application = context.applicationContext as android.app.Application,
            bucketId = bucketId,
        )
    )
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (val s = state) {
                            is AlbumDetailState.Ready -> s.bucketName
                            else -> stringResource(R.string.album_detail_fallback_title)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
        when (val s = state) {
            is AlbumDetailState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            is AlbumDetailState.Ready -> {
                if (s.items.isEmpty()) {
                    StateScreen(
                        iconRes = R.drawable.ic_nav_albums,
                        titleRes = R.string.album_detail_fallback_title,
                        bodyRes = R.string.album_detail_empty_body,
                    )
                } else {
                    MediaGrid(
                        items = s.items,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
