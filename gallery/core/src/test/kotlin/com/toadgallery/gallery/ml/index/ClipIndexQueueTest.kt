package com.toadgallery.gallery.ml.index

import com.toadgallery.gallery.data.diag.DiagLog
import com.toadgallery.gallery.data.media.revision.MediaRevision
import com.toadgallery.gallery.ml.engine.MlEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        /** Candidate uris whose embedding throws (r51, audit C-5). */
        var embedFaultFor: Set<String> = emptySet()

        private fun embedding(seed: Int) = FloatArray(512) { (it + seed).toFloat() }

        override fun clipImageEmbedding(jpeg: ByteArray): FloatArray {
            val uri = String(jpeg)
            if (uri in embedFaultFor) {
                throw IllegalStateException("native clip decode fault for $uri")
            }
            embedCalls.add(uri)
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
        // r51 (audit C-9): the dead reIndexKeys set is gone --- delete-first
        // is unconditional (putClip even upserts), so a CONTENT_CHANGED
        // candidate needs no extra bookkeeping to have its stale row dropped.
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
        // up-to-date (the terminal card stays honest about what skipped).
        // The store truth is the honest post-run read: c's row is in the
        // store (the r51 C-3 cross-check would re-plan it otherwise).
        val nextPlan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = setOf(3L),
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
            storeIndexedFileIds = setOf(3L),
        )
        assertEquals(listOf("b"), replan.toIndex.map { it.localMediaKey })
    }

    @Test
    fun `a refused putClip terminally skips the photo, never a silent drop from the index`() = runBlocking {
        // r51 (audit C-5): the engine's own success flag refused the
        // insert --- still a FAULT, never a silent drop, but the fault is
        // now contained per-photo: the deliberate IllegalStateException
        // rides the engine-fault catch, so THIS photo terminally skips
        // (counted, logged, held until its revision changes) and the batch
        // completes instead of wedging at this head item forever (a
        // refused insert is deterministic per photo --- no retry could fix
        // it).
        val engine = FakeEngine().apply { refusePutFor = setOf(2L) }
        val store = FakeStateStore()
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )

        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        val outcome = try {
            ClipIndexQueueProcessor(engine, Dispatchers.Unconfined).processBatch(
                plan = plan,
                stateStore = store,
                readBytes = { bytesOf(it) },
                nowMillis = { 1L },
            )
        } finally {
            DiagLog.sink = stockSink
        }

        // The batch completed: c was still embedded and put, b was not:
        assertEquals(1, outcome.indexedThisRun)
        assertEquals(1, outcome.engineFailedThisRun)
        assertEquals(listOf("2", "3"), engine.embedCalls.map { it.substringAfterLast('/') })
        assertEquals(listOf(3L), engine.putCalls.map { it.first })
        // The skip record (the F-39 mirror with the engine-fault flag):
        val skipped = store.saved!!.single { it.localMediaKey == "b" }
        assertTrue(skipped.engineFailed)
        assertFalse(skipped.unreadable)
        assertFalse(store.saved!!.single { it.localMediaKey == "c" }.engineFailed)
        assertFalse(outcome.moreRemaining)
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
    fun `an engine fault on one photo terminally skips it and the batch completes`() = runBlocking {
        // r51 (audit C-5), the face queue's mirror: a decodable-but-corrupt
        // JPEG makes clipImageEmbedding throw EVERY run. The pre-r51
        // contract aborted the batch at the first throw, so the same photo
        // sat at the head of the queue through every WorkManager retry and
        // every restart, wedging BOTH queues (they share the store). The
        // per-photo catch now terminally skips it (the F-39 unreadable
        // mirror) and the batch COMPLETES with the photos after it.
        val engine = FakeEngine().apply {
            embedFaultFor = setOf("content://media/external/file/2")
        }
        val store = FakeStateStore()
        val plan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )

        // The engine-fault skip logs one DiagLog line; the default sink
        // touches android.util.Log --- unmocked on the JVM. Swap it for a
        // recorder (the CompositeScanIndexHookTest pattern) and restore it
        // no matter how the test ends.
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        val logged = mutableListOf<String>()
        DiagLog.sink = { _, _, msg -> logged.add(msg) }
        val outcome = try {
            ClipIndexQueueProcessor(engine, Dispatchers.Unconfined).processBatch(
                plan = plan,
                stateStore = store,
                readBytes = { bytesOf(it) },
                nowMillis = { 1L },
            )
        } finally {
            DiagLog.sink = stockSink
        }

        // The batch COMPLETED: c (after the poison b) was still indexed.
        assertEquals(1, outcome.indexedThisRun)
        assertEquals(listOf("3"), engine.embedCalls.map { it.substringAfterLast('/') })
        assertFalse(outcome.moreRemaining)
        // The new counter is reported alongside unreadableThisRun:
        assertEquals(1, outcome.engineFailedThisRun)
        assertEquals(0, outcome.unreadableThisRun)
        // One log line for the one poison photo:
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("b"))
        assertTrue(logged.single().contains("terminal skip"))
        // b leaves a TERMINAL-SKIP record with the engine-fault flag (the
        // F-39 mirror): no clip row, held out until its revision changes.
        val skipped = store.saved!!.single { it.localMediaKey == "b" }
        assertTrue(skipped.engineFailed)
        assertFalse(skipped.unreadable)
        assertFalse(store.saved!!.single { it.localMediaKey == "c" }.engineFailed)

        // The NEXT plan holds b out --- counted as skippedEngineFailed, NOT
        // up-to-date (the outcome stays honest about what skipped):
        val nextPlan = ClipIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = setOf(3L),
        )
        assertTrue(nextPlan.toIndex.isEmpty())
        assertEquals(1, nextPlan.skippedEngineFailed)
        assertEquals(1, nextPlan.skippedUpToDate)

        // A revision change gives the photo a fresh attempt: the skip is a
        // hold, not a ban (a genuinely changed file re-enters delete-first).
        val changed = candidate("b", 2, size = 101L)
        val replan = ClipIndexQueuePlanner.plan(
            candidates = listOf(changed, candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = setOf(3L),
        )
        assertEquals(listOf("b"), replan.toIndex.map { it.localMediaKey })
    }

    @Test
    fun `state records over a missing store plan a full re-index`() {
        // r51 (audit C-3 backstop, the file half): the reset-timeout
        // resurrect shape --- the state file survived while the store it
        // describes was deleted. The old planner diffed every record
        // UNCHANGED and skipped the whole library; the backstop plans the
        // full re-index.
        val dir = tmp.newFolder()
        val stateFile = File(dir, "clip-index-state.json")
        runBlocking { ClipIndexStateFile(stateFile).save(listOf(state("a", 1), state("b", 2))) }
        val candidates = listOf(candidate("a", 1), candidate("b", 2), candidate("new", 3))

        // The backstop logs one DiagLog line (same JVM sink discipline).
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        val plan = try {
            ClipIndexQueuePlanner.plan(
                candidates = candidates,
                states = runBlocking { ClipIndexStateFile(stateFile).load() },
                storeIndexedFileIds = emptySet(),
                stateStoreFile = stateFile,
            )
        } finally {
            DiagLog.sink = stockSink
        }

        // Everything is re-index work --- no UNCHANGED skips:
        assertEquals(listOf("a", "b", "new"), plan.toIndex.map { it.localMediaKey })
        assertEquals(0, plan.skippedUpToDate)
        assertEquals(0, plan.skippedMetadataOnly)
        assertEquals(0, plan.skippedUnreadable)
        assertEquals(0, plan.skippedEngineFailed)
        assertFalse(plan.isEmpty)

        // The control: with the store file PRESENT and its rows live (the
        // normal world), the same inputs keep the plain diff mapping ---
        // a and b skip, only the never-processed "new" indexes. (The row
        // truth must be healthy too: the store-truth half of the backstop
        // --- pinned by its own test below --- re-plans a live record
        // whose row is gone even when the file exists.)
        File(dir, ML_INDEX_STORE_FILE_NAME).writeText("sqlite")
        val normalPlan = ClipIndexQueuePlanner.plan(
            candidates = candidates,
            states = runBlocking { ClipIndexStateFile(stateFile).load() },
            storeIndexedFileIds = setOf(1L, 2L),
            stateStoreFile = stateFile,
        )
        assertEquals(listOf("new"), normalPlan.toIndex.map { it.localMediaKey })
        assertEquals(2, normalPlan.skippedUpToDate)
    }

    @Test
    fun `live state records whose store rows are gone plan a re-index, not a skip`() {
        // r51 (audit C-3 backstop, the store-truth half): after the
        // reset-timeout resurrect the worker's own store-open RECREATES an
        // empty ml-index.sqlite before the planner runs, so a File check
        // can no longer see the deletion --- but the store truth the
        // planner already receives is empty while the resurrected records
        // claim indexed photos. Every CLIP-indexed photo carries exactly
        // one row, so a live record with no row is a definite
        // inconsistency: plan the delete-first re-index. (The terminal-skip
        // records are exempt --- they never carry rows by design.)
        val states = listOf(
            state("a", 1), // row gone -> re-index
            state("b", 2).copy(unreadable = true), // terminal skip stays a skip
            state("c", 3).copy(engineFailed = true), // terminal skip stays a skip
        )
        val candidates = listOf(candidate("a", 1), candidate("b", 2), candidate("c", 3))

        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        val plan = try {
            ClipIndexQueuePlanner.plan(
                candidates = candidates,
                states = states,
                storeIndexedFileIds = emptySet(), // the resurrected store is empty
            )
        } finally {
            DiagLog.sink = stockSink
        }

        assertEquals(listOf("a"), plan.toIndex.map { it.localMediaKey })
        assertEquals(0, plan.skippedUpToDate)
        assertEquals(1, plan.skippedUnreadable)
        assertEquals(1, plan.skippedEngineFailed)

        // The control: with the row present, the same record skips.
        val healthy = ClipIndexQueuePlanner.plan(
            candidates = candidates,
            states = states,
            storeIndexedFileIds = setOf(1L),
        )
        assertTrue(healthy.toIndex.isEmpty())
        assertEquals(1, healthy.skippedUpToDate)
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
            // r51 (audit C-5): the engine-fault flag rides the same round
            // trip (the same additive-optional-field discipline).
            state("d", 4, indexedAt = 40L).copy(engineFailed = true),
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
            com.toadgallery.gallery.data.media.revision.RevisionDelta.UNCHANGED,
            loaded.revision.diff(candidateNow.revision),
        )
    }
}
