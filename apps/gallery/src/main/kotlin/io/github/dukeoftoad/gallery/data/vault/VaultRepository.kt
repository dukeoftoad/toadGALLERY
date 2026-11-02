package io.github.dukeoftoad.gallery.data.vault

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import io.github.dukeoftoad.gallery.data.FileOpFailureReason
import io.github.dukeoftoad.gallery.data.FileOpOutcome
import io.github.dukeoftoad.gallery.data.MediaFileOps
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Per-item outcome of one move-to-vault attempt (issue #32, ADR-0017).
 * Pure values so the ViewModel narrates batches without Android types.
 */
enum class VaultMoveOutcome {
    /** Bytes sealed AND the MediaStore row removed: fully moved. */
    MOVED,

    /** Bytes sealed; removing the row needs user consent (API 29+ recovery). */
    NEEDS_CONSENT,

    /** Bytes sealed; the row could not be removed for another reason. */
    NOT_REMOVED,

    /** Nothing sealed --- the item is untouched in the gallery. */
    FAILED,
}

/** Result of restoring one vault entry back into the gallery. */
enum class VaultRestoreResult {
    /** New MediaStore row published and the vault copy removed. */
    RESTORED,

    /** Row published, but the vault copy could not be removed (kept). */
    RESTORED_KEPT_COPY,

    /** Nothing restored; the vault copy is untouched. */
    FAILED,
}

/**
 * One structured diagnostics probe of the hidden vault (W1 --- the
 * Settings > About > Diagnostics surface): the pure-JVM store's disk truth
 * ([store], from [VaultStore.describeState]) plus whether the
 * AndroidKeyStore media key exists ([keystoreKeyPresent], load-only ---
 * the probe never generates). Plain values so the screen renders without
 * Android types; nothing here decrypts content or exposes names.
 */
data class VaultDiagnostics(
    /**
     * The store's on-disk shape: root/pin/index/binding presence, blob
     * count+bytes, writability.
     */
    val store: VaultStoreState,
    /** The vault's AndroidKeyStore key exists (load-only probe; never generated). */
    val keystoreKeyPresent: Boolean,
)

/**
 * The hidden vault's thin Android glue (issue #32, ADR-0017): the bridge
 * between the pure-JVM [VaultStore] (sealed files, temp-dir testable) and
 * MediaStore/[ContentResolver].
 *
 * NOT JVM-unit-tested, by design and documented here: every method is
 * provider I/O over `ContentResolver`/AndroidKeyStore with no branching
 * logic beyond honest per-item outcome mapping --- the same seam split the
 * file-ops slice uses (`MediaFileOps` untested, `FileOpNaming` tested).
 * The testable surface lives under it: [VaultCrypto]/[VaultIndex]/
 * [VaultStore] with their JVM suites.
 *
 * ## Move-to-vault protocol (ADR-0017)
 * Copy-then-delete, strictly per item: stream the content-URI bytes through
 * the streaming seal into the app-private store ([VaultStore.store]), THEN
 * remove the MediaStore row through the SAME consent machinery the file-ops
 * slice built ([MediaFileOps.delete] maps `RecoverableSecurityException` to
 * [FileOpOutcome.NeedsConsent]; the ViewModel aggregates one
 * [createDeleteConsentRequest] per batch and retries the deletes only).
 * A crash between the two leaves an orphaned sealed blob, reclaimed by the
 * integrity sweep on the next unlock --- never a dangling gallery entry.
 *
 * ## Restore protocol (ADR-0017)
 * The inverse: insert a pending row into the right collection, stream the
 * plaintext in, publish the row, and only THEN delete the sealed blob ---
 * a failure before publishing keeps the vault copy, so restore can never
 * lose media. Publication is confirmed by the `update()` row count: a
 * 0-row publish is treated as a failure (WARN-logged for the on-device
 * diagnostics story, W1) --- pending row cleaned up, vault copy kept,
 * never as success.
 *
 * ## API level gating (mirrors the file-ops slice exactly)
 *  - [moveToVault]/[deleteOriginals]/[restore] are `@RequiresApi(Q)`:
 *    row deletion needs the Q+ consent protocol (`RecoverableSecurityException`
 *    recovery) and the restore insert uses the Q+ `IS_PENDING` pattern ---
 *    below 29 the app is read-only and the vault flows stay inert.
 *  - [createDeleteConsentRequest] is `@RequiresApi(R)`: `createDeleteRequest`
 *    exists from API 30. On Q (29) a consent-blocked delete cannot be
 *    recovered through a batch request; the ViewModel narrates those items
 *    as sealed-but-not-removed instead of looping a dialog.
 *
 * ## Diagnostics (W1)
 * Every operation logs its outcome to [DiagLog] under the shared
 * "ToadVault" tag --- counts, booleans, entry ids and exception
 * class+message ONLY; never PIN data, key material, display names, or the
 * content URIs of vault items. The ring feeds the About screen;
 * [diagnostics] is its structured leg, and the ViewModel adds one
 * op-level WARN per swallowed exception so the two read together (op
 * there, phase here).
 *
 * Privacy (ADR-0006/ADR-0010/ADR-0014): content URIs in, app-private
 * ciphertext out, no network permission exists in the app --- nothing here
 * can leave the device.
 */
class VaultRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    private val store: VaultStore = VaultStore(
        root = context.filesDir.resolve(VAULT_DIR_NAME),
        keys = AndroidVaultKeys,
    )

    /** Issue #58: bounded in-memory thumbnails over the store + private cacheDir. */
    private val thumbnails: VaultThumbnailDecoder =
        VaultThumbnailDecoder(cacheDir = context.cacheDir, store = store)

    /**
     * Q+ only, mirroring `MediaFileOps`'s `@RequiresApi(Q)` contract (the
     * lexically lint-visible `SDK_INT` check is the guard NewApi lint
     * recognizes; same pattern as `FileOpsViewModel`).
     */
    private val ops: MediaFileOps? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaFileOps(resolver)
        } else {
            null
        }

    // ------------------------------------------------------------------
    // Configuration + PIN (PBKDF2 on Dispatchers.Default per ADR-0017)
    // ------------------------------------------------------------------

    /** True once the sealed PIN record exists (delegates to [VaultStore]). */
    suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) { store.isConfigured() }

    /**
     * Creates the vault for [pin]. Runs on [Dispatchers.Default]: the 210k
     * PBKDF2 rounds are the expensive part (ADR-0017 --- never the main
     * thread); the tiny sealed writes ride along.
     *
     * W1 instrumentation: the pre-state summary from
     * [VaultStore.describeState] is INFO-logged before the call (the exact
     * shape that separated the versionCode-11 failure), the commit after;
     * a failure WARN-logs the throwable and rethrows (cancellation
     * rethrows unlogged --- it is not a failure).
     */
    suspend fun setup(pin: CharArray) {
        withContext(Dispatchers.Default) {
            val pre = store.describeState()
            DiagLog.i(
                TAG,
                "setup: pre-state root=${pre.rootExists} pin=${pre.pinPresent} " +
                    "index=${pre.indexPresent} blobs=${pre.blobCount}",
            )
            try {
                store.setup(pin)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagLog.w(TAG, "setup: failed", e)
                throw e
            }
            DiagLog.i(TAG, "setup: committed (pin+index)")
        }
    }

    /**
     * Verifies [pin] against the stored record, off the main thread
     * (Dispatchers.Default, ADR-0017). Throws [VaultStoreException] when
     * the record is corrupt --- the UI must narrate that as an error, not
     * as "wrong PIN". W1: a wrong PIN logs a bare INFO mismatch line; a
     * corrupt record WARN-logs the throwable before rethrowing.
     */
    suspend fun verifyPin(pin: CharArray): Boolean {
        return withContext(Dispatchers.Default) {
            try {
                val matches = store.verifyPin(pin)
                if (!matches) {
                    // No other detail on purpose --- never hint how close.
                    DiagLog.i(TAG, "verify: mismatch")
                }
                matches
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A corrupt/unreadable record (VaultStoreException): WARN
                // with the throwable; the ViewModel's op-level line pairs
                // with this phase-level one.
                DiagLog.w(TAG, "verify: record corrupt", e)
                throw e
            }
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics (W1; read-only probe for the About screen)
    // ------------------------------------------------------------------

    /**
     * One structured probe for the About > Diagnostics screen (W1): the
     * store's disk truth plus the keystore key's presence. Dispatchers.IO
     * like every other wrapper here; reads only --- nothing decrypts,
     * nothing generates, and a keystore probe failure surfaces as
     * `keystoreKeyPresent=false` rather than a crash.
     */
    suspend fun diagnostics(): VaultDiagnostics = withContext(Dispatchers.IO) {
        VaultDiagnostics(store.describeState(), AndroidVaultKeys.probeExistingKey())
    }

    // ------------------------------------------------------------------
    // Unlocked reading (Dispatchers.IO; the index is a small sealed file)
    // ------------------------------------------------------------------

    /** The decrypted index (no sweep); re-reads the disk truth every call. */
    suspend fun loadEntries(): List<VaultIndexEntry> =
        withContext(Dispatchers.IO) { store.entries() }

    /**
     * The unlock entry point: runs the ADR-0017 integrity sweep (reclaiming
     * crash-orphaned blobs), then returns the index. W1: the swept count
     * and the entry count are INFO-logged --- the unlock leg of the device
     * story.
     *
     * Why no repository-level mutex (the unlock-vs-move race round): the
     * unlock path (`moveToVault` -> [VaultStore.store]) and this sweep are
     * deliberately allowed to overlap --- moves run while the vault is
     * Locked (the gate is [isConfigured], not the session) --- and the
     * data-loss window between them is closed INSIDE the store:
     * [VaultStore.store], [VaultStore.delete] and [VaultStore.sweepOrphans]
     * serialize on one store-level monitor, so the sweep can only run
     * fully BEFORE a store's blob write (nothing fresh to reclaim) or
     * fully AFTER its index commit (the entry is known); the pre-fix
     * interleave --- the sweep deleting a blob whose index write was
     * still in flight, right before the move deleted the original
     * MediaStore row --- is unreachable. A store that lands between this
     * sweep and the [store.entries] read is fully committed and therefore
     * VISIBLE in the listing (never lost, never half-state), which is the
     * benign freshness consequence of not holding a repo mutex across the
     * two calls. See [VaultStore]'s KDoc for the full interleave list.
     */
    suspend fun loadEntriesAfterSweep(): List<VaultIndexEntry> = withContext(Dispatchers.IO) {
        val swept = store.sweepOrphans()
        val entries = store.entries()
        DiagLog.i(TAG, "unlock: swept=$swept entries=${entries.size}")
        entries
    }

    /** Deletes one entry (sealed blob + index row) from the vault. */
    suspend fun deleteEntry(entry: VaultIndexEntry) {
        withContext(Dispatchers.IO) { store.delete(entry) }
    }

    // ------------------------------------------------------------------
    // Biometric binding (issue #57, ADR-0019; thin IO over the store)
    // ------------------------------------------------------------------

    /** True once the optional `biometric.binding` envelope exists (#57). */
    suspend fun hasBiometricBinding(): Boolean =
        withContext(Dispatchers.IO) { store.hasBiometricBinding() }

    /** Persists a freshly sealed session secret (the enroll round's last step). */
    suspend fun saveBiometricBinding(sealedSecret: ByteArray) {
        withContext(Dispatchers.IO) { store.saveBiometricBinding(sealedSecret) }
    }

    /**
     * The stored sealed session secret, or null when biometric unlock is
     * not enrolled. Throws [VaultStoreException] on a malformed envelope ---
     * the ViewModel narrates that as an error, never "not enrolled".
     */
    suspend fun loadBiometricBinding(): ByteArray? =
        withContext(Dispatchers.IO) { store.loadBiometricBinding() }

    /** Removes the binding (toggle off, or the invalidated-key cleanup). */
    suspend fun removeBiometricBinding(): Boolean =
        withContext(Dispatchers.IO) { store.removeBiometricBinding() }

    // ------------------------------------------------------------------
    // Thumbnails (issue #58; Dispatchers.IO, caller bounds concurrency)
    // ------------------------------------------------------------------

    /**
     * One bounded in-memory thumbnail for [entry], or null on any per-item
     * failure (placeholder territory --- see [VaultThumbnailDecoder]). The
     * stale-temp sweep runs once per call batch via [cleanThumbnailTempFiles].
     */
    suspend fun decodeThumbnail(entry: VaultIndexEntry): Bitmap? =
        withContext(Dispatchers.IO) { thumbnails.decode(entry) }

    /** Sweeps crash-leftover plaintext temp files (issue #58 hygiene). */
    suspend fun cleanThumbnailTempFiles() {
        withContext(Dispatchers.IO) { thumbnails.cleanStaleTempFiles() }
    }

    // ------------------------------------------------------------------
    // Move to vault (Photos selection; Q+)
    // ------------------------------------------------------------------

    /**
     * Moves [items] into the vault, strictly one at a time (issue #32):
     * seal the bytes, then delete the MediaStore row. [onResult] receives
     * the stable item index (callers pair outcomes with items positionally,
     * even on cancellation --- only a prefix arrives then), [onProgress]
     * fires `(0, total)` before the first item and `(completed, total)`
     * after each. Cancellation is checked between items; per-item failures
     * never abort the batch. W1: the completed batch logs one INFO line of
     * outcome counts (a cancelled batch narrates its prefix in the UI
     * instead; the counts here describe only completed batches).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun moveToVault(
        items: List<MediaItem>,
        onResult: (index: Int, outcome: VaultMoveOutcome) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit,
    ) {
        val localOps = ops
        if (localOps == null) {
            // Unreachable through the UI (selection needs Q+); defensive.
            items.forEachIndexed { index, _ -> onResult(index, VaultMoveOutcome.FAILED) }
            return
        }
        var moved = 0
        var needsConsent = 0
        var notRemoved = 0
        var failed = 0
        onProgress(0, items.size)
        for ((index, item) in items.withIndex()) {
            coroutineContext.ensureActive()
            val outcome = moveOne(item)
            when (outcome) {
                VaultMoveOutcome.MOVED -> moved++
                VaultMoveOutcome.NEEDS_CONSENT -> needsConsent++
                VaultMoveOutcome.NOT_REMOVED -> notRemoved++
                VaultMoveOutcome.FAILED -> failed++
            }
            onResult(index, outcome)
            onProgress(index + 1, items.size)
        }
        DiagLog.i(
            TAG,
            "move: moved=$moved needsConsent=$needsConsent notRemoved=$notRemoved failed=$failed",
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun moveOne(item: MediaItem): VaultMoveOutcome =
        withContext(Dispatchers.IO) {
            val details = querySourceDetails(item.uri)
                ?: return@withContext VaultMoveOutcome.FAILED
            try {
                resolver.openInputStream(item.uri)?.use { input ->
                    store.store(
                        displayName = details.displayName,
                        mimeType = item.mimeType,
                        isVideo = item.isVideo,
                        addedAtMillis = System.currentTimeMillis(),
                        bytes = input,
                        length = details.sizeBytes,
                    )
                } ?: return@withContext VaultMoveOutcome.FAILED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // openInputStream or the seal failed: nothing index-visible
                // happened; any half blob is a sweepable orphan (reclaimed
                // once aged past the sweep's grace window --- see
                // [VaultStore.sweepOrphans]).
                return@withContext VaultMoveOutcome.FAILED
            }
            // Sealed. Now remove the original through the existing consent
            // machinery (RecoverableSecurityException -> NeedsConsent).
            when (ops?.delete(item.uri)?.outcome) {
                FileOpOutcome.Success -> VaultMoveOutcome.MOVED
                FileOpOutcome.NeedsConsent -> VaultMoveOutcome.NEEDS_CONSENT
                else -> VaultMoveOutcome.NOT_REMOVED
            }
        }

    /**
     * The consent retry round: deletes exactly the originals whose removal
     * was consent-blocked (their sealed copies already exist and are never
     * re-sealed). A row that vanished mid-flow (NOT_FOUND) still counts as
     * removed --- the item is out of the gallery either way, the same
     * rule `MediaFileOps.move` applies.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun deleteOriginals(
        uris: List<Uri>,
        onResult: (index: Int, removed: Boolean) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit,
    ) {
        val localOps = ops ?: return
        onProgress(0, uris.size)
        for ((index, uri) in uris.withIndex()) {
            coroutineContext.ensureActive()
            val outcome = localOps.delete(uri).outcome
            val removed = when (outcome) {
                FileOpOutcome.Success -> true
                is FileOpOutcome.Failure -> outcome.reason == FileOpFailureReason.NOT_FOUND
                else -> false
            }
            onResult(index, removed)
            onProgress(index + 1, uris.size)
        }
    }

    /**
     * One system "delete these items?" request covering [uris] (the same
     * `createDeleteRequest` builder the file-ops slice uses); R+ per lint
     * (the resolved SDK-36 overload reports API 30). On Q, see the class
     * KDoc: consent-blocked deletes narrate as sealed-but-not-removed.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun createDeleteConsentRequest(uris: Collection<Uri>): IntentSender =
        ops?.createDeleteConsentRequest(uris)
            ?: throw IllegalStateException("file ops require Android 10")

    // ------------------------------------------------------------------
    // Restore (vault screen; Q+)
    // ------------------------------------------------------------------

    /**
     * Restores [entry] into the gallery: pending insert into the collection
     * its type maps to, stream the plaintext in, publish, then delete the
     * sealed copy (ADR-0017 order --- a failure before publishing cleans up
     * the app-owned pending row and keeps the vault copy, so restore never
     * loses media). `RELATIVE_PATH` is left unset on purpose: the provider
     * files the item into the collection's default top-level directory.
     * W1: every outcome logs one line --- entry id + publish row count +
     * result on the happy path, WARN with the throwable on failure (the
     * entry id is the sealed blob's name, never the display name).
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    suspend fun restore(entry: VaultIndexEntry): VaultRestoreResult =
        withContext(Dispatchers.IO) {
            val collection = if (entry.isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, entry.displayName)
                // Wildcard mimetypes are not valid insert values; the
                // provider infers the type from the preserved extension
                // (same rule as MediaFileOps.copy).
                if (!entry.mimeType.contains('*')) {
                    put(MediaStore.MediaColumns.MIME_TYPE, entry.mimeType)
                }
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val newUri = resolver.insert(collection, values)
                ?: run {
                    // The provider refused the insert outright: no pending
                    // row exists yet and the vault copy is untouched.
                    DiagLog.w(TAG, "restore: id=${entry.id} insert offered no row")
                    return@withContext VaultRestoreResult.FAILED
                }
            val published: Int = try {
                resolver.openOutputStream(newUri)?.use { output ->
                    store.restoreOut(entry, output)
                } ?: throw IOException("provider offered no output stream for $newUri")
                val settled = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                val publishedRows = resolver.update(newUri, settled, null, null)
                if (publishedRows <= 0) {
                    // A 0-row update is NOT a publication: the provider
                    // refused (or the pending row vanished) while the bytes
                    // are already streamed. Treat it like any other failure
                    // --- delete the app-owned pending row and keep the
                    // vault copy (ADR-0017: never delete the sealed blob
                    // without confirmed publication).
                    DiagLog.w(TAG, "restore: id=${entry.id} publish refused (rows=$publishedRows)")
                    throw IOException(
                        "provider did not publish the pending row ($publishedRows rows updated)",
                    )
                }
                publishedRows
            } catch (e: CancellationException) {
                runCatching { resolver.delete(newUri, null, null) }
                throw e
            } catch (e: Exception) {
                // Never leak a half-written pending row (app-owned, so the
                // cleanup delete needs no consent); the vault copy stays.
                DiagLog.w(TAG, "restore: id=${entry.id} failed", e)
                runCatching { resolver.delete(newUri, null, null) }
                return@withContext VaultRestoreResult.FAILED
            }
            // Published: the media is durably back in the gallery. Only now
            // remove the vault copy.
            val removed = runCatching { store.delete(entry) }.isSuccess
            val result = if (removed) {
                VaultRestoreResult.RESTORED
            } else {
                VaultRestoreResult.RESTORED_KEPT_COPY
            }
            DiagLog.i(TAG, "restore: id=${entry.id} published=$published result=$result")
            result
        }

    // ------------------------------------------------------------------
    // Provider probe
    // ------------------------------------------------------------------

    /** Display name + declared size of [uri]; null when the row is gone. */
    private data class VaultSourceDetails(
        val displayName: String,
        val sizeBytes: Long,
    )

    private fun querySourceDetails(uri: Uri): VaultSourceDetails? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            VaultSourceDetails(
                displayName = cursor.getString(0)?.takeIf { it.isNotEmpty() }
                    ?: FALLBACK_DISPLAY_NAME,
                sizeBytes = if (cursor.isNull(1)) 0L else cursor.getLong(1),
            )
        }
    }

    private companion object {
        /** Diagnostics tag shared by the vault's Android seams (W1). */
        const val TAG = "ToadVault"

        /** App-private directory under filesDir (never a public share). */
        const val VAULT_DIR_NAME = "vault"

        /** A row without a usable DISPLAY_NAME is stored under this name. */
        const val FALLBACK_DISPLAY_NAME = "vault-item"
    }
}
