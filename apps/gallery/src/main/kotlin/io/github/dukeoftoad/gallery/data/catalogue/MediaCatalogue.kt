package io.github.dukeoftoad.gallery.data.catalogue

/**
 * The persistent media catalogue of Phase 4 (ADR-0006's Room persistence
 * layer; audit contract items "Room catalogue" and "MediaChangeFeed" ---
 * generation feed + ContentObserver + reconciliation + tombstones).
 *
 * The catalogue answers one question fast: "what did the media world look
 * at the last authoritative scan, per local media key?" Everything the
 * incremental pipeline does --- deciding what to index, what to re-index and
 * what to tombstone --- is derived by diffing a fresh scan against this
 * state (see [io.github.dukeoftoad.gallery.data.media.changes
 * .CatalogueReconciler]), which is why the surface is deliberately small:
 * upsert, point-read, bulk-read, tombstone, checkpoint, purge.
 *
 * DELETES ARE TOMBSTONES, never hard erasures of history: [tombstone]
 * removes the live record but leaves a dated tombstone behind so that (a) a
 * removable volume that is merely UNMOUNTED does not get its items
 * re-indexed from scratch when it returns, and (b) delete/revoke events stay
 * observable by the AI engines (the audit demands "delete/revoke
 * tombstones", because a hard delete would silently orphan engine-owned
 * rows with no way to tell "gone" from "never seen"). Tombstones are
 * cleared automatically when an upsert reports the item live again, and
 * aged out by [purgeTombstonesOlderThan] once the retention window has
 * passed.
 *
 * [ScanCheckpoint] records, per volume, the generation token and wall clock
 * of the last completed authoritative scan. On API 30+ the generation token
 * lets the next scan SKIP work entirely when the volume has not moved; on
 * API 26-29 (and for unknown volumes) the token is `null` and the
 * ContentObserver signal is the only change hint --- the checkpoint's
 * [ScanCheckpoint.scannedAtMillis] still bounds how stale the catalogue can
 * be.
 *
 * Implementations: [RoomMediaCatalogue][io.github.dukeoftoad.gallery.data
 * .catalogue.room.RoomMediaCatalogue] (production, ADR-0016-thin, untested)
 * and [InMemoryMediaCatalogue] (JVM-pure, reference semantics, heavily
 * unit-tested). Callers depend on THIS interface, never on a concrete
 * implementation --- the Phase 4 plan's package-boundary rule.
 */
interface MediaCatalogue {

    /**
     * Inserts or replaces the records for the given keys (keyed by
     * [CatalogueRecord.localMediaKey]). Upserting a key that is currently
     * tombstoned RESURRECTS it: the record goes back into the live set and
     * the tombstone is dropped, because the item demonstrably exists again.
     */
    suspend fun upsertAll(records: List<CatalogueRecord>)

    /** The live record for [localMediaKey], or `null` if unknown/tombstoned. */
    suspend fun record(localMediaKey: String): CatalogueRecord?

    /** Every live record; tombstoned keys are excluded. */
    suspend fun allRecords(): List<CatalogueRecord>

    /** [allRecords] as key -> record; tombstoned keys are excluded. */
    suspend fun recordsMap(): Map<String, CatalogueRecord>

    /**
     * Moves the given keys from the live set into the tombstone table at
     * [atMillis] (epoch millis, the moment the authoritative scan that
     * missed them completed). Keys without a live record may still be
     * tombstoned --- a driver may want to record "this key is dead" even
     * when the live row was already gone.
     */
    suspend fun tombstone(localMediaKeys: List<String>, atMillis: Long)

    /** Every currently tombstoned key, regardless of age. */
    suspend fun tombstonedKeys(): Set<String>

    /**
     * Records the completion of an authoritative scan of [volumeName] at
     * [scannedAtMillis] with the volume's [generationToken] (`null` where
     * the platform offers none). Replaces any previous checkpoint for the
     * volume.
     */
    suspend fun saveScanCheckpoint(
        volumeName: String,
        generationToken: String?,
        scannedAtMillis: Long,
    )

    /** The last checkpoint for [volumeName], or `null` if never scanned. */
    suspend fun scanCheckpoint(volumeName: String): ScanCheckpoint?

    /**
     * Drops tombstones strictly older than [cutoffMillis]. Retention policy
     * is the caller's decision (grace period for unmounted removable
     * volumes vs. unbounded growth); the catalogue only executes it. Live
     * records are never touched.
     */
    suspend fun purgeTombstonesOlderThan(cutoffMillis: Long)
}

/**
 * The per-volume fingerprint of the last completed authoritative scan.
 *
 * On API 30+, [generationToken] is the MediaStore volume generation: if a
 * new scan reads the same token, NOTHING on the volume changed and the scan
 * can skip the reconcile entirely. Below API 30 there is no generation
 * (the observer is the only signal), so the token is `null` and
 * [scannedAtMillis] is all the checkpoint can promise.
 */
data class ScanCheckpoint(
    /** The MediaStore volume name (`external_primary`, a volume UUID, ...). */
    val volumeName: String,
    /** The volume generation token at scan time; `null` on API < 30. */
    val generationToken: String?,
    /** Epoch millis of the scan's completion. */
    val scannedAtMillis: Long,
)
