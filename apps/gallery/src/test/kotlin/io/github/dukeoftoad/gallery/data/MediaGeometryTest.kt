package io.github.dukeoftoad.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure aspect-ratio helper (issues #34 and #33).
 *
 * [displayAspectRatioOf] is deliberately free of any `android.net.Uri`
 * dependency --- the whole point of keeping the geometry top-level --- so
 * these run as plain JVM tests in the style of the privacy suite: no
 * emulator, no Robolectric.
 */
class MediaGeometryTest {

    /** Float compare with a 1e-6 tolerance; exact float division is not the point. */
    private fun assertRatio(expected: Float, width: Int, height: Int) {
        assertEquals(expected, displayAspectRatioOf(width, height), 1e-6f)
    }

    @Test
    fun `4x3 landscape photo yields 4 over 3`() {
        assertRatio(4f / 3f, 4000, 3000)
    }

    @Test
    fun `16x9 landscape frame yields 16 over 9`() {
        assertRatio(16f / 9f, 1920, 1080)
    }

    @Test
    fun `9x16 portrait photo yields 9 over 16`() {
        assertRatio(9f / 16f, 1080, 1920)
    }

    @Test
    fun `extreme panorama clamps to the lower bound`() {
        assertRatio(0.5f, 100, 1000)
    }

    @Test
    fun `extreme sliver clamps to the upper bound`() {
        assertRatio(2f, 1000, 100)
    }

    @Test
    fun `zero dimensions mean unknown and fall back to square`() {
        assertRatio(1f, 0, 0)
    }

    @Test
    fun `negative dimensions mean unknown and fall back to square`() {
        assertRatio(1f, -640, 480)
    }
}
