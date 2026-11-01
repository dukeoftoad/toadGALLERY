package io.github.dukeoftoad.gallery.data.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the one-shot Albums -> Photos filter hand-off (r17):
 * latest-wins requests, single consumption, and the nothing-pending case.
 *
 * The slot is process-wide, so every test leaves it EMPTY behind itself
 * (`consume()` in a cleanup pass) --- the suite stays order-independent
 * that way.
 */
class PhotosFilterHandoffTest {

    @org.junit.After
    fun clearSlot() {
        // One-shot semantics make this a no-op after a consumed test; a
        // failed assertion before a consume would otherwise leak the slot
        // into the next test.
        PhotosFilterHandoff.consume()
    }

    @Test
    fun `consuming with nothing pending yields null`() {
        assertNull(PhotosFilterHandoff.consume())
    }

    @Test
    fun `consumption is one-shot`() {
        val requested = TimelineFilter(types = setOf(FilterMediaType.GIFS))
        PhotosFilterHandoff.request(requested)
        assertEquals(requested, PhotosFilterHandoff.consume())
        // The second consume must NOT replay the request: re-entering the
        // Photos tab without a new tap applies nothing.
        assertNull(PhotosFilterHandoff.consume())
    }

    @Test
    fun `the latest request wins`() {
        val first = TimelineFilter(types = setOf(FilterMediaType.PHOTOS))
        val second = TimelineFilter(favoritesOnly = true)
        PhotosFilterHandoff.request(first)
        PhotosFilterHandoff.request(second)
        assertEquals(second, PhotosFilterHandoff.consume())
        assertNull(PhotosFilterHandoff.consume())
    }
}
