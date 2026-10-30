package io.github.dukeoftoad.gallery.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.MediaGrid
import io.github.dukeoftoad.gallery.ui.media.MediaPermissionState
import io.github.dukeoftoad.gallery.ui.media.rememberMediaPermissionRequester

/**
 * Photos tab (Phase 3 first slice, issue #25).
 *
 * Three runtime states derived from the user's permission decision:
 *  - PermissionNeeded: the offline-friendly rationale + Grant button
 *  - Loading: a single centered spinner while the MediaStore cursor walks
 *  - Ready: the shared aspect-aware staggered media grid (`MediaGrid`/
 *    `MediaThumb`) via Coil
 *
 * The permission request and the grid are shared with the Albums tab
 * (`ui/media/MediaPermissions.kt`, `ui/media/MediaGrid.kt`, issue #29);
 * the grid staggers cells by aspect ratio and carries the video duration
 * overlay and GIF badge (issues #33/#34); the remaining Phase 3 slices
 * (paging, viewer entry) layer on the shared pieces.
 *
 * Privacy: every image is a local content URI (ADR-0006). Coil is wired
 * without its network artifact, so even if a URI somehow resolved to a
 * remote URL the loader could not fetch it.
 */
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    val requester = rememberMediaPermissionRequester(
        onResult = { granted ->
            if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
        }
    )

    // First composition: kick off the permission request immediately. The user
    // sees the rationale state only on denial or after re-entry from settings.
    LaunchedEffect(Unit) {
        requester.launch()
    }

    when (val s = state) {
        is PhotosState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is PhotosState.PermissionNeeded -> MediaPermissionState(onGrant = requester::launch)
        is PhotosState.Ready -> {
            if (s.items.isEmpty()) {
                StateScreen(
                    iconRes = R.drawable.ic_nav_photos,
                    titleRes = R.string.state_photos_empty_title,
                    bodyRes = R.string.state_photos_empty_body,
                )
            } else {
                MediaGrid(items = s.items)
            }
        }
    }
}
