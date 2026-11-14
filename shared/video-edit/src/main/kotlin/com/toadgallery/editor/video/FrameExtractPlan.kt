package com.toadgallery.editor.video

/**
 * The frame-extraction plan: ONE user-chosen timestamp within the clip's duration,
 * exported as a new JPEG or PNG MediaStore entry. Bounds are validated, never clamped
 * --- the honest refusal shape.
 */
data class FrameExtractPlan(
    /** The source clip's duration in milliseconds (the fact). */
    val durationMs: Long,
    /** The chosen timestamp, `0 <= timestampMs <= durationMs` (both ends are real frames). */
    val timestampMs: Long,
    /** The still-image output format. */
    val format: FrameFormat,
)

/** The honest encodable set for a single extracted frame. */
enum class FrameFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
}

/** The structured validation outcome for frame plans. */
sealed interface FrameExtractValidation {
    data class Valid(val plan: FrameExtractPlan) : FrameExtractValidation
    data class Invalid(val reason: String) : FrameExtractValidation
}

/** The single validation gate for frame-extraction plans. */
object FrameExtractPlanValidator {

    fun validate(plan: FrameExtractPlan): FrameExtractValidation {
        if (plan.durationMs <= 0) {
            return FrameExtractValidation.Invalid(
                "clip duration must be positive, was ${plan.durationMs} ms --- an unreadable source, not a plan",
            )
        }
        if (plan.timestampMs < 0) {
            return FrameExtractValidation.Invalid(
                "frame timestamp ${plan.timestampMs} ms is before the clip start (0 ms)",
            )
        }
        if (plan.timestampMs > plan.durationMs) {
            return FrameExtractValidation.Invalid(
                "frame timestamp ${plan.timestampMs} ms is past the clip duration ${plan.durationMs} ms",
            )
        }
        return FrameExtractValidation.Valid(plan)
    }
}
