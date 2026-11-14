package com.toadgallery.editor.video

/**
 * ONE imported audio asset laid against the video's output timeline: a placement
 * window, a gain, and a ducking envelope. Ducking is pure data (JVM-pinned by
 * [VideoMusicTrackTest]); the realization applies a curve it was handed, so preview
 * and export agree by construction. v1's mix replaces per-clip audio at the mux when
 * [replaceClipAudio] is set (a true N+1 mix is a follow-up); the model stays general.
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
     * The music's own bed gain: 0 = silent .. 2 = +6 dB, 1f unchanged (the same
     * bounds the plan's volume rides --- one audio grammar).
     */
    val gain: Float = 1f,
    /**
     * True when the music REPLACES the clips' own audio (the v1 mux shape); false
     * mixes over them (a true mix is a realization follow-up).
     */
    val replaceClipAudio: Boolean = false,
    /**
     * The ducking request: how far the music dips (a GAIN FACTOR 0..1, 1 = no dip)
     * while the video's own audio is present. The envelope's shape lives in [VideoMusicDucking].
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
 * The ducking envelope's shape (pure): WHERE the music dips. The music ducks across
 * every output span whose clip carries a track (the [envelope] input, computed by the
 * caller from [ClipTimeline]) and ramps between ducked and lead levels over a fixed
 * [RAMP_MS] so the gain never clicks.
 */
object VideoMusicDucking {

    /**
     * The cross-fade leg between ducked and lead levels: short enough to feel like a
     * mix decision, long enough that the gain step never clicks.
     */
    const val RAMP_MS: Long = 150

    /**
     * The envelope as piecewise-linear gain points over the music's window:
     * (offsetIntoWindowMs, gain). Starts at lead, ducks to `duckFactor` across the
     * duck spans, ramps over [RAMP_MS], and the last point lands the final level exactly
     * at the window's end; the realization drives its gain curve from these points
     * verbatim. Overlapping duck spans fold (a union, not a sum); out-of-window spans clip.
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
            // Ramp down into the duck: leg starts RAMP_MS before the span, lands ducked at its start.
            val rampStart = (s - RAMP_MS).coerceAtLeast(0)
            if (rampStart > 0) points.add(rampStart to lead)
            points.add(s to ducked)
            // Hold the ducked level through the span, then ramp up out (lead RAMP_MS after
            // the end). A span ending AT the window's end stays ducked to the last sample.
            points.add(e.coerceAtMost(length) to ducked)
            if (e + RAMP_MS <= length) {
                points.add(e + RAMP_MS to lead)
            }
        }
        // Dedupe/sort by offset (tight spans can produce out-of-order ramps; the last
        // write at an offset wins), then land the final level exactly at the window's end.
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
     * The duck spans of a composition: the OUTPUT span of every entry that carries a
     * track ([ClipTimelineEntry.audioTrackPresent]). Pure, one walk; the caller hands
     * the result to [envelope].
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
 * The single validation gate for a music track against a validated composition: the
 * window rides inside the output duration; the asset must cover the window.
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
