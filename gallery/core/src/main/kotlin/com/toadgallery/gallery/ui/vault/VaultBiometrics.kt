package com.toadgallery.gallery.ui.vault

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.vault.AndroidVaultBiometricKeys
import com.toadgallery.gallery.data.vault.VaultBiometricBinding
import com.toadgallery.gallery.data.vault.VaultCrypto
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
 *
 * r10 device fix (build 13 crash): `PromptInfo` built with
 * `BIOMETRIC_WEAK` and no negative button text throws
 * `IllegalArgumentException("Negative text must be set and non-empty.")`
 * from `PromptInfo.Builder.build()` the moment the biometric sheet is
 * shown. The negative button is mandatory here because the prompt allows
 * no `DEVICE_CREDENTIAL` fallback (a crypto prompt is biometric-only ---
 * the Keystore handshake only authorizes a biometric round), and
 * androidx.biometric requires non-empty negative text whenever device
 * credential is not allowed. The construction is isolated in
 * [promptInfoBuilder] and pinned by `VaultBiometricsPromptInfoTest` on the
 * JVM (the rest of this object stays hardware-bound and untested ---
 * ADR-0016). One JVM limitation, stated honestly: `PromptInfo.Builder.build()`
 * itself calls `android.text.TextUtils`, which the unit-test stub does not
 * implement --- so the suite pins the BUILDER state (negative text set,
 * BIOMETRIC_STRONG, no confirmation) via reflection and leaves the
 * library's own `build()` enforcement to the device checklist.
 *
 * r11 device fix (build 14 crash): the SAME tap crashed one validation
 * later with `IllegalArgumentException("Crypto-based authentication is not
 * supported for Class 2 (Weak) biometrics.")` from
 * `BiometricPrompt.authenticate(PromptInfo, CryptoObject)`: the prompt
 * requested `BIOMETRIC_WEAK` while binding a CryptoObject, a combination
 * androidx.biometric 1.1.0 rejects unconditionally (the check tests the
 * requested authenticator flags, not the device's sensor class --- the
 * error's "Class 2" wording names the requested class, not the test
 * device's sensor; the path crashed on every device, not only
 * Class-2-sensor devices). A CryptoObject-bound prompt must request
 * `BIOMETRIC_STRONG` --- the library's own default for crypto sessions
 * when no authenticators are set is exactly that. The fix switches the
 * builder and the [canPrompt] probe to STRONG, so devices without a
 * Class 3 sensor/enrollment honestly offer PIN-only via
 * [VaultBiometricSupport]'s `Unsupported` state.
 *
 * r19a wiring --- the handle-cache invalidation seam: the r19 vault slice
 * added [AndroidVaultBiometricKeys.invalidateCache] as a documented-but-
 * UNWIRED hook ("the use-time failure sites live in the prompt wrapper" ---
 * this file). This slice wires it at exactly those sites, the catch arms
 * around the cipher-init that USES [AndroidVaultBiometricKeys.key] in
 * [unlock]: a permanently-invalidated key (enrollment change, the
 * [Failure.KEY_INVALIDATED] narration the ViewModel turns into the
 * ADR-0019 PIN fallback) and the residual use-time failures
 * (`UserNotAuthenticated` and keystore-side hiccups riding the generic
 * arm). The call order follows the keys' KDoc: narrate first, then drop
 * the cached handle, so the next operation re-loads it from the keystore
 * once instead of serving a dead handle for the rest of the process (the
 * re-load serves the same dead key after an enrollment change --- only
 * re-enrollment's [AndroidVaultBiometricKeys.freshKey] mints a usable
 * one, which is the ADR-0019 path). [AndroidVaultKeys]' twin hook stays
 * unwired here, honestly: the MEDIA key never flows through this wrapper
 * (its crypto runs inside the vault store), and it carries no auth
 * binding, so no use-time failure of it can surface in this file.
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
     * True when this device can prompt RIGHT NOW for a `BIOMETRIC_STRONG`
     * (Class 3) authenticator --- hardware present AND at least one Class 3
     * biometric enrolled. The vault's CryptoObject handshake can only be
     * authorized by a Class 3 biometric (androidx.biometric enforces it, and
     * only Class 3 can authorize an auth-per-use AndroidKeyStore key
     * operation), so this is the honest probe (ADR-0019). On devices whose
     * sensor/enrollment is only Class 2 (Weak), this returns false and the
     * vault's readiness machine ([VaultBiometricSupport]) lands in
     * `Unsupported` --- PIN-only, no biometric UI, the truthful degradation
     * (a Class 2 biometric cannot gate this key at all).
     */
    fun canPrompt(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
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
            // r19a: the seam the keys' KDoc documents. Narrate first, then
            // drop the cached handle (the KDoc-named order) --- the next
            // operation re-loads it from the keystore once. The re-loaded
            // key is the same dead one (it still loads; only re-enrollment
            // mints a usable key), so this is hygiene for the transient
            // case, and the KEY_INVALIDATED narration above is what drives
            // the honest ADR-0019 re-enroll fallback.
            AndroidVaultBiometricKeys.invalidateCache()
            return
        } catch (t: Throwable) {
            onResult(UnlockResult.Failed(Failure.ERROR))
            // r19a: the generic use-time arm (UserNotAuthenticated and
            // keystore-side hiccups land here). Dropping the cache is the
            // documented "one honest way to force the re-load" for a handle
            // that just failed in use; the worst cost when the failure was
            // actually the blob's shape (a tampered sealed secret) is one
            // extra keystore load on the next operation.
            AndroidVaultBiometricKeys.invalidateCache()
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
            buildPromptInfo(
                title = activity.getString(R.string.vault_biometric_prompt_title),
                subtitle = activity.getString(R.string.vault_biometric_prompt_subtitle),
                negativeText = activity.getString(R.string.vault_biometric_prompt_negative),
            ),
            BiometricPrompt.CryptoObject(cipher),
        )
    }

    /**
     * The vault's [BiometricPrompt.PromptInfo] [BiometricPrompt.PromptInfo.Builder],
     * configured but NOT built --- the seam the JVM suite pins (see the r10
     * and r11 notes on the object): the CryptoObject-bound prompt MUST
     * request `BIOMETRIC_STRONG` --- androidx.biometric 1.1.0's
     * `authenticate(PromptInfo, CryptoObject)` rejects any
     * allowed-authenticator set that includes `BIOMETRIC_WEAK` (the
     * build-14 crash), because crypto-bound Keystore auth requires a
     * Class 3 (Strong) biometric; the check tests the requested flags, not
     * the device's sensor class, so WEAK+crypto threw on every device.
     * STRONG alone still means no `DEVICE_CREDENTIAL` fallback, so the
     * builder ALWAYS sets non-empty negative text. Parameters are plain
     * `CharSequence`s --- no `Context` --- so the test classpath needs no
     * Android framework.
     */
    internal fun promptInfoBuilder(
        title: CharSequence,
        subtitle: CharSequence,
        negativeText: CharSequence,
    ): BiometricPrompt.PromptInfo.Builder =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setConfirmationRequired(false)

    /** The built prompt the two verbs authenticate with; see [promptInfoBuilder]. */
    internal fun buildPromptInfo(
        title: CharSequence,
        subtitle: CharSequence,
        negativeText: CharSequence,
    ): BiometricPrompt.PromptInfo = promptInfoBuilder(title, subtitle, negativeText).build()
}
