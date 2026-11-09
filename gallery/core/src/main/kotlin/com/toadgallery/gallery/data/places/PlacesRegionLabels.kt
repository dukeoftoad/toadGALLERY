package com.toadgallery.gallery.data.places

/**
 * The offline region labels of the Places surface (r27): every bucket of
 * [PlacesRegionGrid] is labeled by its COORDINATE BAND --- pure math over
 * the band indexes, zero lookups, zero network, zero invented place
 * names.
 *
 * WHY BAND LABELS (and not "Berlin", "Goa", …): reverse geocoding is the
 * one thing the roadmap's Places rule forbids ("No reverse geocoding
 * network call; labels use coarse on-device region buckets derived from
 * coordinates"), and the device holds no offline gazetteer. A coordinate
 * band is the honest label the data can support: it says exactly where
 * the bucket is, it is deterministic, it is localizable by format rather
 * than by translation, and it never pretends to know a city the photo
 * merely happens to be near. The degree sign and the compass letters are
 * the international cartographic convention (ISO 6709-style), shared by
 * every locale the app ships.
 *
 * Shape of one label: `"<lat band> · <lon band>"`, e.g.
 * `"30°N–60°N · 30°W–0°"`. Each band runs from its LOW edge to its HIGH
 * edge reading west-to-east / south-to-north on the map (the longitude
 * band 150W..180W prints as "150°W–180°W"; the band -30..0 as "30°W–0°";
 * the equator itself prints as "0°"). The separator is a middle dot with
 * spaces, the same visual idiom the timeline headers use for their
 * count suffix.
 *
 * Pure Kotlin, JVM-tested ([PlacesRegionLabelsTest]) per ADR-0016; the
 * screens call these functions with the band indexes a [PlaceRegion]
 * already carries.
 */
object PlacesRegionLabels {

    /** The label separator between the latitude and longitude bands. */
    const val SEPARATOR: String = " · "

    /** The band-range separator inside one band label. */
    const val RANGE_SEPARATOR: String = "–"

    /**
     * The full region label of one band pair: `"<lat> · <lon>"`.
     */
    fun regionLabel(row: Int, column: Int): String =
        latitudeBandLabel(row) + SEPARATOR + longitudeBandLabel(column)

    /** The full region label of one [PlaceRegion]. */
    fun regionLabel(region: PlaceRegion): String =
        regionLabel(region.row, region.column)

    /**
     * The latitude band label of grid row [row] (0 = northernmost):
     * `"60°N–90°N"` for row 0 … `"30°S–60°S"` for row 4, `"60°S–90°S"`
     * for row 5 --- each band printed from its southern edge to its
     * northern edge so the range always reads "up" the map.
     */
    fun latitudeBandLabel(row: Int): String {
        val high = 90.0 - row * PlacesRegionGrid.LATITUDE_BAND_DEGREES
        val low = high - PlacesRegionGrid.LATITUDE_BAND_DEGREES
        return formatLatitude(low) + RANGE_SEPARATOR + formatLatitude(high)
    }

    /**
     * The longitude band label of grid column [column] (0 = westernmost):
     * `"180°W–150°W"` for column 0 … `"30°W–0°"` and `"0°–30°E"` around
     * Greenwich … `"150°E–180°E"` for column 11 --- each band printed
     * west edge to east edge, the map's left-to-right reading.
     */
    fun longitudeBandLabel(column: Int): String {
        val west = -180.0 + column * PlacesRegionGrid.LONGITUDE_BAND_DEGREES
        val east = west + PlacesRegionGrid.LONGITUDE_BAND_DEGREES
        return formatLongitude(west) + RANGE_SEPARATOR + formatLongitude(east)
    }

    /**
     * One latitude as `"0°"` / `"<n>°N"` / `"<n>°S"`; whole degrees only
     * (the band edges are exact multiples of 30, and a decimal would be
     * noise).
     */
    internal fun formatLatitude(degrees: Double): String {
        val whole = kotlin.math.abs(degrees).toInt()
        val suffix = when {
            degrees > 0.0 -> "N"
            degrees < 0.0 -> "S"
            else -> ""
        }
        return "${whole}°$suffix"
    }

    /**
     * One longitude as `"0°"` / `"<n>°E"` / `"<n>°W"`.
     * [formatLatitude]'s whole-degree rule, east/west spelled.
     */
    internal fun formatLongitude(degrees: Double): String {
        val whole = kotlin.math.abs(degrees).toInt()
        val suffix = when {
            degrees > 0.0 -> "E"
            degrees < 0.0 -> "W"
            else -> ""
        }
        return "${whole}°$suffix"
    }
}
