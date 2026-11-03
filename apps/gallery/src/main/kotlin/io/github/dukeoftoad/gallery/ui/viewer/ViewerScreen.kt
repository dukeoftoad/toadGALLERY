package io.github.dukeoftoad.gallery.ui.viewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.RenameCheck
import io.github.dukeoftoad.gallery.data.baseNameOf
import io.github.dukeoftoad.gallery.data.checkRenameInput
import io.github.dukeoftoad.gallery.edit.contract.EditOutcome
import io.github.dukeoftoad.gallery.ui.photos.FileOpsEvent
import io.github.dukeoftoad.gallery.ui.photos.FileOpsUiState
import io.github.dukeoftoad.gallery.ui.photos.FileOpsViewModel
import io.github.dukeoftoad.gallery.ui.photos.UndoPayload
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 *    handling, and built with DECODER FALLBACK enabled (issue #65 --- see
 *    [VideoPage]). Playback is LIFECYCLE- and PAGER-aware (r19a): audio
 *    pauses when the app is backgrounded (ON_START resumes only a page
 *    that was actually playing when it stopped) and when the page stops
 *    being the current page, so a swiped-away neighbor never plays under
 *    its successor --- see [VideoPage]. A decode/playback failure renders
 *    the honest `viewer_video_error_*` copy plus a Retry button instead of
 *    a dead black page.
 *
 * A scrim top bar carries the back action, the position counter
 * (`viewer_counter`) and --- issue #63 --- the operations for the CURRENT
 * page's item: Details (a [ModalBottomSheet] fed by the pure
 * [ViewerInfoFormat] plus one optional MediaStore lookup, see
 * [ViewerInfoSheet]), Share (`ACTION_SEND` with the local content URI),
 * Rename and Delete (both through the viewer-scoped [FileOpsViewModel] ---
 * the SAME state machine, consent round-trip and undo snackbars the Photos
 * tab uses for issue #30, driven one item at a time). Issue #66 adds the
 * FAVORITE toggle as the first action: a heart that reflects the current
 * page's favorite state (filled vs outlined) and drives the SAME
 * viewer-scoped [FileOpsViewModel] through its single-item favorite op
 * (`requestFavorite`) --- one `IS_FAVORITE` write, the write-consent
 * round-trip on Android 11+ for items this app didn't contribute, and an
 * undo snackbar that toggles back. Like Rename/Delete it rides the Q+
 * write gate (`canModify`); below Android 10 the app is read-only and the
 * heart stays hidden. The frozen tap-time window cannot mutate its items,
 * so the screen keeps page-local OVERRIDES (keyed by content URI) that the
 * success snackbar's `UndoPayload.Favorite` updates --- the heart is always
 * honest for pages the user toggled in this viewer session, and the grids
 * pick the persisted state up through the MediaStore observer on R+.
 * Rename/Delete are hidden below Android 10, where the app is read-only
 * (the lexically visible `SDK_INT` gate NewApi lint recognizes; the
 * ViewModel is inert there too). Icon actions keep the 48dp `IconButton`
 * touch floor (DesignTokens) and TalkBack descriptions via
 * `cd_viewer_action_*`.
 *
 * ### Window mutation after ops (issue #63, honest v1)
 * DELETE success drops the deleted item's page from the shell-scoped
 * window ([ViewerViewModel.removeItem] -> [ViewerWindow.withoutPageAt]); an
 * emptied window falls through the defensive empty-check below and closes
 * the viewer. RENAME needs no mutation at all: MediaStore rename rewrites
 * `DISPLAY_NAME` only, so the row id, the content URI and therefore the
 * pager's URI-keyed pages stay valid. Success detection rides the
 * ViewModel's public event surface: exactly the two delete-success
 * narrations (`trash_moved`, `file_ops_done_delete`) mean "the row is gone",
 * and only they drop a page --- failures and declined consent keep it.
 *
 * The window comes from the shell-scoped [ViewerViewModel] ---
 * [ToadGalleryApp] passes the SAME instance the grid screens wrote to
 * (issue #27 hand-off pattern); a null/empty window (defensive: deep link or
 * race, or the last page was just deleted) navigates straight back.
 *
 * Privacy (ADR-0006 / ADR-0014): every model value is a local MediaStore
 * content URI; ExoPlayer is fed content URIs only, Share hands the SAME
 * local content URI to the user-chosen app with a read grant
 * (`FLAG_GRANT_READ_URI_PERMISSION`) --- nothing is uploaded or copied
 * anywhere the app itself controls, and the app holds no INTERNET
 * permission, so nothing here can reach the network even with a hostile
 * URI. The GIF decoders (coil-gif) decode purely on-device.
 */
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    onBack: () -> Unit,
    /**
     * Phase 4: opens the metadata Details sub-screen for one item; wired
     * from the info sheet's Full-details entry. Empty lambda default keeps
     * every existing call site compiling unchanged.
     */
    onOpenDetails: (item: MediaItem) -> Unit = {},
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
        // entry point must not strand the user on a black screen. Issue #63
        // makes this reachable legitimately: deleting the LAST page of the
        // window empties it, and closing the viewer is the honest result.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    // ---- Slideshow (r27) ----
    // The shell-scoped flag the Stories shelf / grid overflow actions armed
    // (or the top bar's own toggle flips); the interval is the user's last
    // in-session choice. Leaving the viewer ALWAYS stops a run --- a
    // slideshow must never silently survive into the next plain tap-to-view
    // --- so the screen owns exactly one dispose-side stop.
    val slideshowActive by viewModel.slideshowActive.collectAsState()
    val slideshowIntervalMillis by viewModel.slideshowIntervalMillis.collectAsState()
    DisposableEffect(viewModel) {
        onDispose { viewModel.stopSlideshow() }
    }

    // ---- Viewer file operations (issue #63) ----
    // A viewer-route-scoped instance of the SAME #30 state machine the
    // Photos tab uses (plain AndroidViewModel; read-only for this slice ---
    // driven here strictly through its public API). It owns the batch
    // runner, the consent round-trip and the undo payloads; the viewer only
    // selects one item at a time and mirrors the surface PhotosScreen wires.
    val fileOpsViewModel: FileOpsViewModel = viewModel()
    val fileOpsState by fileOpsViewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The system consent launcher (API 29+ recovery), the minimal mirror of
    // PhotosScreen's wiring: RequestConsent hands over an IntentSender, the
    // screen launches it and reports the result code; the ViewModel retries
    // on RESULT_OK. Leaving any lingering selection afterwards keeps a
    // declined round from wedging the next single-item op on another page
    // (the ViewModel never resets a running selection by itself).
    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        fileOpsViewModel.onConsentResult(result.resultCode == Activity.RESULT_OK)
        fileOpsViewModel.exitSelection()
    }

    // ---- The Gallery <-> Edit contract launch (r29, Issue #14 gate) ----
    // The editor resolves EXACTLY (contract app ID + negotiated version,
    // never implicit resolution); the reply parses through the shared
    // EditResultParser. Refresh needs NO rescan and NO extra wiring here:
    // the saved copy is a MediaStore INSERT, which the existing
    // ContentObserver-driven paging invalidation (issue #38 follow-up)
    // picks up on return --- the contract's "Gallery refreshes via
    // MediaStore change observation" property.
    val editorLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        when (val outcome = parseEditResult(result.resultCode, result.data)) {
            is EditOutcome.SavedCopy -> scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.viewer_edit_saved),
                )
            }
            is EditOutcome.Canceled -> Unit // nothing was modified anywhere
            is EditOutcome.Error -> scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.viewer_edit_error,
                        outcome.errorMessage.ifEmpty { outcome.errorCode },
                    ),
                )
            }
        }
    }

    /**
     * Edit (r29): launches the toadGALLERY Edit prototype on the current
     * IMAGE (videos are out of the v1 editor scope), through the contract:
     * not installed -> honest message + the third-party chooser fallback;
     * outdated -> refusal + update nudge; disabled -> explanatory message;
     * ready -> the launch intent with the read-only URI grant.
     */
    fun editItem(item: MediaItem) {
        if (item.isVideo) return
        when (val resolution = resolveEditor(context)) {
            is EditorResolution.Ready -> editorLauncher.launch(
                buildEditorIntent(item.uri, resolution),
            )
            is EditorResolution.Disabled -> scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.viewer_edit_disabled),
                )
            }
            is EditorResolution.Outdated -> scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.viewer_edit_outdated),
                )
            }
            is EditorResolution.NotInstalled -> {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.viewer_edit_not_installed),
                    )
                }
                // The contract's fallback: third-party editors, the user
                // chooses. A device with no editor at all simply stays put.
                launchFallbackEditor(context, item.uri)
            }
        }
    }

    // The delete whose outcome is still unresolved (the item's URI, not a
    // page index --- the user can swipe while the system dialog is up).
    // Cleared on the first narration that follows; only the two
    // delete-SUCCESS narrations drop the page.
    var deleteInFlight by remember { mutableStateOf<String?>(null) }

    // Issue #66: page-local favorite overrides over the frozen tap-time
    // window. The window is a snapshot (ViewerViewModel owns no mutation
    // for favorites), so a toggle's persisted result is mirrored here keyed
    // by content URI; the success snackbar's UndoPayload.Favorite updates
    // it, and undoing toggles it back just as cleanly.
    var favoriteOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    /** The effective favorite state of [item] (override > window snapshot). */
    fun favoriteStateOf(item: MediaItem): Boolean =
        favoriteOverrides[item.uri.toString()] ?: item.isFavorite

    /**
     * Toggles the current page's favorite flag (issue #66): one item, one
     * `IS_FAVORITE` write through the SAME viewer-scoped ops ViewModel
     * (consent round-trip + undo included); no selection is needed because
     * the favorite op takes its item directly.
     */
    fun requestToggleFavorite(item: MediaItem) {
        if (fileOpsState is FileOpsUiState.Running) return
        fileOpsViewModel.requestFavorite(item, favorite = !favoriteStateOf(item))
    }

    // Dialog/sheet state, screen-local exactly like PhotosTimeline's: one-tap
    // decisions that need not survive process death.
    var infoTarget by remember { mutableStateOf<MediaItem?>(null) }
    var renameTarget by remember { mutableStateOf<MediaItem?>(null) }
    var renamePrefill by remember { mutableStateOf<String?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<MediaItem?>(null) }

    LaunchedEffect(Unit) {
        fileOpsViewModel.events.collect { event ->
            when (event) {
                is FileOpsEvent.RequestConsent -> consentLauncher.launch(
                    IntentSenderRequest.Builder(event.intentSender).build(),
                )
                is FileOpsEvent.ShowSnackbar -> {
                    // Strings resolve against the current configuration at
                    // display time; the event carries only ids/args (same
                    // pattern as PhotosScreen).
                    val message = if (event.pluralCount >= 0) {
                        context.resources.getQuantityString(
                            event.messageRes, event.pluralCount, event.pluralCount,
                        )
                    } else if (event.formatArgs.isEmpty()) {
                        context.getString(event.messageRes)
                    } else {
                        context.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    }
                    val actionLabel = event.undo
                        ?.let { context.getString(R.string.file_ops_undo_action) }
                    val shown = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = actionLabel,
                        duration = if (event.undo != null) {
                            SnackbarDuration.Long
                        } else {
                            SnackbarDuration.Short
                        },
                    )
                    if (shown == SnackbarResult.ActionPerformed) {
                        fileOpsViewModel.performUndo(event.undo)
                    }
                    // Issue #66: a favorite success narration carries its
                    // UndoPayload.Favorite on EVERY favorite write (including
                    // undo) --- the payload is the success signal that keeps
                    // the page-local heart override honest: the new state is
                    // the inverse of the payload's `previous`.
                    (event.undo as? UndoPayload.Favorite)?.let { favorite ->
                        favoriteOverrides = favoriteOverrides +
                            (favorite.item.uri.toString() to !favorite.previous)
                    }
                    // Issue #63 window mutation: the ViewModel's public
                    // event surface narrates delete success through exactly
                    // two resources --- the API 30+ trash round and the
                    // batch delete --- and only those mean "the row is
                    // gone". Failures (file_ops_failed) and declined
                    // consent keep the page.
                    val uriString = deleteInFlight
                    if (uriString != null) {
                        deleteInFlight = null
                        if (event.messageRes == R.plurals.trash_moved ||
                            event.messageRes == R.plurals.file_ops_done_delete
                        ) {
                            viewModel.removeItem(uriString)
                        }
                    }
                }
            }
        }
    }

    /** Selects [item] into the ops ViewModel and fires the delete round. */
    fun requestDelete(item: MediaItem) {
        if (fileOpsState is FileOpsUiState.Running) return
        deleteInFlight = item.uri.toString()
        // The public single-item path: selection of exactly one item, then
        // the op. exitSelection() before re-selecting clears any stale
        // selection (a declined round leaves one behind), and the one after
        // requestDelete() clears the R+ trash path's selection --- the
        // request is already armed in the ViewModel's pendingConsent, and a
        // decline would otherwise never return to Idle. On the API 29 batch
        // path the second call is a no-op: state is already Running, which
        // the guard skips.
        fileOpsViewModel.exitSelection()
        fileOpsViewModel.onItemLongPressed(item)
        fileOpsViewModel.requestDelete()
        fileOpsViewModel.exitSelection()
    }

    /** Selects [item] into the ops ViewModel and renames it to [newName]. */
    fun requestRename(item: MediaItem, newName: String) {
        if (fileOpsState is FileOpsUiState.Running) return
        fileOpsViewModel.exitSelection()
        fileOpsViewModel.onItemLongPressed(item)
        fileOpsViewModel.requestRename(newName)
        // No trailing exitSelection here: requestRename re-checks the
        // selection after its name lookup suspends; its own run settles the
        // state back to Idle on completion.
    }

    /**
     * Share (issue #63): plain ACTION_SEND with the item's LOCAL content
     * URI and a read grant, through the system chooser. ADR-0006/ADR-0014:
     * the app never touches a network or remote URI --- it only lends
     * read access to whatever app the user picks.
     */
    fun shareItem(item: MediaItem) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(sendIntent, null))
        } catch (_: ActivityNotFoundException) {
            // Honest narration instead of a crash on ROMs without a chooser.
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.viewer_share_unavailable),
                )
            }
        }
    }

    // Rename prefill: the current name minus its extension (the extension
    // is restored on submit; see checkRenameInput). Below Q the lookup
    // yields null (the ops layer is Q+ only) --- the actions are hidden
    // there anyway, so the dialog is unreachable.
    LaunchedEffect(renameTarget) {
        renamePrefill = renameTarget?.let { item ->
            fileOpsViewModel.displayNameOf(item)?.let { name -> baseNameOf(name) }
        }
    }

    var chromeVisible by remember { mutableStateOf(true) }
    // True while the CURRENT page's photo is meaningfully zoomed
    // ([ZoomableState.isZoomed]): locks the pager's user scrolling, the
    // documented v1 trade-off in PhotoPage's KDoc. Because swiping is
    // disabled while this is set, the zoomed page is by construction the
    // settled page, so a single flag is enough.
    //
    // Keyed to [window] (r19a): a delete swaps the window object and the
    // `key(window)` below rebuilds the pager around a fresh seed page, and
    // the old zoomed PhotoPage unwinds through DISPOSAL --- its
    // leave-resets-zoom [LaunchedEffect] is cancelled, never run --- so an
    // unkeyed flag would survive the mutation as `true` and leave
    // `userScrollEnabled` false for the rest of the viewer session (locked
    // until some later zoom round-trip happened to clear it). Re-seeding
    // the flag on exactly the mutation the pager re-keys on is the minimal
    // sound fix, and `false` is the honest seed: the rebuilt pages start at
    // 1x (their uri-keyed [ZoomableState]s are fresh), and the window is
    // otherwise frozen, so this key change fires only on the delete path
    // that needed it.
    var currentPageZoomed by remember(window) { mutableStateOf(false) }

    // A delete replaces the window object; key() gives the pager a fresh
    // state seeded at the window's computed follow-up page (the pager's
    // own index bookkeeping cannot know which page a removal meant to
    // keep). The window is otherwise frozen, so this re-keys ONLY on the
    // initial open and on delete mutations.
    key(window) {
        val pagerState = rememberPagerState(initialPage = window.startIndex) {
            window.items.size
        }

        // The slideshow's auto-advance loop (r27): while active, sleep the
        // configured interval, then advance one page (wrapping to the first
        // after the last --- the pure [SlideshowTiming.nextIndex] rule) with
        // the pager's own settle animation, whose position sweep is what the
        // crossfade layer below consumes. Restarted (not re-timed) when the
        // interval, the window, or the active flag changes; a MANUAL swipe
        // during a run does not reset the running timer (documented v1
        // behavior --- the advance lands on the page the user landed on,
        // via the fresh pagerState.currentPage read each iteration). A
        // one-page window keeps the loop honest: nextIndex answers 0, so
        // the "advance" re-settles the only page there is.
        LaunchedEffect(slideshowActive, slideshowIntervalMillis, window) {
            while (slideshowActive) {
                delay(slideshowIntervalMillis)
                val next = SlideshowTiming.nextIndex(
                    pagerState.currentPage,
                    window.items.size,
                )
                pagerState.animateScrollToPage(next)
            }
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
                // The slideshow's crossfade (r27): while active, each page's
                // alpha falls with its distance from the settling position
                // (the pure ViewerCrossfade rule); the graphicsLayer lambda
                // reads the pager state DEFERRED, so the fade animates on
                // scroll frames without recomposing the page. The plain
                // swipe keeps the pager's stock look byte-identical.
                val fadeModifier = if (slideshowActive) {
                    Modifier.graphicsLayer {
                        alpha = ViewerCrossfade.pageAlpha(
                            page,
                            pagerState.currentPage + pagerState.currentPageOffsetFraction,
                        )
                    }
                } else {
                    Modifier
                }
                Box(modifier = Modifier.fillMaxSize().then(fadeModifier)) {
                    if (item.isVideo) {
                        VideoPage(
                            item = item,
                            // The SAME current-page seam PhotoPage receives
                            // (r19a): a video page pauses its player the
                            // moment it stops being the settled page, the
                            // audio-side mirror of PhotoPage's leave-resets-
                            // zoom. See VideoPage for the semantics.
                            isCurrentPage = pagerState.currentPage == page,
                        )
                    } else {
                        PhotoPage(
                            item = item,
                            isCurrentPage = pagerState.currentPage == page,
                            onTap = { chromeVisible = !chromeVisible },
                            onZoomedChanged = { zoomed -> currentPageZoomed = zoomed },
                        )
                    }
                }
            }
            ViewerTopBar(
                visible = chromeVisible,
                current = pagerState.currentPage,
                total = window.items.size,
                // Read-only below Android 10 (the app holds no write
                // consent protocol there): the favorite toggle appears only
                // on Q+ alongside rename/delete, the lexically visible
                // guard NewApi lint recognizes.
                canModify = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                isFavorite = favoriteStateOf(window.items[pagerState.currentPage]),
                slideshowActive = slideshowActive,
                slideshowIntervalMillis = slideshowIntervalMillis,
                onToggleSlideshow = {
                    if (slideshowActive) {
                        viewModel.stopSlideshow()
                    } else {
                        viewModel.startSlideshow()
                    }
                },
                onSlideshowInterval = viewModel::setSlideshowInterval,
                onBack = onBack,
                onInfo = { infoTarget = window.items[pagerState.currentPage] },
                onShare = { shareItem(window.items[pagerState.currentPage]) },
                canEdit = !window.items[pagerState.currentPage].isVideo,
                onEdit = { editItem(window.items[pagerState.currentPage]) },
                onToggleFavorite = {
                    requestToggleFavorite(window.items[pagerState.currentPage])
                },
                onRename = { renameTarget = window.items[pagerState.currentPage] },
                onDelete = { deleteConfirmTarget = window.items[pagerState.currentPage] },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            // The viewer is full-screen; ops narration surfaces through a
            // SnackbarHost over the canvas rather than escaping to a shell
            // scaffold the user cannot see here.
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }

    // ---- Viewer dialogs & the info sheet (issue #63) ----

    infoTarget?.let { item ->
        ViewerInfoSheet(
            item = item,
            onDismiss = { infoTarget = null },
            onOpenDetails = { onOpenDetails(item) },
        )
    }
    renameTarget?.let { item ->
        ViewerRenameDialog(
            prefill = renamePrefill,
            onConfirm = { name ->
                requestRename(item, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
    deleteConfirmTarget?.let { item ->
        ViewerDeleteDialog(
            onConfirm = {
                deleteConfirmTarget = null
                requestDelete(item)
            },
            onDismiss = { deleteConfirmTarget = null },
        )
    }
}

/**
 * The info sheet (issue #63): a Material 3 [ModalBottomSheet] with the
 * current item's details. Every value comes from the pure
 * [ViewerInfoFormat] (JVM-pinned) over [MediaItem] fields plus ONE
 * optional MediaStore lookup ([queryViewerInfoLookup]) for the two columns
 * the item does not carry (display name, size); `null` from any formatter
 * renders the localized `viewer_info_unknown` --- honest "not known"
 * beats a fabricated number.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerInfoSheet(item: MediaItem, onDismiss: () -> Unit, onOpenDetails: () -> Unit) {
    val context = LocalContext.current
    var lookup by remember(item.uri) { mutableStateOf<ViewerInfoLookup?>(null) }
    LaunchedEffect(item.uri) {
        lookup = queryViewerInfoLookup(context.contentResolver, item.uri)
    }
    // A date is localized prose (unlike a duration); the current
    // configuration's locale feeds the pure formatter.
    val locale: Locale = LocalConfiguration.current.locales[0]
    val unknown = stringResource(R.string.viewer_info_unknown)
    fun value(text: String?): String = text ?: unknown

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.viewer_info_title),
                style = MaterialTheme.typography.titleMedium,
            )
            ViewerInfoRow(
                label = stringResource(R.string.viewer_info_name),
                value = value(ViewerInfoFormat.displayName(lookup?.displayName, item.uri.toString())),
            )
            ViewerInfoRow(
                label = stringResource(R.string.viewer_info_type),
                value = item.mimeType,
            )
            ViewerInfoRow(
                label = stringResource(R.string.viewer_info_size),
                value = value(ViewerInfoFormat.size(lookup?.sizeBytes)),
            )
            ViewerInfoRow(
                label = stringResource(R.string.viewer_info_dimensions),
                value = value(ViewerInfoFormat.dimensions(item.width, item.height)),
            )
            if (item.isVideo) {
                ViewerInfoRow(
                    label = stringResource(R.string.viewer_info_duration),
                    value = value(ViewerInfoFormat.duration(item.durationMs, item.isVideo)),
                )
            }
            ViewerInfoRow(
                label = stringResource(R.string.viewer_info_date_added),
                value = value(ViewerInfoFormat.dateAdded(item.dateAddedSec, locale)),
            )
            ViewerInfoRow(
                label = stringResource(R.string.viewer_info_uri),
                value = item.uri.toString(),
            )
            // Phase 4: the full metadata Details sub-screen (dimensions,
            // exposure triangle, GPS, the availability-honest block tree)
            // opens from here; this sheet stays the light MediaStore-row
            // summary.
            TextButton(onClick = onOpenDetails) {
                Text(stringResource(R.string.viewer_info_open_full_details))
            }
        }
    }
}

/** One label/value pair of the info sheet; label muted, value prominent. */
@Composable
private fun ViewerInfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("viewer_info_row"),
        )
    }
}

/**
 * The viewer's rename dialog (issue #63): the SAME UX contract as the
 * Photos rename dialog --- prefilled with the current base name (extension
 * stripped; it is restored on submit), live-validated by the pure
 * [checkRenameInput], confirm enabled only for valid input. Reimplemented
 * here rather than shared because the Photos dialog is private to its
 * screen and file ownership forbids editing it; the copy strings are the
 * SAME resources, so the two dialogs cannot drift in wording.
 */
@Composable
private fun ViewerRenameDialog(
    prefill: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Re-seeds once the (fast) provider lookup lands; typing before that is
    // impossible to validate meaningfully, so the field starts empty and
    // the confirm disabled.
    var text by remember(prefill) { mutableStateOf(prefill ?: "") }
    val check = remember(text) { checkRenameInput(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = check is RenameCheck.Valid,
            ) {
                Text(stringResource(R.string.file_ops_action_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = { Text(stringResource(R.string.file_ops_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.file_ops_rename_label)) },
                isError = text.isNotBlank() && check !is RenameCheck.Valid,
                singleLine = true,
                supportingText = {
                    if (text.isNotBlank() && check !is RenameCheck.Valid) {
                        Text(stringResource(R.string.file_ops_rename_invalid))
                    }
                },
                modifier = Modifier.testTag("viewer_dialog_rename_field"),
            )
        },
        modifier = Modifier.testTag("viewer_dialog_rename"),
    )
}

/**
 * The viewer's delete confirmation (issue #63): the SAME honest copy as the
 * Photos delete dialog --- recycle bin on Android 11+, permanent on
 * Android 10 and below (the plurals resource covers the single item).
 */
@Composable
private fun ViewerDeleteDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.file_ops_delete_confirm_yes))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
        title = {
            Text(
                pluralStringResource(
                    R.plurals.file_ops_delete_confirm_title, 1, 1,
                ),
            )
        },
        text = { Text(stringResource(R.string.file_ops_delete_confirm_body)) },
        modifier = Modifier.testTag("viewer_dialog_delete"),
    )
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
 * ## Decoder fallback (issue #65)
 * The player is built with [DefaultRenderersFactory] and
 * `setEnableDecoderFallback(true)`: a real Xiaomi device log (issue #65,
 * c2.qti.hevc.decoder) shows 10-bit HEVC (`hvc1.2.4.H123.90`) in an MKV
 * container being rejected by the hardware decoder ---
 * `format_supported=NO_EXCEEDS_CAPABILITIES`, then `Decoder failed:
 * c2.qti.hevc.decoder` and a dead black surface. With fallback enabled,
 * ExoPlayer continues down its decoder-init list instead of dying on the
 * first hardware rejection, so the software `c2.android.hevc.decoder`
 * (or any other capable init) gets the stream. AudioAttributes and the
 * rest of the configuration are applied after the build, unchanged.
 *
 * The player lives for as long as the page shows (remembered and keyed by the
 * item's URI, released on dispose --- swiping away or leaving the viewer
 * always releases). Playback errors swap the dead surface for the honest
 * `viewer_video_error_*` fallback copy --- with a Retry button (issue #65)
 * that re-prepares the same item; the failure flag is keyed by the URI, so
 * swiping to another page always starts clean. The same underlying device
 * limitation that makes `getFrameAtTime` return NULL for some thumbnails
 * can make playback undecodable even after a retry, and the viewer must say
 * so instead of showing a frozen black frame.
 *
 * ## Lifecycle- and pager-aware playback (r19a)
 * The player used to be created `playWhenReady = true` and touched by
 * nothing but disposal, so a video kept playing its audio after the app
 * was backgrounded, and the pager keeping NEAR pages composed meant two
 * players could sound at once around a swipe. Two seams close that:
 *
 *  - **Lifecycle**: a [LifecycleEventObserver] on the [LocalLifecycleOwner]
 *    pauses on `ON_STOP` and resumes on `ON_START` ONLY when the page was
 *    actually playing when it stopped --- `playWhenReady` is the intent
 *    signal, and it reads false for a page the pager seam below (or the
 *    user) already paused, so a mere foreground round-trip never
 *    resurrects either. A user-paused video therefore stays paused across
 *    backgrounding, which is the honest reading of "resume only if it was
 *    playing".
 *  - **Pager**: the [isCurrentPage] seam PhotoPage already receives
 *    (`pagerState.currentPage == page`, threaded by the pager below):
 *    the moment this page stops being the settled page, its player pauses
 *    --- the audio-side mirror of PhotoPage's leave-resets-zoom. Without
 *    it, a page one swipe away (still composed) would keep playing under
 *    its successor, and a neighbor composed mid-swipe would start under
 *    the page being left (its initial pause lands in the same composition
 *    frame, before the first audio render). Returning to a paused page
 *    keeps it paused: the PlayerView's own play control is the resume,
 *    exactly the "leaving resets, returning does not restore" half
 *    PhotoPage implements for zoom.
 *
 * Zoom/pan are deliberately out of scope on video pages: `PlayerView` owns
 * its gestures, and the photo pipeline ([ZoomableState],
 * [detectPanZoomGestures]) never installs itself here.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPage(item: MediaItem, isCurrentPage: Boolean) {
    val context = LocalContext.current
    var playbackFailed by remember(item.uri) { mutableStateOf(false) }
    val player = remember(item.uri) {
        // Issue #65: decoder fallback on top of the default renderers, so a
        // hardware decoder rejection (10-bit HEVC NO_EXCEEDS_CAPABILITIES on
        // c2.qti.hevc.decoder in the filed device log) degrades to the next
        // capable decoder instead of ending playback. See the KDoc above.
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
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

    // Leaving this page (the pager settled on a neighbor): pause --- the
    // audio-side mirror of PhotoPage's leave-resets-zoom effect above. See
    // the KDoc's pager section for why returning does NOT auto-resume.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            player.pause()
        }
    }

    // Lifecycle-aware playback: ON_STOP pauses, ON_START resumes only a
    // page that was playing when it stopped (see the KDoc's lifecycle
    // section for why playWhenReady is the intent signal). Keyed on the
    // lifecycle owner AND the uri: a uri change rebuilds the player above,
    // and the relaunched effect must observe the NEW instance, never keep
    // driving the released old one.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, item.uri) {
        var wasPlayingWhenStopped = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlayingWhenStopped = player.playWhenReady
                    player.pause()
                }
                Lifecycle.Event.ON_START -> if (wasPlayingWhenStopped) player.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** Issue #65: re-prepare the same item after a playback failure. */
    fun retryPlayback() {
        playbackFailed = false
        player.setMediaItem(PlayerMediaItem.fromUri(item.uri))
        player.prepare()
        player.playWhenReady = true
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
                TextButton(
                    onClick = ::retryPlayback,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    // 48dp touch floor (DesignTokens): IconButton-sized
                    // padding keeps the target comfortable on the black
                    // canvas.
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(stringResource(R.string.state_action_retry))
                }
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
 * The viewer's chrome: a top scrim with the back action, the
 * `viewer_counter` position text ("n of m") and --- issue #63 --- the
 * actions for the CURRENT page's item: Details, Share, and (Q+ only,
 * [canModify]) Rename and Delete. Issue #66 adds the favorite toggle as
 * the first action: a filled heart while [isFavorite], an outlined heart
 * otherwise (both CORE-set icons). Fading in/out with [visible]; chrome
 * visibility is toggled by photo taps ([PhotoPage]); video pages keep the
 * PlayerView's own controls, so the top bar simply follows the last
 * toggled state there.
 *
 * r27 adds the slideshow controls after the counter: a toggle
 * (PlayArrow while stopped / an X while running, both CORE icons --- the
 * slideshow STOPS rather than pauses, and the glyph says so) and, while a
 * run is active, an interval menu (DateRange, CORE) with the three
 * [SlideshowTiming] options check-marked. Config-while-relevant: the
 * interval button appears only during a run (the default 5s applies
 * otherwise), keeping the bar's resting density unchanged for everyone
 * who never starts a slideshow.
 *
 * Every action is an `IconButton` --- the 48dp touch floor DesignTokens
 * mandates --- with a `cd_viewer_action_*` content description, and all
 * icons come from the material-icons CORE set the repo already depends on
 * (Info, Share, Edit, Delete, Favorite, FavoriteBorder, PlayArrow, Clear,
 * DateRange, Check; no extended-icons dependency is added).
 */
@Composable
private fun ViewerTopBar(
    visible: Boolean,
    current: Int,
    total: Int,
    canModify: Boolean,
    isFavorite: Boolean,
    slideshowActive: Boolean,
    slideshowIntervalMillis: Long,
    onToggleSlideshow: () -> Unit,
    onSlideshowInterval: (Long) -> Unit,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
    canEdit: Boolean,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The interval menu's open flag, local to the bar (the menu closes on
    // any pick or outside tap; the CHOICE rides the callback up).
    var intervalMenuOpen by remember { mutableStateOf(false) }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f))
                .statusBarsPadding()
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
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
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            // ---- Slideshow (r27) ----
            IconButton(
                onClick = onToggleSlideshow,
                modifier = Modifier.testTag("viewer_action_slideshow"),
            ) {
                Icon(
                    imageVector = if (slideshowActive) {
                        Icons.Filled.Clear
                    } else {
                        Icons.Filled.PlayArrow
                    },
                    contentDescription = stringResource(
                        if (slideshowActive) {
                            R.string.cd_viewer_action_slideshow_stop
                        } else {
                            R.string.cd_viewer_action_slideshow_start
                        }
                    ),
                    tint = Color.White,
                )
            }
            if (slideshowActive) {
                Box {
                    IconButton(
                        onClick = { intervalMenuOpen = true },
                        modifier = Modifier.testTag("viewer_action_slideshow_interval"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DateRange,
                            contentDescription = stringResource(
                                R.string.cd_viewer_action_slideshow_interval
                            ),
                            tint = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = intervalMenuOpen,
                        onDismissRequest = { intervalMenuOpen = false },
                    ) {
                        SlideshowTiming.INTERVALS_MILLIS.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            R.string.slideshow_interval_option,
                                            option / 1000,
                                        )
                                    )
                                },
                                onClick = {
                                    intervalMenuOpen = false
                                    onSlideshowInterval(option)
                                },
                                trailingIcon = if (option == slideshowIntervalMillis) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null, // the state is the checkmark
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = onInfo,
                modifier = Modifier.testTag("viewer_action_info"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.cd_viewer_action_info),
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = onShare,
                modifier = Modifier.testTag("viewer_action_share"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = stringResource(R.string.cd_viewer_action_share),
                    tint = Color.White,
                )
            }
            // The editor launch (r29): image pages only --- the v1 editor
            // scope is stills, so the button is absent on video pages
            // (honest scope, no inert affordance).
            if (canEdit) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("viewer_action_edit"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Create,
                        contentDescription = stringResource(R.string.cd_viewer_action_edit),
                        tint = Color.White,
                    )
                }
            }
            if (canModify) {
                // Issue #66: the favorite toggle. Filled heart while the
                // current page is a favorite, outlined otherwise; the
                // content description flips with the state so TalkBack
                // announces the action, not the glyph.
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.testTag("viewer_action_favorite"),
                ) {
                    Icon(
                        imageVector = if (isFavorite) {
                            Icons.Filled.Favorite
                        } else {
                            Icons.Filled.FavoriteBorder
                        },
                        contentDescription = stringResource(
                            if (isFavorite) {
                                R.string.cd_viewer_action_favorite_remove
                            } else {
                                R.string.cd_viewer_action_favorite_add
                            },
                        ),
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = onRename,
                    modifier = Modifier.testTag("viewer_action_rename"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.cd_viewer_action_rename),
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("viewer_action_delete"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_viewer_action_delete),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
