package io.github.dukeoftoad.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [DataSizeFormats] (issue #31). No emulator, no Robolectric
 * (ADR-0016): the formatter is pure Kotlin, so the trash UI's size strings
 * are pinned byte-for-byte.
 */
class DataSizeFormatsTest {

    @Test
    fun `non-positive input renders as zero bytes`() {
        assertEquals("0 B", DataSizeFormats.formatBytes(0))
        assertEquals("0 B", DataSizeFormats.formatBytes(-5))
    }

    @Test
    fun `bytes stay plain integers`() {
        assertEquals("1 B", DataSizeFormats.formatBytes(1))
        assertEquals("999 B", DataSizeFormats.formatBytes(999))
    }

    @Test
    fun `kilobytes get one decimal below 100`() {
        assertEquals("1.0 KB", DataSizeFormats.formatBytes(1000))
        assertEquals("4.5 KB", DataSizeFormats.formatBytes(4500))
        assertEquals("99.9 KB", DataSizeFormats.formatBytes(99_900))
    }

    @Test
    fun `hundreds of a unit drop the decimal`() {
        assertEquals("340 MB", DataSizeFormats.formatBytes(340_000_000))
        assertEquals("100 KB", DataSizeFormats.formatBytes(100_000))
    }

    @Test
    fun `gigabyte boundary is exact`() {
        assertEquals("1.0 GB", DataSizeFormats.formatBytes(1_000_000_000))
        assertEquals("2.2 GB", DataSizeFormats.formatBytes(2_200_000_000))
    }

    @Test
    fun `huge values clamp at terabytes`() {
        assertEquals("1.0 TB", DataSizeFormats.formatBytes(1_000_000_000_000))
        // Long.MAX_VALUE (~9.22e18) clamps into the last unit; at 100+ the
        // format drops the decimal: ~9.2 million TB.
        assertEquals("9223372 TB", DataSizeFormats.formatBytes(Long.MAX_VALUE))
    }

    @Test
    fun `decimal point is locale-stable`() {
        // 4.5 KB in a comma-decimal locale must not become "4,5 KB": the
        // formatter pins Locale.ROOT (the string is size-only, no locale text).
        val s = DataSizeFormats.formatBytes(4500)
        assertEquals(true, s.endsWith(".5 KB"))
    }
}
