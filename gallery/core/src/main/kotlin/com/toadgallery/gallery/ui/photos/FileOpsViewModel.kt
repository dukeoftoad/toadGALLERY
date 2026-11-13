package com.toadgallery.gallery.ui.photos

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.annotation.AnyRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.Album
import com.toadgallery.gallery.data.AlbumRef
import com.toadgallery.gallery.data.FileOpFailureReason
import com.toadgallery.gallery.data.FileOpKind
import com.toadgallery.gallery.data.FileOpOutcome
import com.toadgallery.gallery.data.FileOpResult
import com.toadgallery.gallery.data.MediaFileOps
import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.data.MediaStoreSource
import com.toadgallery.gallery.data.summarizeBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State holder for the Photos-tab file operations (issue #30): selection
 * mode, batch execution with `(index, total)` progress, user-consent
 * recovery, and undo. Since issue #64 the SAME instance type drives the
 * album detail screen (`ui/albums/AlbumDetailScreen`) --- the class is
 * screen-agnostic by construction (no Photos paging/pipeline references),
 * and #64 needed zero code changes here: the album screen excludes its
 * own bucket from the move picker locally and owns its grid refresh.
 * Since issue #66 it also carries the single-item FAVORITE toggle
 * ([requestFavorite]) --- a metadata-only MediaStore write, deliberately
 * NOT routed through the batch runner (one item, no progress dialog), but
 * through the same consent round-trip and undo snackbar machinery.
 *
 * Deliberately a SEPARATE ViewModel from [PhotosViewModel] --- the paging
 * pipeline there is frozen for concurrent work (#38 follow-ups), and file
 * ops are an orthogonal state machine:
 *
 *  - `Idle`      : no selection; long-press starts one
 *  - `Selection` : the picked [MediaItem]s; the app bar shows Rename
 *                  (single selection only), Copy, Move, Delete
 *  - `Running`   : a batch is executing; `done`/`total` drive the
 *                  cancellable progress dialog for batches over the
 *                  threshold
 *
 * ## Execution model
 * Batches run strictly sequentially per item through
 * [MediaFileOps.runBatch] (copy/move/delete/rename are one-item-at-a-time
 * provider calls); progress updates the `Running` state, results collect
 * into an index-aligned list, and a user Cancel keeps the honest prefix:
 * the CancellationException is caught only to publish the partial summary,
 * then rethrown --- cancellation is never swallowed.
 *
 * ## Consent recovery (critical, real Android behavior --- API 29+)
 * Renaming or deleting items this app did not contribute throws a
 * recoverable `SecurityException`. [MediaFileOps] maps those to
 * `NeedsConsent`/`Partial(SECURITY)` outcomes; this ViewModel aggregates
 * ALL affected URIs of a batch into ONE
 * [MediaFileOps.createWriteConsentRequest] / [MediaFileOps.createDeleteConsentRequest]
 * and emits [FileOpsEvent.RequestConsent] with the `IntentSender`. The
 * screen launches it (`StartIntentSenderForResult`) and reports back via
 * [onConsentResult]; a grant re-runs exactly the remaining work (a
 * consent-blocked move only deletes the originals --- the copies already
 * exist and are never duplicated). The retry round is single-shot: if the
 * provider still refuses, the items surface as honest failures instead of
 * looping dialogs.
 *
 * ## Undo
 * Rename (restore old name), Copy (delete the created copy) and Move (move
 * the created copy back to its source album, remembered per item) carry
 * [UndoPayload]s on their completion snackbars; Delete has none (permanent
 * in v1 --- the confirm dialog says so). Move-undo is a NEW provider row in
 * the source album, not the resurrected original id (scoped storage cannot
 * restore a deleted row); documented in `docs/architecture/file-ops.md`.
 *
 * ## Refresh contract
 * After any op the ViewModel never hand-refreshes the timeline: MediaStore
 * writes fire the `ContentObserver` -> `PagingSource.invalidate()` pipeline
 * that #38 added (API 30+; API 29 keeps its documented snapshot staleness
 * until re-entry --- see file-ops.md limitations).
 *
 * ## API level
 * The whole surface is inert below Android 10: [fileOps] is null there (the
 * app is read-only below Q --- no consent protocol, no scoped-storage
 * inserts), so selection cannot even start. The UI relies on the same
 * `Build.VERSION.SDK_INT` gate.
 *
 * Privacy (ADR-0014/ADR-0006): every URI is a local content URI and every
 * op is provider I/O --- no network permission exists to violate.
 */
class FileOpsViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Q+ only, mirroring `MediaFileOps`' `@RequiresApi(Q)` contract (the
     * lexically lint-visible `SDK_INT` check is the guard NewApi lint
     * recognizes; same pattern as `PhotosViewModel`).
     */
    private val fileOps: MediaFileOps? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaFileOps(app.contentResolver)
        } else {
            null
        }

    /** Reuses the existing album query (issue #29) for the move picker. */
    private val albumSource = MediaStoreSource(app)

    private val _state = MutableStateFlow<FileOpsUiState>(FileOpsUiState.Idle)
    val state: StateFlow<FileOpsUiState> = _state.asStateFlow()

    /** Move-target albums; null while the (re)query is in flight. */
    private val _albums = MutableStateFlow<List<Album>?>(null)
    val albums: StateFlow<List<Album>?> = _albums.asStateFlow()

    /**
     * One-shot UI events (snackbars, consent requests). Buffered generously
     * and never replayed; the screen collects from first composition, which
     * always precedes any user-triggered op.
     */
    private val _events = MutableSharedFlow<FileOpsEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<FileOpsEvent> = _events.asSharedFlow()

    /** The active batch run (one at a time); Cancel cancels it. */
    private var runJob: Job? = null

    /**
     * The consent request awaiting the activity result, built when a batch
     * found consent-blocked items; consumed by [onConsentResult].
     */
    private var pendingConsent: PendingConsent? = null

    /**
     * The rename in flight (rename is single-selection only), holding the
     * pre-op display name for undo. Cleared when the run finishes.
     */
    private var renameRequest: RenameRequest? = null

    /**
     * The favorite toggle awaiting its consent round (issue #66), if any;
     * consumed by [onConsentResult]. Kept SEPARATE from [pendingConsent]
     * (which is keyed by [FileOpKind] for batch ops) so adding the favorite
     * flow needed no changes to the batch bookkeeping types.
     */
    private var pendingFavorite: FavoriteRequest? = null

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    /** Long-press enters selection; no-op while a batch runs or below Q. */
    fun onItemLongPressed(item: MediaItem) {
        if (fileOps == null) return // below API 29 the app is read-only
        when (_state.value) {
            is FileOpsUiState.Selection -> return // never reset a running selection
            is FileOpsUiState.Running -> return
            else -> Unit
        }
        _state.value = FileOpsUiState.Selection(listOf(item))
    }

    /** Tap toggles membership; deselecting the last item exits selection. */
    fun onItemTapped(item: MediaItem) {
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        val key = item.uri.toString()
        val updated = if (selection.items.any { it.uri.toString() == key }) {
            selection.items.filterNot { it.uri.toString() == key }
        } else {
            selection.items + item
        }
        _state.value = if (updated.isEmpty()) {
            FileOpsUiState.Idle
        } else {
            FileOpsUiState.Selection(updated)
        }
    }

    /** Exits selection mode (app-bar close button). Never interrupts a run. */
    fun exitSelection() {
        if (_state.value is FileOpsUiState.Running) return
        _state.value = FileOpsUiState.Idle
    }

    // ------------------------------------------------------------------
    // Move picker input
    // ------------------------------------------------------------------

    /**
     * Re-queries albums through the existing [MediaStoreSource.queryAlbums]
     * (issue #29's query, no new pipeline). Called every time the picker
     * opens --- counts and buckets change as moves land.
     *
     * r19: the query runs inside `runCatching { ... }.getOrElse { ... }`
     * because this fires the moment the user opens the Move picker from a
     * selection, and a provider failure (permission revoked mid-session, a
     * provider SQLiteException, a missing column) previously escaped
     * `viewModelScope.launch` uncaught and killed the process mid-dialog.
     * The fallback publishes the EMPTY list --- the picker state its
     * dialog already renders as "no targets", distinct from null, its
     * spinner (a failed query must not spin forever) --- and narrates
     * through the same snackbar event channel the op outcomes use. The
     * catch sits INSIDE the IO hop, so cancellation of the hop itself
     * still propagates (the query is a plain blocking call).
     */
    fun refreshAlbums() {
        viewModelScope.launch {
            _albums.value = withContext(Dispatchers.IO) {
                runCatching { albumSource.queryAlbums() }
                    .getOrElse {
                        _events.tryEmit(
                            FileOpsEvent.ShowSnackbar(R.string.file_ops_albums_failed),
                        )
                        emptyList()
                    }
            }
        }
    }

    /**
     * Current `DISPLAY_NAME` of [item]; null when the row is gone (below Q:
     * null --- [fileOps] is only constructed on Q+).
     */
    // Lint cannot see the null-guard: fileOps is null below API 29, so the
    // @RequiresApi(Q) callee is only reached on Q+ devices.
    @Suppress("NewApi")
    suspend fun displayNameOf(item: MediaItem): String? = fileOps?.displayNameOf(item.uri)

    // ------------------------------------------------------------------
    // Operations
    // ------------------------------------------------------------------

    /**
     * Renames the single selected item. Fetches the pre-op name first ---
     * the undo payload needs it, and the ops layer needs the original
     * extension to restore (see `MediaFileOps.rename`).
     */
    fun requestRename(newName: String) {
        // fileOps is non-null only on Q+ (see the fileOps property) --- every
        // @Suppress("NewApi") in this class rests on that runtime invariant,
        // which lint cannot follow through the nullable-field elvis pattern.
        @Suppress("NewApi")
        val ops = fileOps ?: return
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        val item = selection.items.singleOrNull() ?: return
        viewModelScope.launch {
            @Suppress("NewApi")
            val oldName = ops.displayNameOf(item.uri)
            if (oldName == null) {
                _events.tryEmit(FileOpsEvent.ShowSnackbar(
                    messageRes = R.plurals.file_ops_failed,
                    pluralCount = 1,
                ))
                return@launch
            }
            // The name lookup suspends; bail out if the user already started
            // another op from the same selection in the meantime.
            if (_state.value !is FileOpsUiState.Selection) return@launch
            renameRequest = RenameRequest(item = item, newName = newName, oldName = oldName)
            @Suppress("NewApi")
            startBatch(FileOpKind.RENAME, listOf(item)) { ops.rename(it.uri, newName) }
        }
    }

    /** Duplicates every selected item in place (no dialog needed). */
    fun requestCopy() {
        @Suppress("NewApi")
        val ops = fileOps ?: return
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        @Suppress("NewApi")
        startBatch(FileOpKind.COPY, selection.items) { ops.copy(it, targetAlbum = null) }
    }

    /** Moves every selected item into [target] (album picker choice). */
    fun requestMove(target: Album) {
        @Suppress("NewApi")
        val ops = fileOps ?: return
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        val targetRef = AlbumRef(bucketId = target.bucketId, bucketName = target.bucketName)
        @Suppress("NewApi")
        startBatch(FileOpKind.MOVE, selection.items) { ops.move(it.uri, targetRef) }
    }

    /**
     * Deletes the selection (after the confirm dialog) --- to the recycle
     * bin on API 30+ (issue #31), permanently on API 29 (no MediaStore
     * trash protocol exists below R; that device tier keeps the #30 v1
     * behavior).
     *
     * Trash path: ONE `createTrashRequest` covers the whole batch, and even
     * app-owned rows complete through the launcher (silently), so this is
     * a consent round trip, not a batch run. On RESULT_OK the rows are
     * already trashed; the snackbar narrates the count and the MediaStore
     * observer refreshes the timeline.
     *
     * r76.1 crash fix: the consent build is a PROVIDER call that can throw
     * (an unrecognized/stale row, a volume that refuses trash). It runs
     * inside the scope on IO and a refusal folds into the honest "N
     * failed" narration --- the first cut built the request bare on the
     * main thread, and one rejection killed the process on the Delete tap
     * before the system dialog could appear.
     */
    fun requestDelete() {
        @Suppress("NewApi")
        val ops = fileOps ?: return
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            viewModelScope.launch {
                @Suppress("NewApi")
                val sender = ops.createTrashConsentRequest(selection.items.map { it.uri })
                if (sender == null) {
                    emitFailed(selection.items.size)
                    return@launch
                }
                // The state snapshot is re-checked so a delete fired twice
                // in the await window cannot stage two consent rounds.
                if (_state.value !is FileOpsUiState.Selection) return@launch
                pendingConsent = PendingConsent(
                    kind = FileOpKind.DELETE,
                    trash = true,
                    deleteRetry = selection.items,
                )
                _events.tryEmit(FileOpsEvent.RequestConsent(sender))
            }
        } else {
            @Suppress("NewApi")
            startBatch(FileOpKind.DELETE, selection.items) { ops.delete(it) }
        }
    }

    /** Cancels the active batch (progress dialog button); keeps the prefix. */
    fun cancelRunning() {
        runJob?.cancel()
    }

    // ------------------------------------------------------------------
    // Favorite toggle (issue #66)
    // ------------------------------------------------------------------

    /**
     * Toggles the favorite flag of ONE item (issue #66) --- the viewer's
     * heart action. Deliberately NOT a batch run: one item needs no progress
     * dialog and no aggregation, so this is a single [MediaFileOps.setFavorite]
     * with the same consent recovery and undo narration as the batch ops.
     *
     * ## API level contract (honest tiering)
     *  - Below Q the whole call is a no-op (`fileOps` is null --- the app is
     *    read-only there, like every other write op).
     *  - On Q, a consent-blocked write (another app's item) surfaces as an
     *    honest failure: the recovery request (`createWriteRequest`) is R+,
     *    so looping a dialog that cannot exist would only lie.
     *  - On R+, one write-consent round-trip for the item, then a single
     *    retry ([retry]); a second block fails honestly --- the same
     *    bounded, single-shot retry discipline the batch ops use.
     *
     * Success narrates `favorite_added` / `favorite_removed` with an undo
     * payload that toggles back (a favorite write is its own inverse). The
     * MediaStore change observer refreshes the grids on R+; below that the
     * overlays catch up on re-entry (the documented Q staleness class).
     */
    fun requestFavorite(item: MediaItem, favorite: Boolean, retry: Boolean = false) {
        @Suppress("NewApi")
        val ops = fileOps ?: return // below API 29 the app is read-only
        viewModelScope.launch {
            @Suppress("NewApi")
            val result = ops.setFavorite(item.uri, favorite)
            when (result.outcome) {
                is FileOpOutcome.Success -> _events.tryEmit(
                    FileOpsEvent.ShowSnackbar(
                        messageRes = if (favorite) {
                            R.string.favorite_added
                        } else {
                            R.string.favorite_removed
                        },
                        // Undo = the inverse update; the payload carries the
                        // PRE-op state so the viewer can also correct its
                        // heart override (the snackbar event IS the success
                        // signal for it).
                        undo = UndoPayload.Favorite(item = item, previous = !favorite),
                    ),
                )
                is FileOpOutcome.NeedsConsent -> {
                    if (!retry && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // r76.1: the build runs on IO inside this coroutine
                        // and a provider refusal folds into the honest
                        // failure narration (never a main-thread throw).
                        @Suppress("NewApi")
                        val sender = ops.createWriteConsentRequest(listOf(item.uri))
                        if (sender == null) {
                            emitFailed(1)
                        } else {
                            pendingFavorite = FavoriteRequest(item = item, favorite = favorite)
                            _events.tryEmit(FileOpsEvent.RequestConsent(sender))
                        }
                    } else {
                        // Q (no recovery protocol) or a still-blocked retry:
                        // bounded, honest failure --- never a dialog loop.
                        emitFailed(1)
                    }
                }
                else -> emitFailed(1)
            }
        }
    }

    /**
     * Activity result of the system consent dialog. Granted: re-runs only
     * the remaining work the consent covers. Declined: reports what did
     * change (undo stays available for those items) plus the honest
     * "permission declined" line.
     */
    fun onConsentResult(granted: Boolean) {
        @Suppress("NewApi")
        val ops = fileOps ?: return
        // Issue #66: the favorite toggle has its own single-item pending
        // state (see pendingFavorite); resolve it FIRST so a favorite
        // round-trip can never be mistaken for a batch consent.
        pendingFavorite?.let { request ->
            pendingFavorite = null
            if (granted) {
                requestFavorite(request.item, request.favorite, retry = true)
            } else {
                _events.tryEmit(FileOpsEvent.ShowSnackbar(R.string.file_ops_consent_denied))
            }
            return
        }
        val pending = pendingConsent ?: return
        pendingConsent = null
        if (!granted) {
            when (pending.kind) {
                FileOpKind.RENAME -> Unit // nothing changed for rename
                FileOpKind.DELETE -> {
                    if (pending.deleteAlreadyDone > 0) emitDone(FileOpKind.DELETE, pending.deleteAlreadyDone)
                }
                FileOpKind.MOVE -> {
                    if (pending.moveAlreadyCount > 0) {
                        emitDone(
                            FileOpKind.MOVE,
                            pending.moveAlreadyCount,
                            undo = pending.moveAlreadyDone
                                .takeIf { it.isNotEmpty() }
                                ?.let { UndoPayload.Moves(it) },
                        )
                    }
                }
                FileOpKind.COPY -> Unit // inserts never need consent
            }
            _events.tryEmit(FileOpsEvent.ShowSnackbar(R.string.file_ops_consent_denied))
            return
        }
        when (pending.kind) {
            FileOpKind.DELETE if pending.trash -> {
                // Issue #31: the trash request already performed the move on
                // RESULT_OK --- narrate and leave selection (nothing to retry).
                _events.tryEmit(
                    FileOpsEvent.ShowSnackbar(
                        messageRes = R.plurals.trash_moved,
                        pluralCount = pending.deleteRetry.size,
                    ),
                )
                _state.value = FileOpsUiState.Idle
            }
            FileOpKind.RENAME -> pending.rename?.let { request ->
                renameRequest = request
                @Suppress("NewApi")
                startBatch(FileOpKind.RENAME, listOf(request.item), retry = true) {
                    ops.rename(it.uri, request.newName)
                }
            }
            FileOpKind.DELETE -> @Suppress("NewApi") startBatch(
                kind = FileOpKind.DELETE,
                items = pending.deleteRetry,
                retry = true,
                alreadySucceeded = pending.deleteAlreadyDone,
            ) { ops.delete(it.uri) }
            FileOpKind.MOVE -> @Suppress("NewApi") startBatch(
                kind = FileOpKind.MOVE,
                items = pending.moveRetry.map { it.original },
                retry = true,
                moveUndoAnchors = pending.moveRetry,
                alreadySucceeded = pending.moveAlreadyCount,
            ) { ops.delete(it.uri) } // the copies already exist; only originals remain
            FileOpKind.COPY -> Unit
        }
    }

    /**
     * Executes an undo from a snackbar action. Best-effort by design: undo
     * ops run without confirmations (app-owned copies / consent already
     * granted), and a failed undo surfaces [R.string.file_ops_undo_failed]
     * instead of pretending nothing happened.
     */
    fun performUndo(payload: UndoPayload?) {
        @Suppress("NewApi")
        val ops = fileOps ?: return
        if (payload == null) return
        viewModelScope.launch {
            var failures = 0
            when (payload) {
                is UndoPayload.Rename -> {
                    // The old name's typed-extension strip + original
                    // extension restore make this the exact inverse rename.
                    @Suppress("NewApi")
                    if (ops.rename(payload.item.uri, payload.oldName).outcome != FileOpOutcome.Success) {
                        failures++
                    }
                }
                is UndoPayload.Favorite -> {
                    // A favorite write is its own inverse: restore the
                    // pre-op flag (issue #66).
                    @Suppress("NewApi")
                    if (ops.setFavorite(payload.item.uri, payload.previous).outcome != FileOpOutcome.Success) {
                        failures++
                    }
                }
                is UndoPayload.Copies -> @Suppress("NewApi") ops.runBatch(
                    items = payload.createdUris,
                    onResult = { _, result -> if (result.outcome != FileOpOutcome.Success) failures++ },
                ) { uri -> ops.delete(uri) } // app-owned: no consent needed
                is UndoPayload.Moves -> @Suppress("NewApi") ops.runBatch(
                    items = payload.entries,
                    onResult = { _, result -> if (result.outcome != FileOpOutcome.Success) failures++ },
                ) { entry -> ops.move(entry.createdUri, entry.sourceAlbum) }
            }
            if (failures > 0) {
                _events.tryEmit(FileOpsEvent.ShowSnackbar(R.string.file_ops_undo_failed))
            }
        }
    }

    // ------------------------------------------------------------------
    // Batch runner
    // ------------------------------------------------------------------

    /**
     * Runs one batch. Cancels any previous run (the UI cannot stack ops ---
     * the actions bar only exists in `Selection`), publishes `Running`
     * progress, and routes the collected outcomes into [finishRun] on both
     * normal and cancelled completion.
     */
    private fun startBatch(
        kind: FileOpKind,
        items: List<MediaItem>,
        retry: Boolean = false,
        moveUndoAnchors: List<MoveFinish>? = null,
        alreadySucceeded: Int = 0,
        operation: suspend (MediaItem) -> FileOpResult,
    ) {
        val ops = fileOps ?: return
        if (items.isEmpty()) {
            _state.value = FileOpsUiState.Idle
            return
        }
        runJob?.cancel()
        val results = mutableListOf<FileOpResult>()
        _state.value = FileOpsUiState.Running(kind = kind, done = 0, total = items.size)
        runJob = viewModelScope.launch {
            try {
                @Suppress("NewApi")
                ops.runBatch(
                    items = items,
                    onResult = { _, result -> results += result },
                    onProgress = { done, total ->
                        _state.value = FileOpsUiState.Running(kind = kind, done = done, total = total)
                    },
                    operation = operation,
                )
                finishRun(
                    BatchRun(
                        kind = kind,
                        items = items,
                        results = results,
                        cancelled = false,
                        retry = retry,
                        moveUndoAnchors = moveUndoAnchors,
                        alreadySucceeded = alreadySucceeded,
                    ),
                )
            } catch (c: CancellationException) {
                // Publish the honest prefix, then let the cancellation
                // complete --- never swallow it.
                finishRun(
                    BatchRun(
                        kind = kind,
                        items = items,
                        results = results,
                        cancelled = true,
                        retry = retry,
                        moveUndoAnchors = moveUndoAnchors,
                        alreadySucceeded = alreadySucceeded,
                    ),
                )
                throw c
            }
        }
    }

    /**
     * Reduces one finished (or cancelled) run into state + events. Non-
     * suspending on purpose: it must be callable from the cancellation
     * handler of a cancelled coroutine (plain StateFlow/SharedFlow writes).
     */
    private fun finishRun(run: BatchRun) {
        val ops = fileOps
        _state.value = FileOpsUiState.Idle
        if (ops == null) return // below Q: unreachable, defensive
        val summary = summarizeBatch(run.results.map { it.outcome }, cancelled = run.cancelled)
        val succeededTotal = run.alreadySucceeded + summary.succeeded

        if (run.cancelled) {
            val processed = run.alreadySucceeded + run.results.size
            val total = run.alreadySucceeded + run.items.size
            when (run.kind) {
                FileOpKind.COPY -> if (succeededTotal > 0) {
                    emitDone(
                        FileOpKind.COPY,
                        succeededTotal,
                        undo = run.results.mapNotNull { it.createdUri }
                            .takeIf { it.isNotEmpty() }
                            ?.let { UndoPayload.Copies(it) },
                    )
                }
                FileOpKind.MOVE -> if (succeededTotal > 0) {
                    emitDone(
                        FileOpKind.MOVE,
                        succeededTotal,
                        undo = collectMoveUndo(run).takeIf { it.isNotEmpty() }?.let { UndoPayload.Moves(it) },
                    )
                }
                FileOpKind.DELETE -> if (succeededTotal > 0) emitDone(FileOpKind.DELETE, succeededTotal)
                FileOpKind.RENAME -> Unit // single item, never cancellable from the UI
            }
            _events.tryEmit(
                FileOpsEvent.ShowSnackbar(
                    messageRes = R.string.file_ops_cancelled_batch,
                    formatArgs = listOf(processed, total),
                ),
            )
            return
        }

        when (run.kind) {
            FileOpKind.RENAME -> {
                val request = renameRequest
                renameRequest = null
                when (run.results.firstOrNull()?.outcome) {
                    is FileOpOutcome.Success -> _events.tryEmit(
                        FileOpsEvent.ShowSnackbar(
                            messageRes = R.string.file_ops_done_rename,
                            undo = request?.let { UndoPayload.Rename(item = it.item, oldName = it.oldName) },
                        ),
                    )
                    is FileOpOutcome.NeedsConsent -> {
                        val req = request
                        if (!run.retry && req != null) {
                            // r76.1: the consent build is a provider call;
                            // it runs on IO inside its own scope child (the
                            // reducer stays non-suspending) and a refusal
                            // narrates the bounded failure --- the first
                            // cut threw out of the main-thread reducer and
                            // killed the process.
                            viewModelScope.launch {
                                @Suppress("NewApi")
                                val sender = ops.createWriteConsentRequest(listOf(req.item.uri))
                                if (sender == null) {
                                    emitFailed(1)
                                } else {
                                    pendingConsent = PendingConsent(kind = FileOpKind.RENAME, rename = req)
                                    _events.tryEmit(FileOpsEvent.RequestConsent(sender))
                                }
                            }
                        } else {
                            emitFailed(1) // retry round still blocked: bounded, honest
                        }
                    }
                    else -> emitFailed(1)
                }
            }

            FileOpKind.COPY -> {
                val created = run.results.mapNotNull { it.createdUri }
                if (succeededTotal > 0) {
                    emitDone(
                        FileOpKind.COPY,
                        succeededTotal,
                        undo = created.takeIf { it.isNotEmpty() }?.let { UndoPayload.Copies(it) },
                    )
                }
                // Inserts never need consent; count defensively.
                val failedCount = summary.failed + summary.needingConsent
                if (failedCount > 0) emitFailed(failedCount)
            }

            FileOpKind.MOVE -> {
                val undoEntries = collectMoveUndo(run)
                val consentAnchors = mutableListOf<MoveFinish>()
                var partialOther = 0
                var failedCount = summary.failed
                run.results.forEachIndexed { index, result ->
                    when (val outcome = result.outcome) {
                        is FileOpOutcome.Partial -> {
                            val created = result.createdUri
                            when {
                                outcome.deleteReason == FileOpFailureReason.SECURITY &&
                                    !run.retry && created != null -> {
                                    // Copy landed; original delete needs consent.
                                    consentAnchors += MoveFinish(
                                        original = run.items[index],
                                        createdUri = created,
                                        sourceAlbum = result.sourceAlbum,
                                    )
                                }
                                outcome.deleteReason == FileOpFailureReason.SECURITY -> failedCount++
                                else -> partialOther++
                            }
                        }
                        is FileOpOutcome.NeedsConsent -> failedCount++ // impossible for move; defensive
                        else -> Unit
                    }
                }
                if (consentAnchors.isNotEmpty()) {
                    // r76.1: provider call on IO inside a scope child; a
                    // refusal lands on the moved/failed narration below
                    // (the consent-anchored originals count as failed ---
                    // their copies stay behind, honest partial state).
                    viewModelScope.launch {
                        @Suppress("NewApi")
                        val sender = ops.createDeleteConsentRequest(
                            consentAnchors.map { it.original.uri },
                        )
                        if (sender == null) {
                            narrateMoveOutcome(
                                movedTotal = run.alreadySucceeded + summary.succeeded,
                                undoEntries = undoEntries,
                                partialOther = partialOther,
                                failedCount = failedCount + consentAnchors.size,
                            )
                        } else {
                            pendingConsent = PendingConsent(
                                kind = FileOpKind.MOVE,
                                moveRetry = consentAnchors,
                                moveAlreadyDone = undoEntries,
                                moveAlreadyCount = run.alreadySucceeded + summary.succeeded,
                            )
                            _events.tryEmit(FileOpsEvent.RequestConsent(sender))
                        }
                    }
                } else {
                    narrateMoveOutcome(
                        movedTotal = run.alreadySucceeded + summary.succeeded,
                        undoEntries = undoEntries,
                        partialOther = partialOther,
                        failedCount = failedCount,
                    )
                }
            }

            FileOpKind.DELETE -> {
                val consentItems = mutableListOf<MediaItem>()
                var failedCount = summary.failed
                run.results.forEachIndexed { index, result ->
                    if (result.outcome == FileOpOutcome.NeedsConsent) {
                        if (!run.retry) consentItems += run.items[index] else failedCount++
                    }
                }
                if (consentItems.isNotEmpty()) {
                    // r76.1: provider call on IO inside a scope child; a
                    // refusal narrates the honest totals instead of the
                    // process dying on the Delete tap (the field-report
                    // crash this round fixes).
                    viewModelScope.launch {
                        @Suppress("NewApi")
                        val sender = ops.createDeleteConsentRequest(consentItems.map { it.uri })
                        if (sender == null) {
                            if (succeededTotal > 0) emitDone(FileOpKind.DELETE, succeededTotal)
                            emitFailed(failedCount + consentItems.size)
                        } else {
                            pendingConsent = PendingConsent(
                                kind = FileOpKind.DELETE,
                                deleteRetry = consentItems,
                                deleteAlreadyDone = succeededTotal,
                            )
                            _events.tryEmit(FileOpsEvent.RequestConsent(sender))
                        }
                    }
                } else {
                    if (succeededTotal > 0) emitDone(FileOpKind.DELETE, succeededTotal)
                    if (failedCount > 0) emitFailed(failedCount)
                }
            }
        }
    }

    /**
     * The move run's success narration, shared by the plain finish and the
     * r76.1 consent-refusal path (whose consent-anchored originals join the
     * failed count --- their copies stay behind, honest partial state).
     * Non-suspending: plain StateFlow/SharedFlow writes only.
     */
    private fun narrateMoveOutcome(
        movedTotal: Int,
        undoEntries: List<MoveBack>,
        partialOther: Int,
        failedCount: Int,
    ) {
        if (movedTotal > 0) {
            emitDone(
                FileOpKind.MOVE,
                movedTotal,
                undo = undoEntries.takeIf { it.isNotEmpty() }?.let { UndoPayload.Moves(it) },
            )
        }
        if (partialOther > 0) {
            _events.tryEmit(FileOpsEvent.ShowSnackbar(R.string.file_ops_move_partial))
        }
        if (failedCount > 0) emitFailed(failedCount)
    }

    /**
     * Undo anchors for a finished (or cancelled) move run: retry runs read
     * them from [BatchRun.moveUndoAnchors] (their own results are plain
     * deletes with no createdUri); first runs read them off the results.
     */
    private fun collectMoveUndo(run: BatchRun): List<MoveBack> {
        val entries = mutableListOf<MoveBack>()
        run.results.forEachIndexed { index, result ->
            if (result.outcome == FileOpOutcome.Success) {
                val anchor = run.moveUndoAnchors?.getOrNull(index)
                val createdUri = anchor?.createdUri ?: result.createdUri
                val sourceAlbum = anchor?.sourceAlbum ?: result.sourceAlbum
                if (createdUri != null && sourceAlbum != null) {
                    entries += MoveBack(createdUri = createdUri, sourceAlbum = sourceAlbum)
                }
            }
        }
        return entries
    }

    /** Completion snackbar; RENAME uses a plain string, the rest plurals. */
    private fun emitDone(kind: FileOpKind, count: Int, undo: UndoPayload? = null) {
        val messageRes = when (kind) {
            FileOpKind.RENAME -> R.string.file_ops_done_rename
            FileOpKind.COPY -> R.plurals.file_ops_done_copy
            FileOpKind.MOVE -> R.plurals.file_ops_done_move
            FileOpKind.DELETE -> R.plurals.file_ops_done_delete
        }
        _events.tryEmit(
            FileOpsEvent.ShowSnackbar(
                messageRes = messageRes,
                // A <string> (rename) must go through getString, not
                // getQuantityString; the plurals take the count as quantity.
                pluralCount = if (kind == FileOpKind.RENAME) -1 else count,
                undo = undo,
            ),
        )
    }

    private fun emitFailed(count: Int) {
        _events.tryEmit(
            FileOpsEvent.ShowSnackbar(messageRes = R.plurals.file_ops_failed, pluralCount = count),
        )
    }

    companion object {
        /** One event per batch phase; 16 coalesces bursts without blocking tryEmit. */
        private const val EVENT_BUFFER_CAPACITY = 16

        /**
         * Batches over [PROGRESS_DIALOG_THRESHOLD] items show the cancellable
         * progress dialog ("Moving 3 of 12…"); smaller batches (and the
         * single-item rename) finish without it. Public: PhotosScreen keys
         * the dialog's visibility off the same constant.
         */
        const val PROGRESS_DIALOG_THRESHOLD = 5
    }
}

// ---------------------------------------------------------------------
// Public state / event / undo types (referenced by PhotosScreen)
// ---------------------------------------------------------------------

/** Selection/batch state of the Photos-tab file ops (issue #30). */
sealed interface FileOpsUiState {
    /** No selection (grid behaves exactly as before this slice). */
    data object Idle : FileOpsUiState

    /** The picked items, in pick order; tapping toggles membership. */
    data class Selection(val items: List<MediaItem>) : FileOpsUiState

    /** A batch is executing; `done` of `total` items completed. */
    data class Running(val kind: FileOpKind, val done: Int, val total: Int) : FileOpsUiState
}

/** One-shot UI events surfaced by [FileOpsViewModel]. */
sealed interface FileOpsEvent {
    /**
     * A snackbar: [messageRes] with either [pluralCount] (>= 0: a
     * `R.plurals` resource, quantity == first format arg) or [formatArgs]
     * (a plain `R.string`), plus an optional undo action whose payload is
     * opaque to the screen and returned to [FileOpsViewModel.performUndo].
     */
    data class ShowSnackbar(
        @AnyRes val messageRes: Int,
        val pluralCount: Int = -1,
        val formatArgs: List<Any> = emptyList(),
        val undo: UndoPayload? = null,
    ) : FileOpsEvent

    /**
     * A system consent request (API 29+ recovery) the screen must launch
     * via `StartIntentSenderForResult`, reporting the outcome to
     * [FileOpsViewModel.onConsentResult].
     */
    data class RequestConsent(val intentSender: IntentSender) : FileOpsEvent
}

/**
 * Opaque undo payload a snackbar hands back to the ViewModel.
 * Delete never carries one (permanent in v1).
 */
sealed interface UndoPayload {
    /** Rename-undo: restore [oldName]. */
    data class Rename(val item: MediaItem, val oldName: String) : UndoPayload

    /**
     * Favorite-undo (issue #66): restore [previous] --- the flag value
     * before the toggled write. Doubles as the success signal the viewer
     * reads to correct its page-local heart override.
     */
    data class Favorite(val item: MediaItem, val previous: Boolean) : UndoPayload

    /** Copy-undo: delete every created copy (app-owned, no consent). */
    data class Copies(val createdUris: List<Uri>) : UndoPayload

    /** Move-undo: move every created copy back to its source album. */
    data class Moves(val entries: List<MoveBack>) : UndoPayload
}

/** One move-undo anchor: the created copy and where it came from. */
data class MoveBack(
    val createdUri: Uri,
    val sourceAlbum: AlbumRef,
)

// ---------------------------------------------------------------------
// Private run/consent bookkeeping
// ---------------------------------------------------------------------

/** The rename in flight (single selection only); feeds undo and consent retry. */
private data class RenameRequest(
    val item: MediaItem,
    val newName: String,
    val oldName: String,
)

/** The favorite toggle awaiting its consent round (issue #66). */
private data class FavoriteRequest(
    val item: MediaItem,
    val favorite: Boolean,
)

/**
 * A consent round awaiting its activity result. Built from what a batch
 * actually hit (never pre-guessed); the kind dictates which retry fields
 * are meaningful.
 */
private data class PendingConsent(
    val kind: FileOpKind,
    val rename: RenameRequest? = null,
    /**
     * DELETE-as-trash (issue #31, API 30+): the launched request IS the
     * trash op --- on RESULT_OK the rows are already trashed, there is no
     * retry batch. `deleteRetry` still carries the selection for the
     * snackbar count.
     */
    val trash: Boolean = false,
    /** DELETE retry: exactly the items whose delete was consent-blocked. */
    val deleteRetry: List<MediaItem> = emptyList(),
    /** DELETE: successes that landed before the prompt (reported on decline). */
    val deleteAlreadyDone: Int = 0,
    /** MOVE retry: originals to delete once granted (copies already exist). */
    val moveRetry: List<MoveFinish> = emptyList(),
    /** MOVE: fully-moved undo anchors collected before the prompt. */
    val moveAlreadyDone: List<MoveBack> = emptyList(),
    /** MOVE: fully-moved count collected before the prompt. */
    val moveAlreadyCount: Int = 0,
)

/** MOVE: the leftover work of one partially-moved item. */
private data class MoveFinish(
    val original: MediaItem,
    val createdUri: Uri,
    val sourceAlbum: AlbumRef?,
)

/** One finished (or cancelled) batch, ready for [FileOpsViewModel]'s reducer. */
private data class BatchRun(
    val kind: FileOpKind,
    val items: List<MediaItem>,
    /** Index-aligned with [items] (prefix only, when cancelled). */
    val results: List<FileOpResult>,
    val cancelled: Boolean,
    /** Retry round: consent was granted; further NeedsConsent results fail honestly. */
    val retry: Boolean,
    /** MOVE retry: result i completes moveUndoAnchors[i]. */
    val moveUndoAnchors: List<MoveFinish>?,
    /** Successes of the first round (consent-deferred batches report the sum). */
    val alreadySucceeded: Int,
)
