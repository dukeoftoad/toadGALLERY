package io.github.dukeoftoad.gallery.data.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.annotation.RequiresApi
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import java.security.GeneralSecurityException
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
 * Handle caching (the binder-IPC round): [key] used to run a full
 * AndroidKeyStore load+getEntry on every call; the handle is now cached
 * after the first successful load (an AndroidKeyStore `SecretKey` handle
 * stays valid for the process lifetime). [freshKey] refreshes the cache
 * with the key it mints; [invalidateCache] is the use-time-failure hook.
 * Honest limits, stated up front: a key invalidated by a biometric
 * enrollment change still LOADS (see [key]'s KDoc), so a re-load serves
 * the same dead handle --- the hook covers transient handle staleness,
 * while enrollment changes keep needing [freshKey]'s re-mint.
 *
 * StrongBox is attempted on API 28+ with fallback to the regular TEE-backed
 * Keystore exactly like [AndroidVaultKeys] (`setIsStrongBoxBacked(true)` →
 * [StrongBoxUnavailableException] → TEE) --- including the sibling's BROAD
 * fallback (D-5 parity): some OEM/custom-ROM keystores abort StrongBox
 * generation with a plain `ProviderException` or `KeyStoreException`
 * instead of the documented exception, which used to abort biometric
 * enrollment raw on exactly those devices. The StrongBox leg therefore
 * falls back to the TEE on ANY [GeneralSecurityException] or
 * [RuntimeException] too (WARN-logged with the throwable; the expected
 * StrongBox-unavailable case stays INFO). Only the TEE generation path
 * itself still propagates failures. Both spellings satisfy ADR-0017/0019.
 *
 * Privacy (ADR-0014): the key material never leaves the Keystore hardware;
 * the app holds no INTERNET permission, so neither key nor ciphertext can be
 * sent anywhere. Biometric data never enters the app at all --- the prompt and
 * the auth token handshake are OS/hardware-internal.
 */
object AndroidVaultBiometricKeys {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Diagnostics tag shared by the vault's Android seams (W1; [AndroidVaultKeys]'s tag). */
    private const val TAG = "ToadVault"

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
     * The cached handle (guarded by [lock]): served for the process
     * lifetime after the first successful load --- re-fetching it instead
     * costs a full keystore load+getEntry (2-3 binder IPCs) per
     * prompt-bound operation. [freshKey] replaces it; [invalidateCache]
     * clears it.
     */
    private var cachedKey: SecretKey? = null

    /**
     * Gets-or-generates the auth-bound key. A key invalidated by a biometric
     * enrollment change is still RETURNED here (it loads fine; it only fails
     * when used) --- callers catch the use-time failure, narrate the
     * ADR-0019 fallback, and call [invalidateCache] to drop the cached
     * handle (the re-load serves the same dead key; only re-enrollment via
     * [freshKey] mints a usable one). Enrollment paths use [freshKey]
     * instead.
     */
    fun key(): SecretKey = synchronized(lock) {
        cachedKey?.let { return it }
        loadExistingKey()?.let { existing ->
            cachedKey = existing
            return existing
        }
        generate().also { cachedKey = it }
    }

    /**
     * Deletes any existing key under the alias and generates a fresh one ---
     * the enrollment path: each enrollment seals a NEW session secret and
     * replaces the binding file, so the old key (possibly invalidated by a
     * biometric enrollment change) is simply discarded. The cache follows
     * the mint: a later [key] must never serve the deleted old handle.
     */
    fun freshKey(): SecretKey = synchronized(lock) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
        generate().also { cachedKey = it }
    }

    /**
     * The use-time-failure hook: clears [cachedKey] so the next [key]
     * re-loads the handle from the keystore ONCE. See the class KDoc's
     * caching note for the honest limit: an enrollment-invalidated key
     * still loads, so the re-load helps only transient handle staleness
     * (keystore-side hiccups) --- the ADR-0019 invalidation path is
     * [freshKey], which callers reach through re-enrollment. No caller in
     * this round invokes it: the use-time failure sites live in the
     * prompt wrapper (`ui/vault/VaultBiometrics`), outside this data-layer
     * scope; the hook is the documented seam for that wiring.
     */
    fun invalidateCache() {
        synchronized(lock) { cachedKey = null }
    }

    /** TEE-backed generation with a StrongBox attempt on P+ (the lint-visible guard). */
    private fun generate(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateStrongBoxKey()
            } catch (e: StrongBoxUnavailableException) {
                // No StrongBox secure element on this device; the regular
                // Keystore is the ADR-0017/0019-approved home for the key.
                DiagLog.i(TAG, "biometric key: StrongBox unavailable -> TEE keystore")
            } catch (e: GeneralSecurityException) {
                // D-5 parity with AndroidVaultKeys: some OEM/custom-ROM
                // keystores abort StrongBox generation with a plain
                // KeyStoreException instead of the documented exception
                // above --- fall back exactly like the expected case, but
                // WARN with the throwable (unexpected).
                DiagLog.w(
                    TAG,
                    "biometric key: StrongBox generation failed (${e.javaClass.name}) -> TEE keystore",
                    e,
                )
            } catch (e: RuntimeException) {
                // ... and some with a plain ProviderException. StrongBox is
                // defense in depth, never a requirement (ADR-0017/0019).
                DiagLog.w(
                    TAG,
                    "biometric key: StrongBox generation failed (${e.javaClass.name}) -> TEE keystore",
                    e,
                )
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
