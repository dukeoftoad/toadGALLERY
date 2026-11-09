package com.toadgallery.gallery.data.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the MEDIASTORE selection projection of the r17 smart-filter
 * engine ([TimelineFilterMediaStore.selection]): the exact selection text and
 * bound args for every dimension, pinned verbatim like `SearchQueryTest`
 * pins its specs (the same "SQL literals live in the pure layer" convention).
 *
 * The constant values behind the literals are documented against the SDK
 * reference pages in [TimelineFilterMediaStore]'s KDoc (IMAGE = 1,
 * VIDEO = 3 --- the engine deliberately does NOT reuse the older files'
 * `2 == video` claim, which the reference page contradicts).
 */
class TimelineFilterMediaStoreTest {

    private fun selectionOf(filter: TimelineFilter) =
        TimelineFilterMediaStore.selection(filter)

    @Test
    fun `an empty filter projects to no selection and no args`() {
        // The pre-r17 query shape, exactly: nothing to AND after the base.
        val projection = selectionOf(TimelineFilter())
        assertNull(projection.selection)
        assertEquals(emptyList<String>(), projection.selectionArgs)
    }

    @Test
    fun `each type coverage projects one fixed literal clause`() {
        assertEquals(
            "media_type = 1",
            selectionOf(TimelineFilter(types = setOf(FilterMediaType.PHOTOS))).selection,
        )
        assertEquals(
            "media_type = 3",
            selectionOf(TimelineFilter(types = setOf(FilterMediaType.VIDEOS))).selection,
        )
        assertEquals(
            "mime_type LIKE 'image/gif'",
            selectionOf(TimelineFilter(types = setOf(FilterMediaType.GIFS))).selection,
        )
        assertEquals(
            "(media_type = 3 OR mime_type LIKE 'image/gif')",
            selectionOf(
                TimelineFilter(
                    types = setOf(FilterMediaType.VIDEOS, FilterMediaType.GIFS),
                ),
            ).selection,
        )
        // The subset rule: Photos+Videos (with or without GIFs) is "all".
        assertNull(
            selectionOf(
                TimelineFilter(
                    types = setOf(FilterMediaType.PHOTOS, FilterMediaType.VIDEOS),
                ),
            ).selection,
        )
    }

    @Test
    fun `favorites projects the is_favorite literal`() {
        assertEquals(
            "is_favorite = 1",
            selectionOf(TimelineFilter(favoritesOnly = true)).selection,
        )
    }

    @Test
    fun `type and favorites compose in a fixed clause order`() {
        assertEquals(
            "media_type = 1 AND is_favorite = 1",
            selectionOf(
                TimelineFilter(
                    types = setOf(FilterMediaType.PHOTOS),
                    favoritesOnly = true,
                ),
            ).selection,
        )
    }

    @Test
    fun `the value dimensions bind their args, never interpolate them`() {
        val bucket = selectionOf(TimelineFilter(bucketId = -4711L))
        assertEquals("bucket_id = ?", bucket.selection)
        assertEquals(listOf("-4711"), bucket.selectionArgs)

        val range = selectionOf(
            TimelineFilter(
                dateRange = FilterDateRange(
                    startMillis = 1_700_000_000_000L,
                    endMillis = 1_700_008_640_000L,
                ),
            ),
        )
        assertEquals("date_added BETWEEN ? AND ?", range.selection)
        // Provider seconds: the millis bounds divide back to seconds so all
        // three projections agree exactly.
        assertEquals(listOf("1700000000", "1700008640"), range.selectionArgs)
    }

    @Test
    fun `every dimension together projects deterministically`() {
        val projection = selectionOf(
            TimelineFilter(
                types = setOf(FilterMediaType.VIDEOS, FilterMediaType.GIFS),
                favoritesOnly = true,
                bucketId = 7L,
                dateRange = FilterDateRange(
                    startMillis = 1_700_000_000_000L,
                    endMillis = 1_700_008_640_000L,
                ),
            ),
        )
        assertEquals(
            "(media_type = 3 OR mime_type LIKE 'image/gif') AND " +
                "is_favorite = 1 AND " +
                "bucket_id = ? AND " +
                "date_added BETWEEN ? AND ?",
            projection.selection,
        )
        assertEquals(
            listOf("7", "1700000000", "1700008640"),
            projection.selectionArgs,
        )
    }
}
