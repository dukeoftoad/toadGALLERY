package com.toadgallery.gallery.ui.things

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.ui.media.LoadingState
import com.toadgallery.gallery.ui.media.MediaThumb

/**
 * The Things collection detail sub-screen (r39, the ente-port exit
 * round; the PersonDetail pattern, Things-flavored): one named
 * semantic collection's members in the CLIP ranking's RELEVANCE order,
 * photo taps opening the shared full-screen viewer, and the overflow
 * menu carrying the three management actions:
 *  - Rename (dialog; a blank input is refused --- the name is the
 *    label, the id is the identity and never changes);
 *  - Edit query (dialog; a blank input resets to the collection's
 *    name --- the "Dogs over dogs" default; the membership refreshes
 *    on confirm);
 *  - Delete (confirmation dialog; the screen pops back on confirm ---
 *    the delete dialog itself was the confirmation, the PersonDetail
 *    hide contract).
 *
 * The honest states render as themselves: Loading (the shared
 * LoadingState), Missing (a one-frame transition on the way to
 * `onBack()` --- never fake stale content), Error (the failure's own
 * text verbatim + Retry), and Empty (a collection whose query matched
 * nothing within the membership cut --- its own copy + the edit-query
 * affordance, NOT an error).
 *
 * The viewer hand-off: photo taps open the EXISTING full-screen viewer
 * through the shell's `(window, index)` hand-off, exactly like the
 * person-detail grid; the members are REAL [MediaItem]s from the same
 * MediaStore query Magic Search uses (no synthesized fields).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThingsDetailScreen(
    collectionId: String,
    onBack: () -> Unit,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit = { _, _ -> },
) {
    // The Application is process-scoped; capturing it for the ViewModel
    // factory is the standard Android pattern (outlives the composition).
    val context = LocalContext.current
    val viewModel: ThingsDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ThingsDetailViewModel.factory(
            application = context.applicationContext as android.app.Application,
            collectionId = collectionId,
        ),
    )
    val state by viewModel.state.collectAsState()

    // The collection is gone (deleted on the Albums tab meanwhile, or a
    // stale route). Pop back --- never fake stale content.
    LaunchedEffect(state) {
        if (state is ThingsDetailState.Missing) onBack()
    }

    // Screen-local dialog state: dialogs are one-tap decisions, nothing
    // here needs to survive process death (the mutations live in the
    // ViewModel and re-derive from the live store).
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showRequery by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val ready = state as? ThingsDetailState.Ready
    val empty = state as? ThingsDetailState.Empty

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            ready != null -> ready.collection.name
                            empty != null -> empty.collection.name
                            else -> stringResource(R.string.things_detail_fallback_title)
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
                    // Management only makes sense over an existing
                    // collection; Loading/Error show no menu (an action
                    // that cannot run is a lying control).
                    if (ready != null || empty != null) {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.testTag("things_detail_overflow"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.cd_things_overflow),
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.things_detail_menu_rename)) },
                                onClick = {
                                    menuOpen = false
                                    showRename = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.things_detail_menu_requery)) },
                                onClick = {
                                    menuOpen = false
                                    showRequery = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.things_detail_menu_delete)) },
                                onClick = {
                                    menuOpen = false
                                    showDeleteConfirm = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (val s = state) {
                is ThingsDetailState.Loading ->
                    LoadingState(tag = "things_detail_state_loading")

                is ThingsDetailState.Missing ->
                    // A one-frame transition state on the way to onBack().
                    LoadingState(tag = "things_detail_state_missing")

                is ThingsDetailState.Error ->
                    ThingsStateMessage(
                        titleRes = R.string.things_detail_error_title,
                        // The failure's own text, surfaced verbatim.
                        body = s.message,
                        actionRes = R.string.state_action_retry,
                        onAction = viewModel::retry,
                        tag = "things_detail_state_error",
                    )

                is ThingsDetailState.Empty -> ThingsStateMessage(
                    titleRes = R.string.things_detail_empty_title,
                    bodyRes = R.string.things_detail_empty_body,
                    actionRes = R.string.things_detail_menu_requery,
                    onAction = { showRequery = true },
                    tag = "things_detail_state_empty",
                )

                is ThingsDetailState.Ready -> {
                    if (s.items.isEmpty()) {
                        // Unreachable by construction (an empty membership
                        // is the Empty state), kept honest anyway.
                        ThingsStateMessage(
                            titleRes = R.string.things_detail_empty_title,
                            bodyRes = R.string.things_detail_empty_body,
                            tag = "things_detail_state_empty",
                        )
                    } else {
                        ThingsMemberGrid(
                            items = s.items,
                            onOpenViewer = onOpenViewer,
                        )
                    }
                }
            }
        }
    }

    if (showRename && (ready != null || empty != null)) {
        val current = (ready?.collection ?: empty?.collection)
        var name by remember { mutableStateOf(current?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.things_rename_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.things_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRename = false
                        viewModel.rename(name)
                    },
                    // A blank name is refused by the repository; a disabled
                    // confirm beats a lying one (the merge-menu rule).
                    enabled = name.isNotBlank(),
                ) {
                    Text(stringResource(R.string.things_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.file_ops_action_cancel))
                }
            },
        )
    }

    if (showRequery && (ready != null || empty != null)) {
        val current = (ready?.collection ?: empty?.collection)
        var query by remember { mutableStateOf(current?.query ?: "") }
        AlertDialog(
            onDismissRequest = { showRequery = false },
            title = { Text(stringResource(R.string.things_requery_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.things_requery_dialog_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.things_query_label)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRequery = false
                        viewModel.requery(query)
                    },
                    // A blank query is ALLOWED (it resets to the name ---
                    // the documented default), so the confirm stays enabled.
                ) {
                    Text(stringResource(R.string.things_action_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRequery = false }) {
                    Text(stringResource(R.string.file_ops_action_cancel))
                }
            },
        )
    }

    if (showDeleteConfirm && (ready != null || empty != null)) {
        val current = (ready?.collection ?: empty?.collection)
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.things_delete_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.things_delete_dialog_body,
                        current?.name ?: "",
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                }) {
                    Text(stringResource(R.string.things_delete_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.file_ops_action_cancel))
                }
            },
        )
    }
}

/**
 * The member grid: a uniform 3-column grid of the collection's members
 * in the CLIP ranking's RELEVANCE order (the display order; the
 * vecdb's own), taps opening the shared viewer over the WHOLE ranked
 * list at the tapped index (the person-grid contract).
 */
@Composable
private fun ThingsMemberGrid(
    items: List<MediaItem>,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(items) { index, item ->
            // The whole cell is the tap target (the MediaGrid convention);
            // MediaThumb owns the square aspect ratio.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenViewer(items, index) },
            ) {
                MediaThumb(item = item, forceSquare = true)
            }
        }
    }
}

/**
 * The honest-state card (the PeopleStateMessage shape, in-file because
 * the People one is private): title + body (or verbatim error text) +
 * optional action, tagged for UI tests.
 */
@Composable
private fun ThingsStateMessage(
    titleRes: Int,
    bodyRes: Int? = null,
    body: String? = null,
    actionRes: Int? = null,
    onAction: (() -> Unit)? = null,
    tag: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(tag),
        )
        val message = body ?: bodyRes?.let { stringResource(it) }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (actionRes != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(actionRes))
            }
        }
    }
}
