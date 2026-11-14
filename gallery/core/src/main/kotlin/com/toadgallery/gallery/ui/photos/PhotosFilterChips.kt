package com.toadgallery.gallery.ui.photos

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.Album
import com.toadgallery.gallery.data.filters.DerivedCollections
import com.toadgallery.gallery.data.filters.FilterMediaType
import com.toadgallery.gallery.data.filters.TimelineFilter
import com.toadgallery.gallery.data.filters.withFavoritesToggled
import com.toadgallery.gallery.data.filters.withTypeToggled
import com.toadgallery.gallery.ui.components.ToadChipDefaults
import com.toadgallery.gallery.ui.theme.ToadSpacing

/**
 * The Photos timeline's smart-filter chips row (r17 round, Phase 4
 * still-open item "Aves-style collections and smart-filter chips"):
 * a horizontally scrolling row of Material3 [FilterChip]s under the
 * header bar, narrowing the timeline through the PhotosViewModel's
 * [TimelineFilter] (see `PhotosViewModel` for how each data path applies
 * it --- always inside the window query, never client-side after paging).
 *
 * ## Chip semantics (multi-select where it composes sensibly)
 *  - **Folder** (r19b, the LEADING chip): unlike the row's toggles, its
 *    value space is the device's bucket list, not a fixed enum --- so a
 *    tap never toggles it in place; it opens the picker sheet below.
 *    Unselected it reads "All folders"; selected it carries the picked
 *    folder's display name (the ViewModel's name state, dropped
 *    automatically whenever the bucket dimension changes through any
 *    other writer --- All chip, Clear action, Collections hand-off). The
 *    bucket dimension AND-composes with types/favorites (the engine's
 *    contract) on BOTH backends, and clearing it is the sheet's "All
 *    folders" row, which leaves the other dimensions untouched (the All
 *    chip and Clear action reset EVERYTHING). One scope-honest edge: on
 *    the pre-30 snapshot path a bucket filter fails CLOSED in the engine
 *    (a visibly empty timeline, never a silently unfiltered one) --- and
 *    this row stays rendered in that nothing-matched state, so the way
 *    back is always one tap, not a dead UI.
 *  - **All**: selected exactly while the filter is INACTIVE; tapping it
 *    clears every dimension. Together with the trailing Clear action this
 *    is the row's clear-all affordance (both set the same fresh
 *    [TimelineFilter] --- one state, two discoverable affordances).
 *  - **Photos / Videos / GIFs**: independent multi-select toggles. GIFs
 *    are a SUBSET of photos in the engine's reduction
 *    (`FilterTypeCoverage`), so Photos+GIFs reads back as just Photos ---
 *    the engine owns that rule; the chips only toggle membership.
 *  - **Favorites**: an independent toggle AND-composed with the type set.
 *    Hidden below Android 10 (Q): the `is_favorite` provider column is
 *    Q+ (see `Favorites`), so below Q every read maps `isFavorite =
 *    false` and the chip would promise a view that can never fill --- the
 *    same tiering the favorites pseudo-album card and the viewer's
 *    favorite toggle use.
 *
 * The row shows with the plain timeline (it hides in selection mode
 * together with the header bar, exactly like `TimelineSortMenu`) AND with
 * the nothing-matched empty state, where its All/Clear affordances are the
 * way back from an over-narrow filter --- the filter is ViewModel state
 * that survives tab switches, so a vanished row would strand the user.
 * State is hoisted: this composable is purely presentational over
 * (filter, onChange) plus the folder picker's value/callback params, so
 * the ViewModel's per-session state survives config changes and drives
 * both the chips and the data pipeline from one source. The picker
 * sheet's VISIBILITY is screen-local (a one-tap decision, like the
 * file-ops dialogs) and owned here so both rendered call sites --- the
 * plain timeline and the nothing-matched state --- get the same chip
 * behavior for free.
 *
 * Chips stay text-only (no icons): the core material-icons set has no
 * photo/video glyphs (the extended-icons dependency is deliberately
 * absent), and the codebase's convention is text over mystery icons
 * (see `FileOpsSelectionActions`). The folder chip's label is the one
 * DYNAMIC label in the row, so it alone carries the name-ellipsis
 * discipline the Albums cards use (`maxLines = 1` + ellipsis); the fixed
 * labels of the other chips cannot outgrow their chips.
 *
 * r77.7 chrome: every chip renders through [ToadChipDefaults] --- the
 * prototype's chip vocabulary (selected = solid `primary` pill with an
 * `onPrimary` label, unselected = `bgSurface` + `textMuted` + a `1dp
 * borderSubtle` stroke) instead of the stock M3 secondaryContainer fill
 * that read as a near-black pill on the toad palettes. The M3 component
 * itself stays (ripple, semantics, touch floor); only the color/border
 * slots follow the palette.
 */
@Composable
fun PhotosFilterChipsRow(
    filter: TimelineFilter,
    onChange: (TimelineFilter) -> Unit,
    selectedFolderName: String?,
    folders: PhotosFoldersState,
    onFoldersRefresh: () -> Unit,
    onFolderPick: (Album) -> Unit,
    onFolderClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFolderPicker by remember { mutableStateOf(false) }

    LazyRow(
        modifier = modifier.testTag("photos_filter_chips"),
        contentPadding = PaddingValues(horizontal = ToadSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(ToadSpacing.sm),
    ) {
        item {
            FilterChip(
                selected = filter.bucketId != null,
                onClick = { showFolderPicker = true },
                label = {
                    // The active label is the picked folder's display
                    // name; the generic fallback covers a bucket whose
                    // name this UI never learned (no such filter writer
                    // exists today --- an honest generic beats a stale
                    // name).
                    Text(
                        text = if (filter.bucketId == null) {
                            stringResource(R.string.photos_filter_folder_all)
                        } else {
                            selectedFolderName
                                ?: stringResource(R.string.photos_filter_folder)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag("photos_filter_chip_folder"),
                colors = ToadChipDefaults.colors(),
                border = ToadChipDefaults.border(),
            )
        }
        item {
            FilterChip(
                selected = !filter.isActive,
                onClick = { onChange(TimelineFilter()) },
                label = { Text(stringResource(R.string.photos_filter_all)) },
                modifier = Modifier.testTag("photos_filter_chip_all"),
                colors = ToadChipDefaults.colors(),
                border = ToadChipDefaults.border(),
            )
        }
        items(FilterMediaType.entries) { type ->
            FilterChip(
                selected = type in filter.types,
                onClick = { onChange(filter.withTypeToggled(type)) },
                label = { Text(stringResource(type.labelRes)) },
                modifier = Modifier.testTag(
                    "photos_filter_chip_" + type.name.lowercase(),
                ),
                colors = ToadChipDefaults.colors(),
                border = ToadChipDefaults.border(),
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            item {
                FilterChip(
                    selected = filter.favoritesOnly,
                    onClick = { onChange(filter.withFavoritesToggled()) },
                    label = { Text(stringResource(R.string.photos_filter_favorites)) },
                    modifier = Modifier.testTag("photos_filter_chip_favorites"),
                    colors = ToadChipDefaults.colors(),
                    border = ToadChipDefaults.border(),
                )
            }
        }
        if (filter.isActive) {
            item {
                // The explicit clear-all affordance: same effect as the All
                // chip, spelled as the action it is. A TextButton (not a
                // chip) reads as an action, not a category.
                TextButton(
                    onClick = { onChange(TimelineFilter()) },
                    modifier = Modifier.testTag("photos_filter_clear"),
                ) {
                    Text(stringResource(R.string.photos_filter_clear))
                }
            }
        }
    }

    if (showFolderPicker) {
        PhotosFolderPickerSheet(
            folders = folders,
            onRefresh = onFoldersRefresh,
            onPick = { album ->
                onFolderPick(album)
                showFolderPicker = false
            },
            onClear = {
                onFolderClear()
                showFolderPicker = false
            },
            onDismiss = { showFolderPicker = false },
        )
    }
}

/**
 * The folder-picker sheet (r19b): a Material 3 [ModalBottomSheet] (the
 * sheet idiom the viewer's info sheet and the Map tab's location sheet
 * use) listing the device's media folders for the folder chip --- the
 * SAME `MediaStoreSource.queryAlbums` list the move picker shows (bucket
 * names + live item counts), plus a leading "All folders" row that
 * clears ONLY the folder dimension (the chips row's All chip and Clear
 * action clear everything).
 *
 * ## Honest states, never a silent empty list
 *  - [PhotosFoldersState.Loading]: the picker spinner idiom (the move
 *    picker's), shown while the (re)query runs.
 *  - [PhotosFoldersState.Error]: honest error copy with the shared retry
 *    action (`state_action_retry`); retry re-queries through [onRefresh].
 *    A provider failure must never masquerade as "no folders".
 *  - [PhotosFoldersState.Ready] with an empty list: the REAL empty
 *    result --- its own honest line, distinct from the failure above.
 *  - [PhotosFoldersState.Ready] with folders: one row per folder, the
 *    move picker's name+count row shape.
 *
 * The (re)query rides the OPEN, not the screen's composition: the sheet
 * only composes while open, so [onRefresh] fires once per open (and once
 * per retry) and the counts stay honest as ops land --- the move picker's
 * refresh contract.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotosFolderPickerSheet(
    folders: PhotosFoldersState,
    onRefresh: () -> Unit,
    onPick: (Album) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("photos_folder_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = ToadSpacing.xxl, end = ToadSpacing.xxl)
                .padding(bottom = ToadSpacing.xxxl),
        ) {
            Text(
                text = stringResource(
                    R.string.photos_filter_folder_sheet_title,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            when (folders) {
                PhotosFoldersState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }

                PhotosFoldersState.Error -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ToadSpacing.xl)
                        .testTag("photos_folder_sheet_error"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(
                            R.string.photos_filter_folder_sheet_error_title,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(
                            R.string.photos_filter_folder_sheet_error_body,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = ToadSpacing.sm),
                    )
                    TextButton(
                        onClick = onRefresh,
                        modifier = Modifier.padding(top = ToadSpacing.md),
                    ) {
                        Text(stringResource(R.string.state_action_retry))
                    }
                }

                is PhotosFoldersState.Ready ->
                    if (folders.folders.isEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.photos_filter_folder_sheet_empty,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = ToadSpacing.xl)
                                .testTag("photos_folder_sheet_empty"),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = ToadSpacing.lg),
                        ) {
                            item {
                                PhotosFolderRow(
                                    name = stringResource(
                                        R.string.photos_filter_folder_all,
                                    ),
                                    countText = null,
                                    onClick = onClear,
                                    tag = "photos_folder_row_all",
                                )
                            }
                            items(
                                folders.folders,
                                key = { it.bucketId },
                            ) { album ->
                                PhotosFolderRow(
                                    name = album.bucketName,
                                    countText = pluralStringResource(
                                        R.plurals.album_item_count,
                                        album.itemCount,
                                        album.itemCount,
                                    ),
                                    onClick = { onPick(album) },
                                    tag = "photos_folder_row",
                                )
                            }
                        }
                    }
            }
        }
    }
}

/**
 * One row of the folder-picker sheet: the move picker's row shape (name +
 * live count) reused verbatim so the two pickers read as one idiom. The
 * "All folders" row passes no count --- there is no folder behind it,
 * only the absence of the restriction.
 */
@Composable
private fun PhotosFolderRow(
    name: String,
    countText: String?,
    onClick: () -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (countText != null) {
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The localized label of one media-type chip (UI-layer resource mapping). */
private val FilterMediaType.labelRes: Int
    get() = when (this) {
        FilterMediaType.PHOTOS -> R.string.photos_filter_photos
        FilterMediaType.VIDEOS -> R.string.photos_filter_videos
        FilterMediaType.GIFS -> R.string.photos_filter_gifs
    }

/**
 * The localized label of one derived-collection id (UI-layer resource
 * mapping, shared by the Albums Collections section; null for an unknown
 * id so a future preset the mapping predates renders nothing rather than
 * a wrong label --- `DerivedCollectionsTest` pins today's id set, so
 * adding a preset forces this mapping along).
 */
internal fun collectionLabelRes(id: String): Int? = when (id) {
    DerivedCollections.ID_PHOTOS -> R.string.photos_filter_photos
    DerivedCollections.ID_VIDEOS -> R.string.photos_filter_videos
    DerivedCollections.ID_GIFS -> R.string.photos_filter_gifs
    DerivedCollections.ID_FAVORITES -> R.string.photos_filter_favorites
    else -> null
}
