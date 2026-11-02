package io.github.dukeoftoad.gallery.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.SearchMediaType
import io.github.dukeoftoad.gallery.data.SearchQuery
import io.github.dukeoftoad.gallery.data.MediaStoreSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Screen states of the Search tab (issue #62).
 *
 * `Idle` covers BOTH "nothing typed yet" and "short query" --- the screen
 * disambiguates with the live query text (pure gate:
 * [SearchQuery.isSearchable]) so the state machine stays one-dimensional.
 * `PermissionNeeded` (r19a) is the honest fourth state before any query
 * runs: revoked media access used to surface as endless generic
 * "Error + Retry".
 */
sealed interface SearchUiState {
    /** No results to show: empty field or a query under the minimum length. */
    data object Idle : SearchUiState

    /**
     * No media read permission (r19a): the pipeline refuses to query until
     * the screen reports a grant. Rendered with the SAME shared
     * permission surface (Grant + Settings affordances) the Photos and
     * Albums tabs use.
     */
    data object PermissionNeeded : SearchUiState

    /** A query is in flight (post-debounce). */
    data object Searching : SearchUiState

    /** The query ran and matched nothing. */
    data object NoResults : SearchUiState

    /** The query failed; the screen offers retry. */
    data object Error : SearchUiState

    /** The query's matches, newest first (bounded by [SearchQuery.DEFAULT_LIMIT]). */
    data class Results(val items: List<MediaItem>) : SearchUiState
}

/**
 * State holder for the Search tab (issue #62, search tab v1).
 *
 * ## Input model
 * The screen pushes raw text into [onQueryChange] and the filter chip into
 * [onMediaTypeChange]; both live as StateFlows so the TextField and the
 * chips render from the same source of truth the query pipeline reads.
 * `init` combines query + media type + a retry counter, DEBOUNCES the pair
 * 300ms (per-keystroke MediaStore round-trips would hammer the provider),
 * and re-runs the latest request on every emission. `collectLatest` cancels
 * a query still in flight when a newer (debounced) request lands, so a
 * stale result can never overwrite a newer one.
 *
 * ## Permission gate (r19a)
 * The MediaStore query runs ONLY while the screen has reported the media
 * read permission granted ([onPermissionGranted]/[onPermissionDenied] ---
 * the same screen-driven decision PhotosViewModel models). Without the
 * grant the pipeline publishes [SearchUiState.PermissionNeeded] instead of
 * querying, so revoked access surfaces as the honest grant-me state with
 * the standard affordances rather than an endless generic
 * "Error + Retry"; a [SecurityException] mid-query (the grant vanishing
 * while this tab stayed composed) folds into the same state. Typing while
 * ungranted is harmless: the field keeps working and the pipeline settles
 * the moment a grant lands.
 *
 * ## Gating
 * Every request first consults the pure [SearchQuery.isSearchable] gate:
 * trimmed queries shorter than 2 characters never reach MediaStore ---
 * the state just returns to [SearchUiState.Idle] (the "keep typing" hint).
 *
 * ## Execution
 * [MediaStoreSource.querySearch] runs on Dispatchers.IO inside
 * viewModelScope, the same discipline as PhotosViewModel/TrashViewModel.
 * Any other provider failure (SQLiteException, ...) maps to
 * [SearchUiState.Error]; [retry] bumps the counter so the combine pipeline
 * re-runs the CURRENT query + filter without the screen having to resend
 * anything.
 *
 * ## v1 scope (issue #62)
 * Name query + photo/video filter only. Advanced smart filters (date,
 * album, people, OCR) are roadmap Phase 5 and would extend
 * [SearchQuery.build] --- the pure, tested seam this ViewModel already
 * consumes.
 *
 * Privacy (ADR-0006 / ADR-0014): results are local content URIs read from
 * MediaStore; the app holds no INTERNET permission, so nothing a query
 * matches can ever leave the device.
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** The live TextField text (raw, untrimmed). */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The live filter-chip selection (All / Photos / Videos). */
    private val _mediaType = MutableStateFlow(SearchMediaType.ALL)
    val mediaType: StateFlow<SearchMediaType> = _mediaType.asStateFlow()

    /**
     * Retry counter: a StateFlow (not SharedFlow) so `combine` has an
     * initial value and emits from the first frame; `retry()` increments it,
     * which re-runs the pipeline for the current query + filter. All three
     * inputs are StateFlows, so no emission is ever lost to buffering.
     */
    private val retries = MutableStateFlow(0)

    /**
     * The screen-reported media read permission (r19a): written by
     * [onPermissionGranted]/[onPermissionDenied] and read by the pipeline
     * gate below. Main-thread-only exactly like the StateFlows above (both
     * writers and the collector run on the viewModelScope's main
     * dispatcher), so a plain var is the honest, lock-free shape.
     */
    private var permissionGranted = false

    init {
        viewModelScope.launch {
            combine(_query, _mediaType, retries) { query, type, _ ->
                query to type
            }
                .debounce(DEBOUNCE_MS.milliseconds)
                .collectLatest { (rawQuery, type) ->
                    if (!permissionGranted) {
                        _state.value = SearchUiState.PermissionNeeded
                        return@collectLatest
                    }
                    if (!SearchQuery.isSearchable(rawQuery)) {
                        _state.value = SearchUiState.Idle
                        return@collectLatest
                    }
                    _state.value = SearchUiState.Searching
                    _state.value = try {
                        val results = withContext(Dispatchers.IO) {
                            source.querySearch(rawQuery, type)
                        }
                        if (results.isEmpty()) {
                            SearchUiState.NoResults
                        } else {
                            SearchUiState.Results(results)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: SecurityException) {
                        // The grant vanished mid-session (revoked in system
                        // settings while this tab stayed composed): the
                        // honest state is the permission state, not a
                        // generic error the Retry button could not fix.
                        permissionGranted = false
                        SearchUiState.PermissionNeeded
                    } catch (_: Exception) {
                        SearchUiState.Error
                    }
                }
        }
    }

    /** Called by the screen on every keystroke; debounced upstream. */
    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** Called by the filter chips (single-select); debounced upstream. */
    fun onMediaTypeChange(type: SearchMediaType) {
        _mediaType.value = type
    }

    /** Re-runs the current query + filter after an [SearchUiState.Error]. */
    fun retry() {
        retries.value += 1
    }

    /**
     * The screen reports the media read permission granted (r19a): the
     * pipeline gate opens and the current query + filter re-run through
     * the SAME retry bump [retry] uses (the debounced collector re-decides
     * --- Idle invitation when nothing searchable is typed, the query
     * otherwise). The early-return keeps tab re-entries cheap: the
     * launcher returns immediately when access is already granted, so
     * every re-entry would otherwise re-run the live query for nothing.
     */
    fun onPermissionGranted() {
        if (permissionGranted) return
        permissionGranted = true
        retries.value += 1
    }

    /**
     * The screen reports the permission denied or revoked later (r19a):
     * the gate closes and the state flips to the honest grant-me surface,
     * discarding any shown results (they are unreadable without the grant
     * anyway).
     */
    fun onPermissionDenied() {
        permissionGranted = false
        _state.value = SearchUiState.PermissionNeeded
    }

    private companion object {
        /**
         * Debounce between input changes and the MediaStore round-trip
         * (issue #62). 300ms is the standard "user stopped typing" window;
         * it also coalesces rapid filter-chip taps into one query.
         */
        const val DEBOUNCE_MS = 300L
    }
}
