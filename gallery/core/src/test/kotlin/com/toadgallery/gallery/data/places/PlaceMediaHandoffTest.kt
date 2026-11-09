package com.toadgallery.gallery.data.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the one-shot Places tap hand-off (r27, the MI-style
 * organisational UX round): latest-wins requests, single consumption, and
 * the nothing-pending case --- the [PlaceMediaHandoff] twin of the pinned
 * `PhotosFilterHandoffTest` contract (the r17 pattern this slot applies
 * verbatim).
 *
 * The slot is process-wide, so every test leaves it EMPTY behind itself
 * (`consume()` in a cleanup pass) --- the suite stays order-independent
 * that way. Unlike `PhotosFilterHandoff`, whose payload embeds
 * `android.net.Uri` and therefore carries no JVM test of its own, this
 * slot's payload is a plain [String][PlaceMediaHandoff.PlaceMediaRequest
 * .regionKey], so the full one-shot contract is pinnable on the JVM.
 */
class PlaceMediaHandoffTest {

    @org.junit.After
    fun clearSlot() {
        // One-shot semantics make this a no-op after a consumed test; a
        // failed assertion before a consume would otherwise leak the slot
        // into the next test.
        PlaceMediaHandoff.consume()
    }

    @Test
    fun `consuming with nothing pending yields null`() {
        assertNull(PlaceMediaHandoff.consume())
    }

    @Test
    fun `consumption is one-shot`() {
        PlaceMediaHandoff.request("r1c10")
        assertEquals(
            PlaceMediaHandoff.PlaceMediaRequest("r1c10"),
            PlaceMediaHandoff.consume(),
        )
        // The second consume must NOT replay the request: re-entering the
        // detail route without a new tap falls back to the nav argument.
        assertNull(PlaceMediaHandoff.consume())
    }

    @Test
    fun `the latest request wins`() {
        PlaceMediaHandoff.request("r1c2")
        PlaceMediaHandoff.request("r4c11")
        assertEquals(
            PlaceMediaHandoff.PlaceMediaRequest("r4c11"),
            PlaceMediaHandoff.consume(),
        )
        assertNull(PlaceMediaHandoff.consume())
    }

    @Test
    fun `the carried key is the region grid's own stable identity`() {
        // The keys the surfaces stash are exactly the pure grid's
        // regionKeyOf output, so the hand-off and the route argument name
        // the same deterministic bucket.
        val key = PlacesRegionGrid.regionKeyOf(2, 6)
        PlaceMediaHandoff.request(key)
        assertEquals(PlaceMediaHandoff.PlaceMediaRequest(key), PlaceMediaHandoff.consume())
    }
}
