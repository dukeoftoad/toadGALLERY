package com.toadgallery.editor

import com.toadgallery.editor.contract.MetadataPolicy

/**
 * The save-copy export plan (pure; the activity executes it against MediaStore). The
 * export is an INSERT of a NEW entry (the source is never written, updated or deleted);
 * IS_PENDING on API 29+, cancelled on a failed write. Name derives from the source with
 * an "-edit" suffix; quality applies to lossy formats only.
 */
object ExportPlan {

    /** The export sub-directory under Pictures (API 29+ RELATIVE_PATH). */
    const val RELATIVE_DIR: String = "Pictures/toadGALLERY Edit"

    /** The default: JPEG at quality 95. */
    val DEFAULT_FORMAT: ExportFormat = ExportFormat.JPEG
    const val DEFAULT_QUALITY: Int = 95

    /** The pending-state flag values mirrored from MediaStore for JVM tests. */
    const val IS_PENDING_TRUE: Int = 1
    const val IS_PENDING_FALSE: Int = 0

    /**
     * Display name from the source name + format: extension replaced, "-edit" suffix,
     * separators/control chars stripped; null source falls back to a generic name.
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
     * The initial INSERT values: modern columns (RELATIVE_PATH + IS_PENDING) on API 29+;
     * pre-Q inserts the display name only.
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
     * Failure-recovery decision: cancel the pending row on API 29+; no cleanup on pre-Q
     * (an insert there is visible immediately and is the user's row to remove).
     */
    fun failureRecovery(api29Plus: Boolean): Recovery = if (api29Plus) Recovery.CANCEL_PENDING_ROW else Recovery.LEAVE_ROW

    enum class Recovery { CANCEL_PENDING_ROW, LEAVE_ROW }

    /** The echoed metadata policy is the applied one; the URI goes back only on success. */
    fun resultPolicy(policy: MetadataPolicy): String = policy.wireName
}
