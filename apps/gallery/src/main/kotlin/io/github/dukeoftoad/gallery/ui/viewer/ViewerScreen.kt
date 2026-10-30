package io.github.dukeoftoad.gallery.ui.viewer

import android.app.Activity
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
 *  - photos/GIFs render full-fit via Coil ([ContentScale.Fit]; GIFs show a
 *    static frame in this slice --- animated decode is deferred, see issue
 *    #50's follow-up list), and a tap toggles the chrome bar;
 *  - videos play in-app through media3's [ExoPlayer] in a views-based
 *    [PlayerView] (its own transport controls), autoplaying with proper audio
 *    focus handling. A decode/playback failure (e.g. the device codec cannot
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
 * content URI; ExoPlayer is fed content URIs only. The app holds no INTERNET
 * permission, so nothing here can reach the network even with a hostile URI.
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
            key = { page -> window.items[page].uri.toString() },
        ) { page ->
            val item = window.items[page]
            if (item.isVideo) {
                VideoPage(item = item)
            } else {
                PhotoPage(
                    item = item,
                    onTap = { chromeVisible = !chromeVisible },
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
 * One full-fit photo/GIF page: Coil renders the content URI scaled to fit the
 * black canvas (letterboxed, Mi-Gallery-style), and any tap toggles the chrome
 * bar. The content description mirrors the grid's "Photo"/"GIF" semantics via
 * `cd_viewer_photo`.
 */
@Composable
private fun PhotoPage(
    item: MediaItem,
    onTap: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.uri) {
                detectTapGestures(onTap = { onTap() })
            },
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = stringResource(R.string.cd_viewer_photo),
            modifier = Modifier.fillMaxSize(),
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
