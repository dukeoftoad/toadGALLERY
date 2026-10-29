package io.github.dukeoftoad.gallery.ui.photos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.MediaStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Photos timeline (Phase 3, issue #25).
 *
 * Three states, derived from the user's permission decision:
 *  - `Loading`       : first frame, before the first permission check or query
 *  - `PermissionNeeded`: no media access yet (initial denial, or revoked later)
 *  - `Ready`         : media access granted; the timeline list may still be empty
 *                      if the user has no photos on-device.
 *
 * The query runs off the main thread on `Dispatchers.IO` and the result is
 * published back to the main thread via `StateFlow`. The source is the
 * `MediaStoreSource` (URI-first per ADR-0006); no paging yet.
 */
class PhotosViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<PhotosState>(PhotosState.Loading)
    val state: StateFlow<PhotosState> = _state.asStateFlow()

    /**
     * Called by the screen once the runtime permission grant has succeeded.
     * Triggers a fresh timeline load (the screen calls this only on grant, so
     * repeated calls are cheap but unnecessary).
     */
    fun onPermissionGranted() {
        _state.value = PhotosState.Loading
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { source.queryTimeline() }
            _state.value = PhotosState.Ready(items)
        }
    }

    /** Called when the user denies the permission or revokes it later. */
    fun onPermissionDenied() {
        _state.value = PhotosState.PermissionNeeded
    }
}

sealed interface PhotosState {
    /** Initial state and re-loading state. */
    data object Loading : PhotosState

    /** No media access; show the permission state with a Grant button. */
    data object PermissionNeeded : PhotosState

    /** Media access granted; `items` may be empty (clean library, not an error). */
    data class Ready(val items: List<MediaItem>) : PhotosState
}
