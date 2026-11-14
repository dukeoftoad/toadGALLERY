package com.toadgallery.editor.video

/**
 * The non-destructive MediaStore policy as data: every export is an INSERT of a NEW
 * entry. The source URI is never opened for write, never updated, never deleted (no
 * replace-original path exists here). The insert carries IS_PENDING on API 29+ while
 * the bytes land; a FAILED write must CANCEL the pending row. Display name derives
 * from the source name + "-edit"; video lands under Movies, stills under Pictures.
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
     * Derives the display name from the source name + the output kind: extension
     * replaced, "-edit" suffix, path separators and control chars stripped. A null
     * source falls back to a stable generic name.
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
     * The initial INSERT values. `api29Plus` toggles the modern columns (RELATIVE_PATH +
     * IS_PENDING); pre-Q inserts with the display name only (legacy write grant at export).
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
     * Failure-recovery decision (pure): a failed export cancels the pending row when one
     * exists (API 29+); pre-Q does NO cleanup (an insert there is visible immediately;
     * deleting another app-visible row needs permissions the editor refuses to hold).
     */
    fun failureRecovery(api29Plus: Boolean): Recovery =
        if (api29Plus) Recovery.CANCEL_PENDING_ROW else Recovery.LEAVE_ROW

    enum class Recovery { CANCEL_PENDING_ROW, LEAVE_ROW }
}

/**
 * The export PREFLIGHT verdict for the resolution-and-HDR tier: PURE device facts in,
 * an honest verdict out (the caller supplies encoder limits, HDR decode path, API level;
 * the model knows nothing about codec queries).
 *
 * The policy: SDR is always offered; 4K SDR rides only when the encoders report
 * 3840x2160+; HDR10 requires BOTH the API-33+ gate AND an HDR decode path, otherwise
 * the tier is REFUSED with the honest reason (never silently exported as SDR). A
 * refusal is a plain sentence the export sheet can show verbatim.
 */
object VideoExportPreflight {

    /** The 4K short edge pair (3840x2160), the tier the policy names. */
    const val UHD_WIDTH: Int = 3840
    const val UHD_HEIGHT: Int = 2160

    /** The API level where 10-bit HDR output paths exist on the platform. */
    const val HDR_MIN_API: Int = 33

    /** The requested output tier (v1 shipped SDR only; the ladder adds the rest). */
    enum class Tier { SDR, UHD_SDR, HDR10 }

    /** The device facts the preflight weighs (a caller-supplied record, never guessed). */
    data class DeviceFacts(
        /** The API level of this device. */
        val apiLevel: Int,
        /** The widest video the device's encoders report (0 = unknown --- treated as refusing). */
        val maxEncoderWidth: Int,
        /** The tallest video the encoders report (0 = unknown). */
        val maxEncoderHeight: Int,
        /** Whether the device exposes a 10-bit HDR decode path. */
        val hdrDecodePath: Boolean,
    )

    /** The verdict: offered (and at what honest ceiling) or refused (with the reason). */
    sealed interface Verdict {
        /** The tier is offered; the export runs at exactly [tier]. */
        data class Offered(val tier: Tier) : Verdict

        /** Refused, with the user-readable reason shown in the export sheet. */
        data class Refused(val reason: String) : Verdict
    }

    /** The preflight: one honest answer per (tier, device). */
    fun check(tier: Tier, facts: DeviceFacts): Verdict = when (tier) {
        Tier.SDR -> Verdict.Offered(Tier.SDR)
        Tier.UHD_SDR -> {
            val widthOk = facts.maxEncoderWidth >= UHD_WIDTH && facts.maxEncoderHeight >= UHD_HEIGHT
            if (widthOk) {
                Verdict.Offered(Tier.UHD_SDR)
            } else {
                Verdict.Refused(
                    "this device's encoders top out at ${facts.maxEncoderWidth}x" +
                        "${facts.maxEncoderHeight} --- 4K (${UHD_WIDTH}x${UHD_HEIGHT}) needs a" +
                        " UHD-capable encoder",
                )
            }
        }
        Tier.HDR10 -> {
            if (facts.apiLevel < HDR_MIN_API) {
                Verdict.Refused(
                    "HDR10 export needs Android ${HDR_MIN_API}+ --- this device reports API" +
                        " ${facts.apiLevel}",
                )
            } else if (!facts.hdrDecodePath) {
                Verdict.Refused(
                    "this device exposes no 10-bit HDR decode path --- the export would be" +
                        " SDR wearing an HDR badge, and the editor does not ship that",
                )
            } else {
                Verdict.Offered(Tier.HDR10)
            }
        }
    }
}

/** Which MediaStore collection an output kind belongs to. */
enum class VideoOutputStore { VIDEO, IMAGE }

/** Every output kind with its MediaStore facts. */
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
