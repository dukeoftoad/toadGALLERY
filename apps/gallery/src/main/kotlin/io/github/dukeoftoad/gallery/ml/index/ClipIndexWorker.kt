package io.github.dukeoftoad.gallery.ml.index

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import io.github.dukeoftoad.gallery.ml.engine.ModelManifest
import io.github.dukeoftoad.gallery.ml.engine.ModelProvisioner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The r26 background semantic-index WORKER (Workstream 7): the
 * Android-adjacent half of the CLIP queue --- the SIBLING of the r24
 * [FaceIndexWorker], binding the real seams (the Phase 4 catalogue, the
 * content resolver, the [MlEngine] facade over the JNI store, the
 * app-private face-index directory --- the SAME directory, because the
 * CLIP rows live in the SAME `ml-index.sqlite` the faces do) around the
 * pure [ClipIndexQueuePlanner] / [ClipIndexQueueProcessor] core.
 * WorkManager owns scheduling, process-death persistence and retries
 * (ADR-0016 applies: the logic lives in the JVM-tested pure types; this
 * class only wires).
 *
 * One run = one bounded batch:
 *  1. session gates, in the honest order (each a permanent
 *     failure-with-message --- no retry can download a library or a
 *     model pack): engine library present -> SEMANTIC models
 *     provisioned -> `initSemantic` (r39: installs the semantic session
 *     from scratch when none exists --- the semantic-only session --- or
 *     upgrades an existing face session; the FACE pack and
 *     `initialize` are NO LONGER gates, the r26 round's documented
 *     limitation dropped; see `docs/phase-5/ente-exit.md`) ->
 *     `openFaceIndex`;
 *  2. candidates: the r24 face worker's own [eligibleCandidates] filter
 *     (JPEGs on the indexable external-primary volume, newest first ---
 *     the three-gate `EligiblePublicMedia` discipline: vault media are
 *     never catalogue rows while sealed, so they can never become
 *     candidates), mapped into [ClipIndexCandidate]s;
 *  3. plan: [ClipIndexQueuePlanner] over candidates + the queue's
 *     recorded states (`clip-index-state.json`, same face-index
 *     directory) + the store's own clip-indexed-file truth;
 *  4. execute: [ClipIndexQueueProcessor] --- orphan deletes, then at
 *     most its batch size photos (bytes read one photo at a time, never
 *     the library; ONE whole-photo embedding per photo), states
 *     persisted after the batch and on cancellation, the reconciliation
 *     pass exactly once at queue drain;
 *  5. finish: the run's outcome goes into the output [Data] for the
 *     Settings card; when the plan outlived the batch
 *     ([ClipIndexQueueProcessor.BatchOutcome.moreRemaining]) the worker
 *     RE-ENQUEUES itself under the same unique name before succeeding ---
 *     the chain survives process death through WorkManager's own
 *     persistence, and cancels cleanly with the unique name.
 *
 * Failure shapes are honest, never paraphrased away: a missing engine
 * library, unprovisioned SEMANTIC models, a semantic-init or store-open
 * failure is a permanent
 * [androidx.work.ListenableWorker.Result.failure] carrying the engine's
 * own message; a mid-run engine fault retries with WorkManager backoff
 * up to [MAX_RUN_ATTEMPTS] and then fails with the same message. A
 * cancelled run (user Cancel / Reset) simply stops --- the whole-photo
 * bookkeeping already persisted means the next run resumes exactly where
 * the records stop.
 */
class ClipIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        // r47 honesty fix (the maintainer's 2026-11-05 device screenshots:
        // "Semantic indexing failed: semantic indexing failed" --- the
        // cause-less FALLBACK fired, which means the worker reached a
        // FAILED terminal state with EMPTY output data: an uncaught throw
        // from one of the unguarded setup seams (the catalogue read, the
        // state-file load, the r26 JNI clip-indexed-list call --- the
        // MlEngine facade's contract propagates the engine's
        // IllegalStateException with the Rust message) escaped doWork
        // entirely. The whole body now rides this catch-all with the SAME
        // retry-then-fail-with-cause shape the mid-run processBatch guard
        // already had, so the next dogfood report carries the REAL cause
        // instead of the circular fallback. Cancellation is rethrown --- a
        // user Cancel/Reset is not a failure (the queue's own contract,
        // resumable by Start again).
        try {
            val app = applicationContext as ToadGalleryApplication
        val engine = MlEngine()
        if (!engine.available) {
            return Result.failure(
                Data.Builder().putString(KEY_CLIP_MESSAGE, "engine library not in this build (build with the engine flag)").build(),
            )
        }

        val provisioner = app.modelProvisioner

        // Session gates, honest order. The r39 shape: the semantic queue
        // needs ONLY the SEMANTIC pack --- `initSemantic` installs the
        // session from scratch when none exists (the semantic-only
        // session), so the FACE capability gate and the `initialize`
        // call the r26 worker carried are gone (the round's documented
        // limitation, dropped; `docs/phase-5/ente-exit.md`).
        if (provisioner.capabilityState(ModelManifest.Capability.SEMANTIC) != ModelProvisioner.CapabilityState.Ready) {
            return Result.failure(
                Data.Builder().putString(KEY_CLIP_MESSAGE, "SEMANTIC models are not installed; use the semantic model download above").build(),
            )
        }
        when (val semantic = engine.initSemantic(provisioner.modelsDir.absolutePath)) {
            is MlEngine.SemanticState.Ready -> Unit
            is MlEngine.SemanticState.Error ->
                return Result.failure(
                    Data.Builder().putString(KEY_CLIP_MESSAGE, "semantic init failed: ${semantic.message}").build(),
                )
            else ->
                return Result.failure(
                    Data.Builder().putString(KEY_CLIP_MESSAGE, "semantic init: $semantic").build(),
                )
        }

        val faceIndexDir = File(app.filesDir, FACE_INDEX_DIR_NAME)
        try {
            // r47 honesty fix, part 2: the JNI store open THROWS
            // IllegalStateException on a native failure --- it does not
            // only return false (the facade's contract propagates the
            // Rust message; `nativeStoreOpen` is documented as
            // "failure throws java.lang.IllegalStateException"), so the
            // false-return guard alone let the throw escape doWork and
            // produce the cause-less fallback the device screenshots
            // caught. The seam-named catch turns a store-open failure
            // into the honest card message WITH the engine's own cause.
            // (The clip queue shares the SAME ml-index.sqlite store the
            // face queue opens --- one failing store breaks both queues,
            // which is exactly the field evidence: both cards failed
            // with the same cause-less message.)
            val storeOpened = try {
                engine.openFaceIndex(faceIndexDir.absolutePath)
            } catch (e: IllegalStateException) {
                return Result.failure(
                    Data.Builder()
                        .putString(KEY_CLIP_MESSAGE, "face index store did not open: ${e.message}")
                        .build(),
                )
            }
            if (!storeOpened) {
                return Result.failure(
                    Data.Builder().putString(KEY_CLIP_MESSAGE, "face index store did not open").build(),
                )
            }

            // The r24 face worker's own filter (JPEG + indexable volume +
            // newest-first), reused verbatim so the two queues can never
            // disagree about what is indexable; mapped into the clip
            // queue's own candidate type.
            val candidates = eligibleCandidates(app.mediaCatalogue.allRecords())
                .map { record ->
                    ClipIndexCandidate(
                        localMediaKey = record.localMediaKey,
                        fileId = record.fileId,
                        revision = record.revision,
                        uri = record.uri,
                        dateAddedMillis = record.dateAddedMillis,
                    )
                }
            val stateStore = ClipIndexStateFile(File(faceIndexDir, CLIP_INDEX_STATE_FILE_NAME))
            val plan = ClipIndexQueuePlanner.plan(
                candidates = candidates,
                states = stateStore.load(),
                storeIndexedFileIds = engine.clipIndexedFileIds().toSet(),
            )
            val resolver = app.contentResolver
            val processor = ClipIndexQueueProcessor(MlEngineClipIndexEngine(engine))
            val outcome = try {
                processor.processBatch(
                    plan = plan,
                    stateStore = stateStore,
                    readBytes = { candidate ->
                        resolver.openInputStream(Uri.parse(candidate.uri))?.use { it.readBytes() }
                    },
                    nowMillis = { System.currentTimeMillis() },
                    onProgress = { progress ->
                        setProgress(
                            Data.Builder()
                                .putInt(KEY_CLIP_PROGRESS_PROCESSED, progress.processedThisRun)
                                .putInt(KEY_CLIP_PROGRESS_BATCH, progress.batchSize)
                                .putInt(KEY_CLIP_PROGRESS_DEPTH, progress.queueDepth)
                                .build(),
                        )
                    },
                )
            } catch (e: Exception) {
                if (runAttemptCount < MAX_RUN_ATTEMPTS) return Result.retry()
                return Result.failure(
                    Data.Builder().putString(KEY_CLIP_MESSAGE, e.message ?: e.javaClass.simpleName).build(),
                )
            }

            if (outcome.moreRemaining) {
                // The plan outlived this batch: hand the same queue to the
                // next run before succeeding. REPLACE keeps exactly one
                // chain alive under the unique name.
                ClipIndexScheduler.enqueue(applicationContext)
            }
            return Result.success(outcomeData(outcome))
        } finally {
            engine.closeFaceIndex()
            engine.release()
        }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < MAX_RUN_ATTEMPTS) return Result.retry()
            return Result.failure(
                Data.Builder()
                    .putString(KEY_CLIP_MESSAGE, "uncaught ${e.javaClass.simpleName}: ${e.message}")
                    .build(),
            )
        }

    /** The outcome as the terminal output the Settings card renders. */
    private fun outcomeData(outcome: ClipIndexQueueProcessor.BatchOutcome): Data = Data.Builder()
        .putInt(KEY_CLIP_OUT_INDEXED, outcome.indexedThisRun)
        .putInt(KEY_CLIP_OUT_ORPHANS, outcome.orphanRowsDeletedFor)
        .putInt(KEY_CLIP_OUT_DEPTH, outcome.queueDepth)
        .putInt(KEY_CLIP_OUT_UNREADABLE, outcome.unreadableThisRun)
        .putInt(KEY_CLIP_OUT_SKIPPED_UP_TO_DATE, outcome.skippedUpToDate)
        .putInt(KEY_CLIP_OUT_SKIPPED_METADATA_ONLY, outcome.skippedMetadataOnly)
        .putString(KEY_CLIP_OUT_FILL_OUTCOME, outcome.fillReport?.outcome)
        .putInt(KEY_CLIP_OUT_FILL_ROWS, outcome.fillReport?.rows ?: -1)
        .putInt(KEY_CLIP_OUT_FILL_INDEXED, outcome.fillReport?.indexed ?: -1)
        .putInt(KEY_CLIP_OUT_FILL_SKIPPED, outcome.fillReport?.skipped ?: -1)
        .putBoolean(KEY_CLIP_OUT_FILL_RESUMED, outcome.fillReport?.resumed ?: false)
        .putLong(KEY_CLIP_OUT_TOTAL_CLIPS, outcome.totalClipsInStore)
        .build()

    private companion object {
        /** Mid-run engine faults retry (with WorkManager backoff) this often. */
        const val MAX_RUN_ATTEMPTS = 3
    }
}

/**
 * The semantic queue's unique WorkManager name: every r26 run --- the
 * Settings Start button, the worker's own re-enqueue --- lands under this
 * ONE name (the r24 `FACE_INDEX_WORK_NAME` naming style, its own string
 * so one chain per queue exists and Cancel/Reset kills only the queue it
 * names).
 */
const val CLIP_INDEX_WORK_NAME = "clip-index-queue"

/** The semantic queue's state file, inside [FACE_INDEX_DIR_NAME] (r26). */
const val CLIP_INDEX_STATE_FILE_NAME = "clip-index-state.json"

// The WorkManager Data keys (input, progress, output), clip-flavored so
// the two queues' keys can never be interchanged at the Data level.

/** Progress/output: photos processed this run / batch size / queue depth. */
const val KEY_CLIP_PROGRESS_PROCESSED = "clipProcessedThisRun"
const val KEY_CLIP_PROGRESS_BATCH = "clipBatchSize"
const val KEY_CLIP_PROGRESS_DEPTH = "clipQueueDepth"

/** Output: the terminal outcome fields the Settings card renders. */
const val KEY_CLIP_OUT_INDEXED = "clipIndexedThisRun"
const val KEY_CLIP_OUT_ORPHANS = "clipOrphanRowsDeletedFor"
const val KEY_CLIP_OUT_DEPTH = "clipQueueDepth"
const val KEY_CLIP_OUT_UNREADABLE = "clipUnreadableThisRun"
const val KEY_CLIP_OUT_SKIPPED_UP_TO_DATE = "clipSkippedUpToDate"
const val KEY_CLIP_OUT_SKIPPED_METADATA_ONLY = "clipSkippedMetadataOnly"

/**
 * Output: the once-per-drain reconciliation pass's report. The outcome
 * string is the engine's own; the counters are `-1` when no fill pass
 * ran (an empty or non-draining run), the flag the card distinguishes
 * the shapes on.
 */
const val KEY_CLIP_OUT_FILL_OUTCOME = "clipFillOutcome"
const val KEY_CLIP_OUT_FILL_ROWS = "clipFillRows"
const val KEY_CLIP_OUT_FILL_INDEXED = "clipFillIndexed"
const val KEY_CLIP_OUT_FILL_SKIPPED = "clipFillSkipped"
const val KEY_CLIP_OUT_FILL_RESUMED = "clipFillResumed"

/** Output: total CLIP rows in the store after the run. */
const val KEY_CLIP_OUT_TOTAL_CLIPS = "totalClipsInStore"

/** Output: the failure message of a permanent failure shape. */
const val KEY_CLIP_MESSAGE = "clipMessage"

/**
 * The semantic queue's WorkManager plumbing: enqueue (Settings Start +
 * the worker's self re-enqueue), cancel (Settings Cancel / Reset) and
 * the live state the Settings card collects
 * ([getWorkInfosForUniqueWorkFlow], the r24-era Flow API --- no LiveData
 * dependency). Android-adjacent glue only; untested per ADR-0016.
 */
object ClipIndexScheduler {

    /** Enqueues one semantic queue run under the unique name (REPLACE: one chain). */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<ClipIndexWorker>()
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(CLIP_INDEX_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** Cancels the whole chain (current run + anything enqueued). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(CLIP_INDEX_WORK_NAME)
    }

    /**
     * The card's live view of the chain. The unique work carries at most
     * one [WorkInfo]; its state maps to [ClipIndexWorkState], terminal
     * states kept (the card shows the LAST outcome until the next start).
     */
    fun observe(context: Context): Flow<ClipIndexWorkState> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(CLIP_INDEX_WORK_NAME)
            .map { infos -> ClipIndexWorkState.of(infos.firstOrNull()) }
}

/**
 * The semantic queue's coarse state for the Settings card (the r24
 * [FaceIndexWorkState] shape, CLIP flavor). [Running] carries the live
 * progress snapshot; [Succeeded]/[Failed] carry the terminal output data
 * (see the KEY_CLIP_OUT_* keys); [Idle] means no chain ever ran (or the
 * chain was cancelled before finishing).
 */
sealed class ClipIndexWorkState {

    /** No work record under the unique name. */
    object Idle : ClipIndexWorkState()

    /** Enqueued but not running yet. */
    object Enqueued : ClipIndexWorkState()

    /** Running; the progress snapshot from the worker's setProgress. */
    data class Running(
        val processedThisRun: Int,
        val batchSize: Int,
        val queueDepth: Int,
    ) : ClipIndexWorkState()

    /** Terminal success; the outcome data the card renders. */
    data class Succeeded(val output: Data) : ClipIndexWorkState()

    /** Terminal failure; [message] is the engine's own text. */
    data class Failed(val message: String) : ClipIndexWorkState()

    /** Cancelled (user Cancel/Reset) --- resumable by starting again. */
    object Cancelled : ClipIndexWorkState()

    companion object {
        internal fun of(info: WorkInfo?): ClipIndexWorkState = when (info?.state) {
            null -> Idle
            WorkInfo.State.ENQUEUED -> Enqueued
            WorkInfo.State.RUNNING -> Running(
                processedThisRun = info.progress.getInt(KEY_CLIP_PROGRESS_PROCESSED, 0),
                batchSize = info.progress.getInt(KEY_CLIP_PROGRESS_BATCH, 0),
                queueDepth = info.progress.getInt(KEY_CLIP_PROGRESS_DEPTH, 0),
            )
            WorkInfo.State.SUCCEEDED -> Succeeded(info.outputData)
            WorkInfo.State.FAILED -> Failed(
                // r47 honesty fix: the fallback can no longer be the
                // circular "semantic indexing failed" --- if the worker
                // dies without writing KEY_CLIP_MESSAGE (uncaught
                // termination), the card must SAY so instead of parroting
                // the failure label.
                info.outputData.getString(KEY_CLIP_MESSAGE)
                    ?: "worker ended without a message (uncaught termination)",
            )
            WorkInfo.State.CANCELLED -> Cancelled
            WorkInfo.State.BLOCKED -> Enqueued
        }
    }
}
