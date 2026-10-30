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
}
