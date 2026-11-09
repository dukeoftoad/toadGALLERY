package com.toadgallery.gallery.ui.picker

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toadgallery.gallery.data.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared state holder for the user-picked photo set (issue #27).
 *
 * Backs BOTH the Settings tab (picker entry point + live count) and the
 * `picked` sub-screen (staggered grid), so the shell hoists ONE
 * activity-scoped instance and passes it to both destinations --- the set
 * itself never travels through nav arguments.
 *
 * ## Privacy property (ADR-0006 / ADR-0014, product-vision privacy posture)
 * Every item's [MediaItem.uri] is a user-granted, persistable content URI
 * handed over by the Android system Photo Picker (`ACTION_PICK_IMAGES` on
 * API 33+, surfaced through `ActivityResultContracts.PickMultipleVisualMedia`).
 * This path needs ZERO runtime permissions --- no broad `READ_MEDIA_*` /
 * `READ_EXTERNAL_STORAGE` grant is required or requested to browse it ---
 * and the app still holds no INTERNET permission, so the media cannot
 * leave the device even in principle. MediaStore is never queried here;
 * the picker result URIs are the only input.
 *
 * ## v1 limitation (deliberate, documented)
 * The picked set is SESSION-SCOPED: it lives in this in-memory ViewModel
 * and is empty again after process death. Rebuilding it from the persisted
 * URI permission grants (or storing the picked URI set itself) is a
 * follow-up slice --- the grants taken in [onPicked] are exactly what makes
 * a future "remember picked set" view possible, so nothing blocks it but
 * scope.
 */
class PickedPhotosViewModel(app: Application) : AndroidViewModel(app) {

    private val _picked = MutableStateFlow<List<MediaItem>>(emptyList())
    val picked: StateFlow<List<MediaItem>> = _picked.asStateFlow()

    /**
     * Ingests one Photo Picker result batch into the picked set.
     *
     * Merge-with-dedupe semantics, not replace: the system picker remembers
     * previous selections across launches, so re-picking extends the browsable
     * subset and re-confirming already-picked items is a no-op. Deduping is
     * also a correctness requirement, not just a nicety --- the shared grid
     * keys cells on `uri.toString()` and duplicate keys crash a
     * `LazyVerticalStaggeredGrid`, so the final publish re-dedupes defensively.
     *
     * Per-URI mapping into the existing [MediaItem] pipeline (issue #27):
     *  - Only `content://` URIs are accepted (ADR-0006: content URIs only,
     *    never other schemes, never file paths). The picker guarantees
     *    content URIs; the filter keeps the pipeline's invariant explicit.
     *  - `takePersistableUriPermission(READ)` is attempted best-effort in a
     *    try/catch: not every grantor offers persistable grants (some OEM
     *    picker implementations, or the OS cap on persisted grants, raise
     *    `SecurityException`). That is non-fatal --- the URI stays readable
     *    for the session; only the future across-restart slice loses a step.
     *  - `mimeType` comes from `ContentResolver.getType` (`""` when unknown);
     *    `isVideo` is derived from the `video/` prefix exactly like the
     *    MediaStore pipeline derives it.
     *  - `dateAddedSec` is "picked now" (`System.currentTimeMillis() / 1000`)
     *    for the whole batch: picker URIs carry no reliable MediaStore date,
     *    and ordering by pick time is the honest model. Nothing sorts by it
     *    today; the list preserves pick order.
     *  - `id` is synthetic-stable: `uri.toString().hashCode().toLong()`.
     *    Picker URIs have no MediaStore `_id`, and the pipeline never builds
     *    URIs from `id` (the URI is primary here, per ADR-0006), so a
     *    deterministic URI-derived value only needs to be stable, not unique;
     *    hash collisions are theoretical and harmless because nothing keys
     *    on `id` --- the grid keys on the URI string.
     *  - `width`/`height`/`durationMs` keep their `0` "unknown" defaults: the
     *    staggered grid falls back to a square aspect and the video badge
     *    stays hidden, both by design of the shared cell (issues #33/#34).
     *
     * The mapping runs on [Dispatchers.IO] (binder calls to the picker
     * provider); the flow update lands back on the main dispatcher.
     */
    fun onPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val additions = withContext(Dispatchers.IO) {
                val contentUris = uris.filter { it.scheme == SCHEME_CONTENT }
                val seen = _picked.value.mapTo(HashSet()) { it.uri.toString() }
                val nowSec = System.currentTimeMillis() / MILLIS_PER_SECOND
                contentUris.mapNotNull { uri ->
                    val key = uri.toString()
                    if (!seen.add(key)) return@mapNotNull null // old pick re-confirmed
                    try {
                        resolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    } catch (_: SecurityException) {
                        // No persistable grant on offer (or grant cap hit) ---
                        // non-fatal: the URI remains usable for this session.
                    }
                    val mimeType = resolver.getType(uri) ?: ""
                    MediaItem(
                        id = key.hashCode().toLong(),
                        uri = uri,
                        mimeType = mimeType,
                        dateAddedSec = nowSec,
                        isVideo = mimeType.startsWith("video/"),
                    )
                }
            }
            if (additions.isNotEmpty()) {
                _picked.value = (_picked.value + additions)
                    .distinctBy { it.uri.toString() }
            }
        }
    }

    private companion object {
        /** ADR-0006: only content URIs may enter the media pipeline. */
        const val SCHEME_CONTENT = "content"
        const val MILLIS_PER_SECOND = 1_000L
    }
}
