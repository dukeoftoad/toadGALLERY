package io.github.dukeoftoad.gallery.ui.vault

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.vault.AndroidVaultBiometricKeys
import io.github.dukeoftoad.gallery.data.vault.VaultBiometricBinding
import io.github.dukeoftoad.gallery.data.vault.VaultCrypto
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * The device-only half of the vault's biometric unlock binding (issue #57,
 * ADR-0019): `androidx.biometric` [BiometricPrompt] over a
 * [BiometricPrompt.CryptoObject] wrapping a [Cipher] initialized with the
 * auth-bound AndroidKeyStore key ([AndroidVaultBiometricKeys]).
 *
 * Deliberately THIN and NOT unit-tested (the same seam split as
 * `AndroidVaultKeys`: everything here needs real Keystore hardware and a
 * live prompt, which the JVM suite never has --- ADR-0016). The flows are
 * the two #57 verbs:
 *
 *  - **[unlock]**: a `DECRYPT_MODE` cipher initialized with the stored
 *    blob's leading IV is authenticated by the prompt; on success the
 *    authorized cipher opens the sealed session secret
 *    (`IV(12) || ciphertext || tag`), and THAT plaintext --- validated to be
 *    32 bytes --- is the unlock proof. No PIN entry.
 *  - **[enroll]**: a fresh key ([AndroidVaultBiometricKeys.freshKey], which
 *    also self-heals a key invalidated by an enrollment change) and a fresh
 *    random session secret ([VaultBiometricBinding.newSessionSecret]); the
 *    authorized cipher seals it and the caller persists the sealed secret.
 *
 * Keystore cipher mechanics: `ENCRYPT_MODE` is initialized WITHOUT a caller
 * IV (AndroidKeyStore generates the 12-byte GCM IV itself --- the provider's
 * randomized-encryption requirement), captured from [Cipher.getIv] right
 * after init; `DECRYPT_MODE` takes the persisted IV back via
 * [GCMParameterSpec]. The auth handshake is the documented CryptoObject
 * pattern: the cipher passed to `authenticate()` carries the begun Keystore
 * operation, and `onAuthenticationSucceeded` hands back the authorized
 * cipher via [BiometricPrompt.AuthenticationResult.getCryptoObject].
 *
 * `MainActivity` is a [FragmentActivity] since issue #57 exactly because
 * the androidx [BiometricPrompt] port requires one.
 *
 * Privacy (ADR-0010/ADR-0014): the prompt is pure on-device authentication;
 * biometric data never enters the app (the OS compares, the Keystore
 * authorizes), the sealed secret never leaves AndroidKeyStore's cipher
 * pipeline, and the app holds no INTERNET permission anyway.
 */
object VaultBiometrics {

    /**
     * The GCM spelling [VaultCrypto] seals with (its own constant is private
     * by design; this wrapper spells the identical transformation).
     */
    private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

    /** Why a biometric round failed; drives the ViewModel's narration. */
    enum class Failure {
        /** The user cancelled (back, negative button, dismissed retries). */
        CANCELLED,

        /** The Keystore key is dead (biometric enrollment changed --- ADR-0019). */
        KEY_INVALIDATED,

        /** Anything else (cipher errors, tampered binding, mid-flow lockout). */
        ERROR,
    }

    /** Result of an [unlock] round: the opened secret, or a [Failure]. */
    sealed interface UnlockResult {
        /** The decrypted 32-byte session secret --- the unlock proof itself. */
        data class Authenticated(val secret: ByteArray) : UnlockResult

        data class Failed(val failure: Failure) : UnlockResult
    }

    /** Result of an [enroll] round: the sealed secret, or a [Failure]. */
    sealed interface EnrollResult {
        /** `IV(12) || ciphertext(32) || GCM tag(16)`, ready for the store. */
        data class Sealed(val sealedSecret: ByteArray) : EnrollResult

        data class Failed(val failure: Failure) : EnrollResult
    }

    /**
     * True when this device can prompt RIGHT NOW for a `BIOMETRIC_WEAK`
     * authenticator (hardware present AND at least one biometric enrolled).
     * `BIOMETRIC_WEAK` is the class a CryptoObject-bound key may authorize;
     * the vault accepts it --- the auth-bound key is the gate, not the
     * authenticator class (ADR-0019).
     */
    fun canPrompt(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Prompts for biometric authentication and, on success, opens the stored
     * sealed session secret with the authorized cipher. [sealedSecret] is
     * `IV(12) || ciphertext(32) || tag(16)` exactly as loaded from
     * `biometric.binding`. [onResult] fires on the main executor.
     */
    fun unlock(
        activity: FragmentActivity,
        sealedSecret: ByteArray,
        onResult: (UnlockResult) -> Unit,
    ) {
        val cipher = try {
            val iv = sealedSecret.copyOfRange(0, VaultCrypto.IV_BYTES)
            Cipher.getInstance(CIPHER_TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    AndroidVaultBiometricKeys.key(),
                    GCMParameterSpec(VaultCrypto.GCM_TAG_BITS, iv),
                )
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            onResult(UnlockResult.Failed(Failure.KEY_INVALIDATED))
            return
        } catch (t: Throwable) {
            onResult(UnlockResult.Failed(Failure.ERROR))
            return
        }
        prompt(
            activity = activity,
            cipher = cipher,
            onSucceeded = { authorized ->
                val plain = runCatching {
                    authorized.doFinal(
                        sealedSecret,
                        VaultCrypto.IV_BYTES,
                        sealedSecret.size - VaultCrypto.IV_BYTES,
                    )
                }.getOrNull()
                if (plain != null && VaultBiometricBinding.isSessionSecret(plain)) {
                    onResult(UnlockResult.Authenticated(plain))
                } else {
                    onResult(UnlockResult.Failed(Failure.ERROR))
                }
            },
            onFailed = { failure -> onResult(UnlockResult.Failed(failure)) },
        )
    }

    /**
     * Prompts for biometric authentication and, on success, seals a FRESH
     * random session secret under a FRESH key (each enrollment replaces the
     * whole binding --- including a key invalidated by an enrollment change,
     * the self-healing path of ADR-0019). [onResult] receives the sealed
     * secret ready for `VaultStore.saveBiometricBinding`.
     */
    fun enroll(
        activity: FragmentActivity,
        onResult: (EnrollResult) -> Unit,
    ) {
        val cipher: Cipher
        val iv: ByteArray
        try {
            cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            // No caller IV in encrypt mode: AndroidKeyStore generates it
            // (randomized-encryption requirement); capture it right after
            // init, before the prompt runs.
            cipher.init(Cipher.ENCRYPT_MODE, AndroidVaultBiometricKeys.freshKey())
            iv = cipher.iv
        } catch (t: Throwable) {
            onResult(EnrollResult.Failed(Failure.ERROR))
            return
        }
        val secret = VaultBiometricBinding.newSessionSecret()
        prompt(
            activity = activity,
            cipher = cipher,
            onSucceeded = { authorized ->
                val sealed = runCatching { iv + authorized.doFinal(secret) }.getOrNull()
                if (sealed != null && VaultBiometricBinding.isSealedSecretWellFormed(sealed)) {
                    onResult(EnrollResult.Sealed(sealed))
                } else {
                    onResult(EnrollResult.Failed(Failure.ERROR))
                }
            },
            onFailed = { failure -> onResult(EnrollResult.Failed(failure)) },
        )
    }

    // ------------------------------------------------------------------
    // The prompt plumbing (shared by both verbs)
    // ------------------------------------------------------------------

    /** Runs one CryptoObject-authenticated prompt; maps outcomes to the two lambdas. */
    private fun prompt(
        activity: FragmentActivity,
        cipher: Cipher,
        onSucceeded: (Cipher) -> Unit,
        onFailed: (Failure) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // The prompt authorized the BEGUN Keystore operation wrapped
                // in the CryptoObject; this cipher alone may now finish it.
                val authorized = result.cryptoObject?.cipher
                if (authorized != null) onSucceeded(authorized) else onFailed(Failure.ERROR)
            }

            override fun onAuthenticationError(code: Int, errString: CharSequence) {
                onFailed(
                    when (code) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        -> Failure.CANCELLED

                        else -> Failure.ERROR
                    },
                )
            }

            // A single bad touch: the prompt keeps running, nothing to report.
            override fun onAuthenticationFailed() = Unit
        }
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            callback,
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.vault_biometric_prompt_title))
                .setSubtitle(activity.getString(R.string.vault_biometric_prompt_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .setConfirmationRequired(false)
                .build(),
            BiometricPrompt.CryptoObject(cipher),
        )
    }
}
