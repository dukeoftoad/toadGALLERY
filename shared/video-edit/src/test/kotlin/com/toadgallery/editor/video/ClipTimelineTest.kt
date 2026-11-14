package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composition-model pins: validation, the per-clip walk, single-clip equivalence
 * with the v1 plan math, and the inverse round trip.
 */
class ClipTimelineTest {

    private fun entry(
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        speed: Double = 1.0,
        audio: Boolean = true,
    ) = ClipTimelineEntry(
        sourceUri = "content://test/$durationMs",
        durationMs = durationMs,
        audioTrackPresent = audio,
        trim = TimeRange(startMs, endMs),
        speedFactor = speed,
    )

    // ---- validation ----

    @Test
    fun `an empty composition is not an edit`() {
        val outcome = ClipTimelineValidator.validate(ClipTimeline(emptyList()))
        assertTrue(outcome is ClipTimelineValidation.Invalid)
    }

    @Test
    fun `a composition past the entry cap is refused`() {
        val many = ClipTimeline((1..ClipTimelineValidator.MAX_ENTRIES + 1).map { entry(1_000, 0, 1_000) })
        val outcome = ClipTimelineValidator.validate(many)
        assertTrue(outcome is ClipTimelineValidation.Invalid)
    }

    @Test
    fun `a trim window escaping its clip is refused with the clip named`() {
        val outcome = ClipTimelineValidator.validate(
            ClipTimeline(listOf(entry(1_000, 0, 1_000), entry(2_000, 100, 2_500))),
        )
        val reason = (outcome as ClipTimelineValidation.Invalid).reason
        assertTrue(reason.contains("clip 1"))
        assertTrue(reason.contains("2500"))
    }

    @Test
    fun `a negative trim start is refused`() {
        val outcome = ClipTimelineValidator.validate(
            ClipTimeline(listOf(entry(1_000, -10, 500))),
        )
        assertTrue(outcome is ClipTimelineValidation.Invalid)
    }

    @Test
    fun `a valid multi-clip composition passes`() {
        val timeline = ClipTimeline(
            listOf(entry(10_000, 500, 9_000, 0.5), entry(4_000, 0, 4_000, 2.0)),
        )
        assertTrue(ClipTimelineValidator.validate(timeline) is ClipTimelineValidation.Valid)
    }

    // ---- the single-clip equivalence (the adoption bridge) ----

    @Test
    fun `a one-clip timeline agrees with the v1 plan math on output duration`() {
        for (speed in listOf(0.25, 0.5, 1.0, 1.5, 2.0)) {
            val timeline = ClipTimeline(listOf(entry(20_000, 1_000, 19_000, speed)))
            val plan = VideoEditPlan(
                durationMs = 20_000,
                trim = TimeRange(1_000, 19_000),
                speedSegments = listOf(SpeedSegment(1_000, 19_000, speed)),
                audioTrackPresent = false,
            )
            assertEquals(
                "factor $speed",
                VideoEditMath.outputDurationMs(plan),
                ClipTimelineMath.outputDurationMs(timeline),
            )
        }
    }

    @Test
    fun `a one-clip timeline agrees with the v1 plan math on the source walk`() {
        val speed = 0.5
        val timeline = ClipTimeline(listOf(entry(20_000, 1_000, 19_000, speed)))
        val plan = VideoEditPlan(
            durationMs = 20_000,
            trim = TimeRange(1_000, 19_000),
            speedSegments = listOf(SpeedSegment(1_000, 19_000, speed)),
            audioTrackPresent = false,
        )
        for (offset in listOf(0L, 1L, 500L, 4_000L, 8_000L, 15_999L, 16_000L)) {
            assertEquals(
                "offset $offset",
                VideoEditMath.sourceTimeAtOutputOffset(plan, offset),
                ClipTimelineMath.playheadAtOutputOffset(timeline, offset).sourceMs,
            )
        }
    }

    // ---- the multi-clip walk ----

    @Test
    fun `the output duration sums the per-clip trimmed lengths at speed`() {
        val timeline = ClipTimeline(
            listOf(
                entry(10_000, 0, 4_000, 1.0), // 4 000 ms out
                entry(10_000, 1_000, 9_000, 0.5), // 16 000 ms out
                entry(10_000, 2_000, 4_000, 2.0), // 1 000 ms out
            ),
        )
        assertEquals(21_000L, ClipTimelineMath.outputDurationMs(timeline))
    }

    @Test
    fun `the walk lands inside the right clip at the right source time`() {
        val timeline = ClipTimeline(
            listOf(
                entry(10_000, 0, 4_000, 1.0),
                entry(10_000, 1_000, 9_000, 0.5),
                entry(10_000, 2_000, 4_000, 2.0),
            ),
        )
        // 2 000 into clip 2: source = 1 000 + 2 000 * 0.5 = 2 000.
        val mid = ClipTimelineMath.playheadAtOutputOffset(timeline, 4_000 + 2_000)
        assertEquals(1, mid.entryIndex)
        assertEquals(2_000L, mid.sourceMs)
        // 500 into clip 3: source = 2 000 + 500 * 2 = 3 000.
        val tail = ClipTimelineMath.playheadAtOutputOffset(timeline, 20_000 + 500)
        assertEquals(2, tail.entryIndex)
        assertEquals(3_000L, tail.sourceMs)
    }

    @Test
    fun `a clip boundary belongs to the next clip's start`() {
        val timeline = ClipTimeline(
            listOf(entry(10_000, 0, 4_000, 1.0), entry(10_000, 1_000, 9_000, 1.0)),
        )
        val boundary = ClipTimelineMath.playheadAtOutputOffset(timeline, 4_000)
        assertEquals(1, boundary.entryIndex)
        assertEquals(1_000L, boundary.sourceMs)
    }

    @Test
    fun `an offset past the total clamps to the last clip's trim end`() {
        val timeline = ClipTimeline(
            listOf(entry(10_000, 0, 4_000, 1.0), entry(10_000, 1_000, 9_000, 1.0)),
        )
        val past = ClipTimelineMath.playheadAtOutputOffset(timeline, 12_000)
        assertEquals(1, past.entryIndex)
        assertEquals(9_000L, past.sourceMs)
    }

    @Test
    fun `source time strictly increases across the whole output timeline`() {
        val timeline = ClipTimeline(
            listOf(
                entry(8_000, 500, 7_500, 0.25),
                entry(8_000, 0, 8_000, 2.0),
                entry(8_000, 100, 7_900, 1.0),
            ),
        )
        // Compare the ABSOLUTE source-position stream (clip index breaks the comparison): walk in
        // output time and demand the playhead never go backwards.
        var previous = ClipTimelineMath.playheadAtOutputOffset(timeline, 0)
        var offset = 250L
        val total = ClipTimelineMath.outputDurationMs(timeline)
        while (offset < total) {
            val current = ClipTimelineMath.playheadAtOutputOffset(timeline, offset)
            assertTrue(
                "the playhead folded backwards at output $offset: $previous then $current",
                current.entryIndex > previous.entryIndex ||
                    (current.entryIndex == previous.entryIndex && current.sourceMs >= previous.sourceMs),
            )
            previous = current
            offset += 250L
        }
    }

    // ---- the inverse round trip ----

    @Test
    fun `source-to-output and output-to-source round trip within a millisecond`() {
        val timeline = ClipTimeline(
            listOf(
                entry(10_000, 1_000, 9_000, 0.5),
                entry(6_000, 0, 6_000, 1.5),
                entry(6_000, 500, 5_500, 1.0),
            ),
        )
        val total = ClipTimelineMath.outputDurationMs(timeline)
        var offset = 0L
        while (offset <= total) {
            val playhead = ClipTimelineMath.playheadAtOutputOffset(timeline, offset)
            val roundTrip = ClipTimelineMath.outputOffsetOfSource(
                timeline,
                playhead.entryIndex,
                playhead.sourceMs,
            )
            assertTrue(
                "offset $offset round-tripped to $roundTrip",
                Math.abs(roundTrip - offset) <= 1L,
            )
            offset += 333L
        }
    }

    @Test
    fun `asking about an untrimmed position answers with the nearest bound`() {
        val timeline = ClipTimeline(listOf(entry(10_000, 2_000, 8_000, 1.0)))
        assertEquals(0L, ClipTimelineMath.outputOffsetOfSource(timeline, 0, 500))
        assertEquals(6_000L, ClipTimelineMath.outputOffsetOfSource(timeline, 0, 9_999))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an out-of-range entry index is refused`() {
        val timeline = ClipTimeline(listOf(entry(10_000, 0, 10_000)))
        ClipTimelineMath.outputOffsetOfSource(timeline, 1, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative output offset is refused`() {
        val timeline = ClipTimeline(listOf(entry(10_000, 0, 10_000)))
        ClipTimelineMath.playheadAtOutputOffset(timeline, -1)
    }
}
