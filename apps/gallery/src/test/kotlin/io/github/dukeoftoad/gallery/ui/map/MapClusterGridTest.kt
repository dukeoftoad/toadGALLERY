package io.github.dukeoftoad.gallery.ui.map

import io.github.dukeoftoad.gallery.data.geo.MapPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for the map's grid-bucket clustering (r16 round; ADR-0016 ---
 * [MapClusterGrid] is pure Kotlin, so the whole contract --- deterministic
 * bucketing, edge coercion, hit-testing, the dot-radius scale --- is pinned
 * here without the Android runtime).
 */
class MapClusterGridTest {

    // --- bucketing -------------------------------------------------------------

    @Test
    fun `empty input clusters to nothing`() {
        assertTrue(MapClusterGrid.cluster(emptyList()).isEmpty())
    }

    @Test
    fun `points in one cell merge into one cluster anchored at their mean`() {
        val clusters = MapClusterGrid.cluster(
            listOf(MapPoint(0.02, 0.02), MapPoint(0.03, 0.03)),
        )

        assertEquals(1, clusters.size)
        val cluster = clusters.single()
        assertEquals(2, cluster.count)
        assertEquals(listOf(0, 1), cluster.memberIndexes)
        assertEquals(0.025, cluster.x, 1e-12)
        assertEquals(0.025, cluster.y, 1e-12)
    }

    @Test
    fun `output order is deterministic row-major and members ascend`() {
        // 2x2 grid: first point lands in the top-right cell (index 1),
        // second in the bottom-left (index 2) --- row-major order puts the
        // TOP row first regardless of input order.
        val clusters = MapClusterGrid.cluster(
            listOf(MapPoint(0.75, 0.25), MapPoint(0.25, 0.75)),
            columns = 2,
            rows = 2,
        )

        assertEquals(2, clusters.size)
        assertEquals(listOf(0), clusters[0].memberIndexes)
        assertEquals(listOf(1), clusters[1].memberIndexes)
        // Same input, same output: the clustering is a pure function.
        assertEquals(clusters, MapClusterGrid.cluster(
            listOf(MapPoint(0.75, 0.25), MapPoint(0.25, 0.75)),
            columns = 2,
            rows = 2,
        ))
    }

    @Test
    fun `edge coordinates land in the last cell and degenerate input in the first`() {
        // The projection can legitimately produce the exact 1.0 edges
        // (antimeridian, poles): they belong to the LAST cell.
        val clusters = MapClusterGrid.cluster(
            listOf(MapPoint(1.0, 1.0), MapPoint(0.0, 0.0), MapPoint(-1.0, Double.NaN)),
            columns = 2,
            rows = 2,
        )
        // (1,1) -> cell 3 (last), (0,0) -> cell 0, and the degenerate
        // negative/NaN point coerces to cell 0 too.
        assertEquals(2, clusters.size)
        val cellZero = clusters[0]
        val lastCell = clusters[1]
        assertEquals(listOf(1, 2), cellZero.memberIndexes)
        assertEquals(listOf(0), lastCell.memberIndexes)
    }

    @Test
    fun `a grid below 1x1 is refused`() {
        try {
            MapClusterGrid.cluster(listOf(MapPoint(0.5, 0.5)), columns = 0, rows = 2)
            fail("expected IllegalArgumentException for a 0-column grid")
        } catch (expected: IllegalArgumentException) {
            // Guarded up front; no bucketing happens on a broken grid.
        }
    }

    // --- hit-testing -------------------------------------------------------------

    @Test
    fun `clusterAt finds the nearest cluster within the radius or nothing`() {
        val clusters = listOf(
            MapCluster(0.2, 0.2, listOf(0)),
            MapCluster(0.8, 0.8, listOf(1)),
        )
        // A tap near the first anchor, inside the radius.
        assertEquals(clusters[0], MapClusterGrid.clusterAt(clusters, 0.25, 0.2, radius = 0.1))
        // The second anchor is nearer this time.
        assertEquals(clusters[1], MapClusterGrid.clusterAt(clusters, 0.75, 0.8, radius = 0.1))
        // Equidistant tap: the FIRST cluster in list order wins (stable).
        assertEquals(clusters[0], MapClusterGrid.clusterAt(clusters, 0.5, 0.5, radius = 1.0))
        // Nothing within the finger radius.
        assertNull(MapClusterGrid.clusterAt(clusters, 0.5, 0.5, radius = 0.1))
        // No clusters at all.
        assertNull(MapClusterGrid.clusterAt(emptyList(), 0.5, 0.5, radius = 1.0))
    }

    // --- the dot-radius scale -----------------------------------------------------

    @Test
    fun `dot radii grow monotonically with the count`() {
        assertEquals(6f, MapClusterGrid.dotRadiusDp(1), 0f)
        assertEquals(6f, MapClusterGrid.dotRadiusDp(0), 0f) // defensive: never below single
        assertEquals(9f, MapClusterGrid.dotRadiusDp(2), 0f)
        assertEquals(9f, MapClusterGrid.dotRadiusDp(9), 0f)
        assertEquals(11f, MapClusterGrid.dotRadiusDp(10), 0f)
        assertEquals(11f, MapClusterGrid.dotRadiusDp(99), 0f)
        assertEquals(13f, MapClusterGrid.dotRadiusDp(100), 0f)
        // Monotonic across the whole range a whole-world map can produce.
        var previous = 0f
        for (count in 1..300) {
            val radius = MapClusterGrid.dotRadiusDp(count)
            assertTrue("radius shrank at count=$count", radius >= previous)
            previous = radius
        }
    }
}
