package com.toadgallery.gallery.ui.map

import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the map screen's content-state mapping (r16 round;
 * ADR-0016 --- [MapContentPolicy] is pure Kotlin over plain values, so the
 * "which state does the user see" rules are pinned here instead of living
 * implicitly in the ViewModel's control flow).
 *
 * What is pinned:
 * - the display gate decides FIRST: consent off shows the consent state
 *   even when persisted coordinates exist (drawing dots from coordinates
 *   the user's current consent choice withholds would be the exact
 *   "secretly kept back" display the r14 gate exists to prevent);
 * - displayable + no rows -> the honest empty state, carrying the pending
 *   re-index count for its footnote;
 * - displayable + rows -> Ready with the SAME rows, in the SAME order the
 *   DAO's timeline-ordering projection produced them (the policy never
 *   re-sorts).
 */
class MapContentPolicyTest {

    private fun row(key: String = "external_primary:1"): CatalogueLocationRow =
        CatalogueLocationRow(
            localMediaKey = key,
            canonicalUri = "content://media/external/images/media/${key.substringAfterLast(':')}",
            mimeType = "image/jpeg",
            dateAddedMillis = 500L,
            resolvedCaptureDateMillis = 1_700_000_000_000L,
            displayName = "IMG_001.jpg",
            width = 100,
            height = 100,
            durationMs = 0,
            isFavorite = false,
            latitude = 37.7749,
            longitude = -122.4194,
        )

    @Test
    fun `the display gate decides first - consent off wins even over persisted rows`() {
        val state = MapContentPolicy.decide(
            gpsDisplayable = false,
            rows = listOf(row()),
            pendingUngrantedRows = 3,
        )
        assertEquals(MapContentState.ConsentOff, state)
    }

    @Test
    fun `displayable with no rows is the honest empty state with the pending count`() {
        val state = MapContentPolicy.decide(
            gpsDisplayable = true,
            rows = emptyList(),
            pendingUngrantedRows = 7,
        )
        assertEquals(MapContentState.Empty(pendingUngrantedRows = 7), state)
        // Zero pending is the plain first-scan story, not a re-index one.
        assertEquals(
            MapContentState.Empty(pendingUngrantedRows = 0),
            MapContentPolicy.decide(true, emptyList(), 0),
        )
    }

    @Test
    fun `displayable with rows is ready - rows carried verbatim, never re-sorted`() {
        val newestFirst = listOf(row("external_primary:9"), row("external_primary:2"))
        val state = MapContentPolicy.decide(
            gpsDisplayable = true,
            rows = newestFirst,
            pendingUngrantedRows = 0,
        )
        assertEquals(MapContentState.Ready(newestFirst, 0), state)
        // The same object identity order: the policy is a mapping, not a
        // sorter (the DAO's ORDER BY is the timeline contract).
        assertTrue((state as MapContentState.Ready).rows === newestFirst)
        // A non-empty map can still carry a pending footnote for OTHER,
        // still-withheld items.
        assertEquals(
            MapContentState.Ready(newestFirst, 12),
            MapContentPolicy.decide(true, newestFirst, 12),
        )
    }
}
