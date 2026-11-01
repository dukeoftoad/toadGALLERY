package io.github.dukeoftoad.gallery.data.filters

/**
 * The one-shot, process-wide hand-off that carries a requested collection
 * filter from the Albums tab's Collections section to the Photos timeline
 * (r17 round).
 *
 * WHY A HAND-OFF OBJECT: the Albums screen's only navigation callback
 * opens album detail --- the shell (`ToadGalleryApp`) owns every tab
 * switch, and the shell file is frozen for the parallel r15-r18 rounds.
 * The honest mechanism within that constraint is the same one the shell's
 * own cross-screen state uses (activity-scoped ViewModels): one
 * process-wide slot, written by Albums, consumed by the Photos screen on
 * entry. It is in-memory only --- zero persistence, by design and by the
 * Collections contract (derived views, nothing stored).
 *
 * Semantics:
 *  - [request] overwrites (latest request wins);
 *  - [consume] returns the pending filter ONCE and clears the slot ---
 *    re-entering Photos without a new request applies nothing;
 *  - the slot is @Volatile: the write (Albums, main thread) and the read
 *    (Photos, main thread) happen on different compositions, and the
 *    annotation keeps the visibility honest even though both callers are
 *    UI-thread in practice.
 *
 * Pure Kotlin (ADR-0016): `PhotosFilterHandoffTest` pins the one-shot and
 * latest-wins contracts on the plain JVM.
 */
object PhotosFilterHandoff {

    @Volatile
    private var pending: TimelineFilter? = null

    /** Stashes [filter] as the pending request (latest wins). */
    fun request(filter: TimelineFilter) {
        pending = filter
    }

    /**
     * Returns the pending filter and clears the slot, or `null` when
     * nothing is pending. One-shot by construction.
     */
    fun consume(): TimelineFilter? {
        val filter = pending
        pending = null
        return filter
    }
}
