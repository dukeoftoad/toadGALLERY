package io.github.dukeoftoad.gallery.ui.photos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.MediaPagingGrid
import io.github.dukeoftoad.gallery.ui.media.MediaPermissionState
import io.github.dukeoftoad.gallery.ui.media.rememberMediaPermissionRequester

/**
 * Photos tab (Phase 3, issue #25; incremental paging since #28).
 *
 * Three runtime states derived from the user's permission decision:
 *  - PermissionNeeded: the offline-friendly rationale + Grant button
 *  - Loading: a single centered spinner for the first frame, before the
 *    permission check resolves
 *  - Ready: the Paging 3 stream, further layered by the paging load state
 *    (issue #28), with four sub-states:
 *     1. refresh Loading + nothing emitted yet -> the same centered spinner
 *     2. refresh Error -> the "couldn't load" StateScreen with a Retry
 *        button (`lazyItems.retry()` re-runs the failed refresh window)
 *     3. granted but empty library (refresh done, 0 items) -> the unchanged
 *        empty-state StateScreen (`state_photos_empty_title`)
 *     4. otherwise -> [MediaPagingGrid], the paged 3-column thumbnail grid
 *
 * The permission request and the cell/grid visuals are shared with the
 * Albums tab (`ui/media/MediaPermissions.kt`, `ui/media/MediaGrid.kt` +
 * `ui/media/MediaPagingGrid.kt`, issue #29); later Phase 3 slices
 * (staggered grid, overlays, viewer entry) layer on the shared pieces.
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
            val lazyItems = s.pagingData.collectAsLazyPagingItems()
            when {
                // Refresh in flight and nothing to show yet: the same centered
                // spinner the shell Loading state uses.
                lazyItems.loadState.refresh is LoadState.Loading && lazyItems.itemCount == 0 -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                // Refresh failed (e.g. the provider threw): honest error copy +
                // retry. Paging keeps the window math; retry() reruns the
                // failed refresh anchored where the user was.
                lazyItems.loadState.refresh is LoadState.Error -> StateScreen(
                    iconRes = R.drawable.ic_nav_photos,
                    titleRes = R.string.state_photos_error_title,
                    bodyRes = R.string.state_photos_error_body,
                    actionRes = R.string.state_action_retry,
                    onAction = { lazyItems.retry() },
                )
                // Granted, refresh settled, but the library is empty: the clean
                // empty state (native windows return an empty terminal page
                // here; NOT an error). Unchanged from the pre-paging UI.
                lazyItems.itemCount == 0 -> StateScreen(
                    iconRes = R.drawable.ic_nav_photos,
                    titleRes = R.string.state_photos_empty_title,
                    bodyRes = R.string.state_photos_empty_body,
                )
                else -> MediaPagingGrid(items = lazyItems)
            }
        }
    }
}
