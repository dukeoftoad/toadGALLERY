package io.github.dukeoftoad.gallery.data.catalogue.room

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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
 * The one deliberate exception to "no SQL-side logic" is the r13 timeline
 * window pair ([timelineWindowNewestFirst] /
 * [timelineWindowOldestFirst]): the ORDER BY --- effective instant with the
 * same-direction key tiebreak --- is the timeline's row-supply contract
 * (see [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
 * .timelineWindow]), and pushing the ordering into SQL is the only way the
 * windowed reads stay O(window) instead of O(live set). Even those two
 * statements stay free of policy: two fixed ORDER BY clauses, one per
 * direction, chosen by a `when` in the catalogue wrapper --- never
 * string-concatenated SQL, and nothing that a caller can parameterize
 * beyond offset/limit.
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

    // --- timeline surface (r13 round) ---------------------------------------

    /**
     * The NEWEST_FIRST window of the timeline's total order:
     * `COALESCE(resolvedCaptureDateMillis, dateAddedMillis) DESC,
     * localMediaKey DESC`, sliced `LIMIT :limit OFFSET :offset`.
     *
     * The ORDER BY is the whole contract (see the class KDoc and
     * [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
     * .timelineWindow]): the COALESCE makes the persisted capture date the
     * primary key of the order (date_added is only the fallback), and the
     * same-direction `localMediaKey` tiebreak turns it into a TOTAL order so
     * consecutive windows tile --- zero gaps, zero overlaps, no row twice
     * (the duplicate-LazyGrid-key crash) and no row never (the silently
     * skipped photo). `localMediaKey` is the primary key, hence unique, so
     * the tiebreak is guaranteed to fire on at most one row pair and always
     * decides. Callers reach this via
     * [io.github.dukeoftoad.gallery.data.catalogue.room.RoomMediaCatalogue
     * .timelineWindow], which owns the offset/limit guards.
     */
    @Query(
        "SELECT * FROM catalogue_items " +
            "ORDER BY COALESCE(resolvedCaptureDateMillis, dateAddedMillis) DESC, " +
            "localMediaKey DESC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun timelineWindowNewestFirst(offset: Int, limit: Int): List<CatalogueItemEntity>

    /**
     * The OLDEST_FIRST window of the timeline's total order --- the exact
     * mirror of [timelineWindowNewestFirst]: both sort keys ascending, so
     * ascending the timeline walks the same total order backwards and
     * windows tile identically. Same contract, same tiebreak rationale.
     */
    @Query(
        "SELECT * FROM catalogue_items " +
            "ORDER BY COALESCE(resolvedCaptureDateMillis, dateAddedMillis) ASC, " +
            "localMediaKey ASC " +
            "LIMIT :limit OFFSET :offset",
    )
    suspend fun timelineWindowOldestFirst(offset: Int, limit: Int): List<CatalogueItemEntity>

    /**
     * The live-row count (the timeline's denominator); tombstoned keys are
     * not in this table at all, so they are excluded by construction.
     */
    @Query("SELECT COUNT(*) FROM catalogue_items")
    suspend fun liveItemCount(): Int

    /**
     * The invalidation backbone of [io.github.dukeoftoad.gallery.data
     * .catalogue.MediaCatalogue.changes]: a Flow over the live-rows table,
     * so Room's InvalidationTracker re-emits after every catalogue_items
     * write --- upserts, capture-date updates, tombstone deletes. A COUNT
     * query keeps each re-emission's cost trivial. Room flow queries emit
     * their current result once on collection; the catalogue wrapper drops
     * that initial emission to keep the signal "mutations only", matching
     * the in-memory reference. The tombstone/checkpoint tables are
     * deliberately NOT observed: purges and checkpoint saves cannot change
     * the live set or its order.
     */
    @Query("SELECT COUNT(*) FROM catalogue_items")
    fun liveItemCountChanges(): Flow<Int>

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

    // =====================================================================
    // --- r16 round: the on-device map view's location surface -------------
    // (additive end-of-file section; nothing above this line changes. A
    // sibling branch adds its own end-of-file section --- keep additions
    // contiguous and clearly fenced.)
    // =====================================================================

    /**
     * The targeted location write (r16 round): updates ONLY the three
     * location provenance columns of the row, leaving every other column
     * untouched --- the same partial-UPDATE discipline as
     * [updateCaptureDateColumns] (no read-modify-write, so a concurrent
     * UI-side write to other columns cannot be clobbered, and a no-op on
     * an unknown/tombstoned key can never resurrect a row).
     *
     * Consumers reach this through
     * [RoomMediaCatalogue.updateItemLocation], which the r16
     * [io.github.dukeoftoad.gallery.data.media.location.LocationIndexer]
     * consumes through its narrow
     * [io.github.dukeoftoad.gallery.data.media.location.ItemLocationWriter]
     * seam.
     */
    @Query(
        "UPDATE catalogue_items SET " +
            "latitude = :latitude, " +
            "longitude = :longitude, " +
            "locationSource = :sourceName " +
            "WHERE localMediaKey = :localMediaKey",
    )
    suspend fun updateLocationColumns(
        localMediaKey: String,
        latitude: Double?,
        longitude: Double?,
        sourceName: String?,
    )

    /**
     * The map's pin projection (r16): the timeline-relevant columns of
     * every live row that carries a persisted location, NEWEST-FIRST in
     * the timeline's total order (`COALESCE(resolvedCaptureDateMillis,
     * dateAddedMillis) DESC, localMediaKey DESC` --- the same ORDER BY as
     * [timelineWindowNewestFirst]; the map lists a location's items in the
     * order the timeline would).
     *
     * A plain POJO, not the entity: Room maps the query's columns onto
     * this class by name, and keeping it distinct from
     * [CatalogueItemEntity] makes the projection's scope (what the map
     * needs, nothing more --- no revision fields, no cache state) visible
     * at the type level. Both coordinate columns are required non-null in
     * the WHERE clause: a real GPS fix always writes both
     * ([CatalogueItemEntity.latitude]'s KDoc), and the projection math
     * needs the pair.
     */
    @Query(
        "SELECT localMediaKey, canonicalUri, mimeType, dateAddedMillis, " +
            "resolvedCaptureDateMillis, displayName, width, height, " +
            "durationMs, isFavorite, latitude, longitude " +
            "FROM catalogue_items " +
            "WHERE latitude IS NOT NULL AND longitude IS NOT NULL " +
            "ORDER BY COALESCE(resolvedCaptureDateMillis, dateAddedMillis) DESC, " +
            "localMediaKey DESC",
    )
    suspend fun geotaggedItems(): List<CatalogueLocationRow>

    /**
     * How many live rows carry the `MEDIA_LOCATION_UNGRANTED` provenance
     * (r16): rows first scanned while the platform redacts EXIF location,
     * i.e. exactly the set a grant-triggered location re-pass would
     * target. Consumed by the pure
     * [io.github.dukeoftoad.gallery.data.media.location
     * .LocationConsentReindexPolicy] through
     * [RoomMediaCatalogue.pendingUngrantedLocations].
     */
    @Query("SELECT COUNT(*) FROM catalogue_items WHERE locationSource = 'MEDIA_LOCATION_UNGRANTED'")
    suspend fun ungrantedLocationCount(): Int
}

/**
 * One geotagged row for the map surface (r16; see
 * [CatalogueDao.geotaggedItems] for the projection rationale). Pure data
 * by design: no Room annotations, no android.* --- the same class serves
 * the DAO, the map's clustering math and the location sheet unchanged.
 */
data class CatalogueLocationRow(
    /** The stable local media key; identity + the viewer-window key. */
    val localMediaKey: String,
    /** The resolved content `Uri` string; the ACCESS handle. */
    val canonicalUri: String,
    /** MIME type as reported by the provider. */
    val mimeType: String,
    /** Provider `date_added` in epoch millis; the ordering fallback. */
    val dateAddedMillis: Long,
    /** The resolved capture instant in epoch millis, or `null`. */
    val resolvedCaptureDateMillis: Long?,
    /** The provider `_display_name`; `null` when the provider reported none. */
    val displayName: String?,
    /** Pixel width; `0` means "unknown". */
    val width: Int,
    /** Pixel height; `0` means "unknown". */
    val height: Int,
    /** Video duration in milliseconds; `0` for stills / "unknown". */
    val durationMs: Long,
    /** The MediaStore `is_favorite` flag as the last scan saw it. */
    val isFavorite: Boolean,
    /** EXIF GPS latitude, signed decimal degrees; non-null by the query. */
    val latitude: Double,
    /** EXIF GPS longitude, signed decimal degrees; non-null by the query. */
    val longitude: Double,
)
