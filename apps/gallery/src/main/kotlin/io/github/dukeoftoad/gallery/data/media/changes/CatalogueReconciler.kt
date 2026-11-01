package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord

/**
 * Diffs one authoritative MediaStore scan against the catalogue's cached
 * state (Phase 4 contract, "Incremental discovery": skip already indexed
 * revisions; handle deletes, updates and permission changes).
 *
 * This object is the piece the future AI incremental index is built on:
 * `added` rows get their first indexing pass, `changed` rows get exactly
 * the re-index their revision delta justifies (the old record travels along
 * in [ChangedRecord] so callers can compute what to invalidate), and
 * `removedKeys` drives TOMBSTONING --- never a hard delete, because deletes
 * must stay observable and recoverable (audit: "delete/revoke tombstones";
 * see [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue]).
 *
 * It is PURE (no Android imports, no clock, no I/O) by ADR-0016: the
 * reconciler's rules are the highest-value logic in the incremental
 * pipeline and are therefore exhaustively unit-tested on the JVM.
 *
 * THE RULES, and why each is what it is:
 *
 * - A scan row with no catalogue record is ADDED --- first sighting, index
 *   from scratch.
 * - A row whose [MediaScanRow.sizeBytes], [MediaScanRow.dateModifiedMillis]
 *   or [MediaScanRow.generationToken] differs from the cached record is
 *   CHANGED --- the provider row moved and downstream AI outputs are
 *   invalidated. The generation token participates even though size and
 *   date usually move with it: some external edits (and whole-volume
 *   events like a restore) bump the generation while leaving per-row size
 *   and date untouched, and the audit's revision token exists precisely to
 *   catch those.
 * - Everything else is UNCHANGED --- including rows whose ONLY delta is
 *   [CatalogueRecord.metadataWriteRevision] (that counter is written by
 *   this app's own metadata edits; treating it as an external change would
 *   re-index bytes that never moved) and rows that differ only in fields
 *   outside the revision contract (canonicalUri, mimeType, dateAdded,
 *   lastSeenAtMillis): a Uri re-resolution or a provider re-add must not
 *   cost an embedding recompute.
 * - A catalogue record with no scan row is REMOVED --- the driver
 *   tombstones these keys.
 *
 * [ignoreTombstones] exists for scans that are NOT authoritative over the
 * whole catalogue --- a single-volume scan while other volumes (possibly
 * unmounted removable storage) hold live records, or an observer-triggered
 * probe whose row set was never complete. For those, "absent from this
 * scan" does not mean "deleted", so removedKeys --- the tombstone drivers
 * --- is suppressed. Added/changed detection is unaffected: a row the scan
 * DID see is evidence, regardless of how partial the scan was.
 */
object CatalogueReconciler {

    /**
     * The outcome of one reconciliation pass.
     *
     * Invariants the drivers rely on: every scan row lands in exactly one
     * of [added] / [changed] / the [unchangedCount]; no key appears in two
     * buckets; [removedKeys] contains only keys that were present in
     * [catalogueRecords] and absent from the scan.
     */
    data class Reconciliation(
        /** Rows with no catalogue record --- index from scratch. */
        val added: List<MediaScanRow>,
        /** Rows whose observed revision moved --- re-index what the delta touches. */
        val changed: List<ChangedRecord>,
        /** How many rows matched the cached revision exactly. */
        val unchangedCount: Int,
        /**
         * Cached keys the scan did not see --- tombstone candidates; empty
         * when the caller passed `ignoreTombstones = true`.
         */
        val removedKeys: List<String>,
    )

    /**
     * A row whose observed revision moved, paired with the record it
     * replaces. The old record is carried --- not just its key --- so the
     * driver can diff old-vs-new revision itself (e.g. to decide whether a
     * size change invalidates embeddings while a pure date touch does not).
     */
    data class ChangedRecord(
        /** The cached record the scan has just outdated. */
        val old: CatalogueRecord,
        /** The fresh provider row that supersedes it. */
        val new: MediaScanRow,
    )

    /**
     * Reconciles [scanRows] (one row per local media key --- the contract
     * of an authoritative scan walk) against [catalogueRecords] (the live
     * map from [io.github.dukeoftoad.gallery.data.catalogue
     * .MediaCatalogue.recordsMap]; tombstoned keys must already be excluded
     * by the caller, so a resurrected item correctly reports as ADDED and
     * gets re-indexed from scratch). See the object KDoc for the rules.
     */
    fun reconcile(
        scanRows: List<MediaScanRow>,
        catalogueRecords: Map<String, CatalogueRecord>,
        ignoreTombstones: Boolean = false,
    ): Reconciliation {
        val added = mutableListOf<MediaScanRow>()
        val changed = mutableListOf<ChangedRecord>()
        var unchangedCount = 0
        val seenKeys = HashSet<String>(scanRows.size * 2)

        for (row in scanRows) {
            // An authoritative scan walks each provider row once; the guard
            // merely keeps the invariants honest (exactly one bucket per
            // row) if a caller ever violates that precondition --- the
            // first sighting wins.
            if (!seenKeys.add(row.localMediaKey)) continue

            val record = catalogueRecords[row.localMediaKey]
            when {
                record == null -> added.add(row)
                row.changedVersus(record) -> changed.add(ChangedRecord(old = record, new = row))
                else -> unchangedCount++
            }
        }

        val removedKeys =
            if (ignoreTombstones) {
                emptyList()
            } else {
                // Map iteration order (insertion order for the in-memory
                // catalogue) --- drivers treat the list as a set of
                // candidates, not a ranked one.
                catalogueRecords.keys.filter { it !in seenKeys }
            }

        return Reconciliation(
            added = added,
            changed = changed,
            unchangedCount = unchangedCount,
            removedKeys = removedKeys,
        )
    }

    /**
     * The revision comparison, isolated so its exact field set is legible
     * and auditable: size, date_modified, generation token --- nothing
     * else, deliberately. See the object KDoc for why metadataWriteRevision
     * and the access-handle fields are excluded.
     */
    private fun MediaScanRow.changedVersus(record: CatalogueRecord): Boolean =
        sizeBytes != record.sizeBytes ||
            dateModifiedMillis != record.dateModifiedMillis ||
            generationToken != record.generationToken
}
