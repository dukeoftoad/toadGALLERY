package io.github.dukeoftoad.gallery.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the r51 (D-11) app-start leg of the vault thumbnail
 * stale-temp sweep: [VaultThumbnailPolicy.sweepStaleTempFiles], the pure
 * `java.io` half both call sites share (the decoder's per-unlock
 * [VaultThumbnailDecoder.cleanStaleTempFiles] and the app-start sweep in
 * `ToadGalleryApplication`).
 *
 * What is pinned: the sweep deletes ONLY policy-shaped
 * `vault-thumb-<id>.tmp` files (the same matcher discipline as
 * `VaultStore.sweepOrphans` --- anything else in `cacheDir` is not ours to
 * judge), directories are never followed, a missing/empty `cacheDir` is a
 * clean no-op, and the return count is honest. Pure JVM (ADR-0016).
 */
class VaultThumbnailPolicySweepTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the sweep deletes only policy-shaped temp files and reports the count`() {
        val cacheDir = tmp.newFolder()
        val staleA = File(cacheDir, VaultThumbnailPolicy.tempFileName(7))
        val staleB = File(cacheDir, VaultThumbnailPolicy.tempFileName(42))
        staleA.writeBytes(ByteArray(16))
        staleB.writeBytes(ByteArray(16))

        // Decoys: everything the matcher must NOT judge.
        val foreignTmp = File(cacheDir, "other-cache.tmp") // wrong prefix
        val sealedName = File(cacheDir, "vault-thumb-7.bin") // wrong suffix
        val nonNumericId = File(cacheDir, "vault-thumb-abc.tmp") // not an id
        val plainFile = File(cacheDir, "notes.txt")
        val dir = File(cacheDir, VaultThumbnailPolicy.tempFileName(9)) // a DIRECTORY
        foreignTmp.writeBytes(ByteArray(4))
        sealedName.writeBytes(ByteArray(4))
        nonNumericId.writeBytes(ByteArray(4))
        plainFile.writeBytes(ByteArray(4))
        dir.mkdirs()

        val removed = VaultThumbnailPolicy.sweepStaleTempFiles(cacheDir)

        assertEquals(2, removed)
        assertFalse(staleA.exists())
        assertFalse(staleB.exists())
        assertTrue(foreignTmp.exists())
        assertTrue(sealedName.exists())
        assertTrue(nonNumericId.exists())
        assertTrue(plainFile.exists())
        assertTrue(dir.exists())
    }

    @Test
    fun `an empty cache dir is a clean no-op`() {
        val cacheDir = tmp.newFolder()
        assertEquals(0, VaultThumbnailPolicy.sweepStaleTempFiles(cacheDir))
    }

    @Test
    fun `a missing cache dir is a clean no-op, never a crash`() {
        // The process-start leg must survive any cacheDir shape; listFiles()
        // answers null exactly for the missing/unreadable cases.
        val missing = File(tmp.newFolder(), "does-not-exist")
        assertEquals(0, VaultThumbnailPolicy.sweepStaleTempFiles(missing))
    }

    @Test
    fun `the sweep is repeatable - freshly orphaned temps are swept again`() {
        val cacheDir = tmp.newFolder()

        assertEquals(0, VaultThumbnailPolicy.sweepStaleTempFiles(cacheDir))

        // A "crash leftover" appears after the first sweep already ran:
        val leftover = File(cacheDir, VaultThumbnailPolicy.tempFileName(5))
        leftover.writeBytes(ByteArray(8))
        assertEquals(1, VaultThumbnailPolicy.sweepStaleTempFiles(cacheDir))
        assertEquals(0, VaultThumbnailPolicy.sweepStaleTempFiles(cacheDir))
        assertFalse(leftover.exists())
    }
}
