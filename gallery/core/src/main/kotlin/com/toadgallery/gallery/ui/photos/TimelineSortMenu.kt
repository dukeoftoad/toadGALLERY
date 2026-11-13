package com.toadgallery.gallery.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.TimelineSort
import androidx.compose.material3.MaterialTheme

/**
 * The timeline sort control (issue #66): a menu-icon app-bar action opening
 * a two-entry dropdown --- Newest first / Oldest first, the current
 * direction check-marked.
 *
 * Shared by the Photos timeline and the album detail screen (the same cross-
 * package reuse as [FileOpsViewModel]): both own a
 * [TimelineSort]-per-session ViewModel state and rebuild their data pipeline
 * on change; this composable is purely presentational and stateless beyond
 * the open/closed flag.
 *
 * Icons come from the material-icons CORE set the repo already depends on
 * (MoreVert, Check --- no extended-icons dependency; r50 swapped the old
 * hamburger glyph to the overflow idiom the menu actually is); the sort
 * DIRECTION itself is a [TimelineSort] value, so the label mapping below
 * stays in the UI layer and the data enum stays resource-free (the
 * `SearchQuery` purity convention).
 *
 * r76.1: `compact = true` renders the anchor as the prototype header's
 * 32dp circular affordance (the Photos sticky header's small trailing
 * buttons) instead of the 48dp app-bar IconButton --- same testTag, same
 * menu, only the anchor's chrome changes.
 */
@Composable
fun TimelineSortMenu(
    current: TimelineSort,
    onChange: (TimelineSort) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        if (compact) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { expanded = true }
                    .testTag("timeline_sort_menu"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    // r50 ux fix: the hamburger glyph (Menu) universally means a
                    // navigation drawer --- the two-entry sort dropdown behind it
                    // was undiscoverable and misleading. MoreVert is the overflow
                    // idiom this menu actually is (both stay in the CORE set).
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.cd_sort_menu),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            IconButton(
                onClick = { expanded = true },
                modifier = Modifier.testTag("timeline_sort_menu"),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.cd_sort_menu),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            TimelineSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes)) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                    // The current direction is check-marked, mirroring the
                    // selection idiom of filter menus.
                    trailingIcon = if (option == current) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null, // the item text carries the state
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** The localized label of one sort direction (UI-layer resource mapping). */
private val TimelineSort.labelRes: Int
    get() = when (this) {
        TimelineSort.NEWEST_FIRST -> R.string.sort_newest_first
        TimelineSort.OLDEST_FIRST -> R.string.sort_oldest_first
    }
