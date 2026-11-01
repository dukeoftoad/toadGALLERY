package io.github.dukeoftoad.gallery.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueLocationRow
import io.github.dukeoftoad.gallery.data.geo.GeoJsonParser
import io.github.dukeoftoad.gallery.data.geo.GeoOutline
import io.github.dukeoftoad.gallery.data.media.location.AndroidMediaLocationAccess
import io.github.dukeoftoad.gallery.data.media.location.MediaLocationDisplayRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * State holder for the on-device Map tab (r16 round, Phase 4 still-open
 * item "on-device map view").
 *
 * THE STATE MODEL: the pure [MapContentPolicy] decides between the three
 * honest content states --- ConsentOff (the r14 display gate says GPS may
 * not be shown; the copy points at Settings), Empty (consent fine, no
 * persisted locations yet) and Ready (the map dots). This ViewModel only
 * PRODUCES the policy's inputs:
 *  - the geotagged rows, from the catalogue's location projection
 *    ([RoomMediaCatalogue.geotaggedRows][io.github.dukeoftoad.gallery.data
 *    .catalogue.room.RoomMediaCatalogue.geotaggedRows]), reloaded on the
 *    catalogue's `changes()` signal (debounced like the Photos timeline:
 *    the scan pipeline emits per batch) so dots appear as the scan's
 *    location pass fills columns;
 *  - the pending-withheld count
 *    ([RoomMediaCatalogue.pendingUngrantedLocations]) for the honest
 *    "scanned while location access was off" footnote the empty/ready
 *    states carry (see
 *    [LocationConsentReindexPolicy][io.github.dukeoftoad.gallery.data
 *    .media.location.LocationConsentReindexPolicy] for the re-pass
 *    limitation);
 *  - the display gate, from the SAME r14 machinery the Details view uses
 *    ([AndroidMediaLocationAccess] + [MediaLocationDisplayRules]) ---
 *    reused, not forked.
 *
 * THE OUTLINE: the bundled world outline is loaded ONCE from assets and
 * parsed on a background dispatcher by the pure [GeoJsonParser]; any
 * failure degrades to `null`, and the canvas honestly draws the graticule
 * and the photo dots without landmasses rather than failing the screen
 * (ADR-0016: the parse decisions are JVM-pinned in `GeoJsonParserTest`,
 * including a parse of this very asset).
 *
 * Privacy (ADR-0014): everything here is on-device --- the catalogue is a
 * local Room database, the outline is a bundled asset, and no network
 * surface exists (the app holds no INTERNET permission; the map renders
 * with zero tile fetches ever --- see docs/phase-4/on-device-map.md).
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * The process-wide catalogue's concrete Room face (r16): the location
     * surface ([geotaggedRows], [pendingUngrantedLocations]) exists only on
     * `RoomMediaCatalogue` (deliberately not on the
     * [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue]
     * interface this round), while `changes()` comes from the interface
     * --- both point at the ONE shared database instance the Application
     * holds, so invalidation reaches this collector.
     */
    private val catalogue =
        getApplication<ToadGalleryApplication>().mediaCatalogue

    /** The r14 consent machinery, reused (not forked) for the display gate. */
    private val locationAccess = AndroidMediaLocationAccess(app)

    private val _state = MutableStateFlow<MapContentState>(MapContentState.Loading)
    val state: StateFlow<MapContentState> = _state.asStateFlow()

    /**
     * The parsed world outline, or `null` while loading / after an honest
     * parse failure. The screen renders the map the moment pins and the
     * first non-null outline exist; a `null` outline never blocks the
     * dots.
     */
    private val _outline = MutableStateFlow<GeoOutline?>(null)
    val outline: StateFlow<GeoOutline?> = _outline.asStateFlow()

    // The policy inputs; republished through [recomputeState] on every change.
    private var geotaggedRows: List<CatalogueLocationRow> = emptyList()
    private var pendingUngrantedRows: Int = 0
    private var gpsDisplayable: Boolean =
        MediaLocationDisplayRules.Default.shouldDisplayGps(locationAccess.state.value)

    init {
        // The bundled outline: one read + one parse, off the main thread.
        viewModelScope.launch { loadOutline() }
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
        // The pin supply: initial read + a debounced reload per catalogue
        // mutation batch (the scan pipeline's location pass writes land
        // here; same debounce discipline as the Photos timeline).
        viewModelScope.launch { reloadPins() }
        viewModelScope.launch {
            catalogue.changes()
                .debounce(PIN_RELOAD_DEBOUNCE_MS.milliseconds)
                .collect { reloadPins() }
        }
    }

    /**
     * Called by the screen on ON_RESUME: one cheap consent re-check, so a
     * grant flipped in system settings (or the Settings tab's request
     * row) is reflected when the user returns to the Map without waiting
     * for a process restart.
     */
    fun refreshConsent() {
        locationAccess.refresh()
    }

    private suspend fun reloadPins() {
        geotaggedRows = catalogue.geotaggedRows()
        pendingUngrantedRows = catalogue.pendingUngrantedLocations()
        recomputeState()
    }

    private fun recomputeState() {
        _state.value = MapContentPolicy.decide(
            gpsDisplayable = gpsDisplayable,
            rows = geotaggedRows,
            pendingUngrantedRows = pendingUngrantedRows,
        )
    }

    private suspend fun loadOutline() {
        val parsed = withContext(Dispatchers.IO) {
            // Honest degradation: any read/parse failure means "no
            // outline" (null), never a crash --- the map still draws its
            // graticule and the photo dots.
            runCatching {
                val text = getApplication<Application>()
                    .assets
                    .open(WORLD_OUTLINE_ASSET)
                    .bufferedReader()
                    .use { it.readText() }
                GeoJsonParser.parseOutline(text)
            }.getOrNull()
        }
        _outline.value = parsed
    }

    private companion object {
        /** The bundled Natural Earth 110m land outline (public domain). */
        const val WORLD_OUTLINE_ASSET = "maps/world-110m.geojson"

        /**
         * Debounce for pin reloads: the scan pipeline's location pass
         * writes rows one targeted UPDATE at a time, and the catalogue's
         * `changes()` re-emits per write; one reload per burst is enough
         * (the same 500ms discipline as the Photos timeline's batches).
         */
        const val PIN_RELOAD_DEBOUNCE_MS = 500L
    }
}
