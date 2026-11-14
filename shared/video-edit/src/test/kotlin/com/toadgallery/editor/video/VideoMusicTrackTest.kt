package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Music track model: window rules, ducking envelope shape, duck-span derivation. */
class VideoMusicTrackTest {

    private fun timeline(vararg sounding: Boolean): ClipTimeline {
        val entries = sounding.mapIndexed { index, hasAudio ->
            ClipTimelineEntry(
                sourceUri = "clip://$index",
                durationMs = 10_000,
                audioTrackPresent = hasAudio,
                trim = TimeRange(0, 10_000),
                speedFactor = 1.0,
            )
        }
        return ClipTimeline(entries)
    }

    private fun track(startMs: Long = 0, endMs: Long = 20_000, gain: Float = 1f, duck: Float = 0.3f) =
        VideoMusicTrack(
            sourceUri = "music://x",
            durationMs = 30_000,
            startMs = startMs,
            endMs = endMs,
            gain = gain,
            duckFactor = duck,
        )

    // ---- the validator ----

    @Test
    fun `a window past the composition is refused`() {
        val result = VideoMusicValidator.validate(track(endMs = 21_000), timeline(true))
        assertTrue(result is VideoMusicValidation.Invalid)
        assertTrue(
            (result as VideoMusicValidation.Invalid).reason.contains("past the timeline"),
        )
    }

    @Test
    fun `a window longer than the asset is refused`() {
        // 3-clip timeline (30s) so the 25s window fits the timeline; the failure names the asset fact.
        val long = track(endMs = 25_000).copy(durationMs = 20_000)
        val result = VideoMusicValidator.validate(long, timeline(true, true, true))
        assertTrue(result is VideoMusicValidation.Invalid)
        assertTrue(
            (result as VideoMusicValidation.Invalid).reason.contains("longer than the asset"),
        )
    }

    @Test
    fun `a fitting window over a validated composition is valid`() {
        val result = VideoMusicValidator.validate(track(), timeline(true, true))
        assertTrue(result is VideoMusicValidation.Valid)
    }

    @Test
    fun `a null track is valid - music is optional`() {
        val result = VideoMusicValidator.validate(null, timeline(true))
        assertTrue(result is VideoMusicValidation.Valid)
    }

    // ---- the duck-span derivation ----

    @Test
    fun `duck spans follow the sounding clips only`() {
        // clip 0 sounds, clip 1 silent, clip 2 sounds.
        val spans = VideoMusicDucking.duckSpansOf(timeline(true, false, true))
        assertEquals(2, spans.size)
        assertEquals(0L..10_000L, spans[0].startMs..spans[0].endMs)
        assertEquals(20_000L..30_000L, spans[1].startMs..spans[1].endMs)
    }

    @Test
    fun `duck spans account for per-clip speed`() {
        val entry = ClipTimelineEntry(
            sourceUri = "clip://fast",
            durationMs = 10_000,
            audioTrackPresent = true,
            trim = TimeRange(0, 10_000),
            speedFactor = 2.0, // 5s output
        )
        val spans = VideoMusicDucking.duckSpansOf(ClipTimeline(listOf(entry)))
        assertEquals(1, spans.size)
        assertEquals(0L, spans[0].startMs)
        assertEquals(5_000L, spans[0].endMs)
    }

    // ---- the envelope ----

    @Test
    fun `no duck spans hold the lead level flat`() {
        val points = VideoMusicDucking.envelope(track(), emptyList())
        assertEquals(2, points.size)
        assertEquals(1f, points.first().second, 0.0001f)
        assertEquals(1f, points.last().second, 0.0001f)
    }

    @Test
    fun `a duck span dips to the ducked level with ramps on both sides`() {
        // Music 0..20s; the clip sounds 2..12s (inside the window, so the lead-in exists).
        val points = VideoMusicDucking.envelope(
            track(),
            listOf(TimeRange(2_000, 12_000)),
        )
        // Starts at lead, ramps down, holds ducked, ramps up, ends lead.
        assertEquals(1f, points.first().second, 0.0001f)
        val duckedPoints = points.filter { it.first in 2_000L..11_999L }
        assertTrue("the duck holds across the span", duckedPoints.isNotEmpty())
        duckedPoints.forEach { assertEquals(0.3f, it.second, 0.0001f) }
        // Ends back at lead after the span.
        assertEquals(1f, points.last().second, 0.0001f)
        // Monotone in offset (the realization's curve assumption).
        assertEquals(points.sortedBy { it.first }, points)
    }

    @Test
    fun `a duck span starting at the window start begins ducked - honest`() {
        // Audio present from frame 0: no lead-in to preserve, so offset 0 = ducked.
        val points = VideoMusicDucking.envelope(
            track(),
            listOf(TimeRange(0, 10_000)),
        )
        assertEquals(0.3f, points.first().second, 0.0001f)
        // Ramps up after the span and ends at lead.
        assertEquals(1f, points.last().second, 0.0001f)
        assertTrue(points.last().first > 10_000)
    }

    @Test
    fun `overlapping duck spans fold into one dip`() {
        // Two overlapping spans fold to one union (2..10s): one dip with ramps both sides.
        val spans = listOf(TimeRange(0, 6_000), TimeRange(5_000, 10_000))
        val points = VideoMusicDucking.envelope(track(), spans)
        // The union starts at 0: begins ducked, ramps up once at its end --- one dip total.
        val levelChanges = points.zipWithNext { a, b -> a.second != b.second }.count { it }
        assertEquals("one dip, one rise out", 1, levelChanges)
        assertEquals(0.3f, points.first().second, 0.0001f)
        assertEquals(1f, points.last().second, 0.0001f)
    }

    @Test
    fun `a fully ducked window never rises`() {
        // duckFactor 0 over the whole window: silent throughout, ramp-out to the same 0.
        val points = VideoMusicDucking.envelope(
            track(startMs = 0, endMs = 10_000, duck = 0f),
            listOf(TimeRange(0, 10_000)),
        )
        assertTrue(points.isNotEmpty())
        assertTrue("every point silent: $points", points.all { it.second == 0f })
    }
}
