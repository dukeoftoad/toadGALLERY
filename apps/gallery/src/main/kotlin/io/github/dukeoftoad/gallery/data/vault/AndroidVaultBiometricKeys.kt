package io.github.dukeoftoad.gallery.data.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The vault's SECOND AndroidKeyStore key (issue #57, ADR-0019): an AES-256-GCM
 * key that is **user-authentication-bound** (`setUserAuthenticationRequired(true)`,
 * per-use auth --- every seal/open rides a fresh `BiometricPrompt`
 * confirmation), used exclusively to seal the per-vault random session secret
 * stored in `biometric.binding` (see [VaultBiometricBinding]).
 *
 * This mirrors [AndroidVaultKeys] --- the deliberately thin, NOT unit-tested
 * Android seam (Robolectric has no Keystore implementation; ADR-0016 keeps
 * the JVM suite emulator-free) --- with two differences by design:
 *
 *  - **Auth binding**: `setUserAuthenticationRequired(true)` with NO
 *    validity-duration override, so the key demands user authentication for
 *    EVERY use. That is the desired #57 semantics: each biometric unlock
 *    prompts; the session secret cannot be sealed or opened silently.
 *    Enrollment invalidation stays ON (default), so adding/removing a device
 *    fingerprint/face permanently invalidates the key --- the binding then
 *    honestly breaks and the UI falls back to the PIN with a re-enroll nudge
 *    (ADR-0019 consequence).
 *  - **[freshKey]**: every enrollment re-mints the key (delete-then-generate).
 *    Each enrollment seals a brand-new session secret and replaces the whole
 *    binding file, so discarding the old key is free and self-heals the
 *    invalidated-key state without a separate migration path.
 *
 * StrongBox is attempted on API 28+ with fallback to the regular TEE-backed
 * Keystore exactly like [AndroidVaultKeys] (`setIsStrongBoxBacked(true)` →
 * [StrongBoxUnavailableException] → TEE); both spellings satisfy ADR-0017/0019.
 *
 * Privacy (ADR-0014): the key material never leaves the Keystore hardware;
 * the app holds no INTERNET permission, so neither key nor ciphertext can be
 * sent anywhere. Biometric data never enters the app at all --- the prompt and
 * the auth token handshake are OS/hardware-internal.
 */
object AndroidVaultBiometricKeys {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /**
     * Keystore alias of the biometric binding key. Distinct from
     * `AndroidVaultKeys`' media-key alias on purpose: different lifetimes
     * (the media key must never be invalidated; this one is invalidated by
     * biometric enrollment changes and re-minted at every re-enrollment).
     */
    private const val ALIAS = "toadgallery_vault_biometric"

    /** Serializes first access; see [AndroidVaultKeys]'s concurrency note. */
    private val lock = Any()

    /**
     * Gets-or-generates the auth-bound key. A key invalidated by a biometric
     * enrollment change is still RETURNED here (it loads fine; it only fails
     * when used) --- callers catch the use-time failure and narrate the
     * ADR-0019 fallback. Enrollment paths use [freshKey] instead.
     */
    fun key(): SecretKey = synchronized(lock) {
        loadExistingKey()?.let { return it }
        generate()
    }

    /**
     * Deletes any existing key under the alias and generates a fresh one ---
     * the enrollment path: each enrollment seals a NEW session secret and
     * replaces the binding file, so the old key (possibly invalidated by a
     * biometric enrollment change) is simply discarded.
     */
    fun freshKey(): SecretKey = synchronized(lock) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
        generate()
    }

    /** TEE-backed generation with a StrongBox attempt on P+ (the lint-visible guard). */
    private fun generate(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateStrongBoxKey()
            } catch (e: StrongBoxUnavailableException) {
                // No StrongBox secure element on this device; the regular
                // Keystore is the ADR-0017/0019-approved home for the key.
            }
        }
        return generateKeystoreKey()
    }

    private fun loadExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** Regular (TEE-backed) AndroidKeyStore generation; valid from minSdk 26. */
    private fun generateKeystoreKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(baseSpecBuilder().build())
        return generator.generateKey()
    }

    /** StrongBox-backed generation; API 28+ only (the lint-visible guard). */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun generateStrongBoxKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(baseSpecBuilder().setIsStrongBoxBacked(true).build())
        return generator.generateKey()
    }

    private fun baseSpecBuilder(): KeyGenParameterSpec.Builder =
        KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(VaultCrypto.KEY_BITS)
            // THE #57 requirement: every use of this key demands a fresh user
            // authentication (BiometricPrompt). No validity-duration override
            // --- per-use auth is the desired semantics, so each biometric
            // unlock prompts. Invalidation on biometric enrollment change
            // stays at its default (on), per ADR-0019.
            .setUserAuthenticationRequired(true)
}
