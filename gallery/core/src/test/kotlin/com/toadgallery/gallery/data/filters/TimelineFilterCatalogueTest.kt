package com.toadgallery.gallery.data.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the CATALOGUE WHERE projection of the r17 smart-filter
 * engine ([TimelineFilterCatalogue]): the exact SQL fragment + args over
 * ONLY the columns `CatalogueItemEntity` already persists (mimeType,
 * isFavorite, bucketId --- the r19 Room v5 column --- dateAddedMillis),
 * plus the [TimelineFilterCatalogue.canServe] gate that keeps the
 * honest-subset rule honest.
 *
 * The video-ness clause is pinned to match the catalogue timeline's own
 * rule (`CatalogueTimelineItem.isVideo` = `mimeType.startsWith("video/")`)
 * so the chip and the grid can never disagree; the bucket clause is
 * pinned to the same bucket-id-matching semantics as the MediaStore
 * projection's `bucket_id = ?` (`TimelineFilterMediaStoreTest`'s value
 * dimensions test --- the two projections must never disagree about
 * which folder a filter means). See [TimelineFilterCatalogue]'s KDoc for
 * the column inventory.
 */
class TimelineFilterCatalogueTest {

    private fun whereOf(filter: TimelineFilter) = TimelineFilterCatalogue.where(filter)

    @Test
    fun `an empty filter projects to no WHERE fragment`() {
        val where = whereOf(TimelineFilter())
        assertNull(where.sql)
        assertEquals(emptyList<Any>(), where.args)
        assertTrue(TimelineFilterCatalogue.canServe(TimelineFilter()))
    }

    @Test
    fun `the type clauses read the mime column, not a media_type column`() {
        // The entity persists NO media_type --- video-ness derives from the
        // mime, exactly like the catalogue timeline's own isVideo rule, and
        // "photos" is the video complement (which also catches the scan's
        // image/* and application/octet-stream fallback mimes).
        assertEquals(
            "mimeType NOT LIKE 'video/%'",
            whereOf(TimelineFilter(types = setOf(FilterMediaType.PHOTOS))).sql,
        )
        assertEquals(
            "mimeType LIKE 'video/%'",
            whereOf(TimelineFilter(types = setOf(FilterMediaType.VIDEOS))).sql,
        )
        assertEquals(
            "mimeType LIKE 'image/gif'",
            whereOf(TimelineFilter(types = setOf(FilterMediaType.GIFS))).sql,
        )
        assertEquals(
            "(mimeType LIKE 'video/%' OR mimeType LIKE 'image/gif')",
            whereOf(
                TimelineFilter(
                    types = setOf(FilterMediaType.VIDEOS, FilterMediaType.GIFS),
                ),
            ).sql,
        )
    }

    @Test
    fun `favorites reads the isFavorite ride-along column`() {
        assertEquals(
            "isFavorite = 1",
            whereOf(TimelineFilter(favoritesOnly = true)).sql,
        )
    }

    @Test
    fun `the date range divides dateAddedMillis back to seconds and binds long args`() {
        val where = whereOf(
            TimelineFilter(
                dateRange = FilterDateRange(
                    startMillis = 1_700_000_000_000L,
                    endMillis = 1_700_008_640_000L,
                ),
            ),
        )
        assertEquals("(dateAddedMillis / 1000) BETWEEN ? AND ?", where.sql)
        assertEquals(listOf<Any>(1_700_000_000L, 1_700_008_640L), where.args)
    }

    @Test
    fun `type, favorites and date range compose in a fixed clause order`() {
        val where = whereOf(
            TimelineFilter(
                types = setOf(FilterMediaType.VIDEOS),
                favoritesOnly = true,
                dateRange = FilterDateRange(
                    startMillis = 1_700_000_000_000L,
                    endMillis = 1_700_008_640_000L,
                ),
            ),
        )
        assertEquals(
            "mimeType LIKE 'video/%' AND " +
                "isFavorite = 1 AND " +
                "(dateAddedMillis / 1000) BETWEEN ? AND ?",
            where.sql,
        )
        assertEquals(listOf<Any>(1_700_000_000L, 1_700_008_640L), where.args)
    }

    @Test
    fun `the folder bucket filter is served from the bucketId column`() {
        // The r19 v5 column closes the honest-subset gap: the gate passes,
        // and the projection binds the id as a BOUND arg (data, not
        // vocabulary) with the SAME bucket-id-matching semantics as the
        // MediaStore projection's `bucket_id = ?`. SQL `=` never matches
        // NULL, so honestly-bucketless rows belong to no folder window.
        val bucketFilter = TimelineFilter(bucketId = 4711L)
        assertTrue(TimelineFilterCatalogue.canServe(bucketFilter))
        val where = whereOf(bucketFilter)
        assertEquals("bucketId = ?", where.sql)
        assertEquals(listOf<Any>(4711L), where.args)
    }

    @Test
    fun `a negative bucket id binds like any value`() {
        // Parity with the MediaStore projection's -4711 case: the favorites
        // pseudo-album sentinel (Favorites.FAVORITES_BUCKET_ID is negative)
        // is a VALUE here, not a sentinel the engine interprets.
        val where = whereOf(TimelineFilter(bucketId = -4711L))
        assertEquals("bucketId = ?", where.sql)
        assertEquals(listOf<Any>(-4711L), where.args)
    }

    @Test
    fun `canServe passes for every dimension after the v5 bucket column`() {
        // The honest-subset rule, re-verified: every dimension the engine
        // can express reads a persisted column, so the backend ladder may
        // route ANY filter to the catalogue. A future dimension that
        // cannot be served must flip this gate (and extend this test).
        val filters = listOf(
            TimelineFilter(),
            TimelineFilter(types = setOf(FilterMediaType.PHOTOS)),
            TimelineFilter(favoritesOnly = true),
            TimelineFilter(bucketId = 42L),
            TimelineFilter(dateRange = FilterDateRange(startMillis = 0L, endMillis = 1L)),
            TimelineFilter(
                types = setOf(FilterMediaType.VIDEOS, FilterMediaType.GIFS),
                favoritesOnly = true,
                bucketId = 42L,
                dateRange = FilterDateRange(startMillis = 0L, endMillis = 1L),
            ),
        )
        filters.forEach { filter ->
            assertTrue("filter=$filter", TimelineFilterCatalogue.canServe(filter))
        }
    }

    @Test
    fun `type, favorites, bucket and date range compose in the fixed clause order`() {
        // The full-dimension composition: clause order type -> favorites
        // -> bucket -> date range (the same order the MediaStore projection
        // composes), args in placeholder order (bucket id, then the two
        // second bounds) --- the exact contract FilteredWindowSql appends
        // its LIMIT/OFFSET ints onto.
        val where = whereOf(
            TimelineFilter(
                types = setOf(FilterMediaType.VIDEOS),
                favoritesOnly = true,
                bucketId = 7L,
                dateRange = FilterDateRange(
                    startMillis = 1_700_000_000_000L,
                    endMillis = 1_700_008_640_000L,
                ),
            ),
        )
        assertEquals(
            "mimeType LIKE 'video/%' AND " +
                "isFavorite = 1 AND " +
                "bucketId = ? AND " +
                "(dateAddedMillis / 1000) BETWEEN ? AND ?",
            where.sql,
        )
        assertEquals(listOf<Any>(7L, 1_700_000_000L, 1_700_008_640L), where.args)
    }

    @Test
    fun `clean feed projects the bucketDisplayName exclusion clause`() {
        // r76: the No-Screenshots pill's Room leg --- NULL-named rows stay
        // visible (the fail-open rule the in-memory predicate and the
        // MediaStore projection share).
        assertEquals(
            "(bucketDisplayName IS NULL OR bucketDisplayName NOT LIKE '%screenshot%')",
            whereOf(TimelineFilter(excludeScreenshots = true)).sql,
        )
        // Composes AND with the other dimensions, last in the fixed order.
        assertEquals(
            "mimeType NOT LIKE 'video/%' AND " +
                "(bucketDisplayName IS NULL OR bucketDisplayName NOT LIKE '%screenshot%')",
            whereOf(
                TimelineFilter(
                    types = setOf(FilterMediaType.PHOTOS),
                    excludeScreenshots = true,
                ),
            ).sql,
        )
        // No args: the clause is vocabulary, not values.
        assertTrue(whereOf(TimelineFilter(excludeScreenshots = true)).args.isEmpty())
    }
}
