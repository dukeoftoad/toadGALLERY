package com.toadgallery.gallery.ui.places

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.toadgallery.gallery.ToadGalleryApplication
import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import com.toadgallery.gallery.data.places.PlaceMediaHandoff
import com.toadgallery.gallery.data.places.PlaceRegion
import com.toadgallery.gallery.data.places.PlacesRegionGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for one Place region's media grid (r27): the bucket's
 * rows over the SAME catalogue location projection the Places screen and
 * the map read, re-derived from the live data on every load.
 *
 * ## The region key's two sources (the hand-off + the nav argument)
 * The screen is entered through the r17 hand-off pattern: the tapping
 * surface stashed its [PlaceMediaHandoff] request, which this ViewModel
 * CONSUMES on init (one-shot, latest-wins). The navigation argument is
 * the durable fallback --- process death or a re-derived back stack
 * re-enters with the route's `{regionKey}` and the live catalogue
 * answers the same deterministic bucket (the pure [PlacesRegionGrid]
 * mapping is total and stable), so no stale in-memory state ever fakes a
 * region.
 *
 * ## State model (the PersonDetail discipline)
 *  - [PlaceDetailState.Loading] / [PlaceDetailState.Error] (the
 *    failure's own message, surfaced verbatim);
 *  - [PlaceDetailState.Missing]: the region no longer exists (every
 *    geotagged row left the bucket --- moved, trashed, vault-sealed):
 *    the screen pops back; faking a grid of stale photos would be the
 *    opposite of honest;
 *  - [PlaceDetailState.Ready]: the bucket's rows, the projection's own
 *    newest-first order (no re-sorting).
 *
 * The eligibility boundary is inherited (see [PlacesRegionGrid]'s KDoc):
 * the rows come from the catalogue's live table, which vault media never
 * enter and trashed rows leave.
 */
class PlaceDetailViewModel(
    app: Application,
    regionKey: String,
) : AndroidViewModel(app) {

    private val catalogue =
        getApplication<ToadGalleryApplication>().mediaCatalogue

    private val _state = MutableStateFlow<PlaceDetailState>(PlaceDetailState.Loading)
    val state: StateFlow<PlaceDetailState> = _state.asStateFlow()

    /** Monotonic load generation: only the youngest load publishes. */
    private var loadGeneration = 0

    init {
        // The hand-off is consumed EXACTLY ONCE (the PhotosFilterHandoff
        // contract); the nav argument is the fallback when nothing is
        // pending (process death, back-stack re-entry).
        load(PlaceMediaHandoff.consume()?.regionKey ?: regionKey)
    }

    /** Re-reads the region (the Error state's retry action). */
    fun retry() {
        _state.value = PlaceDetailState.Loading
        load(activeRegionKey ?: return)
    }

    /**
     * One load over [regionKey] (the consumed hand-off's key on the
     * initial entry --- which also becomes the retry anchor --- or the
     * already-active key on a retry).
     */
    private fun load(regionKey: String) {
        val generation = ++loadGeneration
        activeRegionKey = regionKey
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { loadReadyState(regionKey) }
                    .getOrElse { e -> PlaceDetailState.Error(e.message ?: e.javaClass.simpleName) }
            }
            if (generation == loadGeneration) {
                _state.value = fresh
            }
        }
    }

    /**
     * The success path, already on a worker thread: the full geotagged
     * projection, the pure bucketing, and THIS key's bucket. A key that
     * no longer buckets any row is [PlaceDetailState.Missing] --- the
     * live-data truth, never a stale grid.
     */
    private suspend fun loadReadyState(regionKey: String): PlaceDetailState {
        val rows = catalogue.geotaggedRows()
        val region: PlaceRegion = PlacesRegionGrid.bucket(rows)
            .firstOrNull { it.regionKey == regionKey }
            ?: return PlaceDetailState.Missing
        return PlaceDetailState.Ready(
            region = region,
            rows = region.memberIndexes.mapNotNull { rows.getOrNull(it) },
        )
    }

    /** The current window's key (the retry anchor, set by the first [load]). */
    private var activeRegionKey: String? = null

    companion object {
        /** Minimal factory carrying the navigation argument (the PersonDetail pattern). */
        fun factory(application: Application, regionKey: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlaceDetailViewModel(application, regionKey) as T
            }
    }
}

/** The Place detail screen's states (r27). */
sealed interface PlaceDetailState {
    data object Loading : PlaceDetailState

    /** The read failed; [message] is the failure's own text, surfaced verbatim. */
    data class Error(val message: String) : PlaceDetailState

    /** The region no longer buckets any row; the screen pops back. */
    data object Missing : PlaceDetailState

    /** The region and its rows, the projection's own newest-first order. */
    data class Ready(
        val region: PlaceRegion,
        val rows: List<CatalogueLocationRow>,
    ) : PlaceDetailState
}
