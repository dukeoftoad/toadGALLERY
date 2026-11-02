package io.github.dukeoftoad.gallery.data.catalogue

import io.github.dukeoftoad.gallery.data.media.dates.ResolvedCaptureDate
import kotlinx.coroutines.flow.Flow

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
 * upsert, point-read, bulk-read, tombstone, checkpoint, purge --- plus the
 * r12 capture-date partial update ([updateCaptureDate]), the one targeted
 * write the scan's index pass needs, and the r13 timeline surface
 * ([timelineWindow] / [liveItemCount] / [changes]), the windowed reads the
 * Photos timeline is switching onto (the roadmap's "timeline's sort/group
 * keys switch to the persisted capture dates").
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

    /**
     * The targeted capture-date write (r12 round): persists the capture
     * date resolved by the scan's index pass
     * ([CombinedExifIndexer][io.github.dukeoftoad.gallery.data.media.changes
     * .CombinedExifIndexer] since r20; the r12/r16 standalone indexers it
     * absorbed) onto the live row for [localMediaKey] ---
     * ONLY the three provenance fields
     * ([CatalogueRecord.resolvedCaptureDateMillis],
     * [CatalogueRecord.resolvedCaptureDateZoneOffsetMinutes],
     * [CatalogueRecord.resolvedCaptureDateSource]); every other column of
     * the row is left exactly as it is. `date == null` clears the
     * provenance (the item was indexed and its metadata carried no
     * trustworthy capture-date tag).
     *
     * WHY A TARGETED PARTIAL UPDATE INSTEAD OF A WHOLE-RECORD UPSERT: the
     * indexer runs from the scan driver while UI-driven writes (favorites,
     * metadata-write revision bumps, future cache-state updates) may touch
     * the same rows concurrently. The catalogue's concurrency guarantee is
     * transaction-PER-CALL --- never read-modify-write atomicity --- so a
     * read-copy-upsert round trip here would silently clobber whatever the
     * other writer changed between this caller's read and write. A partial
     * update that does not carry the other columns cannot clobber them;
     * the cost (an extra surface member) is the honest price of that
     * safety.
     *
     * An unknown or tombstoned key is a NO-OP, never a resurrection: the
     * indexer's evidence can be older than a tombstone (the item was
     * deleted between the scan walk and the index pass), and only an
     * authoritative scan's [upsertAll] may bring a key back to life.
     */
    suspend fun updateCaptureDate(localMediaKey: String, date: ResolvedCaptureDate?)

    /** The live record for [localMediaKey], or `null` if unknown/tombstoned. */
    suspend fun record(localMediaKey: String): CatalogueRecord?

    /** Every live record; tombstoned keys are excluded. */
    suspend fun allRecords(): List<CatalogueRecord>

    /** [allRecords] as key -> record; tombstoned keys are excluded. */
    suspend fun recordsMap(): Map<String, CatalogueRecord>

    // --- timeline surface (r13 round) --------------------------------------

    /**
     * Window [offset, offset+limit) of LIVE records in the timeline's total order.
     *
     * THE ORDER (the timeline's row supply contract): records are ordered by
     * their EFFECTIVE instant ---
     * `COALESCE(resolvedCaptureDateMillis, dateAddedMillis)` --- in
     * [direction], tiebroken by [CatalogueRecord.localMediaKey] in the SAME
     * direction. The capture date wins over the provider's date_added
     * wherever one was resolved (that is the entire point of the r12
     * provenance columns); the date_added fallback keeps pre-index rows and
     * genuinely undateable items in a stable place.
     *
     * THE TIEBREAK IS REQUIRED, exactly like `TimelineSort`'s `_id` tiebreak:
     * many items share one instant (burst shots at one EXIF second;
     * date_added-second granularity on the fallback), and a window boundary
     * can fall between them. Without a total order the implementation would
     * be free to reorder tied rows between window reads, and the windows
     * would then TILE badly --- the same row surfacing in two windows (a
     * DUPLICATE LazyGrid key crash) or none (a silently skipped photo). The
     * key is unique per record, so (effective instant, key) is a genuine
     * total order and consecutive windows cover the sequence exactly once,
     * with no gaps and no overlaps, in both directions.
     *
     * Argument discipline: a negative [offset] or non-positive [limit]
     * yields an empty list (never a whole-table read --- SQLite treats a
     * negative LIMIT as "unbounded"); an offset at/after the end likewise
     * yields an empty list. Windows are read against a snapshot of the live
     * set; pair them with [changes] to know when to re-read.
     */
    suspend fun timelineWindow(
        offset: Int,
        limit: Int,
        direction: TimelineDirection,
    ): List<CatalogueRecord>

    /**
     * Number of live (non-tombstoned) records.
     *
     * The denominator of the timeline's windowed reads: callers pair it with
     * [timelineWindow] to compute page counts and "end reached" signals.
     * Tombstoned keys are not in the live set at all, so they are excluded
     * by construction.
     */
    suspend fun liveItemCount(): Int

    /**
     * Emits after every catalogue mutation batch (Room invalidation / in-memory hook).
     *
     * The signal a timeline consumer collects to know its windows may be
     * stale: every mutation that can change the live set or its ORDER emits
     * --- [upsertAll] (non-empty batch), [updateCaptureDate] (a resolved
     * capture date moves a record's effective instant, i.e. reorders the
     * timeline) and [tombstone] (non-empty list). Purge
     * ([purgeTombstonesOlderThan]) and checkpoint saves never emit: they
     * cannot change the live set or its order.
     *
     * Hot-signal semantics, deliberately identical in both implementations:
     * there is NO initial emission on collection and NO replay of emissions
     * that fired while nobody was collecting (the Room implementation drops
     * its flow query's built-in initial result for exactly this reason) ---
     * callers perform their initial read independently and treat this as a
     * pure invalidation signal, e.g. `changes().collectLatest { re-read }`.
     * Conflated delivery is acceptable (the payload is Unit: "something
     * changed, re-read"), so a burst of scan mutations may collapse into
     * one delivered emission. The one accepted divergence: tombstoning keys
     * that have no live record emits on the in-memory reference (it writes
     * tombstone rows) but not on Room (no live-row write fires the observed
     * table's invalidation) --- over-emission is a harmless redundant
     * re-read, while every change to the LIVE set emits in both.
     */
    fun changes(): Flow<Unit>

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

/**
 * The direction of [MediaCatalogue.timelineWindow]'s total order.
 *
 * Mirrors `TimelineSort`'s two directions (the UI's existing sort menu) but
 * lives here, on the catalogue, because the ordering decision --- effective
 * instant, key tiebreak --- is the DATA layer's to make now that the
 * catalogue is the timeline's row supply. Pure JVM by the `TimelineSort`
 * convention: no android.* imports, so the ordering rules are unit-testable
 * without an emulator.
 */
enum class TimelineDirection {
    /**
     * Newest effective instant first; ties break on descending
     * `localMediaKey` (the same-direction tiebreak that keeps windows
     * tiling --- see [MediaCatalogue.timelineWindow]).
     */
    NEWEST_FIRST,

    /**
     * Oldest effective instant first; ties break on ascending
     * `localMediaKey` --- ascending the timeline is the same total order
     * walked backwards, so paging stays stable in both directions.
     */
    OLDEST_FIRST,
}
