package io.github.dukeoftoad.gallery.ui.photos

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.TimelineSort

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
 * (Menu, Check --- no extended-icons dependency); the sort DIRECTION itself
 * is a [TimelineSort] value, so the label mapping below stays in the UI
 * layer and the data enum stays resource-free (the `SearchQuery` purity
 * convention).
 */
@Composable
fun TimelineSortMenu(
    current: TimelineSort,
    onChange: (TimelineSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag("timeline_sort_menu"),
        ) {
            Icon(
                imageVector = Icons.Filled.Menu,
                contentDescription = stringResource(R.string.cd_sort_menu),
            )
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
