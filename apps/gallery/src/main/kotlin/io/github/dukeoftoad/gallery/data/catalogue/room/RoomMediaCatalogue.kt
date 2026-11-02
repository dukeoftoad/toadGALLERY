package io.github.dukeoftoad.gallery.data.catalogue.room

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import io.github.dukeoftoad.gallery.data.catalogue.ScanCheckpoint
import io.github.dukeoftoad.gallery.data.catalogue.TimelineDirection
import io.github.dukeoftoad.gallery.data.media.dates.ResolvedCaptureDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * The production [MediaCatalogue]: a THIN mapper over [CatalogueDao].
 *
 * ADR-0016 applies in full --- this class is Android-adjacent Room glue and
 * carries NO unit tests. Its only jobs are entity/record translation and
 * sequencing DAO calls; every decision (diff, tombstone policy, purge
 * cutoffs) is made by the pure, JVM-tested types. The reference semantics
 * it must match are [io.github.dukeoftoad.gallery.data.catalogue
 * .InMemoryMediaCatalogue]; any divergence between the two is a bug in THIS
 * class.
 *
 * Transactional guarantees (Phase 4 audit finding D): the two paired
 * live-row/tombstone transitions each run inside ONE Room transaction ---
 * [tombstone] via [CatalogueDao.removeItemsAndWriteTombstones] (delete +
 * tombstone upsert atomic) and [upsertAll] via
 * [CatalogueDao.upsertItemsAndClearTombstones] (item upsert + tombstone
 * clear atomic). A process interruption can therefore leave the catalogue
 * in exactly two states per transition --- both halves applied, or
 * neither --- never a tombstoned-but-live or live-but-still-tombstoned
 * intermediate state on disk. Both transitions are also idempotent:
 * replaying the same call converges to the same state, which is what lets
 * the next authoritative scan heal any state a retry re-observes.
 * [upsertAll] wraps its WHOLE batch in one transaction (batch-level
 * atomicity), matching the in-memory reference, which holds its mutex for
 * the entire batch.
 *
 * Honest JVM limitation (ADR-0016): the Room runtime behavior of these
 * transactions --- actual interruption/rollback under a killed process ---
 * CANNOT be exercised by this repo's JVM suite (no Robolectric, no
 * android.* on the unit-test classpath). It is verified by integration /
 * device checks only. What the JVM suite pins is the contract the Room
 * implementation must match: [InMemoryMediaCatalogue] performs the same
 * transitions as single serialized steps under its mutex, and its tests
 * assert that a concurrent reader never observes a half-applied
 * transition. If the two implementations ever disagree, THIS class is the
 * bug.
 *
 * Timeline surface (r13 round): the windowed reads delegate to the DAO's
 * two fixed ORDER BY statements (one per direction --- never concatenated
 * SQL), the count is a plain COUNT, and [changes] rides Room's
 * InvalidationTracker through a Flow over the live-rows table. Ordering
 * equivalence with the reference implementation is the contract the
 * in-memory suite pins; the SQL itself is Android-adjacent and therefore
 * untested here (ADR-0016).
 */
class RoomMediaCatalogue(
    private val dao: CatalogueDao,
) : MediaCatalogue {

    /**
     * The atomic upsert/restore transition: the whole batch lands (rows
     * upserted AND their tombstones cleared) in one Room transaction, via
     * [CatalogueDao.upsertItemsAndClearTombstones] --- see the class KDoc
     * for the guarantee and its verification limits.
     */
    override suspend fun upsertAll(records: List<CatalogueRecord>) {
        if (records.isEmpty()) return
        dao.upsertItemsAndClearTombstones(
            items = records.map { it.toEntity() },
            // Resurrect: an upserted key is live by definition of an
            // authoritative scan; drop any stale death record.
            clearedLocalMediaKeys = records.map { it.localMediaKey },
        )
    }

    override suspend fun record(localMediaKey: String): CatalogueRecord? =
        dao.itemByLocalMediaKey(localMediaKey)?.toRecord()

    /**
     * The targeted capture-date write: one partial UPDATE statement
     * ([CatalogueDao.updateCaptureDateColumns]) that touches only the
     * three provenance columns. See [MediaCatalogue.updateCaptureDate] for
     * why this is deliberately NOT a read-modify-write upsert (it would
     * clobber concurrent UI writes to other columns, and the catalogue
     * promises transaction-per-call, never read-modify-write atomicity).
     * The UPDATE matching zero rows (unknown/tombstoned key) is the
     * interface's documented no-op --- never a resurrection.
     */
    override suspend fun updateCaptureDate(localMediaKey: String, date: ResolvedCaptureDate?) {
        dao.updateCaptureDateColumns(
            localMediaKey = localMediaKey,
            dateMillis = date?.epochMillis,
            zoneOffsetMinutes = date?.timezoneOffsetMinutes,
            // The enum's name string, same convention as the record field.
            sourceName = date?.source?.name,
        )
    }

    override suspend fun allRecords(): List<CatalogueRecord> =
        dao.allItems().map { it.toRecord() }

    override suspend fun recordsMap(): Map<String, CatalogueRecord> =
        dao.allItems().associateBy { it.localMediaKey }.mapValues { it.value.toRecord() }

    /**
     * The windowed timeline read: the `when` picks ONE of the DAO's two
     * fixed ORDER BY statements --- no SQL is built here, only chosen. The
     * guards run BEFORE the DAO call because SQLite reads a negative LIMIT
     * as "unbounded" (an accidental full-table window) and both
     * implementations must answer invalid arguments identically: empty.
     */
    override suspend fun timelineWindow(
        offset: Int,
        limit: Int,
        direction: TimelineDirection,
    ): List<CatalogueRecord> {
        if (offset < 0 || limit <= 0) return emptyList()
        val window = when (direction) {
            TimelineDirection.NEWEST_FIRST -> dao.timelineWindowNewestFirst(offset, limit)
            TimelineDirection.OLDEST_FIRST -> dao.timelineWindowOldestFirst(offset, limit)
        }
        return window.map { it.toRecord() }
    }

    /** A plain COUNT over the live-rows table; tombstones never enter it. */
    override suspend fun liveItemCount(): Int = dao.liveItemCount()

    /**
     * The invalidation signal, Room-style: a flow query over the live-rows
     * table re-emits after every write to it (Room's InvalidationTracker
     * observes the table the query touches). Room flow queries emit their
     * current result once on collection; [drop] removes that initial value
     * so the signal stays "mutations only", exactly like the in-memory
     * reference (which never emits before its first mutation) --- a
     * collector that subscribes mid-stream misses earlier mutations in BOTH
     * implementations and must re-read independently. [map] discards the
     * count: the payload is deliberately Unit, "something changed".
     */
    override fun changes(): Flow<Unit> = dao.liveItemCountChanges().drop(1).map { }

    /**
     * The atomic remove/tombstone transition: the live rows are deleted
     * AND their tombstones written in one Room transaction, via
     * [CatalogueDao.removeItemsAndWriteTombstones] --- see the class KDoc
     * for the guarantee and its verification limits.
     */
    override suspend fun tombstone(localMediaKeys: List<String>, atMillis: Long) {
        if (localMediaKeys.isEmpty()) return
        dao.removeItemsAndWriteTombstones(
            localMediaKeys = localMediaKeys,
            tombstones = localMediaKeys.map {
                CatalogueTombstoneEntity(
                    localMediaKey = it,
                    tombstonedAtMillis = atMillis,
                )
            },
        )
    }

    override suspend fun tombstonedKeys(): Set<String> =
        dao.allTombstones().map { it.localMediaKey }.toSet()

    override suspend fun saveScanCheckpoint(
        volumeName: String,
        generationToken: String?,
        scannedAtMillis: Long,
    ) {
        dao.upsertCheckpoint(
            ScanCheckpointEntity(
                volumeName = volumeName,
                generationToken = generationToken,
                scannedAtMillis = scannedAtMillis,
            ),
        )
    }

    override suspend fun scanCheckpoint(volumeName: String): ScanCheckpoint? =
        dao.checkpointByVolumeName(volumeName)?.toCheckpoint()

    override suspend fun purgeTombstonesOlderThan(cutoffMillis: Long) {
        dao.deleteTombstonesOlderThan(cutoffMillis)
    }

    // ------------------------------------------------------------------
    // r17 round: FILTERED timeline windows (smart-filter chips) --- an
    // append-only section; nothing above this line is touched, and the
    // entity<->record mappers below the class stay the ONLY translation
    // point. Types are fully qualified on purpose so this file's import
    // block stays untouched (parallel-round merge hygiene).
    //
    // The WHERE fragment + bound args arrive FULLY COMPOSED from the pure,
    // JVM-tested filter engine (io.github.dukeoftoad.gallery.data.filters
    // .TimelineFilterCatalogue) --- fixed clause shapes over existing
    // columns, no user input, no string interpolation of values. Since the
    // r19 catalogue v5 the serviceable dimensions include the folder/bucket
    // filter (`bucketId = ?`, the new v5 column), so the engine's every
    // dimension is serveable here. This method's only jobs are the guards
    // (identical to [timelineWindow]'s: a negative LIMIT is "unbounded" in
    // SQLite, so invalid arguments must answer empty in BOTH
    // implementations) and the ONE composition site --- extracted into the
    // pure [FilteredWindowSql] builder below (r19; an audit flagged the
    // inline composition as the one untested piece of this window), which
    // hands the DAO's @RawQuery window its statement + bound args.
    // ------------------------------------------------------------------

    /**
     * Window `[offset, offset+limit)` of LIVE records matching [where], in
     * the SAME total order [timelineWindow] serves (effective instant,
     * local-media-key tiebreak, [direction]) --- i.e. the FILTERED twin of
     * [timelineWindow]: positional windows must apply the filter inside
     * the query or offsets stop tiling.
     *
     * NOT on the [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue]
     * interface: the interface and its in-memory reference implementation
     * are frozen for the parallel r15-r18 rounds; the production shell
     * always binds THIS class (see `ToadGalleryApplication.mediaCatalogue`),
     * and `PhotosViewModel` reaches the method through that binding. Since
     * the r19 v5 columns the catalogue serves every dimension the engine
     * can express (the former bucket gap closed), so the engine's
     * `canServe` gate passes for every filter value and this window is the
     * catalogue's honest answer for any active generation.
     */
    suspend fun filteredTimelineWindow(
        offset: Int,
        limit: Int,
        direction: TimelineDirection,
        where: io.github.dukeoftoad.gallery.data.filters.TimelineFilterCatalogue.CatalogueFilterWhere,
    ): List<CatalogueRecord> {
        if (offset < 0 || limit <= 0) return emptyList()
        val composed = FilteredWindowSql.compose(where, direction, offset, limit)
        return dao.timelineWindowFiltered(
            androidx.sqlite.db.SimpleSQLiteQuery(
                composed.sql,
                // Built as one explicitly-typed Array<Any?>: a plain
                // `Array<Any> + Array<Int>` is an overload-resolution
                // ambiguity (plus(element) vs plus(elements)), and
                // SimpleSQLiteQuery takes Array<out Any?> anyway.
                composed.bindArgs.toTypedArray(),
            ),
        ).map { it.toRecord() }
    }
    // =====================================================================
    // --- r16 round: the on-device map view's location surface -------------
    // (additive end-of-class section; nothing above this line changes and
    // the [MediaCatalogue] interface members are untouched --- these members
    // exist only on the concrete class, which is why the r16 map screen and
    // the location indexer's writer seam type against `RoomMediaCatalogue`
    // directly. A sibling branch adds its own clearly-fenced section; keep
    // additions contiguous.)
    // =====================================================================

    /**
     * The targeted location write behind the r16 indexer's
     * [io.github.dukeoftoad.gallery.data.media.location.ItemLocationWriter]
     * seam: one partial UPDATE statement ([CatalogueDao.updateLocationColumns])
     * that touches only the three location columns --- the same "cannot
     * clobber concurrent writers, cannot resurrect a dead row" guarantee
     * [updateCaptureDate] documents. Deliberately NOT an interface member:
     * widening [MediaCatalogue] is the Phase 5 Places index's contract
     * (its in-memory reference implementation would have to grow the same
     * surface, and that file is outside this round's editable scope).
     */
    suspend fun updateItemLocation(
        localMediaKey: String,
        latitude: Double?,
        longitude: Double?,
        sourceName: String?,
    ) {
        dao.updateLocationColumns(
            localMediaKey = localMediaKey,
            latitude = latitude,
            longitude = longitude,
            sourceName = sourceName,
        )
    }

    /**
     * The map's pin supply (r16): every live row with a persisted location,
     * newest-first in the timeline's total order --- the catalogue-backed
     * map dots and each location's item sheet both read this one projection.
     */
    suspend fun geotaggedRows(): List<CatalogueLocationRow> = dao.geotaggedItems()

    /**
     * How many rows were first scanned while the platform redacts EXIF
     * location (the `MEDIA_LOCATION_UNGRANTED` provenance) --- the input the
     * pure re-index policy consumes to decide whether a location re-pass
     * could change anything (see
     * [io.github.dukeoftoad.gallery.data.media.location
     * .LocationConsentReindexPolicy]).
     */
    suspend fun pendingUngrantedLocations(): Int = dao.ungrantedLocationCount()
}

// --- entity <-> record mappers (the ONLY translation point) ---------------

/**
 * Record -> row. [CatalogueRecord.metadataCacheState] is stored as its
 * [Enum.name]; see [CatalogueItemEntity] for why a TypeConverter was not
 * used.
 *
 * The location provenance columns are DELIBERATELY not carried: the record
 * has no location fields (the r16 wipe-and-refill pattern --- the location
 * index hook re-fills them right after the batch). The r19 folder/bucket
 * pair is the OPPOSITE case and IS carried verbatim: bucket is ROW-SOURCED
 * with no hook refill, so nulling it here would lose the folder for good
 * (see [CatalogueItemEntity]'s r19 KDoc --- this is the wipe-free
 * carry-through the whole round exists for).
 */
private fun CatalogueRecord.toEntity(): CatalogueItemEntity =
    CatalogueItemEntity(
        localMediaKey = localMediaKey,
        canonicalUri = canonicalUri,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        dateModifiedMillis = dateModifiedMillis,
        dateAddedMillis = dateAddedMillis,
        generationToken = generationToken,
        metadataWriteRevision = metadataWriteRevision,
        metadataCacheState = metadataCacheState.name,
        lastSeenAtMillis = lastSeenAtMillis,
        resolvedCaptureDateMillis = resolvedCaptureDateMillis,
        resolvedCaptureDateZoneOffsetMinutes = resolvedCaptureDateZoneOffsetMinutes,
        resolvedCaptureDateSource = resolvedCaptureDateSource,
        displayName = displayName,
        width = width,
        height = height,
        durationMs = durationMs,
        isFavorite = isFavorite,
        bucketId = bucketId,
        bucketDisplayName = bucketDisplayName,
    )

/**
 * Row -> record. An unrecognized [CatalogueItemEntity.metadataCacheState]
 * string (only possible if a future schema wrote a new enum name this build
 * predates) degrades to [MetadataCacheState.EMPTY] --- the safe direction:
 * EMPTY forces a metadata re-read, never a trust of stale data.
 */
private fun CatalogueItemEntity.toRecord(): CatalogueRecord =
    CatalogueRecord(
        localMediaKey = localMediaKey,
        canonicalUri = canonicalUri,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        dateModifiedMillis = dateModifiedMillis,
        dateAddedMillis = dateAddedMillis,
        generationToken = generationToken,
        metadataWriteRevision = metadataWriteRevision,
        metadataCacheState = runCatching { MetadataCacheState.valueOf(metadataCacheState) }
            .getOrDefault(MetadataCacheState.EMPTY),
        lastSeenAtMillis = lastSeenAtMillis,
        resolvedCaptureDateMillis = resolvedCaptureDateMillis,
        resolvedCaptureDateZoneOffsetMinutes = resolvedCaptureDateZoneOffsetMinutes,
        resolvedCaptureDateSource = resolvedCaptureDateSource,
        displayName = displayName,
        width = width,
        height = height,
        durationMs = durationMs,
        isFavorite = isFavorite,
        bucketId = bucketId,
        bucketDisplayName = bucketDisplayName,
    )

/** Row -> checkpoint; a straight field copy. */
private fun ScanCheckpointEntity.toCheckpoint(): ScanCheckpoint =
    ScanCheckpoint(
        volumeName = volumeName,
        generationToken = generationToken,
        scannedAtMillis = scannedAtMillis,
    )

// =====================================================================
// --- r19 round: the filtered-window SQL builder (pure, JVM-tested) ------
// (additive end-of-file section. The r17 composition that used to live
// inline in filteredTimelineWindow --- the one piece of the filtered
// windows an audit flagged as untestable while the wrapper owned it
// (ADR-0016: the wrapper itself carries no JVM tests) --- is extracted
// verbatim into this pure object so its exact statement text and ARG
// ORDER are pinnable on the plain JVM (`FilteredWindowSqlTest`). No
// android.* / androidx.* references: the wrapper wraps the result in a
// SimpleSQLiteQuery at the thin seam.)
// =====================================================================

/**
 * The pure composition behind [RoomMediaCatalogue.filteredTimelineWindow]
 * (r19): the complete statement + bound args for the DAO's @RawQuery
 * filtered window, assembled from the filter engine's WHERE fragment (see
 * [io.github.dukeoftoad.gallery.data.filters.TimelineFilterCatalogue]) ---
 * fixed clause shapes, no user input, no string interpolation of values.
 *
 * The composition, each piece load-bearing:
 * - the SAME total ORDER BY the two fixed window queries use --- effective
 *   instant (`COALESCE(resolvedCaptureDateMillis, dateAddedMillis)`) with
 *   the same-direction `localMediaKey` tiebreak --- so a filtered window
 *   is a plain slice of the very order [RoomMediaCatalogue.timelineWindow]
 *   serves and pages tile with zero gaps/overlaps (the r13 contract);
 * - the fragment spliced VERBATIM between FROM and ORDER BY (never into
 *   the ORDER BY or LIMIT --- a miscomposed splice would reorder, not
 *   narrow);
 * - LIMIT/OFFSET as BOUND ARGS with the windowing ints LAST: the
 *   fragment's `?` placeholders come first in the statement, so its args
 *   MUST come first in the bind array --- this ordering is the contract
 *   with [io.github.dukeoftoad.gallery.data.filters.TimelineFilterCatalogue
 *   .CatalogueFilterWhere] and the piece the JVM suite pins hardest
 *   (a swapped arg order binds a bucket id as a LIMIT and answers a
 *   VISIBLE lie).
 *
 * The wrapper owns the argument guards (invalid offset/limit answer empty
 * before this is ever called), so this object is total for the values it
 * receives; tombstone-awareness needs no clause here because tombstoned
 * rows are not in `catalogue_items` at all.
 */
internal object FilteredWindowSql {

    /** One composed window statement: the SQL text plus its bound args, in order. */
    data class Composed(
        val sql: String,
        val bindArgs: List<Any?>,
    )

    /**
     * Composes the filtered window statement for [where] (whose fragment,
     * if any, narrows the live rows) in [direction]'s spelling of the
     * timeline's total order, sliced `LIMIT :limit OFFSET :offset`.
     */
    fun compose(
        where: io.github.dukeoftoad.gallery.data.filters.TimelineFilterCatalogue.CatalogueFilterWhere,
        direction: TimelineDirection,
        offset: Int,
        limit: Int,
    ): Composed {
        val directionWord = when (direction) {
            TimelineDirection.NEWEST_FIRST -> "DESC"
            TimelineDirection.OLDEST_FIRST -> "ASC"
        }
        val whereSql = where.sql?.let { " WHERE $it" } ?: ""
        val sql =
            "SELECT * FROM catalogue_items$whereSql " +
                "ORDER BY COALESCE(resolvedCaptureDateMillis, dateAddedMillis) $directionWord, " +
                "localMediaKey $directionWord " +
                "LIMIT ? OFFSET ?"
        // Fragment args first (their placeholders open the statement),
        // then the two windowing ints in LIMIT/OFFSET order --- one
        // explicitly-typed list so the wrapper can hand it to
        // SimpleSQLiteQuery as Array<Any?> without an `Array<Any> +
        // Array<Int>` overload ambiguity.
        val bindArgs: List<Any?> = buildList {
            addAll(where.args)
            add(limit)
            add(offset)
        }
        return Composed(sql = sql, bindArgs = bindArgs)
    }
}
