package com.toadgallery.gallery.ui.trash

import android.app.Application
import android.content.IntentSender
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.MediaFileOps
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.data.MediaStoreSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen states of the Recycle bin (issue #31).
 *
 * [Unsupported] covers the pre-R device tier: `IS_TRASHED` and the
 * `createTrashRequest` protocol only exist from API 30, and on those
 * devices the Photos delete flow (see `FileOpsViewModel.requestDelete`)
 * stays on the #30 permanent-delete path, so there is no trash to show.
 */
sealed interface TrashUiState {
    data object Loading : TrashUiState
    data object Unsupported : TrashUiState
    data object Error : TrashUiState

    /** One read of the trash store: rows (newest-first) + aggregate size. */
    data class Ready(
        val items: List<MediaItem>,
        val totalBytes: Long,
    ) : TrashUiState
}

/** One-shot UI events: system consent launches and snackbar narration. */
sealed interface TrashEvent {
    data class RequestConsent(val intentSender: IntentSender) : TrashEvent

    /** Plain snackbar line. */
    data class ShowMessage(
        @StringRes val messageRes: Int,
    ) : TrashEvent

    /** Plural snackbar line ([count] drives both the grammar and the value). */
    data class ShowPlural(
        @PluralsRes val messageRes: Int,
        val count: Int,
    ) : TrashEvent
}

/**
 * State holder for the Recycle bin screen (issue #31).
 *
 * ## The trash store is MediaStore's own
 * Rows with `IS_TRASHED = 1`, read via [MediaStoreSource.queryTrashed]
 * (API 30+). Trash/restore/permanent-delete are all
 * `MediaStore.createTrashRequest`/`createDeleteRequest` consent requests:
 * the ViewModel builds ONE request per action and emits
 * [TrashEvent.RequestConsent]; the screen launches it, and the result comes
 * back through [onConsentResult]. The request itself performs the change
 * on `RESULT_OK` --- there is no retry batch, only a [refresh] so the list
 * and the Settings entry narrate the new truth. The ~30-day retention and
 * auto-purge are the system's (the issue's retention window), so no
 * app-side scheduler exists.
 */
class TrashViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    /** Q-annotated ops; the trash consent builders additionally require R. */
    private val ops: MediaFileOps? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaFileOps(app.contentResolver)
        } else {
            null
        }

    private val _state = MutableStateFlow<TrashUiState>(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            TrashUiState.Loading
        } else {
            TrashUiState.Unsupported
        },
    )
    val state: StateFlow<TrashUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<TrashEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<TrashEvent> = _events.asSharedFlow()

    /** The consent request awaiting its activity result; consumed once. */
    private var pendingAction: PendingAction? = null

    private data class PendingAction(
        /** true = restore to the library; false = delete permanently. */
        val restore: Boolean,
        val items: List<MediaItem>,
    )

    /** Reloads the trash content. No-op (and Unsupported) below API 30. */
    fun refresh() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            _state.value = TrashUiState.Unsupported
            return
        }
        viewModelScope.launch {
            _state.value = TrashUiState.Loading
            _state.value = try {
                withContext(Dispatchers.IO) {
                    // Lexically guarded above: SDK_INT >= R in this branch.
                    @Suppress("NewApi")
                    val content = source.queryTrashed()
                    TrashUiState.Ready(content.items, content.totalBytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                TrashUiState.Error
            }
        }
    }

    /** Puts [items] back into the library (one restore request, R+). */
    fun requestRestore(items: List<MediaItem>) {
        requestConsent(restore = true, items = items)
    }

    /** Deletes [items] permanently (one delete request, R+). */
    fun requestDeleteForever(items: List<MediaItem>) {
        requestConsent(restore = false, items = items)
    }

    private fun requestConsent(restore: Boolean, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val localOps = ops ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (pendingAction != null) return // one consent round at a time
        // The pendingAction guard is set BEFORE the build so two rapid taps
        // cannot stage two rounds; a refused build clears it before the
        // failure message (r76.1: the build is a provider call on IO that
        // folds rejections into null --- the first cut threw on the main
        // thread and killed the process from the Recycle Bin).
        pendingAction = PendingAction(restore, items)
        viewModelScope.launch {
            @Suppress("NewApi")
            val sender = if (restore) {
                localOps.createRestoreConsentRequest(items.map { it.uri })
            } else {
                localOps.createDeleteConsentRequest(items.map { it.uri })
            }
            if (sender == null) {
                pendingAction = null
                _events.tryEmit(TrashEvent.ShowMessage(R.string.trash_consent_denied))
                return@launch
            }
            _events.tryEmit(TrashEvent.RequestConsent(sender))
        }
    }

    /**
     * Activity result of the system consent dialog. On `RESULT_OK` the
     * request already performed the change: narrate the count and reload.
     * A decline changes nothing by definition of the protocol.
     */
    fun onConsentResult(granted: Boolean) {
        val action = pendingAction ?: return
        pendingAction = null
        if (!granted) {
            _events.tryEmit(TrashEvent.ShowMessage(R.string.trash_consent_denied))
            return
        }
        _events.tryEmit(
            if (action.restore) {
                TrashEvent.ShowPlural(R.plurals.trash_restored, action.items.size)
            } else {
                TrashEvent.ShowPlural(R.plurals.trash_deleted_forever, action.items.size)
            },
        )
        refresh()
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 16
    }
}
