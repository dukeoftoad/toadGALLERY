package io.github.dukeoftoad.gallery.edit.video

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
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
 * contract decision 7 --- the ModelDownloader-seam precedent): the plan in,
 * typed progress + result out. This is the ONLY file whose export path
 * touches media3's Transformer; the JVM suite drives the same plans
 * against a fake runner (VideoExportRunnerTest), so the seam contract
 * itself is test-pinned without a device.
 *
 * How the plan becomes a render (one realization, no drift with the pure
 * math; every API below verified against the media3 1.11.1 artifacts):
 *
 *  - [VideoEditMath.timelineRuns] expands the trim range into ordered
 *    (start, end, factor) runs --- the explicit speed segments plus the 1x
 *    gap runs. Each run becomes ONE [EditedMediaItem]:
 *    [MediaItem.ClippingConfiguration] carries the run's in/out points and
 *    [EditedMediaItem.Builder.setSpeed] the run's factor (a constant
 *    [SpeedProvider] per item --- media3 1.11.1 has no whole-item speed
 *    factor setter); the runs chain in one [EditedMediaItemSequence], so
 *    gaps riding at 1x and per-run speeds are exactly the plan's timeline.
 *  - the nine adjustments ride as ONE [VideoAdjustEffect] per item (the
 *    same effect + shader the ExoPlayer preview consumes); a neutral
 *    parameter set attaches NO effect at all --- an exact identity, not a
 *    no-op shader pass.
 *  - [MuteDecision.STRIP_AUDIO] maps to [EditedMediaItem.Builder
 *    .setRemoveAudio]; a no-audio clip's recorded no-op maps to false
 *    (there is no track to strip; the plan recorded the fact).
 *  - volume rides [EditedMediaItem.Builder.setVolume] (media3 1.11.1's
 *    audio gain --- no separate pass through the effects chain); it is
 *    skipped on the strip-audio path (mute wins by construction: no track
 *    is left to scale) and skipped at the exact 1f identity (the same
 *    neutral-adjustment rule as the effects: a no-op pass is never
 *    attached).
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

    override fun start(request: VideoExportRequest, listener: VideoExportListener) {
        currentListener = listener
        val plan = request.plan
        val stripAudio = plan.muteDecision() == MuteDecision.STRIP_AUDIO
        val videoEffects = if (plan.adjustments.isNeutral) {
            emptyList()
        } else {
            listOf(VideoAdjustEffect(plan.adjustments))
        }
        // The r52 volume gain: attached only when it is neither moot (the
        // strip-audio path has no track left to scale --- mute wins by
        // construction) nor the exact 1f identity (the same
        // neutral-adjustment rule the effects above follow: a no-op pass is
        // never attached).
        val volumeGain = plan.volume
        val applyVolumeGain = !stripAudio && volumeGain != 1f
        val items = VideoEditMath.timelineRuns(plan).map { run ->
            val mediaItem = MediaItem.Builder()
                .setUri(request.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(run.startMs)
                        .setEndPositionMs(run.endMs)
                        .build(),
                )
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setSpeed(SpeedParameters(ConstantSpeedProvider(run.speedFactor.toFloat()), /* shouldMaintainPitch= */ true))
                .setRemoveAudio(stripAudio)
                .apply { if (applyVolumeGain) setVolume(volumeGain) }
                .setEffects(Effects(emptyList<AudioProcessor>(), videoEffects))
                .build()
        }
        val composition = Composition.Builder(
            EditedMediaItemSequence.Builder(items).build(),
        ).build()
        val output = File(request.workingDirPath, request.renderFileName)
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
                            outputDurationMs = VideoEditMath.outputDurationMs(plan),
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

    private companion object {
        const val POLL_INTERVAL_MS: Long = 250
    }
}
