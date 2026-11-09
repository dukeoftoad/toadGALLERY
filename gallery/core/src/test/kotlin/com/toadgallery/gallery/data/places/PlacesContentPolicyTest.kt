package com.toadgallery.gallery.data.places

import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the Places surface's content-state mapping (r27, the
 * MI-style organisational UX round; ADR-0016 --- [PlacesContentPolicy] is
 * pure Kotlin over plain rows, the r16 `MapContentPolicy`'s exact
 * decision order, so the "which state does the user see" rules are pinned
 * here instead of living implicitly in the ViewModels' control flow).
 *
 * What is pinned:
 * - the display gate decides FIRST: consent off shows the consent state
 *   even when persisted coordinates exist (a region list built from
 *   coordinates the user's current consent choice withholds would be the
 *   exact "secretly kept back" display the r14 gate exists to prevent);
 * - displayable + no rows -> the honest empty state, carrying the pending
 *   re-index count for its footnote;
 * - displayable + rows -> Ready with the SAME rows, in the SAME order the
 *   catalogue's projection produced them (the policy never re-sorts).
 */
class PlacesContentPolicyTest {

    private fun row(key: String, latitude: Double, longitude: Double): CatalogueLocationRow =
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
            latitude = latitude,
            longitude = longitude,
        )

    @Test
    fun `the display gate decides first - consent off wins even over persisted rows`() {
        val state = PlacesContentPolicy.decide(
            gpsDisplayable = false,
            rows = listOf(row("external_primary:1", 37.77, -122.42)),
            pendingUngrantedRows = 3,
        )
        assertEquals(PlacesContentState.ConsentOff, state)
    }

    @Test
    fun `displayable with no rows is the honest empty state with the pending count`() {
        val state = PlacesContentPolicy.decide(
            gpsDisplayable = true,
            rows = emptyList(),
            pendingUngrantedRows = 7,
        )
        assertEquals(PlacesContentState.Empty(pendingUngrantedRows = 7), state)
    }

    @Test
    fun `displayable with rows is Ready carrying the pending count`() {
        val rows = listOf(
            row("external_primary:1", 51.50, -0.12),
            row("external_primary:2", 48.85, 2.35),
        )
        val state = PlacesContentPolicy.decide(
            gpsDisplayable = true,
            rows = rows,
            pendingUngrantedRows = 2,
        )
        assertEquals(PlacesContentState.Ready(rows = rows, pendingUngrantedRows = 2), state)
    }

    @Test
    fun `Ready keeps the projection's own order - the policy never re-sorts`() {
        // Newest-first with a deliberate non-sorted coordinate order: the
        // Ready state must carry the list VERBATIM (the region bucketing
        // downstream derives its own deterministic order; the policy's
        // job is to decide, not to order).
        val rows = listOf(
            row("external_primary:9", -33.87, 151.21),
            row("external_primary:4", 51.50, -0.12),
            row("external_primary:7", 35.68, 139.69),
        )
        val ready = PlacesContentPolicy.decide(
            gpsDisplayable = true,
            rows = rows,
            pendingUngrantedRows = 0,
        ) as PlacesContentState.Ready
        assertEquals(rows, ready.rows)
    }
}
