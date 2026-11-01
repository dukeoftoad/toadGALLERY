package io.github.dukeoftoad.gallery.data.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the CATALOGUE WHERE projection of the r17 smart-filter
 * engine ([TimelineFilterCatalogue]): the exact SQL fragment + args over
 * ONLY the columns `CatalogueItemEntity` already persists (mimeType,
 * isFavorite, dateAddedMillis --- no bucket, no media_type), plus the
 * [TimelineFilterCatalogue.canServe] gate that keeps the honest-subset rule
 * honest.
 *
 * The video-ness clause is pinned to match the catalogue timeline's own
 * rule (`CatalogueTimelineItem.isVideo` = `mimeType.startsWith("video/")`)
 * so the chip and the grid can never disagree; see
 * [TimelineFilterCatalogue]'s KDoc for the column inventory.
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
    fun `a bucket filter cannot be served and fails closed`() {
        // No bucket column in the schema: the gate says so, and a caller
        // that ignores the gate gets the impossible clause --- a VISIBLE
        // empty window, never a silently unfiltered one.
        val bucketFilter = TimelineFilter(bucketId = 42L)
        assertFalse(TimelineFilterCatalogue.canServe(bucketFilter))
        val where = whereOf(bucketFilter)
        assertEquals("1 = 0", where.sql)
        assertEquals(emptyList<Any>(), where.args)
    }
}
