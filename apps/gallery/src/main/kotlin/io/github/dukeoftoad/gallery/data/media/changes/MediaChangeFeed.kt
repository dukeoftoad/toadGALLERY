package io.github.dukeoftoad.gallery.data.media.changes

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * The Phase 4 change feed (audit contract item "MediaChangeFeed": MediaStore
 * generation tracking by volume on supported Android versions;
 * ContentObserver and reconciliation fallback on older versions;
 * delete/revoke tombstones --- the tombstones themselves live in the
 * catalogue, this interface only carries the SIGNALS).
 *
 * The interface is the seam the incremental pipeline depends on: the scan
 * driver asks [currentGeneration] after every scan and stores it as the
 * volume's checkpoint; when [registerObserver] fires, the driver compares
 * the live generation against the checkpoint and decides between a cheap
 * no-op (same token), a full authoritative re-scan (different token) or ---
 * on API 26-29, where no token exists --- always the re-scan, because the
 * observer alone cannot prove the volume unchanged.
 *
 * API-level contract, stated once:
 * - API 30+ (R): [currentGeneration] returns the per-volume MediaStore
 *   generation via [MediaStore.getGeneration]; observers register on the
 *   volume's files collection Uri.
 * - API 26-29 (minSdk .. Q): there is NO generation --- [currentGeneration]
 *   returns `null` --- so the ContentObserver is the ONLY signal. On 29 the
 *   observer can watch the volume-scoped `MediaStore.Files` Uri; on 26-28
 *   volume-scoped file Uris do not exist either, and the observer falls
 *   back to the classic external images + video collection Uris, which is
 *   exactly the surface `MediaStoreSource` scans.
 *
 * TWO GENERATION CONCEPTS, kept strictly apart (audit finding C):
 * 1. VOLUME generation --- what [currentGeneration] returns. A per-VOLUME
 *    checkpoint MediaStore bumps on any change anywhere on the volume. Its
 *    ONLY job: "is another scan needed?" The scan driver stores it via
 *    `MediaCatalogue.saveScanCheckpoint` and short-circuits the next scan
 *    when it matches. It must NEVER be stamped onto individual items.
 * 2. PER-ROW generation-modified --- each item's own
 *    `MediaStore.MediaColumns.GENERATION_MODIFIED` value (API 30+). This
 *    is the per-item revision input: "did THIS item change?" It lives in
 *    [MediaScanRow.generationToken] / [CatalogueRecord.generationToken]
 *    and is what the reconciler compares. Stamping the volume checkpoint
 *    per row instead would make every item on a volume report as changed
 *    whenever a single item moved --- a full re-index storm.
 */
interface MediaChangeFeed {

    /**
     * The current MediaStore PER-VOLUME generation of [volumeName] (via
     * `MediaStore.getGeneration`), or `null` when the platform has none
     * (API < 30) or the volume is unknown. `null` never means "unchanged"
     * --- it means "you cannot tell without scanning".
     *
     * This is the SCAN CHECKPOINT only --- "has the volume moved since the
     * last scan?" --- NOT a per-item revision input. MediaStore bumps the
     * volume generation on ANY change anywhere on the volume, so stamping
     * it onto individual rows would invalidate every item whenever one
     * item changed. Per-item revision uses each row's own
     * `MediaStore.MediaColumns.GENERATION_MODIFIED` instead (see the
     * interface KDoc, "two generation concepts").
     */
    fun currentGeneration(volumeName: String): String?

    /**
     * Registers [onChanged] to fire when the volume's media may have
     * changed, and returns the UNREGISTER function --- a registration is a
     * resource and the caller (typically a lifecycle-aware scan scheduler)
     * must be able to release it deterministically.
     *
     * The callback carries no payload and no debounce: ContentObserver
     * self-change loops and rapid-fire bursts are the caller's problem to
     * coalesce (one queued re-scan beats three racing ones).
     */
    fun registerObserver(volumeName: String, onChanged: () -> Unit): () -> Unit
}

/**
 * The [MediaChangeFeed] over the real MediaStore --- THIN by ADR-0016: an
 * untested adapter of two platform calls, with zero policy. Every decision
 * that matters (when to scan, what a missed row means, what to tombstone)
 * lives in the tested pure types ([CatalogueReconciler], the catalogue
 * interface) --- this class only transports the platform's signals to them.
 */
class AndroidMediaStoreChangeFeed(
    private val context: Context,
) : MediaChangeFeed {

    override fun currentGeneration(volumeName: String): String? {
        // No generation exists below R --- returning null (rather than
        // throwing or faking a constant) forces the caller down the
        // observer + full-scan path, which is the only correct one there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            // SDK 36: getGeneration returns a Long volume generation number;
            // the string form is the persisted checkpoint token (Room stores
            // it opaquely --- the only operations ever performed on it are
            // equality and non-null-ness).
            MediaStore.getGeneration(context, volumeName).toString()
        } catch (_: IllegalArgumentException) {
            // Unknown volume (not mounted / never existed): "cannot tell",
            // not a crash. The caller treats it like a token miss.
            null
        }
    }

    override fun registerObserver(volumeName: String, onChanged: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(HANDLER) {
            override fun onChange(selfChange: Boolean) {
                // Payload-less by design; see the interface KDoc. Runs on
                // the main looper handler --- cheap signaling only, the
                // scan itself belongs on a worker.
                onChanged()
            }
        }
        observedUrisFor(volumeName).forEach { uri ->
            // notifyForDescendants = true: item-level notifications
            // (insert/update/delete of a single media row) surface on the
            // collection Uri.
            context.contentResolver.registerContentObserver(uri, true, observer)
        }
        return { context.contentResolver.unregisterContentObserver(observer) }
    }

    /**
     * The collection Uris to observe for [volumeName], by API level (see
     * the interface KDoc for the platform rationale):
     * - 29+: the volume-scoped `MediaStore.Files` Uri --- one observer
     *   covers every image/video row of the volume. Passing
     *   `MediaStore.VOLUME_EXTERNAL` aggregates all external volumes.
     * - 26-28: no volume-scoped file Uris exist; watch the classic external
     *   images + video collections, matching `MediaStoreSource`'s scan
     *   surface. [volumeName] is effectively always "external" here ---
     *   single external storage is the only world those releases knew.
     */
    private fun observedUrisFor(volumeName: String): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listOf(MediaStore.Files.getContentUri(volumeName))
        } else {
            listOf(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            )
        }

    private companion object {
        /**
         * One shared handler for every observer this feed registers ---
         * callbacks land on the main looper, deterministically, instead of
         * an arbitrary binder thread each.
         */
        private val HANDLER = Handler(Looper.getMainLooper())
    }
}
