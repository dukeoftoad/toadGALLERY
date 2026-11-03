package io.github.dukeoftoad.gallery.ui.viewer

import androidx.lifecycle.ViewModel
import io.github.dukeoftoad.gallery.data.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State holder for the full-screen media viewer (issue #50).
 *
 * Activity-scoped by construction: `ToadGalleryApp` creates ONE instance with
 * `viewModel()` at the shell level and hands it both to the grid screens ---
 * which call [open] right before navigating --- and to [ViewerScreen], which
 * renders from [window]. This is the same shell-scoped hand-off the
 * picked-photos set uses (issue #27) and it exists for the same reason: the
 * window list is far too large for navigation arguments, and a nav-scoped
 * ViewModel would be a DIFFERENT instance than the one the grid screens
 * wrote to.
 *
 * The window is written once per navigation and never mutated while the
 * viewer is open. It is deliberately NOT cleared when the viewer closes:
 * clearing on dispose would blank the viewer across an activity recreation
 * (rotation) between dispose and the next composition, and the retained list
 * is a handful of immutable data classes --- trivially small. The next
 * [open] simply replaces it.
 */
class ViewerViewModel : ViewModel() {

    private val _window = MutableStateFlow<ViewerWindow<MediaItem>?>(null)

    /** The window to page over, or `null` before the first [open]. */
    val window: StateFlow<ViewerWindow<MediaItem>?> = _window.asStateFlow()

    /**
     * Arms the viewer with the grid's tap-time window and the tapped position.
     * Clamping is delegated to [ViewerWindow.of] so the JVM test pins it.
     */
    fun open(items: List<MediaItem>, index: Int) {
        _window.value = ViewerWindow.of(items, index)
    }

    /**
     * Drops the page of the item whose content URI is [uriString] (issue
     * #63: a successful viewer delete). Removal is by URI, not by the page
     * index at request time --- the user can swipe while a system consent
     * dialog is up, and the pager must drop the right page regardless.
     * [ViewerWindow.withoutPageAt] picks the honest follow-up page, and an
     * emptied window becomes `null`, which [ViewerScreen]'s defensive
     * empty-check turns into "navigate back" --- the viewer never strands
     * the user on a black screen.
     *
     * Rename does NOT need a counterpart: a MediaStore rename rewrites
     * `DISPLAY_NAME` only; row id and content URI are unchanged, so the
     * URI-keyed pager pages stay valid (see [ViewerWindow.withoutPageAt]'s
     * KDoc).
     */
    fun removeItem(uriString: String) {
        val current = _window.value ?: return
        val index = current.items.indexOfFirst { it.uri.toString() == uriString }
        if (index < 0) return
        _window.value = current.withoutPageAt(index)
    }

    // ---- Slideshow (r27, the MI-style organisational UX round) ----

    /**
     * Whether the viewer's slideshow auto-advance is running. Shell-scoped
     * ON PURPOSE: the Stories shelf and the grid/album overflow actions
     * launch the viewer WITH the slideshow armed (they call [open] then
     * [startSlideshow] before navigating), so the flag must live where
     * the LAUNCHERS can reach it --- the same reason the window itself is
     * shell-scoped.
     *
     * Lifecycle: the screen STOPS the slideshow on dispose (leaving the
     * viewer always ends a run --- a later plain grid tap re-enters with
     * the flag honestly off), and [open] itself does not touch it, so an
     * armed launch survives the navigate() frame between the two calls.
     */
    private val _slideshowActive = MutableStateFlow(false)

    /** Whether the slideshow is running; see the property KDoc. */
    val slideshowActive: StateFlow<Boolean> = _slideshowActive.asStateFlow()

    /**
     * The auto-advance interval, one of [SlideshowTiming.INTERVALS_MILLIS]
     * (coerced on every write). Shell-scoped with the active flag so a
     * Stories launch and a manual launch share the user's last choice in
     * one session; per-session only --- persisting it is a Settings-round
     * concern, not this round's.
     */
    private val _slideshowIntervalMillis =
        MutableStateFlow(SlideshowTiming.DEFAULT_INTERVAL_MILLIS)

    /** The slideshow's auto-advance interval in milliseconds. */
    val slideshowIntervalMillis: StateFlow<Long> = _slideshowIntervalMillis.asStateFlow()

    /** Arms (or re-arms) the slideshow's auto-advance. */
    fun startSlideshow() {
        _slideshowActive.value = true
    }

    /** Stops the slideshow's auto-advance (the screen's stop action + dispose). */
    fun stopSlideshow() {
        _slideshowActive.value = false
    }

    /** Sets the interval, snapped to the catalog by [SlideshowTiming.coerceInterval]. */
    fun setSlideshowInterval(millis: Long) {
        _slideshowIntervalMillis.value = SlideshowTiming.coerceInterval(millis)
    }
}
