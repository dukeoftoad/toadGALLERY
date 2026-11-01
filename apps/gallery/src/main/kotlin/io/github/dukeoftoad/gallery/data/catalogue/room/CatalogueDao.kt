package io.github.dukeoftoad.gallery.data.catalogue.room

import androidx.room.Dao
import androidx.room.Query
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
