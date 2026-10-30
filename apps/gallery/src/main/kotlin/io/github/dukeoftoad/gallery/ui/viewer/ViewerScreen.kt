package io.github.dukeoftoad.gallery.ui.viewer

import android.app.Activity
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem

/**
 * The full-screen media viewer (issue #50): Mi-Gallery-style single-item
 * browsing over the tap-time window, reached by tapping a grid cell on the
 * Photos timeline or in album detail.
 *
 * ## Layout
 * A pure black, edge-to-edge canvas ([immersive][WindowInsetsControllerCompat]
 * system bars hidden for the lifetime of the screen, restored on dispose) with
 * a [HorizontalPager] over the window's items:
 *  - photos/GIFs render full-fit via Coil ([ContentScale.Fit]) with
 *    gallery-standard pinch-zoom, pan and double-tap zoom (the issue #50
 *    follow-up slice; [ZoomableState] owns the clamped transform math and is
 *    pinned by plain JVM tests), and a single tap toggles the chrome bar at
 *    ANY zoom level. While the current page's photo is zoomed, the pager's
 *    user scrolling locks until the user zooms back out --- the pragmatic v1
 *    trade-off, see [PhotoPage]. GIFs animate in the viewer ONLY, through a
 *    per-request GIF decoder; the grid keeps showing static first frames
 *    (issues #33/#34);
 *  - videos play in-app through media3's [ExoPlayer] in a views-based
 *    [PlayerView] (its own transport controls and gestures --- photo zoom is
 *    deliberately out of scope there), autoplaying with proper audio focus
 *    handling. A decode/playback failure (e.g. the device codec cannot
 *    handle the container --- the same class of failure that makes
 *    `getFrameAtTime` return NULL for some thumbnails) renders the honest
 *    `viewer_video_error_*` copy instead of a dead black page.
 *
 * A scrim top bar carries the back action and the position counter
 * (`viewer_counter`), fading with the chrome visibility. The window comes
 * from the shell-scoped [ViewerViewModel] --- [ToadGalleryApp] passes the
 * SAME instance the grid screens wrote to (issue #27 hand-off pattern); a
 * null/empty window (defensive: deep link or race) navigates straight back.
 *
 * Privacy (ADR-0006 / ADR-0014): every model value is a local MediaStore
 * content URI; ExoPlayer is fed content URIs only, and the GIF decoders
 * (coil-gif) decode purely on-device. The app holds no INTERNET permission,
 * so nothing here can reach the network even with a hostile URI.
 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
) {
    // Immersive while the viewer is open: hide both system bars, transient on
    // swipe. Restored on dispose (back, or navigating to another screen).
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val windowState by viewModel.window.collectAsState()
    val window = windowState
    if (window == null || window.items.isEmpty()) {
        // Defensive: the route is only navigated to after open(), but a bad
        // entry point must not strand the user on a black screen.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var chromeVisible by remember { mutableStateOf(true) }
    // True while the CURRENT page's photo is meaningfully zoomed
    // ([ZoomableState.isZoomed]): locks the pager's user scrolling, the
    // documented v1 trade-off in PhotoPage's KDoc. Because swiping is
    // disabled while this is set, the zoomed page is by construction the
    // settled page, so a single flag is enough.
    var currentPageZoomed by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(initialPage = window.startIndex) {
        window.items.size
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("viewer_screen"),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !currentPageZoomed,
            key = { page -> window.items[page].uri.toString() },
        ) { page ->
            val item = window.items[page]
            if (item.isVideo) {
                VideoPage(item = item)
            } else {
                PhotoPage(
                    item = item,
                    isCurrentPage = pagerState.currentPage == page,
                    onTap = { chromeVisible = !chromeVisible },
                    onZoomedChanged = { zoomed -> currentPageZoomed = zoomed },
                )
            }
        }
        ViewerTopBar(
            visible = chromeVisible,
            current = pagerState.currentPage,
            total = window.items.size,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * One full-fit photo/GIF page (issue #50 + its follow-up slice): Coil renders
 * the content URI scaled to fit the black canvas (letterboxed,
 * Mi-Gallery-style) inside a [Modifier.graphicsLayer] driven by
 * [ZoomableState], with gallery-standard gestures:
 *
 *  - a pinch (two fingers) zooms around the pinch centroid at any zoom
 *    level, including zooming in from rest;
 *  - one finger pans while the photo is zoomed ([ZoomableState.isZoomed]),
 *    clamped so the fitted content rectangle never leaves the viewport;
 *  - a double tap toggles between the [ZoomableState.DOUBLE_TAP_BOOST] zoom
 *    anchored on the tap point and 1x centered;
 *  - a single tap toggles the chrome bar at ANY zoom level (unchanged from
 *    the original slice).
 *
 * ## Pager interplay (v1 trade-off)
 *
 * [detectPanZoomGestures] lets a resting photo's single-finger drags flow to
 * the pager, so swiping between pages works exactly as before at 1x. While a
 * photo is zoomed, the viewer takes the pragmatic v1 route and DISABLES the
 * pager's user scrolling (`userScrollEnabled`) until the user zooms back out
 * (double-tap or pinch in): a zoomed photo can never be swiped away
 * mid-inspection, at the cost of not being able to edge-drag to the next
 * page while zoomed. That is the common gallery-app trade-off (it keeps the
 * gesture table small and predictable); a later slice can add an edge-pan
 * hand-off if it proves annoying. Leaving a page resets its zoom, so
 * returning to it always starts at 1x.
 *
 * ## Animated GIFs
 *
 * GIF items render through an [ImageRequest] carrying a per-request
 * `decoderFactory`, so ONLY the viewer animates: the grid keeps decoding
 * static first frames through the default loader, whose behavior is pinned
 * by issues #33/#34 --- `ToadGalleryApplication.newImageLoader` stays
 * unchanged. [ImageDecoderDecoder] wraps `android.graphics.ImageDecoder`
 * (API 28+), so the lexically visible [Build.VERSION.SDK_INT] guard routes
 * API 26/27 devices to [GifDecoder]; Android Lint's NewApi check is a hard
 * CI gate, and that guard is what keeps it green. Both decoders run purely
 * on-device (ADR-0014 --- no INTERNET permission exists to reach).
 *
 * The content description mirrors the grid's "Photo"/"GIF" semantics via
 * `cd_viewer_photo`.
 */
@Composable
private fun PhotoPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    onZoomedChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val zoomable = remember(item.uri) { ZoomableState() }

    // Compose mirror of [zoomable]: the pure class owns the math and the
    // truth, these snapshot values re-execute only the graphicsLayer after
    // every gesture callback.
    var scale by remember(item.uri) { mutableStateOf(ZoomableState.MIN_SCALE) }
    var offsetX by remember(item.uri) { mutableStateOf(0f) }
    var offsetY by remember(item.uri) { mutableStateOf(0f) }

    // The gesture surface's pixel size, fed to the transform math as the Fit
    // letterbox viewport (0 until the first layout pass; [ZoomableState]
    // guards against that).
    var viewportWidth by remember(item.uri) { mutableStateOf(0f) }
    var viewportHeight by remember(item.uri) { mutableStateOf(0f) }

    fun publishZoom(update: ZoomableState.() -> Unit) {
        zoomable.update()
        scale = zoomable.scale
        offsetX = zoomable.offsetX
        offsetY = zoomable.offsetY
        onZoomedChanged(zoomable.isZoomed)
    }

    // Leaving this page (the pager settled on a neighbor) resets its zoom.
    // Belt-and-braces with the uri-keyed remember blocks above, which only
    // help once the pager disposes the page: near pages stay composed, so an
    // explicit reset is the robust half.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            publishZoom { reset() }
        }
    }

    // Built once per page: recompositions happen on every pinch/pan frame
    // (they re-execute the graphicsLayer), and a freshly built, unequal
    // ImageRequest per frame would restart the Coil load each time.
    val imageModel = remember(item.uri) {
        if (item.isGif) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .decoderFactory(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoderDecoder.Factory()
                    } else {
                        GifDecoder.Factory()
                    },
                )
                .build()
        } else {
            item.uri
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                viewportWidth = size.width.toFloat()
                viewportHeight = size.height.toFloat()
            }
            // Pinch + pan. See ViewerGestures.kt for why this is not the
            // stock detectTransformGestures (it would starve the pager at
            // 1x).
            .pointerInput(item.uri) {
                detectPanZoomGestures(
                    canPan = { zoomable.isZoomed },
                    onGesture = { centroid, pan, zoom ->
                        publishZoom {
                            onTransform(
                                panX = pan.x,
                                panY = pan.y,
                                zoomFactor = zoom,
                                centroidX = centroid.x,
                                centroidY = centroid.y,
                                viewportWidth = viewportWidth,
                                viewportHeight = viewportHeight,
                                contentWidth = item.width.toFloat(),
                                contentHeight = item.height.toFloat(),
                            )
                        }
                    },
                )
            }
            // Tap + double tap; two pointerInput modifiers is the established
            // split (tap detection owns the up events, the transform
            // detector owns drags and pinches).
            .pointerInput(item.uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        publishZoom {
                            doubleTap(
                                viewportWidth = viewportWidth,
                                viewportHeight = viewportHeight,
                                contentWidth = item.width.toFloat(),
                                contentHeight = item.height.toFloat(),
                                tapX = tap.x,
                                tapY = tap.y,
                            )
                        }
                    },
                )
            },
    ) {
        AsyncImage(
            model = imageModel,
            contentDescription = stringResource(R.string.cd_viewer_photo),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * One video page (issue #50): an [ExoPlayer] bound to a views-based
 * [PlayerView] with its transport controls enabled, autoplaying the local
 * content URI with audio-focus handling so music/podcasts pause correctly.
 *
 * The player lives for as long as the page shows (remembered and keyed by the
 * item's URI, released on dispose --- swiping away or leaving the viewer
 * always releases). Playback errors set a flag that swaps the dead surface
 * for the `viewer_video_error_*` fallback copy; the same underlying device
 * limitation that makes `getFrameAtTime` return NULL for some thumbnails can
 * make playback undecodable, and the viewer must say so instead of showing a
 * frozen black frame.
 *
 * Zoom/pan are deliberately out of scope on video pages: `PlayerView` owns
 * its gestures, and the photo pipeline ([ZoomableState],
 * [detectPanZoomGestures]) never installs itself here.
 */
@Composable
private fun VideoPage(item: MediaItem) {
    val context = LocalContext.current
    var playbackFailed by remember(item.uri) { mutableStateOf(false) }
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed = true
                }
            })
            setMediaItem(PlayerMediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(item.uri) {
        onDispose { player.release() }
    }

    if (playbackFailed) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(R.drawable.ic_video_fallback),
                    contentDescription = null, // the title carries the meaning
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = stringResource(R.string.viewer_video_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    text = stringResource(R.string.viewer_video_error_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
                )
            }
        }
    } else {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                }
            },
            update = { view -> view.player = player },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The viewer's chrome: a top scrim with the back action and the
 * `viewer_counter` position text ("n of m"), fading in/out with [visible].
 * Chrome visibility is toggled by photo taps ([PhotoPage]); video pages keep
 * the PlayerView's own controls, so the top bar simply follows the last
 * toggled state there.
 */
@Composable
private fun ViewerTopBar(
    visible: Boolean,
    current: Int,
    total: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f))
                .statusBarsPadding(),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_back),
                    tint = Color.White,
                )
            }
            Text(
                text = stringResource(R.string.viewer_counter, current + 1, total),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp),
            )
        }
    }
}
