package com.toadgallery.gallery.data.media.location

/**
 * What SHOULD happen to the location index when the ACCESS_MEDIA_LOCATION
 * consent state moves (r16 round; the re-index-on-grant decision ladder,
 * smallest honest branch --- see [LocationConsentReindexPolicy]).
 *
 * The actions, in decision order:
 *  - [NOT_REQUIRED]: below API 29 the permission does not exist and the
 *    platform never redacted EXIF location
 *    (https://developer.android.com/training/data-storage/shared/media),
 *    so no grant can change what any row's GPS read would have produced.
 *    Nothing to re-index, ever.
 *  - [NO_GRANT]: consent is not granted. The indexer keeps running and
 *    keeps writing the honest MEDIA_LOCATION_UNGRANTED provenance for
 *    newly scanned rows --- there is no grant to react to.
 *  - [NOTHING_PENDING]: granted (now, or always was), and NO live row
 *    carries the withheld provenance --- every scanned row already got
 *    its honest read. Nothing to re-index.
 *  - [REPASS_USEFUL]: granted AND at least one row carries the withheld
 *    provenance --- a full location re-pass over exactly those rows
 *    WOULD change persisted state (their EXIF GPS becomes readable).
 *    v1 does not trigger that pass automatically; see
 *    [LocationConsentReindexPolicy] for the honest limitation.
 */
enum class LocationReindexAction {
    NOT_REQUIRED,
    NO_GRANT,
    NOTHING_PENDING,
    REPASS_USEFUL,
}

/**
 * The PURE decision half of the re-index-on-grant seam (r16), JVM-tested:
 * given the consent facts and how many rows were first scanned while the
 * platform redacted EXIF location, decide what the location index needs.
 *
 * WHY THE FULL RE-PASS IS NOT WIRED IN v1 (the decision ladder's smallest
 * honest branch, stated plainly): the scan pipeline's existing seams
 * cannot express "re-index rows whose CONTENT did not change". The
 * driver's change detection is checkpoint/generation-based --- on API 30+
 * an unchanged MediaStore means every volume SKIPs, and even a forced
 * `runScan(MANUAL)` pass feeds the hooks only the rows the RECONCILER
 * marks added/changed, so the withheld rows would never reach the
 * indexer. Doing it right needs a "re-index rows WHERE locationSource =
 * 'MEDIA_LOCATION_UNGRANTED'" driver pass (or the Phase 5 Places index's
 * own scheduler), which is invasive pipeline surgery this round refuses
 * to do behind the driver's back.
 *
 * The honest v1 semantics this policy documents instead: items first
 * scanned while ungranted resolve their location on their NEXT CONTENT
 * CHANGE (any scan that observes the row as added/changed re-runs the
 * location indexer on it, now granted), and a grant-triggered full
 * re-pass lands with the Phase 5 Places index. The wiring this round is
 * the trivial part: the map screen surfaces the pending count so the
 * limitation is visible to the user, not hidden in a log line.
 *
 * Pure Kotlin (no android.*); the consent facts arrive as plain booleans
 * so the JVM suite pins every branch without the Android runtime
 * (ADR-0016).
 */
object LocationConsentReindexPolicy {

    /**
     * Decides the re-index action.
     *
     * @param permissionRequired false below API 29 (the permission does
     *   not exist; `MediaLocationAccess.required()`).
     * @param granted whether ACCESS_MEDIA_LOCATION is granted right now
     *   (`MediaLocationAccess.isGranted()`, which is always true below
     *   API 29 --- the two inputs never disagree there: required=false
     *   decides first).
     * @param pendingUngrantedRows how many live rows carry the
     *   `MEDIA_LOCATION_UNGRANTED` provenance
     *   ([com.toadgallery.gallery.data.catalogue.room
     *   .RoomMediaCatalogue.pendingUngrantedLocations]); negative inputs
     *   are treated as "none" (defensive: a count can never be negative).
     */
    fun decide(
        permissionRequired: Boolean,
        granted: Boolean,
        pendingUngrantedRows: Int,
    ): LocationReindexAction = when {
        !permissionRequired -> LocationReindexAction.NOT_REQUIRED
        !granted -> LocationReindexAction.NO_GRANT
        pendingUngrantedRows > 0 -> LocationReindexAction.REPASS_USEFUL
        else -> LocationReindexAction.NOTHING_PENDING
    }
}
