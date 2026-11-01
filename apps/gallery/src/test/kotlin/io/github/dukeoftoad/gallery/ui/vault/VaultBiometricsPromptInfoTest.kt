package io.github.dukeoftoad.gallery.ui.vault

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the r10 device fix (build 13 crash): tapping "Unlock with biometrics"
 * crashed with `IllegalArgumentException: Negative text must be set and
 * non-empty.` (androidx.biometric `PromptInfo.Builder.build()`).
 *
 * `VaultBiometrics` itself is deliberately hardware-bound and untested
 * (ADR-0016), but the prompt *configuration* is pure androidx.biometric
 * Java --- no `Context`, no Keystore --- so the JVM suite can hold the
 * contract that keeps the biometric sheet from crashing: the vault's
 * builder ALWAYS sets a non-empty negative button alongside
 * `BIOMETRIC_WEAK`, because a CryptoObject-bound prompt cannot combine
 * with `DEVICE_CREDENTIAL`, and without the credential fallback the
 * negative text is the library-mandated cancel affordance.
 *
 * Honest JVM limitation: `build()` itself calls `android.text.TextUtils`,
 * which the unit-test stub does not implement ("Method isEmpty ... not
 * mocked") --- so the suite reflects the BUILDER's fields instead of
 * calling `build()`, and the library's runtime enforcement is verified on
 * the physical device by the r10 checklist section (the sheet must
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
    fun `vault prompt builder pins biometric-weak and the no-confirmation defaults`() {
        val builder = VaultBiometrics.promptInfoBuilder(
            title = "Unlock the hidden vault",
            subtitle = "Confirm it's you to open the vault",
            negativeText = "Use PIN",
        )
        assertEquals(
            BiometricManager.Authenticators.BIOMETRIC_WEAK,
            builder.field<Int>("mAllowedAuthenticators"),
        )
        assertFalse(builder.field<Boolean>("mIsConfirmationRequired"))
        assertEquals("Unlock the hidden vault", builder.field<CharSequence>("mTitle"))
        assertEquals("Confirm it's you to open the vault", builder.field<CharSequence>("mSubtitle"))
    }

    /** Reads [name] off the builder with the accessibility barrier lowered. */
    private inline fun <reified T : Any> BiometricPrompt.PromptInfo.Builder.field(name: String): T {
        val field = BiometricPrompt.PromptInfo.Builder::class.java.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(this) as T
    }
}
