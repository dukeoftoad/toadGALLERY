package io.github.dukeoftoad.gallery.ml.index

import io.github.dukeoftoad.gallery.data.media.revision.MediaRevision
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

/**
 * JVM tests for the r26 semantic queue core ([ClipIndexQueuePlanner] +
 * [ClipIndexQueueProcessor] over the [ClipIndexEngine] seam, and
 * [ClipIndexStateFile] on a real temp directory) --- never the JNI
 * object, never a pretend native. The suite pins the round's own
 * contract (the r24 `FaceIndexQueueTest` shape, CLIP flavor):
 *
 *  - the PLAN mapping: UNCHANGED/METADATA_CHANGED skip, CONTENT_CHANGED
 *    re-indexes (delete-first), never-processed indexes, orphans and
 *    stale bookkeeping are named for cleanup;
 *  - the BATCH contract: bounded slice, per-photo delete-first + ONE
 *    whole-photo putClip (no per-face rows), whole-photo state records,
 *    unreadable photos skip without a record, states persisted on
 *    completion AND cancellation, the reconciliation fill exactly once
 *    at drain and never mid-queue, a refused putClip surfacing as a
 *    fault (never a silent drop), and the between-photos cancellation
 *    rule carried over;
 *  - the STATE FILE: round-trip, atomic overwrite, corrupt/missing file
 *    degrades to empty (re-index stays correct), version discipline.
 */
class ClipIndexQueueTest {

    /** Real temp directory: the state file's IO behavior is under test. */
    @get:Rule
    val tmp = TemporaryFolder()

    // Test helpers -----------------------------------------------------------------

    private fun revision(
        size: Long = 100L,
        modified: Long = 1_000L,
        generation: String? = null,
        metadataWrites: Int = 0,
    ) = MediaRevision(
        sizeBytes = size,
        dateModifiedMillis = modified,
        generationToken = generation,
        metadataWriteRevision = metadataWrites,
    )

    private fun candidate(
        key: String,
        fileId: Long,
        size: Long = 100L,
        modified: Long = 1_000L,
        generation: String? = null,
        metadataWrites: Int = 0,
        added: Long = 5_000L,
    ) = ClipIndexCandidate(
        localMediaKey = key,
        fileId = fileId,
        revision = revision(size, modified, generation, metadataWrites),
        uri = "content://media/external/file/$fileId",
        dateAddedMillis = added,
    )

    private fun state(
        key: String,
        fileId: Long,
        size: Long = 100L,
        modified: Long = 1_000L,
        generation: String? = null,
        metadataWrites: Int = 0,
        indexedAt: Long = 2_000L,
    ) = ClipIndexedState(
        localMediaKey = key,
        fileId = fileId,
        revision = revision(size, modified, generation, metadataWrites),
        indexedAtMillis = indexedAt,
    )

    /** In-memory state store: records saves, answers scripted loads. */
    private class FakeStateStore(
        var initial: List<ClipIndexedState> = emptyList(),
    ) : ClipIndexStateStore {
        var saved: List<ClipIndexedState>? = null
        var saveCalls = 0

        override suspend fun load(): List<ClipIndexedState> = initial

        override suspend fun save(states: List<ClipIndexedState>) {
            saveCalls++
            saved = states
        }
    }

    /**
     * Scripted engine: deterministic embeddings, records
     * delete/embed/put calls. ONE embedding per photo is the whole
     * write story (no per-face rows).
     */
    private class FakeEngine : ClipIndexEngine {
        val embedCalls = mutableListOf<String>() // candidate uris
        val putCalls = mutableListOf<Pair<Long, FloatArray>>()
        val deleteCalls = mutableListOf<LongArray>()
        var fillCalls = 0
        var fillResult = MlEngine.ClipFillReport(
            outcome = "completed",
            rows = 3,
            indexed = 3,
            skipped = 0,
            resumed = false,
        )
        var clipCountResult = 3L
        var indexedFileIdsResult: List<Long> = emptyList()
        var refusePutFor: Set<Long> = emptySet()

        private fun embedding(seed: Int) = FloatArray(512) { (it + seed).toFloat() }

        override fun clipImageEmbedding(jpeg: ByteArray): FloatArray {
            embedCalls.add(String(jpeg))
            return embedding(embedCalls.size)
        }

        override fun putClip(fileId: Long, embedding: FloatArray): Boolean {
            if (fileId in refusePutFor) return false
            putCalls.add(fileId to embedding)
            return true
        }

        override fun deleteClipsForFiles(fileIds: LongArray): Boolean {
            deleteCalls.add(fileIds)
            return true
        }

        override fun clipIndexedFileIds(): List<Long> = indexedFileIdsResult

        override fun clipCount(): Long = clipCountResult

        override fun fillClipIndex(): MlEngine.ClipFillReport {
            fillCalls++
            return fillResult
        }
    }

    private fun bytesOf(candidate: ClipIndexCandidate): ByteArray = candidate.uri.toByteArray()

    // Planner tests -----------------------------------------------------------------

    @Test
    fun `unchanged and metadata-only revisions skip, content changes re-index`() {
        val candidates = listOf(
            candidate("a", 1), // state identical -> skip
            candidate("b", 2, metadataWrites = 1), // METADATA_CHANGED -> skip AI
            candidate("c", 3, size = 200L), // CONTENT_CHANGED -> re-index
            candidate("d", 4), // no state -> index
        )
        val states = listOf(
            state("a", 1),
            state("b", 2),
            state("c", 3),
        )

        val plan = ClipIndexQueuePlanner.plan(candidates, states, storeIndexedFileIds = setOf(1, 2, 3))

        assertEquals(listOf("c", "d"), plan.toIndex.map { it.localMediaKey })
        assertEquals(setOf("c"), plan.reIndexKeys)
        assertEquals(1, plan.skippedUpToDate)
        assertEquals(1, plan.skippedMetadataOnly)
        assertTrue(plan.orphanFileIds.isEmpty())
        assertTrue(plan.staleStateKeys.isEmpty())
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `store files without candidates are orphans and state records without candidates are stale`() {
        val candidates = listOf(candidate("a", 1))
        val states = listOf(state("a", 1), state("gone", 9))

        val plan = ClipIndexQueuePlanner.plan(candidates, states, storeIndexedFileIds = setOf(1, 8, 9))

        // 8 has rows but no candidate (deleted/trashed/vault-moved): orphan.
        // 9 has no candidate either --- its rows are orphaned too, and its
        // state record is stale bookkeeping.
        assertEquals(listOf(8L, 9L), plan.orphanFileIds.sorted())
        assertEquals(setOf("gone"), plan.staleStateKeys)
        // "a" is up to date; nothing to index:
        assertTrue(plan.toIndex.isEmpty())
        assertTrue(plan.reIndexKeys.isEmpty())
        assertEquals(1, plan.skippedUpToDate)
        // Orphan cleanup IS real work for the plan:
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `an empty world plans empty`() {
        val plan = ClipIndexQueuePlanner.plan(emptyList(), emptyList(), emptySet())
        assertTrue(plan.isEmpty)
        assertTrue(plan.toIndex.isEmpty())
        assertTrue(plan.orphanFileIds.isEmpty())
    }

    // Processor tests -----------------------------------------------------------------

    @Test
    fun `a batch processes photos delete-first with ONE putClip per photo and records states`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore(
            initial = listOf(state("a", 1)), // a up-to-date, b/c/d queued
        )
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(
                candidate("a", 1),
                candidate("b", 2, added = 6_000L),
                candidate("c", 3, added = 5_500L),
                candidate("d", 4, added = 4_000L),
            ),
            states = store.initial,
            storeIndexedFileIds = setOf(1),
        )
        val processor = ClipIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val progress = mutableListOf<ClipIndexQueueProcessor.BatchProgress>()
        val outcome = processor.processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { bytesOf(it) },
            batchSize = 2,
            nowMillis = { 7_000L },
            onProgress = { progress.add(it) },
        )

        // Bounded batch: the two NEWEST queued photos (b, c), not a and d
        // (the fake engine records each photo's uri bytes):
        assertEquals(
            listOf("content://media/external/file/2", "content://media/external/file/3"),
            engine.embedCalls,
        )
        // Delete-first per photo (b, c), even though putClip upserts:
        assertEquals(2, engine.deleteCalls.size)
        assertTrue(engine.deleteCalls[0].contentEquals(longArrayOf(2L)))
        assertTrue(engine.deleteCalls[1].contentEquals(longArrayOf(3L)))
        // ONE embedding per photo --- no per-face rows, no clustering:
        assertEquals(2, engine.putCalls.size)
        assertEquals(2L, engine.putCalls[0].first)
        assertEquals(3L, engine.putCalls[1].first)
        assertEquals(512, engine.putCalls[0].second.size)

        // Progress: after each photo, with the batch and queue depth:
        assertEquals(
            listOf(
                ClipIndexQueueProcessor.BatchProgress(1, 2, 3),
                ClipIndexQueueProcessor.BatchProgress(2, 2, 3),
            ),
            progress,
        )

        // Outcome: queue depth 3, 2 done this run, more remaining, NO
        // reconciliation fill mid-queue:
        assertEquals(3, outcome.queueDepth)
        assertEquals(2, outcome.indexedThisRun)
        assertTrue(outcome.moreRemaining)
        assertNull(outcome.fillReport)
        assertEquals(0, engine.fillCalls)

        // State saved once, whole: a's record kept, b and c stamped with
        // the injected clock, d NOT recorded (untouched this batch):
        assertEquals(1, store.saveCalls)
        val savedByKey = store.saved!!.associateBy { it.localMediaKey }
        assertEquals(setOf("a", "b", "c"), savedByKey.keys)
        assertEquals(7_000L, savedByKey.getValue("b").indexedAtMillis)
        assertEquals(2L, savedByKey.getValue("b").fileId)
    }

    @Test
    fun `the draining batch fills exactly once and reports the store`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore()
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val processor = ClipIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val outcome = processor.processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { bytesOf(it) },
            nowMillis = { 1L },
        )

        assertFalse(outcome.moreRemaining)
        assertEquals(1, engine.fillCalls)
        assertEquals(
            MlEngine.ClipFillReport("completed", 3, 3, 0, resumed = false),
            outcome.fillReport,
        )
        assertEquals(3L, outcome.totalClipsInStore)
    }

    @Test
    fun `an empty plan does no work and does not fill`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore()
        val processor = ClipIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val outcome = processor.processBatch(
            plan = ClipIndexQueuePlanner.plan(emptyList(), emptyList(), emptySet()),
            stateStore = store,
            readBytes = { bytesOf(it) },
            nowMillis = { 1L },
        )

        assertEquals(0, outcome.indexedThisRun)
        assertEquals(0, engine.deleteCalls.size)
        assertEquals(0, engine.embedCalls.size)
        assertEquals(0, engine.fillCalls)
        assertNull(outcome.fillReport)
        assertTrue(outcome.moreRemaining.not())
    }

    @Test
    fun `orphan deletes run before inference and stale bookkeeping is dropped on save`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore(
            initial = listOf(state("gone", 9)),
        )
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("a", 1)),
            states = store.initial,
            storeIndexedFileIds = setOf(8, 9),
        )
        val processor = ClipIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val outcome = processor.processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { bytesOf(it) },
            nowMillis = { 1L },
        )

        // Orphan delete first (one chunked call), then a's own delete-first:
        assertEquals(2, engine.deleteCalls.size)
        assertTrue(engine.deleteCalls[0].contentEquals(longArrayOf(8L, 9L)))
        assertTrue(engine.deleteCalls[1].contentEquals(longArrayOf(1L)))
        assertEquals(2, outcome.orphanRowsDeletedFor)
        // Draining a plan with real work fills:
        assertEquals(1, engine.fillCalls)
        // The stale record is gone from the saved state:
        assertTrue(store.saved!!.none { it.localMediaKey == "gone" })
    }

    @Test
    fun `unreadable photos leave a terminal skip record and do not re-enter the queue`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore()
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val processor = ClipIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val outcome = processor.processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { if (it.localMediaKey == "b") null else bytesOf(it) },
            nowMillis = { 1L },
        )

        assertEquals(1, outcome.unreadableThisRun)
        assertEquals(1, outcome.indexedThisRun)
        // Only c was read, embedded and recorded:
        assertEquals(listOf("3"), engine.embedCalls.map { it.substringAfterLast('/') })
        // r50 (F-39): b leaves a TERMINAL-SKIP record (the face queue's
        // mirror fix --- the r26 contract retried unreadable photos
        // forever, so the chain never drained past a batch-sized set of
        // permanently unreadable rows). The record carries no clip row
        // (b never reached the engine) and marks the skip:
        val skipped = store.saved!!.single { it.localMediaKey == "b" }
        assertTrue(skipped.unreadable)
        assertFalse(store.saved!!.single { it.localMediaKey == "c" }.unreadable)
        // The queue drained per the plan:
        assertFalse(outcome.moreRemaining)

        // The NEXT plan holds b out --- counted as skippedUnreadable, NOT
        // up-to-date (the terminal card stays honest about what skipped):
        val nextPlan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = emptySet(),
        )
        assertTrue(nextPlan.toIndex.isEmpty())
        assertEquals(1, nextPlan.skippedUnreadable)
        assertEquals(1, nextPlan.skippedUpToDate)

        // A revision change gives the photo a fresh read: the skip is a
        // hold, not a ban (a genuinely changed file re-enters).
        val changed = candidate("b", 2, size = 101L)
        val replan = ClipIndexQueuePlanner.plan(
            candidates = listOf(changed, candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = emptySet(),
        )
        assertEquals(listOf("b"), replan.toIndex.map { it.localMediaKey })
    }

    @Test
    fun `a refused putClip is a fault, never a silent drop from the index`() {
        val engine = FakeEngine().apply { refusePutFor = setOf(2L) }
        val store = FakeStateStore()
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        try {
            runBlocking {
                ClipIndexQueueProcessor(engine, Dispatchers.Unconfined).processBatch(
                    plan = plan,
                    stateStore = store,
                    readBytes = { bytesOf(it) },
                    nowMillis = { 1L },
                )
            }
            fail("a refused putClip must surface as an engine fault")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("returned false for file 2"))
        }
    }

    @Test
    fun `cancellation between photos still persists the whole-photo records`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore()
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3), candidate("d", 4)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val processor = ClipIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            processor.processBatch(
                plan = plan,
                stateStore = store,
                readBytes = { bytesOf(it) },
                nowMillis = { 42L },
                onProgress = { progress ->
                    if (progress.processedThisRun == 1) currentCoroutineContext().cancel()
                },
            )
        }
        job.join()
        assertTrue("the batch must surface as cancelled", job.isCancelled)

        // Whole photos only: b ran (one embedding), c and d never started,
        // no reconciliation fill ran:
        assertEquals(1, engine.embedCalls.size)
        assertEquals(1, engine.putCalls.size)
        assertEquals(0, engine.fillCalls)
        // The cancelled run still saved b's record (the resume contract):
        assertEquals(1, store.saveCalls)
        assertEquals(setOf("b"), store.saved!!.map { it.localMediaKey }.toSet())
        assertEquals(42L, store.saved!!.single().indexedAtMillis)
    }

    @Test
    fun `engine failures propagate unchanged`() {
        val engine = object : ClipIndexEngine {
            override fun clipImageEmbedding(jpeg: ByteArray): FloatArray =
                throw IllegalStateException("face index store is not open; call openFaceIndex(dir) first")

            override fun putClip(fileId: Long, embedding: FloatArray): Boolean = true
            override fun deleteClipsForFiles(fileIds: LongArray): Boolean = true
            override fun clipIndexedFileIds(): List<Long> = emptyList()
            override fun clipCount(): Long = 0L
            override fun fillClipIndex(): MlEngine.ClipFillReport =
                MlEngine.ClipFillReport("completed", 0, 0, 0, resumed = false)
        }
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        try {
            runBlocking {
                ClipIndexQueueProcessor(engine, Dispatchers.Unconfined).processBatch(
                    plan = plan,
                    stateStore = FakeStateStore(),
                    readBytes = { bytesOf(it) },
                    nowMillis = { 1L },
                )
            }
            fail("the engine's failure shape must propagate")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("not open"))
        }
    }

    // State file tests -----------------------------------------------------------------

    @Test
    fun `the state file round-trips states including nullable revision fields`() {
        val dir = tmp.newFolder()
        val file = ClipIndexStateFile(File(dir, "clip-index-state.json"))
        val states = listOf(
            state("a", 1, generation = "g42", metadataWrites = 3, indexedAt = 10L),
            state("b", 2, generation = null, indexedAt = 20L),
            // r50 (F-39): the terminal-skip flag rides the same round trip
            // (an additive optional field on version 1).
            state("c", 3, indexedAt = 30L).copy(unreadable = true),
        )

        runBlocking { file.save(states) }
        val loaded = runBlocking { file.load() }

        assertEquals(states, loaded)
        // The atomic write leaves no temp file behind:
        assertEquals(setOf("clip-index-state.json"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `saving twice replaces atomically and loading an absent file is empty`() {
        val dir = tmp.newFolder()
        val file = ClipIndexStateFile(File(dir, "clip-index-state.json"))

        assertTrue(runBlocking { file.load() }.isEmpty())

        runBlocking { file.save(listOf(state("a", 1))) }
        runBlocking { file.save(listOf(state("a", 1), state("b", 2))) }
        assertEquals(2, runBlocking { file.load() }.size)
        assertEquals(setOf("clip-index-state.json"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `a corrupt or wrong-version file degrades to empty instead of throwing`() {
        val dir = tmp.newFolder()
        val target = File(dir, "clip-index-state.json")

        // Garbage bytes:
        target.writeText("not json at all")
        assertTrue(runBlocking { ClipIndexStateFile(target).load() }.isEmpty())

        // A future schema version is not ours to interpret:
        target.writeText("""{"version":2,"states":[]}""")
        assertTrue(runBlocking { ClipIndexStateFile(target).load() }.isEmpty())

        // Partial state rows (a hand-edit dropped the file id) are skipped:
        target.writeText(
            """{"version":1,"states":[{"key":"a"},{"key":"b","fileId":2,"sizeBytes":1,"dateModifiedMillis":1,"metadataWriteRevision":0,"indexedAtMillis":5}]}""",
        )
        val loaded = runBlocking { ClipIndexStateFile(target).load() }
        assertEquals(1, loaded.size)
        assertEquals("b", loaded.single().localMediaKey)
    }

    @Test
    fun `revision equality after a round trip still diffs UNCHANGED`() {
        val dir = tmp.newFolder()
        val file = ClipIndexStateFile(File(dir, "clip-index-state.json"))
        val original = state("a", 1, generation = "g7", metadataWrites = 2)
        runBlocking { file.save(listOf(original)) }
        val loaded = runBlocking { file.load() }.single()

        val candidateNow = candidate(
            "a",
            1,
            size = original.revision.sizeBytes,
            modified = original.revision.dateModifiedMillis,
            generation = "g7",
            metadataWrites = 2,
        )
        assertEquals(
            io.github.dukeoftoad.gallery.data.media.revision.RevisionDelta.UNCHANGED,
            loaded.revision.diff(candidateNow.revision),
        )
    }
}
