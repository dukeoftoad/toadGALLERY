package com.toadgallery.editor.video

/**
 * ONE crossfade between adjacent clips, attached to the boundary BEFORE an entry (boundary i
 * sits between entries i-1 and i). A dissolve of L ms shrinks the output duration by L (the
 * overlap rule); the arithmetic is pure (ADR-0011) so preview and export read one number.
 */
data class VideoCrossfade(
    /**
     * The boundary this dissolve rides: 1..entries.lastIndex (the span BEFORE entry
     * [boundaryIndex]); 0 is refused (no clip precedes the first).
     */
    val boundaryIndex: Int,
    /** The dissolve length in ms, [MIN_LENGTH_MS]..[MAX_LENGTH_MS]. */
    val lengthMs: Int,
) {
    init {
        require(boundaryIndex >= 1) {
            "boundaryIndex must be >= 1 (the span BEFORE entry i), was $boundaryIndex"
        }
        require(lengthMs in MIN_LENGTH_MS..MAX_LENGTH_MS) {
            "crossfade lengthMs $lengthMs outside [$MIN_LENGTH_MS..$MAX_LENGTH_MS]"
        }
    }

    companion object {
        /** The shortest honest dissolve: two frames at 60 fps. */
        const val MIN_LENGTH_MS: Int = 34

        /** The longest: two seconds; beyond that a dissolve reads as a mis-tap. */
        const val MAX_LENGTH_MS: Int = 2000

        /** Every transition boundary of a composition, in order; null for a one-clip timeline. */
        fun boundariesOf(timeline: ClipTimeline): IntRange? =
            if (timeline.entries.size < 2) null else 1..timeline.entries.lastIndex
    }
}

/** The structured validation outcome (the VideoPlanValidation shape). */
sealed interface VideoCrossfadeValidation {
    data class Valid(
        val transitions: List<VideoCrossfade>,
        val timeline: ClipTimeline,
    ) : VideoCrossfadeValidation
    data class Invalid(val reason: String) : VideoCrossfadeValidation
}

/**
 * The validation gate for a transition list against a validated composition: one transition
 * per boundary, the boundary must exist, and both neighbours must outlive half the dissolve.
 */
object VideoCrossfadeValidator {

    fun validate(
        transitions: List<VideoCrossfade>,
        timeline: ClipTimeline,
    ): VideoCrossfadeValidation {
        val composition = when (val v = ClipTimelineValidator.validate(timeline)) {
            is ClipTimelineValidation.Invalid -> return VideoCrossfadeValidation.Invalid(v.reason)
            is ClipTimelineValidation.Valid -> v.timeline
        }
        if (transitions.isEmpty()) {
            return VideoCrossfadeValidation.Valid(transitions, composition)
        }
        if (composition.entries.size < 2) {
            return VideoCrossfadeValidation.Invalid(
                "a one-clip timeline has no boundaries to crossfade --- insert a second clip first",
            )
        }
        val seen = HashMap<Int, VideoCrossfade>()
        for ((index, transition) in transitions.withIndex()) {
            if (transition.boundaryIndex > composition.entries.lastIndex) {
                return VideoCrossfadeValidation.Invalid(
                    "transition $index rides boundary ${transition.boundaryIndex} but the" +
                        " composition has ${composition.entries.size} clips" +
                        " (boundaries 1..${composition.entries.lastIndex})",
                )
            }
            if (seen.containsKey(transition.boundaryIndex)) {
                return VideoCrossfadeValidation.Invalid(
                    "boundary ${transition.boundaryIndex} carries two transitions ---" +
                        " one seam, one dissolve",
                )
            }
            seen[transition.boundaryIndex] = transition
            val before = composition.entries[transition.boundaryIndex - 1]
            val after = composition.entries[transition.boundaryIndex]
            val beforeOut = ClipTimelineMath.entryOutputDurationMs(before)
            val afterOut = ClipTimelineMath.entryOutputDurationMs(after)
            val length = transition.lengthMs.toDouble()
            // A dissolve must leave both neighbours visible: never eat more than half of either.
            if (length > beforeOut / 2 || length > afterOut / 2) {
                return VideoCrossfadeValidation.Invalid(
                    "the boundary-${transition.boundaryIndex} crossfade" +
                        " (${transition.lengthMs} ms) is longer than half a neighbour" +
                        " (clip ${transition.boundaryIndex - 1}: ${Math.round(beforeOut)} ms," +
                        " clip ${transition.boundaryIndex}: ${Math.round(afterOut)} ms)" +
                        " --- shorten the dissolve",
                )
            }
        }
        return VideoCrossfadeValidation.Valid(transitions, composition)
    }
}

/**
 * Duration math over a VALIDATED transition list: each dissolve shortens the output total
 * by its length — the number the preview lays out against and the export's progress reads.
 */
object VideoCrossfadeMath {

    /** The composition's output duration with every transition applied. */
    fun outputDurationMs(
        timeline: ClipTimeline,
        transitions: List<VideoCrossfade>,
    ): Long {
        var total = ClipTimelineMath.outputDurationMs(timeline)
        for (transition in transitions) {
            total -= transition.lengthMs
        }
        return total.coerceAtLeast(0)
    }

    /**
     * The output offset where entry [entryIndex] starts, transitions applied: the plain
     * walk's offset minus every dissolve on a boundary at or before this entry.
     */
    fun entryStartOffsetMs(
        timeline: ClipTimeline,
        transitions: List<VideoCrossfade>,
        entryIndex: Int,
    ): Long {
        require(entryIndex in timeline.entries.indices) {
            "entryIndex $entryIndex outside the composition (0..${timeline.entries.lastIndex})"
        }
        var offset = ClipTimelineMath.outputOffsetOfSource(timeline, entryIndex, timeline.entries[entryIndex].trim.startMs)
        for (transition in transitions) {
            if (transition.boundaryIndex <= entryIndex) {
                offset -= transition.lengthMs
            }
        }
        return offset.coerceAtLeast(0)
    }
}
