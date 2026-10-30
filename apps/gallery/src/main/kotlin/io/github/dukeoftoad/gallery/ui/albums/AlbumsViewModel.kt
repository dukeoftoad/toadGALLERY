package io.github.dukeoftoad.gallery.ui.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.data.Album
import io.github.dukeoftoad.gallery.data.MediaStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Albums tab (Phase 3, issue #29).
 *
 * Mirrors the Photos tab's three-state model (issue #25) so both tabs feel
 * identical: Loading → PermissionNeeded → Ready. `Ready` with an empty list
 * means the user granted access but has no media on the device.
 */
class AlbumsViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<AlbumsState>(AlbumsState.Loading)
    val state: StateFlow<AlbumsState> = _state.asStateFlow()

    /** Called by the screen once the runtime permission grant has succeeded. */
    fun onPermissionGranted() {
        _state.value = AlbumsState.Loading
        viewModelScope.launch {
            val albums = withContext(Dispatchers.IO) { source.queryAlbums() }
            _state.value = AlbumsState.Ready(albums)
        }
    }

    /** Called when the user denies the permission or revokes it later. */
    fun onPermissionDenied() {
        _state.value = AlbumsState.PermissionNeeded
    }
}

sealed interface AlbumsState {
    /** Initial state and re-loading state. */
    data object Loading : AlbumsState

    /** No media access; show the permission state with a Grant button. */
    data object PermissionNeeded : AlbumsState

    /** Media access granted; `albums` may be empty (clean library, not an error). */
    data class Ready(val albums: List<Album>) : AlbumsState
}
