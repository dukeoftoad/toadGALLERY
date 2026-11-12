package com.toadgallery.editor.video

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toadgallery.editor.LilypadTimelineMath
import com.toadgallery.editor.TimelineViewport
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The r69 zoomable timeline strip (Phase 3, the video timeline foundation):
 * the r63d TrimStrip's Main-screen role --- filmstrip, trim window, dim
 * scrim, clamped playhead, the two 16dp accent handles --- grown a
 * viewport. A pinch zooms (anchored under the fingers, snapped to the
 * 1/2/4/8/16x detents on release); zoomed in, a one-finger drag pans the
 * window and a tap still seeks; at fit, one finger scrubs exactly as the
 * TrimStrip always did. The geometry lives in [LilypadTimelineMath]
 * (pure, JVM-pinned); this composable is the thin shell.
 *
 * HONEST THUMBNAILS: the frames decode EVENLY over the whole clip (the
 * TrimStrip contract) and draw at their TRUE source positions, so a
 * zoomed window shows the subset that lands inside it --- sparsely, never
 * misrepresenting which frame is which. Window-aware decode with caching
 * is the R2 follow-up (F-78), deliberately not this round.
 *
 * The surface is single-clip TODAY (the session's one source spans the
 * strip); the ClipTimeline model beside it is the multi-clip contract
 * the Phase 3 surfaces grow into, the same additive way every model here
 * has grown.
 */
@Composable
internal fun TimelineStrip(
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    playheadAbsoluteMs: Long,
    enabled: Boolean,
    filmstripA11yName: String,
    positionStateText: String,
    zoomStateFormat: String,
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
    var stripThumbs by remember { mutableStateOf<List<Bitmap>?>(null) }
    // The viewport resets when the source changes (a remembered-per-source
    // camera); every gesture within a source evolves it in place.
    var viewport by remember(durationMs) {
        mutableStateOf(
            TimelineViewport(LilypadTimelineMath.ZOOM_MIN, (durationMs / 2).toFloat()),
        )
    }
    val haptics = LocalHapticFeedback.current

    // The r63d discipline: everything the long-lived gesture loop reads
    // rides rememberUpdatedState, so the loop's captures never go stale.
    val currentEnabled by rememberUpdatedState(enabled)
    val currentTrimStartMs by rememberUpdatedState(trimStartMs)
    val currentTrimEndMs by rememberUpdatedState(trimEndMs)
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
            .clipToBounds()
            .semantics {
                contentDescription = filmstripA11yName
                stateDescription = positionStateText + ", " +
                    zoomStateFormat.format(LilypadTimelineMath.zoomLabel(viewport.zoom))
            }
            .pointerInput(durationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEnabled || durationMs <= 0L) return@awaitEachGesture
                    val widthPx = size.width.toFloat()
                    val durationF = durationMs.toFloat()

                    fun pxAt(ms: Float): Float =
                        LilypadTimelineMath.pxAt(ms, viewport, durationF, widthPx)

                    fun msAtClamped(xPx: Float): Long = LilypadTimelineMath
                        .msAt(xPx, viewport, durationF, widthPx)
                        .coerceIn(0f, durationF)
                        .toLong()

                    // The r63d classifier, viewport-aware: a down within the
                    // grab radius of a handle grabs it (at any zoom); a down
                    // anywhere else pans while zoomed in, scrubs at fit.
                    val slopPx = TIMELINE_TAP_SLOP.toPx()
                    val grabPx = TIMELINE_HANDLE_GRAB_RADIUS.toPx()
                    val startX = down.position.x
                    val mode = run {
                        val dStart = abs(startX - pxAt(currentTrimStartMs.toFloat()))
                        val dEnd = abs(startX - pxAt(currentTrimEndMs.toFloat()))
                        when {
                            dStart <= grabPx && dStart <= dEnd -> TimelineDragMode.HANDLE_START
                            dEnd <= grabPx -> TimelineDragMode.HANDLE_END
                            viewport.zoom > LilypadTimelineMath.ZOOM_MIN + ZOOM_EPSILON ->
                                TimelineDragMode.PAN
                            else -> TimelineDragMode.SCRUB
                        }
                    }
                    currentOnDragStarted()

                    var moved = 0f
                    var multiTouch = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // The canvas lesson: transform only while TWO or
                            // more pointers are down, and only then consume.
                            multiTouch = true
                            val zoomChange = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            if (zoomChange != 1f || pan != Offset.Zero) {
                                val focalMs = LilypadTimelineMath
                                    .msAt(centroid.x, viewport, durationF, widthPx)
                                    .coerceIn(0f, durationF)
                                viewport = LilypadTimelineMath.anchoredZoom(
                                    viewport,
                                    viewport.zoom * zoomChange,
                                    focalMs,
                                    durationF,
                                    widthPx,
                                )
                                if (pan.x != 0f) {
                                    viewport = viewport.copy(
                                        centerMs = LilypadTimelineMath.pannedCenter(
                                            viewport, pan.x, durationF, widthPx,
                                        ),
                                    )
                                }
                                event.changes.forEach { it.consume() }
                            }
                        } else if (pressed.size == 1) {
                            val change = pressed.first()
                            moved += abs(change.position.x - change.previousPosition.x)
                            // After a pinch, the remaining finger pans the
                            // window (navigation), whatever the down classified.
                            val effective = if (multiTouch) TimelineDragMode.PAN else mode
                            if (moved >= slopPx && change.positionChange() != Offset.Zero) {
                                when (effective) {
                                    TimelineDragMode.HANDLE_START ->
                                        currentOnStartDrag(msAtClamped(change.position.x))
                                    TimelineDragMode.HANDLE_END ->
                                        currentOnEndDrag(msAtClamped(change.position.x))
                                    TimelineDragMode.PAN -> viewport = viewport.copy(
                                        centerMs = LilypadTimelineMath.pannedCenter(
                                            viewport,
                                            change.position.x - change.previousPosition.x,
                                            durationF,
                                            widthPx,
                                        ),
                                    )
                                    TimelineDragMode.SCRUB ->
                                        currentOnSeekAbsolute(msAtClamped(change.position.x))
                                }
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (multiTouch) {
                        // The dial's detent lesson: a released pinch settles
                        // on a detent, with the one sanctioned haptic.
                        val snapped = LilypadTimelineMath.snapZoomToDetent(viewport.zoom)
                        if (abs(snapped - viewport.zoom) > 1e-4f) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            viewport = TimelineViewport(
                                zoom = snapped,
                                centerMs = LilypadTimelineMath.clampCenter(
                                    viewport.centerMs, durationF, widthPx, snapped,
                                ),
                            )
                        }
                        // The pairing contract: the onDragStarted that opened
                        // this gesture paused the player and raised the
                        // active flag, so the release MUST close it (both
                        // finishers are idempotent, guarded on the flag ---
                        // the TrimStrip onDragCancel lesson).
                        when (mode) {
                            TimelineDragMode.HANDLE_START, TimelineDragMode.HANDLE_END ->
                                currentOnHandleDragFinished()
                            else -> currentOnScrubFinished()
                        }
                    } else {
                        when (mode) {
                            TimelineDragMode.HANDLE_START, TimelineDragMode.HANDLE_END ->
                                currentOnHandleDragFinished()
                            TimelineDragMode.SCRUB -> currentOnScrubFinished()
                            TimelineDragMode.PAN -> Unit
                        }
                        // The r63d tap rule, kept at every zoom: a touch that
                        // never crossed the slop seeks (handle downs excepted
                        // --- a tap on a handle is a grab, not a jump).
                        if (moved < slopPx &&
                            (mode == TimelineDragMode.SCRUB || mode == TimelineDragMode.PAN)
                        ) {
                            currentOnSeekAbsolute(msAtClamped(startX))
                        }
                    }
                }
            },
    ) {
        val stripWidthPx = constraints.maxWidth.toFloat()
        val frameCount = (maxWidth.value / TIMELINE_FRAME_SLOT_DP)
            .toInt()
            .coerceIn(TIMELINE_FRAME_MIN, TIMELINE_FRAME_MAX)
        LaunchedEffect(frameCount) {
            onDecodeThumbnails(frameCount) { list -> stripThumbs = list }
        }
        val thumbImages = remember(stripThumbs) { stripThumbs?.map { it.asImageBitmap() } }
        val durationF = durationMs.toFloat()

        val ember = MaterialTheme.colorScheme.primary
        val padRaised = MaterialTheme.colorScheme.surfaceVariant
        val reed = MaterialTheme.colorScheme.onSurfaceVariant
        val hairline = MaterialTheme.colorScheme.outlineVariant

        fun pxAt(ms: Float): Float =
            LilypadTimelineMath.pxAt(ms, viewport, durationF, stripWidthPx)

        Canvas(modifier = Modifier.fillMaxSize()) {
            if (durationMs <= 0L) return@Canvas
            val corner = CornerRadius(TIMEL_STRIP_CORNER.toPx())
            val clip = Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner))
            }
            clipPath(clip) {
                // The filmstrip floor: a pad-raised bed the frames sit on.
                drawRoundRect(
                    color = padRaised,
                    cornerRadius = corner,
                    size = size,
                )
                // The frames, at their true source positions (the honest-
                // sparse rule: a zoomed window shows the subset inside it).
                if (!thumbImages.isNullOrEmpty()) {
                    val n = thumbImages.size
                    val slotW = size.width / n
                    for ((index, image) in thumbImages.withIndex()) {
                        val centerMs = durationF * (index + 0.5f) / n
                        val dstX = pxAt(centerMs) - slotW / 2f
                        if (dstX + slotW < 0f || dstX > size.width) continue
                        // Center-crop the source into the slot (the
                        // FilmstripRow framing, in canvas form).
                        val dstAspect = slotW / size.height
                        val srcW = image.width.toFloat()
                        val srcH = image.height.toFloat()
                        val cropW: Int
                        val cropH: Int
                        if (srcW / srcH > dstAspect) {
                            cropH = image.height
                            cropW = (srcH * dstAspect).toInt().coerceAtLeast(1)
                        } else {
                            cropW = image.width
                            cropH = (srcW / dstAspect).toInt().coerceAtLeast(1)
                        }
                        drawImage(
                            image = image,
                            srcOffset = IntOffset(
                                ((srcW - cropW) / 2f).roundToInt(),
                                ((srcH - cropH) / 2f).roundToInt(),
                            ),
                            srcSize = IntSize(cropW, cropH),
                            dstOffset = IntOffset(dstX.roundToInt(), 0),
                            dstSize = IntSize(slotW.roundToInt().coerceAtLeast(1), size.height.roundToInt()),
                        )
                    }
                }
                // The dim scrim outside the live trim window (r63d, scaled).
                val trimStartPx = pxAt(trimStartMs.toFloat())
                val trimEndPx = pxAt(trimEndMs.toFloat())
                if (trimStartPx > 0f) {
                    drawRect(
                        color = TIMELINE_SCRIM,
                        topLeft = Offset(0f, 0f),
                        size = Size(trimStartPx.coerceAtMost(size.width), size.height),
                    )
                }
                val scrimLeft = trimEndPx.coerceAtLeast(0f)
                if (scrimLeft < size.width) {
                    drawRect(
                        color = TIMELINE_SCRIM,
                        topLeft = Offset(scrimLeft, 0f),
                        size = Size(size.width - scrimLeft, size.height),
                    )
                }
                // The 2dp white playhead, clamped inside the live window.
                val playheadMs = playheadAbsoluteMs.toFloat()
                    .coerceIn(trimStartMs.toFloat(), trimEndMs.toFloat())
                val playheadPx = pxAt(playheadMs)
                val playheadW = TIMELINE_PLAYHEAD.toPx()
                drawRect(
                    color = Color.White,
                    topLeft = Offset(playheadPx - playheadW / 2f, 0f),
                    size = Size(playheadW, size.height),
                )
                // The two 16dp accent handles (the TrimHandle visuals).
                val handleW = TIMELINE_HANDLE_WIDTH.toPx()
                drawRoundRect(
                    color = ember,
                    topLeft = Offset(trimStartPx - handleW / 2f, 0f),
                    size = Size(handleW, size.height),
                    cornerRadius = CornerRadius(TIMELINE_HANDLE_CORNER.toPx()),
                )
                drawRoundRect(
                    color = ember,
                    topLeft = Offset(trimEndPx - handleW / 2f, 0f),
                    size = Size(handleW, size.height),
                    cornerRadius = CornerRadius(TIMELINE_HANDLE_CORNER.toPx()),
                )
            }
        }

        // The handle a11y targets: transparent 16dp nodes over the drawn
        // handles, carrying the Trim tab's name + timestamp state verbatim.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (pxAt(trimStartMs.toFloat()) - TIMELINE_HANDLE_WIDTH.toPx() / 2f).roundToInt(),
                        0,
                    )
                }
                .width(TIMELINE_HANDLE_WIDTH)
                .fillMaxHeight()
                .semantics {
                    contentDescription = startHandleA11y
                    stateDescription = startHandleState
                },
        )
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (pxAt(trimEndMs.toFloat()) - TIMELINE_HANDLE_WIDTH.toPx() / 2f).roundToInt(),
                        0,
                    )
                }
                .width(TIMELINE_HANDLE_WIDTH)
                .fillMaxHeight()
                .semantics {
                    contentDescription = endHandleA11y
                    stateDescription = endHandleState
                },
        )

        // The zoom chip: the live multiplier, mono, top-end --- the value
        // pill's little sibling, shown only while zoomed past fit.
        if (viewport.zoom > LilypadTimelineMath.ZOOM_MIN + ZOOM_EPSILON) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 3.dp)
                    .background(padRaised, RoundedCornerShape(TIMELINE_CHIP_CORNER))
                    .border(1.dp, hairline, RoundedCornerShape(TIMELINE_CHIP_CORNER))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = LilypadTimelineMath.zoomLabel(viewport.zoom),
                    color = reed,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/** The strip's drag classification (the TrimDragMode shape, viewport-grown). */
private enum class TimelineDragMode {
    HANDLE_START,
    HANDLE_END,
    SCRUB,
    PAN,
}

/** Below this the zoom counts as fit (Float-slop tolerance). */
private const val ZOOM_EPSILON = 1e-3f

private val TIMELINE_SCRIM = Color.Black.copy(alpha = 0.55f)
private val TIMELINE_TAP_SLOP = 12.dp
private val TIMELINE_HANDLE_GRAB_RADIUS = 28.dp
private val TIMELINE_HANDLE_WIDTH = 16.dp
private val TIMELINE_HANDLE_CORNER = 3.dp
private val TIMELINE_PLAYHEAD = 2.dp
private val TIMEL_STRIP_CORNER = 6.dp
private val TIMELINE_CHIP_CORNER = 8.dp
private const val TIMELINE_FRAME_SLOT_DP = 72f
private const val TIMELINE_FRAME_MIN = 4
private const val TIMELINE_FRAME_MAX = 12
