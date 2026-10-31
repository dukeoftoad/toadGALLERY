package io.github.dukeoftoad.gallery.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the issue #69 race guard: the [VaultLockGeneration] token and
 * the [VaultThumbnailPolicy.shouldPublishThumbnail] decision. The defect this
 * pins shut: `ensureThumbnails` checks the unlocked state BEFORE each decode,
 * but a decode is a suspend call --- a lock/background event can land in the
 * middle, and without the post-decode re-check the still-running batch would
 * republish freshly decrypted pixels into the cleared map of a locked
 * session ("thumbnails can be repopulated after locking").
 *
 * The guard is generation-based, so it is pure arithmetic on the JVM: a
 * bitmap may land in memory ONLY when the batch's captured generation still
 * matches the live one AND the session is still unlocked. Android-free per
 * ADR-0016.
 */
class VaultLockGenerationTest {

    // ---- VaultLockGeneration: monotonic session token -------------------

    @Test
    fun `the generation starts at zero and reads back stable`() {
        val generation = VaultLockGeneration()
        assertEquals(0L, generation.current())
        assertEquals(0L, generation.current())
    }

    @Test
    fun `advance bumps the token and returns the fresh value`() {
        val generation = VaultLockGeneration()
        assertEquals(1L, generation.advance())
        assertEquals(1L, generation.current())
        assertEquals(2L, generation.advance())
        assertEquals(2L, generation.current())
    }

    @Test
    fun `a captured generation never matches again after a boundary`() {
        val generation = VaultLockGeneration()
        val captured = generation.current()
        generation.advance() // the lock (or unlock) boundary
        assertNotEquals(captured, generation.current())
    }

    // ---- shouldPublishThumbnail: the immediate pre-publish check --------

    @Test
    fun `publishing requires both a fresh generation and an unlocked session`() {
        val truthTable = listOf(
            /* captured, current, unlocked, expected */
            Triple(7L, 7L, true, true),    // same session, still unlocked
            Triple(7L, 7L, false, false),  // fresh token but locked RIGHT NOW
            Triple(7L, 8L, true, false),   // boundary crossed mid-decode
            Triple(7L, 8L, false, false),  // locked AND stale
        )
        truthTable.forEach { (captured, current, unlocked, expected) ->
            assertEquals(
                expected,
                VaultThumbnailPolicy.shouldPublishThumbnail(captured, current, unlocked),
            )
        }
    }

    @Test
    fun `a bitmap decoded when the lock lands mid-decode is dropped`() {
        // The scenario from the issue #69 report: capture at decode start,
        // onBackgrounded() advances the generation and clears the map while
        // the decode is still suspended, then the decode returns.
        val generation = VaultLockGeneration()
        val captured = generation.current()
        generation.advance() // onBackgrounded()
        val unlockedNow = false
        assertFalse(
            VaultThumbnailPolicy.shouldPublishThumbnail(captured, generation.current(), unlockedNow),
        )
    }

    @Test
    fun `a stale bitmap from a dead session cannot land after re-unlocking`() {
        // lock (gen 1) -> unlock (gen 2): a batch captured under gen 1 must
        // still be barred from the NEW session's memory --- the token is
        // monotonic, it never recycles.
        val generation = VaultLockGeneration()
        val captured = generation.current()
        generation.advance() // lock
        generation.advance() // unlock again
        val unlockedNow = true
        assertFalse(
            VaultThumbnailPolicy.shouldPublishThumbnail(captured, generation.current(), unlockedNow),
        )
    }

    @Test
    fun `a fresh batch of the current session publishes normally`() {
        // No spurious invalidation: without any boundary crossing, the
        // post-decode re-check passes and the bitmap lands as before.
        val generation = VaultLockGeneration()
        val captured = generation.advance() // enterUnlockedSession()
        assertTrue(
            VaultThumbnailPolicy.shouldPublishThumbnail(captured, generation.current(), true),
        )
    }
}
