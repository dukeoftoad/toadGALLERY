package com.toadgallery.gallery.data.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM tests for the map's projection + fixed viewport math (r16 round;
 * ADR-0016 --- pure Kotlin in, pure Kotlin out, no Android types). The
 * pins cover the two documented normalization decisions (latitude CLAMPS,
 * longitude WRAPS), the north->south y convention, the inverse round-trip
 * guarantee and both letterbox orientations of the whole-world viewport.
 */
class EquirectangularTest {

    // --- the forward projection ----------------------------------------------

    @Test
    fun `the four corners map onto the unit square`() {
        // West-north -> (0,0): x runs west->east, y runs NORTH->SOUTH.
        assertEquals(MapPoint(0.0, 0.0), EquirectangularProjection.project(-180.0, 90.0))
        assertEquals(MapPoint(1.0, 0.0), EquirectangularProjection.project(180.0, 90.0))
        assertEquals(MapPoint(0.0, 1.0), EquirectangularProjection.project(-180.0, -90.0))
        assertEquals(MapPoint(1.0, 1.0), EquirectangularProjection.project(180.0, -90.0))
    }

    @Test
    fun `the prime meridian and equator sit at the center`() {
        assertEquals(MapPoint(0.5, 0.5), EquirectangularProjection.project(0.0, 0.0))
        // The whole-globe aspect is 2:1 (the letterbox contract).
        assertEquals(2.0, EquirectangularProjection.ASPECT_RATIO, 0.0)
    }

    @Test
    fun `longitude wraps and in-range longitudes pass through unchanged`() {
        // 190 degrees is the same meridian as -170: wrapping puts the dot
        // at the correct map position (clamping would pile it onto the
        // antimeridian edge).
        assertEquals(
            EquirectangularProjection.project(-170.0, 10.0),
            EquirectangularProjection.project(190.0, 10.0),
        )
        assertEquals(
            EquirectangularProjection.project(170.0, 10.0),
            EquirectangularProjection.project(-190.0, 10.0),
        )
        // A full turn wraps to itself.
        assertEquals(
            EquirectangularProjection.project(20.0, 0.0),
            EquirectangularProjection.project(380.0, 0.0),
        )
        // In-range values --- including both edges --- round-trip through
        // normalize byte-for-byte.
        listOf(-180.0, -179.5, 0.0, 45.25, 179.5, 180.0).forEach { lon ->
            assertEquals(lon, EquirectangularProjection.normalizeLongitude(lon), 0.0)
        }
        // The +180 spelling normalizes to the canonical -180 edge.
        assertEquals(-180.0, EquirectangularProjection.normalizeLongitude(540.0), 0.0)
    }

    @Test
    fun `latitude clamps at the poles`() {
        assertEquals(
            EquirectangularProjection.project(0.0, 90.0),
            EquirectangularProjection.project(0.0, 95.0),
        )
        assertEquals(
            EquirectangularProjection.project(0.0, -90.0),
            EquirectangularProjection.project(0.0, -95.0),
        )
        assertEquals(-90.0, EquirectangularProjection.clampLatitude(-123.0), 0.0)
    }

    // --- the inverse projection ------------------------------------------------

    @Test
    fun `unproject inverts project to double precision`() {
        val samples = listOf(
            GeoPosition(-180.0, 90.0),
            GeoPosition(180.0, -90.0),
            GeoPosition(0.0, 0.0),
            GeoPosition(-122.4194, 37.7749), // San Francisco
            GeoPosition(139.6917, 35.6895), // Tokyo
            GeoPosition(-0.1276, 51.5072), // London
        )
        for (position in samples) {
            val roundTrip = EquirectangularProjection.unproject(
                EquirectangularProjection.project(position),
            )
            assertEquals(position.longitude, roundTrip.longitude, 1e-9)
            assertEquals(position.latitude, roundTrip.latitude, 1e-9)
        }
    }

    @Test
    fun `unproject coerces out-of-square inputs to the nearest edge`() {
        assertEquals(
            GeoPosition(-180.0, 90.0),
            EquirectangularProjection.unproject(MapPoint(-0.5, -0.5)),
        )
        assertEquals(
            // x = 1 maps to the +180 edge, which the passthrough keeps as
            // +180 (the same meridian as -180; only out-of-range INPUTS
            // wrap, in-range edges pass through unchanged).
            GeoPosition(180.0, -90.0),
            EquirectangularProjection.unproject(MapPoint(1.5, 1.5)),
        )
    }

    @Test
    fun `isInBounds accepts only finite unit-square points`() {
        assertTrue(EquirectangularProjection.isInBounds(MapPoint(0.0, 1.0)))
        assertTrue(EquirectangularProjection.isInBounds(MapPoint(0.5, 0.5)))
        assertFalse(EquirectangularProjection.isInBounds(MapPoint(-0.01, 0.5)))
        assertFalse(EquirectangularProjection.isInBounds(MapPoint(0.5, 1.01)))
        assertFalse(EquirectangularProjection.isInBounds(MapPoint(Double.NaN, 0.5)))
    }

    // --- the fixed whole-world viewport -----------------------------------------

    @Test
    fun `a portrait canvas fills the width and letterboxes vertically`() {
        // 1080x2340 phone canvas: taller than 2:1, so the map is a
        // full-width band around the vertical center.
        val vp = EquirectangularViewport(widthPx = 1080.0, heightPx = 2340.0)
        assertEquals(0.0, vp.left, 0.0)
        assertEquals(1080.0, vp.mapWidth, 0.0)
        assertEquals(540.0, vp.mapHeight, 0.0) // 1080 / 2
        assertEquals((2340.0 - 540.0) / 2.0, vp.top, 0.0)
        // Corners of the unit square land inside the map rect.
        assertEquals(
            ScreenPoint(0.0, vp.top),
            vp.toScreen(MapPoint(0.0, 0.0)),
        )
        assertEquals(
            ScreenPoint(1080.0, vp.top + 540.0),
            vp.toScreen(MapPoint(1.0, 1.0)),
        )
    }

    @Test
    fun `a wide canvas fills the height and letterboxes horizontally`() {
        val vp = EquirectangularViewport(widthPx = 1920.0, heightPx = 800.0)
        assertEquals(0.0, vp.top, 0.0)
        assertEquals(800.0, vp.mapHeight, 0.0)
        assertEquals(1600.0, vp.mapWidth, 0.0) // 800 * 2
        assertEquals((1920.0 - 1600.0) / 2.0, vp.left, 0.0)
    }

    @Test
    fun `fromScreen maps pixels back and rejects the letterbox bands`() {
        val vp = EquirectangularViewport(widthPx = 1000.0, heightPx = 500.0) // exactly 2:1: no bands
        assertEquals(0.0, vp.left, 0.0)
        assertEquals(0.0, vp.top, 0.0)
        // Round-trip a center tap.
        val center = vp.toScreen(MapPoint(0.5, 0.5))
        assertEquals(MapPoint(0.5, 0.5), vp.fromScreen(center.x, center.y))
        assertTrue(vp.containsPixel(center.x, center.y))
        // A banded viewport rejects taps in the letterbox: 1000x600 has
        // 50px top/bottom bands around a 500px-high map.
        val banded = EquirectangularViewport(widthPx = 1000.0, heightPx = 600.0)
        assertEquals(50.0, banded.top, 0.0)
        assertNull(banded.fromScreen(500.0, 10.0)) // above the map
        assertNull(banded.fromScreen(500.0, 590.0)) // below the map
        assertNull(banded.fromScreen(-1.0, 300.0)) // left of the map
        assertEquals(MapPoint(0.5, 0.5), banded.fromScreen(500.0, 300.0))
        assertFalse(banded.containsPixel(500.0, 10.0))
        // Degenerate canvases are refused up front, never divided by zero.
        try {
            EquirectangularViewport(0.0, 100.0)
            fail("expected IllegalArgumentException for a zero-width canvas")
        } catch (expected: IllegalArgumentException) {
            // The only honest answer to "no canvas yet".
        }
    }
}
