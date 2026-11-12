package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r74 crossfade model's JVM pins: the boundary rules, the
 * neighbour-length honesty, and the transition-applied duration math.
 */
class VideoCrossfadeTest {

    private fun twoClips(first: Long = 10_000, second: Long = 8_000): ClipTimeline {
        fun entry(uri: String, duration: Long) = ClipTimelineEntry(
            sourceUri = uri,
            durationMs = duration,
            audioTrackPresent = true,
            trim = TimeRange(0, duration),
            speedFactor = 1.0,
        )
        return ClipTimeline(listOf(entry("a", first), entry("b", second)))
    }

    // ---- the validator ----

    @Test
    fun `a one-clip timeline has no boundaries`() {
        val single = ClipTimeline(
            listOf(
                ClipTimelineEntry("a", 10_000, true, TimeRange(0, 10_000)),
            ),
        )
        assertEquals(null, VideoCrossfade.boundariesOf(single))
        val result = VideoCrossfadeValidator.validate(
            listOf(VideoCrossfade(1, 500)),
            single,
        )
        assertTrue(result is VideoCrossfadeValidation.Invalid)
        assertTrue(
            (result as VideoCrossfadeValidation.Invalid).reason.contains("no boundaries"),
        )
    }

    @Test
    fun `two transitions on one seam are refused`() {
        val result = VideoCrossfadeValidator.validate(
            listOf(VideoCrossfade(1, 500), VideoCrossfade(1, 600)),
            twoClips(),
        )
        assertTrue(result is VideoCrossfadeValidation.Invalid)
        assertTrue(
            (result as VideoCrossfadeValidation.Invalid).reason.contains("one seam, one dissolve"),
        )
    }

    @Test
    fun `a boundary past the composition is refused`() {
        val result = VideoCrossfadeValidator.validate(
            listOf(VideoCrossfade(2, 500)),
            twoClips(),
        )
        assertTrue(result is VideoCrossfadeValidation.Invalid)
        assertTrue(
            (result as VideoCrossfadeValidation.Invalid).reason.contains("boundaries 1..1"),
        )
    }

    @Test
    fun `a dissolve longer than half a neighbour is refused with it named`() {
        // clip 0 = 10s, clip 1 = 1.2s: a 700ms fade eats more than half of clip 1.
        val result = VideoCrossfadeValidator.validate(
            listOf(VideoCrossfade(1, 700)),
            twoClips(second = 1_200),
        )
        assertTrue(result is VideoCrossfadeValidation.Invalid)
        val reason = (result as VideoCrossfadeValidation.Invalid).reason
        assertTrue(reason.contains("shorten the dissolve"))
        assertTrue(reason.contains("clip 1: 1200 ms"))
    }

    @Test
    fun `an honest dissolve is valid`() {
        val result = VideoCrossfadeValidator.validate(
            listOf(VideoCrossfade(1, 500)),
            twoClips(),
        )
        assertTrue(result is VideoCrossfadeValidation.Valid)
    }

    // ---- the duration math ----

    @Test
    fun `every transition shortens the total by its length`() {
        fun entry(uri: String, duration: Long) = ClipTimelineEntry(
            uri, duration, true, TimeRange(0, duration),
        )
        val three = ClipTimeline(
            listOf(entry("a", 10_000), entry("b", 10_000), entry("c", 10_000)),
        )
        val transitions = listOf(
            VideoCrossfade(1, 500),
            VideoCrossfade(2, 250),
        )
        assertEquals(29_250, VideoCrossfadeMath.outputDurationMs(three, transitions))
        assertEquals(30_000, VideoCrossfadeMath.outputDurationMs(three, emptyList()))
    }

    @Test
    fun `the entry-start offsets shift by every boundary at or before them`() {
        fun entry(uri: String, duration: Long) = ClipTimelineEntry(
            uri, duration, true, TimeRange(0, duration),
        )
        val three = ClipTimeline(
            listOf(entry("a", 10_000), entry("b", 10_000), entry("c", 10_000)),
        )
        val transitions = listOf(VideoCrossfade(1, 500), VideoCrossfade(2, 250))
        assertEquals(0, VideoCrossfadeMath.entryStartOffsetMs(three, transitions, 0))
        assertEquals(9_500, VideoCrossfadeMath.entryStartOffsetMs(three, transitions, 1))
        // boundary 2's transition also applies to entry 2.
        assertEquals(19_250, VideoCrossfadeMath.entryStartOffsetMs(three, transitions, 2))
    }

    @Test
    fun `the walk plus the last entry reproduces the transitioned total`() {
        fun entry(uri: String, duration: Long) = ClipTimelineEntry(
            uri, duration, true, TimeRange(0, duration),
        )
        val three = ClipTimeline(
            listOf(entry("a", 10_000), entry("b", 10_000), entry("c", 10_000)),
        )
        val transitions = listOf(VideoCrossfade(1, 500))
        val total = VideoCrossfadeMath.outputDurationMs(three, transitions)
        val lastStart = VideoCrossfadeMath.entryStartOffsetMs(three, transitions, 2)
        assertEquals(total, lastStart + 10_000)
    }
}
