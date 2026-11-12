package com.toadgallery.editor.video

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

/**
 * The export PREFLIGHT verdict for the resolution-and-HDR tier (r74, the
 * plan doc's P2 rung: "4K/HDR export policies --- surfaced honestly in
 * export preflight"): PURE facts in, an honest verdict out. The model
 * knows nothing about codec queries --- the caller supplies the device
 * facts (max output width/height the encoders report, whether an HDR
 * (10-bit) decode path exists, the API level) and the requested tier,
 * and the verdict says EXACTLY what the export will do, never more.
 *
 * THE POLICY (each rule pinned by [VideoOutputPolicyTest]):
 *  - SDR is always offered (the r42 default --- the tier the v1 editor
 *    shipped); 4K SDR rides only when the encoder profile reports
 *    3840x2160 or better;
 *  - HDR10 requires BOTH the API-33+ gate (the plan doc names it: "HDR
 *    requires API 33+ hardware") AND an HDR decode path; without either
 *    the verdict REFUSES the tier with the honest reason --- it never
 *    silently exports SDR and calls it HDR (the honest-software
 *    principle, applied to a codec fact);
 *  - a refusal is always a plain sentence the export sheet can show
 *    verbatim (the human-readable-diagnosis budget rule).
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
