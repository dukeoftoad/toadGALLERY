package io.github.dukeoftoad.gallery.data

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.InMemoryMediaCatalogue
import io.github.dukeoftoad.gallery.data.catalogue.TimelineDirection
import io.github.dukeoftoad.gallery.data.media.changes.CatalogueReconciler
import io.github.dukeoftoad.gallery.data.media.changes.MediaScanRow
import io.github.dukeoftoad.gallery.data.filters.FilterMediaType
import io.github.dukeoftoad.gallery.data.filters.TimelineFilter
import io.github.dukeoftoad.gallery.data.filters.TimelineFilterFields
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The r28 10k/50k LIBRARY BENCHMARKS (Workstream 8, Phase 5 hardening;
 * `docs/phase-5/hardening.md` §Benchmarks): JVM performance tests with
 * SYNTHETIC catalogue datasets, asserting generous but MEANINGFUL time
 * bounds on the hot paths a real library size actually exercises:
 *
 *  - the catalogue's TIMELINE WINDOW queries ([timelineWindow] and the
 *    r19 [InMemoryMediaCatalogue.folderTimelineWindow]) --- the
 *    reference implementations the Room catalogue's SQL windows must
 *    match, and the read the Photos timeline issues per page;
 *  - [WindowPagingSource] walks over the catalogue's windows --- the
 *    Paging 3 load loop (ADR-0015), several windows deep;
 *  - [CatalogueReconciler.reconcile] --- the scan diff the Phase 4
 *    pipeline (and through it the Phase 5 queue's invalidation
 *    contract) runs after every authoritative scan;
 *  - the in-memory [TimelineFilter.matches] projection --- the pre-R
 *    snapshot path's filter handoff over a fully materialized library.
 *
 * Budget philosophy (deliberately NOT micro-benchmarks): the bounds are
 * 30–150× the expected cost on a shared 2-core CI runner, so normal
 * JIT/GC/runner variance can never flake the suite --- but every budget
 * is far below the cost of the REGRESSION class it exists to catch: an
 * accidental O(n²) at 50k rows is billions of operations (tens of
 * seconds to minutes) and blows every budget here by an order of
 * magnitude. A budget that never fails catches nothing; a budget that
 * flakes catches only itself.
 *
 * Each benchmark prints one `r28-benchmark |` line with the measured
 * milliseconds; the CI test report carries them, and
 * `docs/phase-5/hardening.md` records the measured CI numbers against
 * these budgets. Datasets are built SYNTHETICALLY with a fixed seed
 * (deterministic runs, no MediaStore, no android.* types --- ADR-0016),
 * modeled on a realistic library mix: ~70% JPEGs, ~25% videos, ~5%
 * GIFs, 8 folders, capture dates spread across five years, a small
 * favorites slice.
 *
 * Whole-class cost target: well under 60 s on a CI runner (measured
 * ~2–4 s; see the hardening doc's CI numbers) --- the existing ci.yml
 * `build` job timeout (20 min) is untouched by this suite.
 */
class LibraryScaleBenchmarkTest {

    /** Library sizes the exit gate names: 10k (upper-normal) and 50k (stress). */
    private companion object {
        const val SMALL_LIBRARY = 10_000
        const val LARGE_LIBRARY = 50_000

        /** Distinct folders the synthetic library spreads over. */
        const val BUCKETS = 8

        // Budgets (ms) --- see the class KDoc for the philosophy.
        const val BUDGET_WINDOW_10K = 300L
        const val BUDGET_WINDOW_50K = 1_500L
        const val BUDGET_FOLDER_WINDOW_50K = 1_500L
        const val BUDGET_PAGING_WALK_50K = 5_000L
        const val BUDGET_RECONCILE_10K = 500L
        const val BUDGET_RECONCILE_50K = 1_000L
        const val BUDGET_FILTER_50K = 250L
    }

    // ------------------------------------------------------------------
    // Synthetic datasets
    // ------------------------------------------------------------------

    private val mimeTypes = arrayOf("image/jpeg", "image/jpeg", "image/jpeg", "video/mp4", "image/gif")

    /**
     * Builds [count] synthetic catalogue records with a deterministic
     * seed: keys `external_primary:<i>`, a realistic MIME mix, capture
     * dates spread across five years (some resolvable, some falling
     * back to date_added --- both orderings the timeline serves), 8
     * buckets, ~4% favorites.
     */
    private fun syntheticRecords(count: Int): List<CatalogueRecord> {
        val random = Random(seed = 42)
        val base = 1_600_000_000_000L // Nov 2020
        val span = 5L * 365 * 24 * 60 * 60 * 1000 // five years
        return (1..count).map { i ->
            val added = base + random.nextLong(span)
            // ~80% of rows carry a resolved capture date (the r13 mix),
            // slightly offset from date_added; the rest fall back.
            val resolved = if (random.nextInt(100) < 80) added + random.nextLong(86_400_000L) else null
            CatalogueRecord(
                localMediaKey = "external_primary:$i",
                canonicalUri = "content://media/external/images/$i",
                mimeType = mimeTypes[random.nextInt(mimeTypes.size)],
                sizeBytes = 100_000L + random.nextLong(5_000_000L),
                dateModifiedMillis = added + random.nextLong(1_000_000L),
                dateAddedMillis = added,
                generationToken = if (random.nextInt(100) < 60) random.nextLong(9_999L).toString() else null,
                resolvedCaptureDateMillis = resolved,
                isFavorite = random.nextInt(100) < 4,
                bucketId = (i % BUCKETS).toLong(),
                bucketDisplayName = "Bucket ${i % BUCKETS}",
            )
        }
    }

    /** The scan-row view of a synthetic record (reconciler input shape). */
    private fun scanRowOf(record: CatalogueRecord, sizeDelta: Long = 0L): MediaScanRow = MediaScanRow(
        localMediaKey = record.localMediaKey,
        canonicalUri = record.canonicalUri,
        mimeType = record.mimeType,
        sizeBytes = record.sizeBytes + sizeDelta,
        dateModifiedMillis = record.dateModifiedMillis,
        dateAddedMillis = record.dateAddedMillis,
        generationToken = record.generationToken,
        isFavorite = record.isFavorite,
        bucketId = record.bucketId,
        bucketDisplayName = record.bucketDisplayName,
    )

    // ------------------------------------------------------------------
    // Timing harness
    // ------------------------------------------------------------------

    /** Runs [block], returning its result paired with the elapsed nanos. */
    private suspend fun <T> measured(block: suspend () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start)
    }

    private fun assertWithinBudget(what: String, elapsedNanos: Long, budgetMillis: Long) {
        val elapsedMillis = elapsedNanos / 1_000_000
        // The measured number lands in the CI test report (Gradle
        // captures stdout) --- docs/phase-5/hardening.md records it.
        println("r28-benchmark | $what: ${elapsedMillis} ms (budget $budgetMillis ms)")
        assertTrue(
            "$what took ${elapsedMillis} ms --- over the ${budgetMillis} ms budget. This is a " +
                "hot-path regression at library scale, not runner noise (budgets are 30-150x the " +
                "expected cost; see docs/phase-5/hardening.md, Benchmarks).",
            elapsedNanos < budgetMillis * 1_000_000,
        )
    }

    private fun catalogueOf(records: List<CatalogueRecord>): InMemoryMediaCatalogue =
        InMemoryMediaCatalogue().also { cat ->
            runBlocking { cat.upsertAll(records) }
        }

    // ------------------------------------------------------------------
    // Benchmark 1: the timeline window query
    // ------------------------------------------------------------------

    /**
     * One [InMemoryMediaCatalogue.timelineWindow] read (the reference
     * implementation re-sorts the live map per call --- the reference's
     * dumb-and-pure price; production ordering lives in the DAO's SQL).
     * The budget covers the sort + slice at each scale.
     */
    @Test
    fun `benchmark - one timeline window read at 10k and 50k`() = runBlocking {
        for ((count, budget) in listOf(SMALL_LIBRARY to BUDGET_WINDOW_10K, LARGE_LIBRARY to BUDGET_WINDOW_50K)) {
            val catalogue = catalogueOf(syntheticRecords(count))
            // Warm-up (JIT): one unmeasured read settles the compiled path.
            assertTrue(catalogue.timelineWindow(0, 100, TimelineDirection.NEWEST_FIRST).size == 100)

            val (window, elapsed) = measured {
                catalogue.timelineWindow(0, 100, TimelineDirection.NEWEST_FIRST)
            }

            assertEquals(100, window.size) // non-vacuous: the read really happened
            assertWithinBudget("timelineWindow(0,100) NEWEST_FIRST @ $count records", elapsed, budget)
        }
    }

    /** The oldest-first twin (the Favorites "Oldest" sort rides this direction). */
    @Test
    fun `benchmark - one oldest-first timeline window read at 50k`() = runBlocking {
        val catalogue = catalogueOf(syntheticRecords(LARGE_LIBRARY))
        assertTrue(catalogue.timelineWindow(0, 100, TimelineDirection.OLDEST_FIRST).size == 100)

        val (window, elapsed) = measured {
            catalogue.timelineWindow(0, 100, TimelineDirection.OLDEST_FIRST)
        }

        assertEquals(100, window.size)
        assertWithinBudget("timelineWindow(0,100) OLDEST_FIRST @ 50k records", elapsed, BUDGET_WINDOW_50K)
    }

    /**
     * The r19 FOLDER-FILTERED window (the `WHERE bucketId = ?` reference):
     * filter (50k) + sort (~1/8 of the library) + slice.
     */
    @Test
    fun `benchmark - one folder-filtered window read at 50k`() = runBlocking {
        val catalogue = catalogueOf(syntheticRecords(LARGE_LIBRARY))
        assertTrue(
            catalogue.folderTimelineWindow(0, 100, TimelineDirection.NEWEST_FIRST, bucketId = 3L).size == 100,
        )

        val (window, elapsed) = measured {
            catalogue.folderTimelineWindow(0, 100, TimelineDirection.NEWEST_FIRST, bucketId = 3L)
        }

        assertEquals(100, window.size)
        assertTrue(window.all { it.bucketId == 3L }) // non-vacuous: the filter really applied
        assertWithinBudget("folderTimelineWindow(0,100) bucket=3 @ 50k records", elapsed, BUDGET_FOLDER_WINDOW_50K)
    }

    // ------------------------------------------------------------------
    // Benchmark 2: the Paging 3 walk over the catalogue's windows
    // ------------------------------------------------------------------

    /**
     * [WindowPagingSource] over the catalogue's [timelineWindow]: a
     * 20-window deep walk (10,000 items of the 50k library) in
     * 500-item windows --- the shape a user's fast scroll generates.
     * Each load is one catalogue window read (sort + slice), so the
     * walk's cost is ~20 windows; the budget covers them with margin.
     */
    @Test
    fun `benchmark - a 20-window WindowPagingSource walk over the 50k catalogue`() = runBlocking {
        val records = syntheticRecords(LARGE_LIBRARY)
        val catalogue = catalogueOf(records)
        val pagingSource = WindowPagingSource { offset, limit ->
            catalogue.timelineWindow(offset, limit, TimelineDirection.NEWEST_FIRST)
        }
        // Warm-up: one refresh load.
        assertTrue(
            (pagingSource.load(
                androidx.paging.PagingSource.LoadParams.Refresh(null, 500, false),
            ) as androidx.paging.PagingSource.LoadResult.Page).data.size == 500,
        )

        val (walked, elapsed) = measured {
            var walkedItems = 0
            var key: Int? = 0
            var windows = 0
            while (key != null && windows < 20) {
                val page = pagingSource.load(
                    androidx.paging.PagingSource.LoadParams.Append(key!!, 500, false),
                ) as androidx.paging.PagingSource.LoadResult.Page
                walkedItems += page.data.size
                key = page.nextKey
                windows++
            }
            walkedItems
        }

        assertEquals("20 full windows of 500 items each", 10_000, walked) // non-vacuous walk
        assertWithinBudget("WindowPagingSource 20x500-item append walk @ 50k records", elapsed, BUDGET_PAGING_WALK_50K)
    }

    // ------------------------------------------------------------------
    // Benchmark 3: the scan diff (CatalogueReconciler)
    // ------------------------------------------------------------------

    /**
     * [CatalogueReconciler.reconcile] at 10k and 50k: an authoritative
     * scan against the cached catalogue with a realistic churn mix ---
     * ~5% of rows content-changed (size moved), ~2% newly added (no
     * cached record), ~3% removed (cached but not scanned; the vault
     * move's reconciler shape), the rest unchanged. One pass over both
     * sides with hash lookups: linear expected.
     */
    @Test
    fun `benchmark - one reconciler diff at 10k and 50k with realistic churn`() = runBlocking {
        for ((count, budget) in listOf(SMALL_LIBRARY to BUDGET_RECONCILE_10K, LARGE_LIBRARY to BUDGET_RECONCILE_50K)) {
            val records = syntheticRecords(count)
            val catalogueMap = records.associateBy { it.localMediaKey }
            // Removed rows: every 33rd cached record is absent from the scan
            // (deleted / trashed / vault-moved media --- tombstone candidates).
            val scanRows = records
                .filterIndexed { index, _ -> index % 33 != 0 }
                .map { record ->
                    // Changed rows: every 20th scanned row moved its size.
                    if (record.localMediaKey.hashCode() % 20 == 0) scanRowOf(record, sizeDelta = 1L) else scanRowOf(record)
                }
                .toMutableList()
            // Added rows: 2% new keys the catalogue has never seen.
            repeat(count / 50) { i ->
                scanRows += scanRowOf(records[i]).copy(localMediaKey = "external_primary:new-$i")
            }

            // Warm-up pass on a small prefix.
            CatalogueReconciler.reconcile(scanRows.take(100), catalogueMap)

            val (reconciliation, elapsed) = measured {
                CatalogueReconciler.reconcile(scanRows, catalogueMap)
            }

            // Non-vacuous: the churn mix really diffed (approximately ---
            // the hash-based selection is deterministic but not exactly
            // counted; the invariant is: everything landed somewhere).
            val accounted = reconciliation.added.size + reconciliation.changed.size + reconciliation.unchangedCount
            assertEquals("every scanned row lands in exactly one bucket", scanRows.size, accounted)
            assertTrue("the removed-keys bucket must be non-empty (the vault-move shape)", reconciliation.removedKeys.isNotEmpty())
            assertTrue("the changed bucket must be non-empty (the size-moved rows)", reconciliation.changed.isNotEmpty())
            assertWithinBudget("CatalogueReconciler.reconcile @ $count rows", elapsed, budget)
        }
    }

    // ------------------------------------------------------------------
    // Benchmark 4: the filter handoff (in-memory projection)
    // ------------------------------------------------------------------

    /**
     * The pre-R snapshot path's filter handoff: [TimelineFilter.matches]
     * over a fully materialized 50k-item field projection (the path the
     * MediaStore snapshot takes when a chip is active). Linear with a
     * tiny constant; the budget guards against an accidentally quadratic
     * predicate composition.
     */
    @Test
    fun `benchmark - the in-memory filter projection over 50k items`() = runBlocking {
        val records = syntheticRecords(LARGE_LIBRARY)
        val fields = records.map { record ->
            TimelineFilterFields(
                mimeType = record.mimeType,
                isVideo = record.mimeType.startsWith("video/"),
                isFavorite = record.isFavorite,
                dateAddedSec = record.dateAddedMillis / 1000,
            )
        }
        val filter = TimelineFilter(
            types = setOf(FilterMediaType.PHOTOS, FilterMediaType.GIFS),
            favoritesOnly = false,
        )
        // Warm-up:
        assertTrue(fields.take(100).count { filter.matches(it) } > 0)

        val (matched, elapsed) = measured {
            fields.count { filter.matches(it) }
        }

        // Non-vacuous: the photos-only filter keeps the non-video items.
        assertEquals("the photos-only filter matches exactly the non-video items", fields.count { !it.isVideo }, matched)
        assertWithinBudget("TimelineFilter.matches photos-only @ 50k items", elapsed, BUDGET_FILTER_50K)
    }
}
