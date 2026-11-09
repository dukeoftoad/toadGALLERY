package com.toadgallery.gallery.data.media.changes

import com.toadgallery.gallery.data.catalogue.CatalogueRecord
import com.toadgallery.gallery.data.diag.DiagLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the r51 cross-start rotation (audit E-3): the pass's
 * persisted failure markers, the pure [ProvenanceHealingRotationPolicy]
 * decisions, and the production [ProvenanceHealingFailureMarkerFile]'s
 * atomic-write / forgiving-parse discipline.
 *
 * What is pinned:
 * - the STARVATION FIX end to end: a deterministically-failing head batch
 *   is offered its three strikes, then rotates OUT of the selection so the
 *   pass heals the rows behind it (the r20 pass spent its whole budget
 *   re-offering the same poisoned block forever);
 * - a marker goes STALE when its row is re-scanned (lastSeen moved) and
 *   the row gets a fresh offering whatever its old count;
 * - a row that HEALS has its marker removed (success clears the record);
 * - the selection is OVER-FETCHED by the exclusion count so an
 *   exclusion-held head still fills the batch;
 * - the marker file round-trips, replaces atomically (no `.tmp` left
 *   behind) and recovers to clean-empty from missing/corrupt/wrong-version
 *   files;
 * - the pure policy's threshold, expiry, cap and outcome-folding rules.
 *
 * The healing STORE and the HOOK are fakes (ADR-0016); the marker store is
 * an in-memory fake for the pass tests and the REAL file for the
 * persistence tests (TemporaryFolder).
 */
class ProvenanceHealingRotationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun row(key: String, lastSeenAtMillis: Long): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = key,
            canonicalUri = "content://media/external/images/$key",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = 500L,
            lastSeenAtMillis = lastSeenAtMillis,
        )

    /**
     * The selectable-set store: rows leave only through the stamp (the
     * DAO contract --- `provenanceIndexedAtMillis IS NULL` selection), and
     * every query's LIMIT is recorded so the over-fetch discipline is
     * pinnable.
     */
    private class SelectableStore(
        val selectable: MutableList<CatalogueRecord>,
    ) : ProvenanceHealingStore {
        val queryLimits = mutableListOf<Int>()

        override suspend fun rowsNeedingIndexHealing(limit: Int): List<CatalogueRecord> {
            queryLimits += limit
            return selectable.take(limit)
        }

        override suspend fun markProvenanceIndexed(localMediaKey: String, atMillis: Long) {
            selectable.removeAll { it.localMediaKey == localMediaKey }
        }
    }

    /**
     * A hook whose indexing fails DETERMINISTICALLY for the poisoned keys
     * (the audit's permanently-failing rows: corrupt EXIF throwing past
     * the per-row catch) and stamps everything else.
     */
    private class PoisonedHeadHook(
        private val store: SelectableStore,
        private val poisoned: Set<String>,
    ) : ScanIndexHook {
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

    /** The persistence seam's in-memory fake. */
    private class FakeMarkerStore : ProvenanceHealingFailureMarkerStore {
        val markers = LinkedHashMap<String, ProvenanceHealingFailureMarker>()
        var saves = 0

        override suspend fun load(): List<ProvenanceHealingFailureMarker> = markers.values.toList()

        override suspend fun save(markers: List<ProvenanceHealingFailureMarker>) {
            saves++
            this.markers.clear()
            markers.forEach { this.markers[it.localMediaKey] = it }
        }
    }

    /** Runs [block] with DiagLog silenced (the pass's summary lines). */
    private suspend fun <T> withQuietDiag(block: suspend () -> T): T {
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        try {
            return block()
        } finally {
            DiagLog.sink = stockSink
        }
    }

    // --- the pass, end to end -------------------------------------------------

    @Test
    fun `a poisoned head is offered its three strikes then rotates out for the rows behind it`() = runBlocking {
        // The audited starvation shape: the head batch fails
        // deterministically and its lastSeenAtMillis never moves, so the
        // naive re-selection would re-offer the same two rows forever ---
        // within a pass (the no-progress break, pinned in
        // ProvenanceHealingPassTest) and across passes (the rotation
        // pinned HERE: three strikes, then exclusion, then the over-fetch
        // reaches past the block to the healthy rows behind it).
        val p1 = row("external_primary:p1", lastSeenAtMillis = 100L)
        val p2 = row("external_primary:p2", lastSeenAtMillis = 99L)
        val h1 = row("external_primary:h1", lastSeenAtMillis = 98L)
        val h2 = row("external_primary:h2", lastSeenAtMillis = 97L)
        val store = SelectableStore(mutableListOf(p1, p2, h1, h2))
        val hook = PoisonedHeadHook(store, poisoned = setOf("external_primary:p1", "external_primary:p2"))
        val markerStore = FakeMarkerStore()
        var now = 1_000L
        val pass = ProvenanceHealingPass(
            healingStore = store,
            indexHook = hook,
            batchSize = 2,
            maxBatches = 10,
            failureMarkers = markerStore,
            nowMillis = { now },
        )

        // Passes 1-3: the poisoned head fills the whole batch, fails, is
        // re-selected next pass --- one strike per pass (counts 1, 2, 3).
        repeat(3) {
            now += 10_000L
            val offered = withQuietDiag { pass.heal() }
            assertEquals(2, offered)
        }
        assertEquals(3, markerStore.markers.getValue("external_primary:p1").failureCount)
        assertEquals(3, markerStore.markers.getValue("external_primary:p2").failureCount)

        // Pass 4: the markers exclude the head; the over-fetched selection
        // reaches PAST it and the pass heals the rows behind it instead of
        // re-reading the poisoned bytes a fourth time.
        now += 10_000L
        hook.offeredKeys.clear()
        val offeredPass4 = withQuietDiag { pass.heal() }

        assertEquals(listOf("external_primary:h1", "external_primary:h2"), hook.offeredKeys)
        assertEquals(2, offeredPass4)
        assertEquals(
            listOf("external_primary:p1", "external_primary:p2"),
            store.selectable.map { it.localMediaKey },
        )
        // The excluded head still had its marker refreshed by the pass-end
        // accounting? No --- it was not OFFERED this pass, so its marker
        // carries through unchanged (count stays 3 until it is offered
        // again, e.g. after a revision change or the 30-day expiry).
        assertEquals(3, markerStore.markers.getValue("external_primary:p1").failureCount)
    }

    @Test
    fun `a pre-marked poisoned head is skipped on the very next pass`() = runBlocking {
        // The rotation's cross-start contract in one pass: a marker
        // persisted by an earlier app start (count 3, unchanged revision)
        // holds the poisoned head out, and the selection is over-fetched
        // so the batch still fills with the row behind it.
        val p1 = row("external_primary:p1", lastSeenAtMillis = 100L)
        val h1 = row("external_primary:h1", lastSeenAtMillis = 98L)
        val store = SelectableStore(mutableListOf(p1, h1))
        val hook = PoisonedHeadHook(store, poisoned = setOf("external_primary:p1"))
        val markerStore = FakeMarkerStore()
        markerStore.markers["external_primary:p1"] = ProvenanceHealingFailureMarker(
            localMediaKey = "external_primary:p1",
            failureCount = 3,
            lastFailedAtMillis = 500L,
            rowLastSeenAtMillis = 100L,
        )
        val pass = ProvenanceHealingPass(
            healingStore = store,
            indexHook = hook,
            batchSize = 1,
            maxBatches = 5,
            failureMarkers = markerStore,
            nowMillis = { 1_000L },
        )

        val offered = withQuietDiag { pass.heal() }

        // Only the healthy row is offered; the poisoned head rode through
        // the fetch (LIMIT batchSize + 1 exclusion = 2) but was filtered.
        assertEquals(listOf("external_primary:h1"), hook.offeredKeys)
        assertEquals(1, offered)
        assertEquals(listOf(2, 2, 2), store.queryLimits)
        // The head is still selectable --- excluded from the OFFERING, not
        // deleted; it returns on a revision change or marker expiry.
        assertEquals(listOf("external_primary:p1"), store.selectable.map { it.localMediaKey })
    }

    @Test
    fun `a marker goes stale when the row is re-scanned and the row is offered fresh`() = runBlocking {
        // The revision-change re-inclusion rule: the marker was written
        // against lastSeen=100, the scan has since re-seen the row
        // (lastSeen=200), so the three old failures no longer describe the
        // row's bytes --- it is offered despite the exclusion-grade count.
        val p1 = row("external_primary:p1", lastSeenAtMillis = 200L)
        val store = SelectableStore(mutableListOf(p1))
        val hook = PoisonedHeadHook(store, poisoned = setOf("external_primary:p1"))
        val markerStore = FakeMarkerStore()
        markerStore.markers["external_primary:p1"] = ProvenanceHealingFailureMarker(
            localMediaKey = "external_primary:p1",
            failureCount = 3,
            lastFailedAtMillis = 500L,
            rowLastSeenAtMillis = 100L,
        )
        val pass = ProvenanceHealingPass(
            healingStore = store,
            indexHook = hook,
            batchSize = 1,
            maxBatches = 5,
            failureMarkers = markerStore,
            nowMillis = { 1_000L },
        )

        val offered = withQuietDiag { pass.heal() }

        assertEquals(listOf("external_primary:p1"), hook.offeredKeys)
        assertEquals(1, offered)
        // Still broken after the fresh chance: the failure re-records the
        // marker against the NEW revision (the staleness sentinel moved).
        assertEquals(200L, markerStore.markers.getValue("external_primary:p1").rowLastSeenAtMillis)
        assertEquals(4, markerStore.markers.getValue("external_primary:p1").failureCount)
    }

    @Test
    fun `a row that heals has its failure marker removed`() = runBlocking {
        // Success clears the record (the audit's rule): a row that finally
        // indexes leaves no trace in the marker map, so a future failure
        // starts from zero strikes.
        val r1 = row("external_primary:r1", lastSeenAtMillis = 100L)
        val store = SelectableStore(mutableListOf(r1))
        val hook = PoisonedHeadHook(store, poisoned = emptySet())
        val markerStore = FakeMarkerStore()
        markerStore.markers["external_primary:r1"] = ProvenanceHealingFailureMarker(
            localMediaKey = "external_primary:r1",
            failureCount = 2,
            lastFailedAtMillis = 500L,
            rowLastSeenAtMillis = 100L,
        )
        val pass = ProvenanceHealingPass(
            healingStore = store,
            indexHook = hook,
            batchSize = 1,
            maxBatches = 5,
            failureMarkers = markerStore,
            nowMillis = { 1_000L },
        )

        val offered = withQuietDiag { pass.heal() }

        assertEquals(1, offered)
        assertTrue(store.selectable.isEmpty())
        assertFalse(markerStore.markers.containsKey("external_primary:r1"))
    }

    // --- the marker file ------------------------------------------------------

    @Test
    fun `the marker file round-trips and its atomic write leaves no temp behind`() {
        val dir = tmp.newFolder()
        val file = File(dir, "provenance-healing-markers.json")
        val store = ProvenanceHealingFailureMarkerFile(file)
        val markers = listOf(
            ProvenanceHealingFailureMarker("external_primary:1", 3, 5_000L, 100L),
            ProvenanceHealingFailureMarker("external_primary:2", 1, 4_000L, 200L),
        )

        runBlocking { store.save(markers) }
        val loaded = runBlocking { store.load() }

        assertEquals(markers, loaded)
        assertEquals(
            setOf("provenance-healing-markers.json"),
            dir.listFiles()!!.map { it.name }.toSet(),
        )

        // Replace atomically: the second save fully supersedes the first.
        runBlocking { store.save(listOf(markers.first())) }
        assertEquals(listOf(markers.first()), runBlocking { store.load() })
    }

    @Test
    fun `a missing, corrupt or wrong-version marker file loads as the clean empty state`() {
        val dir = tmp.newFolder()

        // Missing: nothing was ever persisted.
        val missing = ProvenanceHealingFailureMarkerFile(File(dir, "absent.json"))
        assertTrue(runBlocking { missing.load() }.isEmpty())

        // Corrupt: a crash mid-write or a hand edit must degrade to "no
        // markers", never a thrown parse error into the pass.
        val corruptFile = File(dir, "corrupt.json")
        corruptFile.writeText("{ not json")
        assertTrue(runBlocking { ProvenanceHealingFailureMarkerFile(corruptFile).load() }.isEmpty())

        // Wrong version: a future format is not this build's to guess at.
        val futureFile = File(dir, "future.json")
        futureFile.writeText("{\"version\":99,\"markers\":[]}")
        assertTrue(runBlocking { ProvenanceHealingFailureMarkerFile(futureFile).load() }.isEmpty())
    }

    // --- the pure rotation policy ----------------------------------------------

    @Test
    fun `active exclusions require three strikes and a fresh marker`() {
        val now = 10_000_000L
        val markers = listOf(
            ProvenanceHealingFailureMarker("k:0", 0, now, 1L),
            ProvenanceHealingFailureMarker("k:1", 1, now, 1L),
            ProvenanceHealingFailureMarker("k:2", 2, now, 1L),
            ProvenanceHealingFailureMarker("k:3", 3, now, 1L),
            ProvenanceHealingFailureMarker("k:9", 9, now, 1L),
            // Three strikes but the marker is older than the 30-day window:
            // the exclusion lapses and the row gets a fresh trial.
            ProvenanceHealingFailureMarker(
                "k:stale",
                3,
                now - ProvenanceHealingRotationPolicy.MARKER_EXPIRY_MILLIS,
                1L,
            ),
        )

        assertEquals(
            setOf("k:3", "k:9"),
            ProvenanceHealingRotationPolicy.activeExclusions(markers, now),
        )
    }

    @Test
    fun `pass outcomes bump failures, clear healings and carry the rest`() {
        val now = 5_000L
        val existing = mapOf(
            "k:failed-before" to ProvenanceHealingFailureMarker("k:failed-before", 1, 1_000L, 10L),
            "k:healed" to ProvenanceHealingFailureMarker("k:healed", 3, 1_000L, 10L),
            "k:not-offered" to ProvenanceHealingFailureMarker("k:not-offered", 2, 1_000L, 10L),
        )
        val stillSelectable = mapOf(
            "k:failed-before" to row("k:failed-before", lastSeenAtMillis = 10L),
            "k:first-failure" to row("k:first-failure", lastSeenAtMillis = 20L),
        )

        val updated = ProvenanceHealingRotationPolicy.applyPassOutcomes(
            markersByKey = existing,
            offeredKeys = setOf("k:failed-before", "k:healed", "k:first-failure"),
            stillSelectableRows = stillSelectable,
            nowMillis = now,
        )

        // Failed again: count + 1, timestamps refreshed from the live row.
        assertEquals(2, updated.getValue("k:failed-before").failureCount)
        assertEquals(now, updated.getValue("k:failed-before").lastFailedAtMillis)
        assertEquals(10L, updated.getValue("k:failed-before").rowLastSeenAtMillis)
        // First failure: a marker appears from nothing (count 0 -> 1).
        assertEquals(1, updated.getValue("k:first-failure").failureCount)
        assertEquals(20L, updated.getValue("k:first-failure").rowLastSeenAtMillis)
        // Healed: the marker is GONE.
        assertFalse(updated.containsKey("k:healed"))
        // Not offered: carried through untouched.
        assertEquals(existing.getValue("k:not-offered"), updated.getValue("k:not-offered"))
    }

    @Test
    fun `bound drops expired markers and caps the map at the newest failures`() {
        val now = 100_000_000L
        // 5002 fresh markers (lastFailedAtMillis 1..5002) + 1 expired one.
        val fresh = (1..5_002).map {
            ProvenanceHealingFailureMarker("k:$it", 1, now - it.toLong(), 1L)
        }
        val expired = ProvenanceHealingFailureMarker(
            "k:expired",
            3,
            now - ProvenanceHealingRotationPolicy.MARKER_EXPIRY_MILLIS,
            1L,
        )

        val bounded = ProvenanceHealingRotationPolicy.bound(fresh + expired, now)

        // The expired entry is gone; the cap keeps the NEWEST 5000
        // failures (the two oldest fresh markers are evicted).
        assertEquals(ProvenanceHealingRotationPolicy.MAX_TRACKED_KEYS, bounded.size)
        assertFalse(bounded.any { it.localMediaKey == "k:expired" })
        assertEquals(now - 1L, bounded.first().lastFailedAtMillis)
        assertEquals(now - 5_000L, bounded.last().lastFailedAtMillis)
    }

    @Test
    fun `staleness is decided by the recorded row lastSeen versus the current one`() {
        val marker = ProvenanceHealingFailureMarker("k:1", 3, 1_000L, 100L)

        assertFalse(ProvenanceHealingRotationPolicy.isStaleFor(marker, 100L))
        assertTrue(ProvenanceHealingRotationPolicy.isStaleFor(marker, 101L))
    }
}
