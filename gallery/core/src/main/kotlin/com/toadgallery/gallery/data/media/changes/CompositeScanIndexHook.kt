package com.toadgallery.gallery.data.media.changes

import com.toadgallery.gallery.data.catalogue.CatalogueRecord
import com.toadgallery.gallery.data.diag.DiagLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fans ONE [ScanIndexHook] callback out to N hooks (the r16 wiring seam,
 * moved to this package by the r20 round --- exactly the migration its
 * KDoc promised when a third indexer landed): the driver accepts a single
 * hook, and the pipeline has had more than one indexing concern since r16,
 * so this adapter runs them in order with PER-HOOK failure isolation --- a
 * throwing indexer is logged to [DiagLog] and the NEXT one still runs,
 * which is the same "a broken hook must never fail the scan" contract the
 * driver itself upholds around the whole invocation
 * ([MediaScanDriver] wraps the composite call in its own catch-all too;
 * this class keeps one indexer's failure from starving its sibling).
 */
class CompositeScanIndexHook(
    private val hooks: List<ScanIndexHook>,
) : ScanIndexHook {

    override suspend fun onRecordsApplied(
        added: List<CatalogueRecord>,
        changed: List<CatalogueReconciler.ChangedRecord>,
    ) {
        for (hook in hooks) {
            try {
                hook.onRecordsApplied(added, changed)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DiagLog.e(TAG, "scan index hook failed (sibling hooks unaffected)", t)
            }
        }
    }

    private companion object {
        /** Diagnostics tag for the fan-out's failure isolation lines. */
        const val TAG = "CompositeIndexHook"
    }
}
