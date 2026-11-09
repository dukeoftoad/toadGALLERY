package com.toadgallery.gallery.data.media.changes

import com.toadgallery.gallery.data.catalogue.CatalogueRecord
import com.toadgallery.gallery.data.diag.DiagLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the NULL-provenance healing pass (r20 round + the r51
 * E-3 rotation additions): the bounded batch loop, the drain-until-empty
 * convergence, the re-offer of failed rows, and the r51 NO-PROGRESS BREAK
 * that stops a permanently failing selection from burning the whole batch
 * budget re-reading the same bytes.
 *
 * The healing STORE is faked (the Room side is two DAO statements, ADR
 * -0016-thin); the HOOK is a recording fake too --- what is pinned here is
 * the pass's own orchestration, which is all the pass is. The r51
 * cross-start rotation (persisted failure markers) has its own suite:
 * `ProvenanceHealingRotationTest`.
 */
class ProvenanceHealingPassTest {

    private fun record(key: String): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = key,
            canonicalUri = "content://media/external/images/$key",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = 500L,
        )

    /** A store whose selectable set shrinks only when the hook stamps rows. */
    private class FakeHealingStore(
        /** The still-unindexed rows, newest-seen-first (the DAO's order). */
        val selectable: MutableList<CatalogueRecord>,
    ) : ProvenanceHealingStore {
        val queries = mutableListOf<Int>()
        val stamped = mutableListOf<String>()

        override suspend fun rowsNeedingIndexHealing(limit: Int): List<CatalogueRecord> {
            queries += limit
            return selectable.take(limit)
        }

        override suspend fun markProvenanceIndexed(localMediaKey: String, atMillis: Long) {
            stamped += localMediaKey
            selectable.removeAll { it.localMediaKey == localMediaKey }
        }
    }

    /** A hook that stamps every row it is offered (the success path). */
    private class StampingHook(
        private val store: FakeHealingStore,
    ) : ScanIndexHook {
        val offered = mutableListOf<CatalogueRecord>()

        override suspend fun onRecordsApplied(
            added: List<CatalogueRecord>,
            changed: List<CatalogueReconciler.ChangedRecord>,
        ) {
            offered += added
            assertTrue(changed.isEmpty()) // healing re-offers are adds only
            for (record in added) {
                store.markProvenanceIndexed(record.localMediaKey, atMillis = 1L)
            }
        }
    }

    @Test
    fun `an empty selection heals nothing and queries once`() = runBlocking {
        val store = FakeHealingStore(mutableListOf())
        val hook = StampingHook(store)
        val pass = ProvenanceHealingPass(store, hook)

        val offered = pass.heal()

        assertEquals(0, offered)
        assertEquals(1, store.queries.size)
        assertTrue(hook.offered.isEmpty())
    }

    @Test
    fun `a small selection drains in one batch`() = runBlocking {
        val rows = (1..3).map { record("external_primary:$it") }
        val store = FakeHealingStore(rows.toMutableList())
        val hook = StampingHook(store)
        val pass = ProvenanceHealingPass(store, hook, batchSize = 10)

        val offered = withQuietDiag { pass.heal() }

        assertEquals(3, offered)
        assertEquals(rows, hook.offered)
        // One batch (3 < 10) plus the confirming empty query... actually
        // the loop breaks on `rows.size < batchSize` WITHOUT a second
        // query --- pin that too (no wasted query on the drain tail).
        assertEquals(1, store.queries.size)
    }

    @Test
    fun `a large selection drains across batches, newest first per batch`() = runBlocking {
        val rows = (1..5).map { record("external_primary:$it") }
        val store = FakeHealingStore(rows.toMutableList())
        val hook = StampingHook(store)
        val pass = ProvenanceHealingPass(store, hook, batchSize = 2)

        val offered = withQuietDiag { pass.heal() }

        assertEquals(5, offered)
        // Three batches: 2 + 2 + 1, then the size < batchSize break.
        assertEquals(3, store.queries.size)
        // Every row was offered exactly once, in newest-first order.
        assertEquals(rows, hook.offered)
        assertTrue(store.selectable.isEmpty())
    }

    @Test
    fun `a fully repeating selection is offered once then stops on the no-progress break`() = runBlocking {
        // The pathological store: marking does nothing (every stamp lands
        // but the selectable set never shrinks --- the "indexing keeps
        // failing" stand-in, e.g. a read-only disk). The r20 pass re-
        // offered the same head batch up to its max-batches budget --- the
        // audited E-3 waste (the same bytes re-read 10x per start). The
        // r51 no-progress break offers the batch ONCE and stops at the
        // first batch drawn entirely from already-offered keys.
        val rows = (1..2).map { record("external_primary:$it") }
        val store = object : ProvenanceHealingStore {
            val queries = mutableListOf<Int>()
            var offeredCount = 0

            override suspend fun rowsNeedingIndexHealing(limit: Int): List<CatalogueRecord> {
                queries += limit
                return rows
            }

            override suspend fun markProvenanceIndexed(localMediaKey: String, atMillis: Long) {
                // No-op: the row stays selectable (the stamp write itself
                // is what keeps failing on the broken disk).
            }
        }
        val hook = ScanIndexHook { added, _ ->
            store.offeredCount += added.size
        }
        val pass = ProvenanceHealingPass(store, hook, batchSize = 2, maxBatches = 3)

        val offered = withQuietDiag { pass.heal() }

        // Offered exactly once, then the repeat batch stops the pass
        // (before the hook sees the same rows a second time).
        assertEquals(2, offered)
        assertEquals(2, store.queries.size)
        assertEquals(2, store.offeredCount)
    }

    @Test
    fun `mixed batches keep making progress until the batch budget is spent`() = runBlocking {
        // The same broken-disk world, but only SOME rows are poisoned: a
        // batch of repeats + new rows is forward progress, so the pass
        // must NOT stop on it --- only a batch drawn ENTIRELY from
        // already-offered keys is the no-progress signature. Here rows b
        // and e never heal while a, c, d do; every batch carries at least
        // one new row until the budget is spent.
        val rows = listOf("a", "b", "c", "d", "e").map { record("external_primary:$it") }
        val poisoned = setOf("external_primary:b", "external_primary:e")
        val store = FakeHealingStore(rows.toMutableList())
        val hook = object : ScanIndexHook {
            val offeredKeys = mutableListOf<String>()

            override suspend fun onRecordsApplied(
                added: List<CatalogueRecord>,
                changed: List<CatalogueReconciler.ChangedRecord>,
            ) {
                offeredKeys += added.map { it.localMediaKey }
                for (record in added) {
                    if (record.localMediaKey !in poisoned) {
                        store.markProvenanceIndexed(record.localMediaKey, atMillis = 1L)
                    }
                }
            }
        }
        val pass = ProvenanceHealingPass(store, hook, batchSize = 2, maxBatches = 3)

        val offered = withQuietDiag { pass.heal() }

        // All three batches ran (each had a new row), six rows offered
        // (a,b then b,c then b,d --- the poisoned b rides every batch).
        assertEquals(6, offered)
        assertEquals(3, store.queries.size)
        // The healthy rows healed; the poisoned pair stays selectable.
        assertEquals(
            listOf("external_primary:b", "external_primary:e"),
            store.selectable.map { it.localMediaKey },
        )
        assertTrue(hook.offeredKeys.contains("external_primary:b"))
    }

    @Test
    fun `the pass logs its summary through DiagLog only when it offered rows`() = runBlocking {
        val rows = listOf(record("external_primary:41"))
        val store = FakeHealingStore(rows.toMutableList())
        val hook = StampingHook(store)
        val pass = ProvenanceHealingPass(store, hook, batchSize = 10)

        val logged = mutableListOf<String>()
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, tag, message -> if (tag == "ProvenanceHealing") logged += message }
        try {
            pass.heal()
        } finally {
            DiagLog.sink = stockSink
        }

        assertEquals(listOf("provenance healing pass offered 1 rows to the index hook"), logged)
    }

    /** Runs [block] with DiagLog silenced (the offered-rows summary line). */
    private suspend fun <T> withQuietDiag(block: suspend () -> T): T {
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        try {
            return block()
        } finally {
            DiagLog.sink = stockSink
        }
    }
}
