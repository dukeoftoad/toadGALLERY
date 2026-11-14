package com.toadgallery.gallery.ui.people

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.ml.people.PersonPhoto
import com.toadgallery.gallery.ml.people.VisiblePerson
import com.toadgallery.gallery.ui.media.LoadingState
import com.toadgallery.gallery.ui.media.MediaThumb
import com.toadgallery.gallery.ui.media.SelectionCheck

/**
 * The Person detail screen (r25, Workstream 6): one face cluster's photos
 * plus the cluster-management operations. A sub-screen without the
 * bottom bar (non-top-level route), entered from the People screen (or
 * the Albums People section's person cards).
 *
 * ## Content states (no dead ends)
 *  - Loading -> [LoadingState] tagged `person_detail_state_loading`;
 *  - Error -> the honest error layout with the failure's OWN message and
 *    a retry action, tagged `person_detail_state_error`;
 *  - Missing -> the live-table truth "this cluster no longer exists"
 *    (merged away / hidden / emptied / re-clustered): the screen pops
 *    back immediately (a [LoadingState]-tagged frame keeps the inset
 *    honest during the transition) --- faking a grid of stale photos
 *    would be the opposite of honest;
 *  - Ready + empty photos -> the honest empty state tagged
 *    `person_detail_state_empty` (faces exist in the cluster but no
 *    MediaStore row resolves --- deleted/trashed/vault-moved media);
 *  - Ready -> the photo grid tagged `person_detail_state_grid`.
 *
 * ## The grid + selection (the AlbumDetailScreen contract)
 * The SAME staggered grid + selection chrome as the album detail view:
 * long-press enters selection (`file_ops_cell_check` badge + scrim
 * overlay), tap toggles membership while selecting and opens the
 * full-screen VIEWER otherwise, and the selection app bar replaces the
 * person bar with the shared selection-count plurals. The actions row
 * carries the one r25-specific action: "Remove from person"
 * (`removeFacesFromCluster` over the picked photos --- the "this is not
 * them" correction; the photos themselves are untouched), honest
 * face-count snackbar on success.
 *
 * ## The overflow menu
 *  - Rename (dialog; blank input REMOVES the name --- "Unnamed" is the
 *    honest default, so the confirm stays enabled with a hint);
 *  - Merge with… (dialog over the OTHER visible clusters --- hidden
 *    ones are excluded because merging into a person the user cannot
 *    see would be a dead-end action; disabled entirely when no other
 *    person exists --- a disabled control beats a lying one);
 *  - Hide this person (confirmation dialog; the copy names the honest
 *    way back: Reset face index in Settings, which erases the whole
 *    index by design).
 *
 * ## The viewer hand-off (the honest synthesis choice)
 * Photo taps open the EXISTING full-screen viewer through the shell's
 * `(window, index)` hand-off, exactly like `AlbumDetailScreen` --- NOT
 * the metadata Details route. The synthesized [MediaItem]s are fully
 * honest, because the r23/r24 pipeline indexes JPEGs only:
 * `mimeType = "image/jpeg"` and `isVideo = false` are TRUE for every id
 * the cluster table can hold, and `id`/`uri`/`dateAddedSec`/`width`/
 * `height` come from the very MediaStore row [PersonPhoto] was built
 * from ([queryPersonPhotos]); `durationMs = 0` is the image truth.
 * There is no field the viewer needs that this screen cannot
 * truthfully provide, so the richer route (the viewer) is the honest
 * choice; the Details route would only add a detour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    clusterId: String,
    onBack: () -> Unit,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit = { _, _ -> },
) {
    // The Application is process-scoped; capturing it for the ViewModel
    // factory is the standard Android pattern (outlives the composition).
    val context = LocalContext.current
    val viewModel: PersonDetailViewModel = viewModel(
        factory = PersonDetailViewModel.factory(
            application = context.applicationContext as android.app.Application,
            clusterId = clusterId,
        )
    )
    val state by viewModel.state.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selectionActive = selection.active

    // While a selection is active, system back EXITS the selection instead
    // of popping the destination (the r19 Photos/album-grid contract).
    BackHandler(enabled = selectionActive) {
        viewModel.exitSelection()
    }

    // One-shot snackbar events (the FileOpsEvent shape: resource ids +
    // args only; strings resolve against the current configuration at
    // display time).
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is PersonDetailEvent.ShowSnackbar -> {
                    val message = if (event.pluralCount >= 0) {
                        context.resources.getQuantityString(
                            event.messageRes, event.pluralCount, event.pluralCount,
                        )
                    } else if (event.formatArgs.isEmpty()) {
                        context.getString(event.messageRes)
                    } else {
                        context.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    }
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }

    // The live-table truth: the cluster is gone (merged away / hidden /
    // emptied / re-clustered). Pop back --- never fake stale content.
    LaunchedEffect(state) {
        if (state is PersonDetailState.Missing) onBack()
    }

    // Screen-local dialog state: dialogs are one-tap decisions, nothing
    // here needs to survive process death (the ops themselves live in
    // the ViewModel and re-derive from the live store).
    var showRename by remember { mutableStateOf(false) }
    var showMerge by remember { mutableStateOf(false) }
    var showHideConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val ready = state as? PersonDetailState.Ready

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selectionActive) {
                PersonSelectionBar(
                    count = selection.selectedCount,
                    onExit = viewModel::exitSelection,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = when {
                                ready != null -> ready.person.name
                                    ?: stringResource(R.string.people_unnamed)
                                else -> stringResource(R.string.person_detail_fallback_title)
                            },
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
                    actions = {
                        // Cluster management only makes sense over a Ready
                        // state; Loading/Error show no menu (an action that
                        // cannot run is a lying control).
                        if (ready != null) {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.testTag("person_detail_overflow"),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(
                                        R.string.cd_person_overflow,
                                    ),
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.person_detail_menu_rename)) },
                                    onClick = {
                                        menuOpen = false
                                        showRename = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.person_detail_menu_merge)) },
                                    // No other visible person -> nothing to
                                    // merge into: disabled, not hidden (a
                                    // disabled control beats a lying one).
                                    enabled = ready.mergeTargets.isNotEmpty(),
                                    onClick = {
                                        menuOpen = false
                                        showMerge = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.person_detail_menu_hide)) },
                                    onClick = {
                                        menuOpen = false
                                        showHideConfirm = true
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val s = state) {
                is PersonDetailState.Loading ->
                    LoadingState(tag = "person_detail_state_loading")

                is PersonDetailState.Missing ->
                    // A one-frame transition state on the way to onBack().
                    LoadingState(tag = "person_detail_state_missing")

                is PersonDetailState.Error ->
                    PeopleStateMessage(
                        titleRes = R.string.person_detail_error_title,
                        // The failure's own text, surfaced verbatim.
                        body = s.message,
                        actionRes = R.string.state_action_retry,
                        onAction = viewModel::retry,
                        tag = "person_detail_state_error",
                    )

                is PersonDetailState.Ready -> {
                    if (s.photos.isEmpty()) {
                        PeopleStateMessage(
                            titleRes = R.string.person_detail_empty_title,
                            bodyRes = R.string.person_detail_empty_body,
                            tag = "person_detail_state_empty",
                        )
                    } else if (selectionActive) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f)) {
                                PersonPhotoGrid(
                                    photos = s.photos,
                                    selection = selection,
                                    onPhotoTapped = viewModel::onPhotoTapped,
                                    onPhotoLongPressed = viewModel::onPhotoLongPressed,
                                    onOpenViewer = onOpenViewer,
                                )
                            }
                            PersonSelectionActions(
                                selectionEmpty = selection.selectedCount == 0,
                                onRemove = viewModel::removeFromPerson,
                            )
                        }
                    } else {
                        PersonPhotoGrid(
                            photos = s.photos,
                            selection = selection,
                            onPhotoTapped = viewModel::onPhotoTapped,
                            onPhotoLongPressed = viewModel::onPhotoLongPressed,
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

    // ---- Dialogs (r25 cluster management) ----

    if (showRename && ready != null) {
        PersonRenameDialog(
            prefill = ready.person.name.orEmpty(),
            onConfirm = { name ->
                viewModel.rename(name)
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
    if (showMerge && ready != null) {
        PersonMergeDialog(
            targets = ready.mergeTargets,
            onPick = { target ->
                viewModel.mergeInto(target.clusterId)
                showMerge = false
            },
            onDismiss = { showMerge = false },
        )
    }
    if (showHideConfirm) {
        PersonHideDialog(
            onConfirm = {
                viewModel.hide()
                showHideConfirm = false
            },
            onDismiss = { showHideConfirm = false },
        )
    }
}

/**
 * The person's photo grid (r25): the SAME cell/spacing contract as the
 * album detail grid (`MediaThumb` cells, 2.dp gaps, `fileId` keys ---
 * one cell per PHOTO, the repository already deduped multi-face ids).
 * Long-press enters selection, tap toggles while selecting and opens
 * the shell's viewer hand-off otherwise (the window is the
 * materialized photo list --- [PersonPhoto.toMediaItem] synthesizes the
 * honest `MediaItem`s, see the screen KDoc).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonPhotoGrid(
    photos: List<PersonPhoto>,
    selection: PersonDetailSelection,
    onPhotoTapped: (fileId: Long) -> Unit,
    onPhotoLongPressed: (fileId: Long) -> Unit,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .testTag("person_detail_state_grid"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalItemSpacing = 2.dp,
    ) {
        itemsIndexed(
            items = photos,
            key = { _, photo -> photo.fileId },
        ) { index, photo ->
            val picked = photo.fileId in selection.selectedFileIds
            Box(
                modifier = Modifier.combinedClickable(
                    // r50 a11y fix: click labels (the MediaPagingGrid cell's
                    // fix, person-detail edition).
                    onClickLabel = stringResource(
                        if (selection.active) {
                            R.string.a11y_grid_toggle_select
                        } else {
                            R.string.a11y_grid_open
                        },
                    ),
                    onLongClickLabel = stringResource(R.string.a11y_grid_long_press_select),
                    onClick = {
                        if (selection.active) {
                            onPhotoTapped(photo.fileId)
                        } else {
                            // The window is built at tap time from the
                            // current snapshot --- every field is real (see
                            // the screen KDoc's synthesis note).
                            onOpenViewer(photos.map { it.toMediaItem() }, index)
                        }
                    },
                    onLongClick = { onPhotoLongPressed(photo.fileId) },
                ),
            ) {
                MediaThumb(item = photo.toMediaItem())
                if (selection.active) {
                    if (picked) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                                ),
                        )
                    }
                    SelectionCheck(
                        selected = picked,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
            }
        }
    }
}

/**
 * Maps one [PersonPhoto] to the [MediaItem] the viewer hand-off expects
 * (r25). Every field is HONEST (the screen KDoc's pinned choice): the
 * r23/r24 pipeline indexes JPEGs only, so `image/jpeg` + `isVideo=false`
 * hold for every cluster file id by construction; `id`/`uri`/
 * `dateAddedSec`/`width`/`height` come from the same MediaStore row the
 * photo was read from; `durationMs = 0` is the image truth. A null
 * `dateAddedSec` (a cursor without the column --- not a MediaStore
 * shape in practice) maps to `0`, MediaItem's existing "unknown" story
 * for trailing values.
 */
private fun PersonPhoto.toMediaItem(): MediaItem = MediaItem(
    id = fileId,
    uri = uri,
    mimeType = "image/jpeg",
    dateAddedSec = dateAddedSec ?: 0L,
    isVideo = false,
    width = width,
    height = height,
)

/**
 * Selection-mode top bar (r25): the live count (the shared
 * `file_ops_selection_count` plurals) plus an exit action --- the same
 * bar the album detail view shows for its selection mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonSelectionBar(
    count: Int,
    onExit: () -> Unit,
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
        modifier = Modifier.testTag("person_selection_app_bar"),
    )
}

/**
 * The selection actions row (r25): ONE action --- "Remove from person"
 * (`removeFacesFromCluster` over the picked photos; the photos
 * themselves are untouched) --- in the album grid's bottom-row shape.
 * Disabled while nothing is picked (the mode can legally hold zero
 * picks; a disabled action beats a lying one).
 */
@Composable
private fun PersonSelectionActions(
    selectionEmpty: Boolean,
    onRemove: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("person_selection_actions"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            TextButton(
                onClick = onRemove,
                enabled = !selectionEmpty,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.testTag("person_action_remove"),
            ) {
                Text(stringResource(R.string.person_action_remove))
            }
        }
    }
}

/**
 * The rename dialog (r25): prefilled with the person's current name;
 * confirm stays enabled even when the field is BLANK, because a blank
 * submit is the honest "remove the name" action (back to "Unnamed" ---
 * the state store's `renamed` rule, pinned by its tests). The supporting
 * hint says exactly that.
 */
@Composable
private fun PersonRenameDialog(
    prefill: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(prefill) { mutableStateOf(prefill) }
    // r50 ux fix: the field takes focus the moment the dialog opens and
    // the keyboard's action key is Done, submitting (a blank submit stays
    // the honest remove-the-name action --- the KDoc's rule).
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                modifier = Modifier.testTag("person_dialog_rename_confirm"),
            ) {
                Text(stringResource(R.string.person_rename_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = { Text(stringResource(R.string.person_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.person_rename_label)) },
                singleLine = true,
                supportingText = {
                    // The blank-input rule, said out loud (it is a REAL
                    // action here, unlike the file rename's invalid-blank).
                    if (text.isBlank()) {
                        Text(stringResource(R.string.person_rename_blank_hint))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                modifier = Modifier
                    .testTag("person_dialog_rename_field")
                    .focusRequester(focusRequester),
            )
        },
        modifier = Modifier.testTag("person_dialog_rename"),
    )
}

/**
 * The merge-target picker (r25): the OTHER visible clusters, each row
 * showing the circular cover avatar (or the honest glyph avatar), the
 * name/Unnamed and the live face count. Row-tap IS the confirmation ---
 * the dialog body states the direction and irreversibility first (the
 * `AlbumPickerDialog` contract).
 */
@Composable
private fun PersonMergeDialog(
    targets: List<VisiblePerson>,
    onPick: (VisiblePerson) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = { Text(stringResource(R.string.person_merge_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.person_merge_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 320.dp)
                        .testTag("person_dialog_merge_list"),
                ) {
                    items(targets) { target ->
                        MergeTargetRow(
                            target = target,
                            onPick = { onPick(target) },
                        )
                    }
                }
            }
        },
        modifier = Modifier.testTag("person_dialog_merge"),
    )
}

/** One merge-target row (r25): avatar circle + name + face count. */
@Composable
private fun MergeTargetRow(
    target: VisiblePerson,
    onPick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val cover = personCoverUri(target.coverFileId)
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = null, // decorative; the row's name carries meaning
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null, // decorative; the row's name carries meaning
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = target.name ?: stringResource(R.string.people_unnamed),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )
        Text(
            text = pluralStringResource(
                R.plurals.people_face_count,
                target.faceCount,
                target.faceCount,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The hide confirmation (r25; copy updated by the r26 follow-ups
 * round): honest copy about what hiding means (out of every People
 * surface) and the r26 way back --- the People screen's HIDDEN
 * SECTION with its per-person Unhide action. The person stays in the
 * index with their name; the r24 "Reset face index" (which erases the
 * whole index by design) is no longer the only path, so the dialog
 * no longer threatens it.
 */
@Composable
private fun PersonHideDialog(
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
                modifier = Modifier.testTag("person_dialog_hide_confirm"),
            ) {
                Text(stringResource(R.string.person_hide_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = { Text(stringResource(R.string.person_hide_title)) },
        text = { Text(stringResource(R.string.person_hide_body)) },
        modifier = Modifier.testTag("person_dialog_hide"),
    )
}
