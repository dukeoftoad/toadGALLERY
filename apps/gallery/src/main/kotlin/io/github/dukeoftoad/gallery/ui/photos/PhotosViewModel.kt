package io.github.dukeoftoad.gallery.ui.photos

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.MediaStoreSource
import io.github.dukeoftoad.gallery.data.WindowPagingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * State holder for the Photos timeline (Phase 3, issue #25; paging since #28).
 *
 * Three states, derived from the user's permission decision:
 *  - `Loading`        : first frame, before the first permission check resolves
 *  - `PermissionNeeded`: no media access yet (initial denial, or revoked later)
 *  - `Ready`          : media access granted; carries the *paging stream*
 *                      (`Flow<PagingData<MediaItem>>`), not a materialized list.
 *
 * ## Paging pipeline (issue #28)
 * [onPermissionGranted] builds ONE `Pager` per grant and publishes its flow
 * inside `Ready`, `cachedIn(viewModelScope)`. Building the `Pager` is cheap
 * (no query runs at construction); the actual MediaStore loads fire when the
 * screen collects the stream. The flow instance is stable for as long as the
 * state stays `Ready` --- `collectAsLazyPagingItems` in the screen must not
 * restart on recomposition, which it would if a fresh `Pager` were built per
 * frame. `cachedIn` additionally survives tab switches: re-entering Photos
 * re-collects the cached stream instead of re-querying from offset 0.
 *
 * The `PagingSource` is the generic [WindowPagingSource] over positional
 * offsets (window contract in its KDoc):
 *  - API 30+  : native MediaStore windows via
 *    [MediaStoreSource.queryTimelineWindow] --- one Bundle-args query per
 *    page over the unified Files collection (`date_added DESC, _id DESC`),
 *    so first paint waits for 60 items, not for the whole library.
 *  - Below 30 : the full `queryTimeline()` snapshot, memoized once per
 *    PagingSource generation and sliced in-memory (`drop`/`take`). Same
 *    window math and same state shell; only the memory profile stays the
 *    pre-paging one (windowed Bundle queries are not reliable there).
 *
 * ## Why `initialLoadSize == pageSize`
 * `initialLoadSize` defaults to `pageSize * 3`; a larger first window would
 * misalign the second window's `prevKey` into the middle of the first, so a
 * prepend (fast upward scroll) would re-emit rows the grid already shows as
 * duplicates. Both are pinned to 60. Placeholders are disabled: the windowed
 * protocol has no cheap total count (it would cost one `COUNT(*)` per page)
 * and nothing in the UI consumes placeholder slots.
 *
 * Privacy: the stream carries local content URIs only (ADR-0006); the two
 * Paging artifacts are pure-JVM androidx libraries with no network surface,
 * so the offline guarantee and the INTERNET gate are unaffected (ADR-0014).
 */
class PhotosViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<PhotosState>(PhotosState.Loading)
    val state: StateFlow<PhotosState> = _state.asStateFlow()

    /**
     * Called by the screen once the runtime permission grant has succeeded.
     * Publishes a fresh [PhotosState.Ready] wrapping a new `Pager` (one per
     * grant). From here on, load progress / end-of-data / failures surface
     * through the paging `LoadState` that the screen observes inside `Ready`
     * --- the shell states stay permission-driven only.
     */
    fun onPermissionGranted() {
        _state.value = PhotosState.Loading
        val pagingData = Pager(
            config = PagingConfig(
                pageSize = TIMELINE_PAGE_SIZE,
                // == pageSize on purpose; see the class KDoc for why a bigger
                // initial window would duplicate rows on prepend.
                initialLoadSize = TIMELINE_PAGE_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = ::timelineWindowPagingSource,
        ).flow.cachedIn(viewModelScope)
        _state.value = PhotosState.Ready(pagingData)
    }

    /** Called when the user denies the permission or revokes it later. */
    fun onPermissionDenied() {
        _state.value = PhotosState.PermissionNeeded
    }

    /**
     * One [WindowPagingSource] per Pager generation, backed by the best
     * window mechanism this API level offers. The explicit
     * `Build.VERSION.SDK_INT` check (not `supportsWindowedQueries()`) is what
     * NewApi lint recognizes as the guard for the `@RequiresApi(30)` call.
     */
    private fun timelineWindowPagingSource(): WindowPagingSource<MediaItem> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Native windows: one Bundle-args MediaStore query per page.
            WindowPagingSource { offset, limit ->
                withContext(Dispatchers.IO) { source.queryTimelineWindow(offset, limit) }
            }
        } else {
            // Pre-30 fallback: one full snapshot per generation, memoized on
            // first load, then sliced in-memory with the same window contract.
            var snapshot: List<MediaItem>? = null
            WindowPagingSource { offset, limit ->
                withContext(Dispatchers.IO) {
                    (snapshot ?: source.queryTimeline().also { snapshot = it })
                        .drop(offset)
                        .take(limit)
                }
            }
        }

    private companion object {
        /** Timeline page size; doubles as the initial load size (see KDoc). */
        const val TIMELINE_PAGE_SIZE = 60
    }
}

sealed interface PhotosState {
    /** Initial state: first frame before the first permission check resolves. */
    data object Loading : PhotosState

    /** No media access; show the permission state with a Grant button. */
    data object PermissionNeeded : PhotosState

    /**
     * Media access granted; carries the paging stream (issue #28). The
     * stream may legitimately yield zero items (clean library, not an
     * error) or fail a refresh (surfaced as a retryable `LoadState.Error`
     * by the screen) --- both are load-state concerns, not shell concerns.
     */
    data class Ready(val pagingData: Flow<PagingData<MediaItem>>) : PhotosState
}
