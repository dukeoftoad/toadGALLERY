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
 * The media3 Transformer realization of [VideoExportRunner]: job in, typed progress +
 * result out; the only file whose export path touches Transformer. Each resolved spec
 * becomes one EditedMediaItem (clipping = trim, constant SpeedProvider = speed) chained
 * in one sequence; neutral adjustment/look/volume attach no effect/processor. Volume
 * rides GainProcessor (no setVolume at the pinned media3), skipped for stripped audio or
 * the 1f identity; progress polls on the main looper; cancel delivers Canceled exactly
 * once, absorbing media3's synchronous throw and dropping any late onError. Main thread
 * only; the render lands in the caller's working dir (the MediaStore insert stays with
 * the activity).
 */
// media3's speed surface needs both opt-in markers: UnstableApi for the bulk of the API,
// ExperimentalApi for EditedMediaItem.Builder.setSpeed(SpeedParameters, boolean).
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
        val videoEffects = buildList {
            if (!resolved.adjustments.isNeutral) {
                add(VideoAdjustEffect(resolved.adjustments))
            }
            // Shared look: attached only when present with intensity > 0 (no-op passes are
            // never attached). The effect carries the parsed cube; the shader samples it.
            if (resolved.look != null && resolved.look.intensity > 0) {
                add(VideoLookEffect(resolved.look))
            }
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
            // Volume gain per spec: skipped when audio is stripped (mute wins) or gain is the
            // 1f identity; isUnityUntil answers the current time, keeping the processor active.
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
        // The crossfade model ships in VideoCrossfade.kt, but the pinned media3 has no
        // per-boundary transition API; this runner renders hard cuts (dissolve is a tracked follow-up).
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
     * Progress without a busy loop: a handler tick every 250 ms reads [Transformer.getProgress];
     * BUSY/UNAVAILABLE reports the last known value rather than a fake zero.
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

    /** Exactly-once terminal delivery: the first of (onCompleted, onError, cancel) wins. */
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
            // Blocks this thread until media3 releases its pipeline; decode/encode itself
            // runs off-thread, so this is a bounded teardown.
            transformer?.cancel()
        } catch (_: RuntimeException) {
            // media3 may surface cancellation synchronously — that IS the cancel path.
        }
        deliverOnce { currentListener?.onResult(VideoExportResult.Canceled) }
    }

    /** A constant factor over the whole item --- one run, one speed. */
    private class ConstantSpeedProvider(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timestampUs: Long): Float = speed

        override fun getNextSpeedChangeTimeUs(timestampUs: Long): Long = Long.MIN_VALUE
    }

    /**
     * A job resolved to the flat shape the item builder consumes; both request types land
     * here so the builder cannot favor either. The look and crossfades ride along
     * (composition-path containers; the v1 path defaults them off).
     */
    private data class ResolvedJob(
        val specs: List<ClipExportItemSpec>,
        val adjustments: VideoAdjustParams,
        val volume: Float,
        val outputDurationMs: Long,
        val look: VideoLutLook? = null,
        val transitions: List<VideoCrossfade> = emptyList(),
    )

    /** The v1 path: the plan's run expansion over the one source. */
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
     * The composition path: one spec per entry, each with its own source, trim, speed and
     * per-entry strip decision. The request's look and crossfades ride the resolved job.
     */
    private fun resolveTimelineJob(job: ClipExportRequest): ResolvedJob = ResolvedJob(
        specs = ClipExportProjection.itemSpecs(job),
        adjustments = job.adjustments,
        volume = job.volume,
        outputDurationMs = ClipTimelineMath.outputDurationMs(job.timeline),
        look = job.look,
        transitions = job.transitions,
    )

    private companion object {
        const val POLL_INTERVAL_MS: Long = 250
    }
}
