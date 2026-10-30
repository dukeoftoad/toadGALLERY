package io.github.dukeoftoad.gallery.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.MediaPagingGrid
import io.github.dukeoftoad.gallery.ui.media.MediaPermissionState
import io.github.dukeoftoad.gallery.ui.media.rememberMediaPermissionRequester

/**
 * Photos tab (Phase 3, issue #25; incremental paging since #28; review
 * follow-ups #38).
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
 *     4. otherwise -> [MediaPagingGrid], the paged staggered thumbnail grid,
 *        now with an append-state footer (issue #38, finding 2): while
 *        Paging appends the next window the footer shows a small centered
 *        spinner (`state_append_loading`); a failed append shows an honest
 *        one-line error plus a retry button (`state_action_retry` ->
 *        `lazyItems.retry()`); a settled append renders nothing.
 *
 * ## State test-tags (issue #38, finding 7)
 * Every user-visible Photos state carries a stable `Modifier.testTag`, so
 * instrumented tests can prove "this state was reached" independently of the
 * user-visible copy (`PrivacyEgressTest` keeps the text-based waits as the
 * behavioral proof and layers these tag waits on top):
 *  - `photos_state_permission`  (PermissionNeeded; tagged at the call site so
 *    the shared `MediaPermissionState` composable itself stays tag-free)
 *  - `photos_state_error`       (refresh-Error StateScreen wrapper)
 *  - `photos_state_empty`       (empty-library StateScreen wrapper)
 *  - `photos_state_grid`        (the paged grid itself)
 *
 * The permission request and the cell/grid visuals are shared with the
 * Albums tab (`ui/media/MediaPermissions.kt`, `ui/media/MediaGrid.kt` +
 * `ui/media/MediaPagingGrid.kt`, issue #29); both grids stagger cells by
 * aspect ratio and carry the video duration overlay and GIF badge
 * (issues #33/#34, unified into the paging grid at integration).
 *
 * Privacy: every image is a local content URI (ADR-0006). Coil 2.x ships
 * its HTTP fetcher inside the default `coil-compose` registry (there is no
 * separate network artifact to exclude until Coil 3), so the offline
 * guarantee does NOT rest on Coil's packaging: it rests on the app holding
 * no INTERNET permission --- enforced by the ADR-0014 merged-manifest gate
 * and proven by `PrivacyEgressTest` --- which makes the OS refuse every
 * socket, even if a URI somehow resolved to a remote URL.
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
    // (While the ViewModel is already Ready this is a cheap no-op: the grant
    // callback returns immediately and onPermissionGranted() early-returns,
    // keeping the cached paging generation alive --- issue #38, finding 3.)
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
        is PhotosState.PermissionNeeded -> Box(
            modifier = Modifier.testTag("photos_state_permission"),
        ) {
            MediaPermissionState(onGrant = requester::launch)
        }
        is PhotosState.Ready -> {
            val lazyItems = s.pagingData.collectAsLazyPagingItems()
            when {
                // Refresh in flight and nothing to show yet: the same centered
                // spinner the shell Loading state uses. Transient by design,
                // hence untagged (see the state-tag scheme in the KDoc).
                lazyItems.loadState.refresh is LoadState.Loading && lazyItems.itemCount == 0 -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                // Refresh failed (e.g. the provider threw): honest error copy +
                // retry. Paging keeps the window math; retry() reruns the
                // failed refresh anchored where the user was.
                lazyItems.loadState.refresh is LoadState.Error -> Box(
                    modifier = Modifier.testTag("photos_state_error"),
                ) {
                    StateScreen(
                        iconRes = R.drawable.ic_nav_photos,
                        titleRes = R.string.state_photos_error_title,
                        bodyRes = R.string.state_photos_error_body,
                        actionRes = R.string.state_action_retry,
                        onAction = { lazyItems.retry() },
                    )
                }
                // Granted, refresh settled, but the library is empty: the clean
                // empty state (native windows return an empty terminal page
                // here; NOT an error). Unchanged from the pre-paging UI.
                lazyItems.itemCount == 0 -> Box(
                    modifier = Modifier.testTag("photos_state_empty"),
                ) {
                    StateScreen(
                        iconRes = R.drawable.ic_nav_photos,
                        titleRes = R.string.state_photos_empty_title,
                        bodyRes = R.string.state_photos_empty_body,
                    )
                }
                else -> MediaPagingGrid(
                    items = lazyItems,
                    modifier = Modifier.testTag("photos_state_grid"),
                    footer = {
                        // Append load state (issue #38, finding 2): the
                        // refresh states above already own the whole-screen
                        // treatment; the footer only narrates further-window
                        // progress at the end of the list.
                        when (lazyItems.loadState.append) {
                            is LoadState.Loading -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            is LoadState.Error -> Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.state_append_error_title))
                                TextButton(onClick = { lazyItems.retry() }) {
                                    Text(stringResource(R.string.state_action_retry))
                                }
                            }
                            // Append settled: nothing to narrate. The footer
                            // item stays composed but empty --- simplest
                            // correct handling of a conditional footer.
                            else -> Unit
                        }
                    },
                )
            }
        }
    }
}
