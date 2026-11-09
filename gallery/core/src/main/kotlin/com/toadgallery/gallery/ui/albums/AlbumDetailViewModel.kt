package com.toadgallery.gallery.ui.albums

import android.app.Application
import android.database.ContentObserver
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.BucketContent
import com.toadgallery.gallery.data.Favorites
import com.toadgallery.gallery.data.MediaStoreSource
import com.toadgallery.gallery.data.TimelineSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * State holder for the album detail view (Phase 3, issue #29; file-ops
 * refresh since #64; favorites pseudo-bucket + sorting since #66).
 *
 * Loads one bucket's contents plus its display name. The screen is only
 * reachable after the Albums tab already loaded with granted access, so
 * there is no permission flow here; a mid-session revoke surfaces as the
 * honest [AlbumDetailState.Error] with a retry action (r19) instead of
 * the pre-r19 fake --- the defensive fallback used to mask a failed read
 * as an empty bucket.
 *
 * ## Favorites pseudo-bucket (issue #66)
 * When [bucketId] is the sentinel [Favorites.FAVORITES_BUCKET_ID] (the
 * pinned "Favorites" card on the Albums tab), [load] serves
 * [MediaStoreSource.queryFavorites] instead of a bucket query and the
 * screen resolves the title from `albums_favorites_title`. The favorites
 * detail keeps newest-first in v1 (issue #66's documented scope), so the
 * sort state is ignored --- and its menu hidden --- there. Because the
 * favorites surface is Q+ (see `Favorites`), a defensively reachable
 * sentinel below Q simply renders the honest empty state.
 *
 * ## Sorting (issue #66)
 * [sort] is per-session ViewModel state, applied by re-querying the bucket
 * in the chosen order ([MediaStoreSource.queryBucket] owns the total
 * order). The snapshot model makes a sort change a plain new [load] --- no
 * Paging generation to rebuild here --- with the same generation guard as
 * every other re-query.
 *
 * ## Refresh after file operations (issue #64)
 * Unlike the Photos timeline there is no Paging pipeline here --- the grid
 * is one materialized snapshot per entry. Pre-#64 that meant file ops
 * (rename/copy/move/delete, driven by the shared
 * `ui/photos/FileOpsViewModel`) left the bucket list stale until
 * re-entry. The screen therefore keeps it honest on two legs, mirroring
 * the #38 observer->invalidation pipeline as closely as a snapshot model
 * allows:
 *
 *  - API 30+: a MediaStore `ContentObserver` (registered in `init` via
 *    [MediaStoreSource.registerTimelineObserver] --- the same unified
 *    Files collection the paging timeline watches) funnels change
 *    notifications into [bucketChanges]; a debounced collector re-queries
 *    the bucket ([refresh]). Below 30 the observer is not registered, for
 *    the same API-gating reason `PhotosViewModel` uses (the unified Files
 *    collection is 29+ and the R-gated call is what lint can see).
 *  - ALL levels: the screen calls [refresh] whenever the file-ops state
 *    machine lands back in `Idle` after activity (batch finished, consent
 *    resolved, trash round-trip done), which covers Android 10 where the
 *    observer does not exist and file ops are still enabled.
 *
 * Known residual (documented, same trade-off class as the Photos tab's
 * API-29 note in `docs/architecture/file-ops.md`): on Android 10 an
 * *undo* performed from the snackbar changes MediaStore without passing
 * through the state machine, so the bucket list reflects it only on the
 * next refresh trigger or re-entry.
 */
class AlbumDetailViewModel(
    app: Application,
    private val bucketId: Long,
) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<AlbumDetailState>(AlbumDetailState.Loading)
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    /**
     * The bucket's sort direction (issue #66): per-session ViewModel state,
     * newest-first by default; the screen's sort menu writes it and every
     * change re-queries (the snapshot model has no pipeline to rebuild).
     * Ignored for the favorites pseudo-bucket (newest-first in v1).
     */
    private val _sort = MutableStateFlow(TimelineSort.NEWEST_FIRST)
    val sort: StateFlow<TimelineSort> = _sort.asStateFlow()

    /**
     * MediaStore change notifications, emitted by the observer registered
     * in `init` (API 30+). Capacity 1 with DROP_OLDEST coalesces storms
     * into one pending signal and keeps `tryEmit` non-blocking --- same
     * shape as `PhotosViewModel.timelineChanges` (#38).
     */
    private val bucketChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Registered in `init` (API 30+ only); unregistered in [onCleared]. */
    private var bucketObserver: ContentObserver? = null

    /**
     * Monotonic query generation: only the YOUNGEST [load] may publish, so
     * a slow older query (e.g. the init load racing the first observer-
     * triggered refresh) can never overwrite a fresher snapshot with a
     * stale one. Both launches run on the main dispatcher, so the counter
     * needs no synchronization (same argument as
     * `PhotosViewModel.currentSource`).
     */
    private var loadGeneration = 0

    init {
        load()
        // API 30+ only, mirroring PhotosViewModel's gating and rationale:
        // the observer watches the unified Files collection (an R-gated
        // registration), and debouncing matters because ContentObserver
        // fires per changed row during media scans --- one re-query per
        // storm suffices (it sees every change at once).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bucketObserver = source.registerTimelineObserver {
                bucketChanges.tryEmit(Unit)
            }
            viewModelScope.launch {
                bucketChanges
                    .debounce(REFRESH_DEBOUNCE_MS.milliseconds)
                    .collect { refresh() }
            }
        }
    }

    /**
     * Re-queries the bucket and republishes the fresh snapshot ---
     * [AlbumDetailState.Ready], or [AlbumDetailState.Error] since r19 when
     * the provider fails. Called by the screen after file-ops activity
     * settles (issue #64) and by the debounced MediaStore observer above;
     * safe to call while another query is in flight --- the generation
     * guard in [load] keeps only the youngest result.
     */
    fun refresh() {
        load()
    }

    /**
     * Re-runs the bucket query after an [AlbumDetailState.Error] (the
     * error state's retry action): re-enters [AlbumDetailState.Loading]
     * first, so the user sees the honest spinner while the retry is in
     * flight. Deliberately NOT folded into [refresh], whose observer and
     * file-ops callers must keep the current grid on screen while the
     * re-query runs (no spinner flash over a working grid).
     */
    fun retry() {
        _state.value = AlbumDetailState.Loading
        load()
    }

    /**
     * Changes the bucket's sort direction (issue #66): no-op for the current
     * value, otherwise store + re-query. (The favorites pseudo-bucket stays
     * newest-first in v1; the screen hides its menu there, and a defensive
     * call is absorbed because [load] ignores the sort for the sentinel.)
     */
    fun onSortChange(newSort: TimelineSort) {
        if (_sort.value == newSort) return
        _sort.value = newSort
        load()
    }

    override fun onCleared() {
        bucketObserver?.let { source.unregisterTimelineObserver(it) }
        bucketObserver = null
        super.onCleared()
    }

    /**
     * One bucket query (the favorites pseudo-bucket routes to the
     * favorites query, newest-first in v1). Since r19 a failure maps to
     * the honest [AlbumDetailState.Error] via `runCatching { ... }` ---
     * pre-r19 the fallback fabricated an empty bucket, telling the user
     * a folder they had navigated from was EMPTY when the provider had
     * actually failed (permission revoked mid-session, a provider
     * SQLiteException, a missing column), the honesty gap this screen's
     * KDoc carried as a documented residual. The catches sit INSIDE the
     * IO hop, so cancellation of the hop itself still propagates ---
     * `runCatching` sees only provider throws (the queries are plain
     * blocking calls with no suspension points of their own).
     */
    private fun load() {
        val generation = ++loadGeneration
        val favorites = Favorites.isFavoritesBucket(bucketId)
        val sort = _sort.value
        val app = getApplication<Application>()
        viewModelScope.launch {
            val content: BucketContent? = withContext(Dispatchers.IO) {
                if (favorites) {
                    // Issue #66: the sentinel routes to the favorites query;
                    // newest-first in v1 (the sort state is ignored here by
                    // design --- the screen hides the menu).
                    runCatching {
                        BucketContent(
                            bucketName = app.getString(R.string.albums_favorites_title),
                            items = source.queryFavorites(),
                        )
                    }
                } else {
                    runCatching { source.queryBucket(bucketId, sort) }
                }
            }.getOrNull()
            if (generation == loadGeneration) {
                _state.value = if (content != null) {
                    AlbumDetailState.Ready(
                        bucketName = content.bucketName.ifEmpty { "Album" },
                        items = content.items,
                    )
                } else {
                    AlbumDetailState.Error
                }
            }
        }
    }

    companion object {
        /**
         * MediaStore change-storm debounce, in milliseconds (issue #64);
         * same value and rationale as `PhotosViewModel`'s invalidation
         * debounce (#38): one re-query per storm is enough because the
         * fresh query sees every change at once.
         */
        private const val REFRESH_DEBOUNCE_MS = 500L

        /** Minimal factory carrying the navigation argument. */
        fun factory(application: Application, bucketId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AlbumDetailViewModel(application, bucketId) as T
            }
    }
}

sealed interface AlbumDetailState {
    data object Loading : AlbumDetailState

    /**
     * The bucket query failed (r19): permission revoked mid-session, a
     * provider SQLiteException, a missing column. The screen renders the
     * shared `ErrorState` with a retry action under the
     * `album_detail_state_error` tag reserved for this call site in
     * docs/architecture/screen-states.md §2 --- never the pre-r19 fake
     * "empty folder".
     */
    data object Error : AlbumDetailState

    data class Ready(
        val bucketName: String,
        val items: List<com.toadgallery.gallery.data.MediaItem>,
    ) : AlbumDetailState
}
