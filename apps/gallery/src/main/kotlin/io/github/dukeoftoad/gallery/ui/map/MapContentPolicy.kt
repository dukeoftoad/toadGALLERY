package io.github.dukeoftoad.gallery.ui.map

import io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueLocationRow

/**
 * The map screen's three honest content states (r16 round), decided by the
 * PURE [MapContentPolicy] --- no Android imports, JVM-tested, so the
 * "which empty state does the user see" rules are pinned rather than
 * implicit in the ViewModel's control flow (ADR-0016; same shape as the
 * photos/albums three-state model).
 */
sealed interface MapContentState {

    /** First frame: catalogue + consent reads have not resolved yet. */
    data object Loading : MapContentState

    /**
     * Location consent is OFF (API 29+ with ACCESS_MEDIA_LOCATION
     * ungranted): persisted coordinates may exist, but the r14 display
     * gate ([MediaLocationDisplayRules
     * ][io.github.dukeoftoad.gallery.data.media.location
     * .MediaLocationDisplayRules]) hides GPS while the user's consent
     * choice says not to show it --- the map must not present consent as
     * a switch over data it is secretly keeping back. The state's copy
     * points at the Settings tab (the request lives in the r14 row).
     */
    data object ConsentOff : MapContentState

    /**
     * Consent is fine but no item carries a persisted location yet: the
     * library's photos simply have no readable GPS (or the first scan has
     * not finished). Carries the re-index fact ([pendingUngrantedRows])
     * so the copy can be honest about WHY some items may be missing
     * (scanned while ungranted; they resolve on their next change).
     */
    data class Empty(val pendingUngrantedRows: Int) : MapContentState

    /**
     * Ready: [rows] are the geotagged rows (timeline order), and
     * [pendingUngrantedRows] rides along for the same honest footnote
     * [Empty] carries (a map can be non-empty while OTHER items are still
     * withheld).
     */
    data class Ready(
        val rows: List<CatalogueLocationRow>,
        val pendingUngrantedRows: Int,
    ) : MapContentState
}

/**
 * The PURE mapping from the map's two inputs --- "may GPS be displayed
 * right now" (the r14 display gate over the consent state) and "how many
 * rows carry a persisted location" --- to the screen's content state.
 * Exhaustively JVM-tested ([MapContentPolicyTest]); the ViewModel adds
 * nothing but the I/O that produces the inputs.
 *
 * Rule order (pinned by tests):
 *  1. Display gate first: when consent is off, the screen shows the
 *     consent state REGARDLESS of what is persisted --- dots drawn from
 *     coordinates the user's current consent choice withholds would be
 *     the exact "secretly kept back" display the r14 gate exists to
 *     prevent. (Below API 29 the gate is always displayable, so this arm
 *     is unreachable there.)
 *  2. Displayable + zero rows: the honest empty state, with the pending
 *     count for the footnote.
 *  3. Displayable + rows: the map.
 */
object MapContentPolicy {

    fun decide(
        gpsDisplayable: Boolean,
        rows: List<CatalogueLocationRow>,
        pendingUngrantedRows: Int,
    ): MapContentState = when {
        !gpsDisplayable -> MapContentState.ConsentOff
        rows.isEmpty() -> MapContentState.Empty(pendingUngrantedRows)
        else -> MapContentState.Ready(rows, pendingUngrantedRows)
    }
}
