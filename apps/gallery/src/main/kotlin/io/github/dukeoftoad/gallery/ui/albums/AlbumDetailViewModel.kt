package io.github.dukeoftoad.gallery.ui.albums

import android.app.Application
import android.database.ContentObserver
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.data.BucketContent
import io.github.dukeoftoad.gallery.data.MediaStoreSource
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
 * refresh since #64).
 *
 * Loads one bucket's contents plus its display name. The screen is only
 * reachable after the Albums tab already loaded with granted access, so
 * there is no permission flow here; a mid-session revoke surfaces as an
 * empty grid rather than a crash (the query is defensive).
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
     * Re-queries the bucket and republishes [AlbumDetailState.Ready].
     * Called by the screen after file-ops activity settles (issue #64)
     * and by the debounced MediaStore observer above; safe to call while
     * another query is in flight --- the generation guard in [load] keeps
     * only the youngest result.
     */
    fun refresh() {
        load()
    }

    override fun onCleared() {
        bucketObserver?.let { source.unregisterTimelineObserver(it) }
        bucketObserver = null
        super.onCleared()
    }

    /** One defensive bucket query; failures map to an empty bucket. */
    private fun load() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            val content: BucketContent = withContext(Dispatchers.IO) {
                runCatching { source.queryBucket(bucketId) }
                    .getOrElse { BucketContent(bucketName = "", items = emptyList()) }
            }
            if (generation == loadGeneration) {
                _state.value = AlbumDetailState.Ready(
                    bucketName = content.bucketName.ifEmpty { "Album" },
                    items = content.items,
                )
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

    data class Ready(
        val bucketName: String,
        val items: List<io.github.dukeoftoad.gallery.data.MediaItem>,
    ) : AlbumDetailState
}
