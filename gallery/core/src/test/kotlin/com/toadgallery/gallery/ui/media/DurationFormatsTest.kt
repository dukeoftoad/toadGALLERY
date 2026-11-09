package com.toadgallery.gallery.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure duration formatter (issue #33).
 *
 * The helper depends on no `MediaItem`/`Uri` machinery by design, so these
 * pin the exact overlay strings without an emulator or Robolectric.
 */
class DurationFormatsTest {

    @Test
    fun `zero duration renders as unknown`() {
        assertEquals("0:00", formatDurationMs(0))
    }

    @Test
    fun `negative duration renders as unknown`() {
        assertEquals("0:00", formatDurationMs(-1))
    }

    @Test
    fun `sub-minute duration renders m and ss`() {
        assertEquals("0:59", formatDurationMs(59_000))
    }

    @Test
    fun `exactly one minute renders 1 and padded seconds`() {
        assertEquals("1:00", formatDurationMs(60_000))
    }

    @Test
    fun `minute and change renders 1 and 24`() {
        assertEquals("1:24", formatDurationMs(84_000))
    }

    @Test
    fun `exactly one hour renders h mm and ss`() {
        assertEquals("1:00:00", formatDurationMs(3_600_000))
    }

    @Test
    fun `over one hour renders 1 02 and 03`() {
        assertEquals("1:02:03", formatDurationMs(3_723_000))
    }

    @Test
    fun `fractional seconds truncate rather than round`() {
        assertEquals("0:01", formatDurationMs(1_500))
    }
}
