package com.toadgallery.gallery.data.catalogue.room

import com.toadgallery.gallery.data.catalogue.TimelineDirection
import com.toadgallery.gallery.data.filters.FilterMediaType
import com.toadgallery.gallery.data.filters.TimelineFilter
import com.toadgallery.gallery.data.filters.TimelineFilterCatalogue.CatalogueFilterWhere
import com.toadgallery.gallery.data.filters.TimelineFilterCatalogue.where
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [FilteredWindowSql] --- the pure composition behind
 * [RoomMediaCatalogue.filteredTimelineWindow] (r19). An audit flagged the
 * composed statement as untestable while it lived inline in the ADR-0016
 * untested wrapper; the extraction exists so these tests can pin the two
 * things Room never verifies before runtime:
 *
 * - the EXACT statement text the DAO's @RawQuery window receives (the
 *   fragment spliced between FROM and ORDER BY, the SAME total ORDER BY the
 *   fixed window queries use, LIMIT/OFFSET as bound placeholders), and
 * - the ARG ORDER: the fragment's args first (their `?` placeholders open
 *   the statement), then the LIMIT int, then the OFFSET int --- a swapped
 *   order would bind a bucket id as a LIMIT and answer a VISIBLE lie.
 *
 * The end-to-end test composes a REAL engine projection (the r19 bucket
 * clause included), so the whole fragment-args + windowing-args contract is
 * pinned against production fragments, not just synthetic ones.
 *
 * Pure JUnit 4 / JVM (ADR-0016): no android.* types; the wrapper wraps the
 * composed result in a SimpleSQLiteQuery at the thin seam.
 */
class FilteredWindowSqlTest {

    @Test
    fun `a null fragment composes the unfiltered statement with DESC direction words`() {
        val composed = FilteredWindowSql.compose(
            where = CatalogueFilterWhere(sql = null, args = emptyList()),
            direction = TimelineDirection.NEWEST_FIRST,
            offset = 120,
            limit = 60,
        )

        assertEquals(
            "SELECT * FROM catalogue_items " +
                "ORDER BY COALESCE(resolvedCaptureDateMillis, dateAddedMillis) DESC, " +
                "localMediaKey DESC " +
                "LIMIT ? OFFSET ?",
            composed.sql,
        )
        // The two windowing ints close the bind array in LIMIT/OFFSET order.
        assertEquals(listOf<Any?>(60, 120), composed.bindArgs)
    }

    @Test
    fun `oldest-first composes the same statement with ASC direction words`() {
        val composed = FilteredWindowSql.compose(
            where = CatalogueFilterWhere(sql = null, args = emptyList()),
            direction = TimelineDirection.OLDEST_FIRST,
            offset = 60,
            limit = 60,
        )

        assertEquals(
            "SELECT * FROM catalogue_items " +
                "ORDER BY COALESCE(resolvedCaptureDateMillis, dateAddedMillis) ASC, " +
                "localMediaKey ASC " +
                "LIMIT ? OFFSET ?",
            composed.sql,
        )
        assertEquals(listOf<Any?>(60, 60), composed.bindArgs)
    }

    @Test
    fun `a where fragment is spliced verbatim between FROM and ORDER BY`() {
        // The fragment is NEVER re-parsed, re-ordered or re-worded here ---
        // it arrives fully composed from the pure, JVM-tested filter engine
        // and this composition may only place it.
        val composed = FilteredWindowSql.compose(
            where = CatalogueFilterWhere(sql = "bucketId = ?", args = listOf(4711L)),
            direction = TimelineDirection.NEWEST_FIRST,
            offset = 180,
            limit = 60,
        )

        assertEquals(
            "SELECT * FROM catalogue_items WHERE bucketId = ? " +
                "ORDER BY COALESCE(resolvedCaptureDateMillis, dateAddedMillis) DESC, " +
                "localMediaKey DESC " +
                "LIMIT ? OFFSET ?",
            composed.sql,
        )
        assertEquals(listOf<Any?>(4711L, 60, 180), composed.bindArgs)
    }

    @Test
    fun `fragment args bind before the limit and offset ints`() {
        // THE arg-order contract: the fragment's placeholders open the
        // statement, so its args must open the bind array; the windowing
        // ints close it in LIMIT/OFFSET order. A composition that swaps
        // them binds a bucket id as a LIMIT --- an empty page and a VISIBLE
        // lie instead of a crash.
        val composed = FilteredWindowSql.compose(
            where = CatalogueFilterWhere(
                sql = "bucketId = ? AND (dateAddedMillis / 1000) BETWEEN ? AND ?",
                args = listOf(7L, 1_700_000_000L, 1_700_008_640L),
            ),
            direction = TimelineDirection.OLDEST_FIRST,
            offset = 240,
            limit = 60,
        )

        assertEquals(
            listOf<Any?>(7L, 1_700_000_000L, 1_700_008_640L, 60, 240),
            composed.bindArgs,
        )
    }

    @Test
    fun `a real engine projection with the bucket clause composes end to end`() {
        // End to end: the engine's own projection for a full-dimension
        // filter (type + favorites + the r19 folder clause) composed
        // exactly as the wrapper hands it to the DAO --- the statement the
        // catalogue's filtered timeline windows run on a granted library.
        val composed = FilteredWindowSql.compose(
            where = where(
                TimelineFilter(
                    types = setOf(FilterMediaType.VIDEOS),
                    favoritesOnly = true,
                    bucketId = 7L,
                ),
            ),
            direction = TimelineDirection.NEWEST_FIRST,
            offset = 60,
            limit = 60,
        )

        assertEquals(
            "SELECT * FROM catalogue_items " +
                "WHERE mimeType LIKE 'video/%' AND " +
                "isFavorite = 1 AND " +
                "bucketId = ? " +
                "ORDER BY COALESCE(resolvedCaptureDateMillis, dateAddedMillis) DESC, " +
                "localMediaKey DESC " +
                "LIMIT ? OFFSET ?",
            composed.sql,
        )
        assertEquals(listOf<Any?>(7L, 60, 60), composed.bindArgs)
    }
}
