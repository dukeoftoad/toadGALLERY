package io.github.dukeoftoad.gallery.ui.photos

import android.app.Application
import android.database.ContentObserver
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * State holder for the Photos timeline (Phase 3, issue #25; paging since #28;
 * review follow-ups #38).
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
 * frame. `cachedIn` additionally survives tab switches, and since the issue
 * #38 idempotence fix that is the whole re-entry story: [onPermissionGranted]
 * early-returns while the state is already [PhotosState.Ready], so
 * re-entering Photos re-collects the cached stream instead of rebuilding the
 * `Pager` and re-querying from offset 0.
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
 * ## Dataset drift -> generational invalidation (issue #38, finding 1)
 * LIMIT/OFFSET windows are positional: if MediaStore changes between two
 * windows of the SAME generation (camera shot, download, delete), the
 * offsets of later windows no longer tile the new dataset --- a later
 * window can re-emit an item the grid already shows, and the
 * `uri.toString()` LazyGrid key would collide (foundation lazy grids throw
 * on duplicate keys). The ViewModel therefore NEVER appends deltas: a
 * MediaStore `ContentObserver` (registered in `init` via
 * [MediaStoreSource.registerTimelineObserver]) funnels change
 * notifications into [timelineChanges], and a debounced collector calls
 * `currentSource.invalidate()`. Invalidating ends the generation and makes
 * the `Pager` build a fresh source whose refresh re-anchors via
 * `getRefreshKey` (snapped to the window lattice), so every window of the
 * new generation is computed against one consistent dataset.
 *
 * The observer is registered on API 30+ only, matching the windowed
 * protocol it serves: below 30 one memoized in-memory snapshot serves a
 * whole generation, so windows cannot drift apart mid-generation (those
 * devices keep the documented snapshot behavior; their staleness
 * trade-off is the ADR-0015 open question).
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
 * and the change observer only watches the local MediaStore provider --- so
 * the offline guarantee and the INTERNET gate are unaffected (ADR-0014).
 */
class PhotosViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<PhotosState>(PhotosState.Loading)
    val state: StateFlow<PhotosState> = _state.asStateFlow()

    /**
     * The [WindowPagingSource] of the CURRENT Pager generation (issue #38
     * finding 1). The paging factory --- invoked from the collection scope
     * that `cachedIn(viewModelScope)` binds to, i.e. the main dispatcher ---
     * refreshes this field on every generation, and the debounced MediaStore
     * change collector below reads it to invalidate. Both accesses run on
     * the main dispatcher, so no further synchronization is needed.
     */
    private var currentSource: WindowPagingSource<MediaItem>? = null

    /**
     * MediaStore change notifications, emitted by the observer registered in
     * `init`. Capacity 1 with DROP_OLDEST coalesces storms into one pending
     * signal (`ContentObserver` fires per changed row during device-wide
     * media scans) and `tryEmit` can never block or fail.
     */
    private val timelineChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Registered in `init` (API 30+ only); unregistered in [onCleared]. */
    private var timelineObserver: ContentObserver? = null

    init {
        // API 30+ only: the drift risk this observer mitigates is specific
        // to the windowed protocol, where each window is a fresh positional
        // query against a dataset that may have changed between windows.
        // Below 30 the memoized snapshot serves the whole generation and
        // cannot drift mid-generation; paying a full re-walk per change
        // storm there would only churn. The `Build.VERSION.SDK_INT` check
        // is the lexically lint-visible guard for the `@RequiresApi(R)`
        // call (same pattern as [timelineWindowPagingSource]).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            timelineObserver = source.registerTimelineObserver {
                // Called on the main looper (the observer's Handler); tryEmit
                // is non-suspending, so this stays cheap under storms.
                timelineChanges.tryEmit(Unit)
            }
            viewModelScope.launch {
                // Debounce the storms: invalidating per row would thrash
                // Pager generations (each one tears down windows and
                // re-queries). One invalidate per storm is enough --- it is
                // idempotent, and the fresh generation sees every change.
                timelineChanges
                    .debounce(INVALIDATION_DEBOUNCE_MS.milliseconds)
                    .collect { currentSource?.invalidate() }
            }
        }
    }

    override fun onCleared() {
        timelineObserver?.let { source.unregisterTimelineObserver(it) }
        timelineObserver = null
        super.onCleared()
    }

    /**
     * Called by the screen once the runtime permission grant has succeeded.
     * Publishes a fresh [PhotosState.Ready] wrapping a new `Pager` (one per
     * grant). Idempotent (issue #38, finding 3): while the state is already
     * [PhotosState.Ready] this returns early --- re-entering the Photos tab
     * must re-collect the existing `cachedIn` stream, not tear the current
     * generation down and re-query from offset 0. From here on, load
     * progress / end-of-data / failures surface through the paging
     * `LoadState` that the screen observes inside `Ready` --- the shell
     * states stay permission-driven only.
     */
    fun onPermissionGranted() {
        if (_state.value is PhotosState.Ready) return
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
     * Every generation also becomes [currentSource], so the debounced
     * MediaStore change collector invalidates exactly the live source.
     */
    private fun timelineWindowPagingSource(): WindowPagingSource<MediaItem> {
        val newSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        currentSource = newSource
        return newSource
    }

    private companion object {
        /** Timeline page size; doubles as the initial load size (see KDoc). */
        const val TIMELINE_PAGE_SIZE = 60

        /**
         * MediaStore change-storm debounce, in milliseconds (issue #38).
         * ContentObserver fires per changed row (device-wide scans notify
         * hundreds of times); invalidating per row would thrash Pager
         * generations, while one invalidate per storm suffices because the
         * fresh generation re-queries everything at once.
         */
        const val INVALIDATION_DEBOUNCE_MS = 500L
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
     * The stream instance is kept alive across tab switches by
     * `cachedIn` + the idempotent grant path (issue #38): re-entering
     * Photos re-collects this same flow.
     */
    data class Ready(val pagingData: Flow<PagingData<MediaItem>>) : PhotosState
}
