package io.github.dukeoftoad.gallery.ui.photos

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.annotation.AnyRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.Album
import io.github.dukeoftoad.gallery.data.AlbumRef
import io.github.dukeoftoad.gallery.data.FileOpFailureReason
import io.github.dukeoftoad.gallery.data.FileOpKind
import io.github.dukeoftoad.gallery.data.FileOpOutcome
import io.github.dukeoftoad.gallery.data.FileOpResult
import io.github.dukeoftoad.gallery.data.MediaFileOps
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.MediaStoreSource
import io.github.dukeoftoad.gallery.data.summarizeBatch
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
 * recovery, and undo.
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
     */
    fun refreshAlbums() {
        viewModelScope.launch {
            _albums.value = withContext(Dispatchers.IO) { albumSource.queryAlbums() }
        }
    }

    /** Current `DISPLAY_NAME` of [item]; null when the row is gone (below Q: null). */
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
        val ops = fileOps ?: return
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        val item = selection.items.singleOrNull() ?: return
        viewModelScope.launch {
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
            startBatch(FileOpKind.RENAME, listOf(item)) { ops.rename(it.uri, newName) }
        }
    }

    /** Duplicates every selected item in place (no dialog needed). */
    fun requestCopy() {
        val ops = fileOps ?: return
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        startBatch(FileOpKind.COPY, selection.items) { ops.copy(it, targetAlbum = null) }
    }

    /** Moves every selected item into [target] (album picker choice). */
    fun requestMove(target: Album) {
        val ops = fileOps ?: return
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        val targetRef = AlbumRef(bucketId = target.bucketId, bucketName = target.bucketName)
        startBatch(FileOpKind.MOVE, selection.items) { ops.move(it.uri, targetRef) }
    }

    /** Permanently deletes the selection (after the confirm dialog). */
    fun requestDelete() {
        val ops = fileOps ?: return
        val selection = _state.value as? FileOpsUiState.Selection ?: return
        startBatch(FileOpKind.DELETE, selection.items) { ops.delete(it) }
    }

    /** Cancels the active batch (progress dialog button); keeps the prefix. */
    fun cancelRunning() {
        runJob?.cancel()
    }

    /**
     * Activity result of the system consent dialog. Granted: re-runs only
     * the remaining work the consent covers. Declined: reports what did
     * change (undo stays available for those items) plus the honest
     * "permission declined" line.
     */
    fun onConsentResult(granted: Boolean) {
        val ops = fileOps ?: return
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
            FileOpKind.RENAME -> pending.rename?.let { request ->
                renameRequest = request
                startBatch(FileOpKind.RENAME, listOf(request.item), retry = true) {
                    ops.rename(it.uri, request.newName)
                }
            }
            FileOpKind.DELETE -> startBatch(
                kind = FileOpKind.DELETE,
                items = pending.deleteRetry,
                retry = true,
                alreadySucceeded = pending.deleteAlreadyDone,
            ) { ops.delete(it.uri) }
            FileOpKind.MOVE -> startBatch(
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
        val ops = fileOps ?: return
        if (payload == null) return
        viewModelScope.launch {
            var failures = 0
            when (payload) {
                is UndoPayload.Rename -> {
                    // The old name's typed-extension strip + original
                    // extension restore make this the exact inverse rename.
                    if (ops.rename(payload.item.uri, payload.oldName).outcome != FileOpOutcome.Success) {
                        failures++
                    }
                }
                is UndoPayload.Copies -> ops.runBatch(
                    items = payload.createdUris,
                    onResult = { _, result -> if (result.outcome != FileOpOutcome.Success) failures++ },
                ) { uri -> ops.delete(uri) } // app-owned: no consent needed
                is UndoPayload.Moves -> ops.runBatch(
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
                            pendingConsent = PendingConsent(kind = FileOpKind.RENAME, rename = req)
                            _events.tryEmit(
                                FileOpsEvent.RequestConsent(
                                    ops.createWriteConsentRequest(listOf(req.item.uri)),
                                ),
                            )
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
                    pendingConsent = PendingConsent(
                        kind = FileOpKind.MOVE,
                        moveRetry = consentAnchors,
                        moveAlreadyDone = undoEntries,
                        moveAlreadyCount = run.alreadySucceeded + summary.succeeded,
                    )
                    _events.tryEmit(
                        FileOpsEvent.RequestConsent(
                            ops.createDeleteConsentRequest(consentAnchors.map { it.original.uri }),
                        ),
                    )
                } else {
                    val movedTotal = run.alreadySucceeded + summary.succeeded
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
                    pendingConsent = PendingConsent(
                        kind = FileOpKind.DELETE,
                        deleteRetry = consentItems,
                        deleteAlreadyDone = succeededTotal,
                    )
                    _events.tryEmit(
                        FileOpsEvent.RequestConsent(
                            ops.createDeleteConsentRequest(consentItems.map { it.uri }),
                        ),
                    )
                } else {
                    if (succeededTotal > 0) emitDone(FileOpKind.DELETE, succeededTotal)
                    if (failedCount > 0) emitFailed(failedCount)
                }
            }
        }
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

    private companion object {
        /** One event per batch phase; 16 coalesces bursts without blocking tryEmit. */
        const val EVENT_BUFFER_CAPACITY = 16
    }

    companion object {
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

/**
 * A consent round awaiting its activity result. Built from what a batch
 * actually hit (never pre-guessed); the kind dictates which retry fields
 * are meaningful.
 */
private data class PendingConsent(
    val kind: FileOpKind,
    val rename: RenameRequest? = null,
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
