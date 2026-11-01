package io.github.dukeoftoad.gallery.metadata.video

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the pure [VideoMetadataParse] helpers (Phase 4). The
 * `MediaMetadataRetriever` wrapper itself is Android-only and untested by
 * design (ADR-0016) --- these tests pin the string formats the framework
 * hands back, without any Android runtime.
 */
class VideoMetadataSourceTest {

    @Test
    fun `rotation parses axis values and degrades everything else to zero`() {
        assertEquals(0, VideoMetadataParse.rotationDegrees("0"))
        assertEquals(90, VideoMetadataParse.rotationDegrees("90"))
        assertEquals(180, VideoMetadataParse.rotationDegrees("180"))
        assertEquals(270, VideoMetadataParse.rotationDegrees("270"))
        // Some encoders write off-axis values; the only safe display
        // assumption is no rotation.
        assertEquals(0, VideoMetadataParse.rotationDegrees("45"))
        assertEquals(0, VideoMetadataParse.rotationDegrees("sideways"))
        assertEquals(0, VideoMetadataParse.rotationDegrees(null))
        assertEquals(0, VideoMetadataParse.rotationDegrees(" 90 "))
    }

    @Test
    fun `duration parses millis and degrades garbage to unknown`() {
        assertEquals(12_345L, VideoMetadataParse.durationMillis("12345"))
        assertEquals(0L, VideoMetadataParse.durationMillis("-1"))
        assertEquals(0L, VideoMetadataParse.durationMillis("forever"))
        assertEquals(0L, VideoMetadataParse.durationMillis(null))
        assertEquals(0L, VideoMetadataParse.durationMillis(""))
    }

    @Test
    fun `capture timestamp parses the z-suffixed mmr form`() {
        val expected = LocalDateTime.of(2024, 5, 17, 9, 30, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(
            java.lang.Long.valueOf(expected),
            VideoMetadataParse.captureTimestampMillis("20240517T093000.000Z"),
        )
        // Without the fractional part, same instant.
        assertEquals(
            java.lang.Long.valueOf(expected),
            VideoMetadataParse.captureTimestampMillis("20240517T093000Z"),
        )
    }

    @Test
    fun `capture timestamp honors numeric zone offsets`() {
        val utcMillis = LocalDateTime.of(2024, 5, 17, 9, 30, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        // 09:30 at +09:00 is 00:30 UTC --- nine hours earlier.
        assertEquals(
            java.lang.Long.valueOf(utcMillis - 9 * 3600_000L),
            VideoMetadataParse.captureTimestampMillis("20240517T093000.000+09:00"),
        )
        assertEquals(
            java.lang.Long.valueOf(utcMillis - 9 * 3600_000L),
            VideoMetadataParse.captureTimestampMillis("20240517T093000.000+0900"),
        )
        // Negative offset: 09:30 at -05:30 is 15:00 UTC.
        assertEquals(
            java.lang.Long.valueOf(utcMillis + (5 * 3600 + 30 * 60) * 1000L),
            VideoMetadataParse.captureTimestampMillis("20240517T093000.000-05:30"),
        )
    }

    @Test
    fun `capture timestamp without a zone anchors at utc`() {
        val expected = LocalDateTime.of(2024, 5, 17, 9, 30, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(
            java.lang.Long.valueOf(expected),
            VideoMetadataParse.captureTimestampMillis("20240517T093000.000"),
        )
    }

    @Test
    fun `capture timestamp rejects malformed values`() {
        assertNull(VideoMetadataParse.captureTimestampMillis(null))
        assertNull(VideoMetadataParse.captureTimestampMillis(""))
        assertNull(VideoMetadataParse.captureTimestampMillis("   "))
        // Impossible date under the strict resolver.
        assertNull(VideoMetadataParse.captureTimestampMillis("20241301T000000.000Z"))
        assertNull(VideoMetadataParse.captureTimestampMillis("20240230T000000.000Z"))
        // Wrong shape entirely.
        assertNull(VideoMetadataParse.captureTimestampMillis("2024-05-17T09:30:00Z"))
        assertNull(VideoMetadataParse.captureTimestampMillis("yesterday"))
        // Nonsense offsets.
        assertNull(VideoMetadataParse.captureTimestampMillis("20240517T093000.000+99:00"))
        assertNull(VideoMetadataParse.captureTimestampMillis("20240517T093000.000+09:99"))
    }
}
