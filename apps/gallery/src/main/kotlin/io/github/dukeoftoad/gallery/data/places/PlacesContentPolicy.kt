package io.github.dukeoftoad.gallery.data.places

import io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueLocationRow

/**
 * The Places surface's honest content states (r27), decided by the PURE
 * [PlacesContentPolicy] --- the r16 [io.github.dukeoftoad.gallery.ui.map
 * .MapContentPolicy]'s exact decision order applied to the list surface:
 * no Android imports, JVM-tested, so the "which empty state does the user
 * see" rules are pinned rather than implicit in a ViewModel's control
 * flow (ADR-0016).
 */
sealed interface PlacesContentState {

    /** First frame: catalogue + consent reads have not resolved yet. */
    data object Loading : PlacesContentState

    /**
     * Location consent is OFF (API 29+ with ACCESS_MEDIA_LOCATION
     * ungranted): the r14 display gate hides GPS-derived groupings while
     * the user's consent choice says not to show them. The state's copy
     * points at the Settings tab (the request lives in the r14 row).
     */
    data object ConsentOff : PlacesContentState

    /**
     * Consent is fine but no item carries a persisted location yet: the
     * library simply has no readable GPS (or the first scan has not
     * finished). Carries the re-index fact ([pendingUngrantedRows]) for
     * the same honest footnote the map's empty state carries.
     */
    data class Empty(val pendingUngrantedRows: Int) : PlacesContentState

    /**
     * Ready: [rows] are the geotagged rows (newest-first, the projection's
     * own order --- the policy never re-sorts; the region bucketing that
     * follows is the pure [PlacesRegionGrid]'s deterministic output).
     */
    data class Ready(
        val rows: List<CatalogueLocationRow>,
        val pendingUngrantedRows: Int,
    ) : PlacesContentState
}

/**
 * The PURE mapping from the Places surface's two inputs --- "may GPS be
 * displayed right now" (the r14 display gate over the consent state) and
 * "how many rows carry a persisted location" --- to the content state.
 * The [io.github.dukeoftoad.gallery.ui.map.MapContentPolicy] twin, pinned
 * by [PlacesContentPolicyTest]; the ViewModels add nothing but the I/O
 * that produces the inputs.
 *
 * Rule order (identical to the map's, pinned by tests):
 *  1. Display gate first: when consent is off, the surface shows the
 *     consent state REGARDLESS of what is persisted --- a region list
 *     built from coordinates the user's current consent choice withholds
 *     would be the exact "secretly kept back" display the r14 gate exists
 *     to prevent.
 *  2. Displayable + zero rows: the honest empty state, with the pending
 *     count for the footnote.
 *  3. Displayable + rows: Ready.
 */
object PlacesContentPolicy {

    fun decide(
        gpsDisplayable: Boolean,
        rows: List<CatalogueLocationRow>,
        pendingUngrantedRows: Int,
    ): PlacesContentState = when {
        !gpsDisplayable -> PlacesContentState.ConsentOff
        rows.isEmpty() -> PlacesContentState.Empty(pendingUngrantedRows)
        else -> PlacesContentState.Ready(rows, pendingUngrantedRows)
    }
}
