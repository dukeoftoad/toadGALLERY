package com.toadgallery.editor.video

/**
 * The export runner seam (r42, decision 7): the job in, typed progress and
 * result out --- the ModelDownloader-seam precedent, so the JVM tests drive
 * the plans against a fake runner and ONLY the thin media3 implementation in
 * the edit app touches Transformer. No Android type crosses this seam:
 * URIs are strings, the render lands in a caller-supplied working directory
 * and the MediaStore insert (the non-destructive policy) stays with the
 * activity, exactly like the photo editor's ExportPlan execution.
 *
 * r70 grew the seam to TWO jobs under one parent: the v1 single-clip
 * [VideoExportRequest] (a validated [VideoEditPlan] over one source) and
 * the multi-clip [ClipExportRequest] (a validated [ClipTimeline]
 * composition). One interface, one coordinator: the single-flight and
 * exactly-once-terminal rules are the job's, not the plan shape's, so both
 * requests ride the same [VideoExportCoordinator] and the runner picks its
 * resolution by type.
 */

/** What a runner may be asked to render, and where the rendered bytes may land. */
sealed interface VideoExportJob {
    /** A writable directory for the render (the activity passes its cache dir). */
    val workingDirPath: String

    /** The render file name inside the working dir. */
    val renderFileName: String
}

/** The v1 single-clip job: the plan in, the one source it edits. */
data class VideoExportRequest(
    /** The VALIDATED edit plan; the coordinator refuses anything else before the runner. */
    val plan: VideoEditPlan,
    /** The read-only source content URI (a string; the seam is pure). */
    val sourceUri: String,
    /** A writable directory for the render (the activity passes its cache dir). */
    override val workingDirPath: String,
    /** The render file name inside the working dir. */
    override val renderFileName: String = "video-edit-render.mp4",
) : VideoExportJob

/** The typed progress: hundredths of a percent (0..10000), monotonic from the runner. */
data class VideoExportProgress(
    val percentHundredths: Int,
    val stage: VideoExportStage = VideoExportStage.RENDERING,
)

/** The honest stages the runner reports. */
enum class VideoExportStage { PREPARING, RENDERING, FINALIZING }

/** The typed terminal result, delivered exactly once. */
sealed interface VideoExportResult {
    /**
     * The render landed at [renderedFilePath]; the activity copies the bytes
     * into a NEW MediaStore row and deletes the temp file. [outputDurationMs]
     * is the plan's derived output duration (pinned by the plan tests).
     */
    data class Success(val renderedFilePath: String, val outputDurationMs: Long) : VideoExportResult

    /** The render failed; the message is honest and user-readable. */
    data class Failure(val reason: String) : VideoExportResult

    /** The user cancelled; the caller cleans up any temp file. */
    data object Canceled : VideoExportResult
}

/** The listener the runner reports through. */
interface VideoExportListener {
    fun onProgress(progress: VideoExportProgress)
    fun onResult(result: VideoExportResult)
}

/** The seam itself: start a render, or cancel the running one. */
interface VideoExportRunner {
    fun start(job: VideoExportJob, listener: VideoExportListener)
    fun cancel()
}

/**
 * The coordinator over the seam (pure, JVM-tested over a fake): validates
 * the job BEFORE the runner ever sees it (by type --- the v1 plan through
 * [VideoEditPlanValidator], the r70 composition through
 * [ClipExportValidator]), enforces single-flight (one render at a time),
 * forwards typed progress and delivers exactly one terminal result per
 * accepted job.
 */
class VideoExportCoordinator(private val runner: VideoExportRunner) : VideoExportListener {

    /** The submit outcome: the render started, or was refused with the honest reason. */
    sealed interface SubmitOutcome {
        data object Started : SubmitOutcome
        data class Refused(val reason: String) : SubmitOutcome
    }

    private var running: Boolean = false
    private var currentListener: VideoExportListener? = null

    /** The last accepted job, exposed for the tests and the UI's narration. */
    var lastJob: VideoExportJob? = null
        private set

    fun submit(job: VideoExportJob, listener: VideoExportListener): SubmitOutcome {
        if (running) {
            return SubmitOutcome.Refused("an export is already running --- cancel it first")
        }
        when (job) {
            is VideoExportRequest -> when (val validation = VideoEditPlanValidator.validate(job.plan)) {
                is VideoPlanValidation.Invalid -> {
                    return SubmitOutcome.Refused(validation.reason)
                }
                is VideoPlanValidation.Valid -> Unit
            }
            is ClipExportRequest -> when (val validation = ClipExportValidator.validate(job)) {
                is ClipExportValidation.Invalid -> {
                    return SubmitOutcome.Refused(validation.reason)
                }
                is ClipExportValidation.Valid -> Unit
            }
        }
        running = true
        currentListener = listener
        lastJob = job
        runner.start(job, this)
        return SubmitOutcome.Started
    }

    /** Cancels the running render; the runner answers with [VideoExportResult.Canceled]. */
    fun cancel() {
        if (!running) return
        runner.cancel()
    }

    override fun onProgress(progress: VideoExportProgress) {
        if (!running) return
        currentListener?.onProgress(progress)
    }

    override fun onResult(result: VideoExportResult) {
        if (!running) return
        running = false
        val listener = currentListener
        currentListener = null
        listener?.onResult(result)
    }
}
