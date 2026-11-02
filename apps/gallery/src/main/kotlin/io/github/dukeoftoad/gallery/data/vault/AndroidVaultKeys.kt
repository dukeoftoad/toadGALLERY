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
 * Production [VaultCrypto.VaultKeyProvider] for the hidden vault (issue #32,
 * ADR-0017): gets-or-generates the vault's AES-256-GCM key inside
 * AndroidKeyStore, non-exportable by construction.
 *
 * This is the deliberately thin, NOT unit-tested Android seam of the vault
 * (the JVM-tested logic lives in [VaultCrypto]/[VaultStore], which receive
 * the key through the provider interface): everything here is AndroidKeyStore
 * plumbing with no branching logic beyond the StrongBox fallback, and it can
 * only be exercised on real hardware (Robolectric has no Keystore
 * implementation; ADR-0016 keeps the JVM suite emulator-free by design).
 *
 *  - **Alias** [ALIAS]: one stable key per install. It survives app updates
 *    (Keystore keys are not removed on update) and dies with the install or
 *    a factory reset --- which is exactly the documented ADR-0017 trade-off:
 *    no cloud, no recovery; losing the key makes the vault permanently
 *    unopenable.
 *  - **StrongBox**: attempted on API 28+ (`setIsStrongBoxBacked(true)`); a
 *    device without a StrongBox chip throws
 *    [StrongBoxUnavailableException] at generation time and the code falls
 *    back to the regular TEE-backed Keystore. The fallback is deliberately
 *    BROADER than that documented exception (W1 hardening): some OEM and
 *    custom-ROM keystores abort StrongBox generation with a plain
 *    `ProviderException` or `KeyStoreException` instead, which used to
 *    abort vault setup outright --- so the StrongBox leg now falls back to
 *    the TEE on ANY [GeneralSecurityException] or [RuntimeException] too
 *    (WARN-logged with the throwable; the expected StrongBox-unavailable
 *    case stays INFO). Only the TEE generation path itself still propagates
 *    failures: a keystore that cannot mint a regular key is genuinely
 *    fatal. Both spellings satisfy ADR-0017 ("inside AndroidKeyStore");
 *    StrongBox is defense in depth, not a requirement.
 *  - **Concurrency**: generation is [synchronized] with a re-check, so two
 *    racing first-unlocks cannot mint two different keys under one alias
 *    (the loser would silently orphan every blob the winner seals).
 *  - **Handle cache**: [key] used to run a full AndroidKeyStore
 *    load+getEntry (2-3 binder IPCs) for EVERY crypto operation the store
 *    makes; the handle is now cached after the first successful load ---
 *    an AndroidKeyStore `SecretKey` handle stays valid for the process
 *    lifetime (a fresh install or factory reset replaces the key, and
 *    both kill this process with it). [probeExistingKey] reads the same
 *    cache; [invalidateCache] is the use-time-failure hook.
 *  - **Diagnostics** (W1): every [key]/[probeExistingKey] outcome lands in
 *    [DiagLog] under the shared "ToadVault" tag --- outcome class and
 *    duration only, never key material.
 *
 * Privacy (ADR-0014): the key material never leaves the Keystore hardware;
 * the app holds no INTERNET permission, so neither key nor ciphertext can
 * be sent anywhere.
 */
object AndroidVaultKeys : VaultCrypto.VaultKeyProvider {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Diagnostics tag shared by the vault's Android seams (W1). */
    private const val TAG = "ToadVault"

    /** Keystore alias of the vault media key (v1; a rotation would bump it). */
    private const val ALIAS = "toadgallery.vault.media.v1"

    /** Serializes first access; see the class KDoc's concurrency note. */
    private val lock = Any()

    /** Nanoseconds per millisecond; [elapsedMs]'s divisor. */
    private const val NANOS_PER_MS = 1_000_000L

    /**
     * Whether this process has logged the key-reuse line yet (W1). [key]
     * runs for EVERY crypto operation the store makes, so an unconditional
     * line per call would flood the CAP=400 ring with repeats and evict the
     * actual diagnostic story; the first reuse carries the whole signal.
     * Guarded by [lock].
     */
    private var loggedReuse = false

    /**
     * The cached AndroidKeyStore handle (guarded by [lock]): populated on
     * the first successful load/generation and served for the rest of the
     * process lifetime. Re-fetching it instead costs a full keystore
     * load+getEntry (2-3 binder IPCs) per vault operation. [invalidateCache]
     * clears it; nothing else ever replaces it (aliases are one per
     * install).
     */
    private var cachedKey: SecretKey? = null

    override fun key(): SecretKey = synchronized(lock) {
        cachedKey?.let { cached ->
            if (!loggedReuse) {
                DiagLog.i(TAG, "key: existing keystore key reused")
                loggedReuse = true
            }
            return cached
        }
        val existing = loadExistingKey()
        if (existing != null) {
            if (!loggedReuse) {
                DiagLog.i(TAG, "key: existing keystore key reused")
                loggedReuse = true
            }
            cachedKey = existing
            return existing
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val startedAt = System.nanoTime()
                val key = generateStrongBoxKey()
                DiagLog.i(TAG, "key: generated (StrongBox) in ${elapsedMs(startedAt)} ms")
                cachedKey = key
                return key
            } catch (e: StrongBoxUnavailableException) {
                // No StrongBox secure element on this device (the common
                // case); the regular Keystore is the ADR-0017-approved home
                // for the key.
                DiagLog.i(TAG, "key: StrongBox unavailable -> TEE keystore")
            } catch (e: GeneralSecurityException) {
                // W1 hardening: some OEM/custom-ROM keystores abort StrongBox
                // generation with a plain KeyStoreException instead of the
                // documented exception above --- fall back exactly like the
                // expected case, but WARN with the throwable (unexpected).
                DiagLog.w(
                    TAG,
                    "key: StrongBox generation failed (${e.javaClass.name}) -> TEE keystore",
                    e,
                )
            } catch (e: RuntimeException) {
                // ... and some with a plain ProviderException. StrongBox is
                // defense in depth, never a requirement (ADR-0017).
                DiagLog.w(
                    TAG,
                    "key: StrongBox generation failed (${e.javaClass.name}) -> TEE keystore",
                    e,
                )
            }
        }
        // The TEE path keeps propagating failures: a keystore that cannot
        // mint a regular key is genuinely fatal, not a fallback condition.
        val startedAt = System.nanoTime()
        val key = generateKeystoreKey()
        DiagLog.i(TAG, "key: generated (TEE) in ${elapsedMs(startedAt)} ms")
        cachedKey = key
        key
    }

    /**
     * The use-time-failure hook: clears [cachedKey] so the next [key]
     * re-loads the handle from the keystore ONCE instead of serving the
     * cached one for the rest of the process. Handles are valid for the
     * process lifetime, so this is belt-and-braces for the rare case where
     * a keystore-side hiccup surfaces as a use-time failure in the caller
     * (the media key carries NO auth binding, so it is never *invalidated*;
     * the hook exists so a future caller that catches such a failure has
     * one honest way to force the re-load). No caller in this round invokes
     * it --- the failure sites live outside the vault's data layer; the
     * hook is the documented seam.
     */
    fun invalidateCache() {
        synchronized(lock) { cachedKey = null }
    }

    /**
     * Load-only keystore probe for the About > Diagnostics screen (W1):
     * true when the vault's media key exists, false when it does not or
     * when the keystore itself cannot be read (WARN-logged --- a probe
     * failure must never crash the About screen). NEVER generates; the
     * first real crypto operation through [key] owns that decision.
     * Consults [cachedKey] first (a cached handle IS existence proof) and
     * populates the cache when the load succeeds, so the probe costs at
     * most one keystore round-trip per process.
     */
    fun probeExistingKey(): Boolean = try {
        synchronized(lock) {
            if (cachedKey != null) {
                return@synchronized true
            }
            val existing = loadExistingKey()
            if (existing != null) {
                cachedKey = existing
            }
            existing != null
        }
    } catch (e: Exception) {
        DiagLog.w(TAG, "probe: keystore probe failed", e)
        false
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

    /** Elapsed whole milliseconds since [startedAtNanos] ([System.nanoTime] base). */
    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / NANOS_PER_MS

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
}
