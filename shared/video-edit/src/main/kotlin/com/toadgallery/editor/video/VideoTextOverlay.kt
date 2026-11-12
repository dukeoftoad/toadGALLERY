package com.toadgallery.editor.video

/**
 * ONE text overlay on the video timeline (r74, the F-78 text lane): the
 * r34 photo TextLayers contract reshaped for video --- the same
 * tolerance rules (blank text never rides, numerics coerce, unknown ids
 * round-trip verbatim), the same normalised placement grammar
 * (centre + size as a percent of the SHORT edge), plus the video-own
 * facts: WHERE on the output timeline the caption lives and how it
 * fades in and out.
 *
 * WHY PURE (ADR-0011): every rule below is arithmetic over the
 * composition's derived output duration ([ClipTimelineMath]); no
 * Android type crosses this file, so the whole overlay model is
 * JVM-pinned by [VideoTextOverlayTest] exactly like the plan layer
 * beside it. The REALIZATION (a media3 text overlay built from the
 * projected spans) is the activity's thin shell; nothing about media3
 * leaks back here.
 *
 * THE LADDER: the plan doc's P0 rung ("text overlays") names this model
 * as data; the surface (the caption inspector in the video Deck) rides
 * the surface round that follows, the same facts-before-surfaces order
 * every container in this module landed (r42's plan, r70's composition).
 */
data class VideoTextOverlay(
    /** Stable identity (the undo/redo and the inspector re-target by it). */
    val id: String,
    /** The caption text, trimmed at the model cap (the r34 text cap). */
    val text: String,
    /**
     * The font, as a BundledFonts id (the photo text layer's own
     * registry --- ONE typography system, photo and video; the "one
     * language" promise, realised as a shared key space).
     */
    val fontId: String,
    /** Font size as a percent of the output's short edge (the r34 grammar). */
    val sizePct: Int = 100,
    /** The fill colour, packed ARGB (the r34 grammar). */
    val colorArgb: Long = 0xFFFFFFFFL,
    /** Opacity 0..100 (100 = opaque). */
    val opacity: Int = 100,
    /** Rotation, degrees clockwise. */
    val rotationDeg: Int = 0,
    /** The centre as fractions of the output frame (the r34 grammar). */
    val centerX: Double = 0.5,
    val centerY: Double = 0.5,
    /**
     * WHERE on the OUTPUT timeline the caption shows: the composition's
     * derived duration bounds the window; the validator checks it (the
     * trim-vs-duration rule's shape --- the constructor never sees the
     * composition, so window rules belong to the validator, exactly like
     * [ClipTimelineEntry]'s trim).
     */
    val startMs: Long = 0,
    val endMs: Long = 0,
    /**
     * The fade-in/out legs, milliseconds, each in
     * [MIN_FADE_MS]..[MAX_FADE_MS] and never longer than the span's
     * half (two legs sharing one span must not overlap --- the honest
     * bound, validated not clamped).
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
        /** The r34 model cap, verbatim (the photo text layer's own bound). */
        const val MAX_TEXT_LENGTH: Int = 200

        /** The r34 size grammar bounds, verbatim. */
        const val MIN_SIZE_PCT: Int = 20
        const val MAX_SIZE_PCT: Int = 400

        /**
         * The fade-leg ceiling: one second is the longest honest fade a
         * mobile caption wants; longer legs are a different feature
         * (keyframed opacity --- the P2 tier's territory).
         */
        const val MAX_FADE_MS: Int = 1000

        /** The per-composition ceiling (the MAX_ENTRIES sanity shape). */
        const val MAX_OVERLAYS: Int = 32

        /**
         * The tolerance-trimmed constructor (the r34 read rules): a blank
         * id is rejected (an overlay without identity cannot be
         * re-targeted), the TEXT trims to the cap, numerics coerce to
         * their bounds. The inspector writes through this; the codec
         * round-trips the validated model verbatim.
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
 * The single validation gate for an overlay list against a composition's
 * derived output duration (total and structured --- the
 * [ClipTimelineValidator] shape). The composition bounds every window;
 * overlapping captions are ALLOWED (two captions may cross-fade --- a
 * timeline is not a photo); only the window escapes and the fade-leg
 * overlaps are failures.
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
 * The derived math over a VALIDATED overlay (pure; pinned by tests): the
 * opacity envelope at a timeline instant. Flat 100 through the span; the
 * legs ramp linearly; outside the span the overlay is not showing at all
 * (0, and the realization skips the draw entirely).
 */
object VideoTextOverlayMath {

    /**
     * The overlay's opacity 0..[VideoTextOverlay.opacity] at
     * [outputOffsetMs], linear on both legs. `null` when the offset is
     * outside the span (the honest "not showing" --- not a forced 0 the
     * caller might mistake for an in-span fade).
     */
    fun opacityAt(overlay: VideoTextOverlay, outputOffsetMs: Long): Int? {
        if (outputOffsetMs < overlay.startMs || outputOffsetMs >= overlay.endMs) return null
        val into = outputOffsetMs - overlay.startMs
        val span = overlay.endMs - overlay.startMs
        val fromEnd = span - into
        // Each leg ramps linearly from 0 and CLAMPS at the overlay's own
        // opacity --- a leg is over once it reaches the peak, never past.
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
