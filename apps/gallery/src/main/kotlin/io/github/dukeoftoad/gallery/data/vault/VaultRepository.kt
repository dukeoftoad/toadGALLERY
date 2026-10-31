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
 * lose media.
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
     */
    suspend fun setup(pin: CharArray) {
        withContext(Dispatchers.Default) { store.setup(pin) }
    }

    /**
     * Verifies [pin] against the stored record, off the main thread
     * (Dispatchers.Default, ADR-0017). Throws [VaultStoreException] when
     * the record is corrupt --- the UI must narrate that as an error, not
     * as "wrong PIN".
     */
    suspend fun verifyPin(pin: CharArray): Boolean {
        return withContext(Dispatchers.Default) { store.verifyPin(pin) }
    }

    // ------------------------------------------------------------------
    // Unlocked reading (Dispatchers.IO; the index is a small sealed file)
    // ------------------------------------------------------------------

    /** The decrypted index (no sweep); re-reads the disk truth every call. */
    suspend fun loadEntries(): List<VaultIndexEntry> =
        withContext(Dispatchers.IO) { store.entries() }

    /**
     * The unlock entry point: runs the ADR-0017 integrity sweep (reclaiming
     * crash-orphaned blobs), then returns the index.
     */
    suspend fun loadEntriesAfterSweep(): List<VaultIndexEntry> = withContext(Dispatchers.IO) {
        store.sweepOrphans()
        store.entries()
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
     * never abort the batch.
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
        onProgress(0, items.size)
        for ((index, item) in items.withIndex()) {
            coroutineContext.ensureActive()
            val outcome = moveOne(item)
            onResult(index, outcome)
            onProgress(index + 1, items.size)
        }
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
                // happened; any half blob is a sweepable orphan.
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
                ?: return@withContext VaultRestoreResult.FAILED
            try {
                resolver.openOutputStream(newUri)?.use { output ->
                    store.restoreOut(entry, output)
                } ?: throw IOException("provider offered no output stream for $newUri")
                val settled = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(newUri, settled, null, null)
            } catch (e: CancellationException) {
                runCatching { resolver.delete(newUri, null, null) }
                throw e
            } catch (e: Exception) {
                // Never leak a half-written pending row (app-owned, so the
                // cleanup delete needs no consent); the vault copy stays.
                runCatching { resolver.delete(newUri, null, null) }
                return@withContext VaultRestoreResult.FAILED
            }
            // Published: the media is durably back in the gallery. Only now
            // remove the vault copy.
            val removed = runCatching { store.delete(entry) }.isSuccess
            if (removed) VaultRestoreResult.RESTORED else VaultRestoreResult.RESTORED_KEPT_COPY
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
        /** App-private directory under filesDir (never a public share). */
        const val VAULT_DIR_NAME = "vault"

        /** A row without a usable DISPLAY_NAME is stored under this name. */
        const val FALLBACK_DISPLAY_NAME = "vault-item"
    }
}
