package io.github.dukeoftoad.gallery.ui.vault

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.vault.VaultBiometricReadiness
import io.github.dukeoftoad.gallery.data.vault.VaultBiometricSupport
import io.github.dukeoftoad.gallery.data.vault.VaultIndexEntry
import io.github.dukeoftoad.gallery.data.vault.VaultLockGeneration
import io.github.dukeoftoad.gallery.data.vault.VaultMoveOutcome
import io.github.dukeoftoad.gallery.data.vault.VaultRepository
import io.github.dukeoftoad.gallery.data.vault.VaultRestoreResult
import io.github.dukeoftoad.gallery.data.vault.VaultThumbnailPolicy
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
 * One-shot BiometricPrompt requests (issue #57, ADR-0019) --- a SEPARATE
 * flow from [VaultEvent] on purpose: `PhotosScreen`'s event collector is an
 * exhaustive `when`, and only the vault sub-screen can ever serve these
 * (the prompt needs the vault's FragmentActivity front-and-center). The
 * screen drives `VaultBiometrics` with the payload and reports back through
 * [VaultViewModel.onBiometricUnlockResult]/[VaultViewModel.onBiometricEnrollResult].
 */
sealed interface VaultBiometricRequest {

    /** The stored sealed session secret, ready for `VaultBiometrics.unlock`. */
    data class Unlock(val sealedSecret: ByteArray) : VaultBiometricRequest

    /** Run the opt-in enrollment round (`VaultBiometrics.enroll`). */
    data object Enroll : VaultBiometricRequest
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
 * ## Biometric unlock (issue #57, ADR-0019)
 * Opt-in binding: [biometrics] is the pure readiness state machine
 * ([VaultBiometricSupport]); the prompt itself is device-only and lives in
 * `VaultBiometrics` behind the screen, reached through the separate
 * [biometricRequests] flow (NOT [VaultEvent] --- PhotosScreen's collector is
 * an exhaustive `when` this slice must not break). A successful authorized
 * decrypt of the stored 32-byte session secret opens the SAME in-memory
 * session the PIN opens; auto-lock semantics are unchanged. An invalidated
 * key (device biometrics changed) removes the dead binding and narrates the
 * honest PIN-plus-re-enroll nudge.
 *
 * ## Thumbnails (issue #58)
 * [thumbnails] holds decrypted, size-bounded, in-memory previews keyed by
 * entry id, decoded on `Dispatchers.IO` under a 3-permit semaphore with a
 * per-item `Throwable` catch (a failed/oversized blob degrades to the
 * placeholder, never a crash, never a retry loop). ALL decrypted state ---
 * thumbnails included --- clears on [onBackgrounded]; no plaintext vault
 * media persists on disk (the video frame temp file is private-cache and
 * deleted immediately; see `VaultThumbnailDecoder`).
 *
 * ## Lock race (issue #69)
 * A decode is a suspend call: file I/O + cipher + `BitmapFactory` spend real
 * time, and an auto-lock can land in the middle. The pre-decode unlocked
 * check alone therefore cannot stop a still-running batch from publishing
 * plaintext pixels into the CLEARED map of a locked session --- exactly the
 * "thumbnails can be repopulated after locking" defect. The guard is
 * two-legged, per the issue #69 prescription: a monotonic lock-session
 * generation ([VaultLockGeneration], advanced at every unlock/lock boundary)
 * that every batch captures at launch, PLUS wholesale cancellation of the
 * in-flight batches on lock. Each bitmap must pass
 * [VaultThumbnailPolicy.shouldPublishThumbnail] --- generation still fresh
 * AND session still unlocked --- IMMEDIATELY before it is published; a stale
 * bitmap is dropped, never published.
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

    /**
     * Issue #57 (ADR-0019): the biometric readiness state machine
     * ([VaultBiometricSupport] --- pure, JVM-tested). Resolved once at init
     * (hardware can prompt + `biometric.binding` present) and updated by the
     * enroll/disable/invalidated-key paths.
     */
    private val _biometrics = MutableStateFlow<VaultBiometricReadiness>(
        VaultBiometricSupport.readiness(
            hardwareCanPrompt = VaultBiometrics.canPrompt(app),
            bindingEnrolled = false,
        ),
    )
    val biometrics: StateFlow<VaultBiometricReadiness> = _biometrics.asStateFlow()

    /**
     * Issue #58: decrypted, size-bounded thumbnails for the unlocked list,
     * keyed by entry id. IN MEMORY ONLY --- cleared wholesale on lock
     * (auto-lock clears ALL decrypted state, thumbnails included). Entries
     * with no entry here (decode failed / OOM / unsupported codec) render
     * the placeholder plate --- honest degradation.
     */
    private val _thumbnails = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<Long, ImageBitmap>> = _thumbnails.asStateFlow()

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

    /**
     * Issue #57: the BiometricPrompt requests (see [VaultBiometricRequest]
     * for why this is not part of [events]). Only the vault screen collects
     * it; small buffer, drop-oldest, like the event flow.
     */
    private val _biometricRequests = MutableSharedFlow<VaultBiometricRequest>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val biometricRequests: SharedFlow<VaultBiometricRequest> = _biometricRequests.asSharedFlow()

    /** The active move batch or its consent retry (one at a time); Cancel cancels it. */
    private var moveJob: Job? = null

    /** The delete-consent round awaiting its activity result; consumed once. */
    private var pendingConsent: PendingVaultConsent? = null

    /** Bounds concurrent thumbnail decodes (issue #58: 2-4, IO-friendly). */
    private val thumbnailGate = Semaphore(THUMBNAIL_CONCURRENCY)

    /** Entry ids already attempted this session; prevents retry loops on failure. */
    private val thumbnailAttempted = mutableSetOf<Long>()

    /**
     * Issue #69: the lock-session token. Advanced at EVERY unlock/lock
     * boundary ([enterUnlockedSession] and [onBackgrounded]); each thumbnail
     * batch captures it at launch and re-validates immediately before
     * publishing. Pure JVM ([AtomicLong] under the hood) so its semantics
     * are pinned by `VaultLockGenerationTest`.
     */
    private val lockGeneration = VaultLockGeneration()

    /**
     * Issue #69: the in-flight thumbnail batch jobs, so [onBackgrounded] can
     * cancel them wholesale on lock (decodes under a locked vault are wasted
     * work even when the publish re-check already guarantees they cannot
     * land). Jobs remove themselves on completion; at most a handful exist
     * (one per ensureThumbnails kick, deduped by the attempted set).
     */
    private val thumbnailBatches = mutableListOf<Job>()

    init {
        resolveConfiguration()
    }

    /**
     * Reads the sealed PIN record and lands in [VaultUiState.NotConfigured]
     * or [VaultUiState.Locked]. A fresh (post-process-death) ViewModel always
     * starts locked --- the unlock state is in-memory only by design. Also
     * resolves the #57 biometric readiness (`biometric.binding` presence is
     * read while it is already on disk).
     */
    private fun resolveConfiguration() {
        if (_state.value == VaultUiState.Unsupported) return
        viewModelScope.launch {
            val configured = repository.isConfigured()
            _biometrics.value = VaultBiometricSupport.readiness(
                hardwareCanPrompt = VaultBiometrics.canPrompt(getApplication()),
                bindingEnrolled = configured && repository.hasBiometricBinding(),
            )
            _state.value = if (configured) {
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
                enterUnlockedSession()
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
                    enterUnlockedSession()
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
     * The shared unlock landing (PIN and biometric alike, issue #57): the
     * ADR-0017 integrity sweep, the decrypted index, and the #58 thumbnail
     * decode batch for the fresh listing.
     */
    private suspend fun enterUnlockedSession() {
        // Issue #69: a fresh session is a fresh generation --- orphans any
        // batch that might still be running from the previous session
        // (defensive: onBackgrounded cancels them, but belt-and-braces the
        // token makes cross-session publishes impossible either way).
        lockGeneration.advance()
        val entries = repository.loadEntriesAfterSweep()
        _state.value = VaultUiState.Unlocked(entries)
        ensureThumbnails(entries)
    }

    // ------------------------------------------------------------------
    // Biometric unlock binding (issue #57, ADR-0019)
    // ------------------------------------------------------------------

    /**
     * Starts a biometric unlock round: loads the sealed session secret and
     * hands it to the screen as [VaultBiometricRequest.Unlock] (the
     * BiometricPrompt needs the FragmentActivity; the device-only crypto
     * lives in `VaultBiometrics`). Requires Locked + an enrolled binding.
     */
    fun requestBiometricUnlock() {
        if (_state.value != VaultUiState.Locked) return
        val enrolled = (_biometrics.value as? VaultBiometricReadiness.Supported)?.bindingEnrolled
            ?: return
        if (!enrolled) return
        viewModelScope.launch {
            try {
                val sealed = repository.loadBiometricBinding()
                if (sealed == null) {
                    // The file vanished since readiness resolved (e.g. the
                    // invalidated-key cleanup on another surface): re-sync.
                    refreshBiometricReadiness()
                } else {
                    _biometricRequests.tryEmit(VaultBiometricRequest.Unlock(sealed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A malformed/unreadable binding is a vault-state problem:
                // generic error, the PIN path is untouched.
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_error_generic))
            }
        }
    }

    /**
     * The screen reports the BiometricPrompt round's outcome back here.
     * `Authenticated` opens the SAME in-memory session the PIN path opens
     * (auto-lock semantics unchanged --- ON_STOP clears it like any other);
     * an invalidated key removes the dead binding and narrates the honest
     * ADR-0019 fallback (PIN + re-enroll nudge). A user cancel stays quiet.
     */
    fun onBiometricUnlockResult(result: VaultBiometrics.UnlockResult) {
        when (result) {
            is VaultBiometrics.UnlockResult.Authenticated -> {
                if (_state.value != VaultUiState.Locked) return
                viewModelScope.launch {
                    try {
                        enterUnlockedSession()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_error_generic))
                    }
                }
            }
            is VaultBiometrics.UnlockResult.Failed -> when (result.failure) {
                VaultBiometrics.Failure.CANCELLED -> Unit // the user opted out; stay quiet
                VaultBiometrics.Failure.KEY_INVALIDATED -> viewModelScope.launch {
                    runCatching { repository.removeBiometricBinding() }
                    refreshBiometricReadiness()
                    _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_biometric_invalidated))
                }
                VaultBiometrics.Failure.ERROR ->
                    _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_biometric_error))
            }
        }
    }

    /**
     * Starts the opt-in enrollment round (issue #57): the screen runs
     * `VaultBiometrics.enroll` (fresh key + fresh session secret, sealed
     * under a prompt-authorized cipher) and reports back via
     * [onBiometricEnrollResult]. Requires an UNLOCKED session --- the
     * toggle is a settings-style affordance on the vault list.
     */
    fun enableBiometricUnlock() {
        if (_state.value !is VaultUiState.Unlocked) return
        val supported = _biometrics.value as? VaultBiometricReadiness.Supported ?: return
        if (supported.bindingEnrolled) return
        _biometricRequests.tryEmit(VaultBiometricRequest.Enroll)
    }

    /** Persists (or narrates the failure of) a finished enrollment round. */
    fun onBiometricEnrollResult(result: VaultBiometrics.EnrollResult) {
        when (result) {
            is VaultBiometrics.EnrollResult.Sealed -> viewModelScope.launch {
                try {
                    repository.saveBiometricBinding(result.sealedSecret)
                    refreshBiometricReadiness()
                    _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_biometric_enabled))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_error_generic))
                }
            }
            is VaultBiometrics.EnrollResult.Failed -> when (result.failure) {
                VaultBiometrics.Failure.CANCELLED -> Unit // opt-in declined; stay quiet
                VaultBiometrics.Failure.KEY_INVALIDATED,
                VaultBiometrics.Failure.ERROR,
                -> _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_biometric_error))
            }
        }
    }

    /**
     * Turns biometric unlock off (the settings toggle's off leg): removes
     * the binding file. Requires the unlocked session (issue #57: disabling
     * is a vault-settings op, not a locked-screen one).
     */
    fun disableBiometricUnlock() {
        if (_state.value !is VaultUiState.Unlocked) return
        val supported = _biometrics.value as? VaultBiometricReadiness.Supported ?: return
        if (!supported.bindingEnrolled) return
        viewModelScope.launch {
            try {
                repository.removeBiometricBinding()
                refreshBiometricReadiness()
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_biometric_disabled))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _events.tryEmit(VaultEvent.ShowMessage(R.string.vault_error_generic))
            }
        }
    }

    /** Re-reads the binding presence and folds it into [biometrics]. */
    private suspend fun refreshBiometricReadiness() {
        val enrolled = repository.hasBiometricBinding()
        val canPrompt = VaultBiometrics.canPrompt(getApplication())
        _biometrics.value = VaultBiometricSupport.readiness(canPrompt, enrolled)
    }

    /**
     * Auto-lock (ADR-0017): the shell's `ON_STOP` lifecycle observer calls
     * this. Only the in-memory unlock clears --- sealed blobs and the PIN
     * record are untouched, and a running move batch finishes on its own
     * (it is provider/file I/O that is crash-safe by ordering). Issue #58:
     * ALL decrypted session state clears with it, thumbnails included.
     * Issue #69: the lock-session generation advances (orphaning every
     * in-flight thumbnail batch's publishes) and the batches themselves
     * are cancelled outright.
     */
    fun onBackgrounded() {
        if (_state.value is VaultUiState.Unlocked) {
            _state.value = VaultUiState.Locked
        }
        // Issue #69: end the session IN ORDER --- invalidate first, then stop
        // the work, then clear the memory. The generation bump orphans every
        // in-flight batch (their post-decode re-check can never pass again,
        // even if a blocking IO decode outlives the cancel); cancelling the
        // batches stops the wasted decodes outright.
        lockGeneration.advance()
        thumbnailBatches.forEach { it.cancel() }
        thumbnailBatches.clear()
        _thumbnails.value = emptyMap()
        thumbnailAttempted.clear()
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
            val entries = repository.loadEntries()
            _state.value = VaultUiState.Unlocked(entries)
            // Prune thumbnails of deleted/restored entries, decode new ones.
            val liveIds = entries.mapTo(mutableSetOf()) { it.id }
            _thumbnails.value = _thumbnails.value.filterKeys { it in liveIds }
            ensureThumbnails(entries)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _state.value = current
        }
    }

    // ------------------------------------------------------------------
    // Thumbnails (issue #58; Dispatchers.IO under a small semaphore)
    // ------------------------------------------------------------------

    /**
     * Kicks off the decode batch for every listed entry not yet attempted
     * this session. Decodes run under [thumbnailGate] (bounded concurrency)
     * off the main thread, one item at a time; a null result (failed / OOM /
     * unsupported codec) simply never lands in [thumbnails], so the row
     * keeps its placeholder --- no retry loop, no crash.
     *
     * Issue #69 race guard: the batch captures [VaultLockGeneration.current]
     * at launch; EVERY bitmap must pass
     * [VaultThumbnailPolicy.shouldPublishThumbnail] --- generation still
     * fresh AND session still unlocked --- IMMEDIATELY before it is
     * published. The pre-decode unlocked checks alone cannot help: a lock /
     * background event can land while `decodeThumbnail` is suspended, and
     * without the post-decode re-check the still-running batch would
     * repopulate plaintext pixels into the cleared map of a locked session.
     */
    private fun ensureThumbnails(entries: List<VaultIndexEntry>) {
        val todo = entries.filter { it.id !in _thumbnails.value && it.id !in thumbnailAttempted }
        if (todo.isEmpty()) return
        todo.forEach { thumbnailAttempted += it.id }
        val batch = viewModelScope.launch {
            // One stale-temp sweep per batch (crash leftovers from the video
            // frame-extraction path; issue #58 hygiene).
            runCatching { repository.cleanThumbnailTempFiles() }
            // The generation this batch runs under (issue #69). Every lock/
            // unlock boundary advances it; the re-check below compares against
            // it so a bitmap decoded under a DEAD session can never publish.
            val capturedGeneration = lockGeneration.current()
            for (entry in todo) {
                if (_state.value !is VaultUiState.Unlocked) return@launch
                thumbnailGate.withPermit {
                    if (_state.value !is VaultUiState.Unlocked) return@withPermit
                    val bitmap = try {
                        repository.decodeThumbnail(entry)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                    // Issue #69 (the race itself): decodeThumbnail just spent
                    // real time suspended in I/O + cipher + BitmapFactory ---
                    // a lock/background event may have landed in the middle.
                    // Re-check the generation AND the unlocked state
                    // IMMEDIATELY before publishing; a stale bitmap is dropped
                    // (onBackgrounded cleared the map; it must stay cleared).
                    if (bitmap != null && VaultThumbnailPolicy.shouldPublishThumbnail(
                            capturedGeneration = capturedGeneration,
                            currentGeneration = lockGeneration.current(),
                            isUnlocked = _state.value is VaultUiState.Unlocked,
                        )
                    ) {
                        _thumbnails.value = _thumbnails.value + (entry.id to bitmap.asImageBitmap())
                    }
                }
            }
        }
        // Issue #69: remember the batch so onBackgrounded can cancel it on
        // lock; self-remove when done (completed/cancelled alike) to keep the
        // list bounded across a long unlocked session.
        synchronized(thumbnailBatches) {
            thumbnailBatches.add(batch)
            batch.invokeOnCompletion { synchronized(thumbnailBatches) { thumbnailBatches.remove(batch) } }
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

        /** Concurrent thumbnail decodes (issue #58: 2-4 keeps IO + heap sane). */
        private const val THUMBNAIL_CONCURRENCY = 3
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
