package io.github.dukeoftoad.gallery.edit.video

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.dukeoftoad.gallery.edit.EditorTheme
import io.github.dukeoftoad.gallery.edit.R
import io.github.dukeoftoad.gallery.edit.contract.EditContract
import io.github.dukeoftoad.gallery.edit.contract.EditRequest
import io.github.dukeoftoad.gallery.edit.contract.EditRequestValidator
import io.github.dukeoftoad.gallery.edit.contract.MediaKind
import io.github.dukeoftoad.gallery.edit.contract.RawExtras
import io.github.dukeoftoad.gallery.edit.contract.RequestValidation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * toadGALLERY Edit --- the r42 VIDEO surface (the Phase 10 sub-scope B
 * lightweight editor; ADR-0012; docs/phase-10/video-editor.md). The
 * additive contract v2 launch activity: the same contract action filtered
 * on VIDEO mimes, reached through the gallery's video-capable resolution
 * (a v1-era gallery never resolves it, a v2 gallery only sends it video).
 *
 * The session model is the PURE :shared:video-edit module: the UI bounds
 * its own inputs, builds [VideoEditPlan] / [FrameExtractPlan] /
 * [AnimationExportPlan] values, validates them through the shared
 * validators and this activity merely EXECUTES a validated plan ---
 * the ExportPlan precedent, video-shaped:
 *
 *  1. LAUNCH: the contract request validates (the r42 media-kind extra
 *     included; anything but VIDEO on this surface is ERROR_UNSUPPORTED).
 *     The clip FACTS (duration, audio presence, frame rate, pixel size)
 *     come from MediaMetadataRetriever and fail fast to ERROR_UNSUPPORTED
 *     when the clip is unreadable.
 *  2. PREVIEW: ExoPlayer + the SAME [VideoAdjustEffect] the export
 *     consumes (ExoPlayer.setVideoEffects) --- one model, two
 *     realizations, so preview and export agree by construction. Trim is
 *     previewed via the clipping configuration, mute via player volume,
 *     the whole-clip speed factor via playback speed; the optional region
 *     speed is honestly noted as export-applied (decision 3).
 *  3. EXPORT (video): the plan rides the [VideoExportCoordinator] seam
 *     into [Media3VideoExportRunner]; typed progress + cancellation, the
 *     render lands in the cache dir, then the bytes are copied into a NEW
 *     MediaStore row (IS_PENDING on Q+; a failed write cancels the row).
 *  4. EXPORT (frame / animation): pure plans again --- the frame count
 *     and the byte budget are decided BEFORE any decode by
 *     [AnimationPreflight] (the ExportPreflight shape); frames ride the
 *     MediaMetadataRetriever path (getFrameAtTime, OPTION_CLOSEST), the
 *     GIF encoder and the animated-WebP muxer are the shared module's
 *     project-authored code.
 *  5. NON-DESTRUCTIVE, ALWAYS: the source URI is only ever READ; every
 *     export is an INSERT of a new "-edit" named entry (VideoOutputPlan);
 *     no replace-original path exists anywhere.
 *  6. TERMINAL STATES: RESULT_OK (+ OUTPUT_URI; the metadata policy
 *     honestly echoes STRIP_ALL --- the re-encode writes a fresh
 *     container, no source metadata survives) / RESULT_CANCELED (back) /
 *     RESULT_ERROR (structured code).
 */
class VideoEditActivity : ComponentActivity() {

    /** The IO scope for the publish/decode work; outlives the composition. */
    private val exportScope = CoroutineScope(Dispatchers.IO + Job())

    /** The validated contract request; non-null after onCreate validation. */
    internal lateinit var request: EditRequest
        private set

    // ---- The clip facts (read once, before the UI; plan INPUTS, never guesses) ----

    internal var clipDurationMs: Long = 0L
        private set
    internal var audioTrackPresent: Boolean = false
        private set

    /** The source frame rate in fps when the platform exposes it (API 28+), else null. */
    internal var sourceFrameRateFps: Double? = null
        private set

    /** The rendered-orientation pixel size (rotation applied), for the animation height derivation. */
    internal var renderedWidthPx: Int = 0
        private set
    internal var renderedHeightPx: Int = 0
        private set

    /** The seam coordinator; only the thin media3 runner touches Transformer. */
    private val coordinator by lazy { VideoExportCoordinator(Media3VideoExportRunner(this)) }

    /** Set by the UI's cancel button; the frame/animation loops poll it between frames. */
    private val stillExportCanceled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // r47 ux fix: the photo surface's edge-to-edge posture verbatim ---
        // targetSdk 36 enforces it on API 35+; the dark SystemBarStyle
        // forces light icons over the always-black canvas (the auto style
        // rendered dark-on-black in light mode).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        // ---- Contract validation (pre-UI: invalid calls answer immediately) ----
        val sourceFromClip = intent?.clipData?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).uri?.toString() else null
        }
        val raw = RawExtras(
            contractVersion = if (intent?.hasExtra(EditContract.EXTRA_CONTRACT_VERSION) == true) {
                intent.getIntExtra(EditContract.EXTRA_CONTRACT_VERSION, Int.MIN_VALUE)
            } else {
                null
            },
            sourceUri = sourceUriFromExtra(intent) ?: sourceFromClip,
            operationHint = intent?.getStringExtra(EditContract.EXTRA_OPERATION_HINT),
            exportMode = intent?.getStringExtra(EditContract.EXTRA_EXPORT_MODE),
            metadataPolicy = intent?.getStringExtra(EditContract.EXTRA_METADATA_POLICY),
            mediaKind = intent?.getStringExtra(EditContract.EXTRA_MEDIA_KIND),
        )
        when (val validation = EditRequestValidator.validate(
            raw = raw,
            acceptedVersionMin = EditContract.CONTRACT_VERSION_MIN,
            acceptedVersionMax = EditContract.CONTRACT_VERSION,
        )) {
            is RequestValidation.Invalid -> {
                finishWithError(validation.invalid.errorCode, validation.invalid.reason)
                return
            }
            is RequestValidation.Valid -> request = validation.request
        }

        // This surface is the VIDEO half of the additive v2 contract: an
        // image request (the absent-default) belongs on the image activity.
        if (request.mediaKind != MediaKind.VIDEO) {
            finishWithError(
                EditContract.ERROR_UNSUPPORTED,
                "the video editor takes video sources only (media kind " +
                    request.mediaKind.wireName + ")",
            )
            return
        }

        // ---- The clip facts (fail fast on an unreadable source) ----
        val facts = readClipFacts(request.sourceUri)
        if (facts == null) {
            finishWithError(
                EditContract.ERROR_UNSUPPORTED,
                "cannot read " + request.sourceUri + " as a video on this device",
            )
            return
        }
        clipDurationMs = facts.durationMs
        audioTrackPresent = facts.audioTrackPresent
        sourceFrameRateFps = facts.frameRateFps
        renderedWidthPx = facts.widthPx
        renderedHeightPx = facts.heightPx

        setContent {
            // r47 ux fix: the family theme (toad-green, fixed dark) instead of
            // the bare MaterialTheme whose baseline purple leaked through
            // every accent role (the maintainer's device screenshot).
            EditorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    VideoEditorContent(activity = this@VideoEditActivity)
                }
            }
        }
    }

    // ---- The clip facts via MediaMetadataRetriever (the r42 frame path's front door) ----

    private class ClipFacts(
        val durationMs: Long,
        val audioTrackPresent: Boolean,
        val frameRateFps: Double?,
        val widthPx: Int,
        val heightPx: Int,
    )

    /**
     * One cheap metadata pass (the photo surface's decodeBounds precedent):
     * duration, audio presence, frame rate (the video track's format, read
     * through MediaExtractor; best-effort) and the rotation-corrected pixel
     * size. Null on any failure --- the caller answers ERROR_UNSUPPORTED
     * honestly.
     */
    private fun readClipFacts(sourceUri: String): ClipFacts? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, Uri.parse(sourceUri))
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val hasAudio = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            val frameRateFps = readTrackFrameRate(sourceUri)
            val rawWidth = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (durationMs <= 0 || rawWidth <= 0 || rawHeight <= 0) {
                null
            } else {
                val swapped = rotation == 90 || rotation == 270
                ClipFacts(
                    durationMs = durationMs,
                    audioTrackPresent = hasAudio,
                    frameRateFps = frameRateFps?.takeIf { it > 0.0 },
                    widthPx = if (swapped) rawHeight else rawWidth,
                    heightPx = if (swapped) rawWidth else rawHeight,
                )
            }
        } finally {
            retriever.release()
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * The frame-rate fact (the r42 CI catch): MediaMetadataRetriever has NO
     * frame-rate key at ANY API level --- the constant does not exist; the
     * honest source is the video track's MediaFormat, read through
     * MediaExtractor over the source's asset file descriptor (MediaExtractor
     * has no Context/Uri overload; the content resolver opens the
     * read-only source). Best-effort: null when the track format does not
     * carry it, and the animation plan's effectiveFps then rides the honest
     * unknown (the requested fps stands; the plan module's null fallback).
     */
    private fun readTrackFrameRate(sourceUri: String): Double? = try {
        contentResolver.openAssetFileDescriptor(Uri.parse(sourceUri), "r")?.use { afd ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(afd.fileDescriptor)
                var fps: Double? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val isVideo = format.getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                    if (isVideo && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val value = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                        if (value > 0) fps = value.toDouble()
                        break
                    }
                }
                fps
            } finally {
                extractor.release()
            }
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** A fresh retriever over the read-only source, for the frame/animation decodes. */
    private fun openRetriever(sourceUri: String): MediaMetadataRetriever? = try {
        MediaMetadataRetriever().also { it.setDataSource(this, Uri.parse(sourceUri)) }
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    // ---- Result plumbing (the three terminal states) ----

    /** The launch's source URI as a Parcelable Uri (spec section 2), API-33-compat read. */
    private fun sourceUriFromExtra(intent: Intent?): String? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EditContract.EXTRA_SOURCE_URI, Uri::class.java)?.toString()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(EditContract.EXTRA_SOURCE_URI)?.toString()
        }
    }

    /** RESULT_ERROR + structured code, then finish. */
    internal fun finishWithError(errorCode: String, message: String) {
        val result = Intent().apply {
            putExtra(EditContract.EXTRA_ERROR_CODE, errorCode)
            putExtra(EditContract.EXTRA_ERROR_MESSAGE, message)
        }
        setResult(EditContract.RESULT_ERROR, result)
        finish()
    }

    /**
     * RESULT_OK with the saved-copy URI, then finish. The applied policy is
     * honestly STRIP_ALL: the media3 re-encode writes a fresh container and
     * no source metadata survives it (the r31 honesty narration --- never a
     * bare "saved" when the copy's metadata was reduced).
     */
    internal fun finishWithSavedCopy(outputUri: Uri) {
        val result = Intent().apply {
            putExtra(EditContract.EXTRA_OUTPUT_URI, outputUri.toString())
            putExtra(
                EditContract.EXTRA_METADATA_POLICY_APPLIED,
                io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy.STRIP_ALL.wireName,
            )
        }
        setResult(EditContract.RESULT_OK, result)
        finish()
    }

    internal fun finishCanceled() {
        setResult(EditContract.RESULT_CANCELED)
        finish()
    }

    /** The source's display name, for the "-edit" naming rule. */
    internal fun sourceDisplayName(sourceUri: String): String? = try {
        contentResolver.query(
            Uri.parse(sourceUri),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }

    // ---- The video export (the coordinator seam + the MediaStore publish) ----

    /**
     * Submits a validated plan through the seam. Must run on the main
     * thread (the media3 runner requires its building looper); progress
     * and the terminal outcome arrive on the main thread too.
     */
    internal fun submitVideoExport(
        plan: VideoEditPlan,
        onProgress: (VideoExportProgress) -> Unit,
        onFinished: (VideoExportOutcome) -> Unit,
    ) {
        val request = VideoExportRequest(
            plan = plan,
            sourceUri = this.request.sourceUri,
            workingDirPath = cacheDir.path,
        )
        when (val outcome = coordinator.submit(request, object : VideoExportListener {
            override fun onProgress(progress: VideoExportProgress) {
                onProgress(progress)
            }

            override fun onResult(result: VideoExportResult) {
                when (result) {
                    is VideoExportResult.Success -> exportScope.launch {
                        publishVideoExport(result, onFinished)
                    }
                    is VideoExportResult.Failure -> onFinished(VideoExportOutcome.Failed(result.reason))
                    VideoExportResult.Canceled -> onFinished(VideoExportOutcome.Canceled)
                }
            }
        })) {
            is VideoExportCoordinator.SubmitOutcome.Refused ->
                onFinished(VideoExportOutcome.Failed(outcome.reason))
            VideoExportCoordinator.SubmitOutcome.Started -> Unit
        }
    }

    /** Cancels the running video export; the outcome arrives as Canceled. */
    internal fun cancelVideoExport() {
        coordinator.cancel()
    }

    /**
     * Copies the rendered file into a NEW MediaStore row (IS_PENDING on Q+,
     * finalized after the bytes land; a failed write cancels the pending
     * row) and answers the gallery. The temp render is deleted either way.
     */
    private suspend fun publishVideoExport(
        result: VideoExportResult.Success,
        onFinished: (VideoExportOutcome) -> Unit,
    ) {
        val api29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var outputUri: Uri? = null
        val renderFile = File(result.renderedFilePath)
        try {
            val displayName = VideoOutputPlan.displayNameFor(
                sourceDisplayName(request.sourceUri),
                VideoOutputKind.TRIMMED_VIDEO,
            )
            val collection = if (api29Plus) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            outputUri = insertPendingRow(collection, displayName, VideoOutputKind.TRIMMED_VIDEO, api29Plus)
            contentResolver.openOutputStream(outputUri)?.use { out ->
                renderFile.inputStream().use { input -> input.copyTo(out) }
                out.flush()
            } ?: throw IOException("cannot open the output stream")
            finalizeRow(outputUri, api29Plus)
            withContext(Dispatchers.Main) {
                onFinished(VideoExportOutcome.Saved)
                finishWithSavedCopy(outputUri)
            }
        } catch (e: SecurityException) {
            cancelPendingRow(outputUri, api29Plus)
            deleteQuietly(renderFile)
            withContext(Dispatchers.Main) {
                onFinished(VideoExportOutcome.Failed("a MediaStore access was refused"))
            }
        } catch (e: IOException) {
            cancelPendingRow(outputUri, api29Plus)
            deleteQuietly(renderFile)
            withContext(Dispatchers.Main) {
                onFinished(VideoExportOutcome.Failed("an I/O failure occurred during the export"))
            }
        } catch (e: RuntimeException) {
            cancelPendingRow(outputUri, api29Plus)
            deleteQuietly(renderFile)
            withContext(Dispatchers.Main) {
                onFinished(VideoExportOutcome.Failed(e.message ?: "unexpected export failure"))
            }
        }
    }

    // ---- The frame + animation exports (the retriever path) ----

    /**
     * Extracts ONE source frame (no colour adjustments --- the honest note
     * says so in the sheet) and inserts it as a new JPEG/PNG entry.
     */
    internal fun submitFrameExtract(
        plan: FrameExtractPlan,
        onFinished: (StillExportOutcome) -> Unit,
    ) {
        stillExportCanceled.set(false)
        exportScope.launch {
            val outcome = runStillExport { insertRow, finalize ->
                val retriever = openRetriever(request.sourceUri)
                    ?: return@runStillExport StillExportOutcome.Failed("cannot open the video")
                try {
                    val frame = retriever.getFrameAtTime(
                        plan.timestampMs * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    ) ?: return@runStillExport StillExportOutcome.Failed("cannot decode the frame")
                    if (stillExportCanceled.get()) return@runStillExport StillExportOutcome.Canceled
                    val kind = when (plan.format) {
                        FrameFormat.JPEG -> VideoOutputKind.FRAME_JPEG
                        FrameFormat.PNG -> VideoOutputKind.FRAME_PNG
                    }
                    val displayName = VideoOutputPlan.displayNameFor(
                        sourceDisplayName(request.sourceUri),
                        kind,
                    )
                    val uri = insertRow(kind, displayName)
                    val ok = contentResolver.openOutputStream(uri)?.use { out ->
                        val format = when (plan.format) {
                            FrameFormat.JPEG -> Bitmap.CompressFormat.JPEG
                            FrameFormat.PNG -> Bitmap.CompressFormat.PNG
                        }
                        val quality = if (plan.format == FrameFormat.JPEG) FRAME_JPEG_QUALITY else 100
                        frame.compress(format, quality, out)
                    } ?: false
                    if (!ok) throw IOException("compression failed")
                    finalize(uri)
                    StillExportOutcome.Saved
                } finally {
                    retriever.release()
                }
            }
            withContext(Dispatchers.Main) { onFinished(outcome) }
        }
    }

    /**
     * The animation export: the preflight has already agreed (the UI ran it
     * BEFORE calling, showing any refusal honestly), so this walks the
     * derived timestamps, decodes + scales each frame, encodes through the
     * shared GIF encoder or the platform lossless-WebP + shared muxer, and
     * inserts the result as ONE new entry. Cancellation is polled between
     * frames; a cancelled run cancels its pending row.
     */
    internal fun submitAnimationExport(
        plan: AnimationExportPlan,
        frameHeightPx: Int,
        onProgress: (frameIndex: Int, frameCount: Int) -> Unit,
        onFinished: (StillExportOutcome) -> Unit,
    ) {
        stillExportCanceled.set(false)
        exportScope.launch {
            val outcome = runStillExport { insertRow, finalize ->
                // The preflight again, defensively: the byte budget is the
                // render-time law, not a UI suggestion.
                when (val preflight = AnimationPreflight.preflight(
                    plan = plan,
                    sourceFrameRateFps = sourceFrameRateFps,
                    frameHeightPx = frameHeightPx,
                    webpLosslessSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                )) {
                    is AnimationPreflight.Outcome.Refusal ->
                        return@runStillExport StillExportOutcome.Failed(preflight.reason)
                    is AnimationPreflight.Outcome.Proceed -> Unit
                }
                val retriever = openRetriever(request.sourceUri)
                    ?: return@runStillExport StillExportOutcome.Failed("cannot open the video")
                try {
                    val timestamps = AnimationExportPlanValidator.frameTimestampsMs(
                        plan,
                        sourceFrameRateFps,
                    )
                    val intervalMs = (1000.0 / AnimationExportPlanValidator.effectiveFps(
                        plan.fps,
                        sourceFrameRateFps,
                    )).toLong().coerceAtLeast(1L)
                    val kind = when (plan.format) {
                        AnimationFormat.GIF -> VideoOutputKind.ANIMATION_GIF
                        AnimationFormat.WEBP -> VideoOutputKind.ANIMATION_WEBP
                    }
                    if (plan.format == AnimationFormat.GIF) {
                        val frames = mutableListOf<GifEncoder.GifFrame>()
                        for ((index, timestampMs) in timestamps.withIndex()) {
                            if (stillExportCanceled.get()) {
                                return@runStillExport StillExportOutcome.Canceled
                            }
                            withContext(Dispatchers.Main) { onProgress(index, timestamps.size) }
                            val frame = decodeScaledFrame(retriever, timestampMs, plan.targetWidthPx)
                                ?: return@runStillExport StillExportOutcome.Failed(
                                    "cannot decode the frame at " + timestampMs + " ms",
                                )
                            val pixels = IntArray(frame.width * frame.height)
                            frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                            frames.add(
                                GifEncoder.GifFrame(
                                    argb = pixels,
                                    width = frame.width,
                                    height = frame.height,
                                    delayCs = GifEncoder.delayCsFor(intervalMs),
                                ),
                            )
                        }
                        withContext(Dispatchers.Main) { onProgress(timestamps.size, timestamps.size) }
                        val bytes = GifEncoder.encode(frames)
                        publishStillBytes(kind, bytes, insertRow, finalize)
                    } else {
                        // WebP: the platform lossless encoder per frame (API
                        // 30+, the preflight's platform gate) + the shared muxer.
                        val frames = mutableListOf<AnimatedWebpMuxer.WebpFrame>()
                        for ((index, timestampMs) in timestamps.withIndex()) {
                            if (stillExportCanceled.get()) {
                                return@runStillExport StillExportOutcome.Canceled
                            }
                            withContext(Dispatchers.Main) { onProgress(index, timestamps.size) }
                            val frame = decodeScaledFrame(retriever, timestampMs, plan.targetWidthPx)
                                ?: return@runStillExport StillExportOutcome.Failed(
                                    "cannot decode the frame at " + timestampMs + " ms",
                                )
                            val encoded = ByteArrayOutputStream()
                            // The r42 lint catch: WEBP_LOSSLESS is API 30;
                            // below R the frame rides lossy WebP at quality
                            // 100 --- the honest degradation mirroring the
                            // preflight's webpLosslessSupported fact.
                            val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Bitmap.CompressFormat.WEBP_LOSSLESS
                            } else {
                                Bitmap.CompressFormat.WEBP
                            }
                            if (!frame.compress(webpFormat, 100, encoded)) {
                                return@runStillExport StillExportOutcome.Failed(
                                    "the WebP encoder refused a frame",
                                )
                            }
                            frames.add(
                                AnimatedWebpMuxer.WebpFrame(
                                    fileBytes = encoded.toByteArray(),
                                    durationMs = intervalMs.toInt(),
                                ),
                            )
                        }
                        withContext(Dispatchers.Main) { onProgress(timestamps.size, timestamps.size) }
                        val bytes = AnimatedWebpMuxer.mux(
                            frames,
                            plan.targetWidthPx,
                            frameHeightPx,
                        )
                        publishStillBytes(kind, bytes, insertRow, finalize)
                    }
                } finally {
                    retriever.release()
                }
            }
            withContext(Dispatchers.Main) { onFinished(outcome) }
        }
    }

    /** Cancels a running frame/animation export (polled between frames). */
    internal fun cancelStillExport() {
        stillExportCanceled.set(true)
    }

    /** One decoded, scaled frame off the retriever; null on any failure. */
    private fun decodeScaledFrame(
        retriever: MediaMetadataRetriever,
        timestampMs: Long,
        targetWidthPx: Int,
    ): Bitmap? = try {
        val frame = retriever.getFrameAtTime(timestampMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: return null
        if (frame.width == targetWidthPx) {
            frame
        } else {
            val targetHeight = ((targetWidthPx.toLong() * frame.height / frame.width).toInt())
                .coerceAtLeast(1)
            Bitmap.createScaledBitmap(frame, targetWidthPx, targetHeight, true)
        }
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * The shared still-export spine: the insert/finalize helpers plus the
     * pending-row recovery, so the frame and animation paths share ONE
     * non-destructive publish discipline. A body that RETURNS a non-Saved
     * outcome (or throws) gets its pending row cancelled --- nothing
     * partial survives.
     */
    private suspend fun runStillExport(
        body: suspend (
            insertRow: (VideoOutputKind, String) -> Uri,
            finalize: (Uri) -> Unit,
        ) -> StillExportOutcome,
    ): StillExportOutcome {
        val api29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var outputUri: Uri? = null
        // The spine's two callbacks (positional: a function-type value takes
        // no named arguments). insertRow keys the row to its collection;
        // finalize clears IS_PENDING.
        val insertRow: (VideoOutputKind, String) -> Uri = { kind, displayName ->
            val collection = if (kind.store == VideoOutputStore.VIDEO) {
                if (api29Plus) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            } else {
                if (api29Plus) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }
            val uri = insertPendingRow(collection, displayName, kind, api29Plus)
            outputUri = uri
            uri
        }
        val finalize: (Uri) -> Unit = { uri -> finalizeRow(uri, api29Plus) }
        return try {
            val outcome = body(insertRow, finalize)
            if (outcome != StillExportOutcome.Saved) {
                cancelPendingRow(outputUri, api29Plus)
            }
            outcome
        } catch (e: SecurityException) {
            cancelPendingRow(outputUri, api29Plus)
            StillExportOutcome.Failed("a MediaStore access was refused")
        } catch (e: IOException) {
            cancelPendingRow(outputUri, api29Plus)
            StillExportOutcome.Failed("an I/O failure occurred during the export")
        } catch (e: OutOfMemoryError) {
            cancelPendingRow(outputUri, api29Plus)
            StillExportOutcome.Failed("this animation is too large for this device's memory")
        } catch (e: RuntimeException) {
            cancelPendingRow(outputUri, api29Plus)
            StillExportOutcome.Failed(e.message ?: "unexpected export failure")
        }
    }

    /** Inserts the encoded animation bytes as one new entry. */
    private fun publishStillBytes(
        kind: VideoOutputKind,
        bytes: ByteArray,
        insertRow: (VideoOutputKind, String) -> Uri,
        finalize: (Uri) -> Unit,
    ): StillExportOutcome {
        val displayName = VideoOutputPlan.displayNameFor(
            sourceDisplayName(request.sourceUri),
            kind,
        )
        val uri = insertRow(kind, displayName)
        contentResolver.openOutputStream(uri)?.use { out ->
            out.write(bytes)
            out.flush()
        } ?: throw IOException("cannot open the output stream")
        finalize(uri)
        return StillExportOutcome.Saved
    }

    // ---- The MediaStore discipline (pure policy in, resolver calls out) ----

    /** The NEW-row insert (IS_PENDING on Q+); the only write the editor performs. */
    private fun insertPendingRow(
        collection: Uri,
        displayName: String,
        kind: VideoOutputKind,
        api29Plus: Boolean,
    ): Uri {
        val values = ContentValues()
        VideoOutputPlan.insertValues(displayName, kind, api29Plus).forEach { (key, value) ->
            when (value) {
                is String -> values.put(key, value)
                is Int -> values.put(key, value)
            }
        }
        return contentResolver.insert(collection, values)
            ?: throw IOException("MediaStore refused the insert")
    }

    /** Clears IS_PENDING on Q+ so the entry becomes visible. */
    private fun finalizeRow(uri: Uri, api29Plus: Boolean) {
        if (!api29Plus) return
        val finalize = ContentValues()
        VideoOutputPlan.finalizeValues(api29Plus).forEach { (key, value) ->
            finalize.put(key, value as Int)
        }
        contentResolver.update(uri, finalize, null, null)
    }

    /** Cancels a pending row on Q+ so no partial entry survives a failed write. */
    private fun cancelPendingRow(outputUri: Uri?, api29Plus: Boolean) {
        if (outputUri == null || !api29Plus ||
            VideoOutputPlan.failureRecovery(api29Plus) != VideoOutputPlan.Recovery.CANCEL_PENDING_ROW
        ) {
            return
        }
        try {
            contentResolver.delete(outputUri, null, null)
        } catch (_: RuntimeException) {
            // Best-effort recovery; never mask the export's own failure.
        }
    }

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (_: RuntimeException) {
            // The cache dir is the OS's to reclaim.
        }
    }

    private companion object {
        const val FRAME_JPEG_QUALITY: Int = 95
    }
}

// ---------------------------------------------------------------------------
// Compose UI
// ---------------------------------------------------------------------------

/** What the UI learns when a video export ends (top-level: the surface reads it unqualified). */
internal sealed interface VideoExportOutcome {
    /** The copy landed and the activity already answered the gallery. */
    data object Saved : VideoExportOutcome

    /** The render or the publish failed; the honest reason rides along. */
    data class Failed(val reason: String) : VideoExportOutcome

    /** The user cancelled; back to editing, nothing was modified anywhere. */
    data object Canceled : VideoExportOutcome
}

/** What the UI learns when a still export (frame or animation) ends. */
internal sealed interface StillExportOutcome {
    /** A new MediaStore entry landed; the editor stays open for more. */
    data object Saved : StillExportOutcome

    /** Validation, decode or the write failed; the honest reason rides along. */
    data class Failed(val reason: String) : StillExportOutcome

    /** The user cancelled between frames; nothing partial survived. */
    data object Canceled : StillExportOutcome
}

/** The smallest trim/speed granularity the sliders snap to (100 ms). */
private const val TIME_STEP_MS: Long = 100

/** One slider's binding over the video session state (the photo SliderSpec shape). */
private data class VideoSliderSpec(
    val label: Int,
    val a11yNameRes: Int,
    val get: (VideoAdjustParams) -> Int,
    val set: (VideoAdjustParams, Int) -> VideoAdjustParams,
)

/** The nine ADR-0012 sliders in the photo surface's own order (sharpness absent --- off-video). */
private val VIDEO_ADJUST_SLIDERS: List<VideoSliderSpec> = listOf(
    VideoSliderSpec(R.string.editor_exposure, R.string.editor_a11y_exposure, { it.exposure }, { p, v -> p.copy(exposure = v) }),
    VideoSliderSpec(R.string.editor_brightness, R.string.editor_a11y_brightness, { it.brightness }, { p, v -> p.copy(brightness = v) }),
    VideoSliderSpec(R.string.editor_contrast, R.string.editor_a11y_contrast, { it.contrast }, { p, v -> p.copy(contrast = v) }),
    VideoSliderSpec(R.string.editor_saturation, R.string.editor_a11y_saturation, { it.saturation }, { p, v -> p.copy(saturation = v) }),
    VideoSliderSpec(R.string.editor_temperature, R.string.editor_a11y_temperature, { it.temperature }, { p, v -> p.copy(temperature = v) }),
    VideoSliderSpec(R.string.editor_tint, R.string.editor_a11y_tint, { it.tint }, { p, v -> p.copy(tint = v) }),
    VideoSliderSpec(R.string.editor_highlights, R.string.editor_a11y_highlights, { it.highlights }, { p, v -> p.copy(highlights = v) }),
    VideoSliderSpec(R.string.editor_shadows, R.string.editor_a11y_shadows, { it.shadows }, { p, v -> p.copy(shadows = v) }),
    VideoSliderSpec(R.string.editor_vignette, R.string.editor_a11y_vignette, { it.vignette }, { p, v -> p.copy(vignette = v) }),
)

/** Formats a millisecond position as seconds with one decimal (the slider labels). */
private fun formatSeconds(ms: Long): String =
    String.format(Locale.US, "%.1f s", ms / 1000.0)

/** Formats a speed factor as the "0.50x" the labels show. */
private fun formatSpeed(factor: Double): String =
    String.format(Locale.US, "%.2fx", factor)

/**
 * The video editor surface: the ExoPlayer preview (trim + mute + the
 * whole-clip speed + the live nine-slider colour preview), the trim /
 * mute / speed controls, the frame-extract and animation sheets, the
 * honest no-audio note, and the export progress with cancellation. The
 * r32 a11y conventions throughout: every control carries its name and
 * its live value, targets stay at the 48dp minimum, the no-audio note is
 * visible text (TalkBack reads it in traversal order --- never a dead
 * toggle).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // FilterChip in the sheets
@Composable
private fun VideoEditorContent(activity: VideoEditActivity) {
    val durationMs = activity.clipDurationMs
    val audioTrackPresent = activity.audioTrackPresent
    val context = LocalContext.current

    // The hoisted a11y names/values (semantics blocks are not composable,
    // so every string they announce is read here first).
    val previewA11yName = stringResource(R.string.editor_a11y_video_preview)
    val muteA11yName = stringResource(R.string.editor_a11y_video_mute)
    val muteStateOn = stringResource(R.string.editor_video_mute_state_on)
    val muteStateOff = stringResource(R.string.editor_video_mute_state_off)

    // ---- The session state (the UI bounds its own inputs; the plan
    // validators are the second, honest gate) ----
    var trimStartMs by remember { mutableStateOf(0L) }
    var trimEndMs by remember { mutableStateOf(durationMs) }
    var mute by remember { mutableStateOf(false) }
    var regionMode by remember { mutableStateOf(false) }
    var wholeSpeed by remember { mutableStateOf(1.0f) }
    var regionStartMs by remember { mutableStateOf(durationMs / 4) }
    var regionEndMs by remember { mutableStateOf(durationMs / 2) }
    var regionSpeed by remember { mutableStateOf(0.5f) }
    var params by remember { mutableStateOf(VideoAdjustParams()) }
    var draft by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // ---- The exports ----
    var videoExport by remember { mutableStateOf<VideoExportProgress?>(null) }
    var videoMessage by remember { mutableStateOf<String?>(null) }
    var frameBusy by remember { mutableStateOf(false) }
    var frameMessage by remember { mutableStateOf<String?>(null) }
    var frameTimestampMs by remember { mutableStateOf(0L) }
    var frameFormat by remember { mutableStateOf(FrameFormat.JPEG) }
    var animStartMs by remember { mutableStateOf(0L) }
    var animEndMs by remember { mutableStateOf((durationMs / 2).coerceAtLeast(TIME_STEP_MS)) }
    var animWidth by remember { mutableStateOf(480f) }
    var animFps by remember { mutableStateOf(10f) }
    var animFormat by remember { mutableStateOf(AnimationFormat.GIF) }
    var animBusy by remember { mutableStateOf(false) }
    var animProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var animMessage by remember { mutableStateOf<String?>(null) }
    val exporting = videoExport != null || frameBusy || animBusy

    // The pre-Q storage permission gate (the photo surface's pattern): the
    // pending action runs after the grant, or is dropped honestly.
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingAction?.invoke()
        } else {
            videoMessage = context.getString(R.string.editor_video_permission_refused)
        }
        pendingAction = null
    }

    fun withStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    // ---- The preview: one player, rebuilt on committed state ----
    val player = remember { ExoPlayer.Builder(activity).build() }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(trimStartMs, trimEndMs, params) {
        val mediaItem = MediaItem.Builder()
            .setUri(activity.request.sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build(),
            )
            .build()
        if (params.isNeutral) {
            // An exact identity: no effect attached at all, no shader pass.
        } else {
            player.setVideoEffects(listOf(VideoAdjustEffect(params)))
        }
        player.setMediaItem(mediaItem)
        player.playWhenReady = true
        player.prepare()
    }
    LaunchedEffect(mute) {
        player.volume = if (mute) 0f else 1f
    }
    // The whole-clip factor previews exactly via playback speed (the
    // setter-only Player API: no read-back property on media3 1.11.1); a
    // region factor is honestly export-applied only (the note says so).
    LaunchedEffect(regionMode, wholeSpeed) {
        player.setPlaybackSpeed(if (regionMode) 1f else wholeSpeed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // r47 ux fix: pay the system-bar insets once, at the root, so
            // the top bar and the sliders never sit under the status/
            // navigation bars (edge-to-edge is enforced at targetSdk 36+).
            .systemBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // ---- Top bar: close / title / save copy ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { activity.finishCanceled() }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.editor_close),
                    tint = Color.White,
                )
            }
            Text(
                text = stringResource(R.string.editor_video_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            if (exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = Color.White,
                )
            } else {
                Button(
                    onClick = {
                        videoMessage = null
                        val segments = if (regionMode) {
                            if (regionSpeed == 1.0f) {
                                emptyList()
                            } else {
                                listOf(
                                    SpeedSegment(
                                        startMs = regionStartMs,
                                        endMs = regionEndMs,
                                        speedFactor = regionSpeed.toDouble(),
                                    ),
                                )
                            }
                        } else if (wholeSpeed == 1.0f) {
                            emptyList()
                        } else {
                            listOf(
                                SpeedSegment(
                                    startMs = trimStartMs,
                                    endMs = trimEndMs,
                                    speedFactor = wholeSpeed.toDouble(),
                                ),
                            )
                        }
                        val plan = VideoEditPlan(
                            durationMs = durationMs,
                            trim = TimeRange(trimStartMs, trimEndMs),
                            speedSegments = segments,
                            audioTrackPresent = audioTrackPresent,
                            mute = mute,
                            adjustments = params,
                        )
                        when (val validation = VideoEditPlanValidator.validate(plan)) {
                            is VideoPlanValidation.Invalid -> {
                                // The UI bounds its own inputs, so this is a
                                // bug or tampering --- and it says so.
                                videoMessage = validation.reason
                            }
                            is VideoPlanValidation.Valid -> withStoragePermission {
                                videoExport = VideoExportProgress(0, VideoExportStage.PREPARING)
                                activity.submitVideoExport(
                                    plan = validation.plan,
                                    onProgress = { progress -> videoExport = progress },
                                    onFinished = { outcome ->
                                        videoExport = null
                                        when (outcome) {
                                            VideoExportOutcome.Saved -> Unit // the activity finishes
                                            is VideoExportOutcome.Failed -> videoMessage = context.getString(
                                                R.string.editor_video_export_failed,
                                                outcome.reason,
                                            )
                                            VideoExportOutcome.Canceled -> Unit
                                        }
                                    },
                                )
                            }
                        }
                    },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text(stringResource(R.string.editor_save_copy))
                }
            }
        }

        // ---- The preview surface ----
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    this.player = player
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = previewA11yName },
        )

        // ---- Trim ----
        SectionLabel(R.string.editor_video_section_trim)
        TimeSlider(
            label = stringResource(R.string.editor_video_trim_start, formatSeconds(trimStartMs)),
            a11yName = stringResource(R.string.editor_a11y_video_trim_start),
            value = trimStartMs,
            range = 0f..(trimEndMs - TIME_STEP_MS).toFloat(),
            enabled = !exporting,
            onCommit = { trimStartMs = it },
        )
        TimeSlider(
            label = stringResource(R.string.editor_video_trim_end, formatSeconds(trimEndMs)),
            a11yName = stringResource(R.string.editor_a11y_video_trim_end),
            value = trimEndMs,
            range = (trimStartMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
            enabled = !exporting,
            onCommit = { trimEndMs = it },
        )

        // ---- Audio: the honest fact, not a dead toggle ----
        SectionLabel(R.string.editor_video_section_audio)
        if (audioTrackPresent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(
                    checked = mute,
                    onCheckedChange = { mute = it },
                    enabled = !exporting,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .semantics {
                            contentDescription = muteA11yName
                            stateDescription = if (mute) muteStateOn else muteStateOff
                        },
                )
                Text(
                    text = stringResource(R.string.editor_video_mute),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        } else {
            // The contract's honest note: the clip HAS no audio track, so
            // there is nothing to mute --- the export proceeds video-only
            // and the note is visible text TalkBack announces.
            Text(
                text = stringResource(R.string.editor_video_no_audio),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // ---- Speed: whole-clip OR one region (the rest rides at 1x) ----
        SectionLabel(R.string.editor_video_section_speed)
        Row {
            FilterChip(
                selected = !regionMode,
                onClick = { regionMode = false },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_video_speed_whole_clip)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = regionMode,
                onClick = { regionMode = true },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_video_speed_region)) },
            )
        }
        if (regionMode) {
            TimeSlider(
                label = stringResource(R.string.editor_video_region_start, formatSeconds(regionStartMs)),
                a11yName = stringResource(R.string.editor_a11y_video_region_start),
                value = regionStartMs,
                range = 0f..(regionEndMs - TIME_STEP_MS).toFloat(),
                enabled = !exporting,
                onCommit = { regionStartMs = it },
            )
            TimeSlider(
                label = stringResource(R.string.editor_video_region_end, formatSeconds(regionEndMs)),
                a11yName = stringResource(R.string.editor_a11y_video_region_end),
                value = regionEndMs,
                range = (regionStartMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
                enabled = !exporting,
                onCommit = { regionEndMs = it },
            )
            SpeedSlider(
                label = stringResource(R.string.editor_video_region_speed, formatSpeed(regionSpeed.toDouble())),
                a11yName = stringResource(R.string.editor_a11y_video_region_speed),
                value = regionSpeed,
                enabled = !exporting,
                onCommit = { regionSpeed = it },
            )
            Text(
                text = stringResource(R.string.editor_video_region_note),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        } else {
            SpeedSlider(
                label = stringResource(R.string.editor_video_speed, formatSpeed(wholeSpeed.toDouble())),
                a11yName = stringResource(R.string.editor_a11y_video_speed),
                value = wholeSpeed,
                enabled = !exporting,
                onCommit = { wholeSpeed = it },
            )
        }

        // ---- The nine ADR-0012 sliders (the Phase 6 parameter set) ----
        SectionLabel(R.string.editor_section_adjust)
        for (spec in VIDEO_ADJUST_SLIDERS) {
            val committedValue = spec.get(params)
            val live = if (draft?.first == spec.label) draft!!.second else committedValue
            val labelText = stringResource(spec.label, live)
            val a11yName = stringResource(spec.a11yNameRes)
            Text(
                text = labelText,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = live.toFloat(),
                onValueChange = { value -> draft = Pair(spec.label, value.toInt()) },
                onValueChangeFinished = {
                    draft?.let { d ->
                        if (d.first == spec.label) {
                            params = spec.set(params, d.second)
                        }
                    }
                    draft = null
                },
                valueRange = -100f..100f,
                enabled = !exporting,
                modifier = Modifier.semantics {
                    contentDescription = a11yName
                    stateDescription = labelText
                },
            )
        }

        // ---- The frame-extract sheet ----
        SectionLabel(R.string.editor_video_section_frame)
        TimeSlider(
            label = stringResource(R.string.editor_video_frame_timestamp, formatSeconds(frameTimestampMs)),
            a11yName = stringResource(R.string.editor_a11y_video_frame_timestamp),
            value = frameTimestampMs,
            range = 0f..durationMs.toFloat(),
            enabled = !exporting,
            onCommit = { frameTimestampMs = it },
        )
        Row {
            FilterChip(
                selected = frameFormat == FrameFormat.JPEG,
                onClick = { frameFormat = FrameFormat.JPEG },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_video_frame_jpeg)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = frameFormat == FrameFormat.PNG,
                onClick = { frameFormat = FrameFormat.PNG },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_video_frame_png)) },
            )
        }
        Text(
            text = stringResource(R.string.editor_video_frame_note),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        Button(
            onClick = {
                frameMessage = null
                val plan = FrameExtractPlan(
                    durationMs = durationMs,
                    timestampMs = frameTimestampMs,
                    format = frameFormat,
                )
                when (val validation = FrameExtractPlanValidator.validate(plan)) {
                    is FrameExtractValidation.Invalid -> frameMessage = validation.reason
                    is FrameExtractValidation.Valid -> withStoragePermission {
                        frameBusy = true
                        activity.submitFrameExtract(validation.plan) { outcome ->
                            frameBusy = false
                            when (outcome) {
                                StillExportOutcome.Saved ->
                                    frameMessage = context.getString(R.string.editor_video_frame_saved)
                                is StillExportOutcome.Failed -> frameMessage = outcome.reason
                                StillExportOutcome.Canceled -> Unit
                            }
                        }
                    }
                }
            },
            enabled = !exporting,
        ) {
            Text(stringResource(R.string.editor_video_frame_extract))
        }

        // ---- The animation sheet ----
        SectionLabel(R.string.editor_video_section_animation)
        TimeSlider(
            label = stringResource(R.string.editor_video_animation_start, formatSeconds(animStartMs)),
            a11yName = stringResource(R.string.editor_a11y_video_animation_start),
            value = animStartMs,
            range = 0f..(animEndMs - TIME_STEP_MS).toFloat(),
            enabled = !exporting,
            onCommit = { animStartMs = it },
        )
        TimeSlider(
            label = stringResource(R.string.editor_video_animation_end, formatSeconds(animEndMs)),
            a11yName = stringResource(R.string.editor_a11y_video_animation_end),
            value = animEndMs,
            range = (animStartMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
            enabled = !exporting,
            onCommit = { animEndMs = it },
        )
        LabeledIntSlider(
            label = stringResource(R.string.editor_video_animation_width, animWidth.toInt()),
            a11yName = stringResource(R.string.editor_a11y_video_animation_width),
            value = animWidth,
            range = AnimationCaps.MIN_TARGET_WIDTH_PX.toFloat()..AnimationCaps.MAX_TARGET_WIDTH_PX.toFloat(),
            enabled = !exporting,
            onCommit = { animWidth = it },
        )
        LabeledIntSlider(
            label = stringResource(R.string.editor_video_animation_fps, animFps.toInt()),
            a11yName = stringResource(R.string.editor_a11y_video_animation_fps),
            value = animFps,
            range = AnimationCaps.MIN_FPS.toFloat()..AnimationCaps.MAX_FPS.toFloat(),
            enabled = !exporting,
            onCommit = { animFps = it },
        )
        Row {
            FilterChip(
                selected = animFormat == AnimationFormat.GIF,
                onClick = { animFormat = AnimationFormat.GIF },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_video_animation_gif)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = animFormat == AnimationFormat.WEBP,
                onClick = { animFormat = AnimationFormat.WEBP },
                enabled = !exporting,
                label = { Text(stringResource(R.string.editor_video_animation_webp)) },
            )
        }
        // The live preflight estimate --- the SAME pure math the export
        // runs, shown BEFORE any work (refusals included, honestly).
        val animHeight = if (activity.renderedWidthPx > 0 && activity.renderedHeightPx > 0) {
            (animWidth.toLong() * activity.renderedHeightPx / activity.renderedWidthPx).toInt()
        } else {
            0
        }
        val animPlan = AnimationExportPlan(
            durationMs = durationMs,
            startMs = animStartMs,
            endMs = animEndMs,
            targetWidthPx = animWidth.toInt(),
            fps = animFps.toInt(),
            format = animFormat,
        )
        val preflight = AnimationPreflight.preflight(
            plan = animPlan,
            sourceFrameRateFps = activity.sourceFrameRateFps,
            frameHeightPx = animHeight,
            webpLosslessSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        )
        when (preflight) {
            is AnimationPreflight.Outcome.Proceed -> Text(
                text = stringResource(
                    R.string.editor_video_animation_estimate,
                    preflight.frameCount,
                    preflight.estimatedBytes / (1024L * 1024L),
                ),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            is AnimationPreflight.Outcome.Refusal -> Text(
                text = preflight.reason,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Button(
            onClick = {
                animMessage = null
                when (preflight) {
                    // The refusal is already on screen; the button is a no-op
                    // on a refused plan (an honest no-op, not a hidden one).
                    is AnimationPreflight.Outcome.Refusal -> Unit
                    is AnimationPreflight.Outcome.Proceed -> withStoragePermission {
                        animBusy = true
                        animProgress = null
                        activity.submitAnimationExport(
                            plan = animPlan,
                            frameHeightPx = animHeight,
                            onProgress = { index, count -> animProgress = Pair(index, count) },
                            onFinished = { outcome ->
                                animBusy = false
                                animProgress = null
                                when (outcome) {
                                    StillExportOutcome.Saved ->
                                        animMessage = context.getString(R.string.editor_video_animation_saved)
                                    is StillExportOutcome.Failed -> animMessage = outcome.reason
                                    StillExportOutcome.Canceled -> Unit
                                }
                            },
                        )
                    }
                }
            },
            enabled = !exporting,
        ) {
            Text(stringResource(R.string.editor_video_animation_export))
        }

        // ---- The honest narration lines ----
        videoMessage?.let { message ->
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        frameMessage?.let { message ->
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        animMessage?.let { message ->
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }

        // ---- The export progress + cancellation ----
        videoExport?.let { progress ->
            val percent = (progress.percentHundredths / 100).coerceIn(0, 100)
            val progressLabel = stringResource(R.string.editor_video_exporting, percent)
            val progressA11y = stringResource(R.string.editor_a11y_video_export_progress, percent)
            Text(
                text = progressLabel,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { stateDescription = progressA11y },
            )
            LinearProgressIndicator(
                progress = { progress.percentHundredths / 10_000f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
            TextButton(onClick = { activity.cancelVideoExport() }) {
                Text(stringResource(R.string.editor_video_cancel_export))
            }
        }
        if (frameBusy || animBusy) {
            animProgress?.let { (index, count) ->
                val percent = if (count > 0) index * 100 / count else 0
                val frameProgressLabel = stringResource(R.string.editor_video_frame_progress, percent)
                Text(
                    text = frameProgressLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                LinearProgressIndicator(
                    progress = { if (count > 0) index.toFloat() / count.toFloat() else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                )
            }
            TextButton(onClick = { activity.cancelStillExport() }) {
                Text(stringResource(R.string.editor_video_cancel_export))
            }
        }
    }
}

/** A section heading (the photo surface's label style + the heading semantics). */
@Composable
private fun SectionLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(top = 12.dp)
            .semantics { heading() },
    )
}

/** One time-position slider snapped to [TIME_STEP_MS]; committed on release. */
@Composable
private fun TimeSlider(
    label: String,
    a11yName: String,
    value: Long,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onCommit: (Long) -> Unit,
) {
    var live by remember(value) { mutableStateOf(value.toFloat()) }
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = live,
        onValueChange = { live = it },
        onValueChangeFinished = {
            onCommit(((live / TIME_STEP_MS).toLong() * TIME_STEP_MS).coerceIn(
                range.start.toLong(),
                range.endInclusive.toLong(),
            ))
        },
        valueRange = range,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = a11yName
            stateDescription = label
        },
    )
}

/** One speed-factor slider over the 0.25x..2x range, snapped to 0.05 steps. */
@Composable
private fun SpeedSlider(
    label: String,
    a11yName: String,
    value: Float,
    enabled: Boolean,
    onCommit: (Float) -> Unit,
) {
    var live by remember(value) { mutableStateOf(value) }
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = live,
        onValueChange = { live = it },
        onValueChangeFinished = {
            val snapped = (Math.round(live * 20.0) / 20.0).toFloat()
                .coerceIn(
                    VideoEditPlanValidator.MIN_SPEED_FACTOR.toFloat(),
                    VideoEditPlanValidator.MAX_SPEED_FACTOR.toFloat(),
                )
            onCommit(snapped)
        },
        valueRange = VideoEditPlanValidator.MIN_SPEED_FACTOR.toFloat()..
            VideoEditPlanValidator.MAX_SPEED_FACTOR.toFloat(),
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = a11yName
            stateDescription = label
        },
    )
}

/** One plain integer slider (width, fps); committed on release. */
@Composable
private fun LabeledIntSlider(
    label: String,
    a11yName: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onCommit: (Float) -> Unit,
) {
    var live by remember(value) { mutableStateOf(value) }
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = live,
        onValueChange = { live = it },
        onValueChangeFinished = { onCommit(live) },
        valueRange = range,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = a11yName
            stateDescription = label
        },
    )
}
