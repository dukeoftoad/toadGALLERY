package com.toadgallery.editor.video

/**
 * ONE text overlay on the video timeline: the photo TextLayer contract reshaped for video —
 * same tolerance/placement grammar (centre + size as percent of the SHORT edge) plus the
 * output-timeline window and fade legs. Pure (ADR-0011): no Android types; JVM-pinned.
 */
data class VideoTextOverlay(
    /** Stable identity (the undo/redo and the inspector re-target by it). */
    val id: String,
    /** The caption text, trimmed at the model cap. */
    val text: String,
    /** The font as a BundledFonts id (one typography system shared with the photo text layers). */
    val fontId: String,
    /** Font size as a percent of the output's short edge. */
    val sizePct: Int = 100,
    /** The fill colour, packed ARGB. */
    val colorArgb: Long = 0xFFFFFFFFL,
    /** Opacity 0..100 (100 = opaque). */
    val opacity: Int = 100,
    /** Rotation, degrees clockwise. */
    val rotationDeg: Int = 0,
    /** The centre as fractions of the output frame. */
    val centerX: Double = 0.5,
    val centerY: Double = 0.5,
    /**
     * Where on the OUTPUT timeline the caption shows; bounded by the composition's derived
     * duration and checked by the validator (the constructor never sees the composition).
     */
    val startMs: Long = 0,
    val endMs: Long = 0,
    /**
     * The fade legs in ms, each 0..[MAX_FADE_MS]; the two legs together must fit the span
     * (validated, not clamped).
     */
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0,
) {
    init {
        require(id.isNotEmpty()) { "overlay id must not be empty" }
        require(text.isNotBlank()) { "overlay text must not be blank" }
        require(sizePct in MIN_SIZE_PCT..MAX_SIZE_PCT) {
            "sizePct $sizePct outside [$MIN_SIZE_PCT..$MAX_SIZE_PCT]"
        }
        require(opacity in 0..100) { "opacity $opacity outside [0..100]" }
        require(rotationDeg in -180..180) { "rotationDeg $rotationDeg outside [-180..180]" }
        require(centerX >= 0.0 && centerX <= 1.0) { "centerX $centerX outside [0..1]" }
        require(centerY >= 0.0 && centerY <= 1.0) { "centerY $centerY outside [0..1]" }
        require(endMs > startMs) { "endMs must be strictly after startMs, was $startMs..$endMs" }
        require(fadeInMs in 0..MAX_FADE_MS) { "fadeInMs $fadeInMs outside [0..$MAX_FADE_MS]" }
        require(fadeOutMs in 0..MAX_FADE_MS) { "fadeOutMs $fadeOutMs outside [0..$MAX_FADE_MS]" }
    }

    companion object {
        /** The model cap, shared with the photo text layer. */
        const val MAX_TEXT_LENGTH: Int = 200

        /** The size grammar bounds. */
        const val MIN_SIZE_PCT: Int = 20
        const val MAX_SIZE_PCT: Int = 400

        /** Fade-leg ceiling: one second; longer fades are keyframed-opacity territory. */
        const val MAX_FADE_MS: Int = 1000

        /** The per-composition ceiling (the MAX_ENTRIES sanity shape). */
        const val MAX_OVERLAYS: Int = 32

        /**
         * The tolerance-trimmed constructor: blank id rejected, text trimmed to the cap,
         * numerics coerced to bounds. The inspector writes through this.
         */
        fun of(
            id: String,
            text: String,
            fontId: String,
            startMs: Long,
            endMs: Long,
            sizePct: Int = 100,
            colorArgb: Long = 0xFFFFFFFFL,
            opacity: Int = 100,
            rotationDeg: Int = 0,
            centerX: Double = 0.5,
            centerY: Double = 0.5,
            fadeInMs: Int = 0,
            fadeOutMs: Int = 0,
        ): VideoTextOverlay = VideoTextOverlay(
            id = id,
            text = text.trim().take(MAX_TEXT_LENGTH),
            fontId = fontId,
            sizePct = sizePct.coerceIn(MIN_SIZE_PCT, MAX_SIZE_PCT),
            colorArgb = colorArgb,
            opacity = opacity.coerceIn(0, 100),
            rotationDeg = rotationDeg.coerceIn(-180, 180),
            centerX = centerX.coerceIn(0.0, 1.0),
            centerY = centerY.coerceIn(0.0, 1.0),
            startMs = startMs,
            endMs = if (endMs > startMs) endMs else startMs + 1,
            fadeInMs = fadeInMs.coerceIn(0, MAX_FADE_MS),
            fadeOutMs = fadeOutMs.coerceIn(0, MAX_FADE_MS),
        )
    }
}

/** The structured validation outcome (the VideoPlanValidation shape). */
sealed interface VideoTextOverlayValidation {
    data class Valid(val overlays: List<VideoTextOverlay>) : VideoTextOverlayValidation
    data class Invalid(val reason: String) : VideoTextOverlayValidation
}

/**
 * The validation gate for an overlay list against the composition's derived output
 * duration. Overlapping captions are allowed (cross-fades); only window escapes and
 * fade-leg overlaps fail.
 */
object VideoTextOverlayValidator {

    fun validate(
        overlays: List<VideoTextOverlay>,
        outputDurationMs: Long,
    ): VideoTextOverlayValidation {
        if (overlays.isEmpty()) {
            return VideoTextOverlayValidation.Valid(overlays)
        }
        if (overlays.size > VideoTextOverlay.MAX_OVERLAYS) {
            return VideoTextOverlayValidation.Invalid(
                "a composition carries at most ${VideoTextOverlay.MAX_OVERLAYS} text overlays," +
                    " was ${overlays.size}",
            )
        }
        if (outputDurationMs <= 0) {
            return VideoTextOverlayValidation.Invalid(
                "output duration must be positive, was $outputDurationMs ms ---" +
                    " validate the composition first, then its overlays",
            )
        }
        val ids = HashSet<String>(overlays.size)
        for ((index, overlay) in overlays.withIndex()) {
            if (!ids.add(overlay.id)) {
                return VideoTextOverlayValidation.Invalid(
                    "text overlay id '${overlay.id}' appears twice --- the undo/redo and the" +
                        " inspector re-target by identity",
                )
            }
            if (overlay.startMs < 0) {
                return VideoTextOverlayValidation.Invalid(
                    "text overlay $index ('${overlay.id}') starts ${overlay.startMs} ms before" +
                        " the timeline start (0 ms)",
                )
            }
            if (overlay.endMs > outputDurationMs) {
                return VideoTextOverlayValidation.Invalid(
                    "text overlay $index ('${overlay.id}') ends ${overlay.endMs} ms past the" +
                        " timeline's ${outputDurationMs} ms",
                )
            }
            val span = overlay.endMs - overlay.startMs
            if (overlay.fadeInMs + overlay.fadeOutMs > span) {
                return VideoTextOverlayValidation.Invalid(
                    "text overlay $index ('${overlay.id}') fade legs (${overlay.fadeInMs} +" +
                        " ${overlay.fadeOutMs} ms) overlap inside its ${span} ms span ---" +
                        " shorten the legs or widen the span",
                )
            }
        }
        return VideoTextOverlayValidation.Valid(overlays)
    }
}

/**
 * The opacity envelope at a timeline instant (pure, test-pinned): flat through the span,
 * linear ramp on both legs; outside the span the overlay is not showing.
 */
object VideoTextOverlayMath {

    /**
     * Opacity 0..[VideoTextOverlay.opacity] at [outputOffsetMs], linear on both legs;
     * null outside the span (not showing — distinct from an in-span fade).
     */
    fun opacityAt(overlay: VideoTextOverlay, outputOffsetMs: Long): Int? {
        if (outputOffsetMs < overlay.startMs || outputOffsetMs >= overlay.endMs) return null
        val into = outputOffsetMs - overlay.startMs
        val span = overlay.endMs - overlay.startMs
        val fromEnd = span - into
        // Each leg ramps linearly from 0, clamping at the overlay's own opacity.
        val fadeInLevel: Int = if (overlay.fadeInMs > 0) {
            (into * overlay.opacity.toLong() / overlay.fadeInMs).toInt()
                .coerceAtMost(overlay.opacity)
        } else {
            overlay.opacity
        }
        val fadeOutLevel: Int = if (overlay.fadeOutMs > 0) {
            (fromEnd * overlay.opacity.toLong() / overlay.fadeOutMs).toInt()
                .coerceAtMost(overlay.opacity)
        } else {
            overlay.opacity
        }
        val level: Int = if (fadeInLevel < fadeOutLevel) fadeInLevel else fadeOutLevel
        return if (level > 0) level else 0
    }
}
