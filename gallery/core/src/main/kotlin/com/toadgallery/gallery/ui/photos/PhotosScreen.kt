package com.toadgallery.gallery.ui.photos

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.Album
import com.toadgallery.gallery.data.FileOpKind
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.data.RenameCheck
import com.toadgallery.gallery.data.TimelineSort
import com.toadgallery.gallery.data.baseNameOf
import com.toadgallery.gallery.data.checkRenameInput
import com.toadgallery.gallery.data.collage.CollageHandoff
import com.toadgallery.gallery.data.collage.CollageLayouts
import com.toadgallery.gallery.data.filters.PhotosFilterHandoff
import com.toadgallery.gallery.data.filters.TimelineFilter
import com.toadgallery.gallery.ui.components.toadTrailingFade
import com.toadgallery.gallery.ui.screens.photos.ToadPhotosHeader
import com.toadgallery.gallery.ui.theme.ToadTheme
import com.toadgallery.gallery.ui.StateScreen
import com.toadgallery.gallery.ui.media.MediaPagingGrid
import com.toadgallery.gallery.ui.media.MediaPermissionState
import com.toadgallery.gallery.ui.media.rememberMediaPermissionRequester
import com.toadgallery.gallery.ui.settings.Appearance
import com.toadgallery.gallery.ui.settings.LayoutStyle
import com.toadgallery.gallery.ui.settings.SettingsViewModel
import com.toadgallery.gallery.ui.vault.VaultBusy
import com.toadgallery.gallery.ui.vault.VaultBusyKind
import com.toadgallery.gallery.ui.vault.VaultEvent
import com.toadgallery.gallery.ui.vault.VaultUiState
import com.toadgallery.gallery.ui.vault.VaultViewModel
import com.toadgallery.gallery.ui.vault.isConfigured
import com.toadgallery.gallery.ui.theme.ToadElevation

/**
 * Photos tab (Phase 3, issue #25; incremental paging since #28; review
 * follow-ups #38; file operations since #30).
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
 *     3. granted but empty (refresh done, 0 items) -> the unchanged
 *        empty-library StateScreen (`state_photos_empty_title`); under an
 *        ACTIVE r17 filter this branch instead renders the honest
 *        nothing-matched state, chips row included (see the r17 section)
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
 * ## File operations (issue #30) --- Photos tab only in this slice
 * [FileOpsViewModel] owns the op state machine; this screen owns every
 * piece of activity-result/visual machinery it needs:
 *  - long-press enters selection (grid cells in [MediaPagingGrid] carry the
 *    check overlay; `file_ops_cell_check`), tap toggles;
 *  - a selection app bar (`file_ops_app_bar`) with the count and an exit
 *    action, plus an actions bar (`file_ops_actions`): Rename (single
 *    selection only), Copy (in-place duplicate), Move (album picker
 *    dialog), Delete (confirmation dialog with explicit "permanent, no
 *    recycle bin yet" wording). System back EXITS selection mode
 *    (r19b) instead of leaving the app with the mode still live in the
 *    ViewModel --- the count bar's Close action's twin, and the mirror
 *    of AlbumDetailScreen's handler;
 *  - undo snackbars for rename/copy/move (delete has none) and a cancellable
 *    progress dialog (`file_ops_progress`, "Moving 3 of 12…") for batches
 *    above [FileOpsViewModel.PROGRESS_DIALOG_THRESHOLD] items;
 *  - the system consent launcher: `FileOpsEvent.RequestConsent` hands over
 *    an `IntentSender` (MediaStore write/delete recovery, API 29+), the
 *    screen launches it via `StartIntentSenderForResult` and reports the
 *    result code back; the ViewModel retries on `RESULT_OK`.
 *  All file-ops UI keys off the ViewModel's state, so below Android 10 ---
 *    where [FileOpsViewModel] is inert (the app holds read-only access
 *    there) --- long-press simply does nothing and the grid renders exactly
 *    as before this slice (documented in `docs/architecture/file-ops.md`).
 *
 * ## Move to vault (issue #32, ADR-0017)
 * The shell-scoped [vaultViewModel] (shared with the Settings entry and the
 * `vault` sub-screen) adds a lock-icon action to the selection app bar,
 * visible ONLY while the vault is configured. The confirmation dialog
 * states exactly what happens: media is copied into the encrypted vault,
 * the originals leave the gallery, and from then on they are only
 * reachable through the vault. The move runs per item (seal bytes, then
 * remove the row through the SAME consent machinery above); a blocked
 * delete round-trips through this screen's vault consent launcher and
 * [VaultViewModel.onConsentResult]. On Android 10, where no batch delete
 * request exists, consent-blocked originals narrate as copied-but-not-
 * removed instead of looping a dialog.
 *
 * The permission request and the cell/grid visuals are shared with the
 * Albums tab (`ui/media/MediaPermissions.kt`, `ui/media/MediaGrid.kt` +
 * `ui/media/MediaPagingGrid.kt`, issue #29); both grids stagger cells by
 * aspect ratio and carry the video duration overlay and GIF badge
 * (issues #33/#34, unified into the paging grid at integration), and since
 * issue #66 the shared `MediaThumb` also carries the favorites heart
 * overlay on favorited cells.
 *
 * ## Timeline sorting (issue #66)
 * The plain (non-selection) timeline gained a slim top app bar whose only
 * action is [TimelineSortMenu]: Newest first / Oldest first, held as
 * per-session ViewModel state. Changing it swaps in a fresh Paging
 * generation (see `PhotosViewModel`); the bar disappears in selection mode
 * exactly like the pre-#66 grid did, replaced by the count bar.
 *
 * ## Smart-filter chips (r17) + the folder picker (r19b)
 * Under the header bar the plain timeline carries [PhotosFilterChipsRow]:
 * All / Photos / Videos / GIFs / Favorites (multi-select; the last is Q+
 * only --- the provider column is Q+), plus a Clear action while any
 * filter is active --- and since r19b a Folder chip LEADS the row:
 * "All folders" unselected, the picked folder's name selected, and a
 * tap opens the picker sheet (a Material 3 modal bottom sheet listing
 * the device's media folders the way the move picker does, with honest
 * loading/error/empty states). Chips write the ViewModel's
 * [TimelineFilter] (one fresh paging generation per change), so grid,
 * empty state and item count all reflect the SAME filtered slice on
 * every data path. An empty result under an active filter renders the
 * honest "nothing matched" state (with the chips row still on top ---
 * the row's All/Clear affordances are the way back from an over-narrow
 * filter), not the empty-library copy. The screen also consumes the
 * Albums tab's Collections hand-off ([PhotosFilterHandoff]) on every
 * entry.
 *
 * ## Appearance settings (roadmap Phase 3 slice)
 * The shell-scoped [settingsViewModel] (shared with the Settings tab and
 * album detail) feeds the collected [Appearance] into the grid: columns,
 * group-by headers and the GRID layout's square cells. Defaults
 * (COLLAGE/3/NONE) keep the render this screen shipped with byte-identical;
 * changes apply live because the grid derives its rows from the paging
 * snapshot reactively (see `MediaPagingGrid`).
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
    fileOpsViewModel: FileOpsViewModel = viewModel(),
    vaultViewModel: VaultViewModel,
    settingsViewModel: SettingsViewModel,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit = { _, _ -> },
    /**
     * r27 (Stories): opens one story's playback --- the shell arms the
     * shared viewer with the window and its slideshow mode.
     */
    onOpenStory: (window: List<MediaItem>) -> Unit = {},
    /**
     * r27 (Slideshow): starts a slideshow over the timeline's loaded
     * window --- the shell arms the shared viewer + slideshow and
     * navigates.
     */
    onStartSlideshow: (window: List<MediaItem>) -> Unit = {},
    /** r27 (Collage): opens the collage creator over the selection. */
    onStartCollage: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val filter by viewModel.filter.collectAsState()
    // r19b: the folder chip's label name + the picker sheet's list state.
    val selectedFolderName by viewModel.selectedFolderName.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val appearance by settingsViewModel.appearance.collectAsState()
    val fileOpsState by fileOpsViewModel.state.collectAsState()
    val vaultState by vaultViewModel.state.collectAsState()
    val vaultBusy by vaultViewModel.busy.collectAsState()

    val requester = rememberMediaPermissionRequester(
        onResult = { granted ->
            if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
        }
    )

    // ---- File-ops plumbing (issue #30): snackbars + consent launching ----
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        fileOpsViewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    // ---- Vault plumbing (issue #32): move narration + delete consent ----
    // A separate launcher from the file-ops one: both consent flows can
    // never be in flight together (a move clears the selection), but the
    // result must route to the right ViewModel.
    val vaultConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        vaultViewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        vaultViewModel.events.collect { event ->
            when (event) {
                is VaultEvent.RequestConsent -> vaultConsentLauncher.launch(
                    IntentSenderRequest.Builder(event.intentSender).build(),
                )
                is VaultEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    message = if (event.formatArgs.isEmpty()) {
                        context.getString(event.messageRes)
                    } else {
                        context.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    },
                    duration = SnackbarDuration.Short,
                )
                is VaultEvent.ShowPlural -> snackbarHostState.showSnackbar(
                    message = context.resources.getQuantityString(
                        event.messageRes, event.count, event.count,
                    ),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        fileOpsViewModel.events.collect { event ->
            when (event) {
                is FileOpsEvent.RequestConsent -> consentLauncher.launch(
                    IntentSenderRequest.Builder(event.intentSender).build(),
                )
                is FileOpsEvent.ShowSnackbar -> {
                    // Strings resolve against the current configuration at
                    // display time; the event carries only resource ids/args.
                    val message = if (event.pluralCount >= 0) {
                        context.resources.getQuantityString(
                            event.messageRes, event.pluralCount, event.pluralCount,
                        )
                    } else if (event.formatArgs.isEmpty()) {
                        context.getString(event.messageRes)
                    } else {
                        context.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    }
                    val actionLabel = event.undo
                        ?.let { context.getString(R.string.file_ops_undo_action) }
                    val shown = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = actionLabel,
                        duration = if (event.undo != null) {
                            SnackbarDuration.Long
                        } else {
                            SnackbarDuration.Short
                        },
                    )
                    if (shown == SnackbarResult.ActionPerformed) {
                        fileOpsViewModel.performUndo(event.undo)
                    }
                }
            }
        }
    }

    // First composition: kick off the permission request immediately. The user
    // sees the rationale state only on denial or after re-entry from settings.
    // (While the ViewModel is already Ready this is a cheap no-op: the grant
    // callback returns immediately and onPermissionGranted() early-returns,
    // keeping the cached paging generation alive --- issue #38, finding 3.)
    LaunchedEffect(Unit) {
        requester.launch()
    }

    // r17: the Albums tab's Collections hand-off, consumed ONCE per Photos
    // entry. The shell owns every tab switch (its file is frozen for the
    // parallel rounds), so a collection card cannot navigate here itself;
    // the one-shot slot is the honest mechanism --- Albums stashes the
    // requested filter (with a snackbar saying so) and the NEXT Photos
    // composition applies it. LaunchedEffect(Unit) re-runs on every tab
    // re-entry because the NavHost disposes a departing destination's
    // content; a plain re-entry with nothing pending consumes null and
    // changes nothing.
    LaunchedEffect(Unit) {
        PhotosFilterHandoff.consume()?.let(viewModel::onFilterChange)
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
                // Granted, refresh settled, and nothing matched: with an
                // ACTIVE filter this is the honest "nothing matched" state
                // (not "your library is empty" --- the library may be full);
                // with NO filter it is the clean empty state (native windows
                // return an empty terminal page here; NOT an error).
                // Unchanged from the pre-paging/pre-r17 UI otherwise.
                lazyItems.itemCount == 0 -> Box(
                    modifier = Modifier.testTag("photos_state_empty"),
                ) {
                    if (filter.isActive) {
                        // The chips stay reachable in the nothing-matched
                        // state: the body copy says "clear a chip or two",
                        // and the filter is ViewModel state that survives
                        // tab switches --- without the row (and its All /
                        // Clear affordances) an over-narrow filter would
                        // strand the user with no way back to the grid.
                        Column(modifier = Modifier.fillMaxSize()) {
                            PhotosFilterChipsRow(
                                filter = filter,
                                onChange = viewModel::onFilterChange,
                                selectedFolderName = selectedFolderName,
                                folders = folders,
                                onFoldersRefresh = viewModel::refreshFolders,
                                onFolderPick = viewModel::onFolderSelected,
                                onFolderClear = viewModel::onFolderCleared,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                StateScreen(
                                    iconRes = R.drawable.ic_nav_photos,
                                    titleRes = R.string.state_photos_filtered_empty_title,
                                    bodyRes = R.string.state_photos_filtered_empty_body,
                                )
                            }
                        }
                    } else {
                        StateScreen(
                            iconRes = R.drawable.ic_nav_photos,
                            titleRes = R.string.state_photos_empty_title,
                            bodyRes = R.string.state_photos_empty_body,
                        )
                    }
                }
                else -> PhotosTimeline(
                    lazyItems = lazyItems,
                    appearance = appearance,
                    onLayoutChange = settingsViewModel::setLayout,
                    sort = sort,
                    onSortChange = viewModel::onSortChange,
                    filter = filter,
                    onFilterChange = viewModel::onFilterChange,
                    selectedFolderName = selectedFolderName,
                    folders = folders,
                    onFoldersRefresh = viewModel::refreshFolders,
                    onFolderPick = viewModel::onFolderSelected,
                    onFolderClear = viewModel::onFolderCleared,
                    fileOpsViewModel = fileOpsViewModel,
                    fileOpsState = fileOpsState,
                    vaultViewModel = vaultViewModel,
                    vaultState = vaultState,
                    vaultBusy = vaultBusy,
                    snackbarHostState = snackbarHostState,
                    onOpenViewer = onOpenViewer,
                    onOpenStory = onOpenStory,
                    onStartSlideshow = onStartSlideshow,
                    onStartCollage = onStartCollage,
                )
            }
        }
    }
}

/**
 * The ready Photos timeline with its file-ops chrome (issue #30): the paged
 * grid, the selection app bar + actions while selecting, the undo snackbar
 * host, and the four file-ops dialogs (rename, delete confirmation, album
 * picker, batch progress). The vault chrome (issue #32) rides along: the
 * move-to-vault confirmation and its cancellable progress dialog. Since
 * issue #66 the plain timeline also carries the sort header bar
 * ([PhotosHeaderBar] + [TimelineSortMenu]); since the appearance slice it
 * threads the shell's [Appearance] through to the grid; since r17 the
 * plain timeline also carries the smart-filter chips row
 * ([PhotosFilterChipsRow]) under the header bar, and since r19b the row's
 * folder chip + picker sheet plumb through here (the ViewModel owns the
 * state; this composable only forwards it).
 *
 * Grid refreshing after ops is NOT done by hand: MediaStore writes fire the
 * existing ContentObserver -> paging invalidation (issue #38); see the
 * `FileOpsViewModel` KDoc and `docs/architecture/file-ops.md`.
 */
@Composable
private fun PhotosTimeline(
    lazyItems: LazyPagingItems<MediaItem>,
    appearance: Appearance,
    onLayoutChange: (LayoutStyle) -> Unit,
    sort: TimelineSort,
    onSortChange: (TimelineSort) -> Unit,
    filter: TimelineFilter,
    onFilterChange: (TimelineFilter) -> Unit,
    selectedFolderName: String?,
    folders: PhotosFoldersState,
    onFoldersRefresh: () -> Unit,
    onFolderPick: (Album) -> Unit,
    onFolderClear: () -> Unit,
    fileOpsViewModel: FileOpsViewModel,
    fileOpsState: FileOpsUiState,
    vaultViewModel: VaultViewModel,
    vaultState: VaultUiState,
    vaultBusy: VaultBusy?,
    snackbarHostState: SnackbarHostState,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
    onOpenStory: (window: List<MediaItem>) -> Unit,
    onStartSlideshow: (window: List<MediaItem>) -> Unit,
    onStartCollage: () -> Unit,
) {
    val selection = fileOpsState as? FileOpsUiState.Selection
    val running = fileOpsState as? FileOpsUiState.Running

    // r19b: while a selection is active, system back EXITS the selection
    // instead of leaving the app with the mode still live in the ViewModel
    // --- the count bar's Close action's twin, and the mirror of
    // AlbumDetailScreen's handler. During a batch run `selection` is null
    // (Running replaces Selection in the file-ops state machine), so back
    // keeps its normal meaning there; `exitSelection` itself also guards
    // Running (a no-op mid-batch), so the two guards agree.
    BackHandler(enabled = selection != null) {
        fileOpsViewModel.exitSelection()
    }

    // Local dialog state: dialog visibility is screen-local, op runs live in
    // the ViewModel. Nothing here needs to survive process death --- an
    // in-flight op is provider-side, and the dialogs are one-tap decisions.
    var renameTarget by remember { mutableStateOf<MediaItem?>(null) }
    var renamePrefill by remember { mutableStateOf<String?>(null) }
    var deleteCount by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAlbumPicker by remember { mutableStateOf(false) }
    var showVaultMoveConfirm by remember { mutableStateOf(false) }
    val albums by fileOpsViewModel.albums.collectAsState()

    // Prefill for the rename dialog: the current name minus its extension
    // (the extension is restored on submit; see checkRenameInput).
    LaunchedEffect(renameTarget) {
        renamePrefill = renameTarget?.let { item ->
            fileOpsViewModel.displayNameOf(item)?.let { name -> baseNameOf(name) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (selection != null) {
            // Selection mode: app bar (count + exit) over the grid, actions
            // row under it. The grid itself keeps its `photos_state_grid`
            // tag and window math --- only the cell overlay differs.
            Column(modifier = Modifier.fillMaxSize()) {
                FileOpsSelectionBar(
                    count = selection.items.size,
                    onExit = fileOpsViewModel::exitSelection,
                    showMoveToVault = vaultState.isConfigured,
                    onMoveToVault = { showVaultMoveConfirm = true },
                    // r27: the collage action rides the selection bar as an
                    // icon (the vault lock's slot --- the actions row's
                    // four labels already fill small screens, per that
                    // bar's own KDoc). Visible once two or more photos are
                    // picked, enabled exactly in the creator's 2..9 range
                    // (a disabled control beats a lying one; the content
                    // description states the range).
                    canCollage = selection.items.size in CollageLayouts.MIN_PHOTOS..CollageLayouts.MAX_PHOTOS,
                    onCollage = {
                        CollageHandoff.request(selection.items)
                        fileOpsViewModel.exitSelection()
                        onStartCollage()
                    },
                )
                Box(modifier = Modifier.weight(1f)) {
                    PhotosGrid(
                        lazyItems = lazyItems,
                        appearance = appearance,
                        fileOpsViewModel = fileOpsViewModel,
                        selectionActive = true,
                        selectedKeys = selection.items.mapTo(HashSet()) { it.uri.toString() },
                        onOpenViewer = onOpenViewer,
                    )
                }
                FileOpsSelectionActions(
                    singleSelection = selection.items.size == 1,
                    onRename = {
                        selection.items.singleOrNull()?.let { item ->
                            renamePrefill = null
                            renameTarget = item
                        }
                    },
                    onCopy = fileOpsViewModel::requestCopy,
                    onMove = { showAlbumPicker = true },
                    onDelete = {
                        deleteCount = selection.items.size
                        showDeleteConfirm = true
                    },
                )
            }
        } else {
            // Plain timeline (issue #66: now under the sort header bar): cells
            // still carry long-press to enter selection (the overlay stays
            // hidden) and tap opens the full-screen viewer (issue #50) --- the
            // pre-#50 plain tap was a no-op.
            Column(modifier = Modifier.fillMaxSize()) {
                // r76: the redesign's frosted header block --- the brand
                // badge + wordmark, the quick Grid/Collage toggle (writes
                // the shell Appearance preference, the same flow the
                // Settings sub-page drives) and the Clean Feed pill (the
                // r76 TimelineFilter.excludeScreenshots dimension), with
                // the sort menu + slideshow action riding the same glass
                // row as small trailing affordances.
                ToadPhotosHeader(
                    layoutIsCollage = appearance.layout == LayoutStyle.COLLAGE,
                    onToggleLayout = {
                        onLayoutChange(
                            if (appearance.layout == LayoutStyle.COLLAGE) {
                                LayoutStyle.GRID
                            } else {
                                LayoutStyle.COLLAGE
                            },
                        )
                    },
                    hideScreenshots = filter.excludeScreenshots,
                    onToggleHideScreenshots = {
                        onFilterChange(filter.copy(excludeScreenshots = !filter.excludeScreenshots))
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ToadTheme.palette.bgApp)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // r27: the slideshow launch (the round's overflow
                    // action) --- the run covers the timeline's loaded
                    // window, exactly the window a viewer tap hands off.
                    IconButton(
                        onClick = {
                            val window = lazyItems.itemSnapshotList.filterNotNull()
                            if (window.isNotEmpty()) onStartSlideshow(window)
                        },
                        modifier = Modifier.testTag("photos_action_slideshow"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                R.string.cd_viewer_action_slideshow_start,
                            ),
                            tint = ToadTheme.palette.primary,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    TimelineSortMenu(current = sort, onChange = onSortChange)
                }
                // The smart-filter chips (r17): under the header bar, plain
                // timeline only (selection mode replaces the header with the
                // count bar, and the chips would collide with the actions
                // row). One tap = one ViewModel filter change = one fresh
                // paging generation (see PhotosViewModel.onFilterChange).
                // Since r19b the row also carries the LEADING folder chip;
                // its picker sheet and the chip's name state thread through
                // the same ViewModel plumbing. r76: the strip carries the
                // prototype's trailing-edge fade mask so chips dissolve
                // instead of clipping.
                Box(Modifier.toadTrailingFade()) {
                    PhotosFilterChipsRow(
                        filter = filter,
                        onChange = onFilterChange,
                        selectedFolderName = selectedFolderName,
                        folders = folders,
                        onFoldersRefresh = onFoldersRefresh,
                        onFolderPick = onFolderPick,
                        onFolderClear = onFolderClear,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // r62: the Stories shelf moved INSIDE the scroll --- it is
                // the grid's first full-line item and scrolls away with the
                // content (the maintainer's "stories features is fixed on
                // home page which shouldn't": the old pinned slot above the
                // grid permanently ate ~100dp of the home page). Still plain
                // unfiltered timeline only (StoriesShelf's own
                // when-it-renders rule), and now also gated by the
                // Appearance "Stories on home page" toggle (default ON ---
                // the pre-r62 render).
                Box(modifier = Modifier.weight(1f)) {
                    PhotosGrid(
                        lazyItems = lazyItems,
                        appearance = appearance,
                        fileOpsViewModel = fileOpsViewModel,
                        selectionActive = false,
                        selectedKeys = emptySet(),
                        onOpenViewer = onOpenViewer,
                        header = if (!filter.isActive && appearance.storiesOnHome) {
                            {
                                StoriesShelf(
                                    lazyItems = lazyItems,
                                    onOpenStory = onOpenStory,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // r50 ux fix: in selection mode the FileOpsSelectionActions
                // row pins the bottom edge --- an unoffset snackbar (the
                // undo narration!) physically covered Rename/Copy/Move/
                // Delete and ate their taps. The host rides above the row
                // exactly when the row is on screen (row height = the
                // 48dp touch floor + the row's 2 x 6dp vertical padding;
                // + the host's own 12dp gap in both shapes).
                .padding(bottom = if (selection != null) 72.dp else 12.dp),
        )
    }

    // ---- File-ops dialogs (issue #30) ----

    renameTarget?.let {
        FileOpsRenameDialog(
            prefill = renamePrefill,
            onConfirm = { name ->
                fileOpsViewModel.requestRename(name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    if (showDeleteConfirm) {
        FileOpsDeleteDialog(
            count = deleteCount,
            onConfirm = {
                fileOpsViewModel.requestDelete()
                showDeleteConfirm = false
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    if (showAlbumPicker) {
        // Re-query on every open: counts and buckets change as ops land.
        LaunchedEffect(Unit) { fileOpsViewModel.refreshAlbums() }
        FileOpsAlbumPickerDialog(
            albums = albums,
            onPick = { album ->
                fileOpsViewModel.requestMove(album)
                showAlbumPicker = false
            },
            onDismiss = { showAlbumPicker = false },
        )
    }
    if (running != null && running.total > FileOpsViewModel.PROGRESS_DIALOG_THRESHOLD) {
        FileOpsProgressDialog(
            kind = running.kind,
            done = running.done,
            total = running.total,
            onCancel = fileOpsViewModel::cancelRunning,
        )
    }

    // ---- Vault move dialogs (issue #32) ----

    if (showVaultMoveConfirm && selection != null && selection.items.isNotEmpty()) {
        VaultMoveDialog(
            count = selection.items.size,
            onConfirm = {
                vaultViewModel.moveToVault(selection.items)
                // The selection handed its items to the vault batch; leaving
                // selection mode now keeps the app bar from acting on items
                // that are about to leave the timeline.
                fileOpsViewModel.exitSelection()
                showVaultMoveConfirm = false
            },
            onDismiss = { showVaultMoveConfirm = false },
        )
    }
    // Every move batch shows the dialog (even one item): sealing copies
    // real media bytes, which dwarfs the rename/copy batches the file-ops
    // threshold was sized for.
    val vaultMoving = vaultBusy?.takeIf { it.kind == VaultBusyKind.MOVING }
    if (vaultMoving != null) {
        VaultMoveProgressDialog(
            done = vaultMoving.done,
            total = vaultMoving.total,
            onCancel = vaultViewModel::cancelMove,
        )
    }
}

/**
 * The timeline grid call, shared by plain and selection layouts so the
 * `photos_state_grid` tag, footer and selection wiring cannot diverge.
 * The shell's [Appearance] lands here: columns, group-by and the GRID
 * layout's square cells (see `MediaPagingGrid`).
 *
 * Tap semantics (issue #50): while a selection is active a tap toggles the
 * item's membership (issue #30 behavior, unchanged); on the plain timeline a
 * tap opens the full-screen viewer, handed the tap-time window (the loaded
 * snapshot filtered of placeholder nulls) plus the tapped position. The
 * index is recomputed against the filtered list so the viewer starts on the
 * tapped item regardless of placeholder slots.
 */
@Composable
private fun PhotosGrid(
    lazyItems: LazyPagingItems<MediaItem>,
    appearance: Appearance,
    fileOpsViewModel: FileOpsViewModel,
    selectionActive: Boolean,
    selectedKeys: Set<String>,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
    header: (@Composable () -> Unit)? = null,
) {
    MediaPagingGrid(
        items = lazyItems,
        modifier = Modifier.testTag("photos_state_grid"),
        columns = appearance.columns,
        groupBy = appearance.groupBy,
        uniformCells = appearance.layout == LayoutStyle.GRID,
        selectionActive = selectionActive,
        selectedKeys = selectedKeys,
        header = header,
        onItemTap = { index, item ->
            if (selectionActive) {
                fileOpsViewModel.onItemTapped(item)
            } else {
                val window = lazyItems.itemSnapshotList.filterNotNull()
                onOpenViewer(window, window.indexOf(item))
            }
        },
        onItemLongPress = { item -> fileOpsViewModel.onItemLongPressed(item) },
        footer = {
            // Append load state (issue #38, finding 2): the refresh states
            // in PhotosScreen already own the whole-screen treatment; the
            // footer only narrates further-window progress at the end of
            // the list.
            when (lazyItems.loadState.append) {
                is LoadState.Loading -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // r50 ux fix: 12dp vertical --- the error footer's
                        // value (the old 20dp was off the token grid and
                        // made the two footer shapes jump on transition).
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                    )
                    // r50 ux fix: the spinner is silent --- TalkBack
                    // announced nothing while further pages loaded, even
                    // though state_append_loading was written FOR this
                    // footer (the KDoc claimed it rendered; it never did).
                    Text(
                        text = stringResource(R.string.state_append_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
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
                // Append settled: nothing to narrate. The footer item stays
                // composed but empty --- simplest correct handling of a
                // conditional footer.
                else -> Unit
            }
        },
    )
}

/**
 * Selection-mode top bar (issue #30): the live count (plurals like
 * `settings_picked_count`) plus an exit action, and --- when the vault is
 * configured --- the Move to vault lock action (issue #32) in the actions
 * slot. An icon action there (not a fifth text button in the actions row)
 * keeps the row's four labels fitting on small screens; the lock glyph
 * reads as "vault" unaided, and the confirmation dialog explains fully.
 * `windowInsets = 0`: the shell Scaffold already consumed the status-bar
 * inset, so a default TopAppBar would double-pad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileOpsSelectionBar(
    count: Int,
    onExit: () -> Unit,
    showMoveToVault: Boolean,
    onMoveToVault: () -> Unit,
    canCollage: Boolean,
    onCollage: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                pluralStringResource(R.plurals.file_ops_selection_count, count, count),
                // r40 a11y: the count is a dynamic surface --- a polite live
                // region so TalkBack announces selection changes.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_file_ops_exit_selection),
                )
            }
        },
        actions = {
            if (count >= CollageLayouts.MIN_PHOTOS) {
                // r27: the collage creator's launch. An icon action in the
                // bar's actions slot (the vault lock's pattern --- the
                // actions ROW below keeps its four labels), enabled
                // exactly in the 2..9 template range.
                IconButton(
                    onClick = onCollage,
                    enabled = canCollage,
                    modifier = Modifier.testTag("photos_action_collage"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_collage),
                        contentDescription = stringResource(R.string.cd_collage_action),
                    )
                }
            }
            if (showMoveToVault) {
                IconButton(
                    onClick = onMoveToVault,
                    modifier = Modifier.testTag("vault_action_move"),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_vault),
                        contentDescription = stringResource(R.string.vault_cd_move_action),
                    )
                }
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.testTag("file_ops_app_bar"),
    )
}

/**
 * The selection actions row (issue #30): Rename (single selection only),
 * Copy (in-place duplicate), Move, Delete. A bottom bar rather than app-bar
 * action icons keeps every action visible with a text label on small
 * screens (the core icon set has no copy/move glyphs, and text beats
 * mystery icons).
 */
@Composable
private fun FileOpsSelectionActions(
    singleSelection: Boolean,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        // r50 ux fix: the token, not the literal (the elevation scale's
        // level1 IS 3dp --- zero visual change, one truth).
        tonalElevation = ToadElevation.level1,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("file_ops_actions"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(
                onClick = onRename,
                enabled = singleSelection,
                modifier = Modifier.testTag("file_ops_action_rename"),
            ) {
                Text(stringResource(R.string.file_ops_action_rename))
            }
            TextButton(
                onClick = onCopy,
                modifier = Modifier.testTag("file_ops_action_copy"),
            ) {
                Text(stringResource(R.string.file_ops_action_copy))
            }
            TextButton(
                onClick = onMove,
                modifier = Modifier.testTag("file_ops_action_move"),
            ) {
                Text(stringResource(R.string.file_ops_action_move))
            }
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("file_ops_action_delete"),
            ) {
                Text(stringResource(R.string.file_ops_action_delete))
            }
        }
    }
}

/**
 * The rename dialog (issue #30): prefilled with the current base name
 * (extension stripped --- it is restored on submit), live-validated by the
 * pure [checkRenameInput], confirm enabled only for valid input.
 */
@Composable
private fun FileOpsRenameDialog(
    prefill: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Re-seeds once the (fast) provider lookup lands; typing before that is
    // impossible to validate meaningfully, so the field starts disabled-looking
    // (empty + confirm disabled).
    var text by remember(prefill) { mutableStateOf(prefill ?: "") }
    val check = remember(text) { checkRenameInput(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = check is RenameCheck.Valid,
            ) {
                Text(stringResource(R.string.file_ops_action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = { Text(stringResource(R.string.file_ops_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.file_ops_rename_label)) },
                isError = text.isNotBlank() && check !is RenameCheck.Valid,
                singleLine = true,
                supportingText = {
                    if (text.isNotBlank() && check !is RenameCheck.Valid) {
                        Text(stringResource(R.string.file_ops_rename_invalid))
                    }
                },
                modifier = Modifier.testTag("file_ops_dialog_rename_field"),
            )
        },
        modifier = Modifier.testTag("file_ops_dialog_rename"),
    )
}

/**
 * The delete confirmation (issue #30): explicit "permanent, no recycle bin
 * yet" wording per the slice contract --- v1 deletes are not reversible and
 * the user must hear that before confirming, not after.
 */
@Composable
private fun FileOpsDeleteDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.file_ops_delete_confirm_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = {
            Text(
                pluralStringResource(
                    R.plurals.file_ops_delete_confirm_title, count, count,
                ),
            )
        },
        text = { Text(stringResource(R.string.file_ops_delete_confirm_body)) },
        modifier = Modifier.testTag("file_ops_dialog_delete"),
    )
}

/**
 * The move-target album picker (issue #30): the SAME album list the Albums
 * tab queries (`MediaStoreSource.queryAlbums`, issue #29) with the existing
 * item-count plurals. Tapping a row confirms; loading shows a small spinner
 * (the query re-runs on every open, so counts stay honest after moves).
 */
@Composable
private fun FileOpsAlbumPickerDialog(
    albums: List<Album>?,
    onPick: (Album) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Row-tap IS the confirmation; there is no extra confirm button.
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = { Text(stringResource(R.string.file_ops_move_title)) },
        text = {
            if (albums == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                LazyColumn(
                    // Bounded height: a LazyColumn inside an AlertDialog
                    // must never measure with infinite constraints.
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .testTag("file_ops_dialog_albums"),
                ) {
                    items(albums, key = { it.bucketId }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(album) }
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                                .testTag("file_ops_album_row"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    text = album.bucketName,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = pluralStringResource(
                                        R.plurals.album_item_count,
                                        album.itemCount,
                                        album.itemCount,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        modifier = Modifier.testTag("file_ops_dialog_albums_root"),
    )
}

/**
 * The cancellable batch progress dialog (issue #30): "Moving 3 of 12…"
 * driven by the ViewModel's `Running` progress, shown only for batches over
 * [FileOpsViewModel.PROGRESS_DIALOG_THRESHOLD] items. Not dismissible by
 * tapping outside --- cancelling is an explicit decision.
 */
@Composable
private fun FileOpsProgressDialog(
    kind: FileOpKind,
    done: Int,
    total: Int,
    onCancel: () -> Unit,
) {
    val messageRes = when (kind) {
        FileOpKind.COPY -> R.string.file_ops_progress_copy
        FileOpKind.MOVE -> R.string.file_ops_progress_move
        FileOpKind.DELETE -> R.string.file_ops_progress_delete
        // Unreachable today (rename is single-selection, under the dialog
        // threshold); the mapping keeps the dialog total for a future
        // multi-rename without re-introducing a when-shaped hole.
        FileOpKind.RENAME -> R.string.file_ops_progress_rename
    }
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = {
            Text(
                text = stringResource(messageRes, done, total),
                // r40 a11y: the batch progress updates politely announce.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        modifier = Modifier.testTag("file_ops_progress"),
    )
}

/**
 * The move-to-vault confirmation (issue #32): states exactly what happens
 * per ADR-0017 --- the media is sealed into the encrypted vault and the
 * originals are removed from the gallery; from then on they are reachable
 * only through the vault.
 */
@Composable
private fun VaultMoveDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // r50 ux fix: moving originals into the vault is the
                // irreversible-without-the-vault operation --- the confirm
                // reads destructive, like every sibling removal dialog.
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("vault_move_confirm_yes"),
            ) {
                Text(stringResource(R.string.vault_move_confirm_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = {
            Text(
                pluralStringResource(
                    R.plurals.vault_move_confirm_title, count, count,
                ),
            )
        },
        text = { Text(stringResource(R.string.vault_move_confirm_body)) },
        modifier = Modifier.testTag("vault_dialog_move"),
    )
}

/**
 * The cancellable move-to-vault progress dialog (issue #32): "Moving 3 of
 * 12 to the vault…" over the vault ViewModel's busy progress, shown for
 * every move batch (bytes are copied; see `PhotosTimeline`). Not
 * dismissible by tapping outside --- cancelling is an explicit decision.
 */
@Composable
private fun VaultMoveProgressDialog(
    done: Int,
    total: Int,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = {
            Text(
                text = stringResource(R.string.vault_progress_move, done, total),
                // r40 a11y: the batch progress updates politely announce.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        modifier = Modifier.testTag("vault_progress"),
    )
}
