package com.toadgallery.gallery.data.places

/**
 * The one-shot, process-wide hand-off that carries a tapped Places
 * region from the Places surfaces (the Places screen, the Albums tab's
 * Places section) to that region's media grid (r27).
 *
 * The r17 [com.toadgallery.gallery.data.filters
 * .PhotosFilterHandoff] pattern, applied verbatim: the Albums/Places
 * screens own no navigation (the shell owns every route switch), so the
 * tap stashes its request in ONE process-wide slot that the Place detail
 * screen consumes on entry. In-memory only, zero persistence --- a
 * Places region is a DERIVED view over the live catalogue (the
 * DerivedCollections doctrine), never stored state.
 *
 * Why the hand-off AND a nav argument: the route's `{regionKey}`
 * argument is the durable identity (it survives process death and
 * re-derives the region from the live catalogue), while the hand-off is
 * the request semantics --- exactly-once consumption, latest-wins ---
 * matching how the Albums tab's Collections hand their filter to the
 * Photos timeline. The detail screen prefers the consumed request and
 * falls back to the nav argument; both name the same deterministic
 * bucket (the pure [PlacesRegionGrid] mapping is total and stable).
 *
 * Semantics (the PhotosFilterHandoff contract):
 *  - [request] overwrites (latest request wins);
 *  - [consume] returns the pending request ONCE and clears the slot;
 *  - `@Volatile` for cross-composition visibility.
 *
 * Pure Kotlin (ADR-0016): `PlaceMediaHandoffTest` pins the one-shot and
 * latest-wins contracts on the plain JVM.
 */
object PlaceMediaHandoff {

    /** One tapped-region request: the bucket's stable key. */
    data class PlaceMediaRequest(val regionKey: String)

    @Volatile
    private var pending: PlaceMediaRequest? = null

    /** Stashes [regionKey]'s request as the pending one (latest wins). */
    fun request(regionKey: String) {
        pending = PlaceMediaRequest(regionKey)
    }

    /**
     * Returns the pending request and clears the slot, or `null` when
     * nothing is pending. One-shot by construction.
     */
    fun consume(): PlaceMediaRequest? {
        val request = pending
        pending = null
        return request
    }
}
