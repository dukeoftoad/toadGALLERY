package com.toadgallery.gallery.data.media.changes

/**
 * The r40 hardening round's PURE policy for the authoritative walk's
 * cancellation checks (ADR-0016: the decision lives in a tested pure type;
 * the Android cursor walk only consults it).
 *
 * WHY IT EXISTS: the Phase 4 walk reads a whole volume's provider cursor
 * into memory (`while (cursor.moveToNext())` over potentially 50,000 rows,
 * [roadmap Phase 10 hardening task] "background work is bounded,
 * cancellable"). The walk is blocking code inside
 * `withContext(Dispatchers.Default)` --- coroutine cancellation cannot
 * interrupt a blocking cursor loop on its own, so before r40 a cancelled
 * app-start scan kept walking the full volume to completion before the
 * driver's between-volumes cancellation check ever ran (holding the scan
 * mutex the whole time). The fix is a cooperative check every
 * [CANCELLATION_CHECK_ROWS] rows: the caller supplies the liveness probe
 * (the scan driver passes its own coroutine context's `isActive`), the
 * policy decides WHEN to consult it, and a positive probe throws
 * [kotlin.coroutines.cancellation.CancellationException] --- the one
 * exception the driver's per-volume catch rethrows untouched, so
 * cancellation propagates exactly like any other coroutine cancellation
 * (no partial checkpoint: the walk dies before `upsertAll`, the old
 * checkpoint survives, the next pass re-heals idempotently --- the
 * driver's crash-mid-apply contract).
 *
 * The period is a constant, not a parameter: 500 rows keeps the probe cost
 * (one lambda call, one `Job.isActive` read) at a rounding error of the
 * row-transport work while bounding the cancelled-walk tail to well under
 * a second on every supported device class.
 */
object ScanWalkPolicy {

    /**
     * Rows read between two cancellation probes of the authoritative walk.
     * 500 --- the same order of magnitude as the r24 index queues'
     * DEFAULT_BATCH_SIZE (50) scaled up for a cheaper-per-row operation
     * (the walk transports rows, it does no model inference).
     */
    const val CANCELLATION_CHECK_ROWS = 500

    /**
     * Whether the walk should consult the caller's liveness probe now.
     * Pure over the row counter: due on every [CANCELLATION_CHECK_ROWS]-th
     * row, never due at or below zero (a 0-row volume must not probe ---
     * and a negative count is a caller bug the probe must not mask).
     */
    fun cancellationDue(rowsRead: Int): Boolean =
        rowsRead > 0 && rowsRead % CANCELLATION_CHECK_ROWS == 0
}
