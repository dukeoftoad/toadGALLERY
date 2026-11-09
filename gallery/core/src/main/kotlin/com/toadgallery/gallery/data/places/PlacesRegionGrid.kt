package com.toadgallery.gallery.data.places

import com.toadgallery.gallery.data.catalogue.room.CatalogueLocationRow
import com.toadgallery.gallery.data.geo.EquirectangularProjection

/**
 * One coarse geographic region bucket of the Places surface (r27, the
 * MI-style organisational UX round): the GRID CELL the bucket owns plus
 * the INDEXES of the member rows in the input list, so the detail grid
 * can list exactly the photos that bucket holds.
 *
 * Pure data (no Android imports) --- produced only by
 * [PlacesRegionGrid.bucket], consumed by the Places screens. JVM-tested
 * per ADR-0016.
 */
data class PlaceRegion(
    /**
     * The stable bucket identity: `"r<row>c<column>"` (both 0-based; row 0
     * is the NORTHERNMOST band). This is the string the navigation route
     * and [PlaceMediaHandoff] carry, so a region stays addressable across
     * recompositions and process death.
     */
    val regionKey: String,
    /** The latitude band index, 0 (60..90N) .. 5 (90..60S). */
    val row: Int,
    /** The longitude band index, 0 (180W..150W) .. 11 (150E..180E). */
    val column: Int,
    /** Indexes into the input list, ascending. */
    val memberIndexes: List<Int>,
    /** Mean latitude of the members, signed decimal degrees. */
    val meanLatitude: Double,
    /** Mean longitude of the members (already normalized), decimal degrees. */
    val meanLongitude: Double,
) {
    /** How many photos the region holds; drives the count label. */
    val count: Int get() = memberIndexes.size

    /**
     * The region's cover: the FIRST member, which in the newest-first
     * catalogue order the input always uses is the region's NEWEST photo
     * (the same cover rule the Albums tab's folder cards use).
     */
    val coverIndex: Int get() = memberIndexes.first()
}

/**
 * The Places surface's coarse offline region bucketing (r27): GRID-BUCKET
 * aggregation over the Room catalogue's persisted GPS coordinates, the
 * sibling of the r16 map's [com.toadgallery.gallery.ui.map
 * .MapClusterGrid] at one coarser grain.
 *
 * WHY A COARSER GRID THAN THE MAP: the map clusters dots for a
 * whole-world CANVAS (24x12 cells, one per 15 degrees, so at most 288
 * draw targets); Places is a LIST surface, and a list of up to 288
 * near-duplicate "regions" is noise, not organisation. 12x6 cells (one
 * per 30 degrees x 30 degrees, at most 72 buckets) is the coarse grain a
 * phone library actually needs --- a trip, a city, a country-sized area
 * --- while staying the same closed-form, deterministic,
 * every-item-in-exactly-one-cell discipline the map pinned (no pairwise
 * distance passes, no ordering dependence, no iteration tuning).
 *
 * ZERO REVERSE GEOCODING (the roadmap's explicit Places rule): the
 * buckets are labeled by their COORDINATE BANDS ([PlacesRegionLabels]),
 * derived offline from the same numbers the grid buckets by. No place
 * name is ever invented, fetched or guessed --- a bucket that spans
 * Lisbon and Casablanca honestly says "30°N–60°N · 30°W–0°", full stop.
 *
 * Coordinates: inputs run through the SAME normalization the r16
 * equirectangular projection applies ([EquirectangularProjection
 * .normalizeLongitude] wraps the antimeridian so +180 and -180 are ONE
 * meridian in ONE band; latitude is clamped at the poles as defense in
 * depth --- the EXIF parser already rejects out-of-range values). The
 * band math itself is closed-form:
 *  - latitude band `= floor((90 - lat) / 30)` clamped to 0..5;
 *  - longitude band `= floor((lon + 180) / 30)` clamped to 0..11.
 *
 * Output order is deterministic: regions in ORDER OF FIRST APPEARANCE in
 * the input list (the catalogue serves newest-first, so the most recently
 * photographed region leads --- the MI-Gallery-style "where you have
 * been, most recent first"), each region's [PlaceRegion.memberIndexes]
 * ascending.
 *
 * The eligibility boundary is inherited, not re-decided: the input rows
 * come from the catalogue's LIVE-rows location projection
 * ([com.toadgallery.gallery.data.catalogue.room
 * .RoomMediaCatalogue.geotaggedRows]) --- the same single supply the r16
 * map reads. Vault media never enter the catalogue (the scan pipeline's
 * MediaEligibility gate), trashed rows leave it (MediaStore hides them
 * from the scan's queries, and the reconciliation pass tombstones the
 * rows it loses), so no Places surface can list vault or trash content.
 * The r14 consent display gate is applied by the SURFACE
 * ([PlacesContentPolicy]), exactly as the map applies it.
 *
 * Pure Kotlin, JVM-tested ([PlacesRegionGridTest]) per ADR-0016.
 */
object PlacesRegionGrid {

    /** The v1 Places grid: 12 columns --- one per 30 degrees of longitude. */
    const val COLUMNS: Int = 12

    /** The v1 Places grid: 6 rows --- one per 30 degrees of latitude. */
    const val ROWS: Int = 6

    /** Degrees per latitude band (180 / [ROWS]). */
    const val LATITUDE_BAND_DEGREES: Double = 180.0 / ROWS

    /** Degrees per longitude band (360 / [COLUMNS]). */
    const val LONGITUDE_BAND_DEGREES: Double = 360.0 / COLUMNS

    /**
     * Buckets [rows] (the catalogue's newest-first geotagged projection)
     * into coarse regions.
     *
     * @throws IllegalArgumentException if [columns] or [rowsPerGrid] is < 1.
     */
    fun bucket(
        rows: List<CatalogueLocationRow>,
        columns: Int = COLUMNS,
        rowsPerGrid: Int = ROWS,
    ): List<PlaceRegion> {
        require(columns >= 1 && rowsPerGrid >= 1) {
            "grid must be at least 1x1 (got ${columns}x$rowsPerGrid)"
        }
        if (rows.isEmpty()) return emptyList()
        val cells = LinkedHashMap<Int, MutableList<Int>>(rows.size)
        for (index in rows.indices) {
            val row = rows[index]
            val bandRow = latitudeBand(row.latitude, rowsPerGrid)
            val bandColumn = longitudeBand(row.longitude, columns)
            val cell = bandRow * columns + bandColumn
            cells.getOrPut(cell) { mutableListOf() }.add(index)
        }
        // LinkedHashMap preserves insertion order == first-appearance order
        // == newest-first recency (see the class KDoc). Each bucket's band
        // pair is re-derived from its first member (every member of a cell
        // computes the same pair by construction).
        return cells.values.map { members ->
            val bandRow = latitudeBand(rows[members.first()].latitude, rowsPerGrid)
            val bandColumn = longitudeBand(rows[members.first()].longitude, columns)
            PlaceRegion(
                regionKey = regionKeyOf(bandRow, bandColumn),
                row = bandRow,
                column = bandColumn,
                memberIndexes = members,
                meanLatitude = members.sumOf { rows[it].latitude } / members.size,
                meanLongitude = members.sumOf {
                    // Mean over the NORMALIZED longitude so antimeridian
                    // neighbors (+179.9 / -179.9) average near 180, not 0.
                    EquirectangularProjection.normalizeLongitude(rows[it].longitude)
                } / members.size,
            )
        }
    }

    /** The stable `"r<row>c<column>"` key of one band pair. */
    fun regionKeyOf(row: Int, column: Int): String = "r${row}c${column}"

    /**
     * The latitude band of [latitude]: `floor((90 - lat) / 30)` clamped
     * into 0..rows-1 (the poles' exact edges land in the outermost bands;
     * NaN coerces to the north band, the same defensive posture the map's
     * cell math takes).
     */
    fun latitudeBand(latitude: Double, rowsPerGrid: Int = ROWS): Int {
        if (latitude.isNaN() || latitude >= 90.0) return 0
        if (latitude <= -90.0) return rowsPerGrid - 1
        // CEIL-1 so an exact band edge lands in its UPPER band (the one
        // closer to the pole --- pinned by PlacesRegionGridTest): a plain
        // floor drops 30.0N into the 0..30N band it only touches.
        val band = (kotlin.math.ceil((90.0 - latitude) / (180.0 / rowsPerGrid)).toInt() - 1)
        return band.coerceIn(0, rowsPerGrid - 1)
    }

    /**
     * The longitude band of [longitude]: the input is normalized through
     * [EquirectangularProjection.normalizeLongitude] first (so +180 and
     * -180 denote the SAME meridian and band), then
     * `floor((lon + 180) / 30)` clamped into 0..columns-1.
     */
    fun longitudeBand(longitude: Double, columns: Int = COLUMNS): Int {
        val lon = EquirectangularProjection.normalizeLongitude(longitude)
        if (lon.isNaN() || lon <= -180.0) return 0
        // +180 IS -180 after the wrap (normalizeLongitude keeps in-range
        // values as-is, so the canonical banding happens HERE): the exact
        // antimeridian lands in ONE band from both directions.
        if (lon == 180.0) return 0
        if (lon > 180.0) return columns - 1
        val band = ((lon + 180.0) / (360.0 / columns)).toInt()
        return band.coerceIn(0, columns - 1)
    }
}
