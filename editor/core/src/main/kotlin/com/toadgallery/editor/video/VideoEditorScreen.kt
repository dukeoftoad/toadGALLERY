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
import com.toadgallery.editor.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// r52 VIDEO surface redesign --- the maintainer-approved Image-2 base
// chassis (dark palette, preview on top, transport row, filmstrip, bottom
// tab bar, per-tab screens with the <- Title v header and the Reset | Apply
// footer). The r42 BEHAVIOR is untouched: the session state is
// committed/drafted here, the plan building / validation / permission gate /
// preflight / progress blocks are the old logic verbatim, and the only new
// capability is the volume percent (task 2-a's VideoEditPlan contract).
//
// r63b: the violet retires --- this surface's pins re-point to the edit
// app's [com.toadgallery.editor.EditorPalette] registry (the approved
// §3 table; same module, so the r52 note's
// "duplicated on purpose" hex fork has no dependency reason to survive).
// r67: the registry's default is the Lilypad family now (the editor-
// rebirth design language: one neutral ladder + the ember accent) --- the
// video lane re-pins with it, one language across photo and video.
// The fixed-dark, non-dynamic contract stands, and the r52 selection
// mechanism survives re-colored (secondaryContainer = the #9CD8A2 accent,
// labels in #00390F).
//
// r63d VIDEO round (the approved proposal section 5 --- presentation +
// gesture layer ONLY): the preview frame gains the spec's 12dp h-margins /
// 14dp radius / 1dp outlineVariant border and hosts the transport as an
// overlay INSIDE the frame (canvas-tap play/pause chip + double-tap +-10s
// seek via the left/right halves + the fullscreen toggle re-anchored
// top-trailing with the r63a a11y strings verbatim --- the three-button
// row retires); the Main filmstrip becomes the PRIMARY trim surface (16dp
// accent handles on a 28dp grab radius, a dim scrim outside the trim
// window, a 2dp white playhead CLAMPED inside it, the adaptive
// (width/72).clamp(4,12) frame count, pause/resume-on-drag, and
// commit-on-release through the SAME Apply path the Trim tab uses);
// TimestampRow retires into the mono TimeRow, and the tab bar adopts the
// photo rail's selected-halo language as six flex(1) columns. PLAYER / GL /
// EXPORT PATHS UNTOUCHED: the MediaItem rebuild, the effects attach, the
// volume/speed previews and every plan/validation/permission/progress
// block below are the r52/r63a logic verbatim --- trim semantics (what
// gets exported) did not move; only the CONTROL surface did. The Trim tab
// stays as the precision surface, byte-identical except the row swap.
// ---------------------------------------------------------------------------

// The video pins, sourced from the shared EditorPalette registry (the ONLY
// Lilypad source of truth in the edit app). VideoEditBg is shared with
// the activity's root Surface.
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
 * The video surface's own fixed dark scheme (the §3 role mapping over the
 * Lilypad registry): primary = the ember accent, background/surface =
 * the warm near-black base, secondaryContainer = accent so a SELECTED
 * FilterChip is the accent-filled pill the design shows (no per-chip color
 * overrides anywhere). Deliberately not dynamic color, exactly like
 * [com.toadgallery.editor.EditorTheme]: the editor's accent
 * promise is deterministic.
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

/**
 * The COMMITTED session state: what the export plan is built from (and what
 * a tab's draft resets to). The defaults mirror the r42 VideoEditorContent
 * verbatim --- trim over the whole clip, sound on at 100%, whole speed 1x,
 * a neutral colour set, and the region defaults at duration/4..duration/2 @ 0.5x.
 */
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
        /** The session's opening state (the r42 defaults). */
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

/** What the UI learns when a video export ends (moved verbatim from the r42 activity file). */
internal sealed interface VideoExportOutcome {
    /** The copy landed and the activity already answered the gallery. */
    data object Saved : VideoExportOutcome

    /** The render or the publish failed; the honest reason rides along. */
    data class Failed(val reason: String) : VideoExportOutcome

    /** The user cancelled; back to editing, nothing was modified anywhere. */
    data object Canceled : VideoExportOutcome
}

/** What the UI learns when a still export (frame or animation) ends (moved verbatim). */
internal sealed interface StillExportOutcome {
    /** A new MediaStore entry landed; the editor stays open for more. */
    data object Saved : StillExportOutcome

    /** Validation, decode or the write failed; the honest reason rides along. */
    data class Failed(val reason: String) : StillExportOutcome

    /** The user cancelled between frames; nothing partial survived. */
    data object Canceled : StillExportOutcome
}

/** The smallest trim/speed granularity the sliders snap to (100 ms; moved verbatim). */
private const val TIME_STEP_MS: Long = 100

/**
 * The canvas transport's double-tap seek step (the section-5 spec's +-10 s
 * via the left/right halves; the retired three-button row carried its +-1 s
 * step as an inline literal --- no named constant existed to match).
 */
private const val TRANSPORT_SEEK_STEP_MS: Long = 10_000L

/**
 * The r63d trim-strip numbers (the Ente video_trim_slider budget adapted to
 * this repo's touch contract): 16dp visual handles centered on the trim
 * bounds, a 28dp grab radius per handle (a 56dp catch zone), and the
 * adaptive frame budget --- about (width/72) frames, clamped to 4..12.
 */
private val STRIP_HANDLE_WIDTH = 16.dp
private val STRIP_HANDLE_GRAB_RADIUS = 28.dp
private const val STRIP_FRAME_SLOT_DP = 72f
private const val STRIP_FRAME_MIN = 4
private const val STRIP_FRAME_MAX = 12

/** The dim scrim over the filmstrip OUTSIDE the trim window (section 5). */
private val TrimScrimColor = Color.Black.copy(alpha = 0.55f)

/** One adjust slider's binding over the colour params (moved verbatim). */
private data class VideoSliderSpec(
    val label: Int,
    // r64: the chip's name-only string --- the chips used to repeat the
    // "Name: value" format while the header above the slider already
    // carries the live value, so long names truncated at the row's edge
    // ("Contr...").
    val nameRes: Int,
    val a11yNameRes: Int,
    val get: (VideoAdjustParams) -> Int,
    val set: (VideoAdjustParams, Int) -> VideoAdjustParams,
)

/** The nine ADR-0012 sliders in the photo surface's own order (moved verbatim). */
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

/** Formats a millisecond position as seconds with one decimal (moved verbatim). */
private fun formatSeconds(ms: Long): String =
    String.format(Locale.US, "%.1f s", ms / 1000.0)

/** Formats a speed factor as the "0.50x" the labels show (moved verbatim). */
private fun formatSpeed(factor: Double): String =
    String.format(Locale.US, "%.2fx", factor)

/**
 * Formats a millisecond position as the design's mm:ss.d. The seconds are
 * floored to the shown tenth first so the formatting can never round a
 * 59.95 s tail up to a bogus "01:60.0".
 */
internal fun formatTimestamp(ms: Long): String {
    val minutes = ms / 60_000L
    val seconds = (ms % 60_000L) / 1000.0
    val floored = Math.floor(seconds * 10.0) / 10.0
    return String.format(Locale.US, "%02d:%04.1f", minutes, floored)
}

/** The slider-release snap for a time position (the old TimeSlider release rule). */
private fun snapTimeStep(value: Float, range: ClosedFloatingPointRange<Float>): Float =
    ((value / TIME_STEP_MS).toLong() * TIME_STEP_MS).toFloat()
        .coerceIn(range.start, range.endInclusive)

/** The slider-release snap for a speed factor (the old SpeedSlider release rule). */
private fun snapSpeed(value: Float): Float =
    (Math.round(value * 20.0) / 20.0).toFloat()
        .coerceIn(
            VideoEditPlanValidator.MIN_SPEED_FACTOR.toFloat(),
            VideoEditPlanValidator.MAX_SPEED_FACTOR.toFloat(),
        )

/** The slider-release snap for the volume percent (the spec's step-5 rule). */
private fun snapVolume(value: Float): Float =
    (Math.round(value / 5f) * 5f).coerceIn(0f, 200f)

/**
 * The video editor surface: ONE scaffold for the whole session (the single
 * PlayerView never leaves composition), the committed/drafted session state,
 * the six tool screens, and the r42 export plumbing verbatim --- plan
 * building from COMMITTED state (now with the volume), the shared
 * validators, the storage-permission gate, the preflight display and the
 * progress + cancel blocks. The r32 a11y conventions throughout: every
 * control carries its name and its live value, headings are announced, the
 * no-audio note is visible text, and everything disabled is disabled for
 * the honest reason (an export is running).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoEditorScreen(activity: VideoEditActivity) {
    val durationMs = activity.clipDurationMs
    val audioTrackPresent = activity.audioTrackPresent
    val context = LocalContext.current

    // The hoisted a11y strings (semantics blocks are not composable, so
    // every announced string is read here first).
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
    // The r52 filmstrip a11y name stays with the precision Trim tab's strip
    // (TrimScreenBody resolves it itself); the Main chassis strip is the
    // trim strip now, with its own name below.
    val trimStripA11yName = stringResource(R.string.editor_v2_trim_strip)
    val transportHint = stringResource(R.string.editor_v2_transport_hint)
    val muteA11yName = stringResource(R.string.editor_a11y_video_mute)
    val muteStateOn = stringResource(R.string.editor_video_mute_state_on)
    val muteStateOff = stringResource(R.string.editor_video_mute_state_off)

    // ---- The committed session state + which screen is on top ----
    var committed by remember { mutableStateOf(VideoSessionState.initial(durationMs)) }
    var screen by remember { mutableStateOf(EditorScreen.Main) }

    // ---- The per-screen DRAFTS: remembered against (screen, committed) so
    // entering a screen always shows the committed values, and Apply (which
    // rewrites committed) honestly re-keys the draft of the screen it came
    // from. Reset assigns the committed values back without a re-key.
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

    // ---- The exports (the r42 session state verbatim) ----
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

    // The pre-Q storage permission gate (the r42 pattern verbatim): the
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

    // The fullscreen toggle's view handle + zoom state live at the chassis
    // (r63a V2 kept them here through the r63d re-anchor: the toggle now
    // sits top-trailing INSIDE the preview frame --- the section-5 spec ---
    // with the exact r63a a11y strings; same action, same semantics).
    val playerViewRef = remember { mutableStateOf<PlayerView?>(null) }
    var fullscreenZoomed by remember { mutableStateOf(false) }

    // r63d trim-on-filmstrip drag contract (the Main strip is the PRIMARY
    // trim surface): any strip drag pauses the preview, and the release
    // resumes it ONLY if it was playing before the drag. The drag-live
    // window rides the existing Trim-tab drafts (which re-key to committed
    // on every commit), and a handle release commits through the SAME copy
    // path the Trim tab's Apply uses --- the trim plan path is untouched.
    var stripDragActive by remember { mutableStateOf(false) }
    var wasPlayingBeforeStripDrag by remember { mutableStateOf(false) }
    var stripDragEndTick by remember { mutableStateOf(0) }

    // While the Adjust screen is up the DRAFT params preview live
    // (recommitted on each slider release); everywhere else the committed
    // set is what the preview shows. The neutral -> no-effects identity is
    // the r42 one, preserved exactly.
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

    // The r63d drag contract's SECOND half, declared AFTER the rebuild
    // effect above so it re-asserts the contract on top of the rebuild's
    // playWhenReady = true (a trim commit restarts the clipped item; the
    // effects dispatch in composition order, so this runs after). Without
    // it, releasing a strip drag while PAUSED would kick the preview into
    // playing --- the contract says resume ONLY if it was playing before.
    LaunchedEffect(stripDragEndTick) {
        if (stripDragEndTick == 0) return@LaunchedEffect
        if (wasPlayingBeforeStripDrag) player.play() else player.pause()
    }

    // The volume preview: the Audio screen's DRAFT (instant on release),
    // the committed values everywhere else. Mute wins, as in the plan.
    val previewMute = if (screen == EditorScreen.Audio) draftMute else committed.mute
    val previewVolumePercent = if (screen == EditorScreen.Audio) {
        draftVolumePercent
    } else {
        committed.volumePercent
    }
    LaunchedEffect(previewMute, previewVolumePercent) {
        player.volume = if (previewMute) 0f else previewVolumePercent / 100f
    }

    // The whole-clip factor previews via playback speed; a region factor is
    // honestly export-applied only (the r42 note says so). COMMITTED values
    // only --- unchanged semantics.
    LaunchedEffect(committed.regionMode, committed.wholeSpeed) {
        player.setPlaybackSpeed(if (committed.regionMode) 1f else committed.wholeSpeed)
    }

    // The Extract screen IS the honest frame preview: paused, and re-seeked
    // to the draft timestamp on entry and on every draft change. The seek is
    // PLAYER-relative (the item is clipped to the committed trim), so an
    // absolute source time outside the trim clamps to the window edge.
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

    // ---- The 100ms poll: position, playing state, and the preview length.
    // For a clipped item player.duration IS the trim length once prepared;
    // until then (TIME_UNSET) fall back to the committed trim length.
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

    // Absolute (source-time) -> player-relative seek. MAPPING: the preview
    // MediaItem is clipped to the committed trim, so the player's position
    // is relative to trimStart --- absoluteMs - committed.trimStartMs. The
    // result clamps into the current preview window.
    val seekToAbsolute: (Long) -> Unit = { absoluteMs ->
        player.seekTo((absoluteMs - committed.trimStartMs).coerceIn(0L, previewDurationMs))
    }

    // ---- The r63d Main-strip drag plumbing: the handle math is the Trim
    // screen's, VERBATIM (same TIME_STEP snap, same minimum gap), the drag
    // start pauses the player and remembers whether it was playing (the
    // playWhenReady intent --- a buffering stream counts as playing), and a
    // handle release commits the drafts through the Apply path. Every
    // finisher is armed only by a real drag start, so a gesture that was
    // gated off (an export running) can never fire the contract. ----
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
            // The Trim tab's Apply, verbatim: committed <- draft.
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

    // Leaving the Main chassis mid-drag (a stray second finger on a tab)
    // drops the accent flag; the drafts re-key to committed on the screen
    // change by themselves.
    LaunchedEffect(screen) {
        if (screen != EditorScreen.Main) stripDragActive = false
    }

    // ---- The real filmstrip frames (decoded once; placeholders until then) ----
    var thumbs by remember { mutableStateOf<List<Bitmap>?>(null) }
    LaunchedEffect(Unit) {
        activity.decodeThumbnails(10) { list -> thumbs = list }
    }

    // ---- The COMMITTED-plan video export (the r42 logic verbatim, plus
    // the volume from the committed session state) ----
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
                // The UI bounds its own inputs, so this is a bug or
                // tampering --- and it says so.
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

    // ---- The frame-extract submit (the r42 onClick verbatim) ----
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

    // ---- The animation preflight + submit (the r42 block verbatim: the
    // SAME pure math drives the on-screen estimate and the export gate) ----
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
            // The refusal is already on screen; the button is a no-op on a
            // refused plan (an honest no-op, not a hidden one).
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

    // ---- The draft commit (Apply = committed <- draft, stay; the header
    // check = Apply + back). Extract/Animate settings are session-live
    // (they affect no video-plan field), so there is nothing to commit.
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

    // ---- The chassis: header / preview + transport overlay / time row /
    // then either (trim strip + tab bar) on Main or (scrolling body +
    // footer) on a tool screen. r47 ux fix paid once at the root.
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
            // The still-export progress + cancel stay reachable from EVERY
            // screen (the r42 shared block, promoted under the header) so
            // leaving the Extract/Animate screen never orphans a running job.
            StillExportProgressBlock(
                animProgress = animProgress,
                cancelLabel = stringResource(R.string.editor_video_cancel_export),
                onCancel = { activity.cancelStillExport() },
            )
        }

        // r63a V1: the preview fills the leftover stage on the Main chassis
        // (the r62 hardcoded 220dp sliver + the dead Spacer below it wasted
        // ~50% of the screen); the tool screens keep the fixed height so
        // their scrolling bodies keep the room. The height is the caller's
        // decision now --- PreviewSurface no longer pins it. r63d: the
        // frame hosts the transport overlay (the retired row's actions live
        // on the canvas now) and the section-5 frame craft.
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
                        // Player-relative, clamped into the current preview
                        // window: the item is clipped to the committed trim,
                        // and previewDurationMs already encodes the fallback
                        // (the retired row's rule, verbatim).
                        player.seekTo((positionMs + deltaMs).coerceIn(0L, previewDurationMs))
                    },
                    onToggleFullscreen = {
                        val view = playerViewRef.value
                        if (view != null) {
                            fullscreenZoomed = !fullscreenZoomed
                            // The resize-mode constants live on the content frame's
                            // AspectRatioFrameLayout (CI's compile judge --- they are
                            // NOT PlayerView members); the view's setter takes them.
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

        // r63d: the Time row replaces the two-row TimestampRow block (mono
        // 11sp; leading = position, accent while a strip drag is live;
        // trailing = the trim end on Main, the draft bounds on the Trim
        // screen, the previewed length on the other tool screens).
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
            // r63d V4: the filmstrip IS the trim surface now (16dp handles,
            // dim scrim outside the window, clamped playhead); the Trim tab
            // below stays as the precision surface. The strip shows the LIVE
            // window --- the drafts, which equal committed at rest.
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
            // r63a V5: the dead Spacer(weight(1f)) that parked ~40% of the
            // screen below the filmstrip is gone --- the preview's weight(1f)
            // above now takes this space, so the filmstrip and the tab bar
            // dock at the bottom against a full-height stage.
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

/** The video-export progress + cancellation (the r42 block, verbatim, under the header). */
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

/**
 * The still-export (frame/animation) progress + cancellation: the r42
 * shared block, promoted under the header so a running job can always be
 * cancelled no matter which screen is on top.
 */
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
 * The preview surface: the ONE PlayerView of the session (never recomposed
 * away --- a sub-screen only ever replaces the content BELOW it). The height
 * is the CALLER's decision (r63a V1: weight(1f) on the Main chassis, the
 * fixed 220dp on the tool screens) --- the r62 hardcoded height(220.dp) here
 * is what locked the preview to ~9% of the screen. r63d adds the section-5
 * frame craft (12dp h-margins, a 14dp-radius clip, a 1dp outlineVariant
 * border) and the [transport] slot rendered INSIDE the frame over the video:
 * the canvas-tap transport overlay, with the fullscreen toggle anchored
 * top-trailing (the r63a a11y strings verbatim).
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
                        // The section-5 fit rule, pinned explicitly (this
                        // is PlayerView's default; the fullscreen toggle
                        // flips it to RESIZE_MODE_ZOOM and back).
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
 * The r63d transport overlay (the three-button row retired): the CANVAS is
 * the transport now. A single tap anywhere toggles play/pause; a double tap
 * on the left/right half seeks back/forward by [TRANSPORT_SEEK_STEP_MS]
 * (the section-5 spec; seeks clamp into the current preview window at the
 * chassis). The playback state renders as the small centered chip --- 44dp,
 * 62% scrim --- which also carries the a11y contract: the play/pause
 * contentDescription exactly like the retired button, plus the retired
 * seek buttons' affordances as custom accessibility actions (honest
 * ten-second labels, same resource names). The fullscreen toggle anchors
 * top-trailing inside the frame (section 5) and is never gated (the r52
 * surface-toggle behavior, preserved verbatim through the re-anchor).
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
    // The gesture handlers read live values through rememberUpdatedState so
    // the pointerInput never keys on them (a mid-session key change would
    // not fire, and stale captures would seek from a frozen position).
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
        // The fullscreen toggle: re-anchored top-trailing INSIDE the frame
        // (the section-5 preview spec), same action + the same a11y strings
        // the r63a transport cluster carried, verbatim.
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
 * The r63d Time row (TimestampRow retired into it): mono 11sp, leading =
 * the position (accent while a strip drag is live), trailing = the
 * duration/trim-end, and on the Main chassis the centered transport hint
 * between them. Plain text like the row it replaces --- no new a11y
 * contract, the values are announced as the texts they are.
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
 * The main filmstrip: ten REAL thumbnails over the FULL clip timeline with
 * the accent playhead and drag-to-scrub. MAPPING (the honest one for a
 * full-clip strip): the playhead fraction is
 * `(committed.trimStart + playerPosition) / clipDuration`, and a scrub at
 * fraction f seeks to absolute `f * clipDuration`, which the seek helper
 * maps into the player's trim-relative window. Tapping and dragging both
 * scrub; the announced state carries the absolute position.
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
 * The trim filmstrip: the same full-clip strip plus TWO accent handles.
 * The drag zones are the two halves of the strip, overlapping by 24dp at
 * the centre (the hit-slop): a touch starting left-of-centre-plus-slop
 * drags the START handle, otherwise the END handle. The dragged handle
 * snaps to TIME_STEP, keeps the TIME_STEP minimum gap, and seeks the
 * player to the dragged position (the honest live preview of the cut).
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
 * The r63d trim-on-filmstrip: the Main chassis' PRIMARY trim surface (the
 * Ente video_trim_slider pattern on the repo's 48dp touch contract). The
 * full-clip filmstrip carries TWO 16dp accent handles centered on the LIVE
 * trim bounds, a dim scrim over the strip OUTSIDE the window, and a 2dp
 * white playhead CLAMPED inside it (during a drag the player's position can
 * fall outside the live window --- the clamp keeps the line honest). The
 * strip renders an adaptive frame count --- about (width/72), clamped to
 * 4..12 --- decoded at the slot midpoints through [onDecodeThumbnails] (the
 * shared 10-frame decode stays with the untouched tool-screen strips).
 *
 * Gestures: a touch starting within the 28dp grab radius of a handle drags
 * THAT handle (nearest wins when the zones overlap); anywhere else is a
 * scrub. A tap seeks. Every drag start pauses the preview and every release
 * resumes it ONLY if it was playing before (the chassis owns that contract
 * and the commit); handle drags feed the chassis' TIME_STEP-snapped math.
 * The pointer inputs read live values through rememberUpdatedState so a
 * draft update mid-drag never re-keys (and cancels) the gesture.
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
        // The dim scrim over everything OUTSIDE the live window (the weights
        // are kept just above zero for the Row contract --- a full-clip trim
        // simply hides the sliver).
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
 * One trim handle: a 16dp full-height accent bar (the section-5 visual)
 * centered on its trim bound, carrying the Trim tab's handle a11y strings
 * verbatim (name + timestamp state). The 28dp grab radius lives on the
 * parent's drag classifier, not here --- this node is the visual + the
 * TalkBack target.
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
 * The thumbnail slots: real bitmaps when decoded, honest outlined dark
 * placeholders otherwise. [frameCount] slots are laid out at equal weight;
 * the r63d Main strip passes its adaptive budget while the untouched
 * tool-screen strips keep the r52 ten (the default).
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
 * The playhead: a 2dp accent line at [fraction] of the strip. Implemented
 * as two weighted spacers sandwiching the line (weights must be > 0, so
 * the fraction is kept just inside (0,1) --- visually exact, and no
 * exotic layout dependency).
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
 * The bottom tab bar (r63d): the photo rail's selected-halo language on the
 * video palette --- a docked 1dp hairline, a 40x28dp accent-halo pill behind
 * the active icon, the 11sp label, the 24x2dp underline --- laid out as six
 * equal-weight (flex) columns so all six tabs fit with NO scroll at 360dp.
 * The enum constants, the label strings and the click behavior are the r52
 * ones verbatim (the a11y reads the labels as it always did).
 */
@Composable
internal fun EditorTabBar(current: EditorScreen, onSelect: (EditorScreen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VideoSurface),
    ) {
        // The r56 hairline: the bar is docked, not floating.
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
                    // The selected halo: the accent language behind the
                    // icon, so the active tab reads at a glance even
                    // before the underline lands.
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
 * The Trim screen: the filmstrip with the two draft handles, the draft
 * start/end in the timestamp row above, the draft duration centred below.
 * Dragging a handle clamps to [0, duration] with the TIME_STEP minimum gap
 * and seeks the player to the dragged position.
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
                // The old start-slider range: 0 until end - TIME_STEP.
                val snapped = snapTimeStep(
                    absoluteMs.toFloat(),
                    0f..(draftEndMs - TIME_STEP_MS).toFloat(),
                ).toLong()
                onDraftStart(snapped)
                onSeekAbsolute(snapped)
            },
            onEndDrag = { absoluteMs ->
                // The old end-slider range: start + TIME_STEP until duration.
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
 * The Speed screen: the big factor display + subtitle, the whole/region
 * chips, the 0.25x..2x slider (snap 0.05 on release, the old rule), the
 * preset chips, and in region mode the old region start/end sliders + the
 * honest export-applied note. The big slider edits the ACTIVE factor
 * (whole in whole mode, region in region mode).
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
            // The endpoints are RUNTIME-formatted (formatSpeed, the r42
            // shape): a quoted dotted speed-shape literal would trip the
            // EditAiPrivacyScanTest host-literal scan, and the r42 record
            // says a new dotted literal joins its set deliberately.
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
                // Runtime-formatted (the scan-safe shape; see the endpoints
                // above): the chips read 0.25x / 0.50x / 1.00x / 1.50x / 2.00x,
                // the same formatting every other speed label in the app shows.
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
 * The Audio screen: the honest split --- with an audio track, the mute row
 * (icon + label + switch, the old a11y states) and the volume row (label +
 * live percent + the 0..200 step-5 slider that drives the live preview);
 * without one, the visible no-audio note replaces both rows.
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
            // The contract's honest note: the clip HAS no audio track, so
            // there is nothing to mute --- the export proceeds video-only
            // and the note is visible text TalkBack announces.
            Text(
                text = stringResource(R.string.editor_video_no_audio),
                color = VideoTextPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * The Adjust screen: the selected adjustment's header (the old label string
 * carries the display name + value), the -100..100 slider with the old
 * draft/commit mechanics (live preview lands on release), and the nine
 * chips in the VIDEO_ADJUST_SLIDERS order for switching the edited row.
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
                    // r64: name-only chips --- the header above the slider
                    // already carries "Name: value"; a chip repeating it
                    // made the row read as half-cut clutter ("Contr...").
                    label = { Text(text = stringResource(chipSpec.nameRes)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

/**
 * The Extract screen: the paused player IS the honest frame preview, the
 * timestamp pill + mini filmstrip pick the frame (drag = seek), the format
 * chips and the Save Frame button run the old validate -> permission ->
 * submit flow verbatim.
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
 * The Animate screen: the animation range (label + value row + the old
 * in/out sliders), the frame-rate chips (8/12/15/24/30, inside MAX_FPS),
 * the width dropdown (six fixed choices inside the 64..1280 caps), the
 * format chips, the live preflight estimate/refusal (the SAME pure math
 * the export re-runs), and the big Export button driving the old submit
 * block verbatim.
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
        // The live preflight estimate --- the SAME pure math the export
        // runs, shown BEFORE any work (refusals included, honestly).
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
 * One labeled slider committed on release with an exact snap (the old
 * slider mechanics: the thumb holds its own live value during the drag,
 * the commit lands on release). [label] renders the label row and doubles
 * as the announced state; when it is empty the caller supplies [stateText]
 * so the announced value never goes blank.
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
