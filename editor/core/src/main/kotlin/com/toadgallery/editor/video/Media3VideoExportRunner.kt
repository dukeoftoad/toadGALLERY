package com.toadgallery.editor.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Transformer.Listener
import androidx.media3.transformer.Effects
import java.io.File

/**
 * The media3 Transformer realization of the [VideoExportRunner] seam (r42,
 * contract decision 7 --- the ModelDownloader-seam precedent): the job in,
 * typed progress + result out. This is the ONLY file whose export path
 * touches media3's Transformer; the JVM suite drives the same jobs
 * against a fake runner (VideoExportRunnerTest), so the seam contract
 * itself is test-pinned without a device.
 *
 * How a job becomes a render (one realization, no drift with the pure
 * math; every API below verified against the media3 1.11.1 artifacts):
 *
 *  - r70: a [VideoExportJob] resolves to ONE flat list of
 *    [ClipExportItemSpec]s first ([resolvePlanJob] expands the v1 plan
 *    through [VideoEditMath.timelineRuns] --- the explicit speed segments
 *    plus the 1x gap runs, all over the one source; [resolveTimelineJob]
 *    projects the r70 [ClipExportRequest] through [ClipExportProjection]
 *    --- one spec per composition entry, each with its own source, trim,
 *    speed and per-entry strip decision; the ClipExportTest equivalence
 *    pin keeps the two resolvers from drifting). Each spec becomes ONE
 *    [EditedMediaItem]: [MediaItem.ClippingConfiguration] carries the
 *    spec's in/out points and [EditedMediaItem.Builder.setSpeed] its
 *    factor (a constant [SpeedProvider] per item --- media3 1.11.1 has no
 *    whole-item speed factor setter); the specs chain in one
 *    [EditedMediaItemSequence], so the render IS the job's timeline.
 *  - the nine adjustments ride as ONE [VideoAdjustEffect] per item (the
 *    same effect + shader the ExoPlayer preview consumes); a neutral
 *    parameter set attaches NO effect at all --- an exact identity, not a
 *    no-op shader pass.
 *  - audio: the strip decision is the SPEC's (the v1 plan-level decision
 *    realized per run; the r70 composition's per-entry resolution).
 *    [MuteDecision.STRIP_AUDIO] maps to [EditedMediaItem.Builder
 *    .setRemoveAudio]; a no-audio clip's recorded no-op maps to false
 *    (there is no track to strip; the job recorded the fact).
 *  - volume rides [GainProcessor] (media3 1.11.1's constant-capable gain
 *    AudioProcessor) in the effects chain's audio-processor slot --- the
 *    r52 CI catch is recorded here honestly: `EditedMediaItem.Builder
 *    .setVolume` does NOT exist at this pinned version (verified against
 *    the 1.11.1 artifacts, not guessed), so the gain is a
 *    [GainProcessor.GainProvider] returning the job's constant factor.
 *    It is skipped on every spec whose audio is stripped (mute wins by
 *    construction: no track is left to scale) and skipped at the exact 1f
 *    identity (the same neutral-adjustment rule as the effects: a no-op
 *    pass is never attached). The PREVIEW realizes the same gain as
 *    `player.volume` --- one model, two realizations, no drift.
 *  - progress: [Transformer.getProgress] polled on the main looper into
 *    the seam's hundredths-of-a-percent; honest stages (PREPARING before
 *    the start call, RENDERING while polling).
 *  - cancellation: media3 1.11.1 has NO ERROR_CODE_CANCELED --- a
 *    [Transformer.cancel] may surface the cancellation SYNCHRONOUSLY (a
 *    RuntimeException out of the cancel call itself) or through a later
 *    onError. The runner owns the mapping: [VideoExportRunner.cancel]
 *    swallows the synchronous throw, marks the cancel, and delivers
 *    [VideoExportResult.Canceled] exactly once (a late onError afterwards
 *    is dropped --- the coordinator's single-terminal-result rule).
 *
 * Everything runs on the app's main thread (Transformer requires its
 * building looper for every call); the heavy decode/encode is
 * media3-internal and off the UI thread by construction. The render lands
 * in the caller's working directory; the MediaStore insert (the
 * non-destructive policy) stays with the activity.
 */
// The media3 1.11.1 speed surface carries TWO separate opt-in markers:
// UnstableApi (the bulk of media3's API surface) and ExperimentalApi
// (EditedMediaItem.Builder.setSpeed(SpeedParameters, boolean) and the
// SpeedParameters constructor it takes --- the merge-gate's fifth catch;
// lint names the marker class, the fix adds it verbatim).
@androidx.annotation.OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class,
)
class Media3VideoExportRunner(private val context: Context) : VideoExportRunner {

    private var transformer: Transformer? = null
    private var progressHandler: Handler? = null
    private var polling: Boolean = false
    private var cancelRequested: Boolean = false
    private var terminalDelivered: Boolean = false
    private var currentListener: VideoExportListener? = null

    override fun start(job: VideoExportJob, listener: VideoExportListener) {
        currentListener = listener
        val resolved = when (job) {
            is VideoExportRequest -> resolvePlanJob(job)
            is ClipExportRequest -> resolveTimelineJob(job)
        }
        val videoEffects = if (resolved.adjustments.isNeutral) {
            emptyList()
        } else {
            listOf(VideoAdjustEffect(resolved.adjustments))
        }
        val volumeGain = resolved.volume
        val items = resolved.specs.map { spec ->
            val mediaItem = MediaItem.Builder()
                .setUri(spec.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(spec.range.startMs)
                        .setEndPositionMs(spec.range.endMs)
                        .build(),
                )
                .build()
            // The r52 volume gain, realized per spec: attached only when it
            // is neither moot (this spec's audio is stripped --- mute wins by
            // construction) nor the exact 1f identity (the same
            // neutral-adjustment rule the effects follow: a no-op pass is
            // never attached). The constant provider answers the SAM pair
            // directly (isUnityUntil returns the CURRENT time --- unity ends
            // now, so the processor stays active for a non-unity gain; the
            // provider itself is only ever attached at non-unity gains).
            val applyVolumeGain = !spec.removeAudio && volumeGain != 1f
            val audioProcessors: List<AudioProcessor> = if (applyVolumeGain) {
                listOf(
                    GainProcessor(
                        object : GainProcessor.GainProvider {
                            override fun getGainFactorAtSamplePosition(
                                presentationTimeUs: Long,
                                samplePosition: Int,
                            ): Float = volumeGain

                            override fun isUnityUntil(
                                presentationTimeUs: Long,
                                samplePosition: Int,
                            ): Long = presentationTimeUs
                        },
                    ),
                )
            } else {
                emptyList()
            }
            EditedMediaItem.Builder(mediaItem)
                .setSpeed(SpeedParameters(ConstantSpeedProvider(spec.speedFactor.toFloat()), /* shouldMaintainPitch= */ true))
                .setRemoveAudio(spec.removeAudio)
                .setEffects(Effects(audioProcessors, videoEffects))
                .build()
        }
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(items).build(),
        ).build()
        val output = File(job.workingDirPath, job.renderFileName)
        val runner = Transformer.Builder(context).build()
        transformer = runner
        cancelRequested = false
        terminalDelivered = false
        runner.addListener(object : Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                deliverOnce {
                    listener.onResult(
                        VideoExportResult.Success(
                            renderedFilePath = output.absolutePath,
                            outputDurationMs = resolved.outputDurationMs,
                        ),
                    )
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                deliverOnce {
                    if (cancelRequested) {
                        listener.onResult(VideoExportResult.Canceled)
                    } else {
                        listener.onResult(
                            VideoExportResult.Failure(exportException.message ?: "the render failed"),
                        )
                    }
                }
            }
        })
        listener.onProgress(VideoExportProgress(0, VideoExportStage.PREPARING))
        runner.start(composition, output.absolutePath)
        startPolling(listener)
    }

    /**
     * Honest progress without a busy loop: a handler tick every quarter
     * second reads [Transformer.getProgress]; STATE_BUSY/UNAVAILABLE is
     * reported as the last known value rather than a fake zero.
     */
    private fun startPolling(listener: VideoExportListener) {
        val handler = Handler(Looper.getMainLooper())
        progressHandler = handler
        polling = true
        var lastPercentHundredths = 0
        val holder = ProgressHolder()
        handler.post(object : Runnable {
            override fun run() {
                if (!polling) return
                val current = transformer
                if (current != null &&
                    current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                ) {
                    lastPercentHundredths = (holder.progress * 100).coerceIn(0, 10_000)
                }
                listener.onProgress(
                    VideoExportProgress(lastPercentHundredths, VideoExportStage.RENDERING),
                )
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        })
    }

    private fun stopPolling() {
        polling = false
        progressHandler?.removeCallbacksAndMessages(null)
        progressHandler = null
    }

    /**
     * The exactly-once terminal delivery: the first of (onCompleted,
     * onError, cancel) wins; the base-class stop bookkeeping rides along.
     */
    private fun deliverOnce(deliver: () -> Unit) {
        if (terminalDelivered) return
        terminalDelivered = true
        stopPolling()
        transformer = null
        deliver()
    }

    override fun cancel() {
        if (terminalDelivered) return
        cancelRequested = true
        try {
            // Blocking on this thread until media3 releases its pipeline
            // (the 1.11.1 contract) --- the export's own decode/encode ran
            // off this thread all along, so this is a bounded teardown.
            transformer?.cancel()
        } catch (_: RuntimeException) {
            // media3 1.11.1 may surface the cancellation synchronously ---
            // that IS the cancel path, not a failure.
        }
        deliverOnce { currentListener?.onResult(VideoExportResult.Canceled) }
    }

    /** A constant factor over the whole item --- one run, one speed. */
    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timestampUs: Long): Float = speed

        override fun getNextSpeedChangeTimeUs(timestampUs: Long): Long = Long.MIN_VALUE
    }

    /**
     * A job resolved to the one flat shape the item builder consumes: the
     * ordered specs, the shared treatment, the derived output duration.
     * Both request types land here, so the builder below cannot favor
     * either --- the ClipExportTest equivalence pin holds the v1 side.
     */
    private data class ResolvedJob(
        val specs: List<ClipExportItemSpec>,
        val adjustments: VideoAdjustParams,
        val volume: Float,
        val outputDurationMs: Long,
    )

    /** The v1 path: the plan's run expansion over the one source, byte-for-byte the r42-r66 shape. */
    private fun resolvePlanJob(job: VideoExportRequest): ResolvedJob {
        val stripAudio = job.plan.muteDecision() == MuteDecision.STRIP_AUDIO
        return ResolvedJob(
            specs = VideoEditMath.timelineRuns(job.plan).map { run ->
                ClipExportItemSpec(
                    sourceUri = job.sourceUri,
                    range = TimeRange(run.startMs, run.endMs),
                    speedFactor = run.speedFactor,
                    removeAudio = stripAudio,
                )
            },
            adjustments = job.plan.adjustments,
            volume = job.plan.volume,
            outputDurationMs = VideoEditMath.outputDurationMs(job.plan),
        )
    }

    /**
     * The r70 path: the composition's projection --- one spec per entry,
     * each carrying its own source, trim, speed and per-entry strip
     * decision. No caller yet (the picker is F-77's next link); the
     * realization lands with its model so the surfaces arrive onto a
     * working export, not a promise.
     */
    private fun resolveTimelineJob(job: ClipExportRequest): ResolvedJob = ResolvedJob(
        specs = ClipExportProjection.itemSpecs(job),
        adjustments = job.adjustments,
        volume = job.volume,
        outputDurationMs = ClipTimelineMath.outputDurationMs(job.timeline),
    )

    private companion object {
        const val POLL_INTERVAL_MS: Long = 250
    }
}
