package io.github.dukeoftoad.gallery.edit

import io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy

/**
 * The save-copy export plan (pure; the activity executes it against
 * MediaStore). Encodes the non-destructive policy as data so the JVM tests
 * pin every edge:
 *
 *  - the export is an INSERT of a NEW entry --- the source URI is never
 *    opened for write, never updated, never deleted (contract section 4.1;
 *    the prototype has no replace-original path at all);
 *  - the insert carries IS_PENDING on API 29+ while the bytes are written,
 *    and a FAILED write must CANCEL the pending row --- no partial MediaStore
 *    entry survives (spec test matrix: "no partial files in MediaStore");
 *  - the display name derives from the source name, JPEG extension (the
 *    prototype always exports JPEG q95).
 */
object ExportPlan {

    /** The export sub-directory under Pictures (API 29+ RELATIVE_PATH). */
    const val RELATIVE_DIR: String = "Pictures/toadGALLERY Edit"

    /** The prototype's fixed export format. */
    const val EXPORT_MIME: String = "image/jpeg"
    const val JPEG_QUALITY: Int = 95

    /** The pending-state flag values mirrored from MediaStore for JVM tests. */
    const val IS_PENDING_TRUE: Int = 1
    const val IS_PENDING_FALSE: Int = 0

    /**
     * Derives the saved-copy display name from the source display name:
     * extension replaced with .jpg and an "-edit" suffix, stripped of
     * path separators and control characters. `null` source (provider gave
     * no name) falls back to a stable generic name.
     */
    fun displayNameFor(sourceDisplayName: String?): String {
        val base = (sourceDisplayName ?: "image")
            .substringAfterLast('/')
            .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "")
            .trim()
            .ifEmpty { "image" }
            .substringBeforeLast('.')
        return "$base-edit.jpg"
    }

    /**
     * The initial INSERT values. `api29Plus` toggles the modern columns
     * (RELATIVE_PATH + IS_PENDING); pre-Q devices insert with the display
     * name only (the legacy write grant is requested at export time).
     */
    fun insertValues(
        displayName: String,
        api29Plus: Boolean,
    ): Map<String, Any> {
        val values = linkedMapOf<String, Any>(
            "_display_name" to displayName,
            "mime_type" to EXPORT_MIME,
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
     * cleanup on pre-Q (an insert there is visible immediately; the row is
     * the user's to delete and a failed pre-Q stream leaves a 0-byte file
     * the gallery's trash flow handles --- deleting another app-visible row
     * from the editor would need permissions the editor refuses to hold).
     */
    fun failureRecovery(api29Plus: Boolean): Recovery = if (api29Plus) Recovery.CANCEL_PENDING_ROW else Recovery.LEAVE_ROW

    enum class Recovery { CANCEL_PENDING_ROW, LEAVE_ROW }

    /**
     * The result payload decision: the output URI goes back to the gallery
     * only on success, and the echoed metadata policy is the applied one.
     */
    fun resultPolicy(policy: MetadataPolicy): String = policy.wireName
}
