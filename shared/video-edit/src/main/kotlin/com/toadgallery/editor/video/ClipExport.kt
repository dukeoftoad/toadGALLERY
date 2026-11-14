package com.toadgallery.editor.video

/**
 * The multi-clip export request: a VALIDATED [ClipTimeline] plus the session-level treatment
 * the whole composition shares (mute, volume gain, the nine ADR-0012 adjustments, the
 * container), in the exact shape of the v1 [VideoExportRequest] --- URIs are strings, the
 * render lands in a caller-supplied working directory, no Android type crosses. AUDIO IS
 * PER-CLIP, TREATMENT IS WHOLE-TIMELINE: the mute resolves per entry against each clip's
 * audio fact; the volume gain and adjustments apply to the whole composition.
 */
data class ClipExportRequest(
    /** The validated composition; the coordinator refuses anything else. */
    val timeline: ClipTimeline,
    /** The mute request; resolved per entry against each clip's audio fact. */
    val mute: Boolean = false,
    /** The whole-timeline audio gain (bounds: 0 = silent .. 2 = +6 dB; 1f = unchanged). */
    val volume: Float = 1f,
    /** The nine ADR-0012 adjustments applied to every clip (0 = neutral). */
    val adjustments: VideoAdjustParams = VideoAdjustParams(),
    /**
     * The shared look over the whole composition: the SAME bundled `.cube` ids the photo
     * Looks deck ships, with the same intensity grammar. Absent = no look (byte-compatible
     * with the pre-look shape).
     */
    val look: VideoLutLook? = null,
    /** The text overlays, validated against the composition's derived output duration. Absent = none. */
    val textOverlays: List<VideoTextOverlay> = emptyList(),
    /** The music track (import with ducking). Absent = the clips' own audio only. */
    val music: VideoMusicTrack? = null,
    /** The crossfade transitions, one per boundary. Absent = hard cuts. */
    val transitions: List<VideoCrossfade> = emptyList(),
    /** The output container (v1: MP4 only --- the honest encodable set). */
    val container: VideoContainer = VideoContainer.MP4,
    /** A writable directory for the render (the activity passes its cache dir). */
    override val workingDirPath: String,
    /** The render file name inside the working dir. */
    override val renderFileName: String = "video-edit-render.mp4",
) : VideoExportJob

/**
 * ONE renderable item, the unit the media3 runner builds its EditedMediaItem from: which
 * source, which window, at what speed, audio stripped or not. The single-clip plan path
 * resolves to the SAME spec type (one per [VideoEditMath.timelineRuns] run), so the runner
 * has ONE item builder and the two request types cannot drift apart.
 */
data class ClipExportItemSpec(
    /** The source clip's URI (a string; the seam is pure). */
    val sourceUri: String,
    /** The window of the source this item renders (always positive length). */
    val range: TimeRange,
    /** The whole-item speed factor (the validated bounds). */
    val speedFactor: Double,
    /** Whether this item's audio track is stripped (the per-entry mute resolution). */
    val removeAudio: Boolean,
)

/** The structured validation outcome (the VideoPlanValidation shape). */
sealed interface ClipExportValidation {
    data class Valid(val request: ClipExportRequest) : ClipExportValidation
    data class Invalid(val reason: String) : ClipExportValidation
}

/** The single validation gate for composition exports (total and structured). */
object ClipExportValidator {

    fun validate(request: ClipExportRequest): ClipExportValidation {
        // The composition itself (empty / over-cap / escaping trims).
        when (val timeline = ClipTimelineValidator.validate(request.timeline)) {
            is ClipTimelineValidation.Invalid -> {
                return ClipExportValidation.Invalid(timeline.reason)
            }
            is ClipTimelineValidation.Valid -> Unit
        }
        // NaN refused FIRST: every comparison against NaN is false, so it would
        // slip past the range check.
        if (request.volume.isNaN()) {
            return ClipExportValidation.Invalid(
                "volume ${request.volume} is not a number (NaN) --- not a gain the export can apply",
            )
        }
        if (request.volume < VideoEditPlanValidator.MIN_VOLUME ||
            request.volume > VideoEditPlanValidator.MAX_VOLUME
        ) {
            return ClipExportValidation.Invalid(
                "volume ${request.volume} outside " +
                    "[${VideoEditPlanValidator.MIN_VOLUME}..${VideoEditPlanValidator.MAX_VOLUME}]" +
                    " --- the gain the export applies",
            )
        }
        // The colour parameters: the shared projection owns the ranges.
        VideoAdjustProjection.validate(request.adjustments)?.let { reason ->
            return ClipExportValidation.Invalid(reason)
        }
        // The shared look's own grammar (intensity bounds, table shape).
        when (val look = VideoLutLookValidator.validate(request.look)) {
            is VideoLutLookValidation.Invalid -> return ClipExportValidation.Invalid(look.reason)
            is VideoLutLookValidation.Valid -> Unit
        }
        // The text overlays against the composition's derived duration.
        when (
            val overlays = VideoTextOverlayValidator.validate(
                request.textOverlays,
                ClipTimelineMath.outputDurationMs(request.timeline),
            )
        ) {
            is VideoTextOverlayValidation.Invalid ->
                return ClipExportValidation.Invalid(overlays.reason)
            is VideoTextOverlayValidation.Valid -> Unit
        }
        // The music track against the same derived duration.
        when (val music = VideoMusicValidator.validate(request.music, request.timeline)) {
            is VideoMusicValidation.Invalid -> return ClipExportValidation.Invalid(music.reason)
            is VideoMusicValidation.Valid -> Unit
        }
        // The transitions against the composition's boundaries.
        when (
            val transitions = VideoCrossfadeValidator.validate(
                request.transitions,
                request.timeline,
            )
        ) {
            is VideoCrossfadeValidation.Invalid ->
                return ClipExportValidation.Invalid(transitions.reason)
            is VideoCrossfadeValidation.Valid -> Unit
        }
        return ClipExportValidation.Valid(request)
    }
}

/**
 * The pure projection from a request to the runner's item list: one [ClipExportItemSpec] per
 * timeline entry, in order, carrying the entry's trim, speed, and the PER-ENTRY mute
 * resolution. The media3 runner maps specs one-to-one onto EditedMediaItems; nothing about
 * media3 leaks back here (JVM-pinned projection, thin-shell runner).
 */
object ClipExportProjection {

    /**
     * The item specs of a request, in timeline order. `removeAudio` is the per-entry mute
     * resolution: mute && the clip HAS a track (a muted silent clip is a no-op --- nothing
     * to strip).
     */
    fun itemSpecs(request: ClipExportRequest): List<ClipExportItemSpec> =
        request.timeline.entries.map { entry ->
            ClipExportItemSpec(
                sourceUri = entry.sourceUri,
                range = entry.trim,
                speedFactor = entry.speedFactor,
                removeAudio = request.mute && entry.audioTrackPresent,
            )
        }
}
