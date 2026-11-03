package io.github.dukeoftoad.gallery.edit

import io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy

/**
 * The save-copy export plan (pure; the activity executes it against
 * MediaStore). Encodes the non-destructive policy as data so the JVM tests
 * pin every edge:
 *
 *  - the export is an INSERT of a NEW entry --- the source URI is never
 *    opened for write, never updated, never deleted (contract section 4.1;
 *    there is no replace-original path at all);
 *  - the insert carries IS_PENDING on API 29+ while the bytes are written,
 *    and a FAILED write must CANCEL the pending row --- no partial
 *    MediaStore entry survives (spec test matrix);
 *  - the display name derives from the source name, the extension follows
 *    the chosen export format;
 *  - quality applies to the lossy formats (JPEG, HEIC where honored);
 *    PNG is lossless and ignores it.
 */
object ExportPlan {

    /** The export sub-directory under Pictures (API 29+ RELATIVE_PATH). */
    const val RELATIVE_DIR: String = "Pictures/toadGALLERY Edit"

    /** The r29 default (still the UI default): JPEG at quality 95. */
    val DEFAULT_FORMAT: ExportFormat = ExportFormat.JPEG
    const val DEFAULT_QUALITY: Int = 95

    /** The pending-state flag values mirrored from MediaStore for JVM tests. */
    const val IS_PENDING_TRUE: Int = 1
    const val IS_PENDING_FALSE: Int = 0

    /**
     * Derives the saved-copy display name from the source display name and
     * the chosen format: extension replaced, an "-edit" suffix added,
     * path separators and control characters stripped. `null` source falls
     * back to a stable generic name.
     */
    fun displayNameFor(sourceDisplayName: String?, format: ExportFormat): String {
        val base = (sourceDisplayName ?: "image")
            .substringAfterLast('/')
            .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "")
            .trim()
            .ifEmpty { "image" }
            .substringBeforeLast('.')
        return "$base-edit.${format.extension}"
    }

    /**
     * The initial INSERT values. `api29Plus` toggles the modern columns
     * (RELATIVE_PATH + IS_PENDING); pre-Q devices insert with the display
     * name only (the legacy write grant is requested at export time).
     */
    fun insertValues(
        displayName: String,
        format: ExportFormat,
        api29Plus: Boolean,
    ): Map<String, Any> {
        val values = linkedMapOf<String, Any>(
            "_display_name" to displayName,
            "mime_type" to format.mimeType,
        )
        if (api29Plus) {
            values["relative_path"] = RELATIVE_DIR
            values["is_pending"] = IS_PENDING_TRUE
        }
        return values
    }

    /** The finalizer values after a successful write (clear IS_PENDING). */
    fun finalizeValues(api29Plus: Boolean): Map<String, Any> =
        if (api29Plus) {
            mapOf("is_pending" to IS_PENDING_FALSE)
        } else {
            emptyMap()
        }

    /**
     * Failure-recovery decision (pure): a failed export must cancel the
     * pending row when one exists (API 29+), and must NOT attempt any
     * cleanup on pre-Q (an insert there is visible immediately; deleting
     * another app-visible row would need permissions the editor refuses
     * to hold --- the row is the user's to remove).
     */
    fun failureRecovery(api29Plus: Boolean): Recovery = if (api29Plus) Recovery.CANCEL_PENDING_ROW else Recovery.LEAVE_ROW

    enum class Recovery { CANCEL_PENDING_ROW, LEAVE_ROW }

    /**
     * The result payload decision: the output URI goes back to the gallery
     * only on success, and the echoed metadata policy is the applied one.
     */
    fun resultPolicy(policy: MetadataPolicy): String = policy.wireName

    /**
     * The honest fallback when a HEIC encode is refused (no device
     * encoder): fall back to JPEG at the same quality --- the export
     * succeeds with the closest supported format rather than failing.
     */
    fun heicFallback(): ExportFormat = ExportFormat.JPEG
}
