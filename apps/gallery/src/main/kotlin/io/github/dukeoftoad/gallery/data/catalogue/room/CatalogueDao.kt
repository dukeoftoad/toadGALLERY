package io.github.dukeoftoad.gallery.data.catalogue.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * The data-access surface of the Phase 4 media catalogue (ADR-0006).
 *
 * Deliberately a bare statement-per-operation DAO: there is no join, no
 * projection and no SQL-side business logic, because per ADR-0016 Room code
 * is untested --- every decision that matters (what changed, what to
 * tombstone, what to purge) lives in pure, JVM-tested types
 * ([io.github.dukeoftoad.gallery.data.media.changes.CatalogueReconciler]
 * for the diff; the caller for policy). The DAO only stores and fetches
 * whole rows.
 *
 * Two operations are composite [Transaction]s (Phase 4 audit finding D):
 * [removeItemsAndWriteTombstones] (delete + tombstone in one transaction)
 * and [upsertItemsAndClearTombstones] (upsert + tombstone clear in one
 * transaction), so a process interruption can never leave the paired
 * halves half-applied. `@Transaction` on suspend DAO methods is the
 * suspend-native spelling of `RoomDatabase.runInTransaction` and needs no
 * API beyond the already-present room-ktx coroutine support.
 *
 * All functions are `suspend`: room-ktx dispatches them off the calling
 * thread, so callers never block the main thread on disk I/O.
 *
 * Room 2.7 notes: [Upsert] handles the insert-or-replace semantics the
 * catalogue needs (a rescan writes the same primary key with refreshed
 * fields) without the race a manual SELECT-then-INSERT pair would have.
 */
@Dao
interface CatalogueDao {

    // --- Live records ------------------------------------------------------

    /** Inserts or replaces every given row, keyed by localMediaKey. */
    @Upsert
    suspend fun upsertItems(items: List<CatalogueItemEntity>)

    /** The live row for [localMediaKey], or `null` when unknown/tombstoned. */
    @Query("SELECT * FROM catalogue_items WHERE localMediaKey = :localMediaKey")
    suspend fun itemByLocalMediaKey(localMediaKey: String): CatalogueItemEntity?

    /** Every live row. Tombstoned keys are not in this table at all. */
    @Query("SELECT * FROM catalogue_items")
    suspend fun allItems(): List<CatalogueItemEntity>

    /**
     * Removes the live rows for [localMediaKeys] (the tombstone half of the
     * delete --- see [CatalogueTombstoneEntity] for why removal is paired
     * with a tombstone write, never performed alone).
     */
    @Query("DELETE FROM catalogue_items WHERE localMediaKey IN (:localMediaKeys)")
    suspend fun deleteItemsByLocalMediaKey(localMediaKeys: List<String>)

    /**
     * The targeted capture-date write (r12 round): updates ONLY the three
     * capture-date provenance columns of the row, leaving every other
     * column (revision fields, metadata-cache state, counters) untouched.
     *
     * Why a partial UPDATE instead of a whole-row upsert: the capture-date
     * indexer runs concurrently with UI-driven catalogue writes, and the
     * catalogue's guarantee is transaction-PER-CALL, never
     * read-modify-write atomicity. A read-copy-upsert round trip would
     * clobber any field another writer changed between the read and the
     * write; this statement cannot --- it does not carry the other
     * columns at all. A no-op on an absent key (Room UPDATE matches zero
     * rows) is deliberate: the indexer must never resurrect a tombstoned
     * row (only [upsertItemsAndClearTombstones], driven by an
     * authoritative scan, may).
     */
    @Query(
        "UPDATE catalogue_items SET " +
            "resolvedCaptureDateMillis = :dateMillis, " +
            "resolvedCaptureDateZoneOffsetMinutes = :zoneOffsetMinutes, " +
            "resolvedCaptureDateSource = :sourceName " +
            "WHERE localMediaKey = :localMediaKey",
    )
    suspend fun updateCaptureDateColumns(
        localMediaKey: String,
        dateMillis: Long?,
        zoneOffsetMinutes: Int?,
        sourceName: String?,
    )

    /**
     * The atomic remove/tombstone transition (Phase 4 audit finding D):
     * deletes the live rows AND writes their tombstones inside ONE Room
     * transaction, so a process interruption can leave the catalogue in
     * exactly two states --- both halves applied, or neither. Before this
     * wrapper existed a crash between the two writes left a live row
     * without a tombstone (healed by the next scan, but only by then).
     * Idempotent: re-running the same call converges to the same state
     * (deleting absent rows and re-upserting the same tombstone are no-ops
     * in effect). Callers reach this via [RoomMediaCatalogue.tombstone].
     */
    @Transaction
    suspend fun removeItemsAndWriteTombstones(
        localMediaKeys: List<String>,
        tombstones: List<CatalogueTombstoneEntity>,
    ) {
        deleteItemsByLocalMediaKey(localMediaKeys)
        upsertTombstones(tombstones)
    }

    // --- Tombstones ---------------------------------------------------------

    /** Writes/refreshes the tombstone rows for the given keys. */
    @Upsert
    suspend fun upsertTombstones(tombstones: List<CatalogueTombstoneEntity>)

    /** Every tombstone row, regardless of age. */
    @Query("SELECT * FROM catalogue_tombstones")
    suspend fun allTombstones(): List<CatalogueTombstoneEntity>

    /**
     * Clears tombstones for resurrected keys --- called on upsert, because
     * an item present in an authoritative scan is live and its old death
     * record would otherwise keep lying to callers and compaction.
     */
    @Query("DELETE FROM catalogue_tombstones WHERE localMediaKey IN (:localMediaKeys)")
    suspend fun deleteTombstonesByLocalMediaKey(localMediaKeys: List<String>)

    /**
     * The atomic upsert/restore transition (Phase 4 audit finding D):
     * upserts the live rows AND clears their tombstones inside ONE Room
     * transaction, so a process interruption can leave the catalogue in
     * exactly two states --- both halves applied, or neither. The old
     * two-call sequencing (item upsert, then tombstone clear) could crash
     * in between and leave a resurrected row still reported dead by
     * `tombstonedKeys()` until the next authoritative scan healed it.
     * Idempotent: re-running converges (re-upsert of the same rows and
     * re-deletion of absent tombstones are no-ops in effect). Callers
     * reach this via [RoomMediaCatalogue.upsertAll].
     */
    @Transaction
    suspend fun upsertItemsAndClearTombstones(
        items: List<CatalogueItemEntity>,
        clearedLocalMediaKeys: List<String>,
    ) {
        upsertItems(items)
        deleteTombstonesByLocalMediaKey(clearedLocalMediaKeys)
    }

    /**
     * Drops tombstones STRICTLY older than [cutoffMillis] (retention
     * policy is decided by the caller, not here).
     */
    @Query("DELETE FROM catalogue_tombstones WHERE tombstonedAtMillis < :cutoffMillis")
    suspend fun deleteTombstonesOlderThan(cutoffMillis: Long)

    // --- Scan checkpoints ---------------------------------------------------

    /** Inserts or replaces the checkpoint row for the volume. */
    @Upsert
    suspend fun upsertCheckpoint(checkpoint: ScanCheckpointEntity)

    /** The checkpoint row for [volumeName], or `null` when never scanned. */
    @Query("SELECT * FROM scan_checkpoints WHERE volumeName = :volumeName")
    suspend fun checkpointByVolumeName(volumeName: String): ScanCheckpointEntity?
}
