package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.diag.DiagLog

/**
 * The NULL-provenance healing pass's persistence seam (r20 round; the
 * r19-named follow-up, closed). Room implements it over the two r20 DAO
 * statements ([io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueDao
 * .rowsNeedingProvenanceHealing] / [updateProvenanceIndexedAt]); JVM tests
 * drive the pass through a fake of this interface, which is the only reason
 * it exists as a seam at all (ADR-0016).
 *
 * ## The problem this pass closes
 *
 * The scan's index hook isolates failures PER ROW (r19): a row whose
 * indexing throws (Room disk error, EXIF parse blowup) is logged, skipped,
 * and the batch continues. But the skipped row's revision did not move, so
 * the NEXT scan classifies it UNCHANGED --- the hook never sees it again,
 * and its provenance columns stay whatever they were. Before r20 there was
 * no marker to distinguish "indexed, honestly null" from "never indexed",
 * so the failure was permanent: one transient error during a scan could
 * strand a row's capture date / location forever.
 *
 * ## The fix, in two halves
 *
 * 1. [markProvenanceIndexed] is the LAST write the combined index pass
 *    makes for a row ([CombinedExifIndexer]): capture date, location, THEN
 *    the stamp. A row that made it through everything carries the stamp; a
 *    row that failed anywhere keeps `null`.
 * 2. [rowsNeedingIndexHealing] selects the `null` rows (bounded, newest
 *    first) and [ProvenanceHealingPass] re-offers them to the same hook ---
 *    idempotently: a row that succeeds is stamped and never re-selected
 *    unless a real add/change re-nulls it (which means it legitimately
 *    re-indexes), and a row that fails again simply stays selectable for
 *    the next pass.
 */
interface ProvenanceHealingStore {

    /**
     * Up to [limit] live rows never fully indexed, most recently seen
     * first. Tombstoned keys are excluded by the live-set invariant; the
     * caller re-offers exactly these rows to the index hook.
     */
    suspend fun rowsNeedingIndexHealing(limit: Int): List<CatalogueRecord>

    /**
     * Stamps [atMillis] onto the live row for [localMediaKey] as the moment
     * its indexing completed. An unknown or tombstoned key is a no-op,
     * never a resurrection (the targeted-UPDATE discipline every scan-side
     * write in this catalogue keeps).
     */
    suspend fun markProvenanceIndexed(localMediaKey: String, atMillis: Long)
}

/**
 * The healing pass itself (r20): re-offers the never-fully-indexed rows to
 * the (combined) index hook in bounded batches until the selectable set is
 * drained or the pass's work budget is spent. PURE ORCHESTRATION around
 * [ProvenanceHealingStore] and the hook --- every decision below is stated
 * here and JVM-tested; nothing Android-shaped anywhere.
 *
 * ## Trigger and budget
 *
 * The scan driver runs one pass right after the APP_START scan (see
 * [MediaScanDriver.start]) --- the same coroutine, so the pass observes the
 * catalogue the first scan just reconciled. The pass is BOUNDED
 * ([maxBatches] * [batchSize] rows per invocation) so a 50k-item cold
 * library --- where every legacy row reads `null` once, by migration
 * nature --- converges over a few app starts instead of stalling the first
 * one (the roadmap's "avoid rescanning the entire library on every
 * launch"). Rows heal newest-seen-first, so the rows the user is most
 * likely to open heal first.
 *
 * ## Concurrency (deliberately lock-free)
 *
 * The pass takes NO lock against concurrent scan passes: the catalogue is
 * transaction-per-call and every write here is a targeted partial update,
 * so an observer scan upserting a row the pass is mid-indexing costs at
 * worst a redundant re-index of that row (the upsert re-nulls the stamp,
 * the next pass re-offers it --- idempotent). Serializing would buy
 * nothing the idempotence does not already provide and would make the
 * pass a contended stranger to the driver's mutex discipline.
 *
 * ## Failure isolation
 *
 * The hook's own per-row catch (the r19 structural boundary) is the whole
 * story: a row that throws again stays `null` and remains selectable, the
 * batch and the pass continue. A pass-level failure (the store itself
 * throwing) propagates to the driver's caller, which logs it and never
 * lets it take the process down --- the same envelope every driver stage
 * runs in.
 */
class ProvenanceHealingPass(
    private val healingStore: ProvenanceHealingStore,
    private val indexHook: ScanIndexHook,
    private val batchSize: Int = HEALING_BATCH_ROWS,
    private val maxBatches: Int = MAX_BATCHES_PER_PASS,
    private val diag: DiagLog = DiagLog,
) {

    /**
     * Runs the bounded healing pass and returns how many rows were
     * RE-OFFERED to the index hook (not how many succeeded --- the hook's
     * per-row failures are invisible here by design; the stamp is the only
     * success signal, and the next pass simply stops selecting the rows
     * that made it).
     */
    suspend fun heal(): Int {
        var offered = 0
        for (batch in 0 until maxBatches) {
            val rows = healingStore.rowsNeedingIndexHealing(batchSize)
            if (rows.isEmpty()) break
            indexHook.onRecordsApplied(rows, emptyList())
            offered += rows.size
            if (rows.size < batchSize) break
        }
        if (offered > 0) {
            diag.i(TAG, "provenance healing pass offered $offered rows to the index hook")
        }
        return offered
    }

    private companion object {
        /** Diagnostics tag for the healing pass's summary line. */
        const val TAG = "ProvenanceHealing"

        /**
         * Rows per healing batch. Kept modest so each batch's hook work
         * (bytes reads through the media boundary) stays a small, honest
         * unit even on slow storage.
         */
        const val HEALING_BATCH_ROWS = 100

        /**
         * Batches per pass: 10 x 100 = at most 1000 rows re-offered per
         * app start. A cold 50k legacy library converges in ~50 starts
         * worst case, while each start stays cheap --- the roadmap's
         * launch-budget rule. (New rows never see the healing path at all:
         * they are ADDED, indexed by the live scan pass, and stamped in
         * the same breath.)
         */
        const val MAX_BATCHES_PER_PASS = 10
    }
}
