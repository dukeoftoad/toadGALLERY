package io.github.dukeoftoad.gallery.edit.video

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The animation-export plan (r42, decision 5): a short GIF/WebP animation
 * over an in/out range with a bounded target width and a bounded frame
 * rate. The derived frame count and the byte-budget preflight are PURE
 * MATH here --- the preflight refuses an over-budget request BEFORE any
 * render starts, with an honest message (the ExportPreflight shape).
 *
 * The caps are ROUND-PINNED (r42) and live here as plan fields' bounds:
 * they are defensible mid-range numbers, not laws of nature --- the
 * contract doc records them and a later round may re-pin them.
 */
data class AnimationExportPlan(
    /** The source clip's duration in milliseconds (the fact). */
    val durationMs: Long,
    /** The animation's in point, `0 <= startMs < endMs`. */
    val startMs: Long,
    /** The animation's out point, `endMs <= durationMs`. */
    val endMs: Long,
    /** The target frame width in pixels (height follows the source aspect). */
    val targetWidthPx: Int,
    /** The requested frame rate; deduplicated against the source rate at derivation. */
    val fps: Int,
    /** The animation format. */
    val format: AnimationFormat,
)

/** The honest encodable set for animations (the r42 encoders realize both). */
enum class AnimationFormat(val mimeType: String, val extension: String) {
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
}

/** The round-pinned r42 caps (documented in docs/phase-10/video-editor.md). */
object AnimationCaps {

    /** Max animation frame width --- 1280 (HD long edge; mid-range decode-safe). */
    const val MAX_TARGET_WIDTH_PX: Int = 1280

    /** Min animation frame width --- 64 (below this a frame is a thumbnail, not an animation). */
    const val MIN_TARGET_WIDTH_PX: Int = 64

    /** The fps bounds --- 2..30 (2 = a crawl, 30 = the smooth ceiling). */
    const val MIN_FPS: Int = 2
    const val MAX_FPS: Int = 30

    /**
     * The output byte budget --- 32 MiB. The preflight's deliberately
     * generous over-estimate must fit inside it or the export refuses
     * before rendering a single frame.
     */
    const val MAX_OUTPUT_BYTES: Long = 32L * 1024 * 1024
}

/** The structured validation outcome for animation plans. */
sealed interface AnimationExportValidation {
    data class Valid(val plan: AnimationExportPlan) : AnimationExportValidation
    data class Invalid(val reason: String) : AnimationExportValidation
}

/** The derived math + validation for animation plans (pure). */
object AnimationExportPlanValidator {

    fun validate(plan: AnimationExportPlan): AnimationExportValidation {
        if (plan.durationMs <= 0) {
            return AnimationExportValidation.Invalid(
                "clip duration must be positive, was ${plan.durationMs} ms --- an unreadable source, not a plan",
            )
        }
        if (plan.startMs < 0) {
            return AnimationExportValidation.Invalid(
                "animation start ${plan.startMs} ms is before the clip start (0 ms)",
            )
        }
        if (plan.endMs <= plan.startMs) {
            return AnimationExportValidation.Invalid(
                "animation range must be positive, was ${plan.startMs}..${plan.endMs} ms",
            )
        }
        if (plan.endMs > plan.durationMs) {
            return AnimationExportValidation.Invalid(
                "animation end ${plan.endMs} ms is past the clip duration ${plan.durationMs} ms",
            )
        }
        if (plan.targetWidthPx < AnimationCaps.MIN_TARGET_WIDTH_PX ||
            plan.targetWidthPx > AnimationCaps.MAX_TARGET_WIDTH_PX
        ) {
            return AnimationExportValidation.Invalid(
                "animation width must be in " +
                    "[${AnimationCaps.MIN_TARGET_WIDTH_PX}..${AnimationCaps.MAX_TARGET_WIDTH_PX}] px, " +
                    "was ${plan.targetWidthPx} px",
            )
        }
        if (plan.fps < AnimationCaps.MIN_FPS || plan.fps > AnimationCaps.MAX_FPS) {
            return AnimationExportValidation.Invalid(
                "animation fps must be in [${AnimationCaps.MIN_FPS}..${AnimationCaps.MAX_FPS}], " +
                    "was ${plan.fps}",
            )
        }
        return AnimationExportValidation.Valid(plan)
    }

    /**
     * The deduplicated frame rate: the requested fps capped at the SOURCE
     * frame rate when it is known and positive --- frames beyond the
     * source's own rate would be pure duplicates, so the derivation never
     * asks for them. An unknown source rate (null or non-positive) rides
     * the requested fps.
     */
    fun effectiveFps(requestedFps: Int, sourceFrameRateFps: Double?): Double {
        if (sourceFrameRateFps == null || sourceFrameRateFps <= 0.0) {
            return requestedFps.toDouble()
        }
        return min(requestedFps.toDouble(), sourceFrameRateFps)
    }

    /**
     * The derived frame count: `range x effectiveFps`, rounded, minimum 1.
     * Pure math, pinned by tests.
     */
    fun frameCount(plan: AnimationExportPlan, sourceFrameRateFps: Double?): Int {
        val rangeSeconds = (plan.endMs - plan.startMs) / 1000.0
        return (rangeSeconds * effectiveFps(plan.fps, sourceFrameRateFps))
            .roundToInt()
            .coerceAtLeast(1)
    }

    /**
     * The retriever timestamps for the derived frames: `start + i / fps`,
     * clamped into the range. The exporter walks exactly this list.
     */
    fun frameTimestampsMs(plan: AnimationExportPlan, sourceFrameRateFps: Double?): List<Long> {
        val count = frameCount(plan, sourceFrameRateFps)
        val fps = effectiveFps(plan.fps, sourceFrameRateFps)
        return List(count) { i ->
            (plan.startMs + (i * 1000.0 / fps).toLong()).coerceAtMost(plan.endMs)
        }
    }
}

/**
 * The animation preflight (the ExportPreflight shape): the byte model is a
 * deliberately generous OVER-estimate --- GIF stores at most one byte per
 * pixel indexed (model: width x height x 1), lossless WebP photographic
 * content lands around two bytes per pixel (model: width x height x 2) ---
 * and the request must fit the round-pinned budget BEFORE any render.
 */
object AnimationPreflight {

    /** The preflight outcome: proceed (with the derived facts) or refuse honestly. */
    sealed interface Outcome {
        data class Proceed(val frameCount: Int, val estimatedBytes: Long) : Outcome
        data class Refusal(val reason: String) : Outcome
    }

    /**
     * The generous per-format byte model per frame. Requires the frame
     * HEIGHT (derived by the caller from the source aspect at the target
     * width --- the plan deliberately carries width only).
     */
    fun estimateBytes(frameCount: Int, widthPx: Int, heightPx: Int, format: AnimationFormat): Long {
        val perFrame = when (format) {
            AnimationFormat.GIF -> widthPx.toLong() * heightPx * 1L
            AnimationFormat.WEBP -> widthPx.toLong() * heightPx * 2L
        }
        return perFrame * frameCount
    }

    /**
     * The full preflight: validation, the platform's WebP capability (an
     * input fact --- the caller supplies it; the lossless WebP encoder the
     * muxer rides exists only on API 30+), then the byte budget.
     */
    fun preflight(
        plan: AnimationExportPlan,
        sourceFrameRateFps: Double?,
        frameHeightPx: Int,
        webpLosslessSupported: Boolean,
    ): Outcome {
        when (val validation = AnimationExportPlanValidator.validate(plan)) {
            is AnimationExportValidation.Invalid -> return Outcome.Refusal(validation.reason)
            is AnimationExportValidation.Valid -> Unit
        }
        if (plan.format == AnimationFormat.WEBP && !webpLosslessSupported) {
            return Outcome.Refusal(
                "animated WebP export needs Android 11 or newer --- pick GIF on this device",
            )
        }
        if (frameHeightPx <= 0) {
            return Outcome.Refusal(
                "cannot derive the animation frame height from this clip --- an unreadable source",
            )
        }
        val count = AnimationExportPlanValidator.frameCount(plan, sourceFrameRateFps)
        val bytes = estimateBytes(count, plan.targetWidthPx, frameHeightPx, plan.format)
        if (bytes > AnimationCaps.MAX_OUTPUT_BYTES) {
            return Outcome.Refusal(
                "the estimated animation is ${bytes / (1024L * 1024)} MiB, over the " +
                    "${AnimationCaps.MAX_OUTPUT_BYTES / (1024L * 1024)} MiB budget --- shorten the " +
                    "range, lower the frame rate or the width",
            )
        }
        return Outcome.Proceed(count, bytes)
    }
}
