package io.github.dukeoftoad.gallery.data.catalogue

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A pure-Kotlin, JVM-friendly [MediaCatalogue] --- no Android types, no
 * persistence, no clock: every timestamp is caller-supplied.
 *
 * It exists for two reasons. First, as the TEST double: the whole
 * incremental-discovery contract (upsert / tombstone / resurrect /
 * checkpoint / purge semantics) is plain logic and ADR-0016 only excludes
 * the Room glue from unit coverage, so the semantics get pinned here by
 * exhaustive JVM tests. Second, as the REFERENCE IMPLEMENTATION:
 * [io.github.dukeoftoad.gallery.data.catalogue.room.RoomMediaCatalogue] is
 * a thin mapper over the generated DAO and must behave exactly like this
 * class --- when the two ever disagree, this one is the contract.
 *
 * Concurrency: a [Mutex] serializes every mutation, mirroring Room's
 * transaction-per-call behavior. The unit tests are single-threaded, but
 * the class must not acquire accidental semantics (lost upserts, torn
 * tombstone/resurrect interleavings) the day a real caller races an
 * observer-triggered reconcile against a foreground scan.
 *
 * Transactional equivalence (Phase 4 audit finding D): each paired
 * transition here is ONE synchronized step under the mutex --- [tombstone]
 * removes the rows and writes the tombstones together, [upsertAll] writes
 * the whole batch and clears its tombstones together --- which is exactly
 * the guarantee the Room implementation gives via
 * `CatalogueDao.removeItemsAndWriteTombstones` /
 * `CatalogueDao.upsertItemsAndClearTombstones` (one Room transaction per
 * call; a reader/interrupter can never observe a tombstoned-but-live or
 * live-but-still-tombstoned intermediate state in either implementation).
 * The JVM tests pin this equivalence --- including a concurrent reader
 * observing only whole transitions --- because the Room runtime side is
 * verifiable only by integration/device checks (ADR-0016).
 *
 * Semantics recap (full rationale on [MediaCatalogue]):
 * - [upsertAll] replaces per key and CLEARS that key's tombstone (a row
 *   seen in a scan is live, by definition of an authoritative scan).
 * - [tombstone] removes the live record (if any) and stamps the key.
 * - [allRecords] / [recordsMap] / [record] expose only live keys.
 * - [purgeTombstonesOlderThan] drops tombstones STRICTLY older than the
 *   cutoff; a tombstone exactly AT the cutoff survives (the cutoff is "the
 *   oldest age still worth keeping").
 */
class InMemoryMediaCatalogue : MediaCatalogue {

    private val mutex = Mutex()

    /** Live rows only; tombstoned keys are removed from this map. */
    private val records = LinkedHashMap<String, CatalogueRecord>()

    /** key -> epoch millis of the tombstone. */
    private val tombstones = LinkedHashMap<String, Long>()

    /** volumeName -> last checkpoint. */
    private val checkpoints = LinkedHashMap<String, ScanCheckpoint>()

    override suspend fun upsertAll(records: List<CatalogueRecord>) {
        if (records.isEmpty()) return
        // Whole batch inside one mutex hold --- the in-memory analogue of
        // Room's batch-level transaction in upsertItemsAndClearTombstones:
        // no reader can observe some records resurrected and others not.
        mutex.withLock {
            for (record in records) {
                this.records[record.localMediaKey] = record
                // The item is live again --- the tombstone would otherwise
                // keep reporting it dead to callers and compaction.
                tombstones.remove(record.localMediaKey)
            }
        }
    }

    override suspend fun record(localMediaKey: String): CatalogueRecord? {
        mutex.withLock { return records[localMediaKey] }
    }

    override suspend fun allRecords(): List<CatalogueRecord> {
        mutex.withLock { return records.values.toList() }
    }

    override suspend fun recordsMap(): Map<String, CatalogueRecord> {
        mutex.withLock { return records.toMap() }
    }

    override suspend fun tombstone(localMediaKeys: List<String>, atMillis: Long) {
        if (localMediaKeys.isEmpty()) return
        // Single mutex hold = one atomic transition, the in-memory
        // analogue of Room's removeItemsAndWriteTombstones transaction:
        // remove first, record the death --- no reader can observe a key
        // simultaneously live and tombstoned, or in neither state.
        mutex.withLock {
            for (key in localMediaKeys) {
                // Remove first: a key must never be simultaneously live and
                // tombstoned, or recordsMap and tombstonedKeys would both
                // report it and the reconciler would see ghosts.
                records.remove(key)
                tombstones[key] = atMillis
            }
        }
    }

    override suspend fun tombstonedKeys(): Set<String> {
        mutex.withLock { return tombstones.keys.toSet() }
    }

    override suspend fun saveScanCheckpoint(
        volumeName: String,
        generationToken: String?,
        scannedAtMillis: Long,
    ) {
        mutex.withLock {
            checkpoints[volumeName] = ScanCheckpoint(
                volumeName = volumeName,
                generationToken = generationToken,
                scannedAtMillis = scannedAtMillis,
            )
        }
    }

    override suspend fun scanCheckpoint(volumeName: String): ScanCheckpoint? {
        mutex.withLock { return checkpoints[volumeName] }
    }

    override suspend fun purgeTombstonesOlderThan(cutoffMillis: Long) {
        mutex.withLock {
            // Strictly older: a tombstone stamped exactly at the cutoff is
            // the newest one still inside the retention window.
            tombstones.entries.removeAll { it.value < cutoffMillis }
        }
    }
}
