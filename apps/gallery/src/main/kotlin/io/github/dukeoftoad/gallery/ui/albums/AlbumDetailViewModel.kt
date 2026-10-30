package io.github.dukeoftoad.gallery.ui.albums

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.data.BucketContent
import io.github.dukeoftoad.gallery.data.MediaStoreSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the album detail view (Phase 3, issue #29).
 *
 * Loads one bucket's contents plus its display name. The screen is only
 * reachable after the Albums tab already loaded with granted access, so
 * there is no permission flow here; a mid-session revoke surfaces as an
 * empty grid rather than a crash (the query is defensive).
 */
class AlbumDetailViewModel(
    app: Application,
    private val bucketId: Long,
) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<AlbumDetailState>(AlbumDetailState.Loading)
    val state: StateFlow<AlbumDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val content: BucketContent = withContext(Dispatchers.IO) {
                runCatching { source.queryBucket(bucketId) }
                    .getOrElse { BucketContent(bucketName = "", items = emptyList()) }
            }
            _state.value = AlbumDetailState.Ready(
                bucketName = content.bucketName.ifEmpty { "Album" },
                items = content.items,
            )
        }
    }

    companion object {
        /** Minimal factory carrying the navigation argument. */
        fun factory(application: Application, bucketId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AlbumDetailViewModel(application, bucketId) as T
            }
    }
}

sealed interface AlbumDetailState {
    data object Loading : AlbumDetailState

    data class Ready(
        val bucketName: String,
        val items: List<io.github.dukeoftoad.gallery.data.MediaItem>,
    ) : AlbumDetailState
}
