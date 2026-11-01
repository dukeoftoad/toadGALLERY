package io.github.dukeoftoad.gallery.data.catalogue.room

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import io.github.dukeoftoad.gallery.data.catalogue.ScanCheckpoint
import io.github.dukeoftoad.gallery.data.media.dates.ResolvedCaptureDate

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
}

// --- entity <-> record mappers (the ONLY translation point) ---------------

/**
 * Record -> row. [CatalogueRecord.metadataCacheState] is stored as its
 * [Enum.name]; see [CatalogueItemEntity] for why a TypeConverter was not
 * used.
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
    )

/** Row -> checkpoint; a straight field copy. */
private fun ScanCheckpointEntity.toCheckpoint(): ScanCheckpoint =
    ScanCheckpoint(
        volumeName = volumeName,
        generationToken = generationToken,
        scannedAtMillis = scannedAtMillis,
    )
