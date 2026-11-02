package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Who asked for a scan pass. Deliberately inert in the decision itself
 * ([ScanDriverPolicy.decide] compares tokens only) --- the trigger exists
 * so diagnostics say WHY a pass ran and so a caller can special-case its
 * own manual refresh at the call site.
 */
enum class ScanTrigger {
    /** The one authoritative pass at process start (see [MediaScanDriver.start]). */
    APP_START,

    /** A change-feed observer fired (coalesced --- see [MediaScanDriver.onMediaChanged]). */
    OBSERVER,

    /** An explicit user- or code-driven refresh. */
    MANUAL,
}

/** The per-volume outcome of the scan decision: cheap skip or authoritative walk. */
enum class ScanDecision {
    /**
     * Nothing to do: the volume's live generation token equals the saved
     * scan checkpoint, the platform's own proof that nothing moved.
     */
    SKIP,

    /**
     * Pull the authoritative rows and reconcile them against the catalogue
     * (adds, changes, tombstones, checkpoint save).
     */
    FULL_RESCAN,
}

/**
 * The Phase 4 scan driver's PURE decision core (ADR-0016: no android.*
 * imports, exhaustively JVM-tested by `ScanDriverPolicyTest`).
 *
 * WHY APP START IS THE TRIGGER: the roadmap's still-open Phase 4 item is
 * "the scan driver that wires the catalogue + change feed into app start".
 * App start is the only moment the process is guaranteed to run with no
 * assumptions about what changed since the last run --- the device may have
 * been off for a week and any other app may have edited MediaStore
 * meanwhile; only a fresh comparison of the saved scan checkpoints against
 * the live generation tokens can tell whether the catalogue still describes
 * reality. Starting the pipeline from Application.onCreate (launched
 * asynchronously, see [MediaScanDriver.start]) means the first UI is backed
 * by a catalogue that is either provably fresh or actively being
 * reconciled, and every later change arrives through the change feed's
 * observers instead of a per-screen re-query.
 */
object ScanDriverPolicy {

    /**
     * Decides, for one volume, between a cheap no-op and a full
     * authoritative re-scan. This is the [MediaChangeFeed] contract's
     * decision table, stated once and unit-tested: the caller supplies both
     * tokens as opaque strings and the policy knows nothing about where
     * they came from.
     *
     * The rules, each justified against the contract:
     *
     * - [checkpointToken] `null` -> [ScanDecision.FULL_RESCAN]. Null means
     *   "never scanned" (no ScanCheckpoint for the volume) or "the platform
     *   had no token when we last scanned" (API 26-29, where
     *   [MediaChangeFeed.currentGeneration] is always null). Either way
     *   there is nothing to compare against; only the walk can establish
     *   the truth.
     * - [liveToken] `null` -> [ScanDecision.FULL_RESCAN]. Per the contract,
     *   null never means "unchanged" --- it means "you cannot tell without
     *   scanning". This covers API 26-29 (no generation exists at all: the
     *   observer alone cannot prove the volume unchanged) and the
     *   unknown-volume case on 30+ (unmounted / never existed).
     * - Tokens EQUAL (both non-null) -> [ScanDecision.SKIP]. MediaStore
     *   bumps the per-volume generation on ANY change anywhere on the
     *   volume; a token that survived since the last COMPLETED scan is the
     *   platform's own proof that nothing moved. Skipping is the entire
     *   payoff of the generation contract on API 30+.
     * - Tokens DIFFER -> [ScanDecision.FULL_RESCAN]. Something changed
     *   somewhere; WHICH items is unknowable from a volume-wide counter ---
     *   that is [CatalogueReconciler]'s job, fed by an authoritative walk.
     * - The [trigger] does NOT participate in the decision. APP_START,
     *   OBSERVER and MANUAL passes with identical arguments MUST produce
     *   identical decisions (the unit suite pins this); the trigger is
     *   carried for diagnostics only.
     */
    fun decide(trigger: ScanTrigger, checkpointToken: String?, liveToken: String?): ScanDecision =
        when {
            // Both tokens null (API 26-29, or a never-scanned volume on a
            // tokenless platform) land on the first arm: with neither a
            // checkpoint nor a live token, the walk is unconditional.
            checkpointToken == null -> ScanDecision.FULL_RESCAN
            liveToken == null -> ScanDecision.FULL_RESCAN
            liveToken == checkpointToken -> ScanDecision.SKIP
            else -> ScanDecision.FULL_RESCAN
        }

    /**
     * The record to upsert for a reconciliation CHANGED pair: the fresh
     * row's MediaStore-observed revision merged with the IN-APP state the
     * scan cannot know. Extracted from the driver loop as a pure function
     * because these rules are exactly the kind that silently rot inline.
     *
     * - [CatalogueRecord.metadataWriteRevision] carries over from the OLD
     *   record: it counts THIS APP's own EXIF/IPTC/XMP writes, which an
     *   external revision bump does not undo; resetting it to
     *   [MediaScanRow.toRecord]'s 0 would make the counter jump backwards.
     * - [CatalogueRecord.metadataCacheState] degrades to STALE unless it
     *   was EMPTY: the provider row moved, so any cached metadata snapshot
     *   predates the current revision and must be re-read before trust (the
     *   safe direction --- see [MetadataCacheState]). EMPTY stays EMPTY:
     *   there was never a snapshot to stale out.
     * - [CatalogueRecord.lastSeenAtMillis] is stamped from [lastSeenAtMillis]
     *   --- the wall-clock moment of the scan that observed the row live,
     *   the driver's job per [MediaScanRow.toRecord]'s contract.
     * - The DISPLAY/SHAPE ride-alongs (displayName, width, height,
     *   durationMs, isFavorite) come from the FRESH row verbatim, via
     *   [MediaScanRow.toRecord]: a CHANGED row's record mirrors its
     *   last-seen provider row. An UNCHANGED row produces no record here at
     *   all (the reconciler drops it into `unchangedCount` and the driver
     *   upserts nothing), so its stored copy survives until the next
     *   revision-visible change --- together with the reconciler's
     *   revision-only comparison this is why display data refreshes for
     *   free on real changes but never CAUSES one. The r19 folder/bucket
     *   pair (bucketId, bucketDisplayName) rides the exact same way ---
     *   row-sourced, never hook-refilled, so it must survive every upsert
     *   unchanged (the MediaScanRow r19 field-group note).
     */
    fun recordForAppliedChange(
        changed: CatalogueReconciler.ChangedRecord,
        lastSeenAtMillis: Long,
    ): CatalogueRecord =
        changed.new.toRecord().copy(
            metadataWriteRevision = changed.old.metadataWriteRevision,
            metadataCacheState = when (changed.old.metadataCacheState) {
                MetadataCacheState.EMPTY -> MetadataCacheState.EMPTY
                // CACHED and STALE both mean "a snapshot exists"; the
                // external revision move outranks any staleness it already
                // had, so both land in STALE.
                else -> MetadataCacheState.STALE
            },
            lastSeenAtMillis = lastSeenAtMillis,
        )
}

/**
 * The Phase 4 scan driver (roadmap item: "the scan driver that wires the
 * catalogue + change feed into app start") --- the orchestration layer that
 * makes the r10 contracts run.
 *
 * ## The pipeline, end to end
 *
 * 1. [MediaScanRowSource.volumes] reports the volumes to consider (the
 *    external volumes MediaStore knows; below Q, the single classic
 *    external volume).
 * 2. Per volume, [ScanDriverPolicy.decide] compares the catalogue's saved
 *    [io.github.dukeoftoad.gallery.data.catalogue.ScanCheckpoint] token
 *    against the live token from [MediaChangeFeed.currentGeneration]:
 *    equal -> cheap SKIP; anything else (never scanned, tokenless
 *    platform, changed token) -> a full authoritative walk.
 * 3. On FULL_RESCAN, [MediaScanRowSource.scanVolume] pulls the
 *    authoritative [MediaScanRow]s, [CatalogueReconciler] diffs them
 *    against the volume's live catalogue records, and the driver APPLIES
 *    the result in a fixed order: upserts first (added + changed records,
 *    which also resurrects tombstoned keys the scan saw live), then
 *    tombstones for the keys the scan missed, then
 *    [MediaCatalogue.saveScanCheckpoint] LAST --- the checkpoint may only
 *    claim the volume after the catalogue reflects the walk, so a crash
 *    mid-apply leaves the old checkpoint and the next pass heals the
 *    volume idempotently.
 * 4. Finally the [ScanIndexHook] (if one is wired) receives the
 *    added + changed records for downstream indexing --- wrapped in a
 *    catch-all, because a broken indexer must never fail the scan (see the
 *    hook's failure contract).
 *
 * ## Concurrency discipline
 *
 * All scans run serialized under one mutex and off the main dispatcher
 * ([Dispatchers.Default]): the catalogue, the reconciler and the hook are
 * written by exactly one pass at a time. Observer firings are COALESCED:
 * an observer storm during a scan leaves one pending flag, and exactly one
 * queued coroutine consumes it and runs a single follow-up pass --- the
 * rest of the storm drains out through the flag as no-ops (one queued
 * re-scan beats three racing ones, per the change-feed contract). A
 * per-volume failure is caught, logged to [DiagLog] and never aborts the
 * remaining volumes; only cancellation propagates.
 *
 * Untested as a whole (ADR-0016: it is coroutine/I-O orchestration around
 * the tested pure types), but every decision in it lives in
 * [ScanDriverPolicy], [CatalogueReconciler] or the catalogue contracts ---
 * this class only sequences them.
 */
class MediaScanDriver(
    private val rowSource: MediaScanRowSource,
    private val catalogue: MediaCatalogue,
    private val changeFeed: MediaChangeFeed,
    private val scanIndexHook: ScanIndexHook?,
    private val scanScope: CoroutineScope,
    private val healingPass: ProvenanceHealingPass? = null,
    private val diag: DiagLog = DiagLog,
) {

    /** Serializes scan passes; see the class KDoc's concurrency discipline. */
    private val scanMutex = Mutex()

    /**
     * Set by every observer firing, consumed by the single queued
     * coroutine that runs the follow-up pass (see [onMediaChanged]).
     */
    private val observerPending = AtomicBoolean(false)

    /** The change-feed unregister functions; see [unregisterObservers]. */
    private val unregisterFns = mutableListOf<() -> Unit>()

    /**
     * The volumes this driver has already registered an observer for
     * (r20 volume hot-plug): registration is IDEMPOTENT against this set,
     * so a volume that appears after process start (SD card inserted,
     * USB OTG) gets its observer exactly once no matter how many mount
     * broadcasts arrive, and [unregisterObservers] resets the set along
     * with the registrations.
     */
    private val registeredVolumes = mutableSetOf<String>()

    /**
     * One-shot app-start entry: registers the change-feed observers (off
     * the main thread --- registration is binder work, and onCreate must
     * stay cheap), launches the first [ScanTrigger.APP_START] pass into
     * [appScope], and --- when a [healingPass] is wired --- runs one
     * bounded NULL-provenance healing pass right after the scan in the
     * SAME coroutine (see [ProvenanceHealingPass]): the healing pass then
     * observes exactly the catalogue the first scan just reconciled, with
     * no lock of its own to take (its writes are targeted partial updates
     * against a transaction-per-call catalogue, and its rows are selected
     * by the stamp, so a racing observer scan costs at worst a redundant
     * re-index of a row that legitimately re-indexed anyway).
     *
     * The APP_START pass is what reconciles everything that happened
     * while the process was dead; observer signals cover only the live
     * process from here on.
     */
    fun start(appScope: CoroutineScope) {
        appScope.launch {
            registerObservers()
            runScan(ScanTrigger.APP_START)
            if (healingPass != null) {
                try {
                    healingPass.heal()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // The healing pass is best-effort housekeeping: a
                    // failure costs the stranded rows their retry THIS
                    // start, never the process (they stay selectable and
                    // the next start re-offers them).
                    diag.e(TAG, "provenance healing pass failed", t)
                }
            }
        }
    }

    /**
     * Registers a change-feed observer for every reported volume not
     * already observed ([registeredVolumes]; r20 made this idempotent so
     * the hot-plug callback below can call it freely). The callbacks carry
     * no payload and no debounce (the feed's contract) --- coalescing is
     * THIS class's job, see [onMediaChanged]. Registrations are resources:
     * [unregisterObservers] releases them deterministically.
     */
    fun registerObservers() {
        for (volume in rowSource.volumes()) {
            val isNew = synchronized(registeredVolumes) {
                if (volume in registeredVolumes) {
                    false
                } else {
                    registeredVolumes += volume
                    true
                }
            }
            if (!isNew) continue
            val unregister = changeFeed.registerObserver(volume) { onMediaChanged() }
            synchronized(unregisterFns) { unregisterFns += unregister }
        }
    }

    /**
     * The volume hot-plug entry (r20, the r19-named follow-up): a storage
     * volume appeared after process start (SD card inserted, USB OTG
     * mounted). The pre-r20 driver observed only the volumes that existed
     * when it registered at app start, so a hot-plugged volume's changes
     * never reached the pipeline until the next process restart.
     *
     * This method (called by the platform mount broadcast shell, see
     * `AndroidVolumeMonitor`) does the two things the restart used to be
     * needed for: it registers observers for the NEW volumes only
     * (idempotent via [registeredVolumes]) and fires [onMediaChanged] ---
     * the coalesced scan valve --- so the fresh volume gets a pass over
     * the same serialized path every other scan takes. A volume that
     * merely DISAPPEARED needs no call: [scanAllVolumes] walks only the
     * volumes the row source reports, so an unmounted volume's rows keep
     * their live records until it returns (and its observer firing on
     * unmount is just another coalesced no-change pass).
     */
    fun onVolumesChanged() {
        registerObservers()
        onMediaChanged()
    }

    /** Releases every observer registration [registerObservers] made. */
    fun unregisterObservers() {
        synchronized(unregisterFns) {
            unregisterFns.forEach { it() }
            unregisterFns.clear()
        }
        synchronized(registeredVolumes) { registeredVolumes.clear() }
    }

    /**
     * Runs one full scan pass over every reported volume (skips are
     * per-volume and cheap). Serialized with any other pass via
     * [scanMutex]; all execution happens off the main dispatcher.
     */
    suspend fun runScan(trigger: ScanTrigger) {
        scanMutex.withLock {
            try {
                withContext(Dispatchers.Default) { scanAllVolumes(trigger) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // A failed pass must still leave the driver usable for the
                // next trigger; per-volume failures are already isolated
                // below, this catches the pass-level envelope (e.g. volume
                // enumeration) --- see [scanAllVolumes].
                diag.e(TAG, "scan pass failed trigger=$trigger", t)
            }
        }
    }

    /**
     * The observer storm valve, per the change-feed contract ("one queued
     * re-scan beats three racing ones"): every firing sets the pending
     * flag and queues one coroutine; only the coroutine that FINDS the
     * flag set when it reaches the mutex front runs the follow-up pass ---
     * the rest observe the flag already consumed and exit as no-ops. A
     * hundred firings during one scan therefore cost one extra pass, not a
     * hundred queued scans.
     */
    private fun onMediaChanged() {
        observerPending.set(true)
        scanScope.launch {
            scanMutex.withLock {
                if (observerPending.getAndSet(false)) {
                    try {
                        withContext(Dispatchers.Default) { scanAllVolumes(ScanTrigger.OBSERVER) }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        diag.e(TAG, "observer scan pass failed", t)
                    }
                }
            }
        }
    }

    /**
     * The decision loop: per volume, checkpoint vs live token, then either
     * a cheap skip line or the full walk. The live token is read ONCE per
     * volume, BEFORE the walk, and the scan saves THAT token: any change
     * landing mid-walk bumps the generation after our read, so the saved
     * checkpoint stays behind and the next pass re-scans --- a read-after
     * -walk token could claim freshness over rows the walk never saw.
     */
    private suspend fun scanAllVolumes(trigger: ScanTrigger) {
        val volumes = try {
            rowSource.volumes()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            diag.e(TAG, "volume enumeration failed trigger=$trigger", t)
            return
        }
        for (volume in volumes) {
            try {
                val checkpointToken = catalogue.scanCheckpoint(volume)?.generationToken
                val liveToken = changeFeed.currentGeneration(volume)
                when (ScanDriverPolicy.decide(trigger, checkpointToken, liveToken)) {
                    ScanDecision.SKIP ->
                        diag.i(TAG, "scan skip volume=$volume trigger=$trigger generation=$liveToken")
                    ScanDecision.FULL_RESCAN -> rescanVolume(volume, liveToken, trigger)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                // One broken volume (unmounted SD, provider crash) must
                // never cost the other volumes their pass.
                diag.e(TAG, "scan failed volume=$volume trigger=$trigger", t)
            }
        }
    }

    /**
     * The authoritative walk of one volume and the apply sequence (see the
     * class KDoc for the ordering rationale: upserts, tombstones,
     * checkpoint last, hook after everything is committed).
     *
     * The catalogue records are filtered to THIS volume via the keys'
     * [MediaIdentity] volume segment: the reconciler's removedKeys are
     * tombstone drivers, so records living on other volumes (possibly
     * unmounted removable storage this pass did not walk) must never enter
     * the diff --- absence from THIS walk is not death for THEM.
     */
    private suspend fun rescanVolume(volume: String, liveToken: String?, trigger: ScanTrigger) {
        val rows = rowSource.scanVolume(volume)
        val liveRecords = catalogue.recordsMap().filterKeys { key ->
            MediaIdentity.fromKeyString(key)?.volumeName == volume
        }
        val reconciliation = CatalogueReconciler.reconcile(rows, liveRecords)
        val scannedAt = System.currentTimeMillis()
        val addedRecords = reconciliation.added.map { it.toRecord().copy(lastSeenAtMillis = scannedAt) }
        val changedRecords = reconciliation.changed.map {
            ScanDriverPolicy.recordForAppliedChange(it, scannedAt)
        }

        // Apply, in the fixed order the class KDoc justifies. Upserts and
        // tombstones never touch the same key (reconciler invariant), so
        // the sequence is order-safe; the checkpoint lands last.
        catalogue.upsertAll(addedRecords + changedRecords)
        if (reconciliation.removedKeys.isNotEmpty()) {
            catalogue.tombstone(reconciliation.removedKeys, scannedAt)
        }
        catalogue.saveScanCheckpoint(volume, liveToken, scannedAt)

        diag.i(
            TAG,
            "scan applied volume=$volume trigger=$trigger rows=${rows.size} " +
                "added=${addedRecords.size} changed=${changedRecords.size} " +
                "removed=${reconciliation.removedKeys.size}",
        )

        // Downstream indexing, AFTER the catalogue is consistent. The hook
        // is arbitrary code: a failure in it loses indexing work only ---
        // never the catalogue state, never the checkpoint, never the scan
        // pass (see the ScanIndexHook failure contract).
        if (scanIndexHook != null && (addedRecords.isNotEmpty() || reconciliation.changed.isNotEmpty())) {
            try {
                scanIndexHook.onRecordsApplied(addedRecords, reconciliation.changed)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                diag.e(TAG, "scan index hook failed volume=$volume (scan unaffected)", t)
            }
        }
    }

    private companion object {
        /** Diagnostics tag for the scan driver's lines. */
        const val TAG = "ScanDriver"
    }
}
