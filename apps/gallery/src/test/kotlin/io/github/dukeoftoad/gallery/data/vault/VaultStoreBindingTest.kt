package io.github.dukeoftoad.gallery.data.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * JVM tests for the store's ADDITIVE biometric-binding leg (issue #57,
 * ADR-0019): the optional `biometric.binding` file.
 *
 * Same rig as `VaultStoreTest` (temp directory + a locally generated AES key
 * through the [VaultCrypto.VaultKeyProvider] seam; ADR-0016 --- the exact
 * production layout, no emulator). The #57 contract pinned here: absent file
 * = not enrolled = the pre-#57 behavior; present file = strict parse; the
 * sweep and the rest of the store never treat the file as theirs.
 */
class VaultStoreBindingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key: SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(VaultCrypto.KEY_BITS) }
        .generateKey()

    private val pin = "1234"

    private fun configuredStore(): Pair<File, VaultStore> {
        val root = tmp.newFolder()
        val store = VaultStore(root = root, keys = { key })
        store.setup(pin.toCharArray())
        return root to store
    }

    /** A well-formed sealed secret (random bytes of the documented size). */
    private fun sealedSecret(): ByteArray =
        ByteArray(VaultBiometricBinding.SEALED_SECRET_BYTES).also { SecureRandom().nextBytes(it) }

    @Test
    fun `a fresh vault has no biometric binding and loads null`() {
        val (_, store) = configuredStore()
        assertFalse(store.hasBiometricBinding())
        assertNull(store.loadBiometricBinding())
    }

    @Test
    fun `save and load round-trip the sealed secret`() {
        val (_, store) = configuredStore()
        val sealed = sealedSecret()
        store.saveBiometricBinding(sealed)
        assertTrue(store.hasBiometricBinding())
        assertArrayEquals(sealed, store.loadBiometricBinding())
    }

    @Test
    fun `the binding file is the documented optional layout entry`() {
        val (root, store) = configuredStore()
        store.saveBiometricBinding(sealedSecret())
        // Additive layout: root keeps exactly pin.bin, index.bin, blobs/,
        // biometric.binding --- file names never leak content.
        val names = root.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("biometric.binding", "blobs", "index.bin", "pin.bin"), names)
    }

    @Test
    fun `save rejects a malformed sealed secret`() {
        val (_, store) = configuredStore()
        assertThrows(VaultStoreException::class.java) {
            store.saveBiometricBinding(ByteArray(VaultBiometricBinding.SEALED_SECRET_BYTES - 1))
        }
        assertFalse(store.hasBiometricBinding())
    }

    @Test
    fun `save requires a configured vault`() {
        val store = VaultStore(root = tmp.newFolder(), keys = { key })
        assertThrows(IllegalStateException::class.java) {
            store.saveBiometricBinding(sealedSecret())
        }
    }

    @Test
    fun `a malformed binding file on disk is a loud error, never null`() {
        val (root, store) = configuredStore()
        File(root, "biometric.binding").writeBytes(ByteArray(3))
        assertTrue(store.hasBiometricBinding())
        assertThrows(VaultStoreException::class.java) {
            store.loadBiometricBinding()
        }
    }

    @Test
    fun `remove deletes the binding and returns to the pre-#57 state`() {
        val (_, store) = configuredStore()
        store.saveBiometricBinding(sealedSecret())
        assertTrue(store.removeBiometricBinding())
        assertFalse(store.hasBiometricBinding())
        assertNull(store.loadBiometricBinding())
        assertFalse(store.removeBiometricBinding())
    }

    @Test
    fun `the orphan sweep leaves the binding file alone`() {
        val (root, store) = configuredStore()
        store.saveBiometricBinding(sealedSecret())
        store.sweepOrphans()
        assertTrue(store.hasBiometricBinding())
        assertTrue(File(root, "biometric.binding").isFile)
    }

    @Test
    fun `re-saving replaces the binding atomically`() {
        val (_, store) = configuredStore()
        val first = sealedSecret()
        store.saveBiometricBinding(first)
        val second = sealedSecret()
        store.saveBiometricBinding(second)
        assertArrayEquals(second, store.loadBiometricBinding())
    }

    @Test
    fun `wipe destroys the binding with the rest of the vault`() {
        val (root, store) = configuredStore()
        store.saveBiometricBinding(sealedSecret())
        store.wipe()
        assertFalse(store.hasBiometricBinding())
        assertFalse(File(root, "biometric.binding").exists())
    }

    @Test
    fun `the PIN path is untouched by the binding`() {
        val (_, store) = configuredStore()
        store.saveBiometricBinding(sealedSecret())
        assertTrue(store.verifyPin(pin.toCharArray()))
        assertFalse(store.verifyPin("0000".toCharArray()))
    }
}
