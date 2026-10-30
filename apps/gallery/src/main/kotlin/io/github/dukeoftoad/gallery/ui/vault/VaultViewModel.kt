package io.github.dukeoftoad.gallery.ui.vault

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.vault.VaultIndexEntry
import io.github.dukeoftoad.gallery.data.vault.VaultMoveOutcome
import io.github.dukeoftoad.gallery.data.vault.VaultRepository
import io.github.dukeoftoad.gallery.data.vault.VaultRestoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lock phases of the hidden vault (issue #32, ADR-0017).
 *
 * [Unsupported] is the pre-Android-10 tier: the vault's MediaStore leg
 * (removing rows on move, publishing rows on restore) rides the file-ops
 * consent contract, which exists only from API 29 --- below that the app is
 * read-only and the whole feature stays inert, exactly like the file-ops
 * slice. [Resolving] is the sub-second first frame while the sealed PIN
 * record is read.
 */
sealed interface VaultUiState {
    data object Unsupported : VaultUiState
    data object Resolving : VaultUiState

    /** No PIN record on disk yet: the screen shows the setup flow. */
    data object NotConfigured : VaultUiState

    /** Configured but locked --- also the auto-lock landing state (ON_STOP). */
    data object Locked : VaultUiState

    /** Unlocked session: the decrypted index, in persistence order. */
    data class Unlocked(val entries: List<VaultIndexEntry>) : VaultUiState
}

/**
 * True once the sealed PIN record exists ([VaultUiState.Locked] /
 * [VaultUiState.Unlocked]); drives the Move to vault action's visibility on
 * the Photos timeline.
 */
val VaultUiState.isConfigured: Boolean
    get() = this is VaultUiState.Locked || this is VaultUiState.Unlocked

/** One async vault operation in flight, for honest progress narration. */
data class VaultBusy(
    val kind: VaultBusyKind,
    val done: Int = 0,
    val total: Int = 0,
)

enum class VaultBusyKind {
    /** PBKDF2 setup or PIN verification (button-level spinner). */
    VERIFYING,

    /** A move-to-vault batch or its delete-consent retry (progress dialog). */
    MOVING,

    /** Restoring one entry into the gallery (progress dialog). */
    RESTORING,

    /** Deleting one entry from the vault (progress dialog). */
    DELETING,
}

/** One-shot UI events (snackbars, consent requests); the TrashEvent shape. */
sealed interface VaultEvent {
    /**
     * A system delete-consent request covering the originals whose removal
     * was blocked during a move batch; the screen launches it via
     * `StartIntentSenderForResult` and reports back through
     * [VaultViewModel.onConsentResult].
     */
    data class RequestConsent(val intentSender: IntentSender) : VaultEvent

    /** Plain snackbar line; [formatArgs] feed a parameterized string. */
    data class ShowMessage(
        @StringRes val messageRes: Int,
        val formatArgs: List<Any> = emptyList(),
    ) : VaultEvent

    /** Plural snackbar line ([count] drives both the grammar and the value). */
    data class ShowPlural(
        @PluralsRes val messageRes: Int,
        val count: Int,
    ) : VaultEvent
}

/**
 * Shell-scoped state holder for the hidden vault (issue #32, ADR-0017).
 *
 * Deliberately ONE activity-scoped ViewModel (created in `ToadGalleryApp`,
 * the `viewerViewModel` pattern) because three surfaces share the session:
 * the Settings entry (live status), the Photos move-to-vault flow, and the
 * vault sub-screen itself. Nothing about the unlock persists: the
 * [VaultUiState.Unlocked] phase lives in memory only and [onBackgrounded]
 * clears it on the shell's `ON_STOP` (auto-lock, ADR-0017).
 *
 * ## Threading (ADR-0017)
 * PBKDF2 costs ~0.1-0.3 s per call, so [setup]/[attemptUnlock] run on
 * `Dispatchers.Default` inside [VaultRepository] --- never on the main
 * thread, never in composition. The busy flag keeps the UI honest while the
 * derivation runs.
 *
 * ## PIN hygiene
 * The PIN arrives as a [CharArray] and is space-filled in a `finally` block
 * after use, per the `VaultCrypto` contract; the ViewModel keeps no
 * reference. (The Compose text field's internal `String` state is the one
 * residual copy on the UI side --- v1 accepts that; a CharArray-backed field
 * editor is a follow-up.)
 *
 * ## Move-to-vault (ADR-0017 order)
 * Copy-then-delete, strictly per item, through the repository. The vault can
 * grow while LOCKED: the PIN gates this UI session, never the encryption
 * (the media key lives in AndroidKeyStore). Items whose original-row delete
 * is consent-blocked are aggregated into ONE `createDeleteRequest` emitted as
 * [VaultEvent.RequestConsent]; the screen reports the activity result via
 * [onConsentResult], which retries the deletes only (the sealed copies
 * already exist and are never re-sealed).
 *
 * ## API level
 * The repository's MediaStore methods are `@RequiresApi(Q)`; the state
 * machine never leaves [VaultUiState.Unsupported] below Q, so every call is
 * unreachable there --- the `SDK_INT` guards keep that invariant lexically
 * visible for NewApi lint, with `@Suppress` belt-and-braces following the
 * `FileOpsViewModel` precedent.
 *
 * Not unit-tested directly (AndroidViewModel + provider I/O); the testable
 * logic lives under it in the JVM-tested `data/vault` classes
 * (`VaultStore`, `VaultIndex`, `VaultCrypto` per ADR-0016).
 *
 * Privacy (ADR-0010/ADR-0014): every byte the vault moves stays on this
 * device; the app holds no INTERNET permission, so nothing here can leave.
 */
class VaultViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = VaultRepository(app)

    private val _state = MutableStateFlow<VaultUiState>(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VaultUiState.Resolving
        } else {
            VaultUiState.Unsupported
        },
    )
    val state: StateFlow<VaultUiState> = _state.asStateFlow()

    private val _busy = MutableStateFlow<VaultBusy?>(null)
    val busy: StateFlow<VaultBusy?> = _busy.asStateFlow()

    /**
     * One-shot UI events. Buffered generously and never replayed; the
     * screens collect from first composition, which always precedes any
     * user-triggered op.
     */
    private val _events = MutableSharedFlow<VaultEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<VaultEvent> = _events.asSharedFlow()

    /** The active move batch or its consent retry (one at a time); Cancel cancels it. */
    private var moveJob: Job? = null

    /** The delete-consent round awaiting its activity result; consumed once. */
    private var pendingConsent: PendingVaultConsent? = null

    init {
        resolveConfiguration()
    }

    /**
     * Reads the sealed PIN record and lands in [VaultUiState.NotConfigured]
     * or [VaultUiState.Locked]. A fresh (post-process-death) ViewModel always
     * starts locked --- the unlock state is in-memory only by design.
     */
    private fun resolveConfiguration() {
        if (_state.value == VaultUiState.Unsupported) return
        viewModelScope.launch {
            _state.value = if (repository.isConfigured()) {
                VaultUiState.Locked
            } else {
                VaultUiState.NotConfigured
            }
        }
    }

    // ------------------------------------------------------------------
    // Setup + unlock (PBKDF2 off the main thread, ADR-0017)
    // ------------------------------------------------------------------

    /**
     * Creates the vault for [pin]. On success the session lands
     * [VaultUiState.Unlocked] directly --- the user just proved the PIN
     * twice --- after the ADR-0017 integrity sweep. [pin] is wiped in the
     * `finally` block whatever the outcome.
     */
    fun setup(pin: CharArray) {
        if (_state.value != VaultUiState.NotConfigured) return
        if (pin.size < MIN_PIN_LENGTH) return // screen-validated; defensive
        if (_busy.value != null) return
        _busy.value = VaultBusy(VaultBusyKind.VERIFYING)
        viewModelScope.launch {
            try {
                repository.setup(pin)
                _state.value = VaultUiState.Unlocked(repository.loadEntriesAfterSweep())
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_created))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_error_generic))
            } finally {
                pin.fill(PIN_WIPE_CHAR)
                _busy.value = null
            }
        }
    }

    /**
     * Verifies [pin] against the stored record; a wrong PIN is narrated
     * generically (no hint about how close it was). A corrupt record throws
     * inside the repository and surfaces as the generic error line --- that
     * is a vault-state problem, never a "wrong PIN".
     */
    fun attemptUnlock(pin: CharArray) {
        if (_state.value != VaultUiState.Locked) return
        if (pin.size < MIN_PIN_LENGTH) return // stored PINs are >= this by setup
        if (_busy.value != null) return
        _busy.value = VaultBusy(VaultBusyKind.VERIFYING)
        viewModelScope.launch {
            try {
                if (repository.verifyPin(pin)) {
                    // The ADR-0017 integrity sweep runs on unlock: crash
                    // orphaned blobs are reclaimed before the listing shows.
                    _state.value = VaultUiState.Unlocked(repository.loadEntriesAfterSweep())
                } else {
                    _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_unlock_wrong))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_error_generic))
            } finally {
                pin.fill(PIN_WIPE_CHAR)
                _busy.value = null
            }
        }
    }

    /**
     * Auto-lock (ADR-0017): the shell's `ON_STOP` lifecycle observer calls
     * this. Only the in-memory unlock clears --- sealed blobs and the PIN
     * record are untouched, and a running move batch finishes on its own
     * (it is provider/file I/O that is crash-safe by ordering).
     */
    fun onBackgrounded() {
        if (_state.value is VaultUiState.Unlocked) {
            _state.value = VaultUiState.Locked
        }
    }

    // ------------------------------------------------------------------
    // Move to vault (Photos selection; consent round-trip like file-ops)
    // ------------------------------------------------------------------

    /**
     * Moves [items] into the vault, strictly one at a time through the
     * repository: seal the bytes, then remove the MediaStore row
     * (copy-then-delete per ADR-0017). Requires the vault to be configured
     * --- NOT unlocked (the store can grow while locked; the PIN gates the
     * session, never the crypto). Progress lands in [busy]; the completion
     * narration is per-item-honest (moved / copied-but-not-removed / failed),
     * and consent-blocked originals trigger ONE [VaultEvent.RequestConsent].
     */
    fun moveToVault(items: List<MediaItem>) {
        // The repository's MediaStore leg is Q+-only; the state machine never
        // leaves Unsupported below Q, so this is unreachable there. The guard
        // keeps that invariant lexically visible for NewApi lint.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (!_state.value.isConfigured || items.isEmpty()) return
        if (_busy.value != null || pendingConsent != null) return
        moveJob?.cancel()
        val outcomes = mutableListOf<VaultMoveOutcome>()
        _busy.value = VaultBusy(VaultBusyKind.MOVING, done = 0, total = items.size)
        moveJob = viewModelScope.launch {
            try {
                @Suppress("NewApi") // guarded by the SDK_INT check above
                repository.moveToVault(
                    items = items,
                    onResult = { _, outcome -> outcomes += outcome },
                    onProgress = { done, total ->
                        _busy.value = VaultBusy(VaultBusyKind.MOVING, done = done, total = total)
                    },
                )
                finishMove(items, outcomes, cancelled = false)
            } catch (c: CancellationException) {
                // Publish the honest prefix, then let the cancellation
                // complete --- never swallow it (the file-ops contract).
                finishMove(items, outcomes, cancelled = true)
                throw c
            } finally {
                clearMoveBusy()
            }
        }
    }

    /** Cancels the active move batch (progress dialog button); keeps the prefix. */
    fun cancelMove() {
        moveJob?.cancel()
    }

    /**
     * Reduces a finished (or cancelled) move into narration. A cancelled run
     * keeps its honest prefix. On Q (API 29) there is no batch delete
     * request, so consent-blocked items narrate as copied-but-not-removed
     * instead of looping a dialog the platform cannot show.
     */
    private fun finishMove(items: List<MediaItem>, outcomes: List<VaultMoveOutcome>, cancelled: Boolean) {
        var moved = 0
        var notRemoved = 0
        var failed = 0
        val consentBlocked = mutableListOf<MediaItem>()
        outcomes.forEachIndexed { index, outcome ->
            when (outcome) {
                VaultMoveOutcome.MOVED -> moved++
                VaultMoveOutcome.NEEDS_CONSENT -> consentBlocked += items[index]
                VaultMoveOutcome.NOT_REMOVED -> notRemoved++
                VaultMoveOutcome.FAILED -> failed++
            }
        }
        if (cancelled) {
            if (moved > 0) {
                _events.tryEmit(VaultEvent.ShowPlural(R.plurals.vault_moved, moved))
            }
            if (consentBlocked.isNotEmpty() || notRemoved > 0) {
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_move_not_removed))
            }
            _events.tryEmit(
                VaultEvent.ShowMessage(
                    messageRes = R.string.vault_move_cancelled,
                    formatArgs = listOf(outcomes.size, items.size),
                ),
            )
            return
        }
        if (consentBlocked.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val blockedUris = consentBlocked.map { it.uri }
            pendingConsent = PendingVaultConsent(
                uris = blockedUris,
                alreadyMoved = moved,
                stuckBeforeConsent = notRemoved,
            )
            @Suppress("NewApi") // R+ per the check above (createDeleteRequest is API 30)
            val sender = repository.createDeleteConsentRequest(blockedUris)
            _events.tryEmit(VaultEvent.RequestConsent(sender))
            return // narration continues after the consent result
        }
        narrateMove(
            moved = moved,
            stuck = notRemoved + consentBlocked.size,
            failed = failed,
        )
    }

    /**
     * Activity result of the system delete-consent dialog. Granted: deletes
     * exactly the blocked originals (their sealed copies already exist) and
     * narrates the batch total. Declined: narrates what did land plus the
     * honest "permission declined" line.
     */
    fun onConsentResult(granted: Boolean) {
        val pending = pendingConsent ?: return
        pendingConsent = null
        if (!granted) {
            narrateMove(
                moved = pending.alreadyMoved,
                stuck = pending.stuckBeforeConsent + pending.uris.size,
                failed = 0,
            )
            _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_consent_denied))
            return
        }
        var removed = 0
        moveJob = viewModelScope.launch {
            _busy.value = VaultBusy(VaultBusyKind.MOVING, done = 0, total = pending.uris.size)
            try {
                @Suppress("NewApi") // same Q+ invariant as moveToVault
                repository.deleteOriginals(
                    uris = pending.uris,
                    onResult = { _, removedNow -> if (removedNow) removed++ },
                    onProgress = { done, total ->
                        _busy.value = VaultBusy(VaultBusyKind.MOVING, done = done, total = total)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall through to the narration with whatever landed.
            } finally {
                clearMoveBusy()
            }
            narrateMove(
                moved = pending.alreadyMoved + removed,
                stuck = pending.stuckBeforeConsent + (pending.uris.size - removed),
                failed = 0,
            )
        }
    }

    /** Per-item-honest completion narration for a move batch. */
    private fun narrateMove(moved: Int, stuck: Int, failed: Int) {
        if (moved > 0) {
            _events.tryEmit(VaultEvent.ShowPlural(R.plurals.vault_moved, moved))
        }
        if (stuck > 0) {
            _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_move_not_removed))
        }
        if (failed > 0) {
            _events.tryEmit(VaultEvent.ShowPlural(R.plurals.vault_move_failed, failed))
        }
    }

    /** Clears the move busy flag only if this run still owns it. */
    private fun clearMoveBusy() {
        if (_busy.value?.kind == VaultBusyKind.MOVING) {
            _busy.value = null
        }
    }

    // ------------------------------------------------------------------
    // Unlocked vault operations (one at a time; progress via [busy])
    // ------------------------------------------------------------------

    /**
     * Restores [entry] into the gallery (ADR-0017 order: unseal into a new
     * pending row, publish it, and only then remove the vault copy --- a
     * failure before publishing keeps the vault entry, so restore never
     * loses media). Requires an unlocked session.
     */
    fun restore(entry: VaultIndexEntry) {
        if (_state.value !is VaultUiState.Unlocked) return
        // The restore insert rides the file-ops Q+ pattern (IS_PENDING).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (_busy.value != null) return
        _busy.value = VaultBusy(VaultBusyKind.RESTORING)
        viewModelScope.launch {
            try {
                @Suppress("NewApi") // guarded by the SDK_INT check above
                when (repository.restore(entry)) {
                    VaultRestoreResult.RESTORED -> {
                        _events.tryEmit(VaultEvent.ShowPlural(R.plurals.vault_restored, 1))
                        reloadEntries()
                    }
                    VaultRestoreResult.RESTORED_KEPT_COPY -> {
                        _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_restore_kept_copy))
                        reloadEntries()
                    }
                    VaultRestoreResult.FAILED ->
                        _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_restore_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_restore_failed))
            } finally {
                _busy.value = null
            }
        }
    }

    /**
     * Deletes [entry] from the vault permanently. The screen confirms first
     * --- there is no undo by design (the confirm dialog says so). Index
     * entry first, blob second (the crash-safe order inside `VaultStore`).
     */
    fun deleteEntry(entry: VaultIndexEntry) {
        if (_state.value !is VaultUiState.Unlocked) return
        if (_busy.value != null) return
        _busy.value = VaultBusy(VaultBusyKind.DELETING)
        viewModelScope.launch {
            try {
                repository.deleteEntry(entry)
                _events.tryEmit(VaultEvent.ShowPlural(R.plurals.vault_deleted, 1))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_delete_failed))
            } finally {
                _busy.value = null
                reloadEntries()
            }
        }
    }

    /** Re-reads the sealed index; a failed reload keeps the last listing. */
    private suspend fun reloadEntries() {
        val current = _state.value as? VaultUiState.Unlocked ?: return
        try {
            _state.value = VaultUiState.Unlocked(repository.loadEntries())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = current
        }
    }

    companion object {
        /**
         * Screen-validated minimum PIN length; re-checked defensively in
         * [setup]/[attemptUnlock]. Public: the screen keys its Create/Unlock
         * button state off the same constant.
         */
        const val MIN_PIN_LENGTH = 4

        /** One event per phase; 16 coalesces bursts without blocking tryEmit. */
        private const val EVENT_BUFFER_CAPACITY = 16

        /** The fill char for wiping PIN arrays (the VaultCrypto contract). */
        private const val PIN_WIPE_CHAR = ' '
    }
}

/**
 * A delete-consent round awaiting its activity result: exactly the blocked
 * originals, plus the counts already narrated so the retry can report the
 * batch total honestly.
 */
private data class PendingVaultConsent(
    val uris: List<Uri>,
    val alreadyMoved: Int,
    val stuckBeforeConsent: Int,
)
