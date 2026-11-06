package io.github.dukeoftoad.gallery.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-order tripwires for the vault's DURABILITY contracts (the R51-2a
 * round: findings D-3, D-5 and D-9 of the r50 backend audit, §5 track D).
 *
 * ## Why tripwires, honestly
 *
 * Two of the three contracts have NO JVM-observable seam, so this suite
 * pins them the way `VaultExclusionReadGateTest` and `EgressSolePathTest`
 * pin their own un-JVM-testable contracts --- on the production source
 * itself, by anchor position:
 *
 *  - **D-3** (blob fsync ordering): [VaultStore] constructs its
 *    `FileOutputStream` internally (the only seams are the root directory
 *    and the [VaultCrypto.VaultKeyProvider] key), and `fd.sync()` is a
 *    syscall whose effect --- data blocks forced to storage before the
 *    fsynced index commit references the blob --- only exists under power
 *    loss; no JVM assertion can see it. The tripwire pins the ordering
 *    that makes the contract: every blob seal is followed by
 *    `output.fd.sync()` (inside the seal's own `.use` block, where alone
 *    the `output` name compiles) BEFORE the `atomicWrite` index commit of
 *    the same seal cycle.
 *  - **D-5** (biometric keygen fallback parity):
 *    [AndroidVaultBiometricKeys] is the documented device-only Android
 *    seam (`android.os.Build` + AndroidKeyStore; Robolectric has no
 *    Keystore implementation and ADR-0016 keeps this suite emulator-free),
 *    so its catch ladder cannot be exercised here at all. The tripwire
 *    pins that `generate()` mirrors [AndroidVaultKeys]'s broad OEM
 *    StrongBox→TEE fallback: the same three-rung catch ladder in the same
 *    order, WARN-logged with the throwable, falling through to the plain
 *    TEE generation.
 *
 * D-9 (atomicWrite's fail-loud rename contract) IS behaviorally pinned ---
 * an unreplaceable target is simulatable on the JVM through the
 * saveBiometricBinding seam --- in `VaultStoreBindingTest`; the cheap
 * source guard here keeps the delete-then-rename fallback from sneaking
 * back through ANY of atomicWrite's callers.
 */
class VaultDurabilityContractTest {

    /**
     * D-3: every blob seal site ([VaultStore.store]'s and
     * [VaultStore.storeBatch]'s; a future shared helper counts too) must
     * fsync the blob BEFORE the fsynced index commit of the same seal
     * cycle references it. The pre-fix shape --- blob bytes only
     * close()-flushed, then a durable index pointing at them --- left a
     * power-loss window where the item permanently failed GCM
     * authentication while a move had already deleted the original
     * MediaStore row.
     */
    @Test
    fun `every blob seal is fsynced before the index commit that references it`() {
        val source = moduleFile(VAULT_STORE_SRC).readText()
        val sealAnchors = Regex("""VaultCrypto\.encryptTo\(counting, output""")
            .findAll(source)
            .map { it.range.first }
            .toList()
        assertTrue(
            "VaultStore must have at least one blob seal site to pin (D-3 anchor missing)",
            sealAnchors.isNotEmpty(),
        )
        sealAnchors.forEach { sealAt ->
            val syncAt = source.indexOf(BLOB_FSYNC_ANCHOR, sealAt)
            val commitAt = source.indexOf(INDEX_COMMIT_ANCHOR, sealAt)
            assertTrue(
                "every blob seal must be followed by $BLOB_FSYNC_ANCHOR (D-3: the blob " +
                    "is fsynced inside its own .use block, before the stream closes)",
                syncAt >= 0,
            )
            assertTrue(
                "every blob seal must be followed by $INDEX_COMMIT_ANCHOR (D-3 anchor missing)",
                commitAt >= 0,
            )
            assertTrue(
                "the blob must be durable BEFORE the fsynced index commit references it " +
                    "(the D-3 crash-ordering contract): found the commit at $commitAt " +
                    "ahead of the sync at $syncAt",
                syncAt < commitAt,
            )
        }
    }

    /**
     * D-9 source guard (the behavioral pin is `VaultStoreBindingTest`'s):
     * `atomicWrite` must NEVER delete the current target to force a
     * replace. The old fallback's `target.delete()` --- deleted index, then
     * a retry that could itself fail --- opened a crash window with no
     * `index.bin` at all; the audit's fix is to fail loudly on the first
     * rename and let the fsynced `.tmp` survive.
     */
    @Test
    fun `atomicWrite never deletes the target to force a replace`() {
        val source = moduleFile(VAULT_STORE_SRC).readText()
        assertFalse(
            "VaultStore must keep the D-9 fail-loud contract: no target.delete() anywhere " +
                "--- the delete-then-rename fallback opened a crash window with no index " +
                "at all (the behavioral pin lives in VaultStoreBindingTest)",
            source.contains("target.delete()"),
        )
    }

    /**
     * D-5: [AndroidVaultBiometricKeys]'s StrongBox leg must carry the SAME
     * broad OEM fallback its sibling [AndroidVaultKeys] got in the W1
     * hardening --- catch order StrongBoxUnavailableException (expected,
     * INFO) → GeneralSecurityException (WARN) → RuntimeException (WARN,
     * the plain-`ProviderException` OEMs), then the fall-through to the
     * plain TEE generation. Some OEM/custom-ROM keystores abort StrongBox
     * generation with a plain `ProviderException`; without the parity,
     * biometric enrollment failed raw on exactly those devices while the
     * media key silently fell back.
     */
    @Test
    fun `the biometric key generation mirrors the media key's broad StrongBox fallback`() {
        val biometric = moduleFile(BIOMETRIC_KEYS_SRC).readText()
        val media = moduleFile(MEDIA_KEYS_SRC).readText()
        val ladder = listOf(
            "catch (e: StrongBoxUnavailableException)",
            "catch (e: GeneralSecurityException)",
            "catch (e: RuntimeException)",
        )
        ladder.forEachIndexed { index, rung ->
            val biometricAt = biometric.indexOf(rung)
            val mediaAt = media.indexOf(rung)
            assertTrue(
                "AndroidVaultBiometricKeys.generate must catch " +
                    "${rung.removePrefix("catch (e: ").removeSuffix(")")} " +
                    "(D-5 parity with AndroidVaultKeys)",
                biometricAt >= 0,
            )
            assertTrue(
                "AndroidVaultKeys must still carry its $rung (the parity template itself)",
                mediaAt >= 0,
            )
            if (index > 0) {
                val previousRung = ladder[index - 1]
                assertTrue(
                    "the catch ladder must keep the sibling's order ($rung after " +
                        "$previousRung) in BOTH files",
                    biometric.indexOf(previousRung) < biometricAt &&
                        media.indexOf(previousRung) < mediaAt,
                )
            }
        }
        // The exhausted ladder falls through to the plain TEE generation
        // (StrongBox is defense in depth, never a requirement).
        val teeFallThrough = biometric.indexOf("return generateKeystoreKey()")
        assertTrue(
            "the exhausted StrongBox ladder must fall through to the plain TEE generation",
            teeFallThrough >= 0,
        )
        assertTrue(
            "the TEE fall-through must follow the ladder, not bypass it",
            teeFallThrough > biometric.indexOf("catch (e: RuntimeException)"),
        )
        // The unexpected-failure rungs WARN through DiagLog with the
        // throwable --- the sibling's call shape and message idiom --- and
        // exactly the two of them (GeneralSecurityException, RuntimeException).
        assertEquals(
            "both unexpected-failure rungs must WARN with the throwable",
            2,
            Regex("""DiagLog\.w\(\s*TAG,\s*"[^"]*StrongBox generation failed[^"]*",\s*e,""")
                .findAll(biometric)
                .count(),
        )
        assertTrue(
            "the expected StrongBox-unavailable case must stay INFO-logged",
            biometric.contains("StrongBox unavailable -> TEE keystore"),
        )
    }

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    private companion object {

        /** The store whose D-3/D-9 contracts are pinned (module dir or repo root). */
        const val VAULT_STORE_SRC =
            "src/main/kotlin/io/github/dukeoftoad/gallery/data/vault/VaultStore.kt"

        /** The D-5 seam under test. */
        const val BIOMETRIC_KEYS_SRC =
            "src/main/kotlin/io/github/dukeoftoad/gallery/data/vault/AndroidVaultBiometricKeys.kt"

        /** The read-only sibling whose D-5 fallback is mirrored. */
        const val MEDIA_KEYS_SRC =
            "src/main/kotlin/io/github/dukeoftoad/gallery/data/vault/AndroidVaultKeys.kt"

        /** D-3's blob-durability anchor: the fsync inside the blob-write `.use` block. */
        const val BLOB_FSYNC_ANCHOR = "output.fd.sync()"

        /** D-3's index-commit anchor: the fsynced atomic rewrite that references the blob. */
        const val INDEX_COMMIT_ANCHOR = "atomicWrite("
    }
}
