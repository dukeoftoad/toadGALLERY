package com.toadgallery.gallery.ui.photos

import com.toadgallery.gallery.data.TimelineSort
import com.toadgallery.gallery.data.catalogue.TimelineDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Photos timeline's pure r13 parts (ADR-0016): the
 * backend-choice helper ([timelineSource]), the sort-to-catalogue direction
 * mapping ([toCatalogueDirection]), and the android-free half of the
 * `CatalogueRecord -> MediaItem` mapping ([catalogueTimelineItem]).
 *
 * No emulator, no Robolectric, no `android.*` --- `Uri.parse` stays at the
 * thin seam inside `PhotosViewModel` (see [CatalogueTimelineItem]'s KDoc),
 * which is exactly why the mapping core takes plain strings/longs and is
 * pinned here.
 */
class PhotosTimelineSourceTest {

    // ------------------------------------------------------------------
    // timelineSource: which backend serves a generation
    // ------------------------------------------------------------------

    @Test
    fun `catalogue drives the timeline once it holds a live record`() {
        assertEquals(TimelineSource.CATALOGUE, timelineSource(cataloguePopulated = true))
    }

    @Test
    fun `an empty catalogue keeps the MediaStore fallback`() {
        // Empty = "the first scan has not populated yet" (first cold start),
        // never a permanent state on a granted library --- the first
        // changes() emission flips the generation onto the catalogue.
        assertEquals(TimelineSource.MEDIA_STORE_FALLBACK, timelineSource(cataloguePopulated = false))
    }

    // ------------------------------------------------------------------
    // timelineSource: the r17 filter-aware ladder
    // ------------------------------------------------------------------

    @Test
    fun `a filter the catalogue cannot serve keeps the MediaStore fallback`() {
        // E.g. a bucket dimension (no catalogue column) or a non-Room
        // catalogue binding: the fallback serves every engine dimension,
        // so the generation stays honest instead of silently unfiltered.
        assertEquals(
            TimelineSource.MEDIA_STORE_FALLBACK,
            timelineSource(
                cataloguePopulated = true,
                catalogueServesFilter = false,
            ),
        )
        // An unpopulated catalogue always keeps the fallback regardless.
        assertEquals(
            TimelineSource.MEDIA_STORE_FALLBACK,
            timelineSource(
                cataloguePopulated = false,
                catalogueServesFilter = false,
            ),
        )
    }

    @Test
    fun `a serveable filter keeps the catalogue backend on a populated catalogue`() {
        // The default parameter preserves the pre-r17 decision exactly;
        // an explicit true (an inactive filter, or an active one the
        // binding can serve) is the same choice.
        assertEquals(
            TimelineSource.CATALOGUE,
            timelineSource(cataloguePopulated = true),
        )
        assertEquals(
            TimelineSource.CATALOGUE,
            timelineSource(
                cataloguePopulated = true,
                catalogueServesFilter = true,
            ),
        )
    }

    // ------------------------------------------------------------------
    // toCatalogueDirection: the sort menu -> catalogue window direction
    // ------------------------------------------------------------------

    @Test
    fun `sort directions map one-to-one onto catalogue directions`() {
        assertEquals(TimelineDirection.NEWEST_FIRST, TimelineSort.NEWEST_FIRST.toCatalogueDirection())
        assertEquals(TimelineDirection.OLDEST_FIRST, TimelineSort.OLDEST_FIRST.toCatalogueDirection())
    }

    // ------------------------------------------------------------------
    // catalogueTimelineItem: record fields -> timeline fields
    // ------------------------------------------------------------------

    @Test
    fun `mapper parses the mediaStoreId out of the local media key`() {
        val item = catalogueTimelineItem(
            localMediaKey = "external_primary:12345",
            canonicalUri = "content://media/external/images/media/12345",
            mimeType = "image/jpeg",
            dateAddedMillis = 1_772_000_000_000,
            resolvedCaptureDateMillis = null,
            width = 4000,
            height = 3000,
            durationMs = 0,
            isFavorite = false,
        )
        assertEquals(12345L, item.id)
    }

    @Test
    fun `mapper reads the id from a paged key, not the page suffix`() {
        // `volume:id:page` keys embed the id in the MIDDLE; a bare
        // suffix parse would read the page id instead.
        val item = catalogueTimelineItem(
            localMediaKey = "external_primary:42:page1",
            canonicalUri = "content://media/external/images/media/42",
            mimeType = "image/tiff",
            dateAddedMillis = 0,
            resolvedCaptureDateMillis = null,
            width = 0,
            height = 0,
            durationMs = 0,
            isFavorite = false,
        )
        assertEquals(42L, item.id)
    }

    @Test
    fun `mapper degrades a malformed key to id zero instead of crashing`() {
        // Belt-and-braces: real keys are written by MediaIdentity, but the
        // timeline window must never throw on a bad row.
        val item = catalogueTimelineItem(
            localMediaKey = "not-a-key",
            canonicalUri = "content://media/external/images/media/1",
            mimeType = "image/jpeg",
            dateAddedMillis = 0,
            resolvedCaptureDateMillis = null,
            width = 0,
            height = 0,
            durationMs = 0,
            isFavorite = false,
        )
        assertEquals(0L, item.id)
    }

    @Test
    fun `mapper keeps the canonical uri string as the access handle`() {
        val uri = "content://media/external/video/media/77"
        val item = catalogueTimelineItem(
            localMediaKey = "external_primary:77",
            canonicalUri = uri,
            mimeType = "video/mp4",
            dateAddedMillis = 0,
            resolvedCaptureDateMillis = null,
            width = 0,
            height = 0,
            durationMs = 0,
            isFavorite = false,
        )
        assertEquals(uri, item.uriString)
    }

    @Test
    fun `mapper derives isVideo from the mime type`() {
        fun mimeOf(mime: String) = catalogueTimelineItem(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/media/1",
            mimeType = mime,
            dateAddedMillis = 0,
            resolvedCaptureDateMillis = null,
            width = 0,
            height = 0,
            durationMs = 0,
            isFavorite = false,
        ).isVideo

        assertTrue(mimeOf("video/mp4"))
        assertTrue(mimeOf("video/x-msvideo"))
        assertFalse(mimeOf("image/jpeg"))
        assertFalse(mimeOf("image/gif"))
        assertFalse(mimeOf("application/octet-stream"))
    }

    @Test
    fun `mapper converts dateAddedMillis to seconds truncating toward zero`() {
        fun secOf(millis: Long) = catalogueTimelineItem(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/media/1",
            mimeType = "image/jpeg",
            dateAddedMillis = millis,
            resolvedCaptureDateMillis = null,
            width = 0,
            height = 0,
            durationMs = 0,
            isFavorite = false,
        ).dateAddedSec

        assertEquals(1_772_000_000L, secOf(1_772_000_000_999))
        assertEquals(1_772_000_000L, secOf(1_772_000_000_000))
        // Kotlin and SQLite integer division both truncate toward zero, so
        // a (theoretical) negative dateAdded round-trips consistently.
        assertEquals(-1L, secOf(-1_500))
    }

    @Test
    fun `mapper passes the resolved capture date through verbatim`() {
        fun captureOf(resolved: Long?) = catalogueTimelineItem(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/media/1",
            mimeType = "image/jpeg",
            dateAddedMillis = 1_772_000_000_000,
            resolvedCaptureDateMillis = resolved,
            width = 0,
            height = 0,
            durationMs = 0,
            isFavorite = false,
        ).captureDateMillis

        assertNull("unresolved stays null (falls back to dateAdded downstream)", captureOf(null))
        assertEquals("a resolved epoch capture is 0, not null", 0L, captureOf(0))
        // Pre-1970 capture instants are legitimate and pass through verbatim.
        assertEquals(-1_163_318_400_000L, captureOf(-1_163_318_400_000L))
    }

    @Test
    fun `mapper carries the grid metadata straight through`() {
        val item = catalogueTimelineItem(
            localMediaKey = "external_primary:9",
            canonicalUri = "content://media/external/video/media/9",
            mimeType = "video/mp4",
            dateAddedMillis = 0,
            resolvedCaptureDateMillis = null,
            width = 1920,
            height = 1080,
            durationMs = 62_500,
            isFavorite = true,
        )
        assertEquals(1920, item.width)
        assertEquals(1080, item.height)
        assertEquals(62_500L, item.durationMs)
        assertTrue(item.isFavorite)
    }
}
