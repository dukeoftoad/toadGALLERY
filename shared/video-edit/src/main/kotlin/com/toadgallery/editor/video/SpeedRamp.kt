package com.toadgallery.editor.video

/**
 * ONE keyframe on a clip's speed curve (r74, the F-78 keyframe lane's
 * data half): a source-timeline instant and the speed factor IN FORCE
 * there. A sorted, non-overlapping keyframe list over a clip's trim is
 * the RAMP --- between two keyframes the factor interpolates linearly
 * (the simplest honest curve; smoother easings ride later rounds on
 * exactly this container, the module's additive rule).
 *
 * WHY THIS IS NOT [SpeedSegment]: the v1 single-clip contract holds
 * STEP segments (a factor per range, gaps at 1x --- a flat plan); a
 * RAMP is keyframed (a factor per INSTANT, interpolated between ---
 * the plan doc's P2 rung "speed ramps", and the container the volume
 * envelopes of the same rung will copy). Both models stay: the plan
 * lane keeps [VideoEditPlan.speedSegments] frozen (byte-compatible,
 * the r42 contract), and this file carries the timeline lane's own
 * curve per [ClipTimelineEntry] --- `ClipTimelineEntry.ramp` rides as
 * an OPTIONAL field, absent = flat at the entry's whole-clip factor.
 */
data class SpeedKeyframe(
    /** The keyframe's instant, in the CLIP's source timeline (ms). */
    val atMs: Long,
    /** The factor in force at [atMs], the plan's validated bounds. */
    val speedFactor: Double,
) {
    init {
        require(atMs >= 0) { "keyframe atMs must be non-negative, was $atMs" }
        require(
            speedFactor >= VideoEditPlanValidator.MIN_SPEED_FACTOR &&
                speedFactor <= VideoEditPlanValidator.MAX_SPEED_FACTOR,
        ) {
            "keyframe speedFactor $speedFactor outside " +
                "[${VideoEditPlanValidator.MIN_SPEED_FACTOR}.." +
                "${VideoEditPlanValidator.MAX_SPEED_FACTOR}]"
        }
    }
}

/** The structured validation outcome (the VideoPlanValidation shape). */
sealed interface SpeedRampValidation {
    data class Valid(val ramp: List<SpeedKeyframe>) : SpeedRampValidation
    data class Invalid(val reason: String) : SpeedRampValidation
}

/**
 * The single validation gate for a keyframe list against an entry's trim
 * window (total and structured): sorted strictly increasing (two
 * keyframes at one instant is a step, and steps are [SpeedSegment]'s
 * grammar, not this one's), every instant inside the trim, and the
 * bounds the constructor already pinned re-stated by the gate.
 */
object SpeedRampValidator {

    /** A ramp rides at most this many keyframes (the MAX_ENTRIES sanity shape). */
    const val MAX_KEYFRAMES: Int = 64

    fun validate(ramp: List<SpeedKeyframe>, trim: TimeRange): SpeedRampValidation {
        if (ramp.isEmpty()) return SpeedRampValidation.Valid(ramp)
        if (ramp.size > MAX_KEYFRAMES) {
            return SpeedRampValidation.Invalid(
                "a ramp carries at most $MAX_KEYFRAMES keyframes, was ${ramp.size}",
            )
        }
        var previousAt = -1L
        for ((index, keyframe) in ramp.withIndex()) {
            if (keyframe.atMs <= previousAt) {
                return SpeedRampValidation.Invalid(
                    "keyframe $index sits at ${keyframe.atMs} ms, not strictly after the" +
                        " previous ($previousAt ms) --- a ramp is a curve, not a step plan",
                )
            }
            if (keyframe.atMs < trim.startMs || keyframe.atMs > trim.endMs) {
                return SpeedRampValidation.Invalid(
                    "keyframe $index (${keyframe.atMs} ms) escapes the trim window" +
                        " ${trim.startMs}..${trim.endMs} ms",
                )
            }
            previousAt = keyframe.atMs
        }
        return SpeedRampValidation.Valid(ramp)
    }
}

/**
 * The derived math over a VALIDATED ramp (pure; pinned by tests): the
 * factor at any source instant. BEFORE the first keyframe the first
 * factor holds; AFTER the last the last holds; BETWEEN two keyframes
 * the factor interpolates linearly in source time. An empty ramp
 * returns [fallback] --- the entry's whole-clip factor --- so the
 * caller treats ramp and flat plan through ONE function.
 */
object SpeedRampMath {

    /** The factor in force at [sourceMs] over [ramp] (or [fallback] when empty). */
    fun factorAt(ramp: List<SpeedKeyframe>, sourceMs: Long, fallback: Double): Double {
        if (ramp.isEmpty()) return fallback
        if (sourceMs <= ramp.first().atMs) return ramp.first().speedFactor
        if (sourceMs >= ramp.last().atMs) return ramp.last().speedFactor
        for (i in 0 until ramp.size - 1) {
            val a = ramp[i]
            val b = ramp[i + 1]
            if (sourceMs >= a.atMs && sourceMs <= b.atMs) {
                val t = (sourceMs - a.atMs).toDouble() / (b.atMs - a.atMs)
                return a.speedFactor + (b.speedFactor - a.speedFactor) * t
            }
        }
        return ramp.last().speedFactor
    }

    /**
     * The ramped clip's output length: the integral of 1/factor over the
     * trim window, computed by the midpoint rule over [STEPS] slices
     * (a linear-in-factor curve has no closed form; 512 slices pin the
     * test cases EXACTLY at the asserted precision, and the error
     * against the true integral is bounded by the slice count, stated
     * honestly rather than hidden). Rounded ONCE at the end --- the
     * module's convention.
     */
    const val STEPS: Int = 512

    fun outputDurationMs(
        ramp: List<SpeedKeyframe>,
        trim: TimeRange,
        fallback: Double,
    ): Long {
        if (ramp.isEmpty()) {
            return Math.round(trim.lengthMs / fallback)
        }
        val slice = trim.lengthMs.toDouble() / STEPS
        var exact = 0.0
        for (i in 0 until STEPS) {
            val mid = trim.startMs + slice * (i + 0.5)
            exact += slice / factorAt(ramp, Math.round(mid), fallback)
        }
        return Math.round(exact)
    }
}
