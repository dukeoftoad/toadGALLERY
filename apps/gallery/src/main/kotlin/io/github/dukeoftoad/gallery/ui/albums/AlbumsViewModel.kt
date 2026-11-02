package io.github.dukeoftoad.gallery.ui.albums

import android.app.Application
import android.database.ContentObserver
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.Album
import io.github.dukeoftoad.gallery.data.Favorites
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
 * State holder for the Albums tab (Phase 3, issue #29; favorites pseudo-album
 * and live counts since #66).
 *
 * Mirrors the Photos tab's state model (issue #25) so both tabs feel
 * identical: Loading → PermissionNeeded → Ready, plus --- since r19 --- the
 * honest Error arm every sibling screen already had. `Ready` with an empty
 * list means the user granted access but has no media on the device;
 * Error is a failed read, never a disguised empty one.
 *
 * ## Favorites pseudo-album (issue #66)
 * `Ready` is pinned with a "Favorites" entry carrying
 * [Favorites.FAVORITES_BUCKET_ID] as its `bucketId` when at least one
 * favorited item exists (an empty favorites album renders no card --- there
 * is nothing to show until the first heart). The count stays live: on
 * API 30+ the same unified-collection `ContentObserver` the Photos timeline
 * watches (#38) funnels MediaStore changes into a debounced re-query, so
 * toggling a heart updates the card (and the pseudo-album's contents)
 * without re-entering the tab. Below R --- the same tiering as
 * `PhotosViewModel`/`AlbumDetailViewModel` --- no observer exists and the
 * list reflects the last query; favorites toggles are Q+ writes, and their
 * staleness on Q is the documented residual of that tier.
 */
class AlbumsViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<AlbumsState>(AlbumsState.Loading)
    val state: StateFlow<AlbumsState> = _state.asStateFlow()

    /**
     * MediaStore change notifications, emitted by the observer registered
     * in `init` (API 30+). Capacity 1 with DROP_OLDEST coalesces storms
     * into one pending signal and keeps `tryEmit` non-blocking --- same
     * shape as `PhotosViewModel.timelineChanges` (#38) and
     * `AlbumDetailViewModel.bucketChanges` (#64).
     */
    private val albumsChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Registered in `init` (API 30+ only); unregistered in [onCleared]. */
    private var albumsObserver: ContentObserver? = null

    init {
        // API 30+ only, mirroring PhotosViewModel's gating and rationale:
        // the observer watches the unified Files collection (an R-gated
        // registration), and one re-query per debounced change storm keeps
        // the favorites count and the album list live.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            albumsObserver = source.registerTimelineObserver {
                albumsChanges.tryEmit(Unit)
            }
            viewModelScope.launch {
                albumsChanges
                    .debounce(RELOAD_DEBOUNCE_MS.milliseconds)
                    .collect { reload() }
            }
        }
    }

    /**
     * Called by the screen once the runtime permission grant has succeeded.
     * A repeated grant (tab re-entry) re-queries --- album lists are cheap
     * snapshots and re-entry is also the below-R refresh path.
     */
    fun onPermissionGranted() {
        _state.value = AlbumsState.Loading
        reload()
    }

    /** Called when the user denies the permission or revokes it later. */
    fun onPermissionDenied() {
        _state.value = AlbumsState.PermissionNeeded
    }

    /**
     * Re-runs the album queries after an [AlbumsState.Error] (the error
     * state's retry action). Re-enters [AlbumsState.Loading] first --- the
     * same contract as `TrashViewModel.refresh` --- so a slow retry never
     * leaves a stale error card on screen while the query is in flight.
     */
    fun retry() {
        _state.value = AlbumsState.Loading
        reload()
    }

    override fun onCleared() {
        albumsObserver?.let { source.unregisterTimelineObserver(it) }
        albumsObserver = null
        super.onCleared()
    }

    /**
     * One (re)load: the bucket list plus the favorites pseudo-album entry
     * pinned on top (issue #66). The favorites query returns empty below Q
     * (see `MediaStoreSource.queryFavorites`), which simply means no card;
     * the per-bucket list is unchanged by the favorites feature.
     *
     * r19: the queries run inside `runCatching { ... }.getOrElse { ... }`
     * (the guard `AlbumDetailViewModel.load` already uses) because a
     * provider hiccup --- permission revoked mid-session, a provider
     * SQLiteException, an IllegalArgumentException from a missing column
     * --- previously escaped `viewModelScope.launch` UNCAUGHT and killed
     * the process; the UI audit found the Albums tab the only screen with
     * that escape (Search/Trash map to their Error states, Photos to the
     * Paging LoadState.Error, AlbumDetail to runCatching). The catch sits
     * INSIDE the IO hop, so cancellation of the hop itself still
     * propagates --- `runCatching` sees only provider throws (the queries
     * are plain blocking calls with no suspension points of their own).
     */
    private fun reload() {
        viewModelScope.launch {
            val ready: AlbumsState = withContext(Dispatchers.IO) {
                runCatching { queryReadyState() }
                    .getOrElse { AlbumsState.Error }
            }
            _state.value = ready
        }
    }

    /**
     * The success path of [reload]: both provider queries plus the Ready
     * assembly over them --- main-thread-free by construction (pure data
     * classes and a `getString`), so it runs whole inside the IO hop.
     */
    private fun queryReadyState(): AlbumsState.Ready {
        val albums = source.queryAlbums()
        val favorites = source.queryFavorites()
        return if (favorites.isEmpty()) {
            AlbumsState.Ready(albums)
        } else {
            // queryFavorites is newest-first, so the first row is the
            // cover; the pseudo-album keeps newest-first contents (v1).
            val newest = favorites.first()
            AlbumsState.Ready(
                listOf(
                    Album(
                        bucketId = Favorites.FAVORITES_BUCKET_ID,
                        bucketName = getApplication<Application>()
                            .getString(R.string.albums_favorites_title),
                        itemCount = favorites.size,
                        coverUri = newest.uri,
                        latestDateSec = newest.dateAddedSec,
                    ),
                ) + albums,
            )
        }
    }

    companion object {
        /**
         * MediaStore change-storm debounce, in milliseconds (issue #66);
         * same value and rationale as the Photos timeline's (#38): one
         * re-query per storm suffices because the fresh query sees every
         * change at once.
         */
        private const val RELOAD_DEBOUNCE_MS = 500L
    }
}

sealed interface AlbumsState {
    /** Initial state and re-loading state. */
    data object Loading : AlbumsState

    /** No media access; show the permission state with a Grant button. */
    data object PermissionNeeded : AlbumsState

    /**
     * The provider query failed (permission revoked mid-session, a
     * provider SQLiteException, a missing column); the screen shows the
     * error state with a retry action (r19). Distinct from `Ready` with an
     * empty list --- that one is a clean library, this one is a failed
     * read; the honest-states doctrine forbids masking one as the other.
     */
    data object Error : AlbumsState

    /**
     * Media access granted; `albums` may be empty (clean library, not an
     * error). Since issue #66 a non-empty favorites set pins the
     * pseudo-album entry first ([Favorites.FAVORITES_BUCKET_ID]).
     */
    data class Ready(val albums: List<Album>) : AlbumsState
}
