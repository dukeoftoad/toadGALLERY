package io.github.dukeoftoad.gallery.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.buildshape.BuildShape
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.SearchMediaType
import io.github.dukeoftoad.gallery.data.SearchQuery
import io.github.dukeoftoad.gallery.ml.search.MagicSearchQuery
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.LoadingState
import io.github.dukeoftoad.gallery.ui.media.MediaGrid
import io.github.dukeoftoad.gallery.ui.media.MediaPermissionState
import io.github.dukeoftoad.gallery.ui.media.rememberMediaPermissionRequester
import io.github.dukeoftoad.gallery.ui.settings.LayoutStyle
import io.github.dukeoftoad.gallery.ui.settings.SettingsViewModel
import io.github.dukeoftoad.gallery.ui.theme.ToadSpacing

/**
 * The Search tab (issue #62, search tab v1): a name query over the media
 * timeline --- and, since r26 (Workstream 7), a CONTENT mode: Magic
 * Search, the on-device CLIP content query over the r26 semantic index.
 *
 * Layout mirrors the top-level tabs (no app bar of its own; the bottom bar
 * stays): a rounded [OutlinedTextField] (search icon, clear button, IME
 * action Search, focused on entry) over the r26 "Search by" mode row
 * (two [FilterChip]s --- [Name][SearchMode.NAME], the existing filename
 * pipeline with its behavior untouched, and
 * [Content][SearchMode.CONTENT], Magic Search), then --- in Name mode
 * only --- the single-select row of filter chips (All / Photos / Videos;
 * the semantic index covers JPEG photos only, so the type filters would
 * lie in Content mode and are hidden there), then the state surface:
 *
 * NAME MODE renders the issue #62/r19a states exactly as before:
 *  - PermissionNeeded (r19a): the SHARED [MediaPermissionState] surface
 *    (Grant + Settings affordances) the Photos and Albums tabs render ---
 *    revoked media access used to surface here as an endless generic
 *    "Error + Retry" loop. The field and chips stay visible and usable
 *    above it; the pipeline simply refuses to query until a grant lands
 *    (see [SearchViewModel]'s permission gate).
 *  - Idle + empty field  : the StateScreen invitation (icon + title + body,
 *    the issue #13 visual language);
 *  - Idle + short query  : the "keep typing" hint (the 2-character gate);
 *  - Searching           : the shared [LoadingState];
 *  - NoResults           : StateScreen with the offending query in the body;
 *  - Error               : StateScreen with the shared Retry action;
 *  - Results             : a "n results" header over the SHARED [MediaGrid]
 *    --- the exact cell UX (aspect-aware staggered cells, video duration
 *    overlay, GIF badge) the Photos timeline and album detail render, with
 *    the shell's photos-per-row setting and the GRID layout's square cells
 *    threaded through (r19a; 3 columns / 2dp gaps / `uri.toString()` keys
 *    remain the defaults), taps handing the shell's `onOpenViewer(window,
 *    index)` the full result list.
 *
 * CONTENT MODE renders [MagicSearchUiState] (the r26 sibling pipeline's
 * honest states): the shared permission surface; the pure
 * [io.github.dukeoftoad.gallery.ml.search.SemanticContentPolicy]'s
 * gating cards (consent off -> "enable Magic search in Settings" with an
 * [onOpenSettings] affordance wired the People/Map consent way; engine
 * missing -> the build-shape card; models not provisioned -> the download
 * pointer with the ~213 MB total named honestly via the formatted size;
 * not indexed -> the semantic-index pointer); Searching (shared
 * [LoadingState]); NoResults (the description in the body); Error (the
 * engine's own text + Retry); Results (the SAME shared [MediaGrid] the
 * name results render --- relevance order, the vecdb's own ranking ---
 * with the same viewer hand-off).
 *
 * Appearance (r19a): the shell-scoped [settingsViewModel] feeds the
 * configured column count and layout into the results grid, the SAME
 * wiring PhotosScreen/AlbumDetailScreen use --- every media grid in the
 * app now honors the setting.
 *
 * TextField + chips render from the ViewModels' StateFlows (the same source
 * of truth the debounced query pipelines read); every keystroke goes
 * through `SearchViewModel.onQueryChange` AND
 * `MagicSearchViewModel.onQueryChange` (the two pipelines share the text
 * and each gates itself on the mode, so exactly one ever queries), and
 * the permission answers drive BOTH gates.
 *
 * Accessibility: the field carries a visible label (the IME hint and
 * TalkBack announcement both come from it) and the clear button a
 * `cd_search_clear` content description; result cells behave exactly like
 * the timeline's grid cells ([MediaThumb] already narrates photo/video/GIF).
 *
 * Privacy (ADR-0006 / ADR-0014; ADR-0010/ADR-0020 for the Content mode):
 * results are local content URIs read from MediaStore, and the Content
 * query is embedded ON-DEVICE by the CLIP text encoder --- no search term,
 * embedding or thumbnail can ever leave the device (the only network the
 * feature touches is the ADR-0020 model download in Settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
    settingsViewModel: SettingsViewModel,
    onOpenSettings: () -> Unit,
    viewModel: SearchViewModel = viewModel(),
    magicViewModel: MagicSearchViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val query by viewModel.query.collectAsState()
    val mediaType by viewModel.mediaType.collectAsState()
    val searchMode by viewModel.searchMode.collectAsState()
    val magicState by magicViewModel.state.collectAsState()
    val appearance by settingsViewModel.appearance.collectAsState()

    // The SAME permission wiring PhotosScreen uses (r19a): the request
    // fires on every tab entry (the launcher returns immediately, without
    // UI, when access is already granted --- so re-entries stay cheap) and
    // the answer drives BOTH pipelines' permission gates (the r26 Content
    // mode refuses to query without the grant exactly like Name mode). A
    // deny renders the shared Grant + Settings surface below instead of an
    // error loop.
    val requester = rememberMediaPermissionRequester(
        onResult = { granted ->
            if (granted) {
                viewModel.onPermissionGranted()
                magicViewModel.onPermissionGranted()
            } else {
                viewModel.onPermissionDenied()
                magicViewModel.onPermissionDenied()
            }
        }
    )

    // Focus on entry (issue #62): the tab is a search-first surface, so the
    // field takes focus (and the keyboard) the moment the tab opens.
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(Unit) { requester.launch() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ToadSpacing.xl),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { value ->
                // r26: the SAME text feeds both pipelines; each gates
                // itself on the mode, so only the active one queries.
                viewModel.onQueryChange(value)
                magicViewModel.onQueryChange(value)
            },
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
                        onClick = {
                            viewModel.onQueryChange("")
                            magicViewModel.onQueryChange("")
                        },
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
        // The r26 "Search by" mode row: Name (the issue #62 filename
        // pipeline, untouched) vs Content (Magic Search). Switching the
        // mode flips BOTH pipelines' engagement through their own gates
        // --- exactly one query pipeline ever runs.
        // r46 build-shape gate: without the SEMANTIC index there is no
        // Content mode, so the row is pointless and is not rendered at
        // all --- the screen is the plain Name pipeline (the ViewModel's
        // mode stays its NAME default, unreachable otherwise).
        if (BuildShape.semanticIndex) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ToadSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(ToadSpacing.md),
            ) {
                FilterChip(
                    selected = searchMode == SearchMode.NAME,
                    onClick = {
                        viewModel.onSearchModeChange(SearchMode.NAME)
                        magicViewModel.onActiveChanged(false)
                    },
                    label = { Text(stringResource(R.string.search_mode_name)) },
                    modifier = Modifier.testTag("search_mode_name"),
                )
                FilterChip(
                    selected = searchMode == SearchMode.CONTENT,
                    onClick = {
                        viewModel.onSearchModeChange(SearchMode.CONTENT)
                        magicViewModel.onActiveChanged(true)
                    },
                    label = { Text(stringResource(R.string.search_mode_content)) },
                    modifier = Modifier.testTag("search_mode_content"),
                )
            }
        }
        if (searchMode == SearchMode.NAME) {
            // The type filters are the NAME pipeline's; the semantic index
            // covers JPEG photos only, so they would lie in Content mode
            // and are hidden there (the honest choice over dead chips).
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
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = ToadSpacing.md),
        ) {
            // r46 build-shape gate: the Content state surface renders only
            // where the SEMANTIC index exists; the mode row above is gone
            // in those builds, so this is defense in depth (a gated build
            // can never reach CONTENT mode through the UI).
            if (searchMode == SearchMode.CONTENT && BuildShape.semanticIndex) {
                MagicSearchStates(
                    state = magicState,
                    query = query,
                    semanticModelsSize = magicViewModel.semanticModelsSize,
                    onOpenSettings = onOpenSettings,
                    onRetry = magicViewModel::retry,
                    onGrant = requester::launch,
                    onOpenViewer = onOpenViewer,
                    columns = appearance.columns,
                    uniformCells = appearance.layout == LayoutStyle.GRID,
                )
            } else {
                when (val s = state) {
                SearchUiState.PermissionNeeded -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("search_state_permission"),
                ) {
                    // The shared Grant + Settings surface (the field and
                    // chips above stay usable; the pipeline gates itself).
                    MediaPermissionState(onGrant = requester::launch)
                }
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
                    // hands the viewer the whole result list + index. The
                    // shell's photos-per-row / GRID-layout settings feed
                    // through (r19a), exactly like the Photos timeline.
                    MediaGrid(
                        items = s.items,
                        columns = appearance.columns,
                        uniformCells = appearance.layout == LayoutStyle.GRID,
                        onItemTap = { index, _ -> onOpenViewer(s.items, index) },
                    )
                }
                }
            }
        }
    }
}

/**
 * The CONTENT mode's state surface (r26, Workstream 7): every
 * [MagicSearchUiState] renders honestly --- the four policy gating
 * cards each point at their own recovery surface, the query states
 * mirror the Name pipeline's shared components, and the results ride
 * the SAME shared [MediaGrid] (relevance order --- the vecdb's own
 * ranking --- with the same viewer hand-off the name results use).
 */
@Composable
private fun MagicSearchStates(
    state: MagicSearchUiState,
    query: String,
    semanticModelsSize: String,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onGrant: () -> Unit,
    onOpenViewer: (window: List<MediaItem>, index: Int) -> Unit,
    columns: Int,
    uniformCells: Boolean,
) {
    when (state) {
        MagicSearchUiState.PermissionNeeded -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("magic_state_permission"),
        ) {
            // The shared Grant + Settings surface (the field and mode
            // chips above stay usable; the pipeline gates itself).
            MediaPermissionState(onGrant = onGrant)
        }
        MagicSearchUiState.ConsentOff -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("magic_state_consent"),
        ) {
            StateScreen(
                iconRes = R.drawable.ic_nav_search,
                titleRes = R.string.magic_state_consent_title,
                bodyRes = R.string.magic_state_consent_body,
                actionRes = R.string.magic_state_open_settings,
                onAction = onOpenSettings,
            )
        }
        MagicSearchUiState.EngineMissing -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("magic_state_engine_missing"),
        ) {
            StateScreen(
                iconRes = R.drawable.ic_nav_search,
                titleRes = R.string.magic_state_engine_title,
                bodyRes = R.string.magic_state_engine_body,
            )
        }
        MagicSearchUiState.ModelsNotProvisioned -> MagicSearchStateCard(
            titleRes = R.string.magic_state_models_title,
            // The ~213 MB total, named honestly via the formatted size
            // (the manifest pin's own sum, never a hand-maintained copy).
            body = stringResource(R.string.magic_state_models_body, semanticModelsSize),
            actionRes = R.string.magic_state_open_settings,
            onAction = onOpenSettings,
            tag = "magic_state_models",
        )
        MagicSearchUiState.NotIndexed -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("magic_state_not_indexed"),
        ) {
            StateScreen(
                iconRes = R.drawable.ic_nav_search,
                titleRes = R.string.magic_state_not_indexed_title,
                bodyRes = R.string.magic_state_not_indexed_body,
                actionRes = R.string.magic_state_open_settings,
                onAction = onOpenSettings,
            )
        }
        MagicSearchUiState.Idle -> {
            // Idle covers "nothing typed", "short query" and the
            // round-pinned over-long query; the live text disambiguates
            // (pure gate: MagicSearchQuery.isSearchable).
            val searchable = MagicSearchQuery.isSearchable(query)
            MagicSearchIdleState(short = !searchable && query.isNotBlank())
        }
        MagicSearchUiState.Searching -> LoadingState(tag = "magic_state_loading")
        MagicSearchUiState.NoResults -> Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("magic_state_no_results"),
        ) {
            StateScreen(
                iconRes = R.drawable.ic_nav_search,
                titleRes = R.string.magic_no_results_title,
                bodyRes = R.string.magic_no_results_body,
            )
        }
        is MagicSearchUiState.Error -> MagicSearchStateCard(
            titleRes = R.string.magic_state_error_title,
            // The engine's own failure text, surfaced verbatim (never
            // paraphrased away) --- the honest-states doctrine.
            body = state.message,
            actionRes = R.string.state_action_retry,
            onAction = onRetry,
            tag = "magic_state_error",
        )
        is MagicSearchUiState.Results -> Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.magic_results_count,
                    state.items.size,
                    state.items.size,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = ToadSpacing.sm),
            )
            // The shared staggered grid, RELEVANCE order (the vecdb's own
            // ranking): same cells, keys, spacing and tap contract as the
            // name results above; the tap hands the viewer the whole
            // ranked list + index.
            MediaGrid(
                items = state.items,
                columns = columns,
                uniformCells = uniformCells,
                onItemTap = { index, _ -> onOpenViewer(state.items, index) },
            )
        }
    }
}

/**
 * The Content mode's card for a DYNAMIC body (r26): the [StateScreen]
 * visual language (icon + title + body + optional action, issue #13)
 * with a plain-[String] body --- the parameterized models copy and the
 * engine's own failure text both need it, and [StateScreen] requires a
 * `@StringRes`. The r25 `PeopleStateMessage` pattern, with the Search
 * tab's own icon.
 */
@Composable
private fun MagicSearchStateCard(
    titleRes: Int,
    body: String,
    actionRes: Int? = null,
    onAction: (() -> Unit)? = null,
    tag: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_nav_search),
            contentDescription = null, // decorative; the title carries meaning
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        if (actionRes != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(stringResource(actionRes))
            }
        }
    }
}

/**
 * The Content mode's Idle surface: the empty-field invitation or the
 * short-query hint, in the StateScreen visual language (issue #13).
 */
@Composable
private fun MagicSearchIdleState(short: Boolean) {
    Box(modifier = Modifier.fillMaxSize()) {
        StateScreen(
            iconRes = R.drawable.ic_nav_search,
            titleRes = if (short) {
                R.string.magic_short_query_title
            } else {
                R.string.magic_state_empty_title
            },
            bodyRes = if (short) {
                R.string.magic_short_query_body
            } else {
                R.string.magic_state_empty_body
            },
        )
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
