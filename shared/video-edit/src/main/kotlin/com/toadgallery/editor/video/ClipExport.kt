package com.toadgallery.editor.video

/**
 * The multi-clip export request (r70, Phase 3 --- the realization half of
 * F-77's chain): a VALIDATED [ClipTimeline] plus the session-level
 * treatment the whole composition shares (the mute request, the volume
 * gain, the nine ADR-0012 adjustments, the container), in the exact
 * shape of the v1 [VideoExportRequest] beside it --- the same seam
 * discipline: URIs are strings, the render lands in a caller-supplied
 * working directory, no Android type crosses.
 *
 * AUDIO IS PER-CLIP, TREATMENT IS WHOLE-TIMELINE: each entry carries its
 * own [ClipTimelineEntry.audioTrackPresent] fact, so the mute request
 * resolves PER ENTRY (a muted composition strips the track only on the
 * clips that have one --- the v1 RECORDED_NO_OP honesty, per clip); the
 * volume gain and the adjustments apply to the whole composition (the
 * r52 maintainer-approved bundle shape, promoted one level up).
 */
data class ClipExportRequest(
    /** The validated composition; the coordinator refuses anything else. */
    val timeline: ClipTimeline,
    /** The mute request; resolved per entry against each clip's audio fact. */
    val mute: Boolean = false,
    /** The whole-timeline audio gain (r52 bounds: 0 = silent .. 2 = +6 dB; 1f = unchanged). */
    val volume: Float = 1f,
    /** The nine ADR-0012 adjustments applied to every clip (0 = neutral). */
    val adjustments: VideoAdjustParams = VideoAdjustParams(),
    /** The output container (v1: MP4 only --- the honest encodable set). */
    val container: VideoContainer = VideoContainer.MP4,
    /** A writable directory for the render (the activity passes its cache dir). */
    override val workingDirPath: String,
    /** The render file name inside the working dir. */
    override val renderFileName: String = "video-edit-render.mp4",
) : VideoExportJob

/**
 * ONE renderable item, the unit the media3 runner builds its
 * EditedMediaItem from: which source, which window of it, at what speed,
 * with its audio stripped or not. The v1 plan path resolves to the SAME
 * spec type (one per [VideoEditMath.timelineRuns] run --- same source
 * URI, the run's range and factor, the plan-level strip decision), so the
 * runner has ONE item builder and the two request types cannot drift
 * apart.
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
        // The volume gain: NaN refused FIRST (every comparison against NaN
        // is false --- the v1 validator's defensive rule, verbatim).
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
        return ClipExportValidation.Valid(request)
    }
}

/**
 * The pure projection from a request to the runner's item list (r70): one
 * [ClipExportItemSpec] per timeline entry, in order, carrying the entry's
 * trim, speed, and the PER-ENTRY mute resolution. The media3 runner maps
 * specs one-to-one onto EditedMediaItems; nothing about media3 leaks back
 * here (the ADR-0011 split --- the projection is JVM-pinned, the runner is
 * the thin shell).
 */
object ClipExportProjection {

    /**
     * The item specs of a request, in timeline order. `removeAudio` is the
     * per-entry mute resolution: mute && the clip HAS a track (a muted
     * silent clip is the recorded no-op --- nothing to strip, the export
     * proceeds video-only for that entry).
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
