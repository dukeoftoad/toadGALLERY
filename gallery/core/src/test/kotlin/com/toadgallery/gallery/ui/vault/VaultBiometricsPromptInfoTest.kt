package com.toadgallery.gallery.ui.vault

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two device-round fixes to the vault's biometric prompt
 * configuration:
 *
 *  - r10 (build 13 crash): tapping "Unlock with biometrics" crashed with
 *    `IllegalArgumentException: Negative text must be set and non-empty.`
 *    (androidx.biometric `PromptInfo.Builder.build()`).
 *  - r11 (build 14 crash): the SAME tap then crashed one validation later
 *    with `IllegalArgumentException: Crypto-based authentication is not
 *    supported for Class 2 (Weak) biometrics.` from
 *    `BiometricPrompt.authenticate(PromptInfo, CryptoObject)` --- a
 *    CryptoObject-bound prompt must request `BIOMETRIC_STRONG`.
 *
 * `VaultBiometrics` itself is deliberately hardware-bound and untested
 * (ADR-0016), but the prompt *configuration* is pure androidx.biometric
 * Java --- no `Context`, no Keystore --- so the JVM suite can hold the
 * contract that keeps the biometric sheet from crashing: the vault's
 * builder ALWAYS sets a non-empty negative button alongside
 * `BIOMETRIC_STRONG`, because a crypto prompt allows no
 * `DEVICE_CREDENTIAL` fallback, and without the credential fallback the
 * negative text is the library-mandated cancel affordance.
 *
 * Honest JVM limitation: `build()` itself calls `android.text.TextUtils`,
 * which the unit-test stub does not implement ("Method isEmpty ... not
 * mocked") --- so the suite reflects the BUILDER's fields instead of
 * calling `build()`, and the library's runtime enforcement is verified on
 * the physical device by the device checklist sections (the sheet must
 * appear, not crash).
 */
class VaultBiometricsPromptInfoTest {

    @Test
    fun `vault prompt builder always sets the negative button text`() {
        val builder = VaultBiometrics.promptInfoBuilder(
            title = "Unlock the hidden vault",
            subtitle = "Confirm it's you to open the vault",
            negativeText = "Use PIN",
        )
        // PromptInfo$Builder's fields are the @RestrictTo-annotated API's
        // private storage; reading them via reflection keeps lint off the
        // library-private getters while asserting exactly what build()
        // will validate.
        assertEquals("Use PIN", builder.field<CharSequence>("mNegativeButtonText"))
    }

    @Test
    fun `vault prompt builder pins biometric-strong and the no-confirmation defaults`() {
        val builder = VaultBiometrics.promptInfoBuilder(
            title = "Unlock the hidden vault",
            subtitle = "Confirm it's you to open the vault",
            negativeText = "Use PIN",
        )
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_STRONG,
            builder.field<Int>("mAllowedAuthenticators"),
        )
        assertFalse(builder.field<Boolean>("mIsConfirmationRequired"))
        assertEquals("Unlock the hidden vault", builder.field<CharSequence>("mTitle"))
        assertEquals("Confirm it's you to open the vault", builder.field<CharSequence>("mSubtitle"))
    }

    @Test
    fun `vault crypto prompt never requests the weak biometric class`() {
        val builder = VaultBiometrics.promptInfoBuilder(
            title = "Unlock the hidden vault",
            subtitle = "Confirm it's you to open the vault",
            negativeText = "Use PIN",
        )
        // This is the exact expression androidx.biometric 1.1.0's
        // AuthenticatorUtils.isWeakBiometricAllowed evaluates inside
        // BiometricPrompt.authenticate(PromptInfo, CryptoObject) before
        // throwing the build-14 crash ("Crypto-based authentication is not
        // supported for Class 2 (Weak) biometrics."): the vault's
        // CryptoObject-bound prompt must never request a set that includes
        // the WEAK class, so the crash cannot recur.
        val allowed = builder.field<Int>("mAllowedAuthenticators")
        assertTrue(
            (allowed and BiometricManager.Authenticators.BIOMETRIC_WEAK) !=
                BiometricManager.Authenticators.BIOMETRIC_WEAK,
        )
    }

    /** Reads [name] off the builder with the accessibility barrier lowered. */
    private inline fun <reified T : Any> BiometricPrompt.PromptInfo.Builder.field(name: String): T {
        val field = BiometricPrompt.PromptInfo.Builder::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }
}
