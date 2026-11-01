package io.github.dukeoftoad.gallery.data.catalogue.room

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState
import io.github.dukeoftoad.gallery.data.catalogue.ScanCheckpoint

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
 * Sequencing detail: [upsertAll] writes the item rows and then clears the
 * tombstones of the upserted keys. That order matters --- if the item write
 * fails, no resurrection happened; if the tombstone clear fails after the
 * items landed, the worst case is a live record that still looks dead to
 * `tombstonedKeys()`, which the next authoritative scan heals (the item
 * will be reported "unchanged" and re-upserted). The reverse order could
 * leave a cleared tombstone with NO live record --- an item that fell out
 * of the catalogue entirely and would be re-indexed from scratch.
 * Room runs each DAO call in its own transaction; a future change can wrap
 * the pair in `androidx.room.withTransaction` if the heal-on-next-scan
 * window is ever deemed too wide.
 */
class RoomMediaCatalogue(
    private val dao: CatalogueDao,
) : MediaCatalogue {

    override suspend fun upsertAll(records: List<CatalogueRecord>) {
        if (records.isEmpty()) return
        dao.upsertItems(records.map { it.toEntity() })
        // Resurrect: an upserted key is live by definition of an
        // authoritative scan; drop any stale death record.
        dao.deleteTombstonesByLocalMediaKey(records.map { it.localMediaKey })
    }

    override suspend fun record(localMediaKey: String): CatalogueRecord? =
        dao.itemByLocalMediaKey(localMediaKey)?.toRecord()

    override suspend fun allRecords(): List<CatalogueRecord> =
        dao.allItems().map { it.toRecord() }

    override suspend fun recordsMap(): Map<String, CatalogueRecord> =
        dao.allItems().associateBy { it.localMediaKey }.mapValues { it.value.toRecord() }

    override suspend fun tombstone(localMediaKeys: List<String>, atMillis: Long) {
        if (localMediaKeys.isEmpty()) return
        // Order matters here too, and for the same reason as in upsertAll:
        // remove the live row first, then record the death. A crash in
        // between can leave a live row without a tombstone (healed by the
        // next scan re-tombstoning it), never a tombstone without the live
        // row having been removed (which would double-report the key).
        dao.deleteItemsByLocalMediaKey(localMediaKeys)
        dao.upsertTombstones(
            localMediaKeys.map {
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
    )

/** Row -> checkpoint; a straight field copy. */
private fun ScanCheckpointEntity.toCheckpoint(): ScanCheckpoint =
    ScanCheckpoint(
        volumeName = volumeName,
        generationToken = generationToken,
        scannedAtMillis = scannedAtMillis,
    )
