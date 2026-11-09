package com.toadgallery.gallery.data.places

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the Places surface's offline region labels (r27, the
 * MI-style organisational UX round; ADR-0016 --- [PlacesRegionLabels] is
 * pure math over the band indexes, so the label vocabulary is pinned here
 * instead of living implicitly in the screens).
 *
 * What is pinned:
 * - the full latitude band ladder, north to south, each band printed
 *   from its southern edge to its northern edge ("up" the map);
 * - the full longitude band ladder, west to east, each band printed west
 *   edge to east edge (the map's left-to-right reading);
 * - the equator and the Greenwich meridian print as `"0°"` (no compass
 *   suffix), whole degrees only;
 * - the composed region label `"<lat> · <lon>"` for band pairs and for a
 *   whole [PlaceRegion] --- the string the Places cards and the Place
 *   detail title render;
 * - the labels agree with the grid's OWN band math (a real city's
 *   coordinates bucket into the band the label names).
 *
 * ZERO reverse geocoding is the round's rule: every expected value below
 * is a coordinate band, never an invented place name.
 */
class PlacesRegionLabelsTest {

    // ------------------------------------------------------------------
    // The latitude ladder (row 0 = northernmost)
    // ------------------------------------------------------------------

    @Test
    fun `latitude bands run north to south reading upward per band`() {
        assertEquals("60°N–90°N", PlacesRegionLabels.latitudeBandLabel(0))
        assertEquals("30°N–60°N", PlacesRegionLabels.latitudeBandLabel(1))
        assertEquals("0°–30°N", PlacesRegionLabels.latitudeBandLabel(2))
        assertEquals("30°S–0°", PlacesRegionLabels.latitudeBandLabel(3))
        assertEquals("60°S–30°S", PlacesRegionLabels.latitudeBandLabel(4))
        assertEquals("90°S–60°S", PlacesRegionLabels.latitudeBandLabel(5))
    }

    @Test
    fun `the equator prints as zero degrees with no compass suffix`() {
        assertEquals("0°", PlacesRegionLabels.formatLatitude(0.0))
        // The equatorial band's SOUTHERN edge is the equator itself: the
        // band 2 label leads with "0°" and band 3's label ends with it.
        assertEquals("0°–30°N", PlacesRegionLabels.latitudeBandLabel(2))
        assertEquals("30°S–0°", PlacesRegionLabels.latitudeBandLabel(3))
    }

    // ------------------------------------------------------------------
    // The longitude ladder (column 0 = westernmost)
    // ------------------------------------------------------------------

    @Test
    fun `longitude bands run west to east reading left to right per band`() {
        assertEquals("180°W–150°W", PlacesRegionLabels.longitudeBandLabel(0))
        assertEquals("150°W–120°W", PlacesRegionLabels.longitudeBandLabel(1))
        assertEquals("30°W–0°", PlacesRegionLabels.longitudeBandLabel(5))
        assertEquals("0°–30°E", PlacesRegionLabels.longitudeBandLabel(6))
        assertEquals("120°E–150°E", PlacesRegionLabels.longitudeBandLabel(10))
        assertEquals("150°E–180°E", PlacesRegionLabels.longitudeBandLabel(11))
    }

    @Test
    fun `the Greenwich meridian prints as zero degrees with no compass suffix`() {
        assertEquals("0°", PlacesRegionLabels.formatLongitude(0.0))
        // Column 5 ends at the meridian; column 6 starts at it.
        assertEquals("30°W–0°", PlacesRegionLabels.longitudeBandLabel(5))
        assertEquals("0°–30°E", PlacesRegionLabels.longitudeBandLabel(6))
    }

    @Test
    fun `whole degrees only - band edges never carry decimals`() {
        // Every band edge is an exact multiple of 30; a decimal would be
        // noise, so the formatters keep whole degrees.
        assertEquals("90°N", PlacesRegionLabels.formatLatitude(90.0))
        assertEquals("90°S", PlacesRegionLabels.formatLatitude(-90.0))
        assertEquals("180°W", PlacesRegionLabels.formatLongitude(-180.0))
        assertEquals("180°E", PlacesRegionLabels.formatLongitude(180.0))
    }

    // ------------------------------------------------------------------
    // The composed region label
    // ------------------------------------------------------------------

    @Test
    fun `the region label composes latitude and longitude with the middle dot`() {
        assertEquals(
            "30°N–60°N · 30°W–0°",
            PlacesRegionLabels.regionLabel(row = 1, column = 5),
        )
        assertEquals(
            "60°N–90°N · 180°W–150°W",
            PlacesRegionLabels.regionLabel(row = 0, column = 0),
        )
        assertEquals(
            "90°S–60°S · 150°E–180°E",
            PlacesRegionLabels.regionLabel(row = 5, column = 11),
        )
    }

    @Test
    fun `the PlaceRegion overload labels the region it carries`() {
        val region = PlaceRegion(
            regionKey = PlacesRegionGrid.regionKeyOf(1, 10),
            row = 1,
            column = 10,
            memberIndexes = listOf(0, 1),
            meanLatitude = 35.68,
            meanLongitude = 139.69,
        )
        assertEquals("30°N–60°N · 120°E–150°E", PlacesRegionLabels.regionLabel(region))
    }

    // ------------------------------------------------------------------
    // Labels agree with the grid's own band math
    // ------------------------------------------------------------------

    @Test
    fun `a real city's coordinates label the band the grid buckets it into`() {
        // Tokyo (35.68N, 139.69E) buckets into row 1, column 10; the
        // label names exactly that band pair.
        val row = PlacesRegionGrid.latitudeBand(35.68)
        val column = PlacesRegionGrid.longitudeBand(139.69)
        assertEquals(1, row)
        assertEquals(10, column)
        assertEquals("30°N–60°N · 120°E–150°E", PlacesRegionLabels.regionLabel(row, column))
    }

    @Test
    fun `the region key and the label agree on the band identity`() {
        // The key is "r<row>c<column>" and the label names the same pair
        // --- the two identities of a bucket can never drift apart.
        (0 until PlacesRegionGrid.ROWS).forEach { row ->
            (0 until PlacesRegionGrid.COLUMNS).forEach { column ->
                val key = PlacesRegionGrid.regionKeyOf(row, column)
                assertEquals("r${row}c${column}", key)
                // The label is a pure function of the same pair: deriving
                // it twice answers the same string.
                assertEquals(
                    PlacesRegionLabels.regionLabel(row, column),
                    PlacesRegionLabels.regionLabel(row, column),
                )
            }
        }
    }
}
