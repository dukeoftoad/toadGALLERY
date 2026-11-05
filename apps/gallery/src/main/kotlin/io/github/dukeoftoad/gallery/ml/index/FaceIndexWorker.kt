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
import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.data.media.revision.MediaRevision
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import io.github.dukeoftoad.gallery.ml.engine.ModelManifest
import io.github.dukeoftoad.gallery.ml.engine.ModelProvisioner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * The r24 background face-index WORKER (Workstream 5): the
 * Android-adjacent half of the queue --- it binds the real seams (the
 * Phase 4 catalogue, the content resolver, the [MlEngine] facade over the
 * JNI store, the app-private face-index directory) around the pure
 * [FaceIndexQueuePlanner] / [FaceIndexQueueProcessor] core, and lets
 * WorkManager own scheduling, process-death persistence and retries
 * (ADR-0016 applies: the logic lives in the JVM-tested pure types; this
 * class only wires).
 *
 * One run = one bounded batch:
 *  1. candidates: the catalogue's live JPEG records on the indexable
 *     volume (newest first; see [INDEXABLE_VOLUME_NAMES] for the
 *     single-volume boundary), each with its [MediaRevision] ---
 *     the Phase 4 contract the queue's invalidation wiring diff()s against;
 *  2. plan: [FaceIndexQueuePlanner] over candidates + the queue's recorded
 *     states (`index-state.json`, same face-index directory as the ML
 *     store) + the store's own indexed-file truth (the r24 JNI read);
 *  3. execute: [FaceIndexQueueProcessor] --- orphan deletes, then at most
 *     [FaceIndexQueueProcessor]'s batch size photos (bytes read one photo
 *     at a time, never the library), states persisted after the batch and
 *     on cancellation, clustering exactly once at queue drain;
 *  4. finish: the run's outcome goes into the output [Data] for the
 *     Settings card; when the plan outlived the batch
 *     ([FaceIndexQueueProcessor.BatchOutcome.moreRemaining]) the worker
 *     RE-ENQUEUES itself under the same unique name before succeeding ---
 *     the chain survives process death through WorkManager's own
 *     persistence, and cancels cleanly with the unique name.
 *
 * Failure shapes are honest, never paraphrased away: a missing engine
 * library, unprovisioned models or a store-open failure is a permanent
 * [androidx.work.ListenableWorker.Result.failure] carrying the engine's
 * own message (retrying cannot download the library); a mid-run engine
 * fault retries with WorkManager backoff up to [MAX_RUN_ATTEMPTS] and
 * then fails with the same message. A cancelled run (user Cancel / Reset)
 * simply stops --- the whole-photo bookkeeping already persisted means the
 * next run resumes exactly where the records stop.
 */
class FaceIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result =
        // r47 honesty fix (the maintainer's 2026-11-05 device screenshots:
        // "Indexing failed: indexing failed" --- the cause-less FALLBACK
        // fired, which means the worker reached a FAILED terminal state
        // with EMPTY output data: an uncaught throw from one of the
        // unguarded setup seams (the catalogue read, the state-file load,
        // the r24 JNI indexed-list call --- the MlEngine facade's contract
        // propagates the engine's IllegalStateException with the Rust
        // message) escaped doWork entirely. The whole body now rides this
        // catch-all with the SAME retry-then-fail-with-cause shape the
        // mid-run processBatch guard already had, so the next dogfood
        // report carries the REAL cause instead of the circular fallback.
        // Cancellation is rethrown --- a user Cancel/Reset is not a
        // failure (the queue's own contract, resumable by Start again).
        try {
            val app = applicationContext as ToadGalleryApplication
        val engine = MlEngine()
        if (!engine.available) {
            return Result.failure(
                Data.Builder().putString(KEY_MESSAGE, "engine library not in this build (build with the engine flag)").build(),
            )
        }

        val threshold = inputData.getDouble(KEY_THRESHOLD, IndexCoordinator.RECOMMENDED_DISTANCE_THRESHOLD)
        val provisioner = app.modelProvisioner

        // Session gates, honest order: models must be provisioned and the
        // engine initialized before the store can open; every failure is
        // permanent-with-message (no retry can fix a missing model pack).
        // r48: every gate line mirrors to the diagnostics ring (F5) --- the
        // "indexing failed, no logs" dogfood report is exactly what these
        // lines prevent.
        if (provisioner.capabilityState(ModelManifest.Capability.FACE) != ModelProvisioner.CapabilityState.Ready) {
            DiagLog.e(TAG, "face queue: FACE models not installed; failing run #$runAttemptCount")
            return Result.failure(
                Data.Builder().putString(KEY_MESSAGE, "FACE models are not installed; use the model download above").build(),
            )
        }
        when (val init = engine.initialize(provisioner.modelsDir.absolutePath)) {
            is MlEngine.State.Ready -> Unit
            is MlEngine.State.Error -> {
                DiagLog.e(TAG, "face queue: engine init failed: ${init.message}")
                return Result.failure(
                    Data.Builder().putString(KEY_MESSAGE, "engine init failed: ${init.message}").build(),
                )
            }
            else -> {
                DiagLog.e(TAG, "face queue: engine init unexpected state: $init")
                return Result.failure(
                    Data.Builder().putString(KEY_MESSAGE, "engine init: $init").build(),
                )
            }
        }

        val faceIndexDir = File(app.filesDir, FACE_INDEX_DIR_NAME)
        // r47 honesty fix, part 2: the JNI store open THROWS
            // IllegalStateException on a native failure --- it does not
            // only return false (the facade's contract propagates the
            // Rust message; `nativeStoreOpen` is documented as
            // "failure throws java.lang.IllegalStateException"), so the
            // false-return guard alone let the throw escape doWork and
            // produce the cause-less fallback the device screenshots
            // caught. The seam-named catch turns a store-open failure
            // into the honest card message WITH the engine's own cause.
            val storeOpened = try {
                engine.openFaceIndex(faceIndexDir.absolutePath)
            } catch (e: IllegalStateException) {
                DiagLog.e(TAG, "face queue: face index store threw: ${e.message}", e)
                return Result.failure(
                    Data.Builder()
                        .putString(KEY_MESSAGE, "face index store did not open: ${e.message}")
                        .build(),
                )
            }
            if (!storeOpened) {
                DiagLog.e(TAG, "face queue: face index store did not open at ${faceIndexDir.absolutePath}")
                return Result.failure(
                    Data.Builder().putString(KEY_MESSAGE, "face index store did not open").build(),
                )
            }

            val candidates = eligibleCandidates(app.mediaCatalogue.allRecords())
            val stateStore = FaceIndexStateFile(File(faceIndexDir, FACE_INDEX_STATE_FILE_NAME))
            val plan = FaceIndexQueuePlanner.plan(
                candidates = candidates,
                states = stateStore.load(),
                storeIndexedFileIds = engine.indexedFaceFileIds().toSet(),
            )
            val resolver = app.contentResolver
            val processor = FaceIndexQueueProcessor(MlEngineIndexEngine(engine))
            val outcome = try {
                processor.processBatch(
                    plan = plan,
                    stateStore = stateStore,
                    // r48 (F2): the queue's tested contract is "unreadable
                    // -> skip -> stays queued" (the JVM tests pin it with a
                    // null-returning fake), but the PRODUCTION seam threw
                    // instead: ContentResolver.openInputStream raises
                    // FileNotFoundException for a row deleted between the
                    // catalogue scan and the batch, SecurityException when a
                    // grant is gone, IOException on truncated streams ---
                    // and the candidates are processed NEWEST-FIRST, so a
                    // just-deleted photo is the MOST likely first victim,
                    // burning all retries and failing the whole queue. The
                    // runCatching wrap restores the tested skip contract.
                    readBytes = { candidate ->
                        runCatching {
                            resolver.openInputStream(Uri.parse(candidate.uri))?.use { it.readBytes() }
                        }.getOrNull()
                    },
                    distanceThreshold = threshold,
                    nowMillis = { System.currentTimeMillis() },
                    onProgress = { progress ->
                        setProgress(
                            Data.Builder()
                                .putInt(KEY_PROGRESS_PROCESSED, progress.processedThisRun)
                                .putInt(KEY_PROGRESS_BATCH, progress.batchSize)
                                .putInt(KEY_PROGRESS_DEPTH, progress.queueDepth)
                                .putInt(KEY_PROGRESS_FACES, progress.facesWrittenThisRun)
                                .build(),
                        )
                    },
                )
            } catch (e: Exception) {
                // r48 (F5): every retry and the terminal failure land in the
                // diagnostics ring with the attempt count and cause --- the
                // report this fix answers was "indexing fails" with no logs.
                DiagLog.e(TAG, "face queue: batch fault (attempt ${runAttemptCount + 1}/$MAX_RUN_ATTEMPTS): ${e.message ?: e.javaClass.simpleName}", e)
                if (runAttemptCount < MAX_RUN_ATTEMPTS) return Result.retry()
                return Result.failure(
                    Data.Builder().putString(KEY_MESSAGE, e.message ?: e.javaClass.simpleName).build(),
                )
            }

            DiagLog.i(
                TAG,
                "face queue: batch done (indexed=${outcome.indexedThisRun} faces=${outcome.facesWrittenThisRun} " +
                    "depth=${outcome.queueDepth} unreadable=${outcome.unreadableThisRun} " +
                    "clusters=${outcome.clusterSummary?.clusters ?: -1} more=${outcome.moreRemaining})",
            )

            if (outcome.moreRemaining) {
                // The plan outlived this batch: hand the same queue (and
                // threshold) to the next run before succeeding. r48 (F3):
                // APPEND_OR_REPLACE --- the old REPLACE cancelled and
                // deleted the unique work named here, INCLUDING THE RUN
                // DOING THE ENQUEUEING (WorkManager's policy deletes the
                // pending self), turning every batch boundary into a
                // cancel-vs-successor race; APPEND_OR_REPLACE chains the
                // successor AFTER this run finishes instead.
                FaceIndexScheduler.enqueue(applicationContext, threshold)
            }
        // r48 (F1): the per-run teardown (closeFaceIndex + release) is
        // REMOVED --- the r47 shape kept a `finally { engine.closeFaceIndex();
        // engine.release() }` here, but the native engine and store are
        // PROCESS-GLOBAL singletons (engine::ENGINE / store::STORE OnceLock
        // statics), so that finally killed the OTHER queue's live session
        // mid-batch: the face and clip chains run CONCURRENTLY on
        // Dispatchers.Default, and whichever finished a batch first tore
        // the engine out from under the other with
        // IllegalStateException("engine is not initialized"), failing BOTH
        // queues (the "indexing are failing" dogfood report --- exactly the
        // class of uncaught faults the r47 catch above surfaces the CAUSE
        // of; this removes the cause itself). Both init calls are
        // idempotent per half (the Rust sides no-op on an already-open
        // session), so the next run --- or the next People/Magic Search
        // session --- reuses or re-opens the resident engine. The UI
        // adapters' close() teardown while a queue runs is the REMAINING
        // half of this fix (follow-up.md F-38).
            return Result.success(outcomeData(outcome))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // r47's honesty fix (the uncaught-fault catch) + r48 (F5): the
            // uncaught fault also lands in the diagnostics ring with the
            // attempt count and cause, so the next dogfood report carries
            // BOTH the card message and the ring line.
            DiagLog.e(TAG, "face queue: uncaught ${e.javaClass.simpleName}: ${e.message} (attempt ${runAttemptCount + 1}/$MAX_RUN_ATTEMPTS)", e)
            if (runAttemptCount < MAX_RUN_ATTEMPTS) return Result.retry()
            return Result.failure(
                Data.Builder()
                    .putString(KEY_MESSAGE, "uncaught ${e.javaClass.simpleName}: ${e.message}")
                    .build(),
            )
        }

    /** The outcome as the terminal output the Settings card renders. */
    private fun outcomeData(outcome: FaceIndexQueueProcessor.BatchOutcome): Data = Data.Builder()
        .putInt(KEY_OUT_INDEXED, outcome.indexedThisRun)
        .putInt(KEY_OUT_FACES, outcome.facesWrittenThisRun)
        .putInt(KEY_OUT_ORPHANS, outcome.orphanRowsDeletedFor)
        .putInt(KEY_OUT_DEPTH, outcome.queueDepth)
        .putInt(KEY_OUT_UNREADABLE, outcome.unreadableThisRun)
        .putInt(KEY_OUT_SKIPPED_UP_TO_DATE, outcome.skippedUpToDate)
        .putInt(KEY_OUT_SKIPPED_METADATA_ONLY, outcome.skippedMetadataOnly)
        .putInt(KEY_OUT_CLUSTERS, outcome.clusterSummary?.clusters ?: -1)
        .putLong(KEY_OUT_TOTAL_FACES, outcome.totalFacesInStore)
        .build()

    private companion object {
        /** Mid-run engine faults retry (with WorkManager backoff) this often. */
        const val MAX_RUN_ATTEMPTS = 3

        /** Diagnostics-ring tag (r48, F5). */
        const val TAG = "FaceIndexWorker"
    }
}

/**
 * The queue's unique WorkManager name: every r24 run --- the Settings
 * Start button, the worker's own re-enqueue --- lands under this ONE name,
 * so at most one chain exists and Cancel/Reset kills the whole thing.
 */
const val FACE_INDEX_WORK_NAME = "face-index-queue"

/** The app-private directory the ML store, the state file and the reset share. */
const val FACE_INDEX_DIR_NAME = "face-index"

/** The queue's state file, inside [FACE_INDEX_DIR_NAME]. */
const val FACE_INDEX_STATE_FILE_NAME = "index-state.json"

/**
 * The volumes whose MediaStore `_id`s the faces table keys on. The r23
 * dogfood wrote external-primary ids (the default `MediaStoreSource`
 * surface); the r24 queue keeps that boundary --- a removable volume's
 * ids would COLLIDE with primary ids in the `(file_id, face_id)` keys
 * (two different photos, one face row namespace). `external` is the
 * pre-API-29 spelling of the same only-volume-there-is (minSdk 26).
 * Multi-volume indexing is deferred to the hardening workstream's
 * volume-aware reconciliation.
 */
val INDEXABLE_VOLUME_NAMES = setOf("external_primary", "external")

/**
 * Maps the catalogue's live records to queue candidates: JPEGs on an
 * indexable volume, identity parsed back for the faces-table file id,
 * revision assembled from the catalogue row (the Phase 4 observed truth),
 * newest-added first (the dogfood queue walks what the user just shot).
 */
internal fun eligibleCandidates(records: List<CatalogueRecord>): List<FaceIndexCandidate> =
    records.asSequence()
        .filter { it.mimeType.equals(JPEG_MIME_TYPE, ignoreCase = true) }
        .mapNotNull { record ->
            val identity = MediaIdentity.fromKeyString(record.localMediaKey) ?: return@mapNotNull null
            if (identity.volumeName !in INDEXABLE_VOLUME_NAMES) return@mapNotNull null
            FaceIndexCandidate(
                localMediaKey = record.localMediaKey,
                fileId = identity.mediaStoreId,
                revision = MediaRevision(
                    sizeBytes = record.sizeBytes,
                    dateModifiedMillis = record.dateModifiedMillis,
                    generationToken = record.generationToken,
                    metadataWriteRevision = record.metadataWriteRevision,
                ),
                uri = record.canonicalUri,
                dateAddedMillis = record.dateAddedMillis,
            )
        }
        .sortedByDescending { it.dateAddedMillis }
        .toList()

private const val JPEG_MIME_TYPE = "image/jpeg"

// The WorkManager Data keys (input, progress, output) -------------------------------

/** Input: the clustering distance threshold for this queue run. */
const val KEY_THRESHOLD = "distanceThreshold"

/** Progress/output: photos processed this run / batch size / queue depth / faces. */
const val KEY_PROGRESS_PROCESSED = "processedThisRun"
const val KEY_PROGRESS_BATCH = "batchSize"
const val KEY_PROGRESS_DEPTH = "queueDepth"
const val KEY_PROGRESS_FACES = "facesWrittenThisRun"

/** Output: the terminal outcome fields the Settings card renders. */
const val KEY_OUT_INDEXED = "indexedThisRun"
const val KEY_OUT_FACES = "facesWrittenThisRun"
const val KEY_OUT_ORPHANS = "orphanRowsDeletedFor"
const val KEY_OUT_DEPTH = "queueDepth"
const val KEY_OUT_UNREADABLE = "unreadableThisRun"
const val KEY_OUT_SKIPPED_UP_TO_DATE = "skippedUpToDate"
const val KEY_OUT_SKIPPED_METADATA_ONLY = "skippedMetadataOnly"
const val KEY_OUT_CLUSTERS = "clusters"
const val KEY_OUT_TOTAL_FACES = "totalFacesInStore"
const val KEY_MESSAGE = "message"

/**
 * The queue's WorkManager plumbing: enqueue (Settings Start + the
 * worker's self re-enqueue), cancel (Settings Cancel / Reset) and the
 * live state the Settings card collects
 * ([getWorkInfosForUniqueWorkFlow], the r24-era Flow API --- no LiveData
 * dependency). Android-adjacent glue only; untested per ADR-0016.
 */
object FaceIndexScheduler {

    /**
     * Enqueues one queue run under the unique name. r48 (F3):
     * APPEND_OR_REPLACE --- REPLACE deleted the pending unique work (which
     * includes the RUN currently enqueueing its own successor at every
     * batch boundary), racing cancel-vs-successor; APPEND_OR_REPLACE keeps
     * the one-chain invariant (at most one live chain under the name)
     * while never cancelling the running worker. A terminal (failed /
     * cancelled) existing chain is replaced, so a fresh Start after a
     * failure begins a new chain as before.
     */
    fun enqueue(context: Context, distanceThreshold: Double) {
        val request = OneTimeWorkRequestBuilder<FaceIndexWorker>()
            .setInputData(
                Data.Builder().putDouble(KEY_THRESHOLD, distanceThreshold).build(),
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(FACE_INDEX_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Cancels the whole chain (current run + anything enqueued). */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FACE_INDEX_WORK_NAME)
    }

    /**
     * The card's live view of the chain. The unique work carries at most
     * one [WorkInfo]; its state maps to [FaceIndexWorkState], terminal
     * states kept (the card shows the LAST outcome until the next start).
     */
    fun observe(context: Context): Flow<FaceIndexWorkState> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(FACE_INDEX_WORK_NAME)
            .map { infos -> FaceIndexWorkState.of(infos.firstOrNull()) }
}

/**
 * The queue's coarse state for the Settings card. [Running] carries the
 * live progress snapshot; [Succeeded]/[Failed] carry the terminal output
 * data (see the KEY_OUT_* keys); [Idle] means no chain ever ran (or the
 * chain was cancelled before finishing).
 */
sealed class FaceIndexWorkState {

    /** No work record under the unique name. */
    object Idle : FaceIndexWorkState()

    /** Enqueued but not running yet. */
    object Enqueued : FaceIndexWorkState()

    /** Running; the progress snapshot from the worker's setProgress. */
    data class Running(
        val processedThisRun: Int,
        val batchSize: Int,
        val queueDepth: Int,
        val facesWrittenThisRun: Int,
    ) : FaceIndexWorkState()

    /** Terminal success; the outcome data the card renders. */
    data class Succeeded(val output: Data) : FaceIndexWorkState()

    /** Terminal failure; [message] is the engine's own text. */
    data class Failed(val message: String) : FaceIndexWorkState()

    /** Cancelled (user Cancel/Reset) --- resumable by starting again. */
    object Cancelled : FaceIndexWorkState()

    companion object {
        internal fun of(info: WorkInfo?): FaceIndexWorkState = when (info?.state) {
            null -> Idle
            WorkInfo.State.ENQUEUED -> Enqueued
            WorkInfo.State.RUNNING -> Running(
                processedThisRun = info.progress.getInt(KEY_PROGRESS_PROCESSED, 0),
                batchSize = info.progress.getInt(KEY_PROGRESS_BATCH, 0),
                queueDepth = info.progress.getInt(KEY_PROGRESS_DEPTH, 0),
                facesWrittenThisRun = info.progress.getInt(KEY_PROGRESS_FACES, 0),
            )
            WorkInfo.State.SUCCEEDED -> Succeeded(info.outputData)
            WorkInfo.State.FAILED -> Failed(
                // r47 honesty fix: the fallback can no longer be the
                // circular "indexing failed" --- if the worker dies
                // without writing KEY_MESSAGE (uncaught termination), the
                // card must SAY so instead of parroting the failure label.
                info.outputData.getString(KEY_MESSAGE)
                    ?: "worker ended without a message (uncaught termination)",
            )
            WorkInfo.State.CANCELLED -> Cancelled
            WorkInfo.State.BLOCKED -> Enqueued
        }
    }
}
