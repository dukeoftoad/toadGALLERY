package io.github.dukeoftoad.gallery.data.collage

import io.github.dukeoftoad.gallery.data.MediaItem

/**
 * The one-shot, process-wide hand-off that carries the Photos tab's
 * multi-selection into the collage creator (r27).
 *
 * The [io.github.dukeoftoad.gallery.data.filters.PhotosFilterHandoff]
 * pattern verbatim (see that object's KDoc for the rationale): the
 * Photos screen owns no navigation --- the shell owns every route switch
 * --- so the "create a collage from these N photos" action stashes its
 * request in ONE process-wide slot that the Collage screen's ViewModel
 * consumes on entry. In-memory only, zero persistence: the picked set is
 * a session decision, and a process death honestly restarts the picker
 * flow rather than resurrecting a stale selection.
 *
 * The carried value is the selection's `MediaItem`s (2..[CollageLayouts
 * .MAX_PHOTOS] of them, enforced at the request site by the action's own
 * enabled-state; the consumer re-checks and treats an out-of-range
 * payload as absent). A list is far too large for navigation arguments
 * --- the same reason the viewer window rides the shell-scoped
 * [io.github.dukeoftoad.gallery.ui.viewer.ViewerViewModel] hand-off.
 *
 * Semantics: [request] overwrites (latest wins); [consume] returns the
 * pending selection ONCE and clears the slot; `@Volatile` for
 * cross-composition visibility.
 *
 * JVM-testability (the honest ADR-0016 limitation): unlike
 * [io.github.dukeoftoad.gallery.data.filters.PhotosFilterHandoff] ---
 * whose `TimelineFilter` payload is pure, so its one-shot/latest-wins
 * contract is JVM-pinned by `PhotosFilterHandoffTest` --- this slot's
 * payload embeds `android.net.Uri` and therefore cannot be constructed
 * on the plain JVM (the same reason `ViewerViewModel`'s window hand-off
 * carries no test of its own). The MECHANICS are byte-identical to the
 * pinned pattern; the only new logic (the 2..9 range gate in [consume])
 * is a two-line coercion whose bounds are JVM-pinned by
 * `CollageLayoutsTest` instead.
 */
object CollageHandoff {

    @Volatile
    private var pending: List<MediaItem>? = null

    /** Stashes [selection] as the pending collage request (latest wins). */
    fun request(selection: List<MediaItem>) {
        pending = selection
    }

    /**
     * Returns the pending selection and clears the slot, or `null` when
     * nothing is pending (or the pending payload is outside the
     * supported 2..9 range --- the honest absent answer, never a
     * half-broken collage). One-shot by construction.
     */
    fun consume(): List<MediaItem>? {
        val selection = pending
        pending = null
        return selection?.takeIf { it.size in CollageLayouts.MIN_PHOTOS..CollageLayouts.MAX_PHOTOS }
    }
}
