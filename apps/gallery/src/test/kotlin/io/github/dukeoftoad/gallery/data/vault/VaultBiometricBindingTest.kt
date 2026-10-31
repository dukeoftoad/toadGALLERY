package io.github.dukeoftoad.gallery.data.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the biometric binding envelope (issue #57, ADR-0019).
 *
 * [VaultBiometricBinding] is pure JVM by design, so the format contract ---
 * version fence, exact length, strict rejection, session-secret shape --- is
 * pinned here exactly the way `VaultIndexTest` pins the index (ADR-0016; no
 * emulator, no Robolectric). The Keystore/BiometricPrompt halves are the
 * documented device-only seam and stay out of the JVM suite.
 *
 * Privacy (ADR-0010/ADR-0014): nothing here touches anything but in-memory
 * byte arrays; the app holds no INTERNET permission.
 */
class VaultBiometricBindingTest {

    /** A syntactically well-formed sealed secret: random bytes of the right size. */
    private fun sealedSecret(): ByteArray =
        ByteArray(VaultBiometricBinding.SEALED_SECRET_BYTES).also { java.security.SecureRandom().nextBytes(it) }

    @Test
    fun `build and parse round-trip the sealed secret exactly`() {
        val sealed = sealedSecret()
        val parsed = VaultBiometricBinding.parse(VaultBiometricBinding.build(sealed))
        assertArrayEquals(sealed, parsed)
    }

    @Test
    fun `the envelope is version byte then sealed secret`() {
        val sealed = sealedSecret()
        val envelope = VaultBiometricBinding.build(sealed)
        assertEquals(1 + VaultBiometricBinding.SEALED_SECRET_BYTES, envelope.size)
        assertEquals(VaultBiometricBinding.FORMAT_VERSION.toByte(), envelope[0])
        assertArrayEquals(sealed, envelope.copyOfRange(1, envelope.size))
    }

    @Test
    fun `parse rejects a wrong version byte`() {
        val bad = VaultBiometricBinding.build(sealedSecret())
        bad[0] = (VaultBiometricBinding.FORMAT_VERSION + 1).toByte()
        assertThrows(VaultBiometricBinding.VaultBiometricBindingException::class.java) {
            VaultBiometricBinding.parse(bad)
        }
    }

    @Test
    fun `parse rejects a truncated envelope`() {
        val envelope = VaultBiometricBinding.build(sealedSecret())
        assertThrows(VaultBiometricBinding.VaultBiometricBindingException::class.java) {
            VaultBiometricBinding.parse(envelope.copyOfRange(0, envelope.size - 1))
        }
    }

    @Test
    fun `parse rejects a padded envelope`() {
        val envelope = VaultBiometricBinding.build(sealedSecret())
        assertThrows(VaultBiometricBinding.VaultBiometricBindingException::class.java) {
            VaultBiometricBinding.parse(envelope + 0)
        }
    }

    @Test
    fun `parse rejects an empty envelope`() {
        assertThrows(VaultBiometricBinding.VaultBiometricBindingException::class.java) {
            VaultBiometricBinding.parse(ByteArray(0))
        }
    }

    @Test
    fun `build rejects a sealed secret of the wrong length`() {
        assertThrows(IllegalArgumentException::class.java) {
            VaultBiometricBinding.build(ByteArray(VaultBiometricBinding.SEALED_SECRET_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VaultBiometricBinding.build(ByteArray(VaultBiometricBinding.SEALED_SECRET_BYTES - 1))
        }
    }

    @Test
    fun `the sealed-secret size is IV plus secret plus GCM tag`() {
        // IV(12) || ciphertext(32) || tag(16) = 60: the documented blob shape.
        assertEquals(
            VaultCrypto.IV_BYTES + VaultBiometricBinding.SESSION_SECRET_BYTES + VaultCrypto.GCM_TAG_BITS / 8,
            VaultBiometricBinding.SEALED_SECRET_BYTES,
        )
    }

    @Test
    fun `new session secrets are 32 random bytes`() {
        val first = VaultBiometricBinding.newSessionSecret()
        val second = VaultBiometricBinding.newSessionSecret()
        assertEquals(VaultBiometricBinding.SESSION_SECRET_BYTES, first.size)
        assertTrue(VaultBiometricBinding.isSessionSecret(first))
        assertFalse(VaultBiometricBinding.isSessionSecret(first.copyOf(first.size - 1)))
        assertNotEquals(first.toList(), second.toList()) // random, not a constant
    }

    @Test
    fun `readiness hardware wins over a stale enrollment flag`() {
        // The decision table of VaultBiometricSupport: without a usable
        // prompt the enrollment flag must not surface any biometric UI.
        assertEquals(
            VaultBiometricReadiness.Unsupported,
            VaultBiometricSupport.readiness(hardwareCanPrompt = false, bindingEnrolled = false),
        )
        assertEquals(
            VaultBiometricReadiness.Unsupported,
            VaultBiometricSupport.readiness(hardwareCanPrompt = false, bindingEnrolled = true),
        )
        assertEquals(
            VaultBiometricReadiness.Supported(bindingEnrolled = false),
            VaultBiometricSupport.readiness(hardwareCanPrompt = true, bindingEnrolled = false),
        )
        assertEquals(
            VaultBiometricReadiness.Supported(bindingEnrolled = true),
            VaultBiometricSupport.readiness(hardwareCanPrompt = true, bindingEnrolled = true),
        )
    }
}
