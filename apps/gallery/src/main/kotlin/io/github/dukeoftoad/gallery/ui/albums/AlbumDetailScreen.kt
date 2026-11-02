package io.github.dukeoftoad.gallery.ui.albums

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.Album
import io.github.dukeoftoad.gallery.data.Favorites
import io.github.dukeoftoad.gallery.data.FileOpKind
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.RenameCheck
import io.github.dukeoftoad.gallery.data.baseNameOf
import io.github.dukeoftoad.gallery.data.checkRenameInput
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.ErrorState
import io.github.dukeoftoad.gallery.ui.media.LoadingState
import io.github.dukeoftoad.gallery.ui.media.MediaThumb
import io.github.dukeoftoad.gallery.ui.photos.FileOpsEvent
import io.github.dukeoftoad.gallery.ui.photos.FileOpsUiState
import io.github.dukeoftoad.gallery.ui.photos.FileOpsViewModel
import io.github.dukeoftoad.gallery.ui.photos.TimelineSortMenu
import io.github.dukeoftoad.gallery.ui.settings.Appearance
import io.github.dukeoftoad.gallery.ui.settings.LayoutStyle
import io.github.dukeoftoad.gallery.ui.settings.SettingsViewModel

/**
 * Album detail view (Phase 3, issue #29; selection mode + file ops since
 * #64).
 *
 * A sub-screen without the bottom navigation bar (hidden by the shell for
 * non-top-level routes): top app bar with the bucket name + back arrow, and
 * a staggered grid (same cell/spacing contract as `ui/media/MediaGrid`)
 * filtered to the bucket's contents. Entered only after the Albums tab
 * loaded with granted access; a mid-session revoke surfaces as the error
 * screen with a retry (the ViewModel maps query failures to
 * `AlbumDetailState.Error`, r19 --- pre-r19 they were masked as an empty
 * grid).
 *
 * States render through the shared screen-state components (issue #13):
 *  - Loading -> [LoadingState] tagged `album_detail_state_loading`, wrapped in
 *    an inset-padded `Box` so the spinner centers below the top app bar
 *    (same geometry as the pre-#13 inline spinner)
 *  - Error -> the shared [ErrorState] tagged `album_detail_state_error`
 *    (r19; the exact tag docs/architecture/screen-states.md §2 reserved for
 *    this call site): a provider failure --- permission revoked mid-session,
 *    a provider SQLiteException, a missing column --- renders the honest
 *    error screen with a retry action that re-queries. Pre-r19 the ViewModel
 *    defensively masked such failures as an EMPTY bucket --- the honesty gap
 *    this KDoc used to carry as a documented residual; this and the Albums
 *    tab's own Error arm are the shared component's first live call sites.
 *  - Ready + empty -> the frozen `StateScreen` empty layout, wrapped in a Box
 *    tagged `album_detail_state_empty` (StateScreen is frozen and carries no
 *    tag parameter; the wrapper supplies the test surface, issue #38.7)
 *  - Ready + items -> the grid, tagged `album_detail_state_grid` (#64, so
 *    the selecting/plain variants share one state surface, like
 *    `photos_state_grid`)
 *
 * ## Selection mode + file operations (issue #64)
 * Full parity with the Photos-tab UX (#30), driven by the SAME screen-
 * agnostic [FileOpsViewModel] instantiated in this screen's scope (its
 * KDoc's decoupling claim holds --- zero changes were needed):
 *  - long-press enters selection (cells gain the same checkmark-chip /
 *    primary-scrim overlay the Photos grid uses; `file_ops_cell_check`),
 *    tap toggles membership instead of opening the viewer;
 *  - the selection app bar (`file_ops_app_bar`: live count, close/exit)
 *    replaces the album app bar, with the actions row (`file_ops_actions`)
 *    at the bottom: Rename (single selection only), Copy, Move, Delete
 *    (confirmation with the same honest recycle-bin/permanent wording);
 *  - the rename dialog prefills the base name (extension stripped) and
 *    live-validates with the pure [checkRenameInput];
 *  - the move picker lists the albums from the ViewModel's `albums` flow
 *    EXCLUDING the current bucket ([moveTargets] --- the item is already
 *    there; PhotosScreen has no analogous exclusion because its timeline
 *    spans all buckets);
 *  - the consent launcher (`StartIntentSenderForResult` + `onConsentResult`),
 *    undo snackbars and the cancellable batch progress dialog (batches over
 *    [FileOpsViewModel.PROGRESS_DIALOG_THRESHOLD]) behave exactly as on the
 *    Photos tab; Move-to-vault (issue #32) intentionally stays a Photos-tab
 *    action (the vault chrome is scoped there).
 *
 * Below Android 10 the whole surface is inert by construction:
 * [FileOpsViewModel] holds a null `MediaFileOps` there, so long-press is a
 * no-op and the grid renders exactly as before #64 --- the same reliance
 * on ViewModel inertness (not a UI-side `SDK_INT` branch) as PhotosScreen.
 *
 * ## Sorting (issue #66)
 * The plain album bar gains the shared [TimelineSortMenu] action (Newest
 * first / Oldest first, per-session ViewModel state; every change
 * re-queries the bucket). The favorites pseudo-bucket hides it --- its
 * contents stay newest-first in v1 (the issue's documented scope), and a
 * hidden control beats a lying one.
 *
 * ## Appearance settings (roadmap Phase 3 slice)
 * The shell-scoped [settingsViewModel] (the Settings tab's instance) drives
 * this screen's grid, too: the configured column count and the GRID
 * layout's square cells replace the hardcoded 3-column staggered look.
 * Group-by stays a Photos-timeline feature here (see [AlbumDetailGrid]).
 * Defaults (COLLAGE/3) keep the pre-slice render byte-identical.
 *
 * ## Refresh after ops (issue #64)
 * The bucket list is a snapshot (no Paging pipeline), so the screen adds
 * the honest triggers [AlbumDetailViewModel] documents: the R+ MediaStore
 * observer re-query, plus a re-query whenever the file-ops machine lands
 * back in `Idle` after activity (covers Android 10, where ops are enabled
 * but no observer exists). See [AlbumDetailViewModel] for the residual
 * Android-10 undo-staleness note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    bucketId: Long,
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit = { _, _ -> },
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
    // The #30 file-ops state machine, screen-agnostic by design (issue
    // #64): one instance in THIS screen's scope, so selections and runs
    // never leak across the Photos tab and this detail view.
    val fileOpsViewModel: FileOpsViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val appearance by settingsViewModel.appearance.collectAsState()
    val fileOpsState by fileOpsViewModel.state.collectAsState()
    val selection = fileOpsState as? FileOpsUiState.Selection
    val running = fileOpsState as? FileOpsUiState.Running

    // r19: while a selection is active, system back EXITS the selection
    // instead of popping the destination --- pre-r19 back navigated away
    // mid-mode, when the user expectation (and every sibling surface's
    // behavior) is that back first leaves the mode. During a batch run
    // `selection` is null (Running replaces Selection in the file-ops state
    // machine), so back keeps its normal meaning there; `exitSelection`
    // itself also guards Running (a no-op mid-batch), so the two guards
    // agree.
    BackHandler(enabled = selection != null) {
        fileOpsViewModel.exitSelection()
    }

    // ---- File-ops plumbing (issue #64, same shape as PhotosScreen) ----
    val snackbarHostState = remember { SnackbarHostState() }
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

    // Snapshot refresh trigger (issue #64): re-query the bucket whenever
    // the file-ops machine settles back into Idle after activity (batch
    // finished, consent resolved, trash round-trip done, or the selection
    // an op cleared). The observer in AlbumDetailViewModel covers R+ for
    // every OTHER MediaStore writer; this leg covers Android 10 ops.
    var opsStateWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(fileOpsState) {
        val active = fileOpsState !is FileOpsUiState.Idle
        if (!active && opsStateWasActive) viewModel.refresh()
        opsStateWasActive = active
    }

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

    Scaffold(
        topBar = {
            if (selection != null) {
                // Selection mode (issue #64): the count bar replaces the
                // album bar. `windowInsets` stays the Scaffold default ---
                // this bar sits in the same slot the album bar did, so the
                // status-bar inset must be consumed exactly once here.
                AlbumSelectionBar(
                    count = selection.items.size,
                    onExit = fileOpsViewModel::exitSelection,
                )
            } else {
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
                    actions = {
                        // Issue #66: the shared sort menu. Hidden for the
                        // favorites pseudo-bucket --- its detail stays
                        // newest-first in v1 (see AlbumDetailViewModel).
                        if (!Favorites.isFavoritesBucket(bucketId)) {
                            TimelineSortMenu(
                                current = sort,
                                onChange = viewModel::onSortChange,
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val s = state) {
                is AlbumDetailState.Loading -> Box(
                    // Keep the shared spinner inside the app-bar inset (the same
                    // geometry the pre-#13 inline layout had); LoadingState itself
                    // fills the constraints it receives.
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LoadingState(tag = "album_detail_state_loading")
                }
                is AlbumDetailState.Error -> ErrorState(
                    titleRes = R.string.album_detail_error_title,
                    bodyRes = R.string.album_detail_error_body,
                    actionRes = R.string.state_action_retry,
                    onRetry = viewModel::retry,
                    tag = "album_detail_state_error",
                )
                is AlbumDetailState.Ready -> {
                    if (s.items.isEmpty()) {
                        // StateScreen is frozen without a tag parameter, so the
                        // stable per-screen tag rides on a wrapper Box (#38.7).
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("album_detail_state_empty"),
                        ) {
                            StateScreen(
                                iconRes = R.drawable.ic_nav_albums,
                                titleRes = R.string.album_detail_fallback_title,
                                bodyRes = R.string.album_detail_empty_body,
                            )
                        }
                    } else if (selection != null) {
                        // Selection mode (issue #64): grid keeps its window
                        // math and tag; only the cell overlay + tap semantics
                        // differ, plus the actions row pinned at the bottom.
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f)) {
                                AlbumDetailGrid(
                                    items = s.items,
                                    columns = appearance.columns,
                                    uniformCells = appearance.layout == LayoutStyle.GRID,
                                    selectionActive = true,
                                    selectedKeys = selection.items
                                        .mapTo(HashSet()) { it.uri.toString() },
                                    fileOpsViewModel = fileOpsViewModel,
                                    onOpenViewer = onOpenViewer,
                                )
                            }
                            AlbumSelectionActions(
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
                        // Plain grid: cells still carry long-press to enter
                        // selection (a no-op below Android 10, where the
                        // ViewModel is inert); tap opens the full-screen
                        // viewer (issue #50) with the materialized bucket
                        // list as the window.
                        AlbumDetailGrid(
                            items = s.items,
                            columns = appearance.columns,
                            uniformCells = appearance.layout == LayoutStyle.GRID,
                            selectionActive = false,
                            selectedKeys = emptySet(),
                            fileOpsViewModel = fileOpsViewModel,
                            onOpenViewer = onOpenViewer,
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
            )
        }
    }

    // ---- File-ops dialogs (issue #64; same contracts as PhotosScreen) ----

    renameTarget?.let {
        AlbumRenameDialog(
            prefill = renamePrefill,
            onConfirm = { name ->
                fileOpsViewModel.requestRename(name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    if (showDeleteConfirm) {
        AlbumDeleteDialog(
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
        AlbumPickerDialog(
            // #64: the current bucket is never a legal target --- the
            // picked items live there already (PhotosScreen needs no such
            // filter: its timeline spans all buckets).
            albums = moveTargets(albums, bucketId) { it.bucketId },
            onPick = { album ->
                fileOpsViewModel.requestMove(album)
                showAlbumPicker = false
            },
            onDismiss = { showAlbumPicker = false },
        )
    }
    if (running != null && running.total > FileOpsViewModel.PROGRESS_DIALOG_THRESHOLD) {
        AlbumProgressDialog(
            kind = running.kind,
            done = running.done,
            total = running.total,
            onCancel = fileOpsViewModel::cancelRunning,
        )
    }
}

/**
 * The bucket's staggered grid (issue #64): the same cell/spacing contract
 * as the shared `ui/media/MediaGrid` (`MediaThumb` cells, 2.dp gaps,
 * `uri.toString()` keys), plus the selection wiring
 * `MediaPagingGrid` gives the Photos timeline --- long-press enters
 * selection, tap toggles while selecting and opens the viewer otherwise.
 * The shared grids themselves stay untouched (frozen for concurrent
 * work), so the interactive cell + overlay chrome live here.
 *
 * Since the appearance slice the column count and the GRID layout's square
 * cells come from the shell's [Appearance] (the pre-slice screen hardcoded
 * `StaggeredGridCells.Fixed(3)`); grouping stays OFF here in v1 --- a
 * bucket is usually a single occasion, and date headers inside it would
 * be noise, not orientation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumDetailGrid(
    items: List<MediaItem>,
    columns: Int,
    uniformCells: Boolean,
    selectionActive: Boolean,
    selectedKeys: Set<String>,
    fileOpsViewModel: FileOpsViewModel,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .testTag("album_detail_state_grid"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalItemSpacing = 2.dp,
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> item.uri.toString() },
        ) { index, item ->
            Box(
                modifier = Modifier.combinedClickable(
                    onClick = {
                        if (selectionActive) {
                            fileOpsViewModel.onItemTapped(item)
                        } else {
                            onOpenViewer(items, index)
                        }
                    },
                    // A no-op below Android 10: the ViewModel holds no
                    // MediaFileOps there (read-only tier), so selection
                    // cannot even start --- same gate PhotosScreen relies on.
                    onLongClick = { fileOpsViewModel.onItemLongPressed(item) },
                ),
            ) {
                MediaThumb(item = item, forceSquare = uniformCells)
                if (selectionActive) {
                    if (item.uri.toString() in selectedKeys) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                ),
                        )
                    }
                    AlbumSelectionCheck(
                        selected = item.uri.toString() in selectedKeys,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
        }
    }
}

/**
 * The scrim-chip checkmark badge of the selection overlay (issue #64):
 * the same idiom as the Photos grid's badge (dark rounded chip, check in
 * the theme's primary when picked, dimmed white otherwise). Decorative by
 * design --- the selection count in the app bar carries the state for
 * accessibility.
 */
@Composable
private fun AlbumSelectionCheck(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .testTag("file_ops_cell_check")
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null, // decorative; the app-bar count is the state
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White.copy(alpha = 0.7f)
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Selection-mode top bar (issue #64): the live count (the shared
 * `file_ops_selection_count` plurals) plus an exit action --- the same
 * bar the Photos tab shows, minus the vault action (issue #32 chrome is
 * scoped to the Photos tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSelectionBar(
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
        modifier = Modifier.testTag("file_ops_app_bar"),
    )
}

/**
 * The selection actions row (issue #64): Rename (single selection only),
 * Copy (in-place duplicate), Move, Delete --- the same row and test tags
 * as the Photos tab, reusing its `file_ops_*` strings verbatim.
 */
@Composable
private fun AlbumSelectionActions(
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
 * The rename dialog (issue #64): prefilled with the current base name
 * (extension stripped --- it is restored on submit), live-validated by the
 * pure [checkRenameInput], confirm enabled only for valid input. Same
 * contract as the Photos-tab dialog.
 */
@Composable
private fun AlbumRenameDialog(
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
 * The delete confirmation (issue #64): the shared honest copy (recycle bin
 * on Android 11+, permanent below) --- the `file_ops_delete_confirm_*`
 * strings already carry the right wording for both tiers.
 */
@Composable
private fun AlbumDeleteDialog(
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
 * The move-target album picker (issue #64): the same list dialog as the
 * Photos tab (album rows with the `album_item_count` plurals, row-tap IS
 * the confirmation), minus the current bucket (see [moveTargets]).
 */
@Composable
private fun AlbumPickerDialog(
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
 * The cancellable batch progress dialog (issue #64): "Moving 3 of 12…"
 * over the ViewModel's `Running` progress, shown only for batches over
 * [FileOpsViewModel.PROGRESS_DIALOG_THRESHOLD] items. Not dismissible by
 * tapping outside --- cancelling is an explicit decision.
 */
@Composable
private fun AlbumProgressDialog(
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

/**
 * Move targets for the album-detail picker (issue #64): the picker's album
 * list minus the bucket the screen is showing --- moving an item into the
 * album it already lives in is a no-op at best (and on some providers a
 * duplicate-row error at worst), so the current bucket is never offered.
 * [PhotosScreen][io.github.dukeoftoad.gallery.ui.photos.PhotosScreen] has
 * no analogous filter: its timeline spans every bucket, so no single one
 * is "current".
 *
 * Generic over the element so the rule is unit-testable without
 * constructing `android.net.Uri`-bearing [Album]s on the JVM (same trick
 * as `WindowPagingSourceTest`): production passes `{ it.bucketId }`.
 * Null passes through unchanged --- "still loading" and "no targets" are
 * different states the dialog renders differently.
 */
internal fun <T> moveTargets(
    albums: List<T>?,
    currentBucketId: Long,
    bucketIdOf: (T) -> Long,
): List<T>? = albums?.filterNot { bucketIdOf(it) == currentBucketId }
