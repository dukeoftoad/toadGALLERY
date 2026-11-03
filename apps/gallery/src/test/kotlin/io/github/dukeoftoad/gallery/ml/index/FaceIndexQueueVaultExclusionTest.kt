package io.github.dukeoftoad.gallery.ml.index

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.InMemoryMediaCatalogue
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GATE 1 of the r28 three-gate vault-exclusion proof (Workstream 8,
 * Phase 5 hardening): vault items are excluded from the face index AT
 * ENQUEUE --- plus the cleanup half the same gate owns: a photo that
 * moves INTO the vault after it was indexed has its face rows
 * orphan-DELETED and its queue bookkeeping dropped.
 *
 * The mechanism under test (no new production code --- this suite pins
 * the existing contract end to end):
 *
 *  - a vault move DELETES the MediaStore row (the vault's sealed blob
 *    lives in the app-private vault directory, never in MediaStore),
 *    so the Phase 4 scan pipeline TOMBSTONES the key and the
 *    catalogue's live records stop containing it. The queue's enqueue
 *    mapping ([eligibleCandidates]) is driven by
 *    `MediaCatalogue.allRecords()` --- a vault item is therefore
 *    unrepresentable as a candidate BY CONSTRUCTION. This suite pins
 *    that construction with the REAL [InMemoryMediaCatalogue] (the
 *    reference implementation Room must match): upsert -> enqueued;
 *    tombstone (the vault-move model) -> gone from candidates, no
 *    re-index, no read, no bytes;
 *  - an item that leaves the listing while the store still holds its
 *    face rows becomes a PLAN ORPHAN ([FaceIndexPlan.orphanFileIds]),
 *    and the processor deletes those rows BEFORE any inference work
 *    (the r24 ordering) --- so a vault move actively PURGES the moved
 *    photo's embeddings, keeping the store vault-free, not merely
 *    stopping new writes;
 *  - the queue's own bookkeeping ([FaceIndexedState]) for the moved
 *    photo is stale by the same token and must not survive the save.
 *
 * The other two gates have their own focused suites:
 * `VaultExclusionReadGateTest` (gate 2, pre-byte-read) and
 * `PeopleVaultExclusionPersistenceTest` (gate 3, pre-persistence).
 * See `docs/phase-5/hardening.md` for the three-gate map.
 */
class FaceIndexQueueVaultExclusionTest {

    // Helpers ------------------------------------------------------------------

    private fun record(
        key: String,
        mimeType: String = "image/jpeg",
        sizeBytes: Long = 100L,
        dateAddedMillis: Long = 5_000L,
    ): CatalogueRecord = CatalogueRecord(
        localMediaKey = key,
        canonicalUri = "content://media/external/images/${key.substringAfter(':')}",
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        dateModifiedMillis = 1_000L,
        dateAddedMillis = dateAddedMillis,
    )

    /** A finished index run's state record for [key] / [fileId]. */
    private fun indexedState(key: String, fileId: Long) = FaceIndexedState(
        localMediaKey = key,
        fileId = fileId,
        revision = io.github.dukeoftoad.gallery.data.media.revision.MediaRevision(
            sizeBytes = 100L,
            dateModifiedMillis = 1_000L,
        ),
        indexedAtMillis = 2_000L,
    )

    /** The queue's engine seam, recording every call (the r24 fake shape). */
    private class RecordingEngine : IndexCoordinator.FaceIndexEngine {
        val detectCalls = mutableListOf<String>()
        val putCalls = mutableListOf<Pair<Long, FloatArray>>()
        val deleteCalls = mutableListOf<LongArray>()
        var clusterCalls = 0

        override fun detectFaceEmbeddings(jpeg: ByteArray): List<MlEngine.FaceEmbedding> {
            detectCalls.add(String(jpeg))
            return emptyList()
        }

        override fun putFace(fileId: Long, embedding: FloatArray): Long {
            putCalls.add(fileId to embedding)
            return putCalls.size.toLong()
        }

        override fun faceCount(): Long = 0L

        override fun clusterFaces(distanceThreshold: Double): MlEngine.ClusterSummary {
            clusterCalls++
            return MlEngine.ClusterSummary(0, 0, emptyList())
        }

        override fun indexedFaceFileIds(): List<Long> = emptyList()

        override fun deleteFacesForFiles(fileIds: LongArray): Boolean {
            deleteCalls.add(fileIds)
            return true
        }
    }

    private fun inMemoryStateStore(vararg states: FaceIndexedState) =
        object : FaceIndexStateStore {
            var current: List<FaceIndexedState> = states.toList()
            var saved: List<FaceIndexedState>? = null
            override suspend fun load(): List<FaceIndexedState> = current
            override suspend fun save(states: List<FaceIndexedState>) {
                saved = states
                current = states
            }
        }

    // The enqueue mapping --------------------------------------------------------

    /**
     * The enqueue filter: only JPEG records on an INDEXABLE volume
     * (external primary, the r23/r24 boundary) become candidates ---
     * and a vault item is not a catalogue record AT ALL (vault blobs
     * are app-private files; MediaStore never lists them), so there is
     * no shape in which one could ride in. This test pins the filter
     * with the catalogue's real reference implementation supplying the
     * records.
     */
    @Test
    fun `enqueue maps only indexable-volume JPEGs and orders newest first`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record("external_primary:1", dateAddedMillis = 1_000L),
                record("external_primary:2", mimeType = "video/mp4"), // not a JPEG
                record("external_primary:3", mimeType = "image/png"), // not a JPEG
                record("removable-sd:4"), // wrong volume: id COLLISION boundary
                record("external_primary:not-a-number"), // malformed key: unparseable identity
            ),
        )

        val candidates = eligibleCandidates(catalogue.allRecords())

        assertEquals(listOf("external_primary:1"), candidates.map { it.localMediaKey })
        assertEquals(listOf(1L), candidates.map { it.fileId })
        assertEquals(
            "the candidate's content Uri is the catalogue row's canonical Uri --- the ONLY way " +
                "the queue can ever ask for bytes",
            listOf("content://media/external/images/1"),
            candidates.map { it.uri },
        )
    }

    /**
     * THE vault-move model at the enqueue gate: a move into the vault
     * deletes the MediaStore row, the scan tombstones the key, and the
     * catalogue's live records stop containing the photo --- so the
     * next queue run's candidate list does not contain it, in either
     * direction the exclusion can fail:
     *
     *  - it must NOT be re-indexed (absent is not CONTENT_CHANGED);
     *  - its record must not linger as a stale candidate.
     */
    @Test
    fun `a vault move removes the photo from the enqueue candidates entirely`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record("external_primary:1", dateAddedMillis = 9_000L),
                record("external_primary:2", dateAddedMillis = 8_000L),
                record("external_primary:3", dateAddedMillis = 7_000L),
            ),
        )
        assertEquals(3, eligibleCandidates(catalogue.allRecords()).size)

        // Photo 2 moves to the vault: MediaStore deletes the row, the
        // scan pipeline tombstones the key (the same transition the
        // vault's two-phase move drives through the reconciler).
        catalogue.tombstone(listOf("external_primary:2"), atMillis = 10_000L)

        val candidates = eligibleCandidates(catalogue.allRecords())
        assertEquals(
            "the vault-moved photo must be absent from the candidates --- not re-enqueued, " +
                "not partially present",
            listOf("external_primary:1", "external_primary:3"),
            candidates.map { it.localMediaKey },
        )
    }

    // The cleanup half ------------------------------------------------------------

    /**
     * The active purge: photo 2 was indexed (face rows in the store +
     * a queue state record), then moved to the vault. The next plan
     * must name its file id as an ORPHAN and its key as STALE
     * bookkeeping, and the processor must DELETE the store rows
     * BEFORE any inference --- with zero byte reads, because the
     * plan's to-index list is empty (the other two photos are
     * UNCHANGED).
     */
    @Test
    fun `an indexed photo moved to the vault is orphan-deleted before any inference`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record("external_primary:1", dateAddedMillis = 9_000L),
                record("external_primary:2", dateAddedMillis = 8_000L),
                record("external_primary:3", dateAddedMillis = 7_000L),
            ),
        )
        val store = inMemoryStateStore(
            indexedState("external_primary:1", 1),
            indexedState("external_primary:2", 2),
            indexedState("external_primary:3", 3),
        )
        // Photo 2 moves to the vault: row deleted, key tombstoned.
        catalogue.tombstone(listOf("external_primary:2"), atMillis = 10_000L)

        val engine = RecordingEngine()
        val candidates = eligibleCandidates(catalogue.allRecords())
        val plan = FaceIndexQueuePlanner.plan(
            candidates = candidates,
            states = store.load(),
            storeIndexedFileIds = setOf(1L, 2L, 3L), // the store's truth: 2 still has rows
        )

        // The plan's shape: nothing to index (1 and 3 are UNCHANGED),
        // photo 2's rows are orphaned, its bookkeeping is stale.
        assertTrue("nothing may be re-indexed: the surviving photos are unchanged", plan.toIndex.isEmpty())
        assertEquals(2, plan.skippedUpToDate)
        assertEquals(listOf(2L), plan.orphanFileIds)
        assertEquals(setOf("external_primary:2"), plan.staleStateKeys)

        val outcome = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined).processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { fail("no bytes may be read: the plan indexes nothing (vault exclusion at enqueue)") },
            nowMillis = { 11_000L },
        )

        // The moved photo's rows were deleted --- before any detection:
        assertEquals(1, engine.deleteCalls.size)
        assertTrue(engine.deleteCalls.single().contentEquals(longArrayOf(2L)))
        assertEquals("no inference may run in a vault-cleanup-only plan", 0, engine.detectCalls.size)
        assertEquals(0, engine.putCalls.size)
        assertEquals(1, outcome.orphanRowsDeletedFor)
        // And the queue's bookkeeping for the moved photo is gone:
        assertEquals(
            setOf("external_primary:1", "external_primary:3"),
            store.saved!!.map { it.localMediaKey }.toSet(),
        )
    }

    /**
     * The never-indexed case: a photo moved to the vault BEFORE the
     * queue ever reached it leaves NOTHING to clean --- no store rows
     * (its read would have failed or never been queued), no state
     * record. The enqueue exclusion is complete on its own for this
     * shape; the plan must be empty rather than inventing work.
     */
    @Test
    fun `a photo moved to the vault before indexing leaves nothing to clean`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record("external_primary:1"), record("external_primary:2")))
        catalogue.tombstone(listOf("external_primary:2"), atMillis = 10_000L)

        val plan = FaceIndexQueuePlanner.plan(
            candidates = eligibleCandidates(catalogue.allRecords()),
            states = listOf(indexedState("external_primary:1", 1)), // only 1 was ever indexed
            storeIndexedFileIds = setOf(1L), // the store never held 2's rows
        )

        assertTrue("a never-indexed vault move must produce an empty plan", plan.isEmpty)
        assertTrue(plan.orphanFileIds.isEmpty())
        assertTrue(plan.staleStateKeys.isEmpty())
    }

    /**
     * The mid-batch shape: photo 2 was enqueued (plan formed while it
     * was still public), then moved to the vault BEFORE its bytes were
     * read. The production read seam (the content resolver) yields
     * null for the deleted row, the processor counts it unreadable,
     * and NOTHING is persisted for it --- no face rows, no state
     * record. The NEXT run's fresh candidate list (gate 1) drops it
     * entirely, so the photo can never re-enter the queue.
     */
    @Test
    fun `a vault move between enqueue and read yields no rows and no state record`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record("external_primary:1"), record("external_primary:2")))

        val plan = FaceIndexQueuePlanner.plan(
            candidates = eligibleCandidates(catalogue.allRecords()),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        assertEquals(setOf("external_primary:1", "external_primary:2"), plan.toIndex.map { it.localMediaKey }.toSet())

        // Photo 2's move lands between plan and read: the resolver read
        // for its (now deleted) row comes back empty --- null bytes.
        val engine = RecordingEngine()
        val store = inMemoryStateStore()
        val outcome = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined).processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { candidate ->
                if (candidate.localMediaKey == "external_primary:2") null else candidate.uri.toByteArray()
            },
            nowMillis = { 11_000L },
        )

        assertEquals(1, outcome.unreadableThisRun)
        assertEquals(1, outcome.indexedThisRun)
        // Photo 1 read + zero faces detected (the recording engine
        // detects none); photo 2 never reached the engine:
        assertEquals(listOf("content://media/external/images/1"), engine.detectCalls)
        assertEquals(0, engine.putCalls.size)
        // No state record for the vault-moved photo (it stays retried
        // in principle --- but the NEXT plan's candidate list drops it):
        assertEquals(setOf("external_primary:1"), store.saved!!.map { it.localMediaKey }.toSet())

        // And the next run (fresh candidates, vault move now visible):
        catalogue.tombstone(listOf("external_primary:2"), atMillis = 12_000L)
        val nextPlan = FaceIndexQueuePlanner.plan(
            candidates = eligibleCandidates(catalogue.allRecords()),
            states = store.saved!!,
            storeIndexedFileIds = emptySet(),
        )
        assertFalse("the vault-moved photo must not re-enter the queue", nextPlan.toIndex.isNotEmpty())
        assertTrue(nextPlan.isEmpty)
    }
}
