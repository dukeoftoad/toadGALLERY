package com.toadgallery.gallery.data.catalogue

import com.toadgallery.gallery.data.media.dates.CaptureDateSource
import com.toadgallery.gallery.data.media.dates.ResolvedCaptureDate
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r13 timeline surface contract of the catalogue
 * ([MediaCatalogue.timelineWindow] / [MediaCatalogue.liveItemCount] /
 * [MediaCatalogue.changes]), pinned on the JVM against
 * [InMemoryMediaCatalogue] --- the reference implementation the Room
 * catalogue must match (ADR-0016: Room glue itself is integration territory,
 * so the ORDERING RULES live here where they can rot loudly instead of
 * silently on a device).
 *
 * The rules most worth guarding forever:
 * - the EFFECTIVE instant is `COALESCE(resolvedCaptureDateMillis,
 *   dateAddedMillis)`: a persisted capture date outranks the provider's
 *   date_added even when date_added is "newer" (that is the entire point of
 *   the r12 provenance columns --- the roadmap's "sort by the persisted
 *   capture dates");
 * - the same-direction `localMediaKey` tiebreak makes the order TOTAL, so
 *   consecutive windows tile: zero gaps, zero overlaps, no key ever surfacing
 *   in two windows (the duplicate-LazyGrid-key crash TimelineSort's `_id`
 *   tiebreak exists to prevent).
 *
 * The r19 section pins the FOLDER-FILTERED window
 * ([InMemoryMediaCatalogue.folderTimelineWindow]) --- the reference the
 * Room catalogue's `WHERE bucketId = ?` filtered windows must match:
 * bucket-id matching (null never matches), the same total order inside a
 * folder, and the same window tiling.
 *
 * Pure JUnit 4 / JVM: no android.* types; `runBlocking` drives the suspend
 * surface (the InMemoryMediaCatalogueTest convention).
 */
class InMemoryMediaCatalogueTimelineTest {

    private fun record(
        key: String,
        dateAddedMillis: Long = 1_000L,
        resolvedCaptureDateMillis: Long? = null,
        displayName: String? = null,
        width: Int = 0,
        height: Int = 0,
        durationMs: Long = 0L,
        isFavorite: Boolean = false,
        bucketId: Long? = null,
        bucketDisplayName: String? = null,
    ): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = key,
            canonicalUri = "content://media/external/images/${key.substringAfter(':')}",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = dateAddedMillis,
            resolvedCaptureDateMillis = resolvedCaptureDateMillis,
            displayName = displayName,
            width = width,
            height = height,
            durationMs = durationMs,
            isFavorite = isFavorite,
            bucketId = bucketId,
            bucketDisplayName = bucketDisplayName,
        )

    /** The keys of a window, in order --- the timeline's row identity. */
    private fun List<CatalogueRecord>.keys(): List<String> = map { it.localMediaKey }

    // --- ordering: effective instant -----------------------------------------

    @Test
    fun `resolved capture date wins over dateAdded even when dateAdded is newer`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                // date_added screams "newest", but the resolved capture date
                // (the honest instant) places it far in the past.
                record(key = "external_primary:1", dateAddedMillis = 900_000L, resolvedCaptureDateMillis = 1_000L),
                // No capture date: falls back to date_added.
                record(key = "external_primary:2", dateAddedMillis = 2_000L),
            ),
        )

        // Effective instants: 1_000 vs 2_000 --- the fallback ordering, NOT
        // the date_added ordering (which would say 900_000 first).
        assertEquals(
            listOf("external_primary:2", "external_primary:1"),
            catalogue.timelineWindow(0, 10, TimelineDirection.NEWEST_FIRST).keys(),
        )
        assertEquals(
            listOf("external_primary:1", "external_primary:2"),
            catalogue.timelineWindow(0, 10, TimelineDirection.OLDEST_FIRST).keys(),
        )
    }

    @Test
    fun `records without a capture date fall back to dateAdded`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record(key = "external_primary:1", dateAddedMillis = 500L),
                record(key = "external_primary:2", dateAddedMillis = 300L),
                record(key = "external_primary:3", dateAddedMillis = 700L),
            ),
        )

        assertEquals(
            listOf("external_primary:3", "external_primary:1", "external_primary:2"),
            catalogue.timelineWindow(0, 10, TimelineDirection.NEWEST_FIRST).keys(),
        )
        assertEquals(
            listOf("external_primary:2", "external_primary:1", "external_primary:3"),
            catalogue.timelineWindow(0, 10, TimelineDirection.OLDEST_FIRST).keys(),
        )
    }

    @Test
    fun `pre-1970 capture dates order correctly on both sides of the epoch`() = runBlocking {
        // Negative resolved instants are legitimate (the MediaDates
        // contract); they must simply be the smallest instants, not poison
        // the comparator.
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record(key = "external_primary:1", resolvedCaptureDateMillis = -100_000L),
                record(key = "external_primary:2", resolvedCaptureDateMillis = 50_000L),
                record(key = "external_primary:3", resolvedCaptureDateMillis = -5_000L),
            ),
        )

        assertEquals(
            listOf("external_primary:2", "external_primary:3", "external_primary:1"),
            catalogue.timelineWindow(0, 10, TimelineDirection.NEWEST_FIRST).keys(),
        )
    }

    // --- ordering: the key tiebreak ------------------------------------------

    @Test
    fun `equal effective instants break on localMediaKey in the same direction`() = runBlocking {
        // Both records land on the SAME effective instant, one via the
        // capture date and one via the date_added fallback --- proving the
        // two spellings of the instant compare equal, exactly like the
        // DAO's COALESCE does.
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record(key = "external_primary:2", dateAddedMillis = 500L, resolvedCaptureDateMillis = 800L),
                record(key = "external_primary:10", dateAddedMillis = 800L),
            ),
        )

        // The tiebreak is LEXICOGRAPHIC (both Kotlin's String.compareTo and
        // SQLite's BINARY collation), which is why ":10" sorts BEFORE ":2"
        // ascending --- the same ordering the Room query's ORDER BY produces.
        assertEquals(
            listOf("external_primary:2", "external_primary:10"),
            catalogue.timelineWindow(0, 10, TimelineDirection.NEWEST_FIRST).keys(),
        )
        assertEquals(
            listOf("external_primary:10", "external_primary:2"),
            catalogue.timelineWindow(0, 10, TimelineDirection.OLDEST_FIRST).keys(),
        )
    }

    @Test
    fun `oldest-first is newest-first walked backwards over the same total order`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record(key = "external_primary:1", dateAddedMillis = 100L, resolvedCaptureDateMillis = 100L),
                record(key = "external_primary:2", dateAddedMillis = 100L),
                record(key = "external_primary:3", dateAddedMillis = 100L),
                record(key = "external_primary:4", dateAddedMillis = 300L),
                record(key = "external_primary:5", dateAddedMillis = 200L, resolvedCaptureDateMillis = 50L),
            ),
        )

        val newest = catalogue.timelineWindow(0, 100, TimelineDirection.NEWEST_FIRST)
        val oldest = catalogue.timelineWindow(0, 100, TimelineDirection.OLDEST_FIRST)

        assertEquals(newest.keys().reversed(), oldest.keys())
    }

    // --- window tiling --------------------------------------------------------

    @Test
    fun `windows tile with zero gaps and overlaps across page boundaries including ties`() = runBlocking {
        // Nine records with instant groups 100 x4, 200 x2, 300 x3. With a
        // page size of 4 the NEWEST_FIRST boundaries land INSIDE the 200
        // group (between keys 6 and 5) and the 100 group (between keys 2
        // and 1): ties straddle both page boundaries, which is precisely
        // the situation an unordered implementation would use to skip or
        // duplicate a photo (the duplicate-LazyGrid-key failure the
        // interface KDoc cites).
        val records = listOf(
            record(key = "external_primary:1", dateAddedMillis = 100L, resolvedCaptureDateMillis = 100L),
            record(key = "external_primary:2", dateAddedMillis = 100L),
            record(key = "external_primary:3", dateAddedMillis = 100L, resolvedCaptureDateMillis = 100L),
            record(key = "external_primary:4", dateAddedMillis = 100L),
            record(key = "external_primary:5", dateAddedMillis = 200L),
            record(key = "external_primary:6", dateAddedMillis = 200L, resolvedCaptureDateMillis = 200L),
            record(key = "external_primary:7", dateAddedMillis = 300L),
            record(key = "external_primary:8", dateAddedMillis = 300L),
            record(key = "external_primary:9", dateAddedMillis = 300L),
        )
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(records)

        for (direction in TimelineDirection.entries) {
            val full = catalogue.timelineWindow(0, records.size, direction)
            // Page through with a size that leaves a short final page (9
            // records -> 4 + 4 + 1) so both a full page and the tail are
            // exercised.
            val paged = (0..2).flatMap { page ->
                catalogue.timelineWindow(page * 4, 4, direction)
            }

            assertEquals("direction=$direction", full.keys(), paged.keys())
            assertEquals("direction=$direction", records.size, paged.size)
            // Every key exactly once across all pages: no window overlap
            // (a key twice = the duplicate LazyGrid key crash) and no gap
            // (a key never = a silently skipped photo).
            assertEquals("direction=$direction", records.map { it.localMediaKey }.toSet(), paged.keys().toSet())
        }
    }

    @Test
    fun `offset at or beyond the end returns an empty window`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:1"), record(key = "external_primary:2")))

        assertTrue(catalogue.timelineWindow(2, 4, TimelineDirection.NEWEST_FIRST).isEmpty())
        assertTrue(catalogue.timelineWindow(100, 4, TimelineDirection.OLDEST_FIRST).isEmpty())
    }

    @Test
    fun `negative offset or non-positive limit returns an empty window`() = runBlocking {
        // SQLite reads a negative LIMIT as "unbounded"; both implementations
        // must answer invalid arguments deterministically instead --- empty.
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:1")))

        assertTrue(catalogue.timelineWindow(-1, 4, TimelineDirection.NEWEST_FIRST).isEmpty())
        assertTrue(catalogue.timelineWindow(0, 0, TimelineDirection.NEWEST_FIRST).isEmpty())
        assertTrue(catalogue.timelineWindow(0, -5, TimelineDirection.OLDEST_FIRST).isEmpty())
    }

    @Test
    fun `windows carry the display and shape fields a cell renders from`() = runBlocking {
        // The r13 point of the whole round: a record from the timeline
        // window is SUFFICIENT to render a cell --- no MediaStore round
        // trip needed for name, dimensions, duration or the favorite heart.
        val catalogue = InMemoryMediaCatalogue()
        val video = record(
            key = "external_primary:7",
            dateAddedMillis = 5_000L,
            displayName = "VID_20240101_120000.mp4",
            width = 1920,
            height = 1080,
            durationMs = 12_345L,
            isFavorite = true,
        )
        catalogue.upsertAll(listOf(video))

        val windowed = catalogue.timelineWindow(0, 10, TimelineDirection.NEWEST_FIRST).single()

        assertEquals(video, windowed)
        assertEquals("VID_20240101_120000.mp4", windowed.displayName)
        assertEquals(1920, windowed.width)
        assertEquals(1080, windowed.height)
        assertEquals(12_345L, windowed.durationMs)
        assertTrue(windowed.isFavorite)
    }

    @Test
    fun `updateCaptureDate reorders the window without any re-upsert`() = runBlocking {
        // The scan's index pass resolves a capture date AFTER the row was
        // upserted (the r12 targeted write). The timeline must follow: the
        // record climbs to its resolved place purely through the provenance
        // update --- the capture date is the primary sort key, after all.
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record(key = "external_primary:1", dateAddedMillis = 1_000L),
                record(key = "external_primary:2", dateAddedMillis = 2_000L),
            ),
        )
        assertEquals(
            listOf("external_primary:2", "external_primary:1"),
            catalogue.timelineWindow(0, 10, TimelineDirection.NEWEST_FIRST).keys(),
        )

        catalogue.updateCaptureDate(
            "external_primary:1",
            ResolvedCaptureDate(epochMillis = 9_999L, source = CaptureDateSource.EXIF_ORIGINAL),
        )

        assertEquals(
            listOf("external_primary:1", "external_primary:2"),
            catalogue.timelineWindow(0, 10, TimelineDirection.NEWEST_FIRST).keys(),
        )
    }

    // --- r19: the folder-filtered window --------------------------------------

    @Test
    fun `folderTimelineWindow narrows to the bucket's records in the same total order`() =
        runBlocking {
            // Interleaved folders: bucket 2 owns keys 1/3/5 with instants
            // 500/300/700, bucket 7 owns the rest --- the folder window must
            // slice ONLY bucket 2, in the SAME effective-instant order the
            // unfiltered window serves it.
            val catalogue = InMemoryMediaCatalogue()
            catalogue.upsertAll(
                listOf(
                    record(
                        key = "external_primary:1",
                        dateAddedMillis = 500L,
                        bucketId = 2L,
                        bucketDisplayName = "Screenshots",
                    ),
                    record(
                        key = "external_primary:2",
                        dateAddedMillis = 100L,
                        bucketId = 7L,
                        bucketDisplayName = "Camera",
                    ),
                    record(
                        key = "external_primary:3",
                        dateAddedMillis = 300L,
                        bucketId = 2L,
                        bucketDisplayName = "Screenshots",
                    ),
                    record(
                        key = "external_primary:4",
                        dateAddedMillis = 900L,
                        bucketId = 7L,
                        bucketDisplayName = "Camera",
                    ),
                    record(
                        key = "external_primary:5",
                        dateAddedMillis = 700L,
                        bucketId = 2L,
                        bucketDisplayName = "Screenshots",
                    ),
                ),
            )

            assertEquals(
                listOf("external_primary:5", "external_primary:1", "external_primary:3"),
                catalogue.folderTimelineWindow(0, 10, TimelineDirection.NEWEST_FIRST, bucketId = 2L)
                    .keys(),
            )
            assertEquals(
                listOf("external_primary:3", "external_primary:1", "external_primary:5"),
                catalogue.folderTimelineWindow(0, 10, TimelineDirection.OLDEST_FIRST, bucketId = 2L)
                    .keys(),
            )
            // A folder window's records are the WHOLE records --- the
            // bucket pair rides the read (the chip can label the filter
            // from what the window itself returns).
            assertEquals(
                "Screenshots",
                catalogue.folderTimelineWindow(0, 1, TimelineDirection.NEWEST_FIRST, bucketId = 2L)
                    .single()
                    .bucketDisplayName,
            )
        }

    @Test
    fun `records without a bucket are excluded from every folder window`() = runBlocking {
        // The honest-null contract: SQL `=` never matches NULL, and neither
        // does the reference --- a null bucketId (the reduced-projection
        // fallback walk, a provider that dropped the column) belongs to NO
        // folder window, never to a fake bucket 0 (0 is a real bucket id
        // shape and would silently absorb the honestly-bucketless rows).
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record(key = "external_primary:1", bucketId = 2L, bucketDisplayName = "Screenshots"),
                record(key = "external_primary:2", bucketId = null, bucketDisplayName = null),
            ),
        )

        val window = catalogue.folderTimelineWindow(0, 10, TimelineDirection.NEWEST_FIRST, bucketId = 2L)

        assertEquals(listOf("external_primary:1"), window.keys())
        // And a bucket-0 query matches nothing here: 0 is a value, not the
        // null sentinel.
        assertTrue(
            catalogue.folderTimelineWindow(0, 10, TimelineDirection.NEWEST_FIRST, bucketId = 0L)
                .isEmpty(),
        )
    }

    @Test
    fun `folder windows tile across pages inside a bucket in both directions`() = runBlocking {
        // Five records in one bucket with an instant tie straddling the
        // page boundary (page size 2 over five records -> 2 + 2 + 1):
        // pages must tile the FILTERED total order with zero gaps and
        // overlaps, exactly like the unfiltered windows.
        val folderRecords = listOf(
            record(key = "external_primary:1", dateAddedMillis = 100L, bucketId = 9L),
            record(key = "external_primary:2", dateAddedMillis = 200L, bucketId = 9L),
            record(key = "external_primary:3", dateAddedMillis = 200L, bucketId = 9L),
            record(key = "external_primary:4", dateAddedMillis = 300L, bucketId = 9L),
            record(key = "external_primary:5", dateAddedMillis = 400L, bucketId = 9L),
        )
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(folderRecords + record(key = "external_primary:6", bucketId = 7L))

        for (direction in TimelineDirection.entries) {
            val full = catalogue.folderTimelineWindow(0, folderRecords.size, direction, bucketId = 9L)
            val paged = (0..2).flatMap { page ->
                catalogue.folderTimelineWindow(page * 2, 2, direction, bucketId = 9L)
            }

            assertEquals("direction=$direction", full.keys(), paged.keys())
            assertEquals("direction=$direction", folderRecords.size, paged.size)
        }
    }

    @Test
    fun `tombstoned records never surface in a folder window`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record(key = "external_primary:1", bucketId = 2L),
                record(key = "external_primary:2", bucketId = 2L),
            ),
        )

        catalogue.tombstone(listOf("external_primary:1"), atMillis = 10_000L)

        assertEquals(
            listOf("external_primary:2"),
            catalogue.folderTimelineWindow(0, 10, TimelineDirection.NEWEST_FIRST, bucketId = 2L)
                .keys(),
        )
    }

    @Test
    fun `folder window invalid arguments and end offsets read empty`() = runBlocking {
        // The same argument discipline as [timelineWindow]: a negative
        // offset or non-positive limit is empty, never an unbounded read,
        // and an offset at/after the folder's end reads empty.
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:1", bucketId = 2L)))

        assertTrue(
            catalogue.folderTimelineWindow(-1, 4, TimelineDirection.NEWEST_FIRST, bucketId = 2L)
                .isEmpty(),
        )
        assertTrue(
            catalogue.folderTimelineWindow(0, 0, TimelineDirection.NEWEST_FIRST, bucketId = 2L)
                .isEmpty(),
        )
        assertTrue(
            catalogue.folderTimelineWindow(0, -5, TimelineDirection.OLDEST_FIRST, bucketId = 2L)
                .isEmpty(),
        )
        assertTrue(
            catalogue.folderTimelineWindow(1, 4, TimelineDirection.NEWEST_FIRST, bucketId = 2L)
                .isEmpty(),
        )
    }

    @Test
    fun `resolved capture dates order a folder window over dateAdded`() = runBlocking {
        // The COALESCE rule inside a folder: a resolved capture date
        // outranks a "newer" date_added exactly like the unfiltered
        // timeline --- the folder window is a slice of the SAME total
        // order, not a different one.
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(
            listOf(
                record(
                    key = "external_primary:1",
                    dateAddedMillis = 900_000L,
                    resolvedCaptureDateMillis = 1_000L,
                    bucketId = 2L,
                ),
                record(key = "external_primary:2", dateAddedMillis = 2_000L, bucketId = 2L),
            ),
        )

        assertEquals(
            listOf("external_primary:2", "external_primary:1"),
            catalogue.folderTimelineWindow(0, 10, TimelineDirection.NEWEST_FIRST, bucketId = 2L)
                .keys(),
        )
    }

    // --- liveItemCount --------------------------------------------------------

    @Test
    fun `liveItemCount counts live records and excludes tombstones`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        assertEquals(0, catalogue.liveItemCount())

        catalogue.upsertAll(
            (1..5).map { record(key = "external_primary:$it") },
        )
        assertEquals(5, catalogue.liveItemCount())

        catalogue.tombstone(listOf("external_primary:1", "external_primary:3"), atMillis = 10_000L)
        assertEquals(3, catalogue.liveItemCount())

        // Tombstoning a key with no live record is bookkeeping, not a live
        // set change.
        catalogue.tombstone(listOf("external_primary:99"), atMillis = 11_000L)
        assertEquals(3, catalogue.liveItemCount())

        // Resurrection restores the count.
        catalogue.upsertAll(listOf(record(key = "external_primary:1")))
        assertEquals(4, catalogue.liveItemCount())
    }

    // --- changes(): the invalidation signal -----------------------------------

    @Test
    fun `changes emits after upsert, capture-date update and tombstone`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val emissions = mutableListOf<Unit>()
        // The collector runs on runBlocking's single-threaded event loop;
        // each yield() below hands it the chance to process exactly what the
        // preceding mutation signalled --- deterministic, no scheduler luck.
        val job = launch { catalogue.changes().collect { emissions += it } }
        yield() // let the collector reach its suspended collect first

        catalogue.upsertAll(listOf(record(key = "external_primary:1"), record(key = "external_primary:2")))
        yield()
        assertEquals(1, emissions.size)

        // The capture date is the timeline's primary sort key: a provenance
        // write reorders windows and must invalidate.
        catalogue.updateCaptureDate(
            "external_primary:1",
            ResolvedCaptureDate(epochMillis = 1L, source = CaptureDateSource.EXIF_ORIGINAL),
        )
        yield()
        assertEquals(2, emissions.size)

        catalogue.tombstone(listOf("external_primary:2"), atMillis = 1L)
        yield()
        assertEquals(3, emissions.size)

        job.cancel()
    }

    @Test
    fun `changes stays silent for no-op mutations, purge and checkpoint saves`() = runBlocking {
        val catalogue = InMemoryMediaCatalogue()
        val emissions = mutableListOf<Unit>()
        val job = launch { catalogue.changes().collect { emissions += it } }
        yield()

        // Seed one live record so the no-op paths have something to miss.
        catalogue.upsertAll(listOf(record(key = "external_primary:1")))
        yield()
        assertEquals(1, emissions.size)

        // No-op mutations: nothing that can change the live set or its
        // order, so nothing may signal.
        catalogue.upsertAll(emptyList())
        catalogue.tombstone(emptyList(), atMillis = 1L)
        catalogue.updateCaptureDate(
            "external_primary:404",
            ResolvedCaptureDate(epochMillis = 1L, source = CaptureDateSource.EXIF_ORIGINAL),
        )
        catalogue.purgeTombstonesOlderThan(Long.MAX_VALUE)
        catalogue.saveScanCheckpoint("external_primary", generationToken = "1", scannedAtMillis = 1L)
        yield()
        assertEquals(1, emissions.size)

        job.cancel()
    }

    @Test
    fun `changes never emits before the first mutation`() = runBlocking {
        // Hot-signal semantics: no initial emission on collection, no replay
        // of mutations that fired before subscription --- a collector must
        // re-read independently (the Room implementation drops its flow
        // query's initial result for exactly this reason).
        val catalogue = InMemoryMediaCatalogue()
        catalogue.upsertAll(listOf(record(key = "external_primary:1"))) // pre-collection mutation
        val emissions = mutableListOf<Unit>()
        val job = launch { catalogue.changes().collect { emissions += it } }
        yield()

        assertTrue(emissions.isEmpty())

        job.cancel()
    }
}
