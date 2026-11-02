package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.media.revision.MediaRevision
import io.github.dukeoftoad.gallery.data.media.revision.RevisionDelta

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
 * - A row whose observed REVISION moved versus the cached record is
 *   CHANGED --- the provider row moved and downstream AI outputs are
 *   invalidated. The comparison DELEGATES to [MediaRevision.diff], so the
 *   repo's ONE revision rule exists in ONE place: a token that merely
 *   APPEARS or DISAPPEARS is new observability of the same bytes, not a
 *   content change (the rule `MediaRevisionTest` pins), and the
 *   reconciler's CHANGED is exactly [RevisionDelta.CONTENT_CHANGED] ---
 *   not a second, subtly different inline field comparison. The token
 *   compared here is each row's OWN per-row generation-modified value
 *   (`MediaStore.MediaColumns.GENERATION_MODIFIED`, API 30+), NOT the
 *   per-volume `MediaStore.getGeneration` scan checkpoint: some external
 *   edits rewrite bytes in place while leaving per-row size and date
 *   untouched, and the row's own generation stamp catches those --- while
 *   the volume checkpoint advances whenever ANY item on the volume
 *   changes, so stamping or comparing it per row would report every item
 *   as changed after every edit anywhere (audit finding C). The volume
 *   checkpoint lives in `ScanCheckpoint` /
 *   `MediaChangeFeed.currentGeneration` and never enters this comparison:
 *   a volume-generation advance with all per-row revisions identical
 *   leaves every row UNCHANGED.
 * - Everything else is UNCHANGED --- including rows whose ONLY delta is
 *   [CatalogueRecord.metadataWriteRevision] (that counter is written by
 *   this app's own metadata edits; treating it as an external change would
 *   re-index bytes that never moved), rows whose only delta is a
 *   generationToken APPEARING or DISAPPEARING (an OS upgrade exposing
 *   generations, a tokenless platform --- new observability of the same
 *   bytes, per [MediaRevision.diff]'s both-present rule), and rows that
 *   differ only in fields outside the revision contract (canonicalUri,
 *   mimeType, dateAdded, lastSeenAtMillis): a Uri re-resolution or a
 *   provider re-add must not cost an embedding recompute.
 *   DRIVER NOTE: an unchanged row is never re-upserted, so a record whose
 *   stored token is null while the platform now exposes one keeps the
 *   null until the row's next CONTENT change adopts the fresh row --- the
 *   comparison stays stable across scans (null vs present is UNCHANGED
 *   every time), so no loop and no drift.
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
     * and auditable: it DELEGATES to [MediaRevision.diff] --- the repo's
     * one revision rule --- and reports CHANGED exactly when the verdict
     * is [RevisionDelta.CONTENT_CHANGED]. The old inline comparison
     * (`generationToken != record.generationToken`) treated a token
     * APPEARING or DISAPPEARING as CHANGED, contradicting MediaRevision's
     * own pinned contract ("a change in OBSERVABILITY, not in content")
     * --- two test suites pinned opposite behaviors for the same input;
     * this delegation leaves ONE rule in ONE place.
     *
     * Only the three provider-observed fields enter the revisions built
     * here: size, date_modified, and the row's OWN per-row
     * generation-modified token (`MediaColumns.GENERATION_MODIFIED`) ---
     * nothing else, deliberately. The token must never be the per-volume
     * `MediaStore.getGeneration` scan checkpoint, which moves whenever
     * any item on the volume changes (audit finding C).
     * [CatalogueRecord.metadataWriteRevision] is IN-APP state the
     * provider row cannot observe; feeding it into the diff would make
     * every in-app EXIF write masquerade as an external edit (see the
     * object KDoc), and [MediaRevision.contentFingerprint] stays null on
     * both sides because a scan row does not carry one.
     */
    private fun MediaScanRow.changedVersus(record: CatalogueRecord): Boolean =
        revision().diff(record.revision()) == RevisionDelta.CONTENT_CHANGED

    /**
     * The provider-observed revision half a scan row contributes to the
     * comparison (see [changedVersus] for the field-set rationale).
     */
    private fun MediaScanRow.revision(): MediaRevision = MediaRevision(
        sizeBytes = sizeBytes,
        dateModifiedMillis = dateModifiedMillis,
        generationToken = generationToken,
    )

    /**
     * The catalogue's provider-observed revision half: the same three
     * fields as the row side, deliberately without the record's in-app
     * [CatalogueRecord.metadataWriteRevision] (see [changedVersus]).
     */
    private fun CatalogueRecord.revision(): MediaRevision = MediaRevision(
        sizeBytes = sizeBytes,
        dateModifiedMillis = dateModifiedMillis,
        generationToken = generationToken,
    )
}
