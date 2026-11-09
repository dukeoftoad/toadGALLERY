package com.toadgallery.gallery.ui.places

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toadgallery.gallery.ToadGalleryApplication
import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import com.toadgallery.gallery.data.media.location.AndroidMediaLocationAccess
import com.toadgallery.gallery.data.media.location.MediaLocationDisplayRules
import com.toadgallery.gallery.data.places.PlacesContentPolicy
import com.toadgallery.gallery.data.places.PlacesContentState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * State holder for the Places surface (r27, the MI-style organisational
 * UX round): the coarse offline region buckets over the Room catalogue's
 * persisted GPS coordinates.
 *
 * THE STATE MODEL: the pure [PlacesContentPolicy] decides between the
 * three honest content states (the r16 map's exact discipline --- Consent
 * first, then the honest Empty, then Ready). This ViewModel only
 * PRODUCES the policy's inputs, and it produces them from the SAME
 * single supply the map reads:
 *  - the geotagged rows
 *    ([RoomMediaCatalogue.geotaggedRows][com.toadgallery.gallery.data
 *    .catalogue.room.RoomMediaCatalogue.geotaggedRows]), reloaded on the
 *    catalogue's `changes()` signal (debounced like the Photos timeline
 *    and the map: the scan pipeline emits per batch);
 *  - the pending-withheld count
 *    ([RoomMediaCatalogue.pendingUngrantedLocations]) for the same
 *    honest "scanned while location access was off" footnote the map
 *    carries;
 *  - the r14 display gate, from the SAME machinery the Details view and
 *    the map use ([AndroidMediaLocationAccess] +
 *    [MediaLocationDisplayRules]) --- reused, not forked.
 *
 * The region BUCKETING itself (the pure [PlacesRegionGrid][io.github
 * .dukeoftoad.gallery.data.places.PlacesRegionGrid]) is derived in the
 * composition from the Ready rows (deterministic pure math; deriving it
 * here would only add one indirection), exactly like the map's dot
 * clustering is derived from its rows in the canvas layer.
 *
 * The eligibility boundary is inherited wholesale (see
 * [PlacesRegionGrid]'s KDoc): the catalogue's live rows are the scan
 * pipeline's MediaEligibility-gated public media; vault media never
 * enter it and trashed rows leave it, so no Places surface can list
 * vault or trash content.
 *
 * Privacy (ADR-0014, and the roadmap's explicit Places rule): everything
 * is on-device --- the catalogue is a local Room database, the region
 * labels are pure math over the coordinates
 * ([PlacesRegionLabels][com.toadgallery.gallery.data.places
 * .PlacesRegionLabels]), and NO reverse-geocoding network call exists
 * (the app's only network path serves the ADR-0020 model pack).
 */
class PlacesViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * The process-wide catalogue's concrete Room face (the r16 decision:
     * the location surface exists only on `RoomMediaCatalogue`, not on
     * the frozen `MediaCatalogue` interface), while `changes()` comes
     * from the interface --- both point at the ONE shared database
     * instance the Application holds.
     */
    private val catalogue =
        getApplication<ToadGalleryApplication>().mediaCatalogue

    /** The r14 consent machinery, reused (not forked) for the display gate. */
    private val locationAccess = AndroidMediaLocationAccess(app)

    private val _state = MutableStateFlow<PlacesContentState>(PlacesContentState.Loading)
    val state: StateFlow<PlacesContentState> = _state.asStateFlow()

    // The policy inputs; republished through [recomputeState] on every change.
    private var geotaggedRows: List<CatalogueLocationRow> = emptyList()
    private var pendingUngrantedRows: Int = 0
    private var gpsDisplayable: Boolean =
        MediaLocationDisplayRules.Default.shouldDisplayGps(locationAccess.state.value)

    init {
        // Consent: the display gate input. [AndroidMediaLocationAccess]
        // constructs with the OS check already folded in; the collector
        // mainly serves [refreshConsent]'s later re-checks.
        viewModelScope.launch {
            locationAccess.state.collect { state ->
                gpsDisplayable =
                    MediaLocationDisplayRules.Default.shouldDisplayGps(state)
                recomputeState()
            }
        }
        // The row supply: initial read + a debounced reload per catalogue
        // mutation batch (the same 500ms discipline as the map's pins).
        viewModelScope.launch { reloadRows() }
        viewModelScope.launch {
            catalogue.changes()
                .debounce(RELOAD_DEBOUNCE_MS.milliseconds)
                .collect { reloadRows() }
        }
    }

    /**
     * Called by the screen on ON_RESUME: one cheap consent re-check, so a
     * grant flipped in Settings is reflected when the user returns to
     * the surface without waiting for a process restart (the map's
     * refresh discipline).
     */
    fun refreshConsent() {
        locationAccess.refresh()
    }

    private suspend fun reloadRows() {
        geotaggedRows = catalogue.geotaggedRows()
        pendingUngrantedRows = catalogue.pendingUngrantedLocations()
        recomputeState()
    }

    private fun recomputeState() {
        _state.value = PlacesContentPolicy.decide(
            gpsDisplayable = gpsDisplayable,
            rows = geotaggedRows,
            pendingUngrantedRows = pendingUngrantedRows,
        )
    }

    private companion object {
        /**
         * Debounce for row reloads: the scan pipeline's location pass
         * writes rows one targeted UPDATE at a time, and the catalogue's
         * `changes()` re-emits per write; one reload per burst is enough
         * (the map's 500ms discipline, which is the Photos timeline's).
         */
        const val RELOAD_DEBOUNCE_MS = 500L
    }
}
