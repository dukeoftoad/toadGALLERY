package io.github.dukeoftoad.gallery.ui.vault

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins the r10 device fix (build 13 crash): tapping "Unlock with biometrics"
 * crashed with `IllegalArgumentException: Negative text must be set and
 * non-empty.` thrown from `PromptInfo.Builder.build()`.
 *
 * `VaultBiometrics` itself is deliberately hardware-bound and untested
 * (ADR-0016), but the prompt *construction* is pure androidx.biometric
 * Java --- no `Context`, no Keystore --- so the JVM suite can hold the
 * contract that keeps the biometric sheet from crashing:
 *
 *  1. a `BIOMETRIC_WEAK` PromptInfo WITHOUT negative text cannot be built
 *     (the library rejects it --- this is the crash);
 *  2. the vault's [VaultBiometrics.buildPromptInfo] always sets one.
 *
 * Why the negative button is unavoidable: the vault prompt authenticates a
 * `CryptoObject`-wrapped Keystore cipher, which cannot combine
 * `BIOMETRIC_WEAK` with `DEVICE_CREDENTIAL`, so there is no credential
 * fallback that would exempt us from the negative-text requirement.
 */
class VaultBiometricsPromptInfoTest {

    @Test
    fun `biometric-weak prompt info without negative text is rejected by the library`() {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock the hidden vault")
            .setSubtitle("Confirm it's you to open the vault")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setConfirmationRequired(false)
        try {
            builder.build()
            fail("androidx.biometric must reject a non-credential prompt without negative text")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "unexpected rejection reason: ${expected.message}",
                expected.message!!.contains("Negative text"),
            )
        }
    }

    @Test
    fun `vault prompt info builds and carries the negative text`() {
        val info = VaultBiometrics.buildPromptInfo(
            title = "Unlock the hidden vault",
            subtitle = "Confirm it's you to open the vault",
            negativeText = "Use PIN",
        )
        // The getter is @RestrictTo-annotated; read the backing field so
        // lint cannot flag the test for using a library-private API.
        val field = BiometricPrompt.PromptInfo::class.java.getDeclaredField("mNegativeButtonText")
        field.isAccessible = true
        assertEquals("Use PIN", field.get(info))
    }
}
