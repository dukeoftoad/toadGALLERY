package io.github.dukeoftoad.gallery.ml.index

import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.revision.MediaRevision
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import kotlinx.coroutines.CancellationException
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
 * The r26 background semantic-index queue (Workstream 7 of the Phase 5
 * plan, `docs/phase-5/magic-search.md`): the pure planning + execution
 * core that gives Magic Search its CLIP index --- the SIBLING of the r24
 * [FaceIndexQueuePlanner] / [FaceIndexQueueProcessor] pair, built by
 * mirroring their structure exactly and changing ONE thing per photo: a
 * whole-photo 512-float `clipImageEmbedding` replaces per-face rows, and
 * the once-per-drain reconciliation pass is [MlEngine.fillClipIndex]
 * instead of clustering (there is nothing to cluster --- CLIP vectors
 * are searched, not grouped).
 *
 * Everything in this file is PURE JVM (no `android.*` type; ADR-0016) and
 * covered by `ClipIndexQueueTest` (the r51 log lines ride [DiagLog], whose
 * JVM-test seam swaps the sink away from `android.util.Log` --- the
 * `CompositeScanIndexHook` pattern):
 *
 *  - [ClipIndexQueuePlanner] decides, per photo, EXACTLY what the queue
 *    owes next from the same three truths the face planner consults
 *    (current eligible listing, the queue's own last-processed records,
 *    the ML store's indexed-file truth --- here the CLIP rows of the SAME
 *    `ml-index.sqlite` store the faces live in), using
 *    [MediaRevision.diff] as the invalidation contract: UNCHANGED skips,
 *    METADATA_CHANGED skips (pixel-derived AI stays valid --- "a metadata
 *    write never burns model inference"), CONTENT_CHANGED re-indexes
 *    delete-first. HONEST CAVEAT on that last mapping: unlike `putFace`
 *    (whose ordinals APPEND, so a re-index without delete would duplicate
 *    rows), `putClip` is an UPSERT --- the delete-first pass is kept
 *    anyway, mirroring the face planner's mapping verbatim, because the
 *    queue's whole resumability story is built on the same diff→action
 *    table and drifting between the two queues would be a correctness
 *    trap for whichever round tries to unify them;
 *  - [ClipIndexQueueProcessor] executes one bounded batch of a plan over
 *    the [ClipIndexEngine] seam (fakes in tests,
 *    [MlEngineClipIndexEngine] in production), persists progress through
 *    a [ClipIndexStateStore] seam after EVERY batch (and on
 *    cancellation, under [NonCancellable]), honors cancellation between
 *    photos (whole photos only --- the r23/r24 rule), and runs the
 *    reconciliation pass ([ClipIndexEngine.fillClipIndex]) exactly once
 *    when the queue drains;
 *  - [ClipIndexStateFile] is the production state store: one versioned
 *    JSON file (`clip-index-state.json`) inside the SAME app-private
 *    face-index directory as `ml-index.sqlite`, `index-state.json` and
 *    `people-state.json`, written with the same atomic
 *    temp-then-move discipline as `FaceIndexStateFile` and parsed
 *    FORGIVINGLY (a corrupt or missing file degrades to "nothing
 *    processed yet" --- the delete-first re-index keeps that state loss
 *    correct, just slower).
 *
 * r51 (audit C-5/C-9/C-3-backstop, the queue-poison-skip round --- the
 * face queue's fixes, mirrored): the processor terminally SKIPS a photo
 * whose engine calls fault (the F-39 unreadable mirror, an
 * `engineFailed` record holding it out until its revision changes) so
 * one poison JPEG can no longer wedge the chain at the same head-of-
 * queue item through every retry; the plan's dead `reIndexKeys` field is
 * gone (delete-first is unconditional); and the planner self-heals a
 * reset-resurrected state file over a deleted store --- by a
 * store-EXISTENCE check when the state file is visible, and by the
 * store-TRUTH cross-check (a live record whose row is gone) that still
 * sees the resurrect after the worker's store-open recreates an empty
 * `ml-index.sqlite`.
 *
 * Design rules this file pins (mirroring [FaceIndexQueuePlanner]):
 *  - NO MediaStore, NO resolver, NO WorkManager, NO UI concerns here; the
 *    Android-adjacent [ClipIndexWorker] binds the real seams;
 *  - the queue's file id is the MediaStore `_id` of the EXTERNAL PRIMARY
 *    volume (the worker reuses [eligibleCandidates], the r24 filter, so
 *    the two queues can never disagree about what is indexable);
 *  - resumability is derived from persisted state, NEVER from a live
 *    process: a fresh plan after process death skips exactly the photos
 *    whose recorded revisions still diff UNCHANGED.
 */
// The r26 queue's own pure types ---------------------------------------------------

/**
 * One eligible photo the semantic queue planner considers: the stable
 * catalogue identity ([localMediaKey], `MediaIdentity.toKeyString()`), the
 * MediaStore `_id` the CLIP rows key on ([fileId] --- external-primary
 * volume), the currently observed [revision] (from the Phase 4 catalogue
 * row), the content [uri] the worker reads bytes through, and
 * [dateAddedMillis] for the newest-first ordering (the same fields as
 * [FaceIndexCandidate]; a distinct type so the two queues' plans can
 * never be silently interchanged).
 */
data class ClipIndexCandidate(
    val localMediaKey: String,
    val fileId: Long,
    val revision: MediaRevision,
    val uri: String,
    val dateAddedMillis: Long,
)

/**
 * The queue's record of one photo it fully processed (its one whole-photo
 * 512-float embedding written --- photos the engine could not embed are
 * never recorded): the [revision] OBSERVED AT INDEX TIME is the half that
 * makes re-planning incremental --- a later [MediaRevision.diff] against
 * a fresh candidate decides skip vs re-index.
 *
 * r50 (F-39): [unreadable] marks the TERMINAL-SKIP record written when
 * the photo's bytes could not be read --- the same fix the face queue's
 * [io.github.dukeoftoad.gallery.ml.index.FaceIndexedState] carries (see
 * its KDoc); it holds the photo out of the queue until its revision
 * changes, and never produces clip rows.
 *
 * r51 (audit C-5): [engineFailed] is the same TERMINAL-SKIP shape for
 * ENGINE faults (the processor's per-photo delete/embed/put block threw
 * --- the facade's `IllegalStateException`, a JNI `RuntimeException`, or
 * a refused insert's deliberate fault). It holds the photo out until its
 * revision changes, exactly like [unreadable]; it carries no clip row.
 */
data class ClipIndexedState(
    val localMediaKey: String,
    val fileId: Long,
    val revision: MediaRevision,
    val indexedAtMillis: Long,
    val unreadable: Boolean = false,
    /** r51 (audit C-5): the engine-fault terminal skip (the F-39 mirror). */
    val engineFailed: Boolean = false,
)

/**
 * What the semantic queue owes, derived once per worker run from
 * candidates + states + store truth. [toIndex] is the FULL queue depth
 * (new files plus CONTENT_CHANGED re-indexes, newest first).
 * Delete-first is UNCONDITIONAL for every photo in [toIndex]:
 * `putClip` is an UPSERT, and delete-before-insert is a superset of
 * re-index, so r51 (audit C-9) removed the old `reIndexKeys` set (the
 * planner computed it and no executor ever read --- the mapping stayed
 * identical to the face planner's on purpose, and it still does).
 * [orphanFileIds] are store files with no current candidate (deleted /
 * trashed / vault-moved media --- their rows must go);
 * [staleStateKeys] are state records with no current candidate.
 */
data class ClipIndexPlan(
    val toIndex: List<ClipIndexCandidate>,
    val orphanFileIds: List<Long>,
    val staleStateKeys: Set<String>,
    val skippedUpToDate: Int,
    val skippedMetadataOnly: Int,
    /** r50 (F-39): photos held out by a terminal unreadable-skip record. */
    val skippedUnreadable: Int = 0,
    /** r51 (audit C-5): photos held out by a terminal engine-fault record. */
    val skippedEngineFailed: Int = 0,
) {
    /** True when the plan asks for no store work at all. */
    val isEmpty: Boolean get() = toIndex.isEmpty() && orphanFileIds.isEmpty()
}

/**
 * Derives the [ClipIndexPlan] from the three truths a worker run gathers.
 * Pure and total, and the SAME invalidation mapping the r24 face planner
 * pins (deliberately --- one diff→action table across both queues):
 *
 *  - state UNCHANGED vs candidate --- skip ([ClipIndexPlan.skippedUpToDate]);
 *  - state METADATA_CHANGED --- skip ([ClipIndexPlan.skippedMetadataOnly]):
 *    the app itself rewrote EXIF/IPTC/XMP, the pixels did not move, and
 *    `RevisionDelta.invalidatesAiOutputs` is false by construction;
 *  - state CONTENT_CHANGED or NO state at all --- index, delete-first
 *    UNCONDITIONALLY (see [ClipIndexPlan]; a store file without a state
 *    record lands in the same set);
 *  - store file with NO candidate --- orphan: delete its rows;
 *  - state record with NO candidate --- stale bookkeeping: drop it.
 */
object ClipIndexQueuePlanner {

    /**
     * The plan for one run's inputs; see the object KDoc for the mapping.
     *
     * r51 (audit C-3 backstop): [stateStoreFile] is the queue's state file
     * (the same `File` the worker builds its [ClipIndexStateFile] on).
     * When it is non-null, live state records exist, and the store file
     * those records describe ([ML_INDEX_STORE_FILE_NAME], the naming
     * convention [MlEngine.openFaceIndex] pins for the shared
     * `ml-index.sqlite`) is MISSING next to it, the bookkeeping
     * resurrected against a deleted store: every such record would diff
     * UNCHANGED and skip forever ("Magic Search/Things stuck NotIndexed").
     * The planner instead plans those photos for a full delete-first
     * re-index and logs one line. Null (the default) keeps the plain
     * diff mapping --- the store-TRUTH cross-check inside the loop needs
     * no wiring and still catches the resurrect in production (see its
     * comment), and since the r51 verification wiring both workers also
     * pass their state file, so this file-existence check is
     * production-active too (before it, the workers used the default ---
     * the wave-1 review's finding).
     */
    fun plan(
        candidates: List<ClipIndexCandidate>,
        states: List<ClipIndexedState>,
        storeIndexedFileIds: Set<Long>,
        stateStoreFile: File? = null,
    ): ClipIndexPlan {
        // r51 (audit C-3 backstop, the file half): the store-existence
        // check, BEFORE any diffing --- cheap by design, one File.isFile
        // at plan time.
        val storeDir = stateStoreFile?.parentFile
        val storeMissing = storeDir != null && states.isNotEmpty() &&
            !File(storeDir, ML_INDEX_STORE_FILE_NAME).isFile
        if (storeMissing) {
            DiagLog.w(
                TAG,
                "clip queue planner: ${states.size} state records reference a deleted store " +
                    "($ML_INDEX_STORE_FILE_NAME missing next to ${stateStoreFile.name}); " +
                    "planning full re-index",
            )
        }

        val statesByKey = HashMap<String, ClipIndexedState>(states.size)
        states.forEach { statesByKey[it.localMediaKey] = it }
        val candidateFileIds = HashSet<Long>(candidates.size)
        val candidateKeys = HashSet<String>(candidates.size)
        val toIndex = ArrayList<ClipIndexCandidate>(candidates.size)
        var skippedUpToDate = 0
        var skippedMetadataOnly = 0
        var skippedUnreadable = 0
        var skippedEngineFailed = 0
        var rePlannedForMissingRows = 0

        for (candidate in candidates) {
            candidateFileIds.add(candidate.fileId)
            candidateKeys.add(candidate.localMediaKey)
            val state = statesByKey[candidate.localMediaKey]
            if (state == null || storeMissing) {
                // Never processed by this queue (or its record was lost):
                // index it; delete-first if some earlier run left a row.
                // storeMissing routes EVERY state-backed photo here too
                // (the C-3 full re-index above).
                toIndex.add(candidate)
                continue
            }
            // r51 (audit C-3 backstop, the store-truth half): a live
            // record whose row is GONE from the store truth describes a
            // store that lost it. This half is what catches the
            // reset-resurrect in production TODAY: the worker's own
            // store-open RECREATES an empty `ml-index.sqlite` before the
            // planner runs, so by plan time the file exists again and
            // only the ROW truth still shows the deletion. Safe here and
            // ONLY here: every CLIP-indexed photo carries exactly ONE
            // row, so a live record with no row is a definite
            // inconsistency (the reset-resurrect, or a model-version
            // bump whose old rows the version-filtered read excludes ---
            // re-embedding at the current pin is the correct response
            // either way). The face planner deliberately has NO such
            // check: a zero-face photo legitimately has a record and no
            // rows, so the cross-check would re-queue face-less
            // libraries forever (the F-39 loop reborn). The TERMINAL-SKIP
            // records are exempt for the same reason ---
            // unreadable/engineFailed photos never carry rows by design.
            if (!state.unreadable && !state.engineFailed &&
                candidate.fileId !in storeIndexedFileIds
            ) {
                rePlannedForMissingRows++
                toIndex.add(candidate)
                continue
            }
            when (state.revision.diff(candidate.revision)) {
                io.github.dukeoftoad.gallery.data.media.revision.RevisionDelta.UNCHANGED ->
                    // r50 (F-39) / r51 (C-5): a terminal-skip record holds
                    // the photo out until its revision changes (see
                    // FaceIndexedState).
                    when {
                        state.unreadable -> skippedUnreadable++
                        state.engineFailed -> skippedEngineFailed++
                        else -> skippedUpToDate++
                    }
                io.github.dukeoftoad.gallery.data.media.revision.RevisionDelta.METADATA_CHANGED ->
                    skippedMetadataOnly++
                io.github.dukeoftoad.gallery.data.media.revision.RevisionDelta.CONTENT_CHANGED ->
                    // r51 (audit C-9): no reIndexKeys bookkeeping --- the
                    // processor's delete-first is unconditional.
                    toIndex.add(candidate)
            }
        }

        if (rePlannedForMissingRows > 0) {
            DiagLog.w(
                TAG,
                "clip queue planner: $rePlannedForMissingRows state records reference store " +
                    "rows that are gone; planning delete-first re-index",
            )
        }

        // Store truth the listing no longer backs: orphan rows to delete.
        // (A re-index candidate's own fileId is NOT an orphan --- its row
        // is dropped per-photo, right before the fresh insert, so a
        // batch-limited run never leaves a half-deleted file behind.)
        val orphanFileIds = storeIndexedFileIds
            .filterTo(ArrayList()) { it !in candidateFileIds }

        // Bookkeeping the listing no longer backs: state records to drop.
        val staleStateKeys = statesByKey.keys
            .filterTo(HashSet()) { it !in candidateKeys }

        return ClipIndexPlan(
            toIndex = toIndex,
            orphanFileIds = orphanFileIds,
            staleStateKeys = staleStateKeys,
            skippedUpToDate = skippedUpToDate,
            skippedMetadataOnly = skippedMetadataOnly,
            skippedUnreadable = skippedUnreadable,
            skippedEngineFailed = skippedEngineFailed,
        )
    }
}

// The r26 queue's execution core ---------------------------------------------------

/**
 * The engine surface the semantic queue drives, as an injectable seam
 * (the [IndexCoordinator.FaceIndexEngine] pattern, CLIP half): production
 * binds [MlEngineClipIndexEngine] over the [MlEngine] facade (Agent A's
 * r26 JNI surface); the JVM suite binds fakes. All members mirror the
 * facade's own contract: store operations require an open
 * `ml-index.sqlite` store, `putClip` guards the 512-float dimension, and
 * failures surface as the facade's own exception shapes.
 */
interface ClipIndexEngine {

    /** The whole-photo 512-float CLIP image embedding for one JPEG's bytes. */
    fun clipImageEmbedding(jpeg: ByteArray): FloatArray

    /**
     * Upserts one CLIP row for [fileId]; the engine's own success flag.
     * Throws IllegalArgumentException when [embedding] is not
     * [MlEngine.CLIP_EMBEDDING_DIM] floats.
     */
    fun putClip(fileId: Long, embedding: FloatArray): Boolean

    /** Deletes every CLIP row for [fileIds]; the invalidation primitive. */
    fun deleteClipsForFiles(fileIds: LongArray): Boolean

    /** File IDs that currently carry CLIP rows (the resumability read). */
    fun clipIndexedFileIds(): List<Long>

    /** Total CLIP rows currently in the store. */
    fun clipCount(): Long

    /**
     * The reconciliation pass over the store's own bookkeeping (the
     * semantic queue's once-per-drain counterpart of the face queue's
     * clustering pass): the engine fills/repairs the vecdb-side index
     * from the sqlite-side rows and reports what it did.
     */
    fun fillClipIndex(): MlEngine.ClipFillReport
}

/** The persistence seam the processor reads and writes queue state through. */
interface ClipIndexStateStore {

    /** The queue's recorded states (empty when none were ever persisted). */
    suspend fun load(): List<ClipIndexedState>

    /** Persists [states] as the WHOLE queue state (an atomic full rewrite). */
    suspend fun save(states: List<ClipIndexedState>)
}

/**
 * Executes one bounded batch of a [ClipIndexPlan] over the engine seam.
 *
 * Bounded work, per run (the r24 concurrency story, unchanged: the JNI
 * engine serializes inference by its own contract, so one photo at a time
 * IS the bounded concurrency): ALL orphan deletes (cheap, chunked
 * upstream), then at most [batchSize] photos --- each read through
 * [readBytes] one at a time (one JPEG in memory, never the library),
 * deleted-first when the store held a row, re-embedded with ONE
 * whole-photo [ClipIndexEngine.clipImageEmbedding] call (no per-face
 * rows, no clustering --- the entire difference from the face queue),
 * re-put, and recorded in the state store. Progress lands in [onProgress]
 * after every photo; cancellation is honored BETWEEN photos (whole
 * photos only --- the r23/r24 rule) and the state gathered so far is
 * persisted under [NonCancellable] even on the cancellation path, so a
 * cancelled or killed run resumes exactly where its records stop.
 *
 * The reconciliation pass ([ClipIndexEngine.fillClipIndex]) runs ONCE,
 * only when the batch drained the whole queue
 * ([BatchOutcome.moreRemaining] false) and the plan carried real work ---
 * never on an empty plan (a no-op run must not burn a fill pass) and
 * never mid-queue (a partial fill would churn on every batch).
 */
class ClipIndexQueueProcessor(
    private val engine: ClipIndexEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Live per-batch progress: [processedThisRun] photos of [batchSize]
     * done, against the full [queueDepth] the plan still owed when the
     * run started (no faces-written counter --- ONE embedding per photo
     * is the whole write story).
     */
    data class BatchProgress(
        val processedThisRun: Int,
        val batchSize: Int,
        val queueDepth: Int,
    )

    /**
     * The run's outcome. [queueDepth]/[skippedUpToDate]/
     * [skippedMetadataOnly] mirror the plan; the rest count THIS run's
     * work. [moreRemaining] is true when the plan's queue outlived this
     * batch --- the worker re-enqueues itself then. [fillReport] is
     * non-null only on the draining run of a non-empty plan.
     * [unreadableThisRun] counts photos whose bytes could not be read;
     * r50 (F-39): each now leaves a TERMINAL-SKIP state record (until its
     * revision changes), so unreadable photos can no longer keep the
     * chain re-enqueueing forever. [skippedUnreadable] mirrors the plan's
     * count of photos held out by earlier skip records.
     * [engineFailedThisRun]/[skippedEngineFailed] are the r51 (audit C-5)
     * mirrors on the engine-fault side: photos whose per-photo
     * delete/embed/put block threw (including a refused insert's
     * deliberate fault), each leaving a TERMINAL-SKIP record (until its
     * revision changes) instead of aborting the batch.
     */
    data class BatchOutcome(
        val queueDepth: Int,
        val indexedThisRun: Int,
        val orphanRowsDeletedFor: Int,
        val skippedUpToDate: Int,
        val skippedMetadataOnly: Int,
        val unreadableThisRun: Int,
        /** r50 (F-39): photos held out by earlier unreadable-skip records. */
        val skippedUnreadable: Int = 0,
        /** r51 (audit C-5): engine-fault terminal skips written THIS run. */
        val engineFailedThisRun: Int = 0,
        /** r51 (audit C-5): photos held out by earlier engine-fault records. */
        val skippedEngineFailed: Int = 0,
        val moreRemaining: Boolean,
        val fillReport: MlEngine.ClipFillReport?,
        val totalClipsInStore: Long,
    )

    /** The batch size bound; one batch's worth of photos per re-enqueue. */
    private companion object {
        const val DEFAULT_BATCH_SIZE = 50
    }

    /** See the class KDoc for the full run contract. */
    suspend fun processBatch(
        plan: ClipIndexPlan,
        stateStore: ClipIndexStateStore,
        readBytes: suspend (ClipIndexCandidate) -> ByteArray?,
        batchSize: Int = DEFAULT_BATCH_SIZE,
        nowMillis: () -> Long,
        onProgress: suspend (BatchProgress) -> Unit = {},
    ): BatchOutcome = withContext(ioDispatcher) {
        // Orphan cleanup: every orphan file's rows, this run, before any
        // inference work (upstream chunks the DELETE itself).
        if (plan.orphanFileIds.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            engine.deleteClipsForFiles(plan.orphanFileIds.toLongArray())
        }

        // Queue bookkeeping: load once, mutate per photo, save whole.
        val statesByKey = HashMap<String, ClipIndexedState>()
        stateStore.load().forEach { statesByKey[it.localMediaKey] = it }
        plan.staleStateKeys.forEach { statesByKey.remove(it) }

        val batch = plan.toIndex.take(batchSize.coerceAtLeast(0))
        var indexed = 0
        var unreadable = 0
        var engineFailed = 0
        try {
            for (candidate in batch) {
                currentCoroutineContext().ensureActive()
                val bytes = readBytes(candidate)
                if (bytes == null) {
                    unreadable++
                    // r50 (F-39): the TERMINAL-SKIP record (the same fix as
                    // the face queue's --- see FaceIndexQueue's processor):
                    // unreadable photos can no longer keep this chain
                    // re-enqueueing forever. Holds the photo out until its
                    // revision changes; carries no clip rows.
                    statesByKey[candidate.localMediaKey] = ClipIndexedState(
                        localMediaKey = candidate.localMediaKey,
                        fileId = candidate.fileId,
                        revision = candidate.revision,
                        indexedAtMillis = nowMillis(),
                        unreadable = true,
                    )
                    continue
                }
                // r51 (audit C-5): the per-photo engine-fault guard (the
                // face queue's mirror). The facade's failure shape is
                // IllegalStateException (the requireX helpers' check, the
                // Rust panic surface) and native decoder faults cross JNI
                // as RuntimeException --- a decodable-but-corrupt JPEG
                // fails in the bytes-dependent call, deterministically,
                // every run. The pre-r51 contract let that throw abort the
                // batch, so one such poison photo wedged BOTH queues at
                // the same head-of-queue item through every WorkManager
                // retry and every restart. CancellationException is an
                // IllegalStateException subclass --- rethrow it first (the
                // worker's own discipline): a cancel must never be filed
                // as an engine fault (this block has no suspension
                // points, so the rethrow is belt-and-braces).
                try {
                    // Delete-first: putClip is an UPSERT so this is
                    // redundant today, but the mapping stays identical to
                    // the face queue's (see the file KDoc) --- idempotent
                    // no-op when the file had no row, correctness-
                    // preserving if the upsert semantics ever drift.
                    // (Unconditional --- audit C-9.)
                    engine.deleteClipsForFiles(longArrayOf(candidate.fileId))
                    val embedding = engine.clipImageEmbedding(bytes)
                    if (!engine.putClip(candidate.fileId, embedding)) {
                        // The engine's own success flag refused the insert;
                        // the honest shape is a fault, never a silent drop
                        // from the index. r51 (C-5): the deliberate throw
                        // now rides the per-photo catch below --- a
                        // refused insert is deterministic PER PHOTO, so
                        // the old batch-abort shape could only ever wedge
                        // the queue at this head item; the terminal skip
                        // (counted, logged, held until revision change) is
                        // the same honesty without the wedge.
                        throw IllegalStateException(
                            "clip insert returned false for file ${candidate.fileId}",
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    // The F-39 unreadable mirror: a TERMINAL-SKIP record
                    // holds the photo out until its revision changes, and
                    // the batch CONTINUES with the next photo. Not a
                    // silent drop --- the skip is counted, logged and
                    // reported in the outcome.
                    engineFailed++
                    statesByKey[candidate.localMediaKey] = ClipIndexedState(
                        localMediaKey = candidate.localMediaKey,
                        fileId = candidate.fileId,
                        revision = candidate.revision,
                        indexedAtMillis = nowMillis(),
                        engineFailed = true,
                    )
                    DiagLog.w(
                        TAG,
                        "clip queue: engine fault on ${candidate.localMediaKey} " +
                            "(file ${candidate.fileId}): ${e.javaClass.simpleName}" +
                            (e.message?.let { ": $it" } ?: "") +
                            " --- terminal skip until revision changes",
                    )
                    continue
                }
                statesByKey[candidate.localMediaKey] = ClipIndexedState(
                    localMediaKey = candidate.localMediaKey,
                    fileId = candidate.fileId,
                    revision = candidate.revision,
                    indexedAtMillis = nowMillis(),
                )
                indexed++
                onProgress(BatchProgress(indexed, batch.size, plan.toIndex.size))
            }
        } finally {
            // Whole-photo bookkeeping survives cancellation and crashes:
            // the save itself runs OUTSIDE the cancelling context.
            withContext(NonCancellable) {
                stateStore.save(ArrayList(statesByKey.values))
            }
        }

        val moreRemaining = plan.toIndex.size > batch.size
        val fillReport = if (!moreRemaining && !plan.isEmpty) {
            // The draining run reconciles once; a cancelled run must not
            // start a half pass (the between-photos rule, one gate later
            // in the pipeline).
            currentCoroutineContext().ensureActive()
            engine.fillClipIndex()
        } else {
            null
        }

        BatchOutcome(
            queueDepth = plan.toIndex.size,
            indexedThisRun = indexed,
            orphanRowsDeletedFor = plan.orphanFileIds.size,
            skippedUpToDate = plan.skippedUpToDate,
            skippedMetadataOnly = plan.skippedMetadataOnly,
            unreadableThisRun = unreadable,
            skippedUnreadable = plan.skippedUnreadable,
            engineFailedThisRun = engineFailed,
            skippedEngineFailed = plan.skippedEngineFailed,
            moreRemaining = moreRemaining,
            fillReport = fillReport,
            totalClipsInStore = engine.clipCount(),
        )
    }
}

// The r26 queue's state persistence -------------------------------------------------

/**
 * The production [ClipIndexStateStore]: one JSON file
 * (`clip-index-state.json`) inside the SAME app-private face-index
 * directory as `ml-index.sqlite`, `index-state.json` and
 * `people-state.json` --- the directory whose recursive delete (the r24
 * Settings Reset) resets the whole ML index, so the semantic queue's
 * bookkeeping can never outlive the rows it describes.
 *
 * Write path mirrors `FaceIndexStateFile` exactly: serialize to
 * `<file>.tmp`, then an atomic NIO move over the previous file (same
 * directory = same filesystem = atomic on POSIX; a plain-filesystem
 * fallback keeps exotic mount layouts honest). A crash between batches
 * therefore loses at most the CURRENT batch's records --- those photos
 * re-index delete-first next run, which is correct, just slower. Read
 * path: FORGIVING --- a missing, empty or corrupt file is "nothing
 * processed yet", never a crash (the same never-trust-stored-bytes
 * posture as every state file in the face-index directory).
 *
 * JVM-tested directly (`ClipIndexQueueTest`): round-trips, atomic
 * overwrite, corrupt-file recovery, version discipline.
 */
class ClipIndexStateFile(
    private val file: File,
) : ClipIndexStateStore {

    override suspend fun load(): List<ClipIndexedState> {
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt(VERSION_KEY) != SUPPORTED_VERSION) return emptyList()
            val array = root.optJSONArray(STATES_KEY) ?: return emptyList()
            val out = ArrayList<ClipIndexedState>(array.length())
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

    override suspend fun save(states: List<ClipIndexedState>) {
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

        // r50 (F-39): the terminal-skip flag. Additive OPTIONAL field on
        // version 1 (old readers ignore it, new readers default false).
        const val UNREADABLE = "unreadable"

        // r51 (audit C-5): the engine-fault terminal-skip flag, under the
        // same additive-optional-field discipline as [UNREADABLE].
        const val ENGINE_FAILED = "engineFailed"

        fun stateToJson(state: ClipIndexedState): JSONObject = JSONObject()
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
            .put(UNREADABLE, state.unreadable)
            .put(ENGINE_FAILED, state.engineFailed)

        fun stateFromJson(obj: JSONObject): ClipIndexedState? {
            val key = obj.optString(KEY)
            if (key.isEmpty()) return null
            val fileId = obj.optLong(FILE_ID, Long.MIN_VALUE)
            if (fileId == Long.MIN_VALUE) return null
            return ClipIndexedState(
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
                unreadable = obj.optBoolean(UNREADABLE, false),
                engineFailed = obj.optBoolean(ENGINE_FAILED, false),
            )
        }
    }
}

/**
 * Production [ClipIndexEngine]: a thin delegation to the [MlEngine]
 * facade's r26 semantic surface (Agent A's engine-side half of this
 * round's contract), constructed with the SAME [MlEngine] instance the
 * worker opened its session on --- the [MlEngineIndexEngine] pattern,
 * CLIP half. The facade owns the JNI bridge, its state machine and its
 * synchronization; this class adds nothing.
 */
class MlEngineClipIndexEngine(private val engine: MlEngine) : ClipIndexEngine {

    override fun clipImageEmbedding(jpeg: ByteArray): FloatArray =
        engine.clipImageEmbedding(jpeg)

    override fun putClip(fileId: Long, embedding: FloatArray): Boolean =
        engine.putClip(fileId, embedding)

    override fun deleteClipsForFiles(fileIds: LongArray): Boolean =
        engine.deleteClipsForFiles(fileIds)

    override fun clipIndexedFileIds(): List<Long> = engine.clipIndexedFileIds()

    override fun clipCount(): Long = engine.clipCount()

    override fun fillClipIndex(): MlEngine.ClipFillReport = engine.fillClipIndex()
}

/** The queue file's diagnostics-ring tag (r51, the workers' DiagLog pattern). */
private const val TAG = "ClipIndexQueue"
