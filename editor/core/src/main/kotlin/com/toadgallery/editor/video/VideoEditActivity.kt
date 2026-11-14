package com.toadgallery.editor.video

import android.content.ContentValues
import android.content.Intent
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
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.toadgallery.editor.contract.EditContract
import com.toadgallery.editor.contract.EditRequest
import com.toadgallery.editor.contract.EditRequestValidator
import com.toadgallery.editor.contract.MediaKind
import com.toadgallery.editor.contract.RawExtras
import com.toadgallery.editor.contract.RequestValidation
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * toadGALLERY Edit video surface: executes validated shared-module plans for ExoPlayer
 * preview and video/frame/animation export; always inserts a new "-edit" row, never overwrites.
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

        // Edge-to-edge like the photo surface: the dark SystemBarStyle keeps light icons
        // over the always-black canvas (auto style renders dark-on-black in light mode).
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
            // Fixed dark violet theme local to this surface; EditorTheme stays with the photo editor.
            VideoEditTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = VideoEditBg) {
                    VideoEditorScreen(activity = this@VideoEditActivity)
                }
            }
        }
    }

    // ---- Clip facts via MediaMetadataRetriever ----

    private class ClipFacts(
        val durationMs: Long,
        val audioTrackPresent: Boolean,
        val frameRateFps: Double?,
        val widthPx: Int,
        val heightPx: Int,
    )

    /**
     * One cheap metadata pass: duration, audio presence, best-effort frame rate, and the
     * rotation-corrected pixel size. Null on any failure (caller answers ERROR_UNSUPPORTED).
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
     * MediaMetadataRetriever has no frame-rate key at any API level; the honest source is the
     * video track's MediaFormat via MediaExtractor (no Uri overload, hence the resolver AFD).
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

    /**
     * Decodes [count] evenly spaced source-clip thumbnails (slice midpoints, OPTION_CLOSEST_SYNC).
     * Empty list on any failure or non-positive count; callbacks arrive on the main thread.
     */
    internal fun decodeThumbnails(count: Int, onDone: (List<Bitmap>) -> Unit) {
        if (count <= 0) {
            onDone(emptyList())
            return
        }
        exportScope.launch {
            val decoded = try {
                val retriever = openRetriever(request.sourceUri)
                val frames = mutableListOf<Bitmap>()
                if (retriever != null) {
                    try {
                        for (i in 0 until count) {
                            val atMs = ((i + 0.5) * clipDurationMs / count).toLong()
                            val frame = retriever.getFrameAtTime(
                                atMs * 1000,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            )
                            if (frame != null) frames.add(frame)
                        }
                    } finally {
                        retriever.release()
                    }
                }
                frames
            } catch (_: OutOfMemoryError) {
                emptyList()
            } catch (_: RuntimeException) {
                emptyList()
            }
            withContext(Dispatchers.Main) { onDone(decoded) }
        }
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
     * RESULT_OK with the saved-copy URI, then finish. Policy is STRIP_ALL: the re-encode
     * writes a fresh container, so no source metadata survives.
     */
    internal fun finishWithSavedCopy(outputUri: Uri) {
        val result = Intent().apply {
            putExtra(EditContract.EXTRA_OUTPUT_URI, outputUri.toString())
            putExtra(
                EditContract.EXTRA_METADATA_POLICY_APPLIED,
                com.toadgallery.editor.contract.MetadataPolicy.STRIP_ALL.wireName,
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
     * Submits a validated plan through the seam; must run on the main thread (media3 requires
     * its building looper). Progress and the terminal outcome arrive on the main thread.
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
     * Copies the render into a NEW MediaStore row (IS_PENDING on Q+, cancelled on a failed
     * write) and answers the gallery; the temp render is deleted either way.
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

    /** Extracts ONE source frame (no colour adjustments) and inserts it as a new JPEG/PNG entry. */
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
     * Animation export: decode + scale each derived timestamp, encode via the shared GIF encoder
     * or platform WebP + shared muxer, insert ONE entry; cancellation polled between frames.
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
                            // WEBP_LOSSLESS is API 30; below R use lossy WebP at quality 100.
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
     * Shared still-export spine: insert/finalize helpers plus pending-row recovery. A non-Saved
     * return (or throw) cancels the pending row, so nothing partial survives.
     */
    private suspend fun runStillExport(
        body: suspend (
            insertRow: (VideoOutputKind, String) -> Uri,
            finalize: (Uri) -> Unit,
        ) -> StillExportOutcome,
    ): StillExportOutcome {
        val api29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        var outputUri: Uri? = null
        // Positional (function-type values take no named args); insertRow keys the row, finalize clears IS_PENDING.
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
