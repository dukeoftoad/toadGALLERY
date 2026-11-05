package io.github.dukeoftoad.gallery.edit.video

/**
 * The non-destructive MediaStore policy as data (r42, decision 9 --- the
 * ExportPlan precedent, video-shaped). Every export --- trimmed video,
 * muted export, slow-mo export, extracted frame, animation --- is an INSERT
 * of a NEW entry:
 *
 *  - the source URI is read-only by construction: never opened for write,
 *    never updated, never deleted (there is NO replace-original path at
 *    all --- this object exposes no such operation);
 *  - the insert carries IS_PENDING on API 29+ while the bytes land, and a
 *    FAILED write must CANCEL the pending row --- no partial MediaStore
 *    entry survives;
 *  - the display name derives from the source name with an "-edit" suffix
 *    and the extension follows the chosen output kind;
 *  - video outputs land under Movies, still outputs (frames, animations)
 *    under Pictures --- the volumes MediaStore owns for each kind.
 */
object VideoOutputPlan {

    /** The video output sub-directory (API 29+ RELATIVE_PATH). */
    const val VIDEO_RELATIVE_DIR: String = "Movies/toadGALLERY Edit"

    /** The still output sub-directory (frames and animations). */
    const val IMAGE_RELATIVE_DIR: String = "Pictures/toadGALLERY Edit"

    /** The pending-state flag values mirrored from MediaStore for JVM tests. */
    const val IS_PENDING_TRUE: Int = 1
    const val IS_PENDING_FALSE: Int = 0

    /**
     * Derives the saved-copy display name from the source display name and
     * the output kind: extension replaced, an "-edit" suffix added, path
     * separators and control characters stripped. `null` source falls back
     * to a stable generic name.
     */
    fun displayNameFor(sourceDisplayName: String?, kind: VideoOutputKind): String {
        val base = (sourceDisplayName ?: "video")
            .substringAfterLast('/')
            .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "")
            .trim()
            .ifEmpty { "video" }
            .substringBeforeLast('.')
        return "$base-edit.${kind.extension}"
    }

    /**
     * The initial INSERT values. `api29Plus` toggles the modern columns
     * (RELATIVE_PATH + IS_PENDING); pre-Q devices insert with the display
     * name only (the legacy write grant is requested at export time).
     */
    fun insertValues(
        displayName: String,
        kind: VideoOutputKind,
        api29Plus: Boolean,
    ): Map<String, Any> {
        val values = linkedMapOf<String, Any>(
            "_display_name" to displayName,
            "mime_type" to kind.mimeType,
        )
        if (api29Plus) {
            values["relative_path"] = relativeDirFor(kind)
            values["is_pending"] = IS_PENDING_TRUE
        }
        return values
    }

    /** Which MediaStore collection the kind inserts into (video vs images). */
    fun relativeDirFor(kind: VideoOutputKind): String = when (kind.store) {
        VideoOutputStore.VIDEO -> VIDEO_RELATIVE_DIR
        VideoOutputStore.IMAGE -> IMAGE_RELATIVE_DIR
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
     * another app-visible row would need permissions the editor refuses to
     * hold --- the row is the user's to remove).
     */
    fun failureRecovery(api29Plus: Boolean): Recovery =
        if (api29Plus) Recovery.CANCEL_PENDING_ROW else Recovery.LEAVE_ROW

    enum class Recovery { CANCEL_PENDING_ROW, LEAVE_ROW }
}

/** Which MediaStore collection an output kind belongs to. */
enum class VideoOutputStore { VIDEO, IMAGE }

/** Every r42 output kind with its MediaStore facts. */
enum class VideoOutputKind(
    val store: VideoOutputStore,
    val mimeType: String,
    val extension: String,
) {
    TRIMMED_VIDEO(VideoOutputStore.VIDEO, "video/mp4", "mp4"),
    FRAME_JPEG(VideoOutputStore.IMAGE, "image/jpeg", "jpg"),
    FRAME_PNG(VideoOutputStore.IMAGE, "image/png", "png"),
    ANIMATION_GIF(VideoOutputStore.IMAGE, "image/gif", "gif"),
    ANIMATION_WEBP(VideoOutputStore.IMAGE, "image/webp", "webp"),
}
