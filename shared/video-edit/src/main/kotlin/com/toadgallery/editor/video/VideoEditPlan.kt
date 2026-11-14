package com.toadgallery.editor.video

/**
 * The validated trim range: `0 <= startMs < endMs <= durationMs`, both bounds
 * inclusive to the clip. Zero/negative length and out-of-bounds bounds are structured
 * failures with honest messages, never a silent clamp (the UI bounds its own inputs).
 */
data class TimeRange(val startMs: Long, val endMs: Long) {
    init {
        // Range-local invariant: positive length only. Clip-relative bounds (start >= 0,
        // end <= duration) belong to [VideoEditPlanValidator] as structured failures.
        require(endMs > startMs) { "endMs must be strictly after startMs, was $startMs..$endMs" }
    }

    /** The range's length in milliseconds (always positive by construction). */
    val lengthMs: Long get() = endMs - startMs
}

/**
 * One speed segment: a factor in [0.25, 2.0] applied over [startMs, endMs). Segments
 * live INSIDE the trim range, must not overlap, and source time must strictly increase
 * across the output timeline (pinned by the validator and the derived math).
 */
data class SpeedSegment(val startMs: Long, val endMs: Long, val speedFactor: Double) {
    init {
        require(startMs >= 0) { "segment startMs must be non-negative, was $startMs" }
        require(endMs > startMs) { "segment endMs must be strictly after startMs, was $startMs..$endMs" }
    }
}

/** The v1 output container: MP4, what the media3 Transformer writes by default. */
enum class VideoContainer(val mimeType: String, val extension: String) {
    MP4("video/mp4", "mp4"),
}

/** What the export does with the clip's audio. */
enum class MuteDecision {
    /** No mute requested: the audio track is copied through. */
    KEEP_AUDIO,

    /** Mute on a clip that HAS an audio track: the export strips it. */
    STRIP_AUDIO,

    /**
     * Mute on a clip with NO audio track: a recorded no-op --- video-only export, and
     * the UI states "no audio track" instead of showing a dead toggle.
     */
    RECORDED_NO_OP,
}

/** The structured validation outcome (the RequestValidation shape). */
sealed interface VideoPlanValidation {
    data class Valid(val plan: VideoEditPlan) : VideoPlanValidation
    data class Invalid(val reason: String) : VideoPlanValidation
}

/**
 * The whole lightweight-edit session as ONE pure value: trim range + speed plan +
 * audio fact + mute request + volume gain + colour adjustments + output container.
 * The activity executes a VALIDATED plan (JVM-pinned). The facts ([durationMs],
 * [audioTrackPresent]) are caller-supplied inputs --- the plan never guesses them.
 */
data class VideoEditPlan(
    /** The source clip's duration in milliseconds (a fact, > 0). */
    val durationMs: Long,
    /** The validated trim range; the export covers exactly this range. */
    val trim: TimeRange,
    /**
     * The speed plan: sorted, non-overlapping segments over the trim range; gaps ride
     * at 1x. The v1 UI exposes whole-clip or one region, but the model stays general.
     */
    val speedSegments: List<SpeedSegment> = emptyList(),
    /** The input fact: whether the source clip has an audio track at all. */
    val audioTrackPresent: Boolean,
    /** The mute request; on a no-audio clip it is a recorded no-op. */
    val mute: Boolean = false,
    /**
     * The audio gain request: 1f = unchanged, 0f = silent, 2f = the +6 dB boost ceiling.
     * MUTE WINS over volume at the realization (a muted export strips the audio track,
     * gain moot); on a clip with no audio track both requests are recorded no-ops.
     */
    val volume: Float = 1f,
    /** The nine ADR-0012 adjustment parameters (0 = neutral everywhere). */
    val adjustments: VideoAdjustParams = VideoAdjustParams(),
    /** The output container (v1: MP4 only --- the honest encodable set). */
    val container: VideoContainer = VideoContainer.MP4,
)

/** The single validation gate for edit plans (total and structured). */
object VideoEditPlanValidator {

    /** The speed factor bounds (the contract's 0.25x..2x slow-mo range). */
    const val MIN_SPEED_FACTOR: Double = 0.25
    const val MAX_SPEED_FACTOR: Double = 2.0

    /** The volume gain bounds: 0 = silent .. 2 = +6 dB; 1f is the unchanged identity. */
    const val MIN_VOLUME: Float = 0.0f
    const val MAX_VOLUME: Float = 2.0f

    fun validate(plan: VideoEditPlan): VideoPlanValidation {
        // The duration fact.
        if (plan.durationMs <= 0) {
            return VideoPlanValidation.Invalid(
                "clip duration must be positive, was ${plan.durationMs} ms --- an unreadable source, not a plan",
            )
        }

        // The trim range: in bounds, positive length, inside the clip.
        if (plan.trim.startMs < 0) {
            return VideoPlanValidation.Invalid(
                "trim start ${plan.trim.startMs} ms is before the clip start (0 ms)",
            )
        }
        if (plan.trim.endMs > plan.durationMs) {
            return VideoPlanValidation.Invalid(
                "trim end ${plan.trim.endMs} ms is past the clip duration ${plan.durationMs} ms",
            )
        }

        // The speed segments: bounds, sorted, non-overlapping, inside the trim.
        var previousEnd = plan.trim.startMs
        for ((index, segment) in plan.speedSegments.withIndex()) {
            if (segment.speedFactor < MIN_SPEED_FACTOR || segment.speedFactor > MAX_SPEED_FACTOR) {
                return VideoPlanValidation.Invalid(
                    "speed segment $index factor ${segment.speedFactor} outside " +
                        "[$MIN_SPEED_FACTOR..$MAX_SPEED_FACTOR]",
                )
            }
            if (segment.startMs < plan.trim.startMs || segment.endMs > plan.trim.endMs) {
                return VideoPlanValidation.Invalid(
                    "speed segment $index (${segment.startMs}..${segment.endMs} ms) escapes the " +
                        "trim range ${plan.trim.startMs}..${plan.trim.endMs} ms",
                )
            }
            if (segment.startMs < previousEnd) {
                return VideoPlanValidation.Invalid(
                    "speed segment $index (${segment.startMs}..${segment.endMs} ms) overlaps or " +
                        "precedes the previous segment (expected sorted, non-overlapping; previous " +
                        "end $previousEnd ms)",
                )
            }
            previousEnd = segment.endMs
        }

        // NaN is refused FIRST: comparisons against NaN are false, so the bounds check
        // alone would let it slip through as valid.
        if (plan.volume.isNaN()) {
            return VideoPlanValidation.Invalid(
                "volume ${plan.volume} is not a number (NaN) --- not a gain the export can apply",
            )
        }
        if (plan.volume < MIN_VOLUME || plan.volume > MAX_VOLUME) {
            return VideoPlanValidation.Invalid(
                "volume ${plan.volume} outside [$MIN_VOLUME..$MAX_VOLUME]" +
                    " --- the gain the export applies",
            )
        }

        // The colour parameters: the shared projection owns the ranges.
        VideoAdjustProjection.validate(plan.adjustments)?.let { reason ->
            return VideoPlanValidation.Invalid(reason)
        }

        return VideoPlanValidation.Valid(plan)
    }
}

/**
 * The derived math over a VALIDATED plan (pure; every rule pinned by tests).
 */
object VideoEditMath {

    /**
     * The timeline the export realizes: the trim range expanded into ordered
     * (start, end, factor) runs --- explicit segments plus 1x gap runs between and
     * around them. Realized here once so preview and export cannot drift.
     */
    fun timelineRuns(plan: VideoEditPlan): List<SpeedSegment> {
        val runs = mutableListOf<SpeedSegment>()
        var cursor = plan.trim.startMs
        for (segment in plan.speedSegments) {
            if (segment.startMs > cursor) {
                runs.add(SpeedSegment(cursor, segment.startMs, 1.0))
            }
            runs.add(segment)
            cursor = segment.endMs
        }
        if (cursor < plan.trim.endMs) {
            runs.add(SpeedSegment(cursor, plan.trim.endMs, 1.0))
        }
        return runs
    }

    /**
     * The derived output duration: sum over every run of `runLength / factor`
     * (ms, rounded once at the end).
     */
    fun outputDurationMs(plan: VideoEditPlan): Long {
        var exact = 0.0
        for (run in timelineRuns(plan)) {
            exact += (run.endMs - run.startMs) / run.speedFactor
        }
        return Math.round(exact)
    }

    /**
     * The source-time mapping: given an output-timeline offset (0-based), the source
     * offset the player/encoder should read. Piecewise linear over the runs; source
     * time strictly increases across the whole output timeline --- no folding backwards.
     */
    fun sourceTimeAtOutputOffset(plan: VideoEditPlan, outputOffsetMs: Long): Long {
        require(outputOffsetMs >= 0) { "output offset must be non-negative, was $outputOffsetMs" }
        var remainingOutput = outputOffsetMs.toDouble()
        for (run in timelineRuns(plan)) {
            val runOutputLength = (run.endMs - run.startMs) / run.speedFactor
            if (remainingOutput <= runOutputLength || runOutputLength <= 0.0) {
                return Math.round(run.startMs + remainingOutput * run.speedFactor)
                    .coerceAtMost(run.endMs)
            }
            remainingOutput -= runOutputLength
        }
        return plan.trim.endMs
    }
}

/** The derived mute decision over the audio fact. */
fun VideoEditPlan.muteDecision(): MuteDecision = when {
    !mute -> MuteDecision.KEEP_AUDIO
    audioTrackPresent -> MuteDecision.STRIP_AUDIO
    else -> MuteDecision.RECORDED_NO_OP
}
