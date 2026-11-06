package io.github.dukeoftoad.gallery.data.catalogue.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [CatalogueInListChunking] --- the r51 (audit E-2) policy
 * that keeps every `IN (:keys)` statement in [CatalogueDao] under SQLite's
 * host-parameter ceiling. The DAO's @Transaction wrappers are Room code
 * (untestable per ADR-0016); this suite pins the one decision that must
 * never silently rot: the chunk SIZE and the empty-list behavior.
 *
 * Why this matters (stated once more, because a constant is where this
 * fix lives or dies): SQLite below 3.32 --- Android 8-11, and minSdk 26
 * keeps those devices in the matrix --- defaults
 * `SQLITE_MAX_VARIABLE_NUMBER` to 999, and Room binds one host parameter
 * per `IN`-list element. An unbounded tombstone/restore list (emptying a
 * large trash, the OS-upgrade re-sight) threw "too many SQL variables",
 * rolled the whole transition back, and re-failed identically on every
 * later scan pass.
 */
class CatalogueInListChunkingTest {

    @Test
    fun `2500 keys chunk into 900, 900 and 700`() {
        val keys = (1..2_500).map { "external_primary:$it" }

        val chunks = CatalogueInListChunking.chunks(keys)

        assertEquals(listOf(900, 900, 700), chunks.map { it.size })
        // Order preserved, nothing lost or duplicated by the slicing.
        assertEquals(keys, chunks.flatten())
    }

    @Test
    fun `an empty key list issues no statements`() {
        // The pre-r51 empty call was Room's `IN ()` (matches zero rows ---
        // a no-op); the chunked call must stay a no-op: zero statements,
        // the caller's loop body never runs.
        assertTrue(CatalogueInListChunking.chunks<String>(emptyList()).isEmpty())
    }

    @Test
    fun `the chunk size stays under the SQLite host-parameter ceiling`() {
        // SQLite < 3.32 defaults SQLITE_MAX_VARIABLE_NUMBER to 999; a chunk
        // size at or above 999 reintroduces E-2 on the oldest devices.
        assertTrue(CatalogueInListChunking.MAX_KEYS_PER_IN_STATEMENT < 999)
    }

    @Test
    fun `boundary sizes chunk exactly`() {
        // Exactly the cap is one statement; one over splits the tail.
        assertEquals(1, CatalogueInListChunking.chunks(List(900) { it }).size)
        assertEquals(
            listOf(900, 1),
            CatalogueInListChunking.chunks(List(901) { it }).map { it.size },
        )
    }
}
