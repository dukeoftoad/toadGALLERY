package com.toadgallery.editor.video

/**
 * The music track over a composition (r74, the F-78 audio lane): ONE
 * imported audio asset laid against the video's output timeline, with a
 * placement window, a gain, and the DUCKING envelope the plan doc's P1
 * rung names ("music import with ducking").
 *
 * WHY DUCKING IS DATA, NOT A REALIZATION TRICK: the duck is a gain
 * envelope computed from FACTS the model already holds --- where the
 * video's own audio is loud --- so the whole envelope is pure
 * arithmetic here, JVM-pinned by [VideoMusicTrackTest], and the
 * realization (the media3 audio processor chain) applies a curve it was
 * HANDED, never one it derives. Preview and export agree by
 * construction, the module's one-model-two-realizations rule.
 *
 * THE v1 CONTRACT, STATED HONESTLY: the model carries the track and the
 * envelope; the r74 REALIZATION ships the mix as the composition's
 * single audio bed (the imported music replaces per-clip audio at the
 * mux when requested --- the [replaceClipAudio] fact) because media3's
 * Composition audio mixing of N+1 sources is the F-78 surface round's
 * own follow-up. The model is general; the realization says exactly
 * what it does.
 */
data class VideoMusicTrack(
    /** The imported audio's URI (opaque; the activity resolves it). */
    val sourceUri: String,
    /** The audio's duration in milliseconds (a retriever fact, > 0). */
    val durationMs: Long,
    /** The output-timeline window the music plays over. */
    val startMs: Long = 0,
    val endMs: Long = 0,
    /**
     * The music's own bed gain, r52 bounds (0 = silent .. 2 = +6 dB; 1f
     * unchanged) --- the same bounds the plan's volume rides, one audio
     * grammar.
     */
    val gain: Float = 1f,
    /**
     * True when the music REPLACES the clips' own audio (the v1 mux
     * shape); false mixes over them (the F-78 surface round realizes
     * the true mix --- stated above, honestly).
     */
    val replaceClipAudio: Boolean = false,
    /**
     * The ducking request: how far the music dips (a GAIN FACTOR
     * 0..1, 1 = no dip) while the video's own audio is present. The
     * envelope's SHAPE lives in [VideoMusicDucking].
     */
    val duckFactor: Float = 1f,
) {
    init {
        require(sourceUri.isNotEmpty()) { "music sourceUri must not be empty" }
        require(durationMs > 0) { "music durationMs must be positive, was $durationMs" }
        require(endMs > startMs) { "music window endMs must be after startMs, was $startMs..$endMs" }
        require(!gain.isNaN()) { "music gain must not be NaN" }
        require(gain >= 0f && gain <= 2f) { "music gain $gain outside [0..2] (the r52 bounds)" }
        require(duckFactor >= 0f && duckFactor <= 1f) {
            "duckFactor $duckFactor outside [0..1]"
        }
    }

    /** The window's length on the output timeline (positive by construction). */
    val windowLengthMs: Long get() = endMs - startMs

    companion object {
        /** A music window may not exceed the audio asset's own length. */
        fun windowEscapesAsset(track: VideoMusicTrack): Boolean =
            track.windowLengthMs > track.durationMs

        /** True when the track plays the asset from its start (no lead-in skip). */
        fun playsFromAssetStart(track: VideoMusicTrack): Boolean = track.startMs == 0L
    }
}

/**
 * The ducking envelope's shape (pure): WHERE the music dips --- the
 * honest boundary between "the video's audio is here" and "the music
 * leads". The r74 contract derives the duck segments from the
 * composition's per-clip audio facts: the music ducks across every
 * output span whose clip carries a track (the [duckSpans] input ---
 * computed by the caller from [ClipTimeline], one line, pinned by
 * tests), and ramps between ducked and lead levels over a fixed
 * [RAMP_MS] so the envelope never clicks.
 */
object VideoMusicDucking {

    /**
     * The cross-fade leg between the ducked and lead levels: 150 ms ---
     * short enough to feel like a mix decision, long enough that the
     * gain step never clicks.
     */
    const val RAMP_MS: Long = 150

    /**
     * The envelope as piecewise-linear gain points over the music's own
     * window: (offsetIntoWindowMs, gain). Starts at the lead level,
     * ducks to `duckFactor` across the duck spans, ramps between levels
     * over [RAMP_MS], and the LAST point lands the final level exactly
     * at the window's end. The realization drives its gain curve from
     * these points verbatim; it computes nothing.
     *
     * Overlapping duck spans are folded (a union, not a sum --- two
     * overlapping loud spans are still one dip); spans outside the
     * window are clipped to it.
     */
    fun envelope(
        track: VideoMusicTrack,
        duckSpans: List<TimeRange>,
    ): List<Pair<Long, Float>> {
        val lead = track.gain
        val ducked = track.gain * track.duckFactor
        val length = track.windowLengthMs

        // Fold the spans into a sorted union, clipped to the window.
        val spans = duckSpans
            .map { span ->
                val start = (span.startMs - track.startMs).coerceIn(0, length)
                val end = (span.endMs - track.startMs).coerceIn(0, length)
                start to end
            }
            .filter { (s, e) -> e > s }
            .sortedBy { it.first }
        val folded = mutableListOf<Pair<Long, Long>>()
        for ((s, e) in spans) {
            val last = folded.lastOrNull()
            if (last != null && s <= last.second) {
                folded[folded.size - 1] = last.first to maxOf(last.second, e)
            } else {
                folded.add(s to e)
            }
        }

        val points = mutableListOf<Pair<Long, Float>>()
        points.add(0L to lead)
        for ((s, e) in folded) {
            // Ramp down INTO the duck: the leg starts RAMP_MS before the
            // span and lands the ducked level exactly at its start.
            val rampStart = (s - RAMP_MS).coerceAtLeast(0)
            if (rampStart > 0) points.add(rampStart to lead)
            points.add(s to ducked)
            // HOLD the ducked level through the span, then ramp up OUT:
            // the leg starts at the span's end and lands the lead level
            // RAMP_MS after it. A span that ends AT the window's end has
            // nowhere to ramp to --- the envelope stays ducked to the
            // last sample (no spurious rise past the music).
            points.add(e.coerceAtMost(length) to ducked)
            if (e + RAMP_MS <= length) {
                points.add(e + RAMP_MS to lead)
            }
        }
        // Dedupe/sort by offset (tight spans can produce out-of-order
        // ramps --- the last write at an offset wins, the envelope's
        // forward-only convention) and land the final level exactly at
        // the window's end.
        return points
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (offset, gains) -> offset to gains.last() }
            .let { leveled ->
                if (leveled.isNotEmpty() && leveled.last().first < length) {
                    leveled + (length to leveled.last().second)
                } else {
                    leveled
                }
            }
    }

    /**
     * The duck spans of a composition, derived from the per-clip audio
     * facts: the OUTPUT span of every entry that carries a track
     * ([ClipTimelineEntry.audioTrackPresent], r70's fact). Pure, one
     * walk, pinned by tests --- the caller hands the result to
     * [envelope] and the model stays the single source of the mix truth.
     */
    fun duckSpansOf(timeline: ClipTimeline): List<TimeRange> {
        val spans = mutableListOf<TimeRange>()
        var offset = 0.0
        for (entry in timeline.entries) {
            val length = ClipTimelineMath.entryOutputDurationMs(entry)
            if (entry.audioTrackPresent) {
                val start = Math.round(offset).coerceAtLeast(0)
                val end = Math.round(offset + length)
                if (end > start) spans.add(TimeRange(start, end))
            }
            offset += length
        }
        return spans
    }
}

/** The structured validation outcome (the VideoPlanValidation shape). */
sealed interface VideoMusicValidation {
    data class Valid(val track: VideoMusicTrack?, val timeline: ClipTimeline) : VideoMusicValidation
    data class Invalid(val reason: String) : VideoMusicValidation
}

/**
 * The single validation gate for a music track against a validated
 * composition (total and structured): the window rides INSIDE the
 * composition's derived output duration, the asset must be long enough
 * for the window, and the gain facts carry the r52 bounds.
 */
object VideoMusicValidator {

    fun validate(
        track: VideoMusicTrack?,
        timeline: ClipTimeline,
    ): VideoMusicValidation {
        val composition = when (val v = ClipTimelineValidator.validate(timeline)) {
            is ClipTimelineValidation.Invalid -> return VideoMusicValidation.Invalid(v.reason)
            is ClipTimelineValidation.Valid -> v.timeline
        }
        if (track == null) return VideoMusicValidation.Valid(null, composition)
        val total = ClipTimelineMath.outputDurationMs(composition)
        if (track.startMs < 0) {
            return VideoMusicValidation.Invalid(
                "music window starts ${track.startMs} ms before the timeline start (0 ms)",
            )
        }
        if (track.endMs > total) {
            return VideoMusicValidation.Invalid(
                "music window ends ${track.endMs} ms past the timeline's $total ms",
            )
        }
        if (VideoMusicTrack.windowEscapesAsset(track)) {
            return VideoMusicValidation.Invalid(
                "music window (${track.windowLengthMs} ms) is longer than the asset" +
                    " (${track.durationMs} ms)",
            )
        }
        return VideoMusicValidation.Valid(track, composition)
    }
}
