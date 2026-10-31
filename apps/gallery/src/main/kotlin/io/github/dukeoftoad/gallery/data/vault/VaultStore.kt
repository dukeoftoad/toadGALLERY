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
 *    in between leaves an orphan blob (harmless, reclaimed by [sweepOrphans]),
 *    never an index entry pointing at a missing blob.
 *  - **delete**: index entry removed FIRST, blob file deleted SECOND --- a
 *    crash in between leaves the same harmless orphan shape.
 *  - **restore**: [restoreOut] streams plaintext out but deliberately does
 *    NOT delete the entry --- the caller deletes via [delete] only after the
 *    plaintext landed durably (ADR-0017: unseal, then the new MediaStore row,
 *    then delete the blob). Deleting inside [restoreOut] would lose the media
 *    if the caller's sink fails after a successful copy.
 *  - Small files (pin record, index) are written to a `.tmp` sibling and
 *    renamed into place, so a crash never leaves a half-written index.
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
     * as `.tmp` files and committed by rename (index first, PIN last), so a
     * failure in either write rolls back to a fully unconfigured store ---
     * never the pin-without-index state that reported configured but could
     * not load its index. A one-file residue from a pre-fix crash or
     * external deletion self-heals: it is removed and setup proceeds fresh.
     */
    fun setup(pin: CharArray) {
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
        if (!root.mkdirs() && !root.isDirectory) {
            throw VaultStoreException("cannot create vault root $root")
        }
        if (!blobsDir().mkdirs() && !blobsDir().isDirectory) {
            throw VaultStoreException("cannot create vault blobs directory")
        }
        try {
            // Two-phase commit: STAGE both ciphertexts as `.tmp` files first,
            // then commit by rename. Commit order matters: the index lands
            // FIRST and the PIN record LAST --- `isConfigured()` treats the
            // pair as the marker, and a crash between the two renames can
            // then only leave a sweepable index (or nothing at all), never
            // the old "pin without index" state that looked configured but
            // could not load.
            stageWrite(pinFile(), VaultCrypto.encrypt(salt + digest, keys.key()))
            stageWrite(
                indexFile(),
                VaultCrypto.encrypt(VaultIndex.serialize(emptyList()), keys.key()),
            )
            try {
                if (!staged(indexFile()).renameTo(indexFile())) {
                    throw VaultStoreException("cannot commit the vault index")
                }
                if (!staged(pinFile()).renameTo(pinFile())) {
                    throw VaultStoreException("cannot commit the vault PIN record")
                }
            } catch (e: VaultStoreException) {
                // Rollback: neither file is configured yet (the check above
                // guaranteed that), so everything on disk right now is THIS
                // attempt's output --- remove it all and surface the failure.
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
     */
    fun saveBiometricBinding(sealedSecret: ByteArray) {
        check(isConfigured()) { "vault is not configured; run setup() first" }
        if (!VaultBiometricBinding.isSealedSecretWellFormed(sealedSecret)) {
            throw VaultStoreException(
                "biometric binding must be ${VaultBiometricBinding.SEALED_SECRET_BYTES} sealed bytes",
            )
        }
        atomicWrite(bindingFile(), VaultBiometricBinding.build(sealedSecret))
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
     * else references them once the envelope is gone.
     */
    fun removeBiometricBinding(): Boolean = bindingFile().delete()

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
     * reclaims; nothing index-visible ever half-exists.
     */
    fun store(
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
        addedAtMillis: Long,
        bytes: InputStream,
        length: Long,
    ): VaultIndexEntry {
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
            // for the sweep. Best-effort cleanup keeps the directory tidy.
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
        return entry
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
     * stale entry silently would hide a real state bug.
     */
    fun delete(entry: VaultIndexEntry) {
        check(isConfigured()) { "vault is not configured" }
        val entries = readIndex().toMutableList()
        val removed = entries.removeAll { it.id == entry.id }
        if (!removed) {
            throw VaultStoreException("no index entry with id ${entry.id}")
        }
        atomicWrite(indexFile(), VaultCrypto.encrypt(VaultIndex.serialize(entries), keys.key()))
        blobFile(entry.id).delete()
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
     */
    fun sweepOrphans(): Int {
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
            if (isOrphan && file.delete()) removed++
        }
        root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(TMP_SUFFIX) }
            ?.forEach { if (it.delete()) removed++ }
        return removed
    }

    /**
     * Destroys the whole vault: PIN record, index, every blob. Best-effort
     * (`File.deleteRecursively`); the caller re-checks [isConfigured] to
     * narrate honestly if something survived (a locked file on a broken
     * filesystem is the only realistic residue). The AndroidKeyStore key
     * itself is NOT deleted --- that is a documented follow-up (key
     * deletion/rotation policy); a surviving key without data is inert.
     */
    fun wipe() {
        root.deleteRecursively()
    }

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
     * reclaimed by [sweepOrphans] and by the next setup's rollback.
     */
    private fun stageWrite(target: File, bytes: ByteArray) {
        val tmp = staged(target)
        FileOutputStream(tmp).use { it.write(bytes) }
    }

    /** The `.tmp` staging path of [target]. */
    private fun staged(target: File): File = File(target.parentFile, target.name + TMP_SUFFIX)

    /**
     * Writes [bytes] to a `.tmp` sibling, then renames into place. On
     * Linux/Android the rename atomically replaces the target, so a crash
     * leaves either the old or the new complete file, never a mix. The
     * delete-then-rename fallback only serves JVM hosts whose rename
     * refuses to replace (not the production platform).
     */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { it.write(bytes) }
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
    }
}
