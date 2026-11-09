package com.toadgallery.gallery.data.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the in-memory projection of the r17 smart-filter engine
 * ([TimelineFilter.matches]): every dimension, the GIFs-are-photos subset
 * rule, the toggle helpers, and the fail-closed edges.
 *
 * Plain `testDebugUnitTest` per ADR-0016: no `android.*` anywhere --- the
 * predicate takes the android-free [TimelineFilterFields] tuple exactly so
 * this suite can pin it (the `SearchQuery`/`TimelineSort.sortItems`
 * convention for android-bearing types).
 */
class TimelineFilterTest {

    private fun fields(
        mime: String = "image/jpeg",
        isVideo: Boolean = false,
        isFavorite: Boolean = false,
        dateAddedSec: Long = 1_000L,
    ): TimelineFilterFields = TimelineFilterFields(
        mimeType = mime,
        isVideo = isVideo,
        isFavorite = isFavorite,
        dateAddedSec = dateAddedSec,
    )

    // ------------------------------------------------------------------
    // The default filter is the unfiltered timeline
    // ------------------------------------------------------------------

    @Test
    fun `a fresh filter matches every item`() {
        val filter = TimelineFilter()
        assertTrue(filter.matches(fields()))
        assertTrue(filter.matches(fields(isVideo = true, mime = "video/mp4")))
        assertTrue(filter.matches(fields(mime = "image/gif")))
        assertTrue(filter.matches(fields(isFavorite = true)))
        assertFalse(filter.isActive)
    }

    // ------------------------------------------------------------------
    // Media type (with the GIFs-are-photos subset rule)
    // ------------------------------------------------------------------

    @Test
    fun `photos only excludes videos and includes gifs`() {
        val filter = TimelineFilter(types = setOf(FilterMediaType.PHOTOS))
        assertTrue(filter.matches(fields()))
        assertTrue(filter.matches(fields(mime = "image/gif")))
        assertFalse(filter.matches(fields(isVideo = true, mime = "video/mp4")))
    }

    @Test
    fun `videos only keeps videos`() {
        val filter = TimelineFilter(types = setOf(FilterMediaType.VIDEOS))
        assertTrue(filter.matches(fields(isVideo = true, mime = "video/mp4")))
        assertFalse(filter.matches(fields()))
        assertFalse(filter.matches(fields(mime = "image/gif")))
    }

    @Test
    fun `gifs only matches the gif mime case-insensitively, and the videos-gifs union keeps both`() {
        val gifsOnly = TimelineFilter(types = setOf(FilterMediaType.GIFS))
        assertTrue(gifsOnly.matches(fields(mime = "image/gif")))
        assertTrue(gifsOnly.matches(fields(mime = "IMAGE/GIF")))
        assertFalse(gifsOnly.matches(fields(mime = "image/png")))
        assertFalse(gifsOnly.matches(fields(isVideo = true, mime = "video/mp4")))

        val union = TimelineFilter(
            types = setOf(FilterMediaType.VIDEOS, FilterMediaType.GIFS),
        )
        assertTrue(union.matches(fields(isVideo = true, mime = "video/mp4")))
        assertTrue(union.matches(fields(mime = "image/gif")))
        assertFalse(union.matches(fields(mime = "image/png")))
    }

    @Test
    fun `photos plus videos collapses to no restriction`() {
        // GIFs are a subset of photos, so Photos+Videos+GIFs is still "all".
        val filter = TimelineFilter(
            types = setOf(
                FilterMediaType.PHOTOS,
                FilterMediaType.VIDEOS,
                FilterMediaType.GIFS,
            ),
        )
        assertTrue(filter.matches(fields()))
        assertTrue(filter.matches(fields(isVideo = true, mime = "video/mp4")))
        assertTrue(filter.matches(fields(mime = "image/gif")))
    }

    // ------------------------------------------------------------------
    // Favorites (composes as AND with the type set)
    // ------------------------------------------------------------------

    @Test
    fun `favorites only composes as AND with the type dimension`() {
        val filter = TimelineFilter(
            types = setOf(FilterMediaType.PHOTOS),
            favoritesOnly = true,
        )
        assertTrue(filter.matches(fields(isFavorite = true)))
        assertFalse(filter.matches(fields(isFavorite = false)))
        assertFalse(
            filter.matches(fields(isVideo = true, mime = "video/mp4", isFavorite = true)),
        )
    }

    // ------------------------------------------------------------------
    // Toggle helpers (what the chips call)
    // ------------------------------------------------------------------

    @Test
    fun `withTypeToggled adds, removes and empties back to all`() {
        val base = TimelineFilter()
        val withPhotos = base.withTypeToggled(FilterMediaType.PHOTOS)
        assertTrue(FilterMediaType.PHOTOS in withPhotos.types)
        assertTrue(withPhotos.isActive)

        val withVideos = withPhotos.withTypeToggled(FilterMediaType.VIDEOS)
        assertTrue(FilterMediaType.VIDEOS in withVideos.types)
        assertTrue(FilterMediaType.PHOTOS in withVideos.types)

        val cleared = withVideos
            .withTypeToggled(FilterMediaType.PHOTOS)
            .withTypeToggled(FilterMediaType.VIDEOS)
        assertTrue(cleared.types.isEmpty())
        assertFalse(cleared.isActive)
    }

    @Test
    fun `withFavoritesToggled flips only the favorites dimension`() {
        val base = TimelineFilter(types = setOf(FilterMediaType.GIFS))
        val toggled = base.withFavoritesToggled()
        assertTrue(toggled.favoritesOnly)
        assertEquals(setOf(FilterMediaType.GIFS), toggled.types)
        assertFalse(base.favoritesOnly)
        assertTrue(toggled.withFavoritesToggled().favoritesOnly.not())
    }

    // ------------------------------------------------------------------
    // Date range (second granularity, inclusive, total)
    // ------------------------------------------------------------------

    @Test
    fun `date range bounds are inclusive at second granularity and an inverted range matches nothing`() {
        // [60.000s, 60.999s] in epoch millis truncates to the single second 60.
        val filter = TimelineFilter(
            dateRange = FilterDateRange(
                startMillis = 60_000L,
                endMillis = 60_999L,
            ),
        )
        assertFalse(filter.matches(fields(dateAddedSec = 59L)))
        assertTrue(filter.matches(fields(dateAddedSec = 60L)))
        assertFalse(filter.matches(fields(dateAddedSec = 61L)))

        // A start beyond the end simply matches nothing --- total, no crash.
        val inverted = TimelineFilter(
            dateRange = FilterDateRange(startMillis = 2_000L, endMillis = 1_000L),
        )
        assertFalse(inverted.matches(fields(dateAddedSec = 1_500L)))
        assertFalse(inverted.matches(fields(dateAddedSec = 1L)))
        assertFalse(inverted.matches(fields(dateAddedSec = 10_000L)))
    }

    // ------------------------------------------------------------------
    // The bucket dimension: implemented in SQL, fail-closed in memory
    // ------------------------------------------------------------------

    @Test
    fun `a bucket filter fails closed in memory`() {
        // MediaItem carries no bucket, so the in-memory tuple cannot
        // evaluate one; the predicate must be visibly empty (never a
        // silent pass-through). The SQL projections serve the dimension;
        // the r17 UI never surfaces it (no catalogue column).
        val filter = TimelineFilter(bucketId = 123L)
        assertTrue(filter.isActive)
        assertFalse(filter.matches(fields()))
        assertFalse(filter.matches(fields(isVideo = true, isFavorite = true)))
    }

    // ------------------------------------------------------------------
    // isActive: the All chip / Clear action's predicate
    // ------------------------------------------------------------------

    @Test
    fun `isActive reflects every dimension`() {
        assertFalse(TimelineFilter().isActive)
        assertTrue(TimelineFilter(types = setOf(FilterMediaType.PHOTOS)).isActive)
        assertTrue(TimelineFilter(favoritesOnly = true).isActive)
        assertTrue(TimelineFilter(bucketId = 1L).isActive)
        assertTrue(
            TimelineFilter(
                dateRange = FilterDateRange(startMillis = 0L, endMillis = 1L),
            ).isActive,
        )
    }
}
