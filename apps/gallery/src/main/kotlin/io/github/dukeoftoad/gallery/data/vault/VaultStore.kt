package io.github.dukeoftoad.gallery.data.vault

import io.github.dukeoftoad.gallery.data.vault.VaultCrypto.VaultCryptoException
import java.io.File
import java.io.FilterInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Raised when the vault's on-disk state cannot be read or written honestly. */
class VaultStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * One [VaultStore.describeState] probe: the vault's on-disk shape as plain
 * values (W1 --- the Settings > About > Diagnostics surface). Pure JVM like
 * the store itself, so the probe is pinned by the same JVM suite; booleans
 * and counts only --- nothing here decrypts content or exposes names.
 */
data class VaultStoreState(
    /** The store root is a directory (false: never created, or wiped away). */
    val rootExists: Boolean,
    /** `pin.bin` exists (half of the configured marker; see [VaultStore.isConfigured]). */
    val pinPresent: Boolean,
    /** `index.bin` exists (the other half; pin without index = broken residue). */
    val indexPresent: Boolean,
    /** The optional #57 `biometric.binding` envelope exists. */
    val biometricBindingPresent: Boolean,
    /** Files in `blobs/`, raw listing (0 when the directory is absent/unreadable). */
    val blobCount: Int,
    /** Sum of the `blobs/` file sizes in bytes (0 under the same rule). */
    val blobBytes: Long,
    /** Root is a directory AND a `.probe` file could be created and deleted inside it. */
    val rootWritable: Boolean,
)

/**
 * The hidden vault's app-private store (issue #32, ADR-0017): sealed blobs +
 * a sealed index + a sealed PIN record under one root directory.
 *
 * PURE JVM on purpose --- `java.io` and `javax.crypto` only, zero `android.*`
 * imports --- so `VaultStoreTest` exercises the exact production layout with
 * a temp directory and a locally generated AES key (the
 * [VaultCrypto.VaultKeyProvider] seam; production supplies the
 * non-exportable AndroidKeyStore key via `AndroidVaultKeys`). No emulator,
 * no Robolectric (ADR-0016).
 *
 * ## Two secrets (ADR-0017)
 * The PIN never encrypts anything: [setup] stores only a PBKDF2 digest +
 * salt, sealed under the media key; [verifyPin] re-derives and compares in
 * constant time. All CONTENT (media blobs, the index, the PIN record
 * itself) is sealed under the provider key with AES-256-GCM. Consequences:
 * the store can grow while locked (move-to-vault only requires the PIN
 * record to exist), while listing ([entries]) requires no PIN either ---
 * the PIN gates the unlock UI session, not the crypto.
 *
 * ## On-disk layout (file names never leak content)
 * ```
 * root/
 *   pin.bin     sealed PBKDF2 record: salt(16) || digest(32)
 *   index.bin   sealed [VaultIndex] serialization
 *   blobs/1.bin sealed media blob (IV(12) || ciphertext || GCM tag)
 *   biometric.binding  optional v1 biometric envelope (issue #57, ADR-0019)
 * ```
 * Blob file names are the entry's stable id, never the display name. The
 * `biometric.binding` file is the ADDITIVE #57 extension: absent = biometric
 * unlock not enrolled = the pre-#57 PIN-only behavior, byte for byte;
 * present = `version(1) || IV(12) || ciphertext(32) || GCM tag(16)` (a
 * session secret sealed under the auth-bound key), parsed strictly via
 * [VaultBiometricBinding] like the index. It is deliberately NOT sealed
 * under the media key: its confidentiality already rests on the biometric
 * key, and its lifetime follows the biometric key (re-enrollment replaces
 * it wholesale), not the media key.
 *
 * ## Crash-safety orderings (ADR-0017)
 *  - **store**: blob fully written FIRST, index persisted SECOND --- a crash
 *    in between leaves an orphan blob (harmless, reclaimed by [sweepOrphans]
 *    once aged past its grace window), never an index entry pointing at a
 *    missing blob.
 *  - **delete**: index entry removed FIRST, blob file deleted SECOND --- a
 *    crash in between leaves the same harmless orphan shape.
 *  - **restore**: [restoreOut] streams plaintext out but deliberately does
 *    NOT delete the entry --- the caller deletes via [delete] only after the
 *    plaintext landed durably (ADR-0017: unseal, then the new MediaStore row,
 *    then delete the blob). Deleting inside [restoreOut] would lose the media
 *    if the caller's sink fails after a successful copy.
 *  - Small files (pin record, index) are written to a `.tmp` sibling, fsynced,
 *    and renamed into place, so a crash or power loss never leaves a
 *    half-written index.
 *
 * ## Mutual exclusion + the sweep grace (the unlock-vs-move race)
 * Every MUTATING operation --- [setup], [store], [delete], [sweepOrphans],
 * [saveBiometricBinding], [removeBiometricBinding], [wipe] --- runs under
 * one private monitor ([storeLock]). This class is blocking-IO pure JVM, so
 * a plain `synchronized` block IS the mechanism (no `android.*` API, no
 * coroutine machinery). [store] and [delete] are read-modify-write cycles
 * over the index; unsynchronized, three interleavings destroyed data (the
 * first is the audit's P0 finding):
 *  1. UNLOCK-SWEEP vs MOVE: `moveToVault` runs while the vault is Locked
 *     (allowed by design --- the gate is `isConfigured`, not the session),
 *     and an unlock's [sweepOrphans] that ran between a store's blob write
 *     and its index write judged the fresh blob an "orphan", deleted it,
 *     and the move then committed an index entry pointing at a deleted
 *     blob while the caller deleted the original MediaStore row ---
 *     permanent loss. The serialized sweep now runs only BEFORE a store
 *     (nothing fresh to delete) or AFTER its index commit (the blob is
 *     known).
 *  2. MOVE vs MOVE: two concurrent stores read the same index and computed
 *     the same [VaultIndex.nextId]; the second store overwrote the first's
 *     blob and its index write dropped the first's entry.
 *  3. DELETE vs STORE/DELETE: interleaved index rewrites dropped or
 *     resurrected entries --- a dropped entry strands its blob, a
 *     resurrected entry dangles at a deleted blob.
 * The lock is deliberately held across [store]'s whole blob copy: a slow
 * [bytes] stream delays the other mutations, never corrupts them. Reads
 * ([entries], [open], [restoreOut], [verifyPin], [describeState]) stay
 * lock-free --- the atomic rename hands them the old or the new complete
 * index, never a mix, and the orderings above are already crash-safe. A
 * JVM monitor only serializes THIS instance, so [sweepOrphans] additionally
 * refuses to delete any swept file younger than [SWEEP_GRACE_MILLIS]:
 * that is the cross-instance and crash-residue leg (a blob orphaned by a
 * crash mid-move is young, and deleting young files is exactly the
 * interleave-1 loss; grace defers such files to a later sweep, it never
 * keeps them forever).
 *
 * ## PBKDF2 off the main thread
 * [setup] and [verifyPin] run 210k PBKDF2 rounds (~0.1-0.3 s). This class
 * cannot dispatch (pure JVM); the Android seam (`VaultRepository`) wraps
 * both in `Dispatchers.Default` per ADR-0017.
 *
 * Privacy (ADR-0010/ADR-0014): everything the store writes is on-device
 * ciphertext under a key that never leaves AndroidKeyStore; the app holds
 * no INTERNET permission, so no byte can leave the device.
 */
class VaultStore(
    private val root: File,
    private val keys: VaultCrypto.VaultKeyProvider,
) {

    /**
     * The mutual-exclusion monitor (see the class KDoc's section on the
     * unlock-vs-move race): serializes every mutating operation --- index
     * read-modify-write cycles ([store], [delete]), the [sweepOrphans]
     * deletion pass, and the shape mutations ([setup], [wipe], the
     * biometric-binding file). A private `Any` on purpose: no holder
     * outside this instance can ever contend on it.
     */
    private val storeLock = Any()

    /**
     * Creates the vault for [pin]: fresh salt, PBKDF2 digest, the sealed PIN
     * record, and a sealed EMPTY index. The PIN itself is never persisted.
     *
     * Double setup is REJECTED with [IllegalStateException]: silently
     * re-running setup would swap the PIN under an existing vault (a
     * surprising, easily-triggered destructive op); changing the PIN is an
     * explicit future feature that must handle re-sealing. The UI only
     * offers setup while unconfigured, so the throw is a loud invariant,
     * not a user-facing path.
     *
     * Transactional (issue follow-up to #32): both ciphertexts are staged
     * as `.tmp` files and committed by rename (index first, PIN last), and
     * the rollback covers the WHOLE staging+commit block, so a failure in
     * EITHER write --- including the second staging write (disk full),
     * whose `.tmp` residue used to linger until a later successful setup
     * because [sweepOrphans] early-returns while unconfigured --- rolls
     * back to a fully unconfigured store, never the pin-without-index state
     * that reported configured but could not load its index. A one-file
     * residue from a pre-fix crash or external deletion self-heals: it is
     * removed and setup proceeds fresh.
     *
     * Serialized behind [storeLock] (see the class KDoc): the configure
     * check, the self-heal, and the staged writes are one indivisible
     * step against [wipe]/[sweepOrphans]. The PBKDF2 cost rides inside
     * the lock on purpose --- setup is once-per-install rare, and the
     * pre-check must be re-observed under the same monitor that gates
     * the writes.
     */
    fun setup(pin: CharArray) {
        synchronized(storeLock) {
            val pinPresent = pinFile().exists()
            val indexPresent = indexFile().exists()
            when {
                pinPresent && indexPresent ->
                    throw IllegalStateException("vault is already configured; wipe() first")
                // Broken residue: one of the two files survived a pre-transactional
                // setup crash or was deleted externally. A vault cannot load
                // without BOTH files (the index is the only entry list), so the
                // remnant holds nothing recoverable --- remove it and self-heal
                // with a fresh setup instead of leaving the store permanently
                // wedged between "configured" (pin only) and "loadable".
                pinPresent || indexPresent -> selfHealBrokenResidue()
            }
            val salt = VaultCrypto.generateSalt()
            val digest = VaultCrypto.pinDigest(pin, salt)
            try {
                if (!root.mkdirs() && !root.isDirectory) {
                    throw VaultStoreException("cannot create vault root $root")
                }
                if (!blobsDir().mkdirs() && !blobsDir().isDirectory) {
                    throw VaultStoreException("cannot create vault blobs directory")
                }
                // Two-phase commit: STAGE both ciphertexts as `.tmp` files first,
                // then commit by rename. Commit order matters: the index lands
                // FIRST and the PIN record LAST --- `isConfigured()` treats the
                // pair as the marker, and a crash between the two renames can
                // then only leave a sweepable index (or nothing at all), never
                // the old "pin without index" state that looked configured but
                // could not load.
                try {
                    stageWrite(pinFile(), VaultCrypto.encrypt(salt + digest, keys.key()))
                    stageWrite(
                        indexFile(),
                        VaultCrypto.encrypt(VaultIndex.serialize(emptyList()), keys.key()),
                    )
                    if (!staged(indexFile()).renameTo(indexFile())) {
                        throw VaultStoreException("cannot commit the vault index")
                    }
                    if (!staged(pinFile()).renameTo(pinFile())) {
                        throw VaultStoreException("cannot commit the vault PIN record")
                    }
                } catch (e: Exception) {
                    // Rollback: neither file is configured yet (the check above
                    // guaranteed that), so everything on disk right now is THIS
                    // attempt's output --- staged or committed, ALL of it ---
                    // remove it and surface the failure. The catch spans the
                    // STAGING writes too, not just the renames: a failure in
                    // the second stageWrite (disk full, key error) used to
                    // strand the first `.tmp` behind, where the unconfigured
                    // sweep would never reclaim it.
                    staged(indexFile()).delete()
                    staged(pinFile()).delete()
                    indexFile().delete()
                    pinFile().delete()
                    throw VaultStoreException("vault setup failed; nothing was configured", e)
                }
            } finally {
                // The digest is the vault's secret-of-record; scrub it like a PIN.
                digest.fill(0)
            }
        }
    }

    /**
     * True once BOTH the sealed PIN record and the sealed index exist. A
     * single-file check lied after a mid-setup failure: with only `pin.bin`
     * on disk the vault reported configured while every [entries] call
     * threw (issue follow-up to #32; setup is transactional since, so this
     * guards against external deletion and pre-fix residue).
     */
    fun isConfigured(): Boolean = pinFile().exists() && indexFile().exists()

    /** Removes a broken one-file residue; a stuck file fails loudly. */
    private fun selfHealBrokenResidue() {
        for (file in listOf(pinFile(), indexFile())) {
            if (file.exists() && !file.delete()) {
                throw VaultStoreException(
                    "broken vault residue ${file.name} cannot be removed; wipe() first",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics (W1; read-only disk truth for the About screen)
    // ------------------------------------------------------------------

    /**
     * The pure-JVM state probe behind Settings > About > Diagnostics (W1):
     * the vault's DISK truth as plain values, so a user-side failure report
     * can show what the store actually looks like without decrypting
     * anything. Reads only (the sole mutation is [rootWritable]'s
     * create-and-delete `.probe` file, cleaned up in a `finally`); never
     * decrypts; never throws --- unreadable probes degrade to false/0, and
     * a missing `blobs/` listing (null) counts as 0. `blobCount`/`blobBytes`
     * are the RAW directory listing (every file, whatever its name): the
     * honest number, not the filtered one --- the sweep, not the probe, is
     * the judge of orphans.
     */
    fun describeState(): VaultStoreState {
        val blobs = blobsDir().listFiles()
        return VaultStoreState(
            rootExists = root.isDirectory,
            pinPresent = pinFile().exists(),
            indexPresent = indexFile().exists(),
            biometricBindingPresent = bindingFile().exists(),
            blobCount = blobs?.size ?: 0,
            blobBytes = blobs?.sumOf { it.length() } ?: 0L,
            rootWritable = probeRootWritable(),
        )
    }

    /**
     * Honest writability probe of [root]: a store-private `.probe` file
     * must be creatable AND deletable inside it. A stale leftover is
     * truncated, never trusted; the `finally` guarantees no residue even
     * when the delete itself is the failing step; an IOException (read-only
     * filesystem, full disk, broken directory) reports plain false.
     */
    private fun probeRootWritable(): Boolean {
        if (!root.isDirectory) return false
        val probe = File(root, PROBE_FILE_NAME)
        return try {
            FileOutputStream(probe).use { /* creating the file IS the probe */ }
            probe.delete()
        } catch (e: IOException) {
            false
        } finally {
            // Belt-and-braces: re-deleting a deleted file is a harmless
            // no-op, and this covers the throw-between window.
            probe.delete()
        }
    }

    // ------------------------------------------------------------------
    // Biometric binding (issue #57, ADR-0019; additive, optional file)
    // ------------------------------------------------------------------

    /** True once the optional `biometric.binding` file exists (#57 enrollment marker). */
    fun hasBiometricBinding(): Boolean = bindingFile().exists()

    /**
     * Persists the biometric binding: [sealedSecret] is the session secret
     * sealed under the auth-bound key (`IV(12) || ciphertext(32) || tag(16)`,
     * exactly [VaultBiometricBinding.SEALED_SECRET_BYTES] bytes --- anything
     * else is rejected loudly, the same store-honesty rule as the index).
     * The file is written through [atomicWrite], so a crash never leaves a
     * half-written binding. Requires a configured vault (like every write).
     * Serialized behind [storeLock] (see the class KDoc) --- an unlock's
     * [sweepOrphans] must never judge the in-flight `biometric.binding.tmp`
     * staging file.
     */
    fun saveBiometricBinding(sealedSecret: ByteArray) {
        synchronized(storeLock) {
            check(isConfigured()) { "vault is not configured; run setup() first" }
            if (!VaultBiometricBinding.isSealedSecretWellFormed(sealedSecret)) {
                throw VaultStoreException(
                    "biometric binding must be ${VaultBiometricBinding.SEALED_SECRET_BYTES} sealed bytes",
                )
            }
            atomicWrite(bindingFile(), VaultBiometricBinding.build(sealedSecret))
        }
    }

    /**
     * The stored sealed session secret, or null when no binding is enrolled
     * (the default, pre-#57 state). A present-but-unparseable file throws
     * [VaultStoreException] --- corruption is narrated as an error and the
     * user falls back to the PIN, never a silent "not enrolled".
     */
    fun loadBiometricBinding(): ByteArray? {
        if (!hasBiometricBinding()) return null
        val raw = try {
            bindingFile().readBytes()
        } catch (e: IOException) {
            throw VaultStoreException("biometric binding is unreadable", e)
        }
        return try {
            VaultBiometricBinding.parse(raw)
        } catch (e: VaultBiometricBinding.VaultBiometricBindingException) {
            throw VaultStoreException("biometric binding is malformed", e)
        }
    }

    /**
     * Removes the binding (the settings toggle's "off" leg, and the
     * invalidated-key cleanup). True when a file was removed. The session
     * secret and the AndroidKeyStore key are simply abandoned --- nothing
     * else references them once the envelope is gone. Serialized behind
     * [storeLock] like every mutation (see the class KDoc).
     */
    fun removeBiometricBinding(): Boolean =
        synchronized(storeLock) { bindingFile().delete() }

    /**
     * Verifies [pin] against the stored PBKDF2 record (constant-time).
     * Returns false when the vault is not configured (nothing to verify
     * against) or the PIN is wrong. A present-but-unreadable record
     * (tamper, wrong key) throws [VaultStoreException] --- that is a
     * corruption the UI must narrate as an error, never as "wrong PIN".
     */
    fun verifyPin(pin: CharArray): Boolean {
        if (!isConfigured()) return false
        val record = readPinRecord()
        return VaultCrypto.pinMatches(pin, record.salt, record.digest)
    }

    /**
     * Seals [bytes] into a new blob and appends the index entry, returning
     * it. [length] is the caller's declared size (e.g. MediaStore's SIZE
     * column); the entry records the ACTUAL plaintext byte count streamed
     * --- when the two disagree, the store keeps the truth.
     *
     * Crash order: blob first (orphan-safe), index second. A failure while
     * persisting the index leaves an orphan blob that [sweepOrphans]
     * reclaims once it ages past the grace window; nothing index-visible
     * ever half-exists.
     *
     * Serialized (see the class KDoc's mutual-exclusion section): the WHOLE
     * cycle --- index read, blob copy, index rewrite --- runs under
     * [storeLock], so a concurrent [sweepOrphans] or a second [store] can
     * never observe or clobber the half-done state (the unlock-vs-move
     * data-loss window). The deliberate cost: a slow [bytes] stream delays
     * the other mutations, never corrupts them.
     */
    fun store(
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
        addedAtMillis: Long,
        bytes: InputStream,
        length: Long,
    ): VaultIndexEntry = synchronized(storeLock) {
        check(isConfigured()) { "vault is not configured; run setup() first" }
        require(length >= 0) { "length must not be negative" }
        val entries = readIndex().toMutableList()
        val id = VaultIndex.nextId(entries)
        val blob = blobFile(id)
        if (!blobsDir().isDirectory) {
            throw VaultStoreException("vault blobs directory is missing")
        }
        val counting = CountingInputStream(bytes)
        try {
            FileOutputStream(blob).use { output ->
                VaultCrypto.encryptTo(counting, output, keys.key())
            }
        } catch (e: Exception) {
            // The half-written blob has no index entry: it is a plain orphan
            // for the sweep (once aged). Best-effort cleanup keeps the
            // directory tidy.
            blob.delete()
            throw e
        }
        val entry = VaultIndexEntry(
            id = id,
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = counting.count,
            addedAtMillis = addedAtMillis,
            isVideo = isVideo,
        )
        entries += entry
        try {
            atomicWrite(indexFile(), VaultCrypto.encrypt(VaultIndex.serialize(entries), keys.key()))
        } catch (e: Exception) {
            // Index persist failed: the blob must not linger as if indexed.
            blob.delete()
            throw e
        }
        entry
    }

    /** The decrypted index, in persistence (insertion) order. */
    fun entries(): List<VaultIndexEntry> = readIndex()

    /**
     * Opens [entry]'s plaintext stream. The caller owns and MUST close it,
     * and must treat any read failure as "blob rejected" (the GCM tag
     * authenticates the whole stream; a truncated or tampered blob fails on
     * the final read, never as partial content).
     */
    fun open(entry: VaultIndexEntry): InputStream {
        val blob = blobFile(entry.id)
        if (!blob.isFile) {
            throw VaultStoreException("blob ${entry.id} is missing")
        }
        return try {
            VaultCrypto.decryptFrom(blob.inputStream(), keys.key())
        } catch (e: VaultCryptoException) {
            throw VaultStoreException("blob ${entry.id} failed to open", e)
        }
    }

    /**
     * Removes [entry]: index entry FIRST, blob file SECOND (a crash between
     * leaves a sweepable orphan, never a dangling index entry). Throws
     * [VaultStoreException] when the id is not in the index --- deleting a
     * stale entry silently would hide a real state bug. Serialized behind
     * [storeLock] (see the class KDoc): the index rewrite and the blob
     * delete are one indivisible step against [store]/[sweepOrphans].
     */
    fun delete(entry: VaultIndexEntry) {
        synchronized(storeLock) {
            check(isConfigured()) { "vault is not configured" }
            val entries = readIndex().toMutableList()
            val removed = entries.removeAll { it.id == entry.id }
            if (!removed) {
                throw VaultStoreException("no index entry with id ${entry.id}")
            }
            atomicWrite(indexFile(), VaultCrypto.encrypt(VaultIndex.serialize(entries), keys.key()))
            blobFile(entry.id).delete()
        }
    }

    /**
     * Streams [entry]'s plaintext into [target] ([target] is NOT closed ---
     * the caller owns its sink and its lifecycle). Deliberately does NOT
     * delete the entry: see the class KDoc's crash-safety section --- the
     * caller deletes via [delete] only after the plaintext landed durably,
     * keeping ADR-0017's restore order (unseal, then the new MediaStore
     * row, then delete the blob) and never losing media to a sink that
     * fails after a successful copy.
     */
    fun restoreOut(entry: VaultIndexEntry, target: OutputStream) {
        open(entry).use { input -> input.copyTo(target) }
    }

    /**
     * The ADR-0017 integrity sweep: deletes blob files the index does not
     * reference (crash leftovers from [store]/[delete]) plus stray `.tmp`
     * files from interrupted [atomicWrite] runs. Returns how many files
     * were removed. No-op (returns 0) while unconfigured --- without the
     * sealed index there is no honest definition of "orphan", and bulk
     * deletion is [wipe]'s job, not the sweep's. Files that match neither
     * the blob naming scheme nor `.tmp` are left alone on purpose.
     *
     * Serialized behind [storeLock] (see the class KDoc's mutual-exclusion
     * section): the sweep can never observe a [store] parked between its
     * blob write and its index write --- the P0 unlock-vs-move interleave.
     *
     * Grace window: a swept file younger than [SWEEP_GRACE_MILLIS] (by
     * `File.lastModified`) is DEFERRED, not deleted. The monitor above only
     * serializes this instance, while the grace works across instances and
     * across crashes: a blob orphaned by a crash mid-move, or by any future
     * writer that forgets the lock, is YOUNG, and deleting young files is
     * exactly the interleave-1 loss. Deferred is not kept --- once the file
     * ages past the window, the NEXT sweep reclaims it.
     */
    fun sweepOrphans(): Int = synchronized(storeLock) {
        if (!isConfigured()) return 0
        val knownIds = readIndex().mapTo(mutableSetOf()) { it.id }
        var removed = 0
        blobsDir().listFiles()?.forEach { file ->
            val name = file.name
            val isOrphan = when {
                name.endsWith(TMP_SUFFIX) -> true
                name.endsWith(BLOB_SUFFIX) -> {
                    val id = name.removeSuffix(BLOB_SUFFIX).toLongOrNull()
                    id == null || id !in knownIds
                }
                else -> false // not ours to judge; see KDoc
            }
            if (isOrphan && isAgedPastSweepGrace(file) && file.delete()) removed++
        }
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(TMP_SUFFIX) }
            ?.forEach { if (isAgedPastSweepGrace(it) && it.delete()) removed++ }
        removed
    }

    /**
     * Destroys the whole vault: PIN record, index, every blob. Best-effort
     * (`File.deleteRecursively`); the caller re-checks [isConfigured] to
     * narrate honestly if something survived (a locked file on a broken
     * filesystem is the only realistic residue). The AndroidKeyStore key
     * itself is NOT deleted --- that is a documented follow-up (key
     * deletion/rotation policy); a surviving key without data is inert.
     * Serialized behind [storeLock] (see the class KDoc): a wipe racing a
     * [store] would otherwise delete the blobs directory out from under an
     * in-flight blob copy.
     */
    fun wipe() {
        synchronized(storeLock) { root.deleteRecursively() }
    }

    /**
     * True when [file]'s mtime is at least [SWEEP_GRACE_MILLIS] old, the
     * sweep's age gate ([File.lastModified] --- the JVM-pure file clock; no
     * injectable clock is needed, the tests age files through
     * `File.setLastModified` directly). An unavailable mtime (`0L`, an I/O
     * error) counts as AGED: an unreadable clock must never wedge the sweep
     * into keeping orphans forever, and the only file whose deletion the
     * grace exists to defer --- a freshly written blob --- always has a
     * fresh mtime.
     */
    private fun isAgedPastSweepGrace(file: File): Boolean =
        System.currentTimeMillis() - file.lastModified() >= SWEEP_GRACE_MILLIS

    // ------------------------------------------------------------------
    // File layout
    // ------------------------------------------------------------------

    private fun pinFile() = File(root, "pin$BLOB_SUFFIX")

    private fun indexFile() = File(root, "index$BLOB_SUFFIX")

    /** The optional #57 binding file (a non-`.bin` name on purpose: the sweep judges only `blobs/`). */
    private fun bindingFile() = File(root, "biometric.binding")

    private fun blobsDir() = File(root, "blobs")

    private fun blobFile(id: Long) = File(blobsDir(), "$id$BLOB_SUFFIX")

    /**
     * The staging half of [setup]'s two-phase commit: writes [bytes] to the
     * `.tmp` sibling only (no rename --- the caller commits both targets in
     * order and rolls back on any failure). Stale `.tmp` leftovers are
     * reclaimed by [sweepOrphans] (once aged) and by the next setup's
     * rollback.
     */
    private fun stageWrite(target: File, bytes: ByteArray) {
        val tmp = staged(target)
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
    }

    /** The `.tmp` staging path of [target]. */
    private fun staged(target: File): File = File(target.parentFile, target.name + TMP_SUFFIX)

    /**
     * Writes [bytes] to a `.tmp` sibling, then renames into place. On
     * Linux/Android the rename atomically replaces the target, so a crash
     * leaves either the old or the new complete file, never a mix. The
     * delete-then-rename fallback only serves JVM hosts whose rename
     * refuses to replace (not the production platform).
     *
     * The staged bytes are fsynced ([FileOutputStream.getFD].sync) before
     * the rename: power-loss durability. Without it a sudden loss can
     * leave the COMMITTED name pointing at unflushed bytes --- the GCM
     * seal makes the rare corruption DETECTABLE (the next read fails
     * authentication), the sync makes it rare in the first place.
     */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) {
                    throw VaultStoreException("cannot replace ${target.name}")
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun readIndex(): List<VaultIndexEntry> {
        val sealed = try {
            indexFile().readBytes()
        } catch (e: IOException) {
            throw VaultStoreException("index is missing or unreadable", e)
        }
        val plain = try {
            VaultCrypto.decrypt(sealed, keys.key())
        } catch (e: VaultCryptoException) {
            throw VaultStoreException("index failed authentication", e)
        }
        return try {
            VaultIndex.deserialize(plain)
        } catch (e: VaultIndex.VaultIndexException) {
            throw VaultStoreException("index is malformed", e)
        }
    }

    /** The decrypted PIN record: salt plus expected digest. */
    private data class PinRecord(val salt: ByteArray, val digest: ByteArray)

    private fun readPinRecord(): PinRecord {
        val sealed = try {
            pinFile().readBytes()
        } catch (e: IOException) {
            throw VaultStoreException("PIN record is missing or unreadable", e)
        }
        val plain = try {
            VaultCrypto.decrypt(sealed, keys.key())
        } catch (e: VaultCryptoException) {
            throw VaultStoreException("PIN record failed authentication", e)
        }
        if (plain.size != VaultCrypto.SALT_BYTES + VaultCrypto.KEY_BITS / 8) {
            throw VaultStoreException("PIN record has an unexpected shape")
        }
        return PinRecord(
            salt = plain.copyOfRange(0, VaultCrypto.SALT_BYTES),
            digest = plain.copyOfRange(VaultCrypto.SALT_BYTES, plain.size),
        )
    }

    /** Counts plaintext bytes as [VaultCrypto.encryptTo] drains the source. */
    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        var count: Long = 0
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val n = super.read(buffer, offset, length)
            if (n > 0) count += n
            return n
        }
    }

    private companion object {
        /** Sealed-file suffix (structural name; never content-derived). */
        const val BLOB_SUFFIX = ".bin"

        /** In-flight suffix of [atomicWrite]; swept when found stale. */
        const val TMP_SUFFIX = ".tmp"

        /**
         * The sweep's grace window, in epoch milliseconds ([File]
         * mtime base): a swept file younger than this is deferred, not
         * deleted --- see [sweepOrphans]. Ten minutes covers any move
         * batch and re-locks the unlock-vs-move window even for callers
         * outside this instance's monitor; an honest one-orphan-per-
         * grace-period residue is the accepted price.
         */
        const val SWEEP_GRACE_MILLIS = 10L * 60L * 1000L

        /** [probeRootWritable]'s transient file; store-private, never a sealed artifact. */
        const val PROBE_FILE_NAME = ".probe"
    }
}
