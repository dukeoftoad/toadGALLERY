package io.github.dukeoftoad.gallery.data.vault

import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * toadGALLERY's hidden-vault crypto core (issue #32, ADR-0017).
 *
 * Deliberately a PURE-JVM object --- `javax.crypto` only, zero Android
 * imports --- so `VaultCryptoTest` exercises the exact production ciphers on
 * the plain JVM (`testDebugUnitTest`, no emulator per ADR-0016). The Android
 * side of the design is a thin seam: [VaultKeyProvider] hands this object a
 * `SecretKey` (production: a non-exportable AndroidKeyStore AES key;
 * tests: a locally generated one), and the UI layer (later slice) gates
 * calls behind PIN/biometric verification.
 *
 * ## The two secrets (ADR-0017 summary)
 *  - **PIN factor**: PBKDF2-HMAC-SHA256 ([PIN_ITERATIONS] rounds, random
 *    16-byte salt) --- the digest is what the vault stores; the PIN itself
 *    is never persisted. Verified with a constant-time compare.
 *  - **Media factor**: AES-256-GCM under the [VaultKeyProvider] key. Every
 *    sealed blob is `IV(12) || ciphertext+tag(128-bit)`; encryption uses a
 *    fresh random IV per call, so re-encrypting the same file yields
 *    different bytes.
 *
 * ## Streaming
 * Vault media includes videos well beyond heap size, so the streaming pair
 * [encryptTo]/[decryptFrom] runs `CipherInputStream`/`CipherOutputStream`
 * over the file streams --- the byte-array pair [encrypt]/[decrypt] exists
 * for small payloads (PIN blobs, metadata) only. The GCM tag is verified
 * when the stream is fully read; a truncated or tampered stream fails the
 * final read with AEADBadTagException (surfaced as [VaultCryptoException]
 * by the byte-array API).
 *
 * Privacy (ADR-0010/ADR-0014): everything here is on-device javax.crypto;
 * no key material, plaintext, or vault blob ever leaves the process, and
 * the app holds no INTERNET permission to leak it with.
 */
object VaultCrypto {

    /** PBKDF2 rounds for the PIN digest (OWASP-style budget; ~0.1-0.3s JVM). */
    const val PIN_ITERATIONS = 210_000

    const val SALT_BYTES = 16
    const val IV_BYTES = 12
    const val GCM_TAG_BITS = 128
    const val KEY_BITS = 256

    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    private val random = SecureRandom()

    /** The vault's key seam: production = AndroidKeyStore, tests = local. */
    fun interface VaultKeyProvider {
        fun key(): SecretKey
    }

    /** Thrown when a sealed blob fails authentication (wrong key/tamper). */
    class VaultCryptoException(cause: Throwable) : Exception(cause)

    /** Fresh random salt for a new PIN digest. */
    fun generateSalt(): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    /** Fresh random IV for one seal operation. */
    private fun generateIv(): ByteArray = ByteArray(IV_BYTES).also { random.nextBytes(it) }

    /**
     * Initializes [cipher] for ONE seal operation and returns the IV that
     * the caller MUST store alongside the ciphertext (the ADR-0017 envelope
     * is `IV(12) || ciphertext || tag`).
     *
     * Provider-IV first, caller-IV fallback (the r8 device round): the
     * canonical AndroidKeyStore GCM pattern is `init(ENCRYPT_MODE, key)`
     * with NO parameters --- the keystore generates the IV and exposes it
     * via `Cipher.getIV()` --- because AndroidKeyStore's default
     * randomized-encryption policy REJECTS caller-provided IVs on encrypt
     * with `InvalidAlgorithmParameterException("Caller-provided IV not
     * permitted")`. That rejection is exactly what killed every real-device
     * vault setup since the first slice: the JVM tests run against software
     * keys (caller IVs accepted), so the suite stayed green while the device
     * could not even seal the PIN record. A provider that instead demands
     * explicit parameters (none of the tested ones do) falls back to a
     * caller-generated fresh random IV --- the pre-fix behavior, which
     * software keys accept. Both legs guarantee a fresh 12-byte IV per
     * call, never reused under one key; the decrypt side is untouched
     * (caller IVs ARE permitted in DECRYPT mode, and [decrypt] reads the
     * stored IV exactly as before).
     */
    internal fun initEncrypt(cipher: Cipher, key: SecretKey): ByteArray {
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val providerIv = cipher.iv
            if (providerIv != null && providerIv.size == IV_BYTES) return providerIv
        } catch (_: GeneralSecurityException) {
            // This provider refuses parameter-less GCM init (or produced no
            // usable IV) --- the caller-IV leg below is the fallback.
        }
        val iv = generateIv()
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv
    }

    /**
     * Derives the PIN digest: PBKDF2-HMAC-SHA256 over the PIN's UTF-8
     * bytes. The PIN arrives as [CharArray] so the UI layer can wipe it
     * after use; this object keeps no reference.
     */
    fun pinDigest(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, PIN_ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM)
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Constant-time PIN verification: re-derives the digest and compares
     * all bytes regardless of where a mismatch occurs.
     */
    fun pinMatches(pin: CharArray, salt: ByteArray, expectedDigest: ByteArray): Boolean {
        val candidate = pinDigest(pin, salt)
        if (candidate.size != expectedDigest.size) return false
        var diff = 0
        for (i in candidate.indices) {
            diff = diff or (candidate[i].toInt() xor expectedDigest[i].toInt())
        }
        return diff == 0
    }

    /**
     * Seals a small payload: returns `IV(12) || ciphertext || GCM tag(16)`.
     * The IV is whatever [initEncrypt] negotiated (provider-generated on
     * AndroidKeyStore, caller-generated on exotic providers).
     */
    fun encrypt(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val iv = initEncrypt(cipher, key)
        return iv + cipher.doFinal(plain)
    }

    /**
     * Opens a blob produced by [encrypt]. Any wrong-key, tampered, or
     * truncated input fails GCM authentication and surfaces as
     * [VaultCryptoException].
     */
    fun decrypt(sealed: ByteArray, key: SecretKey): ByteArray {
        if (sealed.size <= IV_BYTES) {
            throw VaultCryptoException(IllegalArgumentException("Sealed blob too short"))
        }
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, sealed.copyOfRange(0, IV_BYTES)),
            )
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (e: Exception) {
            if (e is VaultCryptoException) throw e
            throw VaultCryptoException(e)
        }
    }

    /**
     * Streaming seal for vault media (files beyond heap size): writes
     * `IV(12)` to [output] first, then the encrypted stream. The caller
     * owns both streams; [input] is NOT closed (callers chain files).
     */
    fun encryptTo(input: InputStream, output: OutputStream, key: SecretKey) {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val iv = initEncrypt(cipher, key)
        output.write(iv)
        CipherOutputStream(output, cipher).use { sealed ->
            input.copyTo(sealed, bufferSize = DEFAULT_COPY_BUFFER)
        }
    }

    /**
     * Streaming open for a blob produced by [encryptTo]: reads the leading
     * IV, then yields the plaintext stream. The GCM tag authenticates the
     * WHOLE stream --- a truncated/tampered file fails while reading the
     * final bytes (read() throws IOException wrapping AEADBadTag), which
     * the caller must treat as "blob rejected", never as partial content.
     */
    fun decryptFrom(input: InputStream, key: SecretKey): InputStream {
        val iv = ByteArray(IV_BYTES)
        var read = 0
        while (read < IV_BYTES) {
            val n = input.read(iv, read, IV_BYTES - read)
            if (n < 0) throw VaultCryptoException(IllegalArgumentException("Sealed stream too short"))
            read += n
        }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return CipherInputStream(input, cipher)
    }

    private const val DEFAULT_COPY_BUFFER = 64 * 1024
}
