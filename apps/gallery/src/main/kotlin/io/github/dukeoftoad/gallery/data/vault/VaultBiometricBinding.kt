package io.github.dukeoftoad.gallery.data.vault

import java.security.SecureRandom

/**
 * Envelope of the vault's optional biometric unlock binding (issue #57,
 * ADR-0019; extends ADR-0017's store ADDITIVELY).
 *
 * At enrollment the vault generates a random 32-byte SESSION SECRET, seals it
 * under the auth-bound AndroidKeyStore key (`toadgallery_vault_biometric`,
 * `setUserAuthenticationRequired(true)` --- see `AndroidVaultBiometricKeys`)
 * with AES-256-GCM, and persists exactly one new optional file in the store
 * root:
 * ```
 * biometric.binding = version(1) || IV(12) || ciphertext(32) || GCM tag(16)
 * ```
 * An absent file means "biometric unlock not enrolled" --- the pre-#57
 * PIN-only behavior, byte for byte. A present file is parsed STRICTLY like
 * [VaultIndex]: wrong version, wrong length, any malformation is a
 * [VaultBiometricBindingException], never a silent salvage.
 *
 * The sealed secret is produced/consumed by the thin Android
 * `ui/vault/VaultBiometrics` wrappers through a Keystore `Cipher` handed to
 * `BiometricPrompt.CryptoObject` --- the GCM seal authenticates the blob AND
 * the per-use user authentication authorizes the key operation in one step.
 * Successful decrypt of the 32-byte secret IS the unlock proof (issue #57
 * design: no PIN entry); the PIN path stays primary/fallback, untouched.
 *
 * PURE JVM on purpose (java.security + javax.crypto constants only) so the
 * format contract is pinned by JVM tests (ADR-0016); the device-only
 * BiometricPrompt flows are deliberately elsewhere.
 *
 * Privacy (ADR-0010/ADR-0014): the binding blob is on-device ciphertext under
 * a key that never leaves AndroidKeyStore; biometric data never leaves the
 * device (it is never even read by the app --- the prompt and the key live in
 * the OS/hardware), and the app holds no INTERNET permission.
 */
object VaultBiometricBinding {

    /** Envelope version fence; a future format change bumps this. */
    const val FORMAT_VERSION = 1

    /** The random per-vault session secret size (AES-256-sized). */
    const val SESSION_SECRET_BYTES = 32

    /** Full sealed-secret size: IV(12) || ciphertext(32) || GCM tag(16). */
    const val SEALED_SECRET_BYTES =
        VaultCrypto.IV_BYTES + SESSION_SECRET_BYTES + VaultCrypto.GCM_TAG_BITS / 8

    /** Thrown when the binding file does not parse as the v1 envelope. */
    class VaultBiometricBindingException(message: String) : Exception(message)

    private val random = SecureRandom()

    /** A fresh random 32-byte session secret for a new enrollment. */
    fun newSessionSecret(): ByteArray =
        ByteArray(SESSION_SECRET_BYTES).also { random.nextBytes(it) }

    /** True when [plain] has exactly the session-secret shape (32 bytes). */
    fun isSessionSecret(plain: ByteArray): Boolean =
        plain.size == SESSION_SECRET_BYTES

    /** True when [sealed] is a well-formed `IV(12) || ciphertext(32) || tag(16)`. */
    fun isSealedSecretWellFormed(sealed: ByteArray): Boolean =
        sealed.size == SEALED_SECRET_BYTES

    /**
     * Wraps [sealedSecret] (the biometric-key-sealed session secret) into the
     * persisted envelope: `version(1) || sealed`.
     */
    fun build(sealedSecret: ByteArray): ByteArray {
        require(isSealedSecretWellFormed(sealedSecret)) {
            "sealed secret must be $SEALED_SECRET_BYTES bytes (IV || ciphertext || tag)"
        }
        return byteArrayOf(FORMAT_VERSION.toByte()) + sealedSecret
    }

    /**
     * Parses the persisted envelope and returns the sealed secret. Strict:
     * an empty blob, a wrong version byte, or a wrong length fails with
     * [VaultBiometricBindingException] --- the caller must treat the binding
     * as rejected (and fall back to the PIN), never salvage.
     */
    fun parse(raw: ByteArray): ByteArray {
        if (raw.size != 1 + SEALED_SECRET_BYTES) {
            throw VaultBiometricBindingException(
                "biometric binding has ${raw.size} bytes, expected ${1 + SEALED_SECRET_BYTES}",
            )
        }
        if (raw[0].toInt() != FORMAT_VERSION) {
            throw VaultBiometricBindingException(
                "biometric binding has version ${raw[0]}, expected $FORMAT_VERSION",
            )
        }
        return raw.copyOfRange(1, raw.size)
    }
}

/**
 * The vault screen's biometric readiness state machine (issue #57, ADR-0019),
 * kept PURE (booleans in, state out) so the gating rules are JVM-testable:
 * the Android inputs --- `BiometricManager.canAuthenticate(BIOMETRIC_STRONG)`
 * and [VaultStore.hasBiometricBinding] --- are read by the ViewModel seam and
 * fed in here.
 *
 * [Unsupported] covers both "no usable biometric hardware" and "hardware
 * present but no biometrics enrolled on the device": either way the PIN is
 * the only path and the UI shows nothing biometric. [Unsupported] also
 * covers "biometric hardware present but only Class 2 (Weak)" --- a Class 2
 * biometric cannot authorize the vault's auth-per-use AndroidKeyStore key,
 * so the PIN is the only honest unlock path. [Supported] with
 * `bindingEnrolled` drives the two affordances: the unlock button (locked
 * screen) and the settings-style toggle (unlocked screen).
 */
sealed interface VaultBiometricReadiness {

    /** No usable biometric on this device: the PIN is the only unlock path. */
    data object Unsupported : VaultBiometricReadiness

    /** Biometrics usable; [bindingEnrolled] says whether this vault bound one. */
    data class Supported(val bindingEnrolled: Boolean) : VaultBiometricReadiness
}

/** The pure decision table behind [VaultBiometricReadiness]. */
object VaultBiometricSupport {

    /**
     * [hardwareCanPrompt] is `canAuthenticate(BIOMETRIC_STRONG) == SUCCESS`
     * (computed by the Android seam); [bindingEnrolled] is the store's
     * `biometric.binding` presence. Hardware wins: without a usable prompt
     * the enrollment flag is moot (a stale binding from before the user
     * removed all fingerprints must not surface UI).
     */
    fun readiness(hardwareCanPrompt: Boolean, bindingEnrolled: Boolean): VaultBiometricReadiness =
        if (hardwareCanPrompt) {
            VaultBiometricReadiness.Supported(bindingEnrolled = bindingEnrolled)
        } else {
            VaultBiometricReadiness.Unsupported
        }
}
