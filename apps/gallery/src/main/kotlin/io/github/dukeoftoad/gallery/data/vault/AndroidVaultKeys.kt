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
 *    back to the regular TEE-backed Keystore. Both spellings satisfy
 *    ADR-0017 ("inside AndroidKeyStore"); StrongBox is defense in depth,
 *    not a requirement.
 *  - **Concurrency**: generation is [synchronized] with a re-check, so two
 *    racing first-unlocks cannot mint two different keys under one alias
 *    (the loser would silently orphan every blob the winner seals).
 *
 * Privacy (ADR-0014): the key material never leaves the Keystore hardware;
 * the app holds no INTERNET permission, so neither key nor ciphertext can
 * be sent anywhere.
 */
object AndroidVaultKeys : VaultCrypto.VaultKeyProvider {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** Keystore alias of the vault media key (v1; a rotation would bump it). */
    private const val ALIAS = "toadgallery.vault.media.v1"

    /** Serializes first access; see the class KDoc's concurrency note. */
    private val lock = Any()

    override fun key(): SecretKey = synchronized(lock) {
        loadExistingKey()?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generateStrongBoxKey()
            } catch (e: StrongBoxUnavailableException) {
                // No StrongBox secure element on this device; the regular
                // Keystore is the ADR-0017-approved home for the key.
            }
        }
        generateKeystoreKey()
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
}
