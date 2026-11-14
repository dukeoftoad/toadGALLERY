package com.toadgallery.editor.video

/**
 * One clip's slot in a multi-clip composition: the source (a URI string --- the shared module
 * stays pure, it never touches Android), the clip's duration fact, the trim window, and the
 * speed handling. ONE factor per clip (a timeline surface edits per-clip speed, not
 * intra-clip ramps), optionally refined by [ramp] (keyframed factors interpolated linearly;
 * absent/empty = flat at [speedFactor]). Deliberately shares [TimeRange] and the speed-factor
 * bounds with [VideoEditPlan] so the single-clip lane can adopt the composition model without
 * a translation layer. The two lanes carry their OWN bounds: this lane 0.25x..4x
 * ([ClipTimelineValidator]), the single-clip plan frozen at 0.25x..2x.
 */
data class ClipTimelineEntry(
    /** The source clip's URI (opaque to this module; the activity resolves it). */
    val sourceUri: String,
    /** The source clip's duration in milliseconds (a fact, > 0). */
    val durationMs: Long,
    /** The input fact: whether THIS clip has an audio track at all (the retriever reads it per clip --- a composition may mix sounding and silent sources). */
    val audioTrackPresent: Boolean,
    /** The trim window into the source; the clip plays exactly this range. */
    val trim: TimeRange,
    /** The clip's whole-clip speed factor, in [ClipTimelineValidator.MIN_SPEED_FACTOR]..[ClipTimelineValidator.MAX_SPEED_FACTOR]. */
    val speedFactor: Double = 1.0,
    /**
     * The clip's keyframed speed curve: sorted strictly-increasing keyframes inside the trim,
     * interpolated linearly between. Empty = the flat whole-clip factor above; validated by
     * [SpeedRampValidator] through the composition gate.
     */
    val ramp: List<SpeedKeyframe> = emptyList(),
) {
    init {
        require(sourceUri.isNotEmpty()) { "sourceUri must not be empty" }
        require(durationMs > 0) { "durationMs must be positive, was $durationMs" }
        require(
            speedFactor >= ClipTimelineValidator.MIN_SPEED_FACTOR &&
                speedFactor <= ClipTimelineValidator.MAX_SPEED_FACTOR,
        ) {
            "speedFactor $speedFactor outside " +
                "[${ClipTimelineValidator.MIN_SPEED_FACTOR}.." +
                "${ClipTimelineValidator.MAX_SPEED_FACTOR}]"
        }
        // The trim-bounds-vs-duration rule belongs to the validator (structured failure),
        // not the constructor.
    }
}

/**
 * An ordered multi-clip composition: clip 1, then clip 2, and so on. The v1 editor edits a
 * one-entry timeline; the model is deliberately minimal (audio ducking, per-clip looks and
 * text can ride on top additively).
 */
data class ClipTimeline(
    val entries: List<ClipTimelineEntry>,
)

/** The structured validation outcome (the VideoPlanValidation shape). */
sealed interface ClipTimelineValidation {
    data class Valid(val timeline: ClipTimeline) : ClipTimelineValidation
    data class Invalid(val reason: String) : ClipTimelineValidation
}

/** The single validation gate for compositions (total and structured). */
object ClipTimelineValidator {

    /** A sane ceiling so a UI bug can never build an unbounded sequence. */
    const val MAX_ENTRIES: Int = 256

    /**
     * The composition lane's speed bounds: 0.25x..4x. The single-clip plan keeps its own
     * frozen 0.25x..2x ([VideoEditPlanValidator]); each lane states its bounds on its
     * own validator.
     */
    const val MIN_SPEED_FACTOR: Double = 0.25
    const val MAX_SPEED_FACTOR: Double = 4.0

    fun validate(timeline: ClipTimeline): ClipTimelineValidation {
        if (timeline.entries.isEmpty()) {
            return ClipTimelineValidation.Invalid(
                "a composition needs at least one clip --- an empty timeline is not an edit",
            )
        }
        if (timeline.entries.size > MAX_ENTRIES) {
            return ClipTimelineValidation.Invalid(
                "a composition caps at $MAX_ENTRIES clips, was ${timeline.entries.size}",
            )
        }
        for ((index, entry) in timeline.entries.withIndex()) {
            if (entry.trim.startMs < 0) {
                return ClipTimelineValidation.Invalid(
                    "clip $index trim start ${entry.trim.startMs} ms is before the clip start (0 ms)",
                )
            }
            if (entry.trim.endMs > entry.durationMs) {
                return ClipTimelineValidation.Invalid(
                    "clip $index trim end ${entry.trim.endMs} ms is past the clip " +
                        "duration ${entry.durationMs} ms",
                )
            }
            // The keyframed ramp validates against its own trim window (sorted, in-bounds, capped).
            when (val ramp = SpeedRampValidator.validate(entry.ramp, entry.trim)) {
                is SpeedRampValidation.Invalid -> {
                    return ClipTimelineValidation.Invalid("clip $index: ${ramp.reason}")
                }
                is SpeedRampValidation.Valid -> Unit
            }
        }
        return ClipTimelineValidation.Valid(timeline)
    }
}

/**
 * Derived math over a VALIDATED composition (pure; every rule pinned by tests) --- the
 * timeline-surface counterpart of [VideoEditMath]. Walks are piecewise linear per clip: a
 * clip's output length is `trimLength / speedFactor`, and source time advances at
 * `speedFactor` ms per output ms.
 */
object ClipTimelineMath {

    /** A resolved playback position: WHICH clip, and WHERE inside it (source-relative). */
    data class TimelinePlayhead(val entryIndex: Int, val sourceMs: Long)

    /** One clip's output length in ms (exact; the Double the sum then rounds once). */
    fun entryOutputDurationMs(entry: ClipTimelineEntry): Double =
        entry.trim.lengthMs / entry.speedFactor

    /**
     * The composition's total output duration: the sum of the per-clip
     * output lengths, rounded ONCE at the end (the [VideoEditMath]
     * convention --- intermediate Doubles, one honest round).
     */
    fun outputDurationMs(timeline: ClipTimeline): Long {
        var exact = 0.0
        for (entry in timeline.entries) {
            exact += entryOutputDurationMs(entry)
        }
        return Math.round(exact)
    }

    /**
     * The output offset the player must seek to land at `sourceMs` inside clip `entryIndex`
     * (the inverse of [playheadAtOutputOffset], pinned as a round trip by tests). `sourceMs`
     * is clamped into the trim window first: an untrimmed position gets the nearest bound.
     */
    fun outputOffsetOfSource(timeline: ClipTimeline, entryIndex: Int, sourceMs: Long): Long {
        require(entryIndex in timeline.entries.indices) {
            "entryIndex $entryIndex outside the composition (0..${timeline.entries.lastIndex})"
        }
        val clamped = sourceMs.coerceIn(
            timeline.entries[entryIndex].trim.startMs,
            timeline.entries[entryIndex].trim.endMs,
        )
        var before = 0.0
        for (i in 0 until entryIndex) {
            before += entryOutputDurationMs(timeline.entries[i])
        }
        val entry = timeline.entries[entryIndex]
        val inside = (clamped - entry.trim.startMs) / entry.speedFactor
        return Math.round(before + inside)
    }

    /**
     * The source position an output offset resolves to: walk the clips until the offset
     * lands inside one; return WHICH clip and WHERE (source-relative, inside the trim).
     * A boundary offset belongs to the NEXT clip's start; offsets at or past the total
     * clamp to the last clip's trim end.
     */
    fun playheadAtOutputOffset(timeline: ClipTimeline, outputOffsetMs: Long): TimelinePlayhead {
        require(outputOffsetMs >= 0) { "output offset must be non-negative, was $outputOffsetMs" }
        var remaining = outputOffsetMs.toDouble()
        for ((index, entry) in timeline.entries.withIndex()) {
            val length = entryOutputDurationMs(entry)
            if (remaining < length || index == timeline.entries.lastIndex) {
                val source = Math.round(entry.trim.startMs + remaining * entry.speedFactor)
                    .coerceIn(entry.trim.startMs, entry.trim.endMs)
                return TimelinePlayhead(index, source)
            }
            remaining -= length
        }
        // Unreachable --- the lastIndex branch above always returns --- but
        // kept total so the function has no hidden exit.
        val last = timeline.entries.last()
        return TimelinePlayhead(timeline.entries.lastIndex, last.trim.endMs)
    }
}
