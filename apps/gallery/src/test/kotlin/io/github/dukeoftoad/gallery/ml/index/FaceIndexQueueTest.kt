package io.github.dukeoftoad.gallery.ml.index

import io.github.dukeoftoad.gallery.data.diag.DiagLog
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
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.File

/**
 * JVM tests for the r24 queue core ([FaceIndexQueuePlanner] +
 * [FaceIndexQueueProcessor] over the [IndexCoordinator.FaceIndexEngine]
 * seam, and [FaceIndexStateFile] on a real temp directory) --- never the
 * JNI object, never a pretend native. The suite pins the round's own
 * contract:
 *
 *  - the PLAN mapping: UNCHANGED/METADATA_CHANGED skip, CONTENT_CHANGED
 *    re-indexes (delete-first), never-processed indexes, orphans and
 *    stale bookkeeping are named for cleanup;
 *  - the BATCH contract: bounded slice, per-photo delete-first +
 *    putFace-per-face, whole-photo state records, unreadable photos skip
 *    without a record, states persisted on completion AND cancellation,
 *    clustering exactly once at drain and never mid-queue, and the r23
 *    between-photos cancellation rule carried over;
 *  - the STATE FILE: round-trip, atomic overwrite, corrupt/missing file
 *    degrades to empty (re-index stays correct), version discipline.
 */
class FaceIndexQueueTest {

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
    ) = FaceIndexCandidate(
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
    ) = FaceIndexedState(
        localMediaKey = key,
        fileId = fileId,
        revision = revision(size, modified, generation, metadataWrites),
        indexedAtMillis = indexedAt,
    )

    /** In-memory state store: records saves, answers scripted loads. */
    private class FakeStateStore(
        var initial: List<FaceIndexedState> = emptyList(),
    ) : FaceIndexStateStore {
        var saved: List<FaceIndexedState>? = null
        var saveCalls = 0

        override suspend fun load(): List<FaceIndexedState> = initial

        override suspend fun save(states: List<FaceIndexedState>) {
            saveCalls++
            saved = states
        }
    }

    /** Scripted engine: deterministic faces, records delete/detect/put. */
    private class FakeEngine : IndexCoordinator.FaceIndexEngine {
        val facesPerPhoto = ArrayDeque(listOf(2, 1, 0, 1))
        val detectCalls = mutableListOf<String>() // candidate uris
        val putCalls = mutableListOf<Pair<Long, FloatArray>>()
        val deleteCalls = mutableListOf<LongArray>()
        var clusterCalls = 0
        var lastThreshold: Double? = null
        var clusterResult = MlEngine.ClusterSummary(faces = 4, clusters = 2, sizes = listOf(3, 1))
        var faceCountResult = 4L
        var indexedFileIdsResult: List<Long> = emptyList()

        /** Candidate uris whose detection throws (r51, audit C-5). */
        var detectFaultFor: Set<String> = emptySet()

        private fun embedding(seed: Float) = FloatArray(192) { it.toFloat() + seed }

        private fun face(seed: Float) = MlEngine.FaceEmbedding(
            x1 = seed,
            y1 = 1f,
            x2 = 2f,
            y2 = 3f,
            score = 0.5f + seed,
            embedding = embedding(seed),
        )

        override fun detectFaceEmbeddings(jpeg: ByteArray): List<MlEngine.FaceEmbedding> {
            val uri = String(jpeg)
            if (uri in detectFaultFor) {
                throw IllegalStateException("native decode fault for $uri")
            }
            detectCalls.add(uri)
            val count = if (facesPerPhoto.isEmpty()) 0 else facesPerPhoto.removeFirst()
            return (0 until count).map { face(it.toFloat()) }
        }

        override fun putFace(fileId: Long, embedding: FloatArray): Long {
            putCalls.add(fileId to embedding)
            return putCalls.size.toLong()
        }

        override fun faceCount(): Long = faceCountResult

        override fun clusterFaces(distanceThreshold: Double): MlEngine.ClusterSummary {
            clusterCalls++
            lastThreshold = distanceThreshold
            return clusterResult
        }

        override fun indexedFaceFileIds(): List<Long> = indexedFileIdsResult

        override fun deleteFacesForFiles(fileIds: LongArray): Boolean {
            deleteCalls.add(fileIds)
            return true
        }
    }

    private fun bytesOf(candidate: FaceIndexCandidate): ByteArray = candidate.uri.toByteArray()

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

        val plan = FaceIndexQueuePlanner.plan(candidates, states, storeIndexedFileIds = setOf(1, 2, 3))

        assertEquals(listOf("c", "d"), plan.toIndex.map { it.localMediaKey })
        // r51 (audit C-9): the dead reIndexKeys set is gone --- delete-first
        // is unconditional, so a CONTENT_CHANGED candidate needs no extra
        // bookkeeping to have its stale rows dropped.
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

        val plan = FaceIndexQueuePlanner.plan(candidates, states, storeIndexedFileIds = setOf(1, 8, 9))

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
        val plan = FaceIndexQueuePlanner.plan(emptyList(), emptyList(), emptySet())
        assertTrue(plan.isEmpty)
        assertTrue(plan.toIndex.isEmpty())
        assertTrue(plan.orphanFileIds.isEmpty())
    }

    // Processor tests -----------------------------------------------------------------

    @Test
    fun `a batch processes photos delete-first with one putFace per face and records states`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore(
            initial = listOf(state("a", 1)), // a up-to-date, b/c/d queued
        )
        val plan = FaceIndexQueuePlanner.plan(
            candidates = listOf(
                candidate("a", 1),
                candidate("b", 2, added = 6_000L),
                candidate("c", 3, added = 5_500L),
                candidate("d", 4, added = 4_000L),
            ),
            states = store.initial,
            storeIndexedFileIds = setOf(1),
        )
        val processor = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val progress = mutableListOf<FaceIndexQueueProcessor.BatchProgress>()
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
            engine.detectCalls,
        )
        // Delete-first per photo (b, c), even though neither had rows:
        assertEquals(2, engine.deleteCalls.size)
        assertTrue(engine.deleteCalls[0].contentEquals(longArrayOf(2L)))
        assertTrue(engine.deleteCalls[1].contentEquals(longArrayOf(3L)))
        // Faces: b=2, c=1 -> 3 puts keyed by the right file ids:
        assertEquals(3, engine.putCalls.size)
        assertEquals(2L, engine.putCalls[0].first)
        assertEquals(2L, engine.putCalls[1].first)
        assertEquals(3L, engine.putCalls[2].first)

        // Progress: after each photo, with the batch and queue depth:
        assertEquals(
            listOf(
                FaceIndexQueueProcessor.BatchProgress(1, 2, 3, 2),
                FaceIndexQueueProcessor.BatchProgress(2, 2, 3, 3),
            ),
            progress,
        )

        // Outcome: queue depth 3, 2 done this run, more remaining, NO
        // clustering mid-queue:
        assertEquals(3, outcome.queueDepth)
        assertEquals(2, outcome.indexedThisRun)
        assertEquals(3, outcome.facesWrittenThisRun)
        assertTrue(outcome.moreRemaining)
        assertNull(outcome.clusterSummary)
        assertEquals(0, engine.clusterCalls)

        // State saved once, whole: a's record kept, b and c stamped with
        // the injected clock, d NOT recorded (untouched this batch):
        assertEquals(1, store.saveCalls)
        val savedByKey = store.saved!!.associateBy { it.localMediaKey }
        assertEquals(setOf("a", "b", "c"), savedByKey.keys)
        assertEquals(7_000L, savedByKey.getValue("b").indexedAtMillis)
        assertEquals(2L, savedByKey.getValue("b").fileId)
    }

    @Test
    fun `the draining batch clusters exactly once at the plan threshold and reports the store`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore()
        val plan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val processor = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val outcome = processor.processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { bytesOf(it) },
            distanceThreshold = 0.30,
            nowMillis = { 1L },
        )

        assertFalse(outcome.moreRemaining)
        assertEquals(1, engine.clusterCalls)
        assertEquals(0.30, engine.lastThreshold!!, 0.0)
        assertEquals(MlEngine.ClusterSummary(4, 2, listOf(3, 1)), outcome.clusterSummary)
        assertEquals(4L, outcome.totalFacesInStore)
    }

    @Test
    fun `an empty plan does no work and does not cluster`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore()
        val processor = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val outcome = processor.processBatch(
            plan = FaceIndexQueuePlanner.plan(emptyList(), emptyList(), emptySet()),
            stateStore = store,
            readBytes = { bytesOf(it) },
            nowMillis = { 1L },
        )

        assertEquals(0, outcome.indexedThisRun)
        assertEquals(0, engine.deleteCalls.size)
        assertEquals(0, engine.detectCalls.size)
        assertEquals(0, engine.clusterCalls)
        assertNull(outcome.clusterSummary)
        assertTrue(outcome.moreRemaining.not())
    }

    @Test
    fun `orphan deletes run before inference and stale bookkeeping is dropped on save`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore(
            initial = listOf(state("gone", 9)),
        )
        val plan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("a", 1)),
            states = store.initial,
            storeIndexedFileIds = setOf(8, 9),
        )
        val processor = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined)

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
        // Draining a plan with real work clusters:
        assertEquals(1, engine.clusterCalls)
        // The stale record is gone from the saved state:
        assertTrue(store.saved!!.none { it.localMediaKey == "gone" })
    }

    @Test
    fun `unreadable photos leave a terminal skip record and do not re-enter the queue`() = runBlocking {
        val engine = FakeEngine()
        val store = FakeStateStore()
        val plan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val processor = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined)

        val outcome = processor.processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { if (it.localMediaKey == "b") null else bytesOf(it) },
            nowMillis = { 1L },
        )

        assertEquals(1, outcome.unreadableThisRun)
        assertEquals(1, outcome.indexedThisRun)
        // Only c was read, embedded and recorded:
        assertEquals(listOf("3"), engine.detectCalls.map { it.substringAfterLast('/') })
        // r50 (F-39): b leaves a TERMINAL-SKIP record instead of staying
        // state-less --- the r24 contract retried it forever, so a batch-
        // sized set of permanently unreadable rows (DRM stubs, revoked
        // grants, deleted rows) re-enqueued the chain endlessly with zero
        // progress. The record carries no faces (b never reached the
        // engine) and marks the skip:
        val skipped = store.saved!!.single { it.localMediaKey == "b" }
        assertTrue(skipped.unreadable)
        assertFalse(store.saved!!.single { it.localMediaKey == "c" }.unreadable)
        // The queue drained per the plan:
        assertFalse(outcome.moreRemaining)

        // The NEXT plan holds b out --- counted as skippedUnreadable, NOT
        // up-to-date (the terminal card stays honest about what skipped):
        val nextPlan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = emptySet(),
        )
        assertTrue(nextPlan.toIndex.isEmpty())
        assertEquals(1, nextPlan.skippedUnreadable)
        assertEquals(1, nextPlan.skippedUpToDate)

        // A revision change gives the photo a fresh read: the skip is a
        // hold, not a ban (a genuinely changed file re-enters delete-first).
        val changed = candidate("b", 2, size = 101L)
        val replan = FaceIndexQueuePlanner.plan(
            candidates = listOf(changed, candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = emptySet(),
        )
        assertEquals(listOf("b"), replan.toIndex.map { it.localMediaKey })
    }

    @Test
    fun `cancellation between photos still persists the whole-photo records`() = runBlocking {
        val engine = FakeEngine() // 2 faces, 1 face, ...
        val store = FakeStateStore()
        val plan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3), candidate("d", 4)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val processor = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined)

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

        // Whole photos only: b ran (2 faces), c and d never started, no
        // clustering pass ran:
        assertEquals(1, engine.detectCalls.size)
        assertEquals(2, engine.putCalls.size)
        assertEquals(0, engine.clusterCalls)
        // The cancelled run still saved b's record (the resume contract):
        assertEquals(1, store.saveCalls)
        assertEquals(setOf("b"), store.saved!!.map { it.localMediaKey }.toSet())
        assertEquals(42L, store.saved!!.single().indexedAtMillis)
    }

    @Test
    fun `an engine fault on every photo still leaves honest state and a real fault path out of the loop`() {
        // r51 (audit C-5), the other edge: when the ENGINE is what's broken
        // (the C-2 teardown shape --- every photo faults), the batch now
        // COMPLETES with every photo terminally skipped instead of burning
        // the three WorkManager retries. The per-photo faults that are NOT
        // bytes-dependent (the orphan delete, the drain-time clustering,
        // the store count) still propagate unchanged --- those are batch
        // faults, and the worker's retry-with-backoff remains their path.
        val engine = object : IndexCoordinator.FaceIndexEngine {
            override fun detectFaceEmbeddings(jpeg: ByteArray): List<MlEngine.FaceEmbedding> =
                throw IllegalStateException("face index store is not open; call openFaceIndex(dir) first")

            override fun putFace(fileId: Long, embedding: FloatArray): Long = 1L
            override fun faceCount(): Long = 0L
            override fun clusterFaces(distanceThreshold: Double): MlEngine.ClusterSummary =
                MlEngine.ClusterSummary(0, 0, emptyList())

            override fun indexedFaceFileIds(): List<Long> = emptyList()
            override fun deleteFacesForFiles(fileIds: LongArray): Boolean = true
        }
        val plan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val store = FakeStateStore()

        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        val outcome = try {
            runBlocking {
                FaceIndexQueueProcessor(engine, Dispatchers.Unconfined).processBatch(
                    plan = plan,
                    stateStore = store,
                    readBytes = { bytesOf(it) },
                    nowMillis = { 1L },
                )
            }
        } finally {
            DiagLog.sink = stockSink
        }

        // No propagation from the per-photo loop anymore --- the skip is
        // recorded and counted (the honest state for a torn-down engine:
        // held out until revision change, re-attempted on the next run's
        // fresh session):
        assertEquals(0, outcome.indexedThisRun)
        assertEquals(1, outcome.engineFailedThisRun)
        assertTrue(store.saved!!.single().engineFailed)
    }

    @Test
    fun `an engine fault on one photo terminally skips it and the batch completes`() = runBlocking {
        // r51 (audit C-5): the poison-photo shape --- a decodable-but-
        // corrupt JPEG makes detectFaceEmbeddings throw EVERY run. The
        // pre-r51 contract aborted the batch at the first throw, so the
        // same photo sat at the head of the queue through every WorkManager
        // retry and every restart, wedging the chain. The per-photo catch
        // now terminally skips it (the F-39 unreadable mirror) and the
        // batch COMPLETES with the photos after it.
        val engine = FakeEngine().apply {
            detectFaultFor = setOf("content://media/external/file/2")
        }
        val store = FakeStateStore()
        val plan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val processor = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined)

        // The engine-fault skip logs one DiagLog line; the default sink
        // touches android.util.Log --- unmocked on the JVM. Swap it for a
        // recorder (the CompositeScanIndexHookTest pattern) and restore it
        // no matter how the test ends.
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        val logged = mutableListOf<String>()
        DiagLog.sink = { _, _, msg -> logged.add(msg) }
        val outcome = try {
            processor.processBatch(
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
        assertEquals(listOf("3"), engine.detectCalls.map { it.substringAfterLast('/') })
        assertFalse(outcome.moreRemaining)
        // The new counter is reported alongside unreadableThisRun:
        assertEquals(1, outcome.engineFailedThisRun)
        assertEquals(0, outcome.unreadableThisRun)
        // One log line for the one poison photo:
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("b"))
        assertTrue(logged.single().contains("terminal skip"))
        // b leaves a TERMINAL-SKIP record with the engine-fault flag (the
        // F-39 mirror): no faces, held out until its revision changes.
        val skipped = store.saved!!.single { it.localMediaKey == "b" }
        assertTrue(skipped.engineFailed)
        assertFalse(skipped.unreadable)
        assertFalse(store.saved!!.single { it.localMediaKey == "c" }.engineFailed)

        // The NEXT plan holds b out --- counted as skippedEngineFailed, NOT
        // up-to-date (the outcome stays honest about what skipped):
        val nextPlan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("b", 2), candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = emptySet(),
        )
        assertTrue(nextPlan.toIndex.isEmpty())
        assertEquals(1, nextPlan.skippedEngineFailed)
        assertEquals(1, nextPlan.skippedUpToDate)

        // A revision change gives the photo a fresh attempt: the skip is a
        // hold, not a ban (a genuinely changed file re-enters delete-first).
        val changed = candidate("b", 2, size = 101L)
        val replan = FaceIndexQueuePlanner.plan(
            candidates = listOf(changed, candidate("c", 3)),
            states = store.saved!!,
            storeIndexedFileIds = emptySet(),
        )
        assertEquals(listOf("b"), replan.toIndex.map { it.localMediaKey })
    }

    @Test
    fun `state records over a missing store plan a full re-index`() {
        // r51 (audit C-3 backstop): the reset-timeout resurrect shape ---
        // the state file survived (a NonCancellable save landed after the
        // reset's deleteRecursively) while the store it describes was
        // deleted. The old planner diffed every record UNCHANGED and
        // skipped the whole library; the backstop plans the full re-index.
        val dir = tmp.newFolder()
        val stateFile = File(dir, "index-state.json")
        runBlocking { FaceIndexStateFile(stateFile).save(listOf(state("a", 1), state("b", 2))) }
        val candidates = listOf(candidate("a", 1), candidate("b", 2), candidate("new", 3))

        // The backstop logs one DiagLog line (same JVM sink discipline).
        val stockSink: (Char, String, String) -> Unit = DiagLog.sink
        DiagLog.sink = { _, _, _ -> }
        val plan = try {
            FaceIndexQueuePlanner.plan(
                candidates = candidates,
                states = runBlocking { FaceIndexStateFile(stateFile).load() },
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

        // The control: with the store file PRESENT (the normal world), the
        // same inputs keep the plain diff mapping --- a and b skip, only
        // the never-processed "new" indexes.
        File(dir, ML_INDEX_STORE_FILE_NAME).writeText("sqlite")
        val normalPlan = FaceIndexQueuePlanner.plan(
            candidates = candidates,
            states = runBlocking { FaceIndexStateFile(stateFile).load() },
            storeIndexedFileIds = emptySet(),
            stateStoreFile = stateFile,
        )
        assertEquals(listOf("new"), normalPlan.toIndex.map { it.localMediaKey })
        assertEquals(2, normalPlan.skippedUpToDate)
    }

    // State file tests -----------------------------------------------------------------

    @Test
    fun `the state file round-trips states including nullable revision fields`() {
        val dir = tmp.newFolder()
        val file = FaceIndexStateFile(File(dir, "index-state.json"))
        val states = listOf(
            state("a", 1, generation = "g42", metadataWrites = 3, indexedAt = 10L),
            state("b", 2, generation = null, indexedAt = 20L),
            // r50 (F-39): the terminal-skip flag rides the same round trip
            // (an additive optional field on version 1 --- old readers
            // ignore the key, new readers default it to false).
            state("c", 3, indexedAt = 30L).copy(unreadable = true),
            // r51 (audit C-5): the engine-fault flag rides the same round
            // trip (the same additive-optional-field discipline).
            state("d", 4, indexedAt = 40L).copy(engineFailed = true),
        )

        runBlocking { file.save(states) }
        val loaded = runBlocking { file.load() }

        assertEquals(states, loaded)
        // The atomic write leaves no temp file behind:
        assertEquals(setOf("index-state.json"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `saving twice replaces atomically and loading an absent file is empty`() {
        val dir = tmp.newFolder()
        val file = FaceIndexStateFile(File(dir, "index-state.json"))

        assertTrue(runBlocking { file.load() }.isEmpty())

        runBlocking { file.save(listOf(state("a", 1))) }
        runBlocking { file.save(listOf(state("a", 1), state("b", 2))) }
        assertEquals(2, runBlocking { file.load() }.size)
        assertEquals(setOf("index-state.json"), dir.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `a corrupt or wrong-version file degrades to empty instead of throwing`() {
        val dir = tmp.newFolder()
        val target = File(dir, "index-state.json")

        // Garbage bytes:
        target.writeText("not json at all")
        assertTrue(runBlocking { FaceIndexStateFile(target).load() }.isEmpty())

        // A future schema version is not ours to interpret:
        target.writeText("""{"version":2,"states":[]}""")
        assertTrue(runBlocking { FaceIndexStateFile(target).load() }.isEmpty())

        // Partial state rows (a hand-edit dropped the file id) are skipped:
        target.writeText(
            """{"version":1,"states":[{"key":"a"},{"key":"b","fileId":2,"sizeBytes":1,"dateModifiedMillis":1,"metadataWriteRevision":0,"indexedAtMillis":5}]}""",
        )
        val loaded = runBlocking { FaceIndexStateFile(target).load() }
        assertEquals(1, loaded.size)
        assertEquals("b", loaded.single().localMediaKey)
    }

    @Test
    fun `revision equality after a round trip still diffs UNCHANGED`() {
        val dir = tmp.newFolder()
        val file = FaceIndexStateFile(File(dir, "index-state.json"))
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
