package io.github.dukeoftoad.gallery.ui.picker

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.MediaGrid
import io.github.dukeoftoad.gallery.ui.settings.LayoutStyle
import io.github.dukeoftoad.gallery.ui.settings.SettingsViewModel

/**
 * The picked-photos view (issue #27): the Android 13+ system Photo Picker
 * alternative to the broad `READ_MEDIA_*` grant.
 *
 * A sub-screen without the bottom navigation bar (the shell hides it for
 * non-top-level routes, same as album detail): top app bar with an Up action
 * and the shared non-paged [MediaGrid] over the session's picked set. The
 * set is held by the activity-scoped [PickedPhotosViewModel] the shell
 * hoists and shares with the Settings tab, so arriving here right after a
 * pick shows the batch as soon as the mapping coroutine publishes it.
 *
 * Appearance (r19a): the shell-scoped [settingsViewModel] feeds the
 * configured column count and the GRID layout's square cells into the
 * grid --- the SAME wiring PhotosScreen/AlbumDetailScreen use, so every
 * media grid in the app honors the setting (this grid used to default to
 * MediaGrid's 3 columns).
 *
 * Empty set (never picked, or a fresh process --- the v1 set is
 * session-scoped, see the ViewModel KDoc): the frozen [StateScreen] empty
 * state. No permission gate exists here by design --- browsing this
 * subset needs no runtime permission at all, which is the whole point of
 * the issue #27 path (ADR-0006 URI-first; zero permissions, zero network).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickedPhotosScreen(
    pickedViewModel: PickedPhotosViewModel,
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
) {
    val items by pickedViewModel.picked.collectAsState()
    val appearance by settingsViewModel.appearance.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.picked_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
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
        if (items.isEmpty()) {
            StateScreen(
                iconRes = R.drawable.ic_nav_photos,
                titleRes = R.string.state_picked_empty_title,
                bodyRes = R.string.state_picked_empty_body,
            )
        } else {
            MediaGrid(
                items = items,
                modifier = Modifier.padding(innerPadding),
                columns = appearance.columns,
                uniformCells = appearance.layout == LayoutStyle.GRID,
                // r50 ux fix: the grid was completely inert --- every other
                // grid surface opens the shared viewer, this one only stared.
                // Same hand-off as the timeline: the picked set IS the
                // viewer window (it is a stable, materialized list).
                onItemTap = { index, _ -> onOpenViewer(items, index) },
            )
        }
    }
}
