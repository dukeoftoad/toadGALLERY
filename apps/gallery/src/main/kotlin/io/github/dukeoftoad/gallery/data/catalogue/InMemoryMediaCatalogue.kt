package io.github.dukeoftoad.gallery.data.catalogue

import io.github.dukeoftoad.gallery.data.media.dates.ResolvedCaptureDate
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
 *
 * Timeline surface (r13 round; the reference the Room implementation must
 * match): [timelineWindow] re-sorts the live map into the direction's
 * total order --- effective instant
 * (`resolvedCaptureDateMillis ?: dateAddedMillis`), then `localMediaKey` in
 * the same direction --- and slices `[offset, offset+limit)` out of it.
 * Re-sorting per call is the reference implementation's price for staying
 * dumb and pure; production ordering lives in the DAO's SQL. Since r19 the
 * folder-filtered twin [folderTimelineWindow] is the reference for the
 * Room catalogue's `WHERE bucketId = ?` filtered windows (the v5 bucket
 * columns): same total order, records narrowed to the matching bucket.
 * [changes] is a conflation-friendly [MutableSharedFlow] hook fired after
 * every mutating call that can change the live set or its order (see the
 * interface KDoc for the exact emission table).
 */
class InMemoryMediaCatalogue : MediaCatalogue {

    private val mutex = Mutex()

    /** Live rows only; tombstoned keys are removed from this map. */
    private val records = LinkedHashMap<String, CatalogueRecord>()

    /** key -> epoch millis of the tombstone. */
    private val tombstones = LinkedHashMap<String, Long>()

    /** volumeName -> last checkpoint. */
    private val checkpoints = LinkedHashMap<String, ScanCheckpoint>()

    /**
     * The in-memory [changes] hook: zero replay (no initial emission, no
     * missed-mutation replay), one slot of buffer with DROP_OLDEST so a
     * burst of mutations conflates instead of suspending the mutator ---
     * the payload is Unit, so conflation loses no information ("something
     * changed" is idempotent).
     */
    private val changeSignals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override fun changes(): Flow<Unit> = changeSignals

    /**
     * Fires the invalidation signal AFTER the mutating call's mutex block:
     * never under the lock (a slow collector must never block mutations)
     * and only for calls that can change the live set or its order.
     */
    private fun notifyChanged() {
        changeSignals.tryEmit(Unit)
    }

    /**
     * The EFFECTIVE instant of a record --- the in-memory spelling of the
     * DAO's `COALESCE(resolvedCaptureDateMillis, dateAddedMillis)`. A
     * capture date resolved by the scan's index pass outranks the
     * provider's date_added; undateable and pre-index rows fall back.
     */
    private fun CatalogueRecord.effectiveInstantMillis(): Long =
        resolvedCaptureDateMillis ?: dateAddedMillis

    /**
     * The timeline's TOTAL ORDER as one comparator (r19: shared by
     * [timelineWindow] and [folderTimelineWindow] so there is exactly one
     * in-memory spelling of the effective-instant + key-tiebreak rule the
     * DAO's fixed window queries and the filtered-window composition both
     * spell in SQL): effective instant, then `localMediaKey` in the SAME
     * direction --- the tiebreak that makes windows tile with zero
     * gaps/overlaps in both directions.
     */
    private fun timelineOrder(direction: TimelineDirection): Comparator<CatalogueRecord> =
        when (direction) {
            TimelineDirection.NEWEST_FIRST ->
                compareByDescending<CatalogueRecord> { it.effectiveInstantMillis() }
                    .thenByDescending { it.localMediaKey }
            TimelineDirection.OLDEST_FIRST ->
                compareBy<CatalogueRecord> { it.effectiveInstantMillis() }
                    .thenBy { it.localMediaKey }
        }

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
        // One emission per batch, not per record: the signal is "re-read
        // your windows", and a batch is the unit the scan driver applies.
        notifyChanged()
    }

    /**
     * One mutex hold, one row copied with ONLY the three provenance fields
     * replaced --- the reference semantics of the Room implementation's
     * targeted UPDATE ([CatalogueDao.updateCaptureDateColumns]). Unknown
     * and tombstoned keys are no-ops (a tombstoned key is not in the live
     * map), matching the interface's never-resurrect rule.
     */
    override suspend fun updateCaptureDate(localMediaKey: String, date: ResolvedCaptureDate?) {
        mutex.withLock {
            val existing = records[localMediaKey] ?: return
            records[localMediaKey] = existing.copy(
                resolvedCaptureDateMillis = date?.epochMillis,
                resolvedCaptureDateZoneOffsetMinutes = date?.timezoneOffsetMinutes,
                resolvedCaptureDateSource = date?.source?.name,
            )
        }
        // The capture date IS the timeline's primary sort key: writing one
        // reorders windows and must invalidate. Unknown/tombstoned keys
        // returned above without a write, matching Room's zero-row UPDATE
        // (no row trigger, no invalidation).
        notifyChanged()
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

    /**
     * The windowed timeline read, as a slice of the live map's sorted view.
     * The order is the interface's total order (effective instant, then key,
     * both in [direction]); the tiebreak makes the sequence stable across
     * calls so windows tile with zero gaps/overlaps. See
     * [MediaCatalogue.timelineWindow] for the full contract.
     */
    override suspend fun timelineWindow(
        offset: Int,
        limit: Int,
        direction: TimelineDirection,
    ): List<CatalogueRecord> {
        // Guards first, identically to the Room implementation: SQLite
        // reads a negative LIMIT as "unbounded", so invalid arguments must
        // never reach a windowing mechanism --- they deterministically read
        // empty in both implementations.
        if (offset < 0 || limit <= 0) return emptyList()
        mutex.withLock {
            val ordered = records.values.sortedWith(timelineOrder(direction))
            if (offset >= ordered.size) return emptyList()
            val end = minOf(offset + limit, ordered.size)
            return ordered.subList(offset, end)
        }
    }

    // ------------------------------------------------------------------
    // r19 round: the FOLDER-FILTERED window --- the folder-filter
    // reference the Room catalogue's `WHERE bucketId = ?` windows
    // (composed by the pure FilteredWindowSql builder + the filter
    // engine's projection) must match. Deliberately a concrete member,
    // NOT on the [MediaCatalogue] interface: the interface stays the
    // frozen r13 surface, exactly like the Room side's filtered windows
    // live on the concrete class (the r17 decision).
    // ------------------------------------------------------------------

    /**
     * Window `[offset, offset+limit)` of LIVE records whose
     * [CatalogueRecord.bucketId] equals [bucketId], in the SAME total
     * order [timelineWindow] serves --- the in-memory reference for the
     * folder-filtered catalogue windows the r19 v5 columns unlock
     * (`WHERE bucketId = ?` spliced into the timeline's ORDER BY).
     *
     * Matching semantics, mirroring the SQL exactly:
     * - `=` never matches NULL: records with a `null` bucketId (the
     *   honest "not resolvable on this API path" --- the pre-Q defensive
     *   reduced-projection walk, a provider that dropped the column) are
     *   EXCLUDED from every folder window, never bucket 0.
     * - Tombstoned keys are excluded by construction (not in the live
     *   map).
     * - The same effective-instant + localMediaKey tiebreak, so windows
     *   tile inside a folder exactly like the unfiltered timeline.
     *
     * Argument discipline identical to [timelineWindow]: a negative
     * [offset] or non-positive [limit] reads empty; an offset at/after the
     * folder's end reads empty.
     */
    suspend fun folderTimelineWindow(
        offset: Int,
        limit: Int,
        direction: TimelineDirection,
        bucketId: Long,
    ): List<CatalogueRecord> {
        if (offset < 0 || limit <= 0) return emptyList()
        mutex.withLock {
            val ordered = records.values
                .filter { it.bucketId == bucketId }
                .sortedWith(timelineOrder(direction))
            if (offset >= ordered.size) return emptyList()
            val end = minOf(offset + limit, ordered.size)
            return ordered.subList(offset, end)
        }
    }

    /** Live rows only --- tombstones are not in [records] to begin with. */
    override suspend fun liveItemCount(): Int = mutex.withLock { records.size }

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
        notifyChanged()
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
        // Deliberately NO change signal: purging tombstones cannot change
        // the live set or its order, so a timeline re-read would be pure
        // waste (Room agrees --- the tombstone table is not observed).
    }
}
