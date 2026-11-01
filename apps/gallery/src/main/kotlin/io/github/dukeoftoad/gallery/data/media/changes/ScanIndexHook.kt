package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord

/**
 * The seam between the scan driver and downstream indexing (Phase 4
 * "scan driver" roadmap item; the bridge to the Phase 5 engine workstream).
 *
 * The scan driver invokes [onRecordsApplied] after a reconcile pass has been
 * FULLY APPLIED to the catalogue --- upserts and tombstones committed, the
 * per-volume checkpoint saved --- so a hook observer always sees catalogue
 * state that agrees with what it is handed:
 *
 * - [added] are FIRST-SIGHTING records: fresh [CatalogueRecord]s converted
 *   from the scan rows via [MediaScanRow.toRecord] (metadata cache empty,
 *   write revision 0, `lastSeenAtMillis` stamped by the driver). A hook
 *   indexes them from scratch --- for these keys no engine output exists.
 * - [changed] carry the OLD record inside
 *   [CatalogueReconciler.ChangedRecord] alongside the fresh row, so the
 *   hook can compute exactly what to re-extract from the revision delta
 *   (a size move invalidates bytes-derived outputs; a date-only touch may
 *   not) instead of blindly re-running everything.
 * - Removals are deliberately NOT part of this callback: deleted keys are
 *   tombstoned in the catalogue, and how the engines learn about deaths
 *   (polling [io.github.dukeoftoad.gallery.data.catalogue
 *   .MediaCatalogue.tombstonedKeys] vs. a mirrored callback) is a later
 *   contract --- keeping this hook add/change-only keeps its failure
 *   surface and ordering obligations minimal.
 *
 * FAILURE CONTRACT: a broken hook must never fail the scan itself. The
 * driver wraps the invocation in a catch-all and writes the throwable to
 * its diagnostics log; the catalogue has already been updated by the time
 * the hook runs, so a throwing indexer loses indexing work only --- never
 * catalogue consistency, never the scan checkpoint, never the scan pass.
 * (Cf. the reconciler's role split: pure decisions here upstream, and the
 * hook is arbitrary downstream code the pipeline must survive.)
 */
fun interface ScanIndexHook {
    suspend fun onRecordsApplied(added: List<CatalogueRecord>, changed: List<CatalogueReconciler.ChangedRecord>)
}
