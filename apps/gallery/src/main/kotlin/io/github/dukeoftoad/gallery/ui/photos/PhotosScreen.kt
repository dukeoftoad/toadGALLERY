package io.github.dukeoftoad.gallery.ui.photos

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.Album
import io.github.dukeoftoad.gallery.data.FileOpKind
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.RenameCheck
import io.github.dukeoftoad.gallery.data.baseNameOf
import io.github.dukeoftoad.gallery.data.checkRenameInput
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.MediaPagingGrid
import io.github.dukeoftoad.gallery.ui.media.MediaPermissionState
import io.github.dukeoftoad.gallery.ui.media.rememberMediaPermissionRequester

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
 * ## File operations (issue #30) --- Photos tab only in this slice
 * [FileOpsViewModel] owns the op state machine; this screen owns every
 * piece of activity-result/visual machinery it needs:
 *  - long-press enters selection (grid cells in [MediaPagingGrid] carry the
 *    check overlay; `file_ops_cell_check`), tap toggles;
 *  - a selection app bar (`file_ops_app_bar`) with the count and an exit
 *    action, plus an actions bar (`file_ops_actions`): Rename (single
 *    selection only), Copy (in-place duplicate), Move (album picker
 *    dialog), Delete (confirmation dialog with explicit "permanent, no
 *    recycle bin yet" wording);
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
    fileOpsViewModel: FileOpsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val fileOpsState by fileOpsViewModel.state.collectAsState()

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
                else -> PhotosTimeline(
                    lazyItems = lazyItems,
                    fileOpsViewModel = fileOpsViewModel,
                    fileOpsState = fileOpsState,
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}

/**
 * The ready Photos timeline with its file-ops chrome (issue #30): the paged
 * grid, the selection app bar + actions while selecting, the undo snackbar
 * host, and the four file-ops dialogs (rename, delete confirmation, album
 * picker, batch progress).
 *
 * Grid refreshing after ops is NOT done by hand: MediaStore writes fire the
 * existing ContentObserver -> paging invalidation (issue #38); see the
 * `FileOpsViewModel` KDoc and `docs/architecture/file-ops.md`.
 */
@Composable
private fun PhotosTimeline(
    lazyItems: LazyPagingItems<MediaItem>,
    fileOpsViewModel: FileOpsViewModel,
    fileOpsState: FileOpsUiState,
    snackbarHostState: SnackbarHostState,
) {
    val selection = fileOpsState as? FileOpsUiState.Selection
    val running = fileOpsState as? FileOpsUiState.Running

    // Local dialog state: dialog visibility is screen-local, op runs live in
    // the ViewModel. Nothing here needs to survive process death --- an
    // in-flight op is provider-side, and the dialogs are one-tap decisions.
    var renameTarget by remember { mutableStateOf<MediaItem?>(null) }
    var renamePrefill by remember { mutableStateOf<String?>(null) }
    var deleteCount by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showAlbumPicker by remember { mutableStateOf(false) }
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
                )
                Box(modifier = Modifier.weight(1f)) {
                    PhotosGrid(
                        lazyItems = lazyItems,
                        fileOpsViewModel = fileOpsViewModel,
                        selectionActive = true,
                        selectedKeys = selection.items.mapTo(HashSet()) { it.uri.toString() },
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
            // Plain timeline: identical to the pre-#30 render (cells still
            // carry long-press to enter selection; the overlay stays hidden).
            PhotosGrid(
                lazyItems = lazyItems,
                fileOpsViewModel = fileOpsViewModel,
                selectionActive = false,
                selectedKeys = emptySet(),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
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
}

/**
 * The timeline grid call, shared by plain and selection layouts so the
 * `photos_state_grid` tag, footer and selection wiring cannot diverge.
 */
@Composable
private fun PhotosGrid(
    lazyItems: LazyPagingItems<MediaItem>,
    fileOpsViewModel: FileOpsViewModel,
    selectionActive: Boolean,
    selectedKeys: Set<String>,
) {
    MediaPagingGrid(
        items = lazyItems,
        modifier = Modifier.testTag("photos_state_grid"),
        selectionActive = selectionActive,
        selectedKeys = selectedKeys,
        onItemTap = { item -> fileOpsViewModel.onItemTapped(item) },
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
 * `settings_picked_count`) plus an exit action. `windowInsets = 0`: the
 * shell Scaffold already consumed the status-bar inset, so a default
 * TopAppBar would double-pad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileOpsSelectionBar(
    count: Int,
    onExit: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(pluralStringResource(R.plurals.file_ops_selection_count, count, count))
        },
        navigationIcon = {
            IconButton(onClick = onExit) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_file_ops_exit_selection),
                )
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
        tonalElevation = 3.dp,
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
        title = { Text(stringResource(messageRes, done, total)) },
        modifier = Modifier.testTag("file_ops_progress"),
    )
}
