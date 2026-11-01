package io.github.dukeoftoad.gallery.ui.map

import io.github.dukeoftoad.gallery.data.geo.MapPoint

/**
 * One grid-bucket cluster of photo-location dots (r16): the members'
 * MEAN position (the cluster's visual anchor) plus the INDEXES of the
 * member items in the input list, so the tap sheet can list exactly the
 * photos that bucket holds.
 *
 * Pure data (no Android imports) --- produced only by
 * [MapClusterGrid.cluster], consumed by [WorldMapCanvas] and the
 * location sheet. JVM-tested per ADR-0016.
 */
data class MapCluster(
    /** Mean normalized x of the members (0..1); the dot's anchor. */
    val x: Double,
    /** Mean normalized y of the members (0..1); the dot's anchor. */
    val y: Double,
    /** Indexes into the input list, ascending. */
    val memberIndexes: List<Int>,
) {
    /** How many items the cluster represents; drives the bubble size. */
    val count: Int get() = memberIndexes.size
}

/**
 * The v1 map's dot clustering: GRID-BUCKET aggregation in normalized map
 * space (r16).
 *
 * WHY GRID BUCKETS (and not distance clustering): the whole-world
 * viewport means dot density is fixed per geography --- a 24x12 grid (one
 * cell per 15 degrees x 15 degrees) gives at most 288 draw targets, every
 * item lands in exactly ONE bucket by a closed-form cell computation
 * (no pairwise distance passes, no ordering dependence, no
 * iteration-count tuning), and the result is fully deterministic: same
 * input list, same clusters, same order. That determinism is what makes
 * the pure class JVM-testable and the tap targets stable frame to frame.
 *
 * Coordinates: [MapPoint] normalized space. The cell grid divides the
 * UNIT square into [columns] x [rows] cells; a point at the exact
 * right/bottom edge (x == 1.0 or y == 1.0) lands in the LAST cell
 * (coerced), because the projection can legitimately produce edge
 * coordinates for the antimeridian and the poles.
 *
 * Output order is deterministic: ascending cell index (row-major,
 * top-left first), each cluster's [MapCluster.memberIndexes] ascending.
 *
 * Hit-testing ([clusterAt]) operates in the SAME linear space the caller
 * supplies --- typically screen pixels, where a round finger radius is
 * actually round (normalized space is anisotropic: one unit of x is
 * twice one unit of y on the 2:1 map). The caller converts cluster
 * anchors and the tap into one space and passes one [radius] in it.
 *
 * Pure Kotlin, JVM-tested ([MapClusterGridTest]) per ADR-0016; the
 * Compose layer only converts units around it.
 */
object MapClusterGrid {

    /**
     * The v1 fixed grid: 24 x 12 --- one cell per 15 degrees of
     * longitude x 15 degrees of latitude, square cells on the 2:1
     * equirectangular map, at most 288 clusters. Frozen here (not a
     * parameter at the call site) so the v1 clustering contract has one
     * value to change when zoom lands.
     */
    const val COLUMNS: Int = 24
    const val ROWS: Int = 12

    /**
     * Buckets [points] into the [columns] x [rows] grid.
     *
     * @throws IllegalArgumentException if [columns] or [rows] is < 1.
     */
    fun cluster(
        points: List<MapPoint>,
        columns: Int = COLUMNS,
        rows: Int = ROWS,
    ): List<MapCluster> {
        require(columns >= 1 && rows >= 1) { "grid must be at least 1x1 (got ${columns}x$rows)" }
        if (points.isEmpty()) return emptyList()
        val cells = HashMap<Int, MutableList<Int>>(points.size)
        for (index in points.indices) {
            val point = points[index]
            val column = cellOf(point.x, columns)
            val row = cellOf(point.y, rows)
            val cell = row * columns + column
            cells.getOrPut(cell) { mutableListOf() }.add(index)
        }
        // Deterministic output: ascending cell index (row-major).
        return cells.keys.sorted().map { cell ->
            val members = cells.getValue(cell)
            MapCluster(
                x = members.sumOf { points[it].x } / members.size,
                y = members.sumOf { points[it].y } / members.size,
                memberIndexes = members,
            )
        }
    }

    /**
     * The nearest cluster to ([x], [y]) within [radius] (all in one
     * linear space --- see the class KDoc), or `null` when no cluster is
     * that close. Ties (two clusters at the same distance) resolve to the
     * FIRST in list order, so the answer stays deterministic.
     */
    fun clusterAt(
        clusters: List<MapCluster>,
        x: Double,
        y: Double,
        radius: Double,
    ): MapCluster? {
        if (clusters.isEmpty()) return null
        var best: MapCluster? = null
        var bestDistance = Double.MAX_VALUE
        for (cluster in clusters) {
            val dx = cluster.x - x
            val dy = cluster.y - y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = cluster
            }
        }
        val bestCluster = best ?: return null
        return if (bestDistance <= radius * radius) bestCluster else null
    }

    /**
     * The cell index along one axis: floor(v * cells) clamped to
     * 0..cells-1 (NaN and negatives coerce to 0, the exact edge 1.0 to
     * the last cell).
     */
    private fun cellOf(v: Double, cells: Int): Int {
        if (v.isNaN() || v <= 0.0) return 0
        if (v >= 1.0) return cells - 1
        val cell = (v * cells).toInt()
        // Defensive: toInt() truncates toward zero, so a positive v can
        // only over-step via floating error at the (already excluded)
        // upper edge; clamp anyway so the invariant is local.
        return cell.coerceIn(0, cells - 1)
    }

    /**
     * The drawn radius (in dp) of one cluster's dot: a single photo is a
     * small 6dp dot; a cluster grows with its count so dense buckets
     * read as bigger anchors and the count badge fits inside --- 9dp for
     * one-digit counts, 11dp for two digits, 13dp for the (rare, only at
     * whole-world zoom) three-digit buckets. Monotonic in [count] and
     * pinned by [MapClusterGridTest] so the visual scale cannot drift
     * silently. Counts below 1 are treated as 1 (defensive: the renderer
     * never sees them --- a cluster exists only with at least one
     * member).
     */
    fun dotRadiusDp(count: Int): Float = when {
        count <= 1 -> 6f
        count < 10 -> 9f
        count < 100 -> 11f
        else -> 13f
    }
}
