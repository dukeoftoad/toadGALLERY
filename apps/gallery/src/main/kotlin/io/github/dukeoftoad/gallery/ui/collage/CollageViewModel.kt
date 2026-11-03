package io.github.dukeoftoad.gallery.ui.collage

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.AnyRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.collage.CollageHandoff
import io.github.dukeoftoad.gallery.data.collage.CollageLayouts
import io.github.dukeoftoad.gallery.data.collage.CollageTemplate
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
 * State holder for the collage creator (r27): the multi-selection the
 * Photos tab handed over through [CollageHandoff], the chosen template,
 * the decoded preview bitmaps, and the export run.
 *
 * ## State model (honest, no dead ends)
 *  - [CollageUiState.Loading]: one frame while the hand-off consumes;
 *  - [CollageUiState.Missing]: no (or out-of-range) hand-off payload ---
 *    process death, a stale route entry --- the honest "nothing to
 *    collage" the screen turns into its empty state + back action;
 *  - [CollageUiState.Ready]: the photos, the chosen template id, the
 *    decoded bitmaps (one slot per photo, `null` until its decode lands
 *    or honestly failed), and the export's progress/outcome flags.
 *
 * ## The decode (preview AND export share it)
 * Each photo decodes ONCE through the app's offline Coil loader with
 * `allowHardware(false)` (the export renders into a software bitmap; a
 * hardware-pinned decode could not be drawn there) at a bounded preview
 * size. The screen's Compose Canvas preview and the exporter's Bitmap
 * render consume the SAME bitmaps --- one decode pipeline, two draw
 * surfaces, no double memory.
 *
 * ## The export
 * [export] renders the chosen template at [CollageLayouts
 * .EXPORT_EDGE_PIXELS] and saves it as a NEW MediaStore image through
 * the file-ops naming/save discipline (the r27 round contract): the
 * display name comes from the pure `FileOpNaming.copyDisplayName`
 * collision rule ("Collage.jpg", "Collage (2).jpg", …), the insert is
 * the same Q+ `IS_PENDING` + `RELATIVE_PATH` protocol `MediaFileOps`
 * uses, and a half-written row is cleaned up on failure. Q+ only ---
 * below Android 10 the app is read-only and the screen hides the action
 * (the same gate rename/delete/copy use).
 */
class CollageViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<CollageUiState>(CollageUiState.Loading)
    val state: StateFlow<CollageUiState> = _state.asStateFlow()

    /** One-shot UI events (the FileOpsEvent shape: resource ids + args). */
    private val _events = MutableSharedFlow<CollageEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<CollageEvent> = _events.asSharedFlow()

    /** The decoded preview bitmaps, one per photo (null = not yet / failed). */
    private val _bitmaps = MutableStateFlow<List<Bitmap?>>(emptyList())
    val bitmaps: StateFlow<List<Bitmap?>> = _bitmaps.asStateFlow()

    /** The last successful export's Uri, for the "saved" snackbar's opener. */
    private val _lastExportedUri = MutableStateFlow<Uri?>(null)
    val lastExportedUri: StateFlow<Uri?> = _lastExportedUri.asStateFlow()

    init {
        val photos = CollageHandoff.consume()
        if (photos.isNullOrEmpty()) {
            _state.value = CollageUiState.Missing
        } else {
            _state.value = CollageUiState.Ready(
                photos = photos,
                templateId = CollageLayouts.defaultFor(photos.size)?.id.orEmpty(),
                exporting = false,
                exportFailed = false,
            )
            decodePhotos(photos)
        }
    }

    /** Selects a template (the picker row's tap); a no-op for unknown ids. */
    fun selectTemplate(templateId: String) {
        val ready = _state.value as? CollageUiState.Ready ?: return
        if (CollageLayouts.templatesFor(ready.photos.size).any { it.id == templateId }) {
            _state.value = ready.copy(templateId = templateId)
        }
    }

    /**
     * Exports the collage as a new image (Q+ only; the screen gates the
     * action below Q). Failures narrate honestly --- the state's
     * `exportFailed` flag plus a snackbar --- and never leave a
     * half-written pending row behind.
     */
    fun export() {
        val ready = _state.value as? CollageUiState.Ready ?: return
        if (ready.exporting) return
        val template = CollageLayouts.templatesFor(ready.photos.size)
            .firstOrNull { it.id == ready.templateId }
            ?: return
        _state.value = ready.copy(exporting = true, exportFailed = false)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    exportCollage(
                        app = getApplication(),
                        photos = ready.photos,
                        bitmaps = _bitmaps.value,
                        template = template,
                    )
                }
            }
            val current = _state.value as? CollageUiState.Ready
            if (current != null) {
                _state.value = when {
                    outcome.isFailure -> current.copy(
                        exporting = false,
                        exportFailed = true,
                    )
                    else -> current.copy(exporting = false, exportFailed = false)
                }
            }
            if (outcome.isSuccess) {
                _lastExportedUri.value = outcome.getOrNull()
                _events.tryEmit(CollageEvent.ShowSnackbar(R.string.collage_saved))
            } else {
                _events.tryEmit(CollageEvent.ShowSnackbar(R.string.collage_save_failed))
            }
        }
    }

    /**
     * Decodes every photo once (software bitmaps at a bounded size);
     * individual failures leave that slot `null` --- the preview draws
     * an honest placeholder and the export refuses to run on a missing
     * photo rather than shipping a gray box.
     */
    private fun decodePhotos(photos: List<MediaItem>) {
        viewModelScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                decodeCollageBitmaps(getApplication(), photos)
            }
            // Only publish while the photos are still this screen's (the
            // state cannot change away from Ready in this VM's lifetime,
            // but the guard keeps the pairing explicit).
            if ((_state.value as? CollageUiState.Ready)?.photos === photos) {
                _bitmaps.value = decoded
            }
        }
    }
}

/** The collage screen's states (r27). */
sealed interface CollageUiState {
    /** One frame while the hand-off consumes. */
    data object Loading : CollageUiState

    /** No hand-off payload; the screen shows its honest empty state. */
    data object Missing : CollageUiState

    /**
     * @param photos the handed-over selection (2..9, enforced upstream).
     * @param templateId the chosen template; the id of [CollageLayouts
     * .defaultFor] until the user picks another.
     * @param exporting true while the export renders/saves.
     * @param exportFailed true after a failed export (cleared by retry).
     */
    data class Ready(
        val photos: List<MediaItem>,
        val templateId: String,
        val exporting: Boolean,
        val exportFailed: Boolean,
    ) : CollageUiState
}

/**
 * One-shot collage UI events (r27): snackbars in the `FileOpsEvent`
 * shape --- resource ids only, strings resolved against the current
 * configuration at display time (the @AnyRes convention the r25 lint
 * fix pinned for a field that only ever feeds `getString`).
 */
sealed interface CollageEvent {
    data class ShowSnackbar(@AnyRes val messageRes: Int) : CollageEvent
}
