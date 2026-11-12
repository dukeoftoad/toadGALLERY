package com.toadgallery.editor.video

/**
 * One clip's slot in a multi-clip composition (r69, the Phase 3 timeline
 * foundation): the source (a URI string --- the shared module stays pure,
 * it never touches Android), the clip's duration fact, the trim window
 * into that source, and the clip's WHOLE-clip speed factor.
 *
 * The v1 video editor's speed plan ([VideoEditPlan.speedSegments]) stays
 * the single-clip contract; a [ClipTimeline] entry carries ONE factor per
 * clip because that is what a timeline surface edits (per-clip speed, not
 * intra-clip ramps --- the Phase 4 keyframe round's territory). The two
 * models deliberately share [TimeRange] and the speed-factor bounds so the
 * single-clip lane can adopt the composition model without a translation
 * layer.
 *
 * r70 grew the entry's FACT set: [audioTrackPresent] joins durationMs as
 * an input fact the caller supplies (the retriever reads it per clip) ---
 * the same additive growth [VideoEditPlan] took at r42, facts before
 * surfaces.
 */
data class ClipTimelineEntry(
    /** The source clip's URI (opaque to this module; the activity resolves it). */
    val sourceUri: String,
    /** The source clip's duration in milliseconds (a fact, > 0). */
    val durationMs: Long,
    /** The input fact: whether THIS clip has an audio track at all (r70; the retriever reads it per clip --- a composition may mix sounding and silent sources). */
    val audioTrackPresent: Boolean,
    /** The trim window into the source; the clip plays exactly this range. */
    val trim: TimeRange,
    /** The clip's whole-clip speed factor, in [VideoEditPlanValidator.MIN_SPEED_FACTOR]..[VideoEditPlanValidator.MAX_SPEED_FACTOR]. */
    val speedFactor: Double = 1.0,
) {
    init {
        require(sourceUri.isNotEmpty()) { "sourceUri must not be empty" }
        require(durationMs > 0) { "durationMs must be positive, was $durationMs" }
        require(
            speedFactor >= VideoEditPlanValidator.MIN_SPEED_FACTOR &&
                speedFactor <= VideoEditPlanValidator.MAX_SPEED_FACTOR,
        ) {
            "speedFactor $speedFactor outside " +
                "[${VideoEditPlanValidator.MIN_SPEED_FACTOR}.." +
                "${VideoEditPlanValidator.MAX_SPEED_FACTOR}]"
        }
        // The trim-bounds-vs-duration rule needs the duration fact, so it
        // belongs to the validator (the structured-failure shape), not the
        // constructor --- exactly the TimeRange precedent.
    }
}

/**
 * An ordered multi-clip composition: clip 1, then clip 2, and so on. The
 * v1 editor edits a one-entry timeline; the Phase 3 surfaces (the clip
 * picker, the reorder rail) grow the list. The model is deliberately
 * minimal --- audio ducking, per-clip looks and text ride later rounds on
 * top of it, the same additive way [VideoEditPlan] grew its containers.
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
        }
        return ClipTimelineValidation.Valid(timeline)
    }
}

/**
 * The derived math over a VALIDATED composition (pure; every rule pinned
 * by tests) --- the timeline-surface counterpart of [VideoEditMath]. All
 * walks are piecewise linear per clip: a clip's output length is
 * `trimLength / speedFactor`, and inside a clip source time advances at
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
     * The output offset where a given source position sits: how far into
     * the OUTPUT timeline the player must seek to land at
     * `sourceMs` inside clip `entryIndex`. The inverse of
     * [playheadAtOutputOffset] --- pinned as a round trip by tests.
     * `sourceMs` is clamped into the clip's trim window first (a caller
     * asking about an untrimmed position gets the nearest bound --- the
     * honest answer, not a failure).
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
     * The source position an output offset resolves to: walk the clips,
     * consuming each clip's output length, until the offset lands inside
     * one; return WHICH clip and WHERE (source-relative, inside the trim).
     * A boundary offset belongs to the NEXT clip's start (a walk that
     * consumes clip i exactly arrives at clip i+1's trim start --- the
     * convention the tests pin). Offsets at or past the total clamp to the
     * last clip's trim end; the caller never folds time backwards.
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
