package com.toadgallery.gallery.ui.trash

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.DataSizeFormats
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.ui.StateScreen
import com.toadgallery.gallery.ui.media.LoadingState
import com.toadgallery.gallery.ui.media.MediaThumb
import com.toadgallery.gallery.ui.media.SelectionCheck
import com.toadgallery.gallery.ui.settings.LayoutStyle
import com.toadgallery.gallery.ui.settings.SettingsViewModel

/**
 * The Recycle bin screen (issue #31): trashed images/videos (MediaStore
 * `IS_TRASHED = 1`, API 30+) with per-selection Restore and
 * delete-permanently, plus an "Empty" action in the app bar.
 *
 * UX follows the sub-page contract (issue #29): no bottom bar, own top app
 * bar with Up. The trash store's ~30-day retention is the system's (see
 * [TrashViewModel]); the screen narrates counts/size and routes every
 * mutation through one consent request per action. Tapping a cell toggles
 * selection --- there is no viewer entry here (trashed rows return to the
 * timeline on restore; keeping this surface lean).
 *
 * Appearance (r19a): the shell-scoped [settingsViewModel] feeds the
 * configured column count and the GRID layout's square cells into the
 * grid --- the SAME wiring PhotosScreen/AlbumDetailScreen use, so every
 * media grid in the app honors the setting (this screen used to hardcode
 * `StaggeredGridCells.Fixed(3)`).
 *
 * Privacy (ADR-0006/ADR-0014): content URIs only, no INTERNET permission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    settingsViewModel: SettingsViewModel,
    viewModel: TrashViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val appearance by settingsViewModel.appearance.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        viewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is TrashEvent.RequestConsent -> consentLauncher.launch(
                    IntentSenderRequest.Builder(event.intentSender).build(),
                )
                is TrashEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    message = context.getString(event.messageRes),
                    duration = SnackbarDuration.Short,
                )
                is TrashEvent.ShowPlural -> snackbarHostState.showSnackbar(
                    message = context.resources.getQuantityString(
                        event.messageRes, event.count, event.count,
                    ),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    // Selection lives here (not in the grid) because the confirm dialogs
    // operate on exactly the selected items.
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }
    val ready = state as? TrashUiState.Ready
    val selectedItems = ready?.items?.filter { it.uri.toString() in selectedKeys } ?: emptyList()

    // Screen-local dialog state (one-tap decisions, nothing survives death).
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEmptyConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.trash_title),
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
                    if (ready != null && ready.items.isNotEmpty()) {
                        TextButton(
                            onClick = { showEmptyConfirm = true },
                            modifier = Modifier.testTag("trash_action_empty"),
                        ) {
                            Text(stringResource(R.string.trash_action_empty))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        when (val s = state) {
            TrashUiState.Loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LoadingState(tag = "trash_state_loading")
            }
            TrashUiState.Unsupported -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("trash_state_unsupported"),
            ) {
                StateScreen(
                    iconRes = R.drawable.ic_trash,
                    titleRes = R.string.trash_unsupported_title,
                    bodyRes = R.string.trash_unsupported_body,
                )
            }
            TrashUiState.Error -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("trash_state_error"),
            ) {
                StateScreen(
                    iconRes = R.drawable.ic_trash,
                    titleRes = R.string.trash_error_title,
                    bodyRes = R.string.trash_error_body,
                    actionRes = R.string.state_action_retry,
                    onAction = { viewModel.refresh() },
                )
            }
            is TrashUiState.Ready -> {
                if (s.items.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("trash_state_empty"),
                    ) {
                        StateScreen(
                            iconRes = R.drawable.ic_trash,
                            titleRes = R.string.trash_empty_title,
                            bodyRes = R.string.trash_empty_body,
                        )
                    }
                } else {
                    TrashContent(
                        items = s.items,
                        totalBytes = s.totalBytes,
                        columns = appearance.columns,
                        uniformCells = appearance.layout == LayoutStyle.GRID,
                        selectedKeys = selectedKeys,
                        onToggle = { key ->
                            selectedKeys = if (key in selectedKeys) {
                                selectedKeys - key
                            } else {
                                selectedKeys + key
                            }
                        },
                        onRestore = {
                            viewModel.requestRestore(selectedItems)
                            selectedKeys = emptySet()
                        },
                        onDeleteClick = { showDeleteConfirm = true },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    if (showDeleteConfirm && selectedItems.isNotEmpty()) {
        TrashConfirmDialog(
            titlePluralCount = selectedItems.size,
            titleRes = R.plurals.file_ops_delete_confirm_title,
            count = selectedItems.size,
            confirmRes = R.string.trash_action_delete,
            onConfirm = {
                viewModel.requestDeleteForever(selectedItems)
                showDeleteConfirm = false
                selectedKeys = emptySet()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
    if (showEmptyConfirm && ready != null && ready.items.isNotEmpty()) {
        TrashConfirmDialog(
            titlePluralCount = -1, // static title string
            titleRes = R.string.trash_empty_confirm_title,
            count = ready.items.size,
            confirmRes = R.string.trash_action_delete_all,
            onConfirm = {
                viewModel.requestDeleteForever(ready.items)
                showEmptyConfirm = false
                selectedKeys = emptySet()
            },
            onDismiss = { showEmptyConfirm = false },
        )
    }
}

/**
 * The Ready trash body: a "n items · size" header over a selectable
 * staggered grid, with the Restore / Delete-permanently action row pinned
 * under it. Tap toggles a cell's selection (keys are `uri.toString()`,
 * matching the #30 selection idiom); actions hand the selected items to the
 * ViewModel and the post-consent reload naturally collapses the selection
 * when items leave the trash.
 *
 * Since r19a the grid takes its column count and the GRID layout's square
 * cells from the shell's appearance settings (the same contract
 * AlbumDetailScreen's grid follows); grouping stays a Photos-timeline
 * feature --- the bin is a flat list.
 */
@Composable
private fun TrashContent(
    items: List<MediaItem>,
    totalBytes: Long,
    columns: Int,
    uniformCells: Boolean,
    selectedKeys: Set<String>,
    onToggle: (String) -> Unit,
    onRestore: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = pluralStringResource(R.plurals.trash_header_items, items.size, items.size) +
                "  ·  " + DataSizeFormats.formatBytes(totalBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("trash_screen_grid"),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalItemSpacing = 2.dp,
        ) {
            items(
                items = items,
                key = { item -> item.uri.toString() },
            ) { item ->
                TrashCell(
                    item = item,
                    selected = item.uri.toString() in selectedKeys,
                    forceSquare = uniformCells,
                    onTap = { onToggle(item.uri.toString()) },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(
                onClick = onRestore,
                enabled = selectedKeys.isNotEmpty(),
                modifier = Modifier.testTag("trash_action_restore"),
            ) {
                Text(stringResource(R.string.trash_action_restore))
            }
            Button(
                onClick = onDeleteClick,
                enabled = selectedKeys.isNotEmpty(),
                modifier = Modifier.testTag("trash_action_delete"),
            ) {
                Text(stringResource(R.string.trash_action_delete))
            }
        }
    }
}

/**
 * One selectable trash cell: [MediaThumb] plus the shared check-badge idiom
 * (same scrim chip as the #30 selection overlay) so trash selection reads
 * exactly like timeline selection. [forceSquare] is the appearance
 * slice's GRID-layout pass-through (r19a), straight into [MediaThumb].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashCell(
    item: MediaItem,
    selected: Boolean,
    forceSquare: Boolean,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier.combinedClickable(
            // r50 a11y fix: the tap toggles selection (no long-press here by
            // design) --- say so instead of a bare "double-tap to activate".
            onClickLabel = stringResource(R.string.a11y_grid_toggle_select),
            onClick = onTap,
        ),
    ) {
        MediaThumb(item = item, forceSquare = forceSquare)
        // r50 ux fix: the cell used to draw its own drifted badge copy ---
        // an EMPTY circle while unselected (the other three surfaces show
        // the dim-white check) and no `file_ops_cell_check` test tag. The
        // shared SelectionCheck closes the drift (the trash selection now
        // reads exactly like timeline selection, as the KDoc promised).
        SelectionCheck(
            selected = selected,
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

/**
 * The confirm dialog shared by delete-selection and empty-all. The title is
 * either a plural (driven by [titlePluralCount] >= 0) or a static string;
 * the body always states the count + permanence.
 */
@Composable
private fun TrashConfirmDialog(
    titlePluralCount: Int,
    titleRes: Int,
    count: Int,
    confirmRes: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (titlePluralCount >= 0) {
                    pluralStringResource(titleRes, titlePluralCount, titlePluralCount)
                } else {
                    stringResource(titleRes)
                },
            )
        },
        text = {
            Text(
                text = pluralStringResource(
                    R.plurals.trash_delete_confirm_items, count, count,
                ) + " " + stringResource(R.string.trash_delete_confirm_body),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // r47 ux fix: the destructive confirm carries the error
                // color, matching every other delete dialog in the app
                // (Photos / album detail / vault) so a destructive action
                // never reads as a neutral one.
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(confirmRes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
    )
}
