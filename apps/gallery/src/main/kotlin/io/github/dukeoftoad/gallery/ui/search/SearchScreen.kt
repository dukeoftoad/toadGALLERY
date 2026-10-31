package io.github.dukeoftoad.gallery.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.LoadingState
import io.github.dukeoftoad.gallery.ui.media.MediaGrid
import io.github.dukeoftoad.gallery.ui.theme.ToadSpacing

/**
 * The Search tab (issue #62, search tab v1): a name query over the media
 * timeline.
 *
 * Layout mirrors the top-level tabs (no app bar of its own; the bottom bar
 * stays): a rounded [OutlinedTextField] (search icon, clear button, IME
 * action Search, focused on entry) over a single-select row of filter chips
 * (All / Photos / Videos), then the state surface:
 *
 *  - Idle + empty field  : the StateScreen invitation (icon + title + body,
 *    the issue #13 visual language);
 *  - Idle + short query  : the "keep typing" hint (the 2-character gate);
 *  - Searching           : the shared [LoadingState];
 *  - NoResults           : StateScreen with the offending query in the body;
 *  - Error               : StateScreen with the shared Retry action;
 *  - Results             : a "n results" header over the SHARED [MediaGrid]
 *    --- the exact cell UX (aspect-aware staggered cells, video duration
 *    overlay, GIF badge) the Photos timeline and album detail render, 3
 *    columns / 2dp gaps / `uri.toString()` keys, taps handing the shell's
 *    `onOpenViewer(window, index)` the full result list.
 *
 * TextField + chips render from the ViewModel's StateFlows (the same source
 * of truth the debounced query pipeline reads); every keystroke goes
 * through `SearchViewModel.onQueryChange`, which owns the 300ms debounce.
 *
 * Accessibility: the field carries a visible label (the IME hint and
 * TalkBack announcement both come from it) and the clear button a
 * `cd_search_clear` content description; result cells behave exactly like
 * the timeline's grid cells ([MediaThumb] already narrates photo/video/GIF).
 *
 * Privacy (ADR-0006 / ADR-0014): results are local content URIs read from
 * MediaStore; the app holds no INTERNET permission, so no search term or
 * thumbnail can ever leave the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()
    val mediaType by viewModel.mediaType.collectAsState()

    // Focus on entry (issue #62): the tab is a search-first surface, so the
    // field takes focus (and the keyboard) the moment the tab opens.
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ToadSpacing.xl),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("search_field"),
            singleLine = true,
            label = { Text(stringResource(R.string.search_field_label)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null, // decorative; the label carries meaning
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.onQueryChange("") },
                        modifier = Modifier.testTag("search_field_clear"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.cd_search_clear),
                        )
                    }
                }
            },
            // ToadShapes.small (8dp) --- the token role for text fields
            // (DesignTokens.kt, issue #11); reached through MaterialTheme
            // so the shape system stays the single source.
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                // The query pipeline is debounced in the ViewModel; the IME
                // action just closes the keyboard so results are visible.
                onSearch = { keyboard?.hide() },
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ToadSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(ToadSpacing.md),
        ) {
            FilterChip(
                selected = mediaType == SearchMediaType.ALL,
                onClick = { viewModel.onMediaTypeChange(SearchMediaType.ALL) },
                label = { Text(stringResource(R.string.search_filter_all)) },
            )
            FilterChip(
                selected = mediaType == SearchMediaType.IMAGES,
                onClick = { viewModel.onMediaTypeChange(SearchMediaType.IMAGES) },
                label = { Text(stringResource(R.string.search_filter_photos)) },
            )
            FilterChip(
                selected = mediaType == SearchMediaType.VIDEOS,
                onClick = { viewModel.onMediaTypeChange(SearchMediaType.VIDEOS) },
                label = { Text(stringResource(R.string.search_filter_videos)) },
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = ToadSpacing.md),
        ) {
            when (val s = state) {
                SearchUiState.Idle -> {
                    // Idle covers both "nothing typed" and "short query";
                    // the live text disambiguates (pure gate: isSearchable).
                    if (SearchQuery.isSearchable(query)) {
                        // A searchable query while Idle can only be a
                        // debounce-in-flight blip; render the invitation.
                        SearchIdleState(short = false)
                    } else {
                        SearchIdleState(short = query.isNotBlank())
                    }
                }
                SearchUiState.Searching -> LoadingState(tag = "search_state_loading")
                SearchUiState.NoResults -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("search_state_no_results"),
                ) {
                    StateScreen(
                        iconRes = R.drawable.ic_nav_search,
                        titleRes = R.string.search_no_results_title,
                        bodyRes = R.string.search_no_results_body,
                    )
                }
                SearchUiState.Error -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("search_state_error"),
                ) {
                    StateScreen(
                        iconRes = R.drawable.ic_nav_search,
                        titleRes = R.string.search_error_title,
                        bodyRes = R.string.search_error_body,
                        actionRes = R.string.state_action_retry,
                        onAction = viewModel::retry,
                    )
                }
                is SearchUiState.Results -> Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.search_results_count,
                            s.items.size,
                            s.items.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = ToadSpacing.sm),
                    )
                    // The shared staggered grid: same cells, keys, spacing
                    // and tap contract as the album detail view; the tap
                    // hands the viewer the whole result list + index.
                    MediaGrid(
                        items = s.items,
                        onItemTap = { index, _ -> onOpenViewer(s.items, index) },
                    )
                }
            }
        }
    }
}

/**
 * The Idle surface: the empty-field invitation or the short-query hint,
 * both in the StateScreen visual language (icon + title + body, issue #13).
 */
@Composable
private fun SearchIdleState(short: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        StateScreen(
            iconRes = R.drawable.ic_nav_search,
            titleRes = if (short) {
                R.string.search_short_query_title
            } else {
                R.string.state_search_empty_title
            },
            bodyRes = if (short) {
                R.string.search_short_query_body
            } else {
                R.string.state_search_empty_body
            },
        )
    }
}
