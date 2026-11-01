package io.github.dukeoftoad.gallery.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ui.settings.GroupBy
import java.util.Locale

/**
 * The paged staggered media grid for the Photos timeline (Phase 3, issues
 * #28 + #34; append-state footer since #38; selection mode since #30).
 *
 * Same visual contract as [MediaGrid] --- aspect-aware staggered cells via
 * [MediaThumb] (which also carries the video-duration overlay and the GIF
 * badge, issue #33), `columns` columns, 2.dp gaps, `uri.toString()` keys ---
 * but the rows come from Paging 3: [LazyPagingItems] instead of a
 * materialized `List`. The grid renders `itemCount` slots and Paging appends
 * more windows as the user scrolls; cell keys stay the stable content URI,
 * exactly as [MediaGrid] does, so cell, key and spacing behavior can never
 * diverge between the two grids.
 *
 * ## Grouped headers (appearance settings slice, roadmap Phase 3)
 * [groupBy] (default [GroupBy.NONE], the flat pre-slice timeline) layers
 * full-line date headers between the cells via the [TimelineRow] model:
 * the rows are recomputed REACTIVELY from `items.itemSnapshotList` (a
 * snapshot-state-backed list) inside a `derivedStateOf`, so appended windows
 * and refreshes regroup without any manual invalidation. The `remember` is
 * keyed on [groupBy] AND [items] --- an unkeyed remember would keep the
 * first composition's closure alive across a settings change or a paging
 * generation swap and render a stale grouping. Keys: cells keep
 * `uri.toString()`; headers use `"header_" + groupKey`; a cell whose
 * snapshot slot is somehow null falls back to `"cell_<index>"` (the row
 * model skips nulls, so this only covers a snapshot mutating between the
 * rows computation and the key call). Headers render with
 * [StaggeredGridItemSpan.FullLine] and the `timeline_header` test tag.
 *
 * ## Uniform grid mode (appearance settings slice, roadmap Phase 3)
 * [uniformCells] (default `false`) switches every cell to the Ente-style
 * GRID layout --- [MediaThumb]'s `forceSquare` --- while the grid itself
 * stays a staggered layout (uniform cells in a staggered grid pack as a
 * plain square grid; one composable, two looks). The defaults keep every
 * pre-slice call site rendering exactly what it rendered before.
 *
 * ## Footer slot (issue #38, finding 2)
 * [footer] is an optional trailing composable rendered as the grid's LAST
 * item (`key = "paging_footer"`, so it can never collide with the
 * uri-keyed media items). The Photos timeline passes a slot that reads
 * `items.loadState.append`: a small centered spinner while the next window
 * loads, an honest error + retry row when an append fails, and nothing at
 * all once the append settles (the item stays composed but empty --- the
 * cheapest correct behavior for a conditional footer).
 *
 * ## Selection mode (issue #30) and viewer taps (issue #50)
 * The optional selection parameters add the Photos-tab multi-select UI
 * (long-press enters, tap toggles) WITHOUT touching [MediaThumb]: cells in
 * selection mode wrap the thumb in a `combinedClickable` box that overlays a
 * checkmark badge (`file_ops_cell_check`, present on every cell so the mode
 * is visible before anything is picked) and a primary-color scrim on
 * selected cells. All defaults keep the non-selecting call sites rendering
 * exactly what they rendered before this slice; selection semantics
 * (state, app bar, undo) are owned by `PhotosScreen`/`FileOpsViewModel`
 * outside this shared grid. Selection keys are `uri.toString()` values,
 * matching the grid's LazyGrid keys one-to-one.
 *
 * Since issue #50 the tap callback is `onItemTap: (index, item) -> Unit` ---
 * the index is the slot inside `LazyPagingItems` (snapshot positions, which
 * is exactly what `itemSnapshotList` maps back to), so the Photos screen can
 * hand the shell the tap-time window + position for the full-screen viewer
 * while selection mode keeps toggling membership. Long-press is unchanged.
 * Grouping does not disturb this: a [TimelineRow.Cell] CARRIES the snapshot
 * index, so selection and taps keep operating on the same positions the
 * ungrouped grid used.
 *
 * Privacy: mirrors [MediaGrid] --- every thumbnail decodes from a local
 * content URI. Coil 2.x ships its HTTP fetcher inside the default
 * `coil-compose` registry (there is no separate network artifact to exclude
 * until Coil 3), so the offline guarantee does NOT rest on Coil's
 * packaging: it rests on the app holding no INTERNET permission ---
 * enforced by the ADR-0014 merged-manifest gate and proven by
 * `PrivacyEgressTest` --- which makes the OS refuse every socket a hostile
 * model value could ever provoke.
 */
@Composable
fun MediaPagingGrid(
    items: LazyPagingItems<MediaItem>,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    groupBy: GroupBy = GroupBy.NONE,
    uniformCells: Boolean = false,
    selectionActive: Boolean = false,
    selectedKeys: Set<String> = emptySet(),
    onItemTap: ((index: Int, item: MediaItem) -> Unit)? = null,
    onItemLongPress: ((MediaItem) -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    // Grouped-timeline rows: recomputed reactively (itemSnapshotList is
    // snapshot-state backed, so appended windows and refreshes re-run the
    // derivation) and re-derived when the grouping itself or the paging
    // instance changes (see the KDoc --- an unkeyed remember would go stale).
    val rows = remember(groupBy, items) {
        derivedStateOf { buildTimelineRows(items.itemSnapshotList, groupBy) }
    }.value
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalItemSpacing = 2.dp,
    ) {
        items(
            count = rows.size,
            key = { rowIndex ->
                when (val row = rows[rowIndex]) {
                    is TimelineRow.Header -> "header_" + row.groupKey
                    is TimelineRow.Cell -> {
                        // Same uri keys the ungrouped grid used; the
                        // cell_<index> fallback only fires if the snapshot
                        // mutated under the derived rows (a null slot there
                        // renders nothing, like the pre-grouping grid did).
                        items[row.index]?.uri?.toString() ?: "cell_${row.index}"
                    }
                }
            },
            span = { rowIndex ->
                // Headers span the full line; media cells stay in a single
                // lane (the span API is non-null in foundation 1.7).
                if (rows[rowIndex] is TimelineRow.Header) {
                    StaggeredGridItemSpan.FullLine
                } else {
                    StaggeredGridItemSpan.SingleLane
                }
            },
        ) { rowIndex ->
            when (val row = rows[rowIndex]) {
                is TimelineRow.Header -> Text(
                    text = formatGroupLabel(row, Locale.getDefault()),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                        .testTag("timeline_header"),
                )
                is TimelineRow.Cell -> items[row.index]?.let { item ->
                    if (onItemTap != null && onItemLongPress != null) {
                        // Interactive cell: long-press enters selection even in
                        // the plain timeline (the ViewModel no-ops below API 29,
                        // where file ops are hidden); the overlay chrome appears
                        // only while a selection is active.
                        SelectableMediaCell(
                            index = row.index,
                            item = item,
                            forceSquare = uniformCells,
                            selected = item.uri.toString() in selectedKeys,
                            overlayVisible = selectionActive,
                            onItemTap = onItemTap,
                            onItemLongPress = onItemLongPress,
                        )
                    } else {
                        // Byte-identical to the pre-#30 render (and to MediaGrid).
                        MediaThumb(item = item, forceSquare = uniformCells)
                    }
                }
            }
        }
        // Append-state footer slot (issue #38): the caller decides what (if
        // anything) each append LoadState renders; a null footer keeps the
        // item list byte-identical to the pre-#38 grid.
        footer?.let { footerContent ->
            item(key = "paging_footer") { footerContent() }
        }
    }
}

/**
 * One interactive grid cell (issue #30): [MediaThumb] wrapped in
 * tap/long-press handling plus the selection chrome, kept OUT of
 * [MediaThumb] so the shared thumbnail composable stays selection-free
 * (other grids reuse it unchanged).
 *
 * - long-press enters selection (or is a no-op while already selecting or
 *   below API 29 --- the ViewModel decides);
 * - tap toggles membership while selecting;
 * - the check badge shows on EVERY cell while selection is active so the
 *   mode is self-evident; picked cells add a primary-color scrim
 *   (Mi-Gallery-style visual anchoring);
 * - [forceSquare] is the appearance slice's GRID-layout pass-through (see
 *   [MediaPagingGrid]'s KDoc): straight into [MediaThumb].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectableMediaCell(
    index: Int,
    item: MediaItem,
    forceSquare: Boolean,
    selected: Boolean,
    overlayVisible: Boolean,
    onItemTap: (Int, MediaItem) -> Unit,
    onItemLongPress: (MediaItem) -> Unit,
) {
    Box(
        modifier = Modifier.combinedClickable(
            onClick = { onItemTap(index, item) },
            onLongClick = { onItemLongPress(item) },
        ),
    ) {
        MediaThumb(item = item, forceSquare = forceSquare)
        if (overlayVisible) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                )
            }
            SelectionCheck(
                selected = selected,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/**
 * The scrim-chip checkmark badge of the selection overlay (issue #30):
 * same scrim idiom as [MediaThumb]'s badges (dark rounded chip), with the
 * check in the theme's primary color when the cell is picked and dimmed
 * white otherwise. Decorative by design --- the selection count in the app
 * bar carries the state for accessibility.
 */
@Composable
private fun SelectionCheck(
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
