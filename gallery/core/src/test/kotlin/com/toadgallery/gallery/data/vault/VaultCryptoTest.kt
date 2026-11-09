package com.toadgallery.gallery.data.vault

import com.toadgallery.gallery.data.vault.VaultCrypto.VaultCryptoException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * JVM tests for [VaultCrypto] (issue #32, ADR-0017).
 *
 * The crypto core is pure `javax.crypto`, so the EXACT production ciphers
 * (PBKDF2-HMAC-SHA256, AES-256-GCM) run on the plain JVM under
 * `testDebugUnitTest` --- no emulator, no Robolectric (ADR-0016). The key
 * seam is [VaultCrypto.VaultKeyProvider]: tests use a locally generated
 * AES-256 key where production will hand over the non-exportable
 * AndroidKeyStore key (thin wrapper, later UI slice).
 */
class VaultCryptoTest {

    private val key: SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(VaultCrypto.KEY_BITS) }
        .generateKey()

    private val otherKey: SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(VaultCrypto.KEY_BITS) }
        .generateKey()

    // ---- PIN factor ---------------------------------------------------

    @Test
    fun `correct pin verifies against its digest`() {
        val salt = VaultCrypto.generateSalt()
        val digest = VaultCrypto.pinDigest("123456".toCharArray(), salt)
        assertTrue(VaultCrypto.pinMatches("123456".toCharArray(), salt, digest))
    }

    @Test
    fun `wrong pin is rejected`() {
        val salt = VaultCrypto.generateSalt()
        val digest = VaultCrypto.pinDigest("123456".toCharArray(), salt)
        assertFalse(VaultCrypto.pinMatches("654321".toCharArray(), salt, digest))
    }

    @Test
    fun `same pin under different salts yields different digests`() {
        val d1 = VaultCrypto.pinDigest("123456".toCharArray(), VaultCrypto.generateSalt())
        val d2 = VaultCrypto.pinDigest("123456".toCharArray(), VaultCrypto.generateSalt())
        assertFalse(d1.contentEquals(d2))
    }

    @Test
    fun `salt is the configured length and unique across draws`() {
        val a = VaultCrypto.generateSalt()
        val b = VaultCrypto.generateSalt()
        assertEquals(VaultCrypto.SALT_BYTES, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `digest is the configured key length`() {
        val digest = VaultCrypto.pinDigest("9".toCharArray(), VaultCrypto.generateSalt())
        assertEquals(VaultCrypto.KEY_BITS / 8, digest.size)
    }

    // ---- Byte-array seal ----------------------------------------------

    @Test
    fun `seal and open round-trip the payload`() {
        val payload = ByteArray(4096) { it.toByte() }
        val opened = VaultCrypto.decrypt(VaultCrypto.encrypt(payload, key), key)
        assertTrue(payload.contentEquals(opened))
    }

    @Test
    fun `opening with the wrong key fails authentication`() {
        val sealed = VaultCrypto.encrypt("vault".toByteArray(), key)
        assertThrows(VaultCryptoException::class.java) {
            VaultCrypto.decrypt(sealed, otherKey)
        }
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val sealed = VaultCrypto.encrypt("vault".toByteArray(), key)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(VaultCryptoException::class.java) {
            VaultCrypto.decrypt(sealed, key)
        }
    }

    @Test
    fun `truncated blob is rejected without touching the provider`() {
        val sealed = VaultCrypto.encrypt("vault".toByteArray(), key)
        assertThrows(VaultCryptoException::class.java) {
            VaultCrypto.decrypt(sealed.copyOfRange(0, VaultCrypto.IV_BYTES), key)
        }
    }

    @Test
    fun `fresh random IV per seal - identical payloads seal differently`() {
        val payload = "same bytes".toByteArray()
        val a = VaultCrypto.encrypt(payload, key)
        val b = VaultCrypto.encrypt(payload, key)
        assertFalse(a.contentEquals(b))
        assertEquals(VaultCrypto.IV_BYTES + "same bytes".length + 16, a.size)
    }

    // ---- Provider-IV encrypt init (the r8 device fix) ------------------
    //
    // AndroidKeyStore's default randomized-encryption policy rejects
    // caller-provided IVs on encrypt ("Caller-provided IV not permitted"),
    // which killed every real-device vault setup while this suite stayed
    // green (software keys accept caller IVs). initEncrypt therefore uses
    // the canonical provider-IV init FIRST; these tests pin its contract
    // on the JVM leg. The AndroidKeyStore leg is the same code path ---
    // parameter-less init --- that the platform documents for keystore
    // keys, and the caller-IV fallback exists for exotic providers only.

    @Test
    fun `initEncrypt yields a 12-byte IV and a usable initialized cipher`() {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val iv = VaultCrypto.initEncrypt(cipher, key)
        assertEquals(VaultCrypto.IV_BYTES, iv.size)
        // The cipher must be ready to seal under the negotiated IV: the
        // round-trip proves the IV matches the ciphertext it prefixed.
        val sealed = iv + cipher.doFinal("negotiated".toByteArray())
        assertEquals(
            "negotiated",
            String(VaultCrypto.decrypt(sealed, key)),
        )
    }

    @Test
    fun `initEncrypt draws a fresh provider IV per call`() {
        val first = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            .let { VaultCrypto.initEncrypt(it, key) }
        val second = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            .let { VaultCrypto.initEncrypt(it, key) }
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `provider-IV seal still follows the ADR envelope shape`() {
        // IV(12) || ciphertext || tag(16), byte-countable exactly like the
        // caller-IV seals above --- the wire format did not change.
        val sealed = VaultCrypto.encrypt("envelope".toByteArray(), key)
        assertEquals(VaultCrypto.IV_BYTES + "envelope".length + 16, sealed.size)
    }

    // ---- Streaming seal (vault media) ---------------------------------

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    @Test
    fun `stream round-trip restores a 5 MiB payload`() {
        val payload = randomBytes(5 * 1024 * 1024)
        val out = ByteArrayOutputStream(payload.size + VaultCrypto.IV_BYTES + 16)
        VaultCrypto.encryptTo(ByteArrayInputStream(payload), out, key)
        val opened = VaultCrypto.decryptFrom(
            ByteArrayInputStream(out.toByteArray()),
            key,
        ).use { it.readBytes() }
        assertTrue(payload.contentEquals(opened))
    }

    @Test
    fun `tampered stream fails while reading the final bytes`() {
        val payload = "stream me".toByteArray()
        val out = ByteArrayOutputStream()
        VaultCrypto.encryptTo(ByteArrayInputStream(payload), out, key)
        val sealed = out.toByteArray()
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) {
            VaultCrypto.decryptFrom(ByteArrayInputStream(sealed), key).use { it.readBytes() }
        }
    }

    @Test
    fun `empty stream seals to iv plus tag and round-trips`() {
        val out = ByteArrayOutputStream()
        VaultCrypto.encryptTo(ByteArrayInputStream(ByteArray(0)), out, key)
        assertEquals(VaultCrypto.IV_BYTES + 16, out.size())
        val opened = VaultCrypto.decryptFrom(
            ByteArrayInputStream(out.toByteArray()),
            key,
        ).use { it.readBytes() }
        assertEquals(0, opened.size)
    }
}
