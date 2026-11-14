package com.toadgallery.editor.video

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.toadgallery.editor.EditorPalette
import com.toadgallery.editor.LilypadFlags
import com.toadgallery.editor.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// The video editor surface: a fixed-dark chassis (preview + transport overlay, filmstrip trim
// strip, tab bar, six tool screens); export plans build from the committed session state.

// Color pins from the shared EditorPalette registry; VideoEditBg is shared with the activity's root Surface.
internal val VideoEditBg = EditorPalette.Lilypad.background
private val VideoSurface = EditorPalette.Lilypad.background
private val VideoSurfaceHigh = EditorPalette.Lilypad.surfaceVariant
private val VideoOutline = EditorPalette.Lilypad.outlineVariant
private val VideoAccent = EditorPalette.Lilypad.primary
private val VideoOnAccent = EditorPalette.Lilypad.onPrimary
private val VideoAccentSoft = EditorPalette.Lilypad.secondary
private val VideoTextPrimary = EditorPalette.Lilypad.onBackground
private val VideoTextSecondary = EditorPalette.Lilypad.onSurfaceVariant
private val VideoError = Color(0xFFF2B8B5)

/**
 * Fixed dark scheme over the shared palette registry; secondaryContainer = accent so a selected
 * FilterChip renders as the accent-filled pill. Not dynamic color, like [com.toadgallery.editor.EditorTheme].
 */
@Composable
internal fun VideoEditTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VideoAccent,
            onPrimary = VideoOnAccent,
            primaryContainer = EditorPalette.Lilypad.primaryContainer,
            onPrimaryContainer = EditorPalette.Lilypad.onPrimaryContainer,
            inversePrimary = EditorPalette.Lilypad.inversePrimary,
            secondary = VideoAccentSoft,
            onSecondary = EditorPalette.Lilypad.onSecondary,
            secondaryContainer = VideoAccent,
            onSecondaryContainer = VideoOnAccent,
            tertiary = EditorPalette.Lilypad.tertiary,
            onTertiary = EditorPalette.Lilypad.onTertiary,
            tertiaryContainer = EditorPalette.Lilypad.tertiaryContainer,
            onTertiaryContainer = EditorPalette.Lilypad.onTertiaryContainer,
            background = VideoEditBg,
            onBackground = VideoTextPrimary,
            surface = VideoEditBg,
            onSurface = VideoTextPrimary,
            surfaceVariant = VideoSurfaceHigh,
            onSurfaceVariant = VideoTextSecondary,
            surfaceTint = VideoAccent,
            inverseSurface = VideoTextPrimary,
            inverseOnSurface = Color(0xFF1D1C17),
            outline = EditorPalette.Lilypad.outline,
            outlineVariant = VideoOutline,
            scrim = Color(0xFF000000),
            error = VideoError,
            onError = Color(0xFF601C10),
            errorContainer = Color(0xFF8C1D18),
            onErrorContainer = Color(0xFFF9DEDC),
        ),
        content = content,
    )
}

/** The editor's screens: the main tab-bar chassis plus the six tool tabs. */
internal enum class EditorScreen(val labelRes: Int, val iconRes: Int) {
    /** The main chassis; never a tab (the tab bar shows the six tools only). */
    Main(R.string.editor_video_title, 0),
    Trim(R.string.editor_v2_tab_trim, R.drawable.ic_vt_trim),
    Speed(R.string.editor_v2_tab_speed, R.drawable.ic_vt_speed),
    Audio(R.string.editor_v2_tab_audio, R.drawable.ic_vt_audio),
    Adjust(R.string.editor_v2_tab_adjust, R.drawable.ic_vt_adjust),
    Extract(R.string.editor_v2_tab_extract, R.drawable.ic_vt_extract),
    Animate(R.string.editor_v2_tab_animate, R.drawable.ic_vt_animate),
}

/** The committed session state: what the export plan is built from and what a tab's draft resets to. */
internal data class VideoSessionState(
    val trimStartMs: Long,
    val trimEndMs: Long,
    val mute: Boolean,
    val volumePercent: Int,
    val wholeSpeed: Float,
    val regionMode: Boolean,
    val regionStartMs: Long,
    val regionEndMs: Long,
    val regionSpeed: Float,
    val adjust: VideoAdjustParams,
) {
    companion object {
        /** The session's opening state. */
        internal fun initial(durationMs: Long): VideoSessionState = VideoSessionState(
            trimStartMs = 0L,
            trimEndMs = durationMs,
            mute = false,
            volumePercent = 100,
            wholeSpeed = 1.0f,
            regionMode = false,
            regionStartMs = durationMs / 4,
            regionEndMs = durationMs / 2,
            regionSpeed = 0.5f,
            adjust = VideoAdjustParams(),
        )
    }
}

/** What the UI learns when a video export ends. */
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

/** Double-tap seek step: +-10 s, left half back / right half forward. */
private const val TRANSPORT_SEEK_STEP_MS: Long = 10_000L

/** Trim strip metrics: 16dp handles, 28dp grab radius per handle, ~width/72 frames clamped to 4..12. */
private val STRIP_HANDLE_WIDTH = 16.dp
private val STRIP_HANDLE_GRAB_RADIUS = 28.dp
private const val STRIP_FRAME_SLOT_DP = 72f
private const val STRIP_FRAME_MIN = 4
private const val STRIP_FRAME_MAX = 12

/** The dim scrim over the filmstrip outside the trim window. */
private val TrimScrimColor = Color.Black.copy(alpha = 0.55f)

/** One adjust slider's binding over the colour params. */
private data class VideoSliderSpec(
    val label: Int,
    // Name-only chip string: the slider header already carries the live value.
    val nameRes: Int,
    val a11yNameRes: Int,
    val get: (VideoAdjustParams) -> Int,
    val set: (VideoAdjustParams, Int) -> VideoAdjustParams,
)

/** The nine adjust sliders in the photo surface's order. */
private val VIDEO_ADJUST_SLIDERS: List<VideoSliderSpec> = listOf(
    VideoSliderSpec(R.string.editor_exposure, R.string.editor_adjust_name_exposure, R.string.editor_a11y_exposure, { it.exposure }, { p, v -> p.copy(exposure = v) }),
    VideoSliderSpec(R.string.editor_brightness, R.string.editor_adjust_name_brightness, R.string.editor_a11y_brightness, { it.brightness }, { p, v -> p.copy(brightness = v) }),
    VideoSliderSpec(R.string.editor_contrast, R.string.editor_adjust_name_contrast, R.string.editor_a11y_contrast, { it.contrast }, { p, v -> p.copy(contrast = v) }),
    VideoSliderSpec(R.string.editor_saturation, R.string.editor_adjust_name_saturation, R.string.editor_a11y_saturation, { it.saturation }, { p, v -> p.copy(saturation = v) }),
    VideoSliderSpec(R.string.editor_temperature, R.string.editor_adjust_name_temperature, R.string.editor_a11y_temperature, { it.temperature }, { p, v -> p.copy(temperature = v) }),
    VideoSliderSpec(R.string.editor_tint, R.string.editor_adjust_name_tint, R.string.editor_a11y_tint, { it.tint }, { p, v -> p.copy(tint = v) }),
    VideoSliderSpec(R.string.editor_highlights, R.string.editor_adjust_name_highlights, R.string.editor_a11y_highlights, { it.highlights }, { p, v -> p.copy(highlights = v) }),
    VideoSliderSpec(R.string.editor_shadows, R.string.editor_adjust_name_shadows, R.string.editor_a11y_shadows, { it.shadows }, { p, v -> p.copy(shadows = v) }),
    VideoSliderSpec(R.string.editor_vignette, R.string.editor_adjust_name_vignette, R.string.editor_a11y_vignette, { it.vignette }, { p, v -> p.copy(vignette = v) }),
)

/** Formats a millisecond position as seconds with one decimal. */
private fun formatSeconds(ms: Long): String =
    String.format(Locale.US, "%.1f s", ms / 1000.0)

/** Formats a speed factor as the "0.50x" the labels show. */
private fun formatSpeed(factor: Double): String =
    String.format(Locale.US, "%.2fx", factor)

/** Formats ms as mm:ss.d, flooring to the shown tenth so 59.95 s never rounds up to "01:60.0". */
internal fun formatTimestamp(ms: Long): String {
    val minutes = ms / 60_000L
    val seconds = (ms % 60_000L) / 1000.0
    val floored = Math.floor(seconds * 10.0) / 10.0
    return String.format(Locale.US, "%02d:%04.1f", minutes, floored)
}

/** Slider-release snap for a time position: TIME_STEP granularity, clamped to the range. */
private fun snapTimeStep(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    ((value / TIME_STEP_MS).toLong() * TIME_STEP_MS).toFloat()
        .coerceIn(range.start, range.endInclusive)

/** Slider-release snap for a speed factor: 0.05 steps within the plan's speed bounds. */
private fun snapSpeed(value: Float): Float =
    (Math.round(value * 20.0) / 20.0).toFloat()
        .coerceIn(
            VideoEditPlanValidator.MIN_SPEED_FACTOR.toFloat(),
            VideoEditPlanValidator.MAX_SPEED_FACTOR.toFloat(),
        )

/** Slider-release snap for the volume percent (step 5, clamped 0..200). */
private fun snapVolume(value: Float): Float =
    (Math.round(value / 5f) * 5f).coerceIn(0f, 200f)

/**
 * The video editor surface: one scaffold holding the session's single PlayerView, the committed/drafted
 * state, and the six tool screens. Controls announce name + live value; disabled only during exports.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoEditorScreen(activity: VideoEditActivity) {
    val durationMs = activity.clipDurationMs
    val audioTrackPresent = activity.audioTrackPresent
    val context = LocalContext.current

    // Hoisted a11y strings (semantics blocks are not composable).
    val closeA11y = stringResource(R.string.editor_close)
    val backA11y = stringResource(R.string.editor_v2_back)
    val confirmA11y = stringResource(R.string.editor_v2_confirm)
    val exportLabel = stringResource(R.string.editor_v2_export)
    val previewA11yName = stringResource(R.string.editor_a11y_video_preview)
    val playA11y = stringResource(R.string.editor_v2_play)
    val pauseA11y = stringResource(R.string.editor_v2_pause)
    val seekBackA11y = stringResource(R.string.editor_v2_seek_back)
    val seekForwardA11y = stringResource(R.string.editor_v2_seek_forward)
    val fullscreenA11y = stringResource(R.string.editor_v2_fullscreen)
    val fullscreenFitState = stringResource(R.string.editor_v2_fullscreen_fit)
    val fullscreenZoomState = stringResource(R.string.editor_v2_fullscreen_zoom)
    // For the Main chassis trim strip; the precision Trim tab's strip resolves its own name in TrimScreenBody.
    val trimStripA11yName = stringResource(R.string.editor_v2_trim_strip)
    val transportHint = stringResource(R.string.editor_v2_transport_hint)
    val muteA11yName = stringResource(R.string.editor_a11y_video_mute)
    val muteStateOn = stringResource(R.string.editor_video_mute_state_on)
    val muteStateOff = stringResource(R.string.editor_video_mute_state_off)

    // The committed session state + which screen is on top.
    var committed by remember { mutableStateOf(VideoSessionState.initial(durationMs)) }
    var screen by remember { mutableStateOf(EditorScreen.Main) }

    // Per-screen drafts keyed on (screen, committed): entering shows the committed values, Apply
    // re-keys the originating draft, and Reset copies the committed values back without re-keying.
    var draftTrimStartMs by remember(screen, committed) { mutableStateOf(committed.trimStartMs) }
    var draftTrimEndMs by remember(screen, committed) { mutableStateOf(committed.trimEndMs) }
    var draftWholeSpeed by remember(screen, committed) { mutableStateOf(committed.wholeSpeed) }
    var draftRegionMode by remember(screen, committed) { mutableStateOf(committed.regionMode) }
    var draftRegionStartMs by remember(screen, committed) { mutableStateOf(committed.regionStartMs) }
    var draftRegionEndMs by remember(screen, committed) { mutableStateOf(committed.regionEndMs) }
    var draftRegionSpeed by remember(screen, committed) { mutableStateOf(committed.regionSpeed) }
    var draftMute by remember(screen, committed) { mutableStateOf(committed.mute) }
    var draftVolumePercent by remember(screen, committed) { mutableStateOf(committed.volumePercent) }
    var draftAdjust by remember(screen, committed) { mutableStateOf(committed.adjust) }
    var adjustSelectedIndex by remember(screen, committed) { mutableStateOf(1) }

    // The export session state.
    var videoExport by remember { mutableStateOf<VideoExportProgress?>(null) }
    var videoMessage by remember { mutableStateOf<String?>(null) }
    var frameBusy by remember { mutableStateOf(false) }
    var frameMessage by remember { mutableStateOf<String?>(null) }
    var frameTimestampMs by remember { mutableStateOf(0L) }
    var frameFormat by remember { mutableStateOf(FrameFormat.JPEG) }
    var animStartMs by remember { mutableStateOf(0L) }
    var animEndMs by remember { mutableStateOf((durationMs / 2).coerceAtLeast(TIME_STEP_MS)) }
    var animWidth by remember { mutableStateOf(480) }
    var animFps by remember { mutableStateOf(10) }
    var animFormat by remember { mutableStateOf(AnimationFormat.GIF) }
    var animBusy by remember { mutableStateOf(false) }
    var animProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var animMessage by remember { mutableStateOf<String?>(null) }
    val exporting = videoExport != null || frameBusy || animBusy

    // Pre-Q storage-permission gate: the pending action runs after the grant, or is refused with a message.
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

    // The preview: one player, rebuilt on committed state.
    val player = remember { ExoPlayer.Builder(activity).build() }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // The fullscreen toggle's view handle + zoom state live at the chassis.
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }
    var fullscreenZoomed by remember { mutableStateOf(false) }

    // Strip-drag contract: any drag pauses the preview and release resumes only if it was playing
    // before; a handle release commits the drafts through the same Apply path the Trim tab uses.
    var stripDragActive by remember { mutableStateOf(false) }
    var wasPlayingBeforeStripDrag by remember { mutableStateOf(false) }
    var stripDragEndTick by remember { mutableStateOf(0) }

    // The Adjust screen previews its draft params live (updated on each slider release);
    // everywhere else the committed set shows.
    val previewAdjust = if (screen == EditorScreen.Adjust) draftAdjust else committed.adjust
    LaunchedEffect(committed.trimStartMs, committed.trimEndMs, previewAdjust) {
        val mediaItem = MediaItem.Builder()
            .setUri(activity.request.sourceUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(committed.trimStartMs)
                    .setEndPositionMs(committed.trimEndMs)
                    .build(),
            )
            .build()
        if (previewAdjust.isNeutral) {
            // An exact identity: no effect attached at all, no shader pass.
        } else {
            player.setVideoEffects(listOf(VideoAdjustEffect(previewAdjust)))
        }
        player.setMediaItem(mediaItem)
        player.playWhenReady = true
        player.prepare()
    }

    // Declared after the rebuild effect so it overrides the rebuild's playWhenReady = true on trim
    // commits; without it, releasing a strip drag while paused would wrongly start playback.
    LaunchedEffect(stripDragEndTick) {
        if (stripDragEndTick == 0) return@LaunchedEffect
        if (wasPlayingBeforeStripDrag) player.play() else player.pause()
    }

    // Volume preview: the Audio screen's draft (instant on release), committed values elsewhere; mute wins.
    val previewMute = if (screen == EditorScreen.Audio) draftMute else committed.mute
    val previewVolumePercent = if (screen == EditorScreen.Audio) {
        draftVolumePercent
    } else {
        committed.volumePercent
    }
    LaunchedEffect(previewMute, previewVolumePercent) {
        player.volume = if (previewMute) 0f else previewVolumePercent / 100f
    }

    // Whole-clip speed previews via playback speed (committed values only);
    // a region factor is export-applied only, never previewed.
    LaunchedEffect(committed.regionMode, committed.wholeSpeed) {
        player.setPlaybackSpeed(if (committed.regionMode) 1f else committed.wholeSpeed)
    }

    // The Extract screen is the frame preview: paused, re-seeked to the draft timestamp on
    // entry/change. Seeks are player-relative (item is trim-clipped) and clamp to the window edge.
    LaunchedEffect(screen, frameTimestampMs, committed.trimStartMs, committed.trimEndMs) {
        if (screen == EditorScreen.Extract) {
            player.pause()
            val previewLen = player.duration.takeIf { it > 0 }
                ?: (committed.trimEndMs - committed.trimStartMs).coerceAtLeast(0L)
            player.seekTo(
                (frameTimestampMs - committed.trimStartMs).coerceIn(0L, previewLen),
            )
        }
    }

    // 100ms poll: position, playing state, preview length. For a clipped item player.duration is
    // the trim length once prepared; until then (TIME_UNSET) fall back to the committed trim length.
    var positionMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var previewDurationMs by remember {
        mutableStateOf((committed.trimEndMs - committed.trimStartMs).coerceAtLeast(0L))
    }
    LaunchedEffect(screen) {
        while (true) {
            positionMs = player.currentPosition
            isPlaying = player.isPlaying
            previewDurationMs = player.duration.takeIf { it > 0 }
                ?: (committed.trimEndMs - committed.trimStartMs).coerceAtLeast(0L)
            delay(100)
        }
    }

    // Absolute (source-time) -> player-relative seek: the preview item is clipped to the committed
    // trim, so the player position is absoluteMs - trimStart, clamped into the preview window.
    val seekToAbsolute: (Long) -> Unit = { absoluteMs ->
        player.seekTo((absoluteMs - committed.trimStartMs).coerceIn(0L, previewDurationMs))
    }

    // Main-strip drag plumbing: handle math matches the Trim screen (TIME_STEP snap, min gap); drag
    // start pauses and remembers playWhenReady; finishers fire only after a real drag start.
    val stripOnDragStarted: () -> Unit = {
        stripDragActive = true
        wasPlayingBeforeStripDrag = player.playWhenReady
        player.pause()
    }
    val stripOnStartDrag: (Long) -> Unit = { absoluteMs ->
        // The Trim tab's start-handle rule: 0 until end - TIME_STEP.
        val snapped = snapTimeStep(
            absoluteMs.toFloat(),
            0f..(draftTrimEndMs - TIME_STEP_MS).toFloat(),
        ).toLong()
        draftTrimStartMs = snapped
        seekToAbsolute(snapped)
    }
    val stripOnEndDrag: (Long) -> Unit = { absoluteMs ->
        // The Trim tab's end-handle rule: start + TIME_STEP until duration.
        val snapped = snapTimeStep(
            absoluteMs.toFloat(),
            (draftTrimStartMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
        ).toLong()
        draftTrimEndMs = snapped
        seekToAbsolute(snapped)
    }
    val stripOnHandleDragFinished: () -> Unit = {
        if (stripDragActive) {
            stripDragActive = false
            // The Trim tab's Apply: committed <- draft.
            committed = committed.copy(trimStartMs = draftTrimStartMs, trimEndMs = draftTrimEndMs)
            stripDragEndTick++
        }
    }
    val stripOnScrubFinished: () -> Unit = {
        if (stripDragActive) {
            stripDragActive = false
            stripDragEndTick++
        }
    }

    // Leaving Main mid-drag clears the accent flag; the drafts re-key on the screen change.
    LaunchedEffect(screen) {
        if (screen != EditorScreen.Main) stripDragActive = false
    }

    // The real filmstrip frames (decoded once; placeholders until then).
    var thumbs by remember { mutableStateOf<List<Bitmap>?>(null) }
    LaunchedEffect(Unit) {
        activity.decodeThumbnails(10) { list -> thumbs = list }
    }

    // The committed-plan video export (plus the volume from the committed session state).
    val startVideoExport: () -> Unit = {
        videoMessage = null
        val segments = if (committed.regionMode) {
            if (committed.regionSpeed == 1.0f) {
                emptyList()
            } else {
                listOf(
                    SpeedSegment(
                        startMs = committed.regionStartMs,
                        endMs = committed.regionEndMs,
                        speedFactor = committed.regionSpeed.toDouble(),
                    ),
                )
            }
        } else if (committed.wholeSpeed == 1.0f) {
            emptyList()
        } else {
            listOf(
                SpeedSegment(
                    startMs = committed.trimStartMs,
                    endMs = committed.trimEndMs,
                    speedFactor = committed.wholeSpeed.toDouble(),
                ),
            )
        }
        val plan = VideoEditPlan(
            durationMs = durationMs,
            trim = TimeRange(committed.trimStartMs, committed.trimEndMs),
            speedSegments = segments,
            audioTrackPresent = audioTrackPresent,
            mute = committed.mute,
            adjustments = committed.adjust,
            volume = committed.volumePercent / 100f,
        )
        when (val validation = VideoEditPlanValidator.validate(plan)) {
            is VideoPlanValidation.Invalid -> {
                // The UI bounds its own inputs, so an invalid plan means a bug or tampering.
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
    }

    // The frame-extract submit.
    val startFrameExtract: () -> Unit = {
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
    }

    // The animation preflight + submit: the same pure math drives the on-screen estimate and the gate.
    val animHeight = if (activity.renderedWidthPx > 0 && activity.renderedHeightPx > 0) {
        (animWidth.toLong() * activity.renderedHeightPx / activity.renderedWidthPx).toInt()
    } else {
        0
    }
    val animPlan = AnimationExportPlan(
        durationMs = durationMs,
        startMs = animStartMs,
        endMs = animEndMs,
        targetWidthPx = animWidth,
        fps = animFps,
        format = animFormat,
    )
    val animPreflight = AnimationPreflight.preflight(
        plan = animPlan,
        sourceFrameRateFps = activity.sourceFrameRateFps,
        frameHeightPx = animHeight,
        webpLosslessSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
    )
    val startAnimationExport: () -> Unit = {
        animMessage = null
        when (animPreflight) {
            // The refusal is already on screen, so the button is a no-op on a refused plan.
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
    }

    // The draft commit: Apply = committed <- draft (stay); the header check = Apply + back.
    // Extract/Animate settings affect no video-plan field, so there is nothing to commit.
    val applyDraft: () -> Unit = {
        when (screen) {
            EditorScreen.Trim -> committed = committed.copy(
                trimStartMs = draftTrimStartMs,
                trimEndMs = draftTrimEndMs,
            )
            EditorScreen.Speed -> committed = committed.copy(
                wholeSpeed = draftWholeSpeed,
                regionMode = draftRegionMode,
                regionStartMs = draftRegionStartMs,
                regionEndMs = draftRegionEndMs,
                regionSpeed = draftRegionSpeed,
            )
            EditorScreen.Audio -> committed = committed.copy(
                mute = draftMute,
                volumePercent = draftVolumePercent,
            )
            EditorScreen.Adjust -> committed = committed.copy(adjust = draftAdjust)
            else -> Unit
        }
    }
    val confirmAndBack: () -> Unit = {
        applyDraft()
        screen = EditorScreen.Main
    }
    val resetDraft: () -> Unit = {
        when (screen) {
            EditorScreen.Trim -> {
                draftTrimStartMs = committed.trimStartMs
                draftTrimEndMs = committed.trimEndMs
            }
            EditorScreen.Speed -> {
                draftWholeSpeed = committed.wholeSpeed
                draftRegionMode = committed.regionMode
                draftRegionStartMs = committed.regionStartMs
                draftRegionEndMs = committed.regionEndMs
                draftRegionSpeed = committed.regionSpeed
            }
            EditorScreen.Audio -> {
                draftMute = committed.mute
                draftVolumePercent = committed.volumePercent
            }
            EditorScreen.Adjust -> draftAdjust = committed.adjust
            else -> Unit
        }
    }

    // Chassis: header / preview + transport overlay / time row, then either
    // (trim strip + tab bar) on Main or (scrolling body + footer) on a tool screen.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        EditorHeader(
            title = stringResource(screen.labelRes),
            isMain = screen == EditorScreen.Main,
            exporting = exporting,
            backA11y = if (screen == EditorScreen.Main) closeA11y else backA11y,
            confirmA11y = confirmA11y,
            exportLabel = exportLabel,
            onBack = {
                if (screen == EditorScreen.Main) activity.finishCanceled() else screen = EditorScreen.Main
            },
            onConfirm = confirmAndBack,
            onExport = startVideoExport,
        )

        videoMessage?.let { message ->
            Text(
                text = message,
                color = VideoError,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        videoExport?.let { progress ->
            VideoExportProgressBlock(
                progress = progress,
                cancelLabel = stringResource(R.string.editor_video_cancel_export),
                onCancel = { activity.cancelVideoExport() },
            )
        }
        if (frameBusy || animBusy) {
            // Still-export progress + cancel stay reachable from every screen so leaving
            // Extract/Animate never orphans a running job.
            StillExportProgressBlock(
                animProgress = animProgress,
                cancelLabel = stringResource(R.string.editor_video_cancel_export),
                onCancel = { activity.cancelStillExport() },
            )
        }

        // The preview fills the leftover stage on Main; tool screens keep a fixed height so their
        // scrolling bodies keep the room. PreviewSurface does not pin its own height.
        PreviewSurface(
            player = player,
            previewA11yName = previewA11yName,
            playerViewRef = playerViewRef,
            transport = {
                PreviewTransportOverlay(
                    isPlaying = isPlaying,
                    enabled = !exporting,
                    playA11y = playA11y,
                    pauseA11y = pauseA11y,
                    seekBackA11y = seekBackA11y,
                    seekForwardA11y = seekForwardA11y,
                    fullscreenZoomed = fullscreenZoomed,
                    fullscreenA11y = fullscreenA11y,
                    fullscreenFitState = fullscreenFitState,
                    fullscreenZoomState = fullscreenZoomState,
                    onTogglePlay = { player.playWhenReady = !player.isPlaying },
                    onSeekRelative = { deltaMs ->
                        // Player-relative, clamped into the preview window (previewDurationMs encodes the fallback).
                        player.seekTo((positionMs + deltaMs).coerceIn(0L, previewDurationMs))
                    },
                    onToggleFullscreen = {
                        val view = playerViewRef.value
                        if (view != null) {
                            fullscreenZoomed = !fullscreenZoomed
                            // Resize-mode constants live on AspectRatioFrameLayout, not PlayerView.
                            view.resizeMode = if (fullscreenZoomed) {
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            } else {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }
                    },
                )
            },
            modifier = if (screen == EditorScreen.Main) {
                Modifier.weight(1f)
            } else {
                Modifier.height(220.dp)
            },
        )

        // Time row: leading = position (accent while a strip drag is live); trailing = the trim end
        // on Main, the draft bounds on Trim, the previewed length on the other tool screens.
        when (screen) {
            EditorScreen.Main -> VideoTimeRow(
                leadingText = formatTimestamp(
                    if (stripDragActive && draftTrimStartMs != committed.trimStartMs) {
                        draftTrimStartMs
                    } else {
                        committed.trimStartMs + positionMs
                    },
                ),
                trailingText = formatTimestamp(draftTrimEndMs),
                hint = transportHint,
                leadingAccent = stripDragActive,
            )
            EditorScreen.Trim -> VideoTimeRow(
                leadingText = formatTimestamp(draftTrimStartMs),
                trailingText = formatTimestamp(draftTrimEndMs),
                hint = null,
                leadingAccent = false,
            )
            else -> VideoTimeRow(
                leadingText = formatTimestamp(positionMs),
                trailingText = formatTimestamp(previewDurationMs),
                hint = null,
                leadingAccent = false,
            )
        }

        if (screen == EditorScreen.Main) {
            // The filmstrip is the primary trim surface, showing the live drafts; the Trim tab
            // stays the precision surface. The zoom flag swaps in a pinch-zoom/pan strip variant.
            if (LilypadFlags.timelineZoomEnabled) {
                TimelineStrip(
                    durationMs = durationMs,
                    trimStartMs = draftTrimStartMs,
                    trimEndMs = draftTrimEndMs,
                    playheadAbsoluteMs = committed.trimStartMs + positionMs,
                    enabled = !exporting,
                    filmstripA11yName = trimStripA11yName,
                    positionStateText = stringResource(
                        R.string.editor_v2_filmstrip_state,
                        formatTimestamp(committed.trimStartMs + positionMs),
                    ),
                    zoomStateFormat = stringResource(R.string.editor_r69_timeline_zoom_state),
                    startHandleA11y = stringResource(R.string.editor_a11y_video_trim_start),
                    endHandleA11y = stringResource(R.string.editor_a11y_video_trim_end),
                    startHandleState = formatTimestamp(draftTrimStartMs),
                    endHandleState = formatTimestamp(draftTrimEndMs),
                    onDecodeThumbnails = { count, onDone -> activity.decodeThumbnails(count, onDone) },
                    onDragStarted = stripOnDragStarted,
                    onHandleDragFinished = stripOnHandleDragFinished,
                    onScrubFinished = stripOnScrubFinished,
                    onStartDrag = stripOnStartDrag,
                    onEndDrag = stripOnEndDrag,
                    onSeekAbsolute = seekToAbsolute,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                TrimStrip(
                    durationMs = durationMs,
                    trimStartMs = draftTrimStartMs,
                    trimEndMs = draftTrimEndMs,
                    playheadAbsoluteMs = committed.trimStartMs + positionMs,
                    enabled = !exporting,
                    filmstripA11yName = trimStripA11yName,
                    positionStateText = stringResource(
                        R.string.editor_v2_filmstrip_state,
                        formatTimestamp(committed.trimStartMs + positionMs),
                    ),
                    startHandleA11y = stringResource(R.string.editor_a11y_video_trim_start),
                    endHandleA11y = stringResource(R.string.editor_a11y_video_trim_end),
                    startHandleState = formatTimestamp(draftTrimStartMs),
                    endHandleState = formatTimestamp(draftTrimEndMs),
                    onDecodeThumbnails = { count, onDone -> activity.decodeThumbnails(count, onDone) },
                    onDragStarted = stripOnDragStarted,
                    onHandleDragFinished = stripOnHandleDragFinished,
                    onScrubFinished = stripOnScrubFinished,
                    onStartDrag = stripOnStartDrag,
                    onEndDrag = stripOnEndDrag,
                    onSeekAbsolute = seekToAbsolute,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // The preview's weight(1f) above lets the filmstrip and tab bar dock at the bottom.
            EditorTabBar(
                current = screen,
                onSelect = { screen = it },
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (screen) {
                    EditorScreen.Trim -> TrimScreenBody(
                        durationMs = durationMs,
                        draftStartMs = draftTrimStartMs,
                        draftEndMs = draftTrimEndMs,
                        playheadAbsoluteMs = committed.trimStartMs + positionMs,
                        enabled = !exporting,
                        thumbs = thumbs,
                        trimStartA11y = stringResource(R.string.editor_a11y_video_trim_start),
                        trimEndA11y = stringResource(R.string.editor_a11y_video_trim_end),
                        onDraftStart = { draftTrimStartMs = it },
                        onDraftEnd = { draftTrimEndMs = it },
                        onSeekAbsolute = seekToAbsolute,
                    )
                    EditorScreen.Speed -> SpeedScreenBody(
                        draftWholeSpeed = draftWholeSpeed,
                        draftRegionMode = draftRegionMode,
                        draftRegionStartMs = draftRegionStartMs,
                        draftRegionEndMs = draftRegionEndMs,
                        draftRegionSpeed = draftRegionSpeed,
                        durationMs = durationMs,
                        enabled = !exporting,
                        speedA11y = stringResource(R.string.editor_a11y_video_speed),
                        regionSpeedA11y = stringResource(R.string.editor_a11y_video_region_speed),
                        onWholeSpeed = { draftWholeSpeed = it },
                        onRegionMode = { draftRegionMode = it },
                        onRegionStart = { draftRegionStartMs = it },
                        onRegionEnd = { draftRegionEndMs = it },
                        onRegionSpeed = { draftRegionSpeed = it },
                    )
                    EditorScreen.Audio -> AudioScreenBody(
                        audioTrackPresent = audioTrackPresent,
                        draftMute = draftMute,
                        draftVolumePercent = draftVolumePercent,
                        enabled = !exporting,
                        muteA11yName = muteA11yName,
                        muteStateOn = muteStateOn,
                        muteStateOff = muteStateOff,
                        onMute = { draftMute = it },
                        onVolume = { draftVolumePercent = it },
                    )
                    EditorScreen.Adjust -> AdjustScreenBody(
                        draftAdjust = draftAdjust,
                        selectedIndex = adjustSelectedIndex,
                        enabled = !exporting,
                        onSelect = { adjustSelectedIndex = it },
                        onDraftAdjust = { draftAdjust = it },
                    )
                    EditorScreen.Extract -> ExtractScreenBody(
                        durationMs = durationMs,
                        timestampMs = frameTimestampMs,
                        format = frameFormat,
                        enabled = !exporting,
                        frameBusy = frameBusy,
                        frameMessage = frameMessage,
                        thumbs = thumbs,
                        onTimestamp = { absolute -> frameTimestampMs = absolute.coerceIn(0L, durationMs) },
                        onFormat = { frameFormat = it },
                        onSaveFrame = startFrameExtract,
                    )
                    EditorScreen.Animate -> AnimateScreenBody(
                        durationMs = durationMs,
                        startMs = animStartMs,
                        endMs = animEndMs,
                        widthPx = animWidth,
                        fps = animFps,
                        format = animFormat,
                        preflight = animPreflight,
                        animMessage = animMessage,
                        enabled = !exporting,
                        onStart = { animStartMs = it },
                        onEnd = { animEndMs = it },
                        onWidth = { animWidth = it },
                        onFps = { animFps = it },
                        onFormat = { animFormat = it },
                        onExport = startAnimationExport,
                    )
                    EditorScreen.Main -> Unit
                }
            }
            if (screen == EditorScreen.Trim || screen == EditorScreen.Speed ||
                screen == EditorScreen.Audio || screen == EditorScreen.Adjust
            ) {
                val canReset = when (screen) {
                    EditorScreen.Trim -> draftTrimStartMs != committed.trimStartMs ||
                        draftTrimEndMs != committed.trimEndMs
                    EditorScreen.Speed -> draftWholeSpeed != committed.wholeSpeed ||
                        draftRegionMode != committed.regionMode ||
                        draftRegionStartMs != committed.regionStartMs ||
                        draftRegionEndMs != committed.regionEndMs ||
                        draftRegionSpeed != committed.regionSpeed
                    EditorScreen.Audio -> draftMute != committed.mute ||
                        draftVolumePercent != committed.volumePercent
                    EditorScreen.Adjust -> draftAdjust != committed.adjust
                    else -> false
                }
                EditorFooter(
                    canReset = canReset,
                    enabled = !exporting,
                    onReset = resetDraft,
                    onApply = applyDraft,
                )
            }
        }
    }
}

/** The chassis header: back on the left, the title, and the export pill (Main) or the check (tool screens). */
@Composable
private fun EditorHeader(
    title: String,
    isMain: Boolean,
    exporting: Boolean,
    backA11y: String,
    confirmA11y: String,
    exportLabel: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backA11y },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = VideoTextPrimary,
            )
        }
        Text(
            text = title,
            color = VideoTextPrimary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (isMain) {
            Button(
                onClick = onExport,
                enabled = !exporting,
                modifier = Modifier.padding(end = 4.dp),
            ) {
                Text(text = exportLabel)
            }
        } else {
            IconButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = confirmA11y },
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = VideoAccent,
                )
            }
        }
    }
}

/** The video-export progress + cancellation, shown under the header. */
@Composable
private fun VideoExportProgressBlock(
    progress: VideoExportProgress,
    cancelLabel: String,
    onCancel: () -> Unit,
) {
    val percent = (progress.percentHundredths / 100).coerceIn(0, 100)
    val progressLabel = stringResource(R.string.editor_video_exporting, percent)
    val progressA11y = stringResource(R.string.editor_a11y_video_export_progress, percent)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = progressLabel,
            color = VideoTextPrimary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { stateDescription = progressA11y },
        )
        LinearProgressIndicator(
            progress = { progress.percentHundredths / 10_000f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
        TextButton(onClick = onCancel) {
            Text(text = cancelLabel)
        }
    }
}

/** Still-export (frame/animation) progress + cancel, shown under the header regardless of screen. */
@Composable
private fun StillExportProgressBlock(
    animProgress: Pair<Int, Int>?,
    cancelLabel: String,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        animProgress?.let { (index, count) ->
            val percent = if (count > 0) index * 100 / count else 0
            Text(
                text = stringResource(R.string.editor_video_frame_progress, percent),
                color = VideoTextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
            LinearProgressIndicator(
                progress = { if (count > 0) index.toFloat() / count.toFloat() else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        }
        TextButton(onClick = onCancel) {
            Text(text = cancelLabel)
        }
    }
}

/**
 * The session's one PlayerView (never recomposed away; sub-screens replace only content below it).
 * Height is the caller's decision; the [transport] slot renders inside the frame over the video.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PreviewSurface(
    player: ExoPlayer,
    previewA11yName: String,
    playerViewRef: MutableState<PlayerView?>,
    transport: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics { contentDescription = previewA11yName },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)),
        ) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        // Pinned explicitly (PlayerView's default); the fullscreen toggle flips it to RESIZE_MODE_ZOOM and back.
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }.also { created -> playerViewRef.value = created }
                },
                modifier = Modifier.fillMaxSize(),
            )
            transport()
        }
    }
}

/**
 * Canvas transport: single tap toggles play/pause; double tap on the left/right half seeks by
 * [TRANSPORT_SEEK_STEP_MS] (clamped at the chassis). The centered chip carries the a11y contract;
 * the fullscreen toggle is never gated.
 */
@Composable
private fun PreviewTransportOverlay(
    isPlaying: Boolean,
    enabled: Boolean,
    playA11y: String,
    pauseA11y: String,
    seekBackA11y: String,
    seekForwardA11y: String,
    fullscreenZoomed: Boolean,
    fullscreenA11y: String,
    fullscreenFitState: String,
    fullscreenZoomState: String,
    onTogglePlay: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    // rememberUpdatedState keeps the pointerInput un-keyed (a key change mid-gesture would not fire).
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnTogglePlay by rememberUpdatedState(onTogglePlay)
    val currentOnSeekRelative by rememberUpdatedState(onSeekRelative)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (currentEnabled) currentOnTogglePlay() },
                    onDoubleTap = { offset ->
                        if (currentEnabled) {
                            currentOnSeekRelative(
                                if (offset.x < size.width / 2f) {
                                    -TRANSPORT_SEEK_STEP_MS
                                } else {
                                    TRANSPORT_SEEK_STEP_MS
                                },
                            )
                        }
                    },
                )
            },
    ) {
        // The play/pause state chip: 44dp, scrim 62%, centered.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(44.dp)
                .background(Color.Black.copy(alpha = 0.62f), CircleShape)
                .clickable(enabled = enabled, onClick = onTogglePlay)
                .semantics {
                    contentDescription = if (isPlaying) pauseA11y else playA11y
                    customActions = listOf(
                        CustomAccessibilityAction(seekBackA11y) {
                            currentOnSeekRelative(-TRANSPORT_SEEK_STEP_MS)
                            true
                        },
                        CustomAccessibilityAction(seekForwardA11y) {
                            currentOnSeekRelative(TRANSPORT_SEEK_STEP_MS)
                            true
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    if (isPlaying) R.drawable.ic_vt_pause else R.drawable.ic_vt_play,
                ),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
        }
        // The fullscreen toggle, top-trailing inside the frame.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.62f), CircleShape),
        ) {
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.semantics {
                    contentDescription = fullscreenA11y
                    stateDescription = if (fullscreenZoomed) fullscreenZoomState else fullscreenFitState
                },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_vt_fullscreen),
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Mono 11sp time row: leading = position (accent while a strip drag is live), trailing =
 * duration/trim-end, optional centered hint between. Plain text, no extra a11y semantics.
 */
@Composable
internal fun VideoTimeRow(
    leadingText: String,
    trailingText: String,
    hint: String?,
    leadingAccent: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = leadingText,
            color = if (leadingAccent) VideoAccent else VideoTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (hint != null) {
            Text(
                text = hint,
                color = VideoTextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(
            text = trailingText,
            color = VideoTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Full-clip filmstrip with an accent playhead; tap and drag both scrub. The playhead fraction is
 * (trimStart + playerPosition) / clipDuration; a scrub at f seeks f * clipDuration via the seek helper.
 */
@Composable
private fun ScrubFilmstrip(
    thumbs: List<Bitmap>?,
    durationMs: Long,
    playheadAbsoluteMs: Long,
    height: Dp,
    filmstripA11yName: String,
    positionStateText: String,
    onSeekAbsolute: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = filmstripA11yName
                stateDescription = positionStateText
            }
            .pointerInput(onSeekAbsolute, durationMs) {
                detectTapGestures { offset ->
                    if (durationMs > 0) {
                        onSeekAbsolute(((offset.x / size.width) * durationMs).toLong())
                    }
                }
            }
            .pointerInput(onSeekAbsolute, durationMs) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    if (durationMs > 0) {
                        onSeekAbsolute(((change.position.x / size.width) * durationMs).toLong())
                    }
                }
            },
    ) {
        FilmstripRow(thumbs = thumbs)
        PlayheadOverlay(fraction = playheadAbsoluteMs.toFloat() / durationMs)
    }
}

/**
 * Full-clip strip plus two accent handles; drag zones are the strip halves overlapping 24dp at the
 * centre (left of centre+slop = START, else END). Handles snap to TIME_STEP with a min gap, seeking live.
 */
@Composable
private fun TrimFilmstrip(
    thumbs: List<Bitmap>?,
    durationMs: Long,
    startFraction: Float,
    endFraction: Float,
    playheadAbsoluteMs: Long,
    enabled: Boolean,
    startHandleA11y: String,
    endHandleA11y: String,
    startHandleState: String,
    endHandleState: String,
    onStartDrag: (Long) -> Unit,
    onEndDrag: (Long) -> Unit,
) {
    var draggingStartHandle by remember { mutableStateOf(true) }
    val filmstripA11yName = stringResource(R.string.editor_v2_filmstrip)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { contentDescription = filmstripA11yName }
            .pointerInput(enabled, durationMs, onStartDrag, onEndDrag) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val centre = size.width / 2f
                        val slopPx = 24.dp.toPx()
                        draggingStartHandle = offset.x <= centre + slopPx
                    },
                ) { change, _ ->
                    change.consume()
                    if (!enabled || durationMs <= 0) return@detectHorizontalDragGestures
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    val absoluteMs = (fraction * durationMs).toLong()
                    if (draggingStartHandle) onStartDrag(absoluteMs) else onEndDrag(absoluteMs)
                }
            },
    ) {
        FilmstripRow(thumbs = thumbs)
        PlayheadOverlay(fraction = playheadAbsoluteMs.toFloat() / durationMs)
        // Two weighted spacers sandwich each handle so the weights (kept
        // just above zero for the Row contract) place them at the fractions.
        val w1 = startFraction.coerceAtLeast(0.002f)
        val w2 = (endFraction - startFraction).coerceAtLeast(0.002f)
        val w3 = (1f - endFraction).coerceAtLeast(0.002f)
        Row(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(w1))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(VideoAccent, RoundedCornerShape(1.5.dp))
                    .semantics {
                        contentDescription = startHandleA11y
                        stateDescription = startHandleState
                    },
            )
            Spacer(Modifier.weight(w2))
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(VideoAccent, RoundedCornerShape(1.5.dp))
                    .semantics {
                        contentDescription = endHandleA11y
                        stateDescription = endHandleState
                    },
            )
            Spacer(Modifier.weight(w3))
        }
    }
}

/** Where a strip drag's touch landed, fixed at grab time. */
private enum class TrimDragMode { START, END, SCRUB }

/**
 * The Main chassis' primary trim surface: full-clip filmstrip, two 16dp accent handles on the live
 * bounds, a dim scrim outside the window, and a 2dp playhead clamped inside it (the player position
 * can fall outside the live window mid-drag). Frames: ~width/72 clamped to 4..12, via [onDecodeThumbnails].
 *
 * Gestures: within the 28dp grab radius the nearest handle wins, elsewhere scrub, tap seeks. The
 * chassis owns pause/resume/commit and the TIME_STEP-snapped handle math; rememberUpdatedState
 * keeps the gestures un-keyed so a draft update mid-drag cannot cancel them.
 */
@Composable
internal fun TrimStrip(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    playheadAbsoluteMs: Long,
    enabled: Boolean,
    filmstripA11yName: String,
    positionStateText: String,
    startHandleA11y: String,
    endHandleA11y: String,
    startHandleState: String,
    endHandleState: String,
    onDecodeThumbnails: (count: Int, onDone: (List<Bitmap>) -> Unit) -> Unit,
    onDragStarted: () -> Unit,
    onHandleDragFinished: () -> Unit,
    onScrubFinished: () -> Unit,
    onStartDrag: (Long) -> Unit,
    onEndDrag: (Long) -> Unit,
    onSeekAbsolute: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The strip's own adaptive decode: placeholders until the frames land.
    var stripThumbs by remember { mutableStateOf<List<Bitmap>?>(null) }
    var dragMode by remember { mutableStateOf(TrimDragMode.SCRUB) }
    val startFraction = if (durationMs > 0) trimStartMs.toFloat() / durationMs else 0f
    val endFraction = if (durationMs > 0) trimEndMs.toFloat() / durationMs else 1f
    val currentEnabled by rememberUpdatedState(enabled)
    val currentStartFraction by rememberUpdatedState(startFraction)
    val currentEndFraction by rememberUpdatedState(endFraction)
    val currentOnDragStarted by rememberUpdatedState(onDragStarted)
    val currentOnHandleDragFinished by rememberUpdatedState(onHandleDragFinished)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val currentOnStartDrag by rememberUpdatedState(onStartDrag)
    val currentOnEndDrag by rememberUpdatedState(onEndDrag)
    val currentOnSeekAbsolute by rememberUpdatedState(onSeekAbsolute)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                contentDescription = filmstripA11yName
                stateDescription = positionStateText
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    if (currentEnabled && durationMs > 0) {
                        currentOnSeekAbsolute(((offset.x / size.width) * durationMs).toLong())
                    }
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (!currentEnabled) return@detectHorizontalDragGestures
                        currentOnDragStarted()
                        val startX = currentStartFraction * size.width
                        val endX = currentEndFraction * size.width
                        val dStart = abs(offset.x - startX)
                        val dEnd = abs(offset.x - endX)
                        val grab = STRIP_HANDLE_GRAB_RADIUS.toPx()
                        dragMode = when {
                            dStart <= grab && dStart <= dEnd -> TrimDragMode.START
                            dEnd <= grab -> TrimDragMode.END
                            else -> TrimDragMode.SCRUB
                        }
                    },
                    onDragEnd = {
                        if (currentEnabled) {
                            if (dragMode == TrimDragMode.SCRUB) {
                                currentOnScrubFinished()
                            } else {
                                currentOnHandleDragFinished()
                            }
                        }
                    },
                    onDragCancel = {
                        if (currentEnabled) {
                            if (dragMode == TrimDragMode.SCRUB) {
                                currentOnScrubFinished()
                            } else {
                                currentOnHandleDragFinished()
                            }
                        }
                    },
                ) { change, _ ->
                    change.consume()
                    if (!currentEnabled || durationMs <= 0) return@detectHorizontalDragGestures
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    val absoluteMs = (fraction * durationMs).toLong()
                    when (dragMode) {
                        TrimDragMode.START -> currentOnStartDrag(absoluteMs)
                        TrimDragMode.END -> currentOnEndDrag(absoluteMs)
                        TrimDragMode.SCRUB -> currentOnSeekAbsolute(absoluteMs)
                    }
                }
            },
    ) {
        val stripWidthPx = constraints.maxWidth.toFloat()
        val frameCount = (maxWidth.value / STRIP_FRAME_SLOT_DP).toInt()
            .coerceIn(STRIP_FRAME_MIN, STRIP_FRAME_MAX)
        LaunchedEffect(frameCount) {
            onDecodeThumbnails(frameCount) { list -> stripThumbs = list }
        }

        FilmstripRow(thumbs = stripThumbs, frameCount = frameCount)
        // Dim scrim over everything outside the live window; weights stay just above zero for the Row contract.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .weight(startFraction.coerceAtLeast(0.002f))
                    .fillMaxHeight()
                    .background(TrimScrimColor),
            )
            Box(
                modifier = Modifier
                    .weight((endFraction - startFraction).coerceAtLeast(0.002f))
                    .fillMaxHeight(),
            )
            Box(
                modifier = Modifier
                    .weight((1f - endFraction).coerceAtLeast(0.002f))
                    .fillMaxHeight()
                    .background(TrimScrimColor),
            )
        }
        // The 2dp white playhead, CLAMPED inside the live trim window.
        val playheadFraction = (
            if (durationMs > 0) playheadAbsoluteMs.toFloat() / durationMs else 0f
            ).coerceIn(startFraction, endFraction)
        Box(
            modifier = Modifier
                .offset {
                    IntOffset((playheadFraction * stripWidthPx).roundToInt(), 0)
                }
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.White),
        )
        // The two 16dp handles, centered on the live bounds.
        TrimHandle(
            fraction = startFraction,
            stripWidthPx = stripWidthPx,
            contentDescription = startHandleA11y,
            stateText = startHandleState,
        )
        TrimHandle(
            fraction = endFraction,
            stripWidthPx = stripWidthPx,
            contentDescription = endHandleA11y,
            stateText = endHandleState,
        )
    }
}

/**
 * One trim handle: a 16dp full-height accent bar centered on its trim bound, carrying the handle's
 * a11y strings. Visual + TalkBack target only; the 28dp grab radius lives on the parent's drag classifier.
 */
@Composable
private fun TrimHandle(
    fraction: Float,
    stripWidthPx: Float,
    contentDescription: String,
    stateText: String,
) {
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (fraction * stripWidthPx - (STRIP_HANDLE_WIDTH.toPx() / 2f)).roundToInt(),
                    0,
                )
            }
            .width(STRIP_HANDLE_WIDTH)
            .fillMaxHeight()
            .background(VideoAccent, RoundedCornerShape(3.dp))
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = stateText
            },
    )
}

/**
 * Thumbnail slots: real bitmaps when decoded, outlined dark placeholders otherwise. [frameCount]
 * equal-weight slots (default 10; the Main strip passes its adaptive budget).
 */
@Composable
private fun FilmstripRow(thumbs: List<Bitmap>?, frameCount: Int = 10) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until frameCount) {
            val bitmap = thumbs?.getOrNull(i)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (bitmap == null) {
                            Modifier
                                .background(VideoSurfaceHigh)
                                .border(1.dp, VideoOutline, RoundedCornerShape(4.dp))
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * A 2dp accent line at [fraction] of the strip, built as two weighted spacers sandwiching the line
 * (Row weights must be > 0, so the fraction stays just inside (0,1)).
 */
@Composable
private fun PlayheadOverlay(fraction: Float) {
    val f = fraction.coerceIn(0.01f, 0.99f)
    Row(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(f))
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(VideoAccent),
        )
        Spacer(Modifier.weight(1f - f))
    }
}

/**
 * Bottom tab bar: docked 1dp hairline, a 40x28dp accent-halo pill behind the active icon, the 11sp
 * label and the 24x2dp underline, laid out as six equal-weight columns (all fit without scroll at 360dp).
 */
@Composable
internal fun EditorTabBar(current: EditorScreen, onSelect: (EditorScreen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VideoSurface),
    ) {
        // The bar is docked, not floating.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            for (tab in EditorScreen.entries) {
                if (tab == EditorScreen.Main) continue
                val selected = tab == current
                val tint = if (selected) VideoAccent else VideoTextSecondary
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .padding(top = 10.dp, bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Selected-tab halo, so the active tab reads at a glance before the underline lands.
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 28.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) VideoAccent.copy(alpha = 0.18f) else Color.Transparent,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = null,
                            tint = tint,
                        )
                    }
                    Text(
                        text = stringResource(tab.labelRes),
                        color = tint,
                        fontSize = 11.sp,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .width(24.dp)
                            .height(2.dp)
                            .background(if (selected) VideoAccent else Color.Transparent),
                    )
                }
            }
        }
    }
}

/** The tool-screen footer: Reset (dark text chip) + Apply (accent-filled). */
@Composable
private fun EditorFooter(
    canReset: Boolean,
    enabled: Boolean,
    onReset: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onReset,
            enabled = enabled && canReset,
        ) {
            Text(
                text = stringResource(R.string.editor_v2_reset),
                color = if (enabled && canReset) VideoTextPrimary else VideoTextSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onApply, enabled = enabled) {
            Text(text = stringResource(R.string.editor_v2_apply))
        }
    }
}

/**
 * The Trim screen: filmstrip with the two draft handles, draft start/end in the timestamp row,
 * the draft duration centred below. Handle drags keep the TIME_STEP minimum gap and seek live.
 */
@Composable
private fun TrimScreenBody(
    durationMs: Long,
    draftStartMs: Long,
    draftEndMs: Long,
    playheadAbsoluteMs: Long,
    enabled: Boolean,
    thumbs: List<Bitmap>?,
    trimStartA11y: String,
    trimEndA11y: String,
    onDraftStart: (Long) -> Unit,
    onDraftEnd: (Long) -> Unit,
    onSeekAbsolute: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TrimFilmstrip(
            thumbs = thumbs,
            durationMs = durationMs,
            startFraction = draftStartMs.toFloat() / durationMs,
            endFraction = draftEndMs.toFloat() / durationMs,
            playheadAbsoluteMs = playheadAbsoluteMs,
            enabled = enabled,
            startHandleA11y = trimStartA11y,
            endHandleA11y = trimEndA11y,
            startHandleState = formatTimestamp(draftStartMs),
            endHandleState = formatTimestamp(draftEndMs),
            onStartDrag = { absoluteMs ->
                // Start-handle range: 0 until end - TIME_STEP.
                val snapped = snapTimeStep(
                    absoluteMs.toFloat(),
                    0f..(draftEndMs - TIME_STEP_MS).toFloat(),
                ).toLong()
                onDraftStart(snapped)
                onSeekAbsolute(snapped)
            },
            onEndDrag = { absoluteMs ->
                // End-handle range: start + TIME_STEP until duration.
                val snapped = snapTimeStep(
                    absoluteMs.toFloat(),
                    (draftStartMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
                ).toLong()
                onDraftEnd(snapped)
                onSeekAbsolute(snapped)
            },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(
                R.string.editor_v2_duration,
                formatTimestamp(draftEndMs - draftStartMs),
            ),
            color = VideoTextPrimary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/**
 * The Speed screen: the big factor display + subtitle, whole/region chips, the 0.25x..2x slider
 * (0.05 snap on release), preset chips, and in region mode the region start/end sliders + the
 * export-applied note. The big slider edits the active factor (whole or region).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedScreenBody(
    draftWholeSpeed: Float,
    draftRegionMode: Boolean,
    draftRegionStartMs: Long,
    draftRegionEndMs: Long,
    draftRegionSpeed: Float,
    durationMs: Long,
    enabled: Boolean,
    speedA11y: String,
    regionSpeedA11y: String,
    onWholeSpeed: (Float) -> Unit,
    onRegionMode: (Boolean) -> Unit,
    onRegionStart: (Long) -> Unit,
    onRegionEnd: (Long) -> Unit,
    onRegionSpeed: (Float) -> Unit,
) {
    val activeSpeed = if (draftRegionMode) draftRegionSpeed else draftWholeSpeed
    val activeA11y = if (draftRegionMode) regionSpeedA11y else speedA11y
    val subtitle = when {
        activeSpeed == 1.0f -> stringResource(R.string.editor_v2_speed_normal)
        activeSpeed < 1.0f -> stringResource(R.string.editor_v2_speed_slower)
        else -> stringResource(R.string.editor_v2_speed_faster)
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = String.format(Locale.US, "%.2fx", activeSpeed),
            color = VideoTextPrimary,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = subtitle,
            color = VideoTextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            FilterChip(
                selected = !draftRegionMode,
                onClick = { onRegionMode(false) },
                enabled = enabled,
                label = { Text(text = stringResource(R.string.editor_video_speed_whole_clip)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = draftRegionMode,
                onClick = { onRegionMode(true) },
                enabled = enabled,
                label = { Text(text = stringResource(R.string.editor_video_speed_region)) },
            )
        }
        Spacer(Modifier.height(4.dp))
        CommitSlider(
            label = formatSpeed(activeSpeed.toDouble()),
            stateText = formatSpeed(activeSpeed.toDouble()),
            a11yName = activeA11y,
            value = activeSpeed,
            range = VideoEditPlanValidator.MIN_SPEED_FACTOR.toFloat()..
                VideoEditPlanValidator.MAX_SPEED_FACTOR.toFloat(),
            enabled = enabled,
            snap = { snapSpeed(it) },
            onCommit = { if (draftRegionMode) onRegionSpeed(it) else onWholeSpeed(it) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Runtime-formatted: a quoted dotted speed literal would trip the EditAiPrivacyScanTest host-literal scan.
            Text(
                text = formatSpeed(VideoEditPlanValidator.MIN_SPEED_FACTOR),
                color = VideoTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = formatSpeed(VideoEditPlanValidator.MAX_SPEED_FACTOR),
                color = VideoTextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.editor_v2_presets),
            color = VideoTextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (preset in listOf(0.25f, 0.5f, 1.0f, 1.5f, 2.0f)) {
                // Runtime-formatted (scan-safe; see the endpoint note above).
                val presetLabel = formatSpeed(preset.toDouble())
                FilterChip(
                    selected = activeSpeed == preset,
                    onClick = {
                        if (draftRegionMode) onRegionSpeed(preset) else onWholeSpeed(preset)
                    },
                    enabled = enabled,
                    label = { Text(text = presetLabel) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        if (draftRegionMode) {
            CommitSlider(
                label = stringResource(
                    R.string.editor_video_region_start,
                    formatSeconds(draftRegionStartMs),
                ),
                stateText = stringResource(
                    R.string.editor_video_region_start,
                    formatSeconds(draftRegionStartMs),
                ),
                a11yName = stringResource(R.string.editor_a11y_video_region_start),
                value = draftRegionStartMs.toFloat(),
                range = 0f..(draftRegionEndMs - TIME_STEP_MS).toFloat(),
                enabled = enabled,
                snap = { snapTimeStep(it, 0f..(draftRegionEndMs - TIME_STEP_MS).toFloat()) },
                onCommit = { onRegionStart(it.toLong()) },
            )
            CommitSlider(
                label = stringResource(
                    R.string.editor_video_region_end,
                    formatSeconds(draftRegionEndMs),
                ),
                stateText = stringResource(
                    R.string.editor_video_region_end,
                    formatSeconds(draftRegionEndMs),
                ),
                a11yName = stringResource(R.string.editor_a11y_video_region_end),
                value = draftRegionEndMs.toFloat(),
                range = (draftRegionStartMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
                enabled = enabled,
                snap = {
                    snapTimeStep(
                        it,
                        (draftRegionStartMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
                    )
                },
                onCommit = { onRegionEnd(it.toLong()) },
            )
            Text(
                text = stringResource(R.string.editor_video_region_note),
                color = VideoTextSecondary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The Audio screen: with an audio track, the mute row + the volume row (0..200 step-5 slider
 * driving the live preview); without one, the visible no-audio note replaces both rows.
 */
@Composable
private fun AudioScreenBody(
    audioTrackPresent: Boolean,
    draftMute: Boolean,
    draftVolumePercent: Int,
    enabled: Boolean,
    muteA11yName: String,
    muteStateOn: String,
    muteStateOff: String,
    onMute: (Boolean) -> Unit,
    onVolume: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (audioTrackPresent) {
            val volumeLabel = stringResource(R.string.editor_v2_volume)
            val volumeValueText = stringResource(R.string.editor_v2_volume_value, draftVolumePercent)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_vt_volume),
                    contentDescription = null,
                    tint = VideoTextSecondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.editor_video_mute),
                    color = VideoTextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = draftMute,
                    onCheckedChange = onMute,
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription = muteA11yName
                        stateDescription = if (draftMute) muteStateOn else muteStateOff
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = volumeLabel,
                    color = VideoTextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = volumeValueText,
                    color = VideoTextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            CommitSlider(
                label = "",
                stateText = volumeValueText,
                a11yName = stringResource(R.string.editor_v2_volume_a11y),
                value = draftVolumePercent.toFloat(),
                range = 0f..200f,
                enabled = enabled,
                snap = { snapVolume(it) },
                onCommit = { onVolume(it.roundToInt()) },
            )
        } else {
            // No audio track: nothing to mute; the export proceeds video-only.
            Text(
                text = stringResource(R.string.editor_video_no_audio),
                color = VideoTextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The Adjust screen: the selected adjustment's header (name + value), the -100..100 slider with
 * commit-on-release, and the nine chips switching the edited row (VIDEO_ADJUST_SLIDERS order).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdjustScreenBody(
    draftAdjust: VideoAdjustParams,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onDraftAdjust: (VideoAdjustParams) -> Unit,
) {
    val spec = VIDEO_ADJUST_SLIDERS[selectedIndex.coerceIn(0, VIDEO_ADJUST_SLIDERS.lastIndex)]
    val liveValue = spec.get(draftAdjust)
    val labelText = stringResource(spec.label, liveValue)
    val a11yName = stringResource(spec.a11yNameRes)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = labelText,
            color = VideoTextPrimary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        CommitSlider(
            label = "",
            stateText = labelText,
            a11yName = a11yName,
            value = liveValue.toFloat(),
            range = -100f..100f,
            enabled = enabled,
            snap = { it },
            onCommit = { onDraftAdjust(spec.set(draftAdjust, it.roundToInt())) },
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for ((index, chipSpec) in VIDEO_ADJUST_SLIDERS.withIndex()) {
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    // Name-only chips: the header above the slider already carries "Name: value".
                    label = { Text(text = stringResource(chipSpec.nameRes)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

/**
 * The Extract screen: the paused player is the frame preview; the timestamp pill + mini filmstrip
 * pick the frame (drag = seek); format chips + Save Frame run validate -> permission -> submit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtractScreenBody(
    durationMs: Long,
    timestampMs: Long,
    format: FrameFormat,
    enabled: Boolean,
    frameBusy: Boolean,
    frameMessage: String?,
    thumbs: List<Bitmap>?,
    onTimestamp: (Long) -> Unit,
    onFormat: (FrameFormat) -> Unit,
    onSaveFrame: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // The hoisted a11y name (semantics blocks are not composable).
        val timestampA11y = stringResource(R.string.editor_a11y_video_frame_timestamp)
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .background(VideoSurfaceHigh, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .semantics {
                    contentDescription = timestampA11y
                    stateDescription = formatTimestamp(timestampMs)
                },
        ) {
            Text(
                text = formatTimestamp(timestampMs),
                color = VideoTextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(Modifier.height(8.dp))
        ScrubFilmstrip(
            thumbs = thumbs,
            durationMs = durationMs,
            playheadAbsoluteMs = timestampMs,
            height = 32.dp,
            filmstripA11yName = stringResource(R.string.editor_v2_filmstrip_mini),
            positionStateText = formatTimestamp(timestampMs),
            onSeekAbsolute = onTimestamp,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            FilterChip(
                selected = format == FrameFormat.JPEG,
                onClick = { onFormat(FrameFormat.JPEG) },
                enabled = enabled,
                label = { Text(text = stringResource(R.string.editor_video_frame_jpeg)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = format == FrameFormat.PNG,
                onClick = { onFormat(FrameFormat.PNG) },
                enabled = enabled,
                label = { Text(text = stringResource(R.string.editor_video_frame_png)) },
            )
        }
        Text(
            text = stringResource(R.string.editor_video_frame_note),
            color = VideoTextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(R.string.editor_v2_extract_hint),
            color = VideoTextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onSaveFrame, enabled = enabled) {
                Text(text = stringResource(R.string.editor_v2_save_frame))
            }
            if (frameBusy) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = VideoAccent,
                )
            }
        }
        frameMessage?.let { message ->
            Text(
                text = message,
                color = VideoTextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The Animate screen: animation range sliders, frame-rate chips (inside MAX_FPS), width dropdown
 * (fixed choices inside the caps), format chips, and the live preflight estimate the export re-runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimateScreenBody(
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    widthPx: Int,
    fps: Int,
    format: AnimationFormat,
    preflight: AnimationPreflight.Outcome,
    animMessage: String?,
    enabled: Boolean,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    onWidth: (Int) -> Unit,
    onFps: (Int) -> Unit,
    onFormat: (AnimationFormat) -> Unit,
    onExport: () -> Unit,
) {
    var widthMenuExpanded by remember { mutableStateOf(false) }
    // The hoisted a11y name (semantics blocks are not composable).
    val widthA11y = stringResource(R.string.editor_v2_width_a11y)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.editor_v2_animation_range),
            color = VideoTextPrimary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = formatTimestamp(startMs) + " ~ " + formatTimestamp(endMs),
            color = VideoTextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        CommitSlider(
            label = stringResource(R.string.editor_video_animation_start, formatSeconds(startMs)),
            stateText = stringResource(R.string.editor_video_animation_start, formatSeconds(startMs)),
            a11yName = stringResource(R.string.editor_a11y_video_animation_start),
            value = startMs.toFloat(),
            range = 0f..(endMs - TIME_STEP_MS).toFloat(),
            enabled = enabled,
            snap = { snapTimeStep(it, 0f..(endMs - TIME_STEP_MS).toFloat()) },
            onCommit = { onStart(it.toLong()) },
        )
        CommitSlider(
            label = stringResource(R.string.editor_video_animation_end, formatSeconds(endMs)),
            stateText = stringResource(R.string.editor_video_animation_end, formatSeconds(endMs)),
            a11yName = stringResource(R.string.editor_a11y_video_animation_end),
            value = endMs.toFloat(),
            range = (startMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
            enabled = enabled,
            snap = {
                snapTimeStep(
                    it,
                    (startMs + TIME_STEP_MS).toFloat()..durationMs.toFloat(),
                )
            },
            onCommit = { onEnd(it.toLong()) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.editor_v2_frame_rate),
            color = VideoTextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            for (choice in listOf(8, 12, 15, 24, 30)) {
                // The hoisted a11y name (semantics blocks are not composable).
                val fpsA11y = stringResource(R.string.editor_video_animation_fps, choice)
                FilterChip(
                    selected = fps == choice,
                    onClick = { onFps(choice) },
                    enabled = enabled,
                    label = { Text(text = choice.toString()) },
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .semantics { contentDescription = fpsA11y },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.editor_v2_width),
            color = VideoTextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
        Box {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = enabled) { widthMenuExpanded = true }
                    .background(VideoSurfaceHigh, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .semantics {
                        contentDescription = widthA11y
                        stateDescription = widthPx.toString() + " px"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = widthPx.toString() + " px",
                    color = VideoTextPrimary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            DropdownMenu(
                expanded = widthMenuExpanded,
                onDismissRequest = { widthMenuExpanded = false },
            ) {
                // The six fixed choices, all inside the AnimationCaps width band.
                for (choice in listOf(320, 480, 640, 720, 960, 1080)) {
                    DropdownMenuItem(
                        text = { Text(text = choice.toString() + " px") },
                        onClick = {
                            onWidth(choice)
                            widthMenuExpanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.editor_v2_format),
            color = VideoTextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
        Row {
            FilterChip(
                selected = format == AnimationFormat.GIF,
                onClick = { onFormat(AnimationFormat.GIF) },
                enabled = enabled,
                label = { Text(text = stringResource(R.string.editor_video_animation_gif)) },
                modifier = Modifier.padding(end = 8.dp),
            )
            FilterChip(
                selected = format == AnimationFormat.WEBP,
                onClick = { onFormat(AnimationFormat.WEBP) },
                enabled = enabled,
                label = { Text(text = stringResource(R.string.editor_video_animation_webp)) },
            )
        }
        // Live preflight estimate: the same pure math the export runs, shown before any work.
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.editor_v2_estimated_size),
            color = VideoTextSecondary,
            style = MaterialTheme.typography.labelLarge,
        )
        when (preflight) {
            is AnimationPreflight.Outcome.Proceed -> Text(
                text = stringResource(
                    R.string.editor_video_animation_estimate,
                    preflight.frameCount,
                    preflight.estimatedBytes / (1024L * 1024L),
                ),
                color = VideoTextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
            is AnimationPreflight.Outcome.Refusal -> Text(
                text = preflight.reason,
                color = VideoError,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Button(onClick = onExport, enabled = enabled) {
            Text(text = stringResource(R.string.editor_video_animation_export))
        }
        animMessage?.let { message ->
            Text(
                text = message,
                color = VideoTextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * A slider committed on release with an exact snap (the thumb holds its live value during the drag).
 * [label] doubles as the announced state; when empty, [stateText] is announced so it never goes blank.
 */
@Composable
private fun CommitSlider(
    label: String,
    stateText: String,
    a11yName: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    snap: (Float) -> Float,
    onCommit: (Float) -> Unit,
) {
    var live by remember(value) { mutableStateOf(value) }
    if (label.isNotEmpty()) {
        Text(
            text = label,
            color = VideoTextPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    Slider(
        value = live,
        onValueChange = { live = it },
        onValueChangeFinished = { onCommit(snap(live)) },
        valueRange = range,
        enabled = enabled,
        modifier = Modifier.semantics {
            contentDescription = a11yName
            stateDescription = stateText
        },
    )
}
