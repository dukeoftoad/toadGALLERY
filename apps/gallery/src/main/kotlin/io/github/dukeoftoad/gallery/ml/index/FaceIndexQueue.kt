package io.github.dukeoftoad.gallery.ml.index

import io.github.dukeoftoad.gallery.data.media.revision.MediaRevision
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The r24 background face-index queue (Workstream 5 of the Phase 5 plan,
 * `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`): the pure
 * planning + execution core that turns the 12-photo r23 dogfood slice into
 * a full-library, resumable, revision-aware indexing queue.
 *
 * Everything in this file is PURE JVM (no `android.*` type; ADR-0016) and
 * covered by `FaceIndexQueueTest` / `FaceIndexStateFileTest`:
 *
 *  - [FaceIndexQueuePlanner] decides, per photo, EXACTLY what the queue
 *    owes next from three inputs --- the current eligible listing
 *    ([FaceIndexCandidate]s), the queue's own last-processed records
 *    ([FaceIndexedState]s) and the ML store's indexed-file truth --- using
 *    [MediaRevision.diff] as the invalidation contract: UNCHANGED skips,
 *    METADATA_CHANGED skips (pixel-derived AI stays valid --- the audit's
 *    "a metadata write never burns model inference" rule), CONTENT_CHANGED
 *    re-indexes delete-first;
 *  - [FaceIndexQueueProcessor] executes one bounded batch of a plan over
 *    the same [IndexCoordinator.FaceIndexEngine] seam the r23 coordinator
 *    uses (fakes in tests, `MlEngineIndexEngine` in production), persists
 *    progress through a [FaceIndexStateStore] seam after EVERY batch (and
 *    on cancellation, under [NonCancellable]), honors cancellation between
 *    photos (the r23 rule: whole photos only, never mid-face), and runs
 *    the clustering pass exactly once when the queue drains;
 *  - [FaceIndexStateFile] is the production state store: one versioned
 *    JSON file inside the app-private face-index directory, written with
 *    an atomic temp-then-move so a crash mid-write can never destroy the
 *    previous contents, and parsed FORGIVINGLY (a corrupt or missing file
 *    degrades to "nothing processed yet" --- the delete-first re-index
 *    keeps that state loss correct, just slower).
 *
 * Design rules this file pins (mirroring [IndexCoordinator]):
 *  - NO MediaStore, NO resolver, NO WorkManager, NO UI concerns here; the
 *    Android-adjacent [FaceIndexWorker] binds the real seams;
 *  - the queue's file id is the MediaStore `_id` of the EXTERNAL PRIMARY
 *    volume (the same id the r23 dogfood wrote); multi-volume libraries
 *    are the r25+ boundary and the worker filters candidates accordingly;
 *  - resumability is derived from persisted state, NEVER from a live
 *    process: a fresh plan after process death skips exactly the photos
 *    whose recorded revisions still diff UNCHANGED.
 */
// The r24 queue's own pure types ---------------------------------------------------

/**
 * One eligible photo the queue planner considers: the stable catalogue
 * identity ([localMediaKey], `MediaIdentity.toKeyString()`), the MediaStore
 * `_id` the faces table keys on ([fileId] --- external-primary volume),
 * the currently observed [revision] (from the Phase 4 catalogue row), the
 * content [uri] the worker reads bytes through, and [dateAddedMillis] for
 * the newest-first ordering the dogfood card's queue walks.
 */
data class FaceIndexCandidate(
    val localMediaKey: String,
    val fileId: Long,
    val revision: MediaRevision,
    val uri: String,
    val dateAddedMillis: Long,
)

/**
 * The queue's record of one photo it fully processed (all its faces for
 * the current bytes written, zero faces included): the [revision] OBSERVED
 * AT INDEX TIME is the half that makes re-planning incremental --- a later
 * [MediaRevision.diff] against a fresh candidate decides skip vs re-index.
 */
data class FaceIndexedState(
    val localMediaKey: String,
    val fileId: Long,
    val revision: MediaRevision,
    val indexedAtMillis: Long,
)

/**
 * What the queue owes, derived once per worker run from candidates +
 * states + store truth. [toIndex] is the FULL queue depth (new files plus
 * CONTENT_CHANGED re-indexes, newest first); [reIndexKeys] marks the
 * subset whose stale faces must be deleted before re-inserting (the r23
 * `putFace` ordinals APPEND, so a re-index without delete would duplicate
 * rows); [orphanFileIds] are store files with no current candidate
 * (deleted / trashed / vault-moved media --- their rows must go);
 * [staleStateKeys] are state records with no current candidate (their
 * bookkeeping must not survive the photos it describes).
 */
data class FaceIndexPlan(
    val toIndex: List<FaceIndexCandidate>,
    val reIndexKeys: Set<String>,
    val orphanFileIds: List<Long>,
    val staleStateKeys: Set<String>,
    val skippedUpToDate: Int,
    val skippedMetadataOnly: Int,
) {
    /** True when the plan asks for no store work at all. */
    val isEmpty: Boolean get() = toIndex.isEmpty() && orphanFileIds.isEmpty()
}

/**
 * Derives the [FaceIndexPlan] from the three truths a worker run gathers.
 * Pure and total: every combination of the three inputs maps to a plan,
 * and the mapping is the invalidation contract of the audit:
 *
 *  - state UNCHANGED vs candidate --- skip ([FaceIndexPlan.skippedUpToDate]);
 *  - state METADATA_CHANGED --- skip ([FaceIndexPlan.skippedMetadataOnly]):
 *    the app itself rewrote EXIF/IPTC/XMP, the pixels did not move, and
 *    `RevisionDelta.invalidatesAiOutputs` is false by construction;
 *  - state CONTENT_CHANGED or NO state at all --- index (delete-first when
 *    the store holds rows for the file: [FaceIndexPlan.reIndexKeys] for
 *    known revisions, plus any store file without a state record --- the
 *    r23 dogfood rows, re-run once against the r24 queue's bookkeeping);
 *  - store file with NO candidate --- orphan: delete its rows;
 *  - state record with NO candidate --- stale bookkeeping: drop it.
 */
object FaceIndexQueuePlanner {

    /** The plan for one run's inputs; see the object KDoc for the mapping. */
    fun plan(
        candidates: List<FaceIndexCandidate>,
        states: List<FaceIndexedState>,
        storeIndexedFileIds: Set<Long>,
    ): FaceIndexPlan {
        val statesByKey = HashMap<String, FaceIndexedState>(states.size)
        states.forEach { statesByKey[it.localMediaKey] = it }
        val candidateFileIds = HashSet<Long>(candidates.size)
        val candidateKeys = HashSet<String>(candidates.size)
        val toIndex = ArrayList<FaceIndexCandidate>(candidates.size)
        val reIndexKeys = HashSet<String>()
        var skippedUpToDate = 0
        var skippedMetadataOnly = 0

        for (candidate in candidates) {
            candidateFileIds.add(candidate.fileId)
            candidateKeys.add(candidate.localMediaKey)
            val state = statesByKey[candidate.localMediaKey]
            if (state == null) {
                // Never processed by this queue (or its record was lost):
                // index it; delete-first if the r23 dogfood left rows.
                toIndex.add(candidate)
                continue
            }
            when (state.revision.diff(candidate.revision)) {
                io.github.dukeoftoad.gallery.data.media.revision.RevisionDelta.UNCHANGED ->
                    skippedUpToDate++
                io.github.dukeoftoad.gallery.data.media.revision.RevisionDelta.METADATA_CHANGED ->
                    skippedMetadataOnly++
                io.github.dukeoftoad.gallery.data.media.revision.RevisionDelta.CONTENT_CHANGED -> {
                    toIndex.add(candidate)
                    reIndexKeys.add(candidate.localMediaKey)
                }
            }
        }

        // Store truth the listing no longer backs: orphan rows to delete.
        // (A re-index candidate's own fileId is NOT an orphan --- its rows
        // are dropped per-photo, right before the fresh insert, so a
        // batch-limited run never leaves a half-deleted file behind.)
        val orphanFileIds = storeIndexedFileIds
            .filterTo(ArrayList()) { it !in candidateFileIds }

        // Bookkeeping the listing no longer backs: state records to drop.
        val staleStateKeys = statesByKey.keys
            .filterTo(HashSet()) { it !in candidateKeys }

        return FaceIndexPlan(
            toIndex = toIndex,
            reIndexKeys = reIndexKeys,
            orphanFileIds = orphanFileIds,
            staleStateKeys = staleStateKeys,
            skippedUpToDate = skippedUpToDate,
            skippedMetadataOnly = skippedMetadataOnly,
        )
    }
}

// The r24 queue's execution core ---------------------------------------------------

/** The persistence seam the processor reads and writes queue state through. */
interface FaceIndexStateStore {

    /** The queue's recorded states (empty when none were ever persisted). */
    suspend fun load(): List<FaceIndexedState>

    /** Persists [states] as the WHOLE queue state (an atomic full rewrite). */
    suspend fun save(states: List<FaceIndexedState>)
}

/**
 * Executes one bounded batch of a [FaceIndexPlan] over the engine seam.
 *
 * Bounded work, per run (the r24 concurrency story --- the JNI engine is
 * serialized by its own contract, so one photo at a time IS the bounded
 * concurrency): ALL orphan deletes (cheap, chunked upstream), then at
 * most [batchSize] photos --- each read through [readBytes] one at a time
 * (one JPEG in memory, never the library), deleted-first when the store
 * held rows, re-embedded, re-put per face, and recorded in the state
 * store. Progress lands in [onProgress] after every photo; cancellation
 * is honored BETWEEN photos (whole photos only --- the r23 rule) and the
 * state gathered so far is persisted under [NonCancellable] even on the
 * cancellation path, so a cancelled or killed run resumes exactly where
 * its records stop.
 *
 * The clustering pass runs ONCE, only when the batch drained the whole
 * queue ([BatchOutcome.moreRemaining] false) and the plan carried real
 * work --- never on an empty plan (a no-op run must not burn a recluster)
 * and never mid-queue (partial clusters would churn on every batch).
 */
class FaceIndexQueueProcessor(
    private val engine: IndexCoordinator.FaceIndexEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Live per-batch progress: [processedThisRun] photos of [batchSize]
     * done, [facesWrittenThisRun] face rows written by this run, against
     * the full [queueDepth] the plan still owed when the run started.
     */
    data class BatchProgress(
        val processedThisRun: Int,
        val batchSize: Int,
        val queueDepth: Int,
        val facesWrittenThisRun: Int,
    )

    /**
     * The run's outcome. [queueDepth]/[skippedUpToDate]/
     * [skippedMetadataOnly] mirror the plan; the rest count THIS run's
     * work. [moreRemaining] is true when the plan's queue outlived this
     * batch --- the worker re-enqueues itself then. [clusterSummary] is
     * non-null only on the draining run of a non-empty plan.
     * [unreadableThisRun] counts photos whose bytes could not be read;
     * they stay in the queue (no state record) and are retried next run
     * --- honest, bounded by the batch size, visible in the card.
     */
    data class BatchOutcome(
        val queueDepth: Int,
        val indexedThisRun: Int,
        val facesWrittenThisRun: Int,
        val orphanRowsDeletedFor: Int,
        val skippedUpToDate: Int,
        val skippedMetadataOnly: Int,
        val unreadableThisRun: Int,
        val moreRemaining: Boolean,
        val clusterSummary: MlEngine.ClusterSummary?,
        val totalFacesInStore: Long,
    )

    /** The batch size bound; one inference's worth of photos per re-enqueue. */
    private companion object {
        const val DEFAULT_BATCH_SIZE = 50
    }

    /** See the class KDoc for the full run contract. */
    suspend fun processBatch(
        plan: FaceIndexPlan,
        stateStore: FaceIndexStateStore,
        readBytes: suspend (FaceIndexCandidate) -> ByteArray?,
        distanceThreshold: Double = IndexCoordinator.RECOMMENDED_DISTANCE_THRESHOLD,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        nowMillis: () -> Long,
        onProgress: suspend (BatchProgress) -> Unit = {},
    ): BatchOutcome = withContext(ioDispatcher) {
        // Orphan cleanup: every orphan file's rows, this run, before any
        // inference work (upstream chunks the DELETE itself).
        if (plan.orphanFileIds.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            engine.deleteFacesForFiles(plan.orphanFileIds.toLongArray())
        }

        // Queue bookkeeping: load once, mutate per photo, save whole.
        val statesByKey = HashMap<String, FaceIndexedState>()
        stateStore.load().forEach { statesByKey[it.localMediaKey] = it }
        plan.staleStateKeys.forEach { statesByKey.remove(it) }

        val batch = plan.toIndex.take(batchSize.coerceAtLeast(0))
        var facesWritten = 0
        var indexed = 0
        var unreadable = 0
        try {
            for (candidate in batch) {
                currentCoroutineContext().ensureActive()
                val bytes = readBytes(candidate)
                if (bytes == null) {
                    unreadable++
                    continue
                }
                // Delete-first: the r23 putFace ordinals append, so stale
                // rows (re-index) or dogfood leftovers must go before the
                // fresh insert. Idempotent no-op when the file had no rows.
                engine.deleteFacesForFiles(longArrayOf(candidate.fileId))
                for (face in engine.detectFaceEmbeddings(bytes)) {
                    engine.putFace(candidate.fileId, face.embedding)
                    facesWritten++
                }
                statesByKey[candidate.localMediaKey] = FaceIndexedState(
                    localMediaKey = candidate.localMediaKey,
                    fileId = candidate.fileId,
                    revision = candidate.revision,
                    indexedAtMillis = nowMillis(),
                )
                indexed++
                onProgress(BatchProgress(indexed, batch.size, plan.toIndex.size, facesWritten))
            }
        } finally {
            // Whole-photo bookkeeping survives cancellation and crashes:
            // the save itself runs OUTSIDE the cancelling context.
            withContext(NonCancellable) {
                stateStore.save(ArrayList(statesByKey.values))
            }
        }

        val moreRemaining = plan.toIndex.size > batch.size
        val clusterSummary = if (!moreRemaining && !plan.isEmpty) {
            // The draining run re-clusters once; a cancelled run must not
            // start a half pass (the r23 between-photos rule, one gate
            // later in the pipeline).
            currentCoroutineContext().ensureActive()
            engine.clusterFaces(distanceThreshold)
        } else {
            null
        }

        BatchOutcome(
            queueDepth = plan.toIndex.size,
            indexedThisRun = indexed,
            facesWrittenThisRun = facesWritten,
            orphanRowsDeletedFor = plan.orphanFileIds.size,
            skippedUpToDate = plan.skippedUpToDate,
            skippedMetadataOnly = plan.skippedMetadataOnly,
            unreadableThisRun = unreadable,
            moreRemaining = moreRemaining,
            clusterSummary = clusterSummary,
            totalFacesInStore = engine.faceCount(),
        )
    }
}

// The r24 queue's state persistence -------------------------------------------------

/**
 * The production [FaceIndexStateStore]: one JSON file (`index-state.json`)
 * inside the app-private face-index directory --- the SAME directory whose
 * recursive delete resets the whole ML index, so the queue's bookkeeping
 * can never outlive the rows it describes.
 *
 * Write path: serialize to `<file>.tmp`, then an atomic NIO move over the
 * previous file (same directory = same filesystem = atomic on POSIX; a
 * plain-filesystem fallback keeps exotic mount layouts honest). A crash
 * between batches therefore loses at most the CURRENT batch's records ---
 * those photos re-index delete-first next run, which is correct, just
 * slower. Read path: FORGIVING --- a missing, empty or corrupt file is
 * "nothing processed yet", never a crash (the same never-trust-stored-
 * bytes posture as the Appearance settings' parse).
 *
 * JVM-tested directly (`FaceIndexStateFileTest`): round-trips, atomic
 * overwrite, corrupt-file recovery, version discipline.
 */
class FaceIndexStateFile(
    private val file: File,
) : FaceIndexStateStore {

    override suspend fun load(): List<FaceIndexedState> {
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt(VERSION_KEY) != SUPPORTED_VERSION) return emptyList()
            val array = root.optJSONArray(STATES_KEY) ?: return emptyList()
            val out = ArrayList<FaceIndexedState>(array.length())
            for (i in 0 until array.length()) {
                stateFromJson(array.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            out
        } catch (_: Exception) {
            // Corrupt / partially-written / hand-edited: the honest state
            // is "nothing processed" (re-index runs, delete-first keeps it
            // correct), never a thrown parse error into the worker.
            emptyList()
        }
    }

    override suspend fun save(states: List<FaceIndexedState>) {
        val array = JSONArray()
        states.forEach { array.put(stateToJson(it)) }
        val root = JSONObject()
            .put(VERSION_KEY, SUPPORTED_VERSION)
            .put(STATES_KEY, array)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Layouts without atomic rename support still get the durable
            // temp-write + replace ordering; only the atomicity window
            // widens.
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val VERSION_KEY = "version"
        const val STATES_KEY = "states"
        const val SUPPORTED_VERSION = 1

        const val KEY = "key"
        const val FILE_ID = "fileId"
        const val INDEXED_AT = "indexedAtMillis"
        const val SIZE_BYTES = "sizeBytes"
        const val DATE_MODIFIED = "dateModifiedMillis"
        const val GENERATION_TOKEN = "generationToken"
        const val METADATA_WRITE_REVISION = "metadataWriteRevision"
        const val CONTENT_FINGERPRINT = "contentFingerprint"

        fun stateToJson(state: FaceIndexedState): JSONObject = JSONObject()
            .put(KEY, state.localMediaKey)
            .put(FILE_ID, state.fileId)
            .put(INDEXED_AT, state.indexedAtMillis)
            .put(SIZE_BYTES, state.revision.sizeBytes)
            .put(DATE_MODIFIED, state.revision.dateModifiedMillis)
            .put(
                GENERATION_TOKEN,
                state.revision.generationToken ?: JSONObject.NULL,
            )
            .put(METADATA_WRITE_REVISION, state.revision.metadataWriteRevision)
            .put(
                CONTENT_FINGERPRINT,
                state.revision.contentFingerprint ?: JSONObject.NULL,
            )

        fun stateFromJson(obj: JSONObject): FaceIndexedState? {
            val key = obj.optString(KEY)
            if (key.isEmpty()) return null
            val fileId = obj.optLong(FILE_ID, Long.MIN_VALUE)
            if (fileId == Long.MIN_VALUE) return null
            return FaceIndexedState(
                localMediaKey = key,
                fileId = fileId,
                revision = MediaRevision(
                    sizeBytes = obj.optLong(SIZE_BYTES),
                    dateModifiedMillis = obj.optLong(DATE_MODIFIED),
                    generationToken = if (obj.isNull(GENERATION_TOKEN)) {
                        null
                    } else {
                        obj.optString(GENERATION_TOKEN)
                    },
                    metadataWriteRevision = obj.optInt(METADATA_WRITE_REVISION),
                    contentFingerprint = if (obj.isNull(CONTENT_FINGERPRINT)) {
                        null
                    } else {
                        obj.optString(CONTENT_FINGERPRINT)
                    },
                ),
                indexedAtMillis = obj.optLong(INDEXED_AT),
            )
        }
    }
}
