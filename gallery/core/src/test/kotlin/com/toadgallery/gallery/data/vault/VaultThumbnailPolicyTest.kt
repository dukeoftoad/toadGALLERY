package com.toadgallery.gallery.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure thumbnail policy (issue #58): the `inSampleSize`
 * halving contract and the plaintext temp-file naming/lifecycle rules that
 * `VaultThumbnailDecoder` (the thin Android half) executes. Pinned here so
 * the memory-bounding arithmetic and the no-plaintext-persists policy are
 * exact and Android-free (ADR-0016).
 */
class VaultThumbnailPolicyTest {

    // ---- inSampleSize: the BitmapFactory halving contract ---------------

    @Test
    fun `images already within the target decode at full resolution`() {
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(256, 256))
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(200, 100, targetEdge = 256))
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(1, 1))
    }

    @Test
    fun `a square image halves to the largest power of two above the target`() {
        // 1024 -> 4 (yields 256px); 512 -> 2 (yields 256px); 511 stays 1
        // because halving once (255px) would already undershoot the target.
        assertEquals(4, VaultThumbnailPolicy.inSampleSize(1024, 1024))
        assertEquals(2, VaultThumbnailPolicy.inSampleSize(512, 512))
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(511, 511))
    }

    @Test
    fun `both edges must survive halving`() {
        // 4096x300: the long edge would tolerate 8, but the short edge (300)
        // halves to 150 < 256 at sample 2 --- sample stays 1.
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(4096, 300))
        // 4096x600: short edge 600 -> 300 >= 256 at sample 2, but 150 < 256
        // at sample 4 --- sample 2.
        assertEquals(2, VaultThumbnailPolicy.inSampleSize(4096, 600))
    }

    @Test
    fun `a huge photo decodes a bounded fraction of its pixels`() {
        // 12k x 9k (a 108 MP shot): sample 32 keeps the decode at 375x281.
        assertEquals(32, VaultThumbnailPolicy.inSampleSize(12_000, 9_000))
    }

    @Test
    fun `degenerate dimensions fall back to an honest full decode`() {
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(0, 1000))
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(1000, 0))
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(-5, 1000))
        assertEquals(1, VaultThumbnailPolicy.inSampleSize(1000, 1000, targetEdge = 0))
    }

    // ---- Temp-file naming: the plaintext video path's lifecycle policy --

    @Test
    fun `temp names are stable per entry id and carry the sweepable shape`() {
        assertEquals("vault-thumb-7.tmp", VaultThumbnailPolicy.tempFileName(7))
        assertEquals("vault-thumb-42.tmp", VaultThumbnailPolicy.tempFileName(42))
        assertNotEquals(
            VaultThumbnailPolicy.tempFileName(1),
            VaultThumbnailPolicy.tempFileName(2),
        )
        assertTrue(VaultThumbnailPolicy.isTempFileName("vault-thumb-7.tmp"))
        assertTrue(VaultThumbnailPolicy.isTempFileName("vault-thumb-123456789.tmp"))
    }

    @Test
    fun `the sweep matcher rejects names outside the policy`() {
        // Only the app's own temp spelling may be swept: a stray cache file
        // with another name is not ours to judge (the same rule as
        // VaultStore.sweepOrphans).
        assertFalse(VaultThumbnailPolicy.isTempFileName("vault-thumb-7.bin"))
        assertFalse(VaultThumbnailPolicy.isTempFileName("thumb-7.tmp"))
        assertFalse(VaultThumbnailPolicy.isTempFileName("vault-thumb-.tmp"))
        assertFalse(VaultThumbnailPolicy.isTempFileName("index.bin"))
        assertFalse(VaultThumbnailPolicy.isTempFileName(""))
    }

    @Test
    fun `the target edge is the documented bound`() {
        assertEquals(256, VaultThumbnailPolicy.TARGET_EDGE_PX)
    }
}
