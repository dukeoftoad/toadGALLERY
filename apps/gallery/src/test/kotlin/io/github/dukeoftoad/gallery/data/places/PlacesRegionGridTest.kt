package io.github.dukeoftoad.gallery.data.places

import io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueLocationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Places surface's region bucketing (r27; ADR-0016 ---
 * [PlacesRegionGrid] is pure Kotlin over plain rows, so every banding and
 * ordering rule is pinned here instead of living implicitly in the
 * surfaces).
 *
 * What is pinned:
 * - the closed-form band math: the poles land in the outermost bands, the
 *   antimeridian is ONE band (via the equirectangular normalization, so
 *   +180 and -180 bucket together), band edges land in their upper band;
 * - the deterministic output: first-appearance region order (newest-first
 *   recency), ascending member indexes, mean positions;
 * - the totals: empty input -> no regions, one region per cell, every
 *   member in exactly one region (the sum of member counts == input size).
 */
class PlacesRegionGridTest {

    /** One geotagged row at ([latitude], [longitude]); the identity fields vary by index. */
    private fun row(index: Int, latitude: Double, longitude: Double): CatalogueLocationRow =
        CatalogueLocationRow(
            localMediaKey = "external_primary:$index",
            canonicalUri = "content://media/external/images/media/$index",
            mimeType = "image/jpeg",
            dateAddedMillis = 1_000L * index,
            resolvedCaptureDateMillis = null,
            displayName = "IMG_$index.jpg",
            width = 100,
            height = 100,
            durationMs = 0,
            isFavorite = false,
            latitude = latitude,
            longitude = longitude,
        )

    // ------------------------------------------------------------------
    // The band math
    // ------------------------------------------------------------------

    @Test
    fun `latitude bands run north to south with poles in the outermost bands`() {
        // 90N (and beyond) -> band 0; 60N -> band 1's northern edge is 60:
        // (90-60)/30 = 1 exactly, so 60N belongs to band 1 (60..30N).
        assertEquals(0, PlacesRegionGrid.latitudeBand(90.0))
        assertEquals(0, PlacesRegionGrid.latitudeBand(75.0))
        assertEquals(1, PlacesRegionGrid.latitudeBand(59.9))
        assertEquals(1, PlacesRegionGrid.latitudeBand(30.0))
        assertEquals(2, PlacesRegionGrid.latitudeBand(29.9))
        assertEquals(2, PlacesRegionGrid.latitudeBand(0.0))
        assertEquals(3, PlacesRegionGrid.latitudeBand(-0.1))
        assertEquals(5, PlacesRegionGrid.latitudeBand(-90.0))
        // Defense in depth beyond the poles clamps to the outermost bands.
        assertEquals(0, PlacesRegionGrid.latitudeBand(95.0))
        assertEquals(5, PlacesRegionGrid.latitudeBand(-95.0))
    }

    @Test
    fun `longitude bands run west to east with the antimeridian in ONE band`() {
        assertEquals(0, PlacesRegionGrid.longitudeBand(-180.0))
        assertEquals(0, PlacesRegionGrid.longitudeBand(-179.9))
        assertEquals(5, PlacesRegionGrid.longitudeBand(-30.0))
        assertEquals(6, PlacesRegionGrid.longitudeBand(-29.9))
        assertEquals(6, PlacesRegionGrid.longitudeBand(0.0))
        assertEquals(11, PlacesRegionGrid.longitudeBand(179.9))
        // +180 normalizes to -180 (the equirectangular wrap), so the SAME
        // meridian lands in the SAME band from both directions.
        assertEquals(0, PlacesRegionGrid.longitudeBand(180.0))
        assertEquals(0, PlacesRegionGrid.longitudeBand(190.0))
        assertEquals(11, PlacesRegionGrid.longitudeBand(-190.0))
    }

    @Test
    fun `NaN coordinates coerce to the outermost bands - never a crash`() {
        // The same defensive posture the map's cell math takes: a NaN
        // coordinate (a corrupt EXIF row that slipped past the parser's
        // own gate) must answer a band, never poison the floor math.
        assertEquals(0, PlacesRegionGrid.latitudeBand(Double.NaN))
        assertEquals(0, PlacesRegionGrid.longitudeBand(Double.NaN))
    }

    @Test
    fun `the stable key is the r-row-c-column spelling of the band pair`() {
        assertEquals("r0c0", PlacesRegionGrid.regionKeyOf(0, 0))
        assertEquals("r1c1", PlacesRegionGrid.regionKeyOf(1, 1))
        assertEquals("r5c11", PlacesRegionGrid.regionKeyOf(5, 11))
    }

    // ------------------------------------------------------------------
    // The bucketing
    // ------------------------------------------------------------------

    @Test
    fun `empty input buckets to no regions`() {
        assertTrue(PlacesRegionGrid.bucket(emptyList()).isEmpty())
    }

    @Test
    fun `one row buckets to one region carrying it with the right identity`() {
        val regions = PlacesRegionGrid.bucket(
            listOf(row(0, latitude = 37.77, longitude = -122.42)), // San Francisco
        )
        assertEquals(1, regions.size)
        val region = regions.single()
        assertEquals("r1c1", region.regionKey) // lat 30..60N, lon 150..120W
        assertEquals(1, region.row)
        assertEquals(1, region.column)
        assertEquals(listOf(0), region.memberIndexes)
        assertEquals(1, region.count)
        assertEquals(37.77, region.meanLatitude, 1e-9)
        assertEquals(-122.42, region.meanLongitude, 1e-9)
    }

    @Test
    fun `same-cell rows share one region and keep ascending member indexes`() {
        val rows = listOf(
            row(0, latitude = 40.0, longitude = -100.0), // r1c2
            row(1, latitude = 35.0, longitude = -95.0), // r1c2
            row(2, latitude = 40.0, longitude = -100.0), // r1c2
        )
        val regions = PlacesRegionGrid.bucket(rows)
        assertEquals(1, regions.size)
        assertEquals(listOf(0, 1, 2), regions.single().memberIndexes)
        assertEquals(3, regions.single().count)
        // Mean position over the members.
        assertEquals((40.0 + 35.0 + 40.0) / 3, regions.single().meanLatitude, 1e-9)
    }

    @Test
    fun `regions come out in first-appearance order with newest-first input`() {
        // The catalogue serves newest-first: index 0 is the NEWEST photo.
        // Region A appears first (its newest photo is newest overall), so
        // it leads even though region B holds more photos.
        val rows = listOf(
            row(0, latitude = 40.0, longitude = -100.0), // A (r1c2)
            row(1, latitude = -33.0, longitude = 151.0), // B (r4c11, Sydney)
            row(2, latitude = -34.0, longitude = 150.0), // B
            row(3, latitude = -35.0, longitude = 152.0), // B
        )
        val regions = PlacesRegionGrid.bucket(rows)
        assertEquals(2, regions.size)
        assertEquals("r1c2", regions[0].regionKey)
        assertEquals(1, regions[0].count)
        assertEquals("r4c11", regions[1].regionKey)
        assertEquals(3, regions[1].count)
        // The cover rule: each region's FIRST member (its newest photo).
        assertEquals(0, regions[0].coverIndex)
        assertEquals(1, regions[1].coverIndex)
    }

    @Test
    fun `every row lands in exactly one region`() {
        val rows = listOf(
            row(0, latitude = 51.5, longitude = -0.12), // London r1c5
            row(1, latitude = 48.85, longitude = 2.35), // Paris r1c6
            row(2, latitude = -23.55, longitude = -46.63), // São Paulo r3c4
            row(3, latitude = 35.68, longitude = 139.69), // Tokyo r1c10
            row(4, latitude = 51.4, longitude = -0.1), // London again
        )
        val regions = PlacesRegionGrid.bucket(rows)
        // The partition property: total membership == input size, no
        // index repeated across regions.
        assertEquals(rows.size, regions.sumOf { it.count })
        val allIndexes = regions.flatMap { it.memberIndexes }
        assertEquals(allIndexes.size, allIndexes.distinct().size)
        assertEquals(4, regions.size) // London, Paris, São Paulo, Tokyo
    }

    @Test
    fun `antimeridian neighbors share one region and average near the meridian`() {
        // +179.9 and -179.9 are a few kilometers apart across the
        // antimeridian; they must bucket together and their mean must sit
        // near 180 (not near 0, which a naive signed average would give).
        val rows = listOf(
            row(0, latitude = 10.0, longitude = 179.9),
            row(1, latitude = 10.0, longitude = -179.9),
        )
        val regions = PlacesRegionGrid.bucket(rows)
        assertEquals(1, regions.size)
        assertEquals(2, regions.single().count)
        val mean = regions.single().meanLongitude
        assertTrue(
            "mean longitude near the antimeridian (got $mean)",
            mean > 179.0 || mean < -179.0,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero-column grid is rejected`() {
        PlacesRegionGrid.bucket(listOf(row(0, 0.0, 0.0)), columns = 0, rowsPerGrid = 6)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero-row grid is rejected`() {
        PlacesRegionGrid.bucket(listOf(row(0, 0.0, 0.0)), columns = 12, rowsPerGrid = 0)
    }
}
