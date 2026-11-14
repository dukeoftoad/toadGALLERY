package com.toadgallery.editor.video

/**
 * ONE crossfade transition between adjacent clips (r74, the F-78
 * transition lane): the plan doc's P1 rung ("crossfade transitions")
 * as pure data --- WHICH boundary, how LONG the dissolve runs, and the
 * validation that keeps it honest.
 *
 * THE MODEL, STATED ONCE: a transition is attached to the BOUNDARY
 * BEFORE an entry (boundary i sits between entry i-1 and entry i; a
 * one-clip timeline has no boundaries, a two-clip timeline has exactly
 * one --- boundary 1). A crossfade of L ms means the composition's
 * output duration SHRINKS by L (the tail of clip i-1 overlaps the head
 * of clip i during the dissolve), exactly the behaviour every timeline
 * editor teaches. That duration arithmetic lives here, pure, so the
 * surface, the preview and the export all read ONE number.
 *
 * WHY PURE (ADR-0011): boundary indices, duration deltas and the
 * side-clamp rules are arithmetic; the media3 realization (a
 * CrossfadeComposition effects build) is the surface round's shell.
 */
data class VideoCrossfade(
    /**
     * The boundary this dissolve rides: 1..entries.lastIndex (the span
     * BEFORE entry [boundaryIndex]). 0 is refused --- there is no clip
     * before the first; the validator says so.
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

        /**
         * The longest: two seconds --- beyond that a mobile dissolve
         * reads as a mis-tap, and the entry-length rule below would be
         * doing all the work anyway.
         */
        const val MAX_LENGTH_MS: Int = 2000

        /**
         * Every transition boundary of a composition, in order --- the
         * surface's picker list and the validator's own bounds check.
         */
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
 * The single validation gate for a transition list against a validated
 * composition (total and structured). The rules, each pinned by tests:
 * one transition per boundary (two dissolves on one seam is a render
 * ambiguity, not a choice), the boundary exists, and BOTH neighbouring
 * entries are long enough that the dissolve never eats a whole clip ---
 * the honest bound, refused with the neighbour named.
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
            // A dissolve must leave BOTH neighbours visible: never eat
            // more than half of either side, and never the whole clip.
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
 * The derived duration math over a VALIDATED transition list (pure):
 * the composition's output duration WITH the transitions applied ---
 * each dissolve shortens the total by its length (the overlap rule
 * stated on [VideoCrossfade]). This is the ONE number the preview
 * timeline lays out against and the export's progress denominator
 * reads; pinned by tests against hand-composed cases.
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
     * The OUTPUT offset where entry [entryIndex] starts, transitions
     * applied: the plain walk's offset minus every dissolve that rides
     * a boundary AT or BEFORE this entry's own. Pinned as consistent
     * with [outputDurationMs] by tests (the walk plus the entries minus
     * the eaten overlaps reproduces the total).
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
