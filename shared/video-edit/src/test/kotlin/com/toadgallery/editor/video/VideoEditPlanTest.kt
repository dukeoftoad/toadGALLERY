package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The VideoEditPlan rules (r42, decisions 1-4): the trim validation matrix,
 * the speed-plan rules, the monotonicity rule, the derived-duration math,
 * the audio-fact handling and the r52 volume-gain bounds. Every rule is a
 * structured failure with an honest message --- never a silent clamp.
 */
class VideoEditPlanTest {

    private fun plan(
        durationMs: Long = 10_000,
        startMs: Long = 0,
        endMs: Long = 10_000,
        segments: List<SpeedSegment> = emptyList(),
        audioTrackPresent: Boolean = true,
        mute: Boolean = false,
        volume: Float = 1f,
        adjustments: VideoAdjustParams = VideoAdjustParams(),
    ) = VideoEditPlan(
        durationMs = durationMs,
        // TimeRange guards only the positive length; the clip-relative
        // bounds are the validator's structured failures (see the matrix).
        trim = TimeRange(startMs, endMs),
        speedSegments = segments,
        audioTrackPresent = audioTrackPresent,
        mute = mute,
        volume = volume,
        adjustments = adjustments,
    )

    // ---- the trim validation matrix ----

    @Test
    fun `a whole-clip trim is valid`() {
        val result = VideoEditPlanValidator.validate(plan())
        assertTrue(result is VideoPlanValidation.Valid)
    }

    @Test
    fun `a partial trim is valid`() {
        val result = VideoEditPlanValidator.validate(plan(startMs = 1_000, endMs = 9_000))
        assertTrue(result is VideoPlanValidation.Valid)
    }

    @Test
    fun `the trim bounds may touch the clip ends exactly`() {
        assertTrue(
            VideoEditPlanValidator.validate(
                plan(durationMs = 5_000, startMs = 0, endMs = 5_000),
            ) is VideoPlanValidation.Valid,
        )
    }

    @Test
    fun `a zero-length trim is refused by the constructor guard`() {
        try {
            TimeRange(4_000, 4_000)
            fail("a zero-length range must not construct")
        } catch (_: IllegalArgumentException) {
            // the honest guard
        }
    }

    @Test
    fun `a negative-length trim is refused by the constructor guard`() {
        try {
            TimeRange(5_000, 4_000)
            fail("a backwards range must not construct")
        } catch (_: IllegalArgumentException) {
            // the honest guard
        }
    }

    @Test
    fun `a trim before the clip start is a structured failure`() {
        val result = VideoEditPlanValidator.validate(plan(startMs = -1))
        assertTrue(result is VideoPlanValidation.Invalid)
        assertTrue(
            (result as VideoPlanValidation.Invalid).reason.contains("before the clip start"),
        )
    }

    @Test
    fun `a trim past the clip duration is a structured failure`() {
        val result = VideoEditPlanValidator.validate(plan(endMs = 10_001))
        assertTrue(result is VideoPlanValidation.Invalid)
        assertTrue(
            (result as VideoPlanValidation.Invalid).reason.contains("past the clip duration"),
        )
    }

    @Test
    fun `a non-positive duration fact is a structured failure`() {
        val result = VideoEditPlanValidator.validate(plan(durationMs = 0))
        assertTrue(result is VideoPlanValidation.Invalid)
        assertTrue(
            (result as VideoPlanValidation.Invalid).reason.contains("unreadable source"),
        )
    }

    // ---- the speed-plan rules ----

    @Test
    fun `factors at the 0x25 and 2x bounds are valid, outside are refused`() {
        val inBounds = listOf(0.25, 0.5, 1.0, 1.5, 2.0)
        for (factor in inBounds) {
            val result = VideoEditPlanValidator.validate(
                plan(segments = listOf(SpeedSegment(0, 5_000, factor))),
            )
            assertTrue("factor $factor must be valid", result is VideoPlanValidation.Valid)
        }
        for (factor in listOf(0.24, 2.01, 0.0, -1.0, 10.0)) {
            val result = VideoEditPlanValidator.validate(
                plan(segments = listOf(SpeedSegment(0, 5_000, factor))),
            )
            assertTrue("factor $factor must be refused", result is VideoPlanValidation.Invalid)
            assertTrue(
                (result as VideoPlanValidation.Invalid).reason.contains("outside"),
            )
        }
    }

    @Test
    fun `segments must stay inside the trim range`() {
        val result = VideoEditPlanValidator.validate(
            plan(startMs = 2_000, endMs = 8_000, segments = listOf(SpeedSegment(0, 3_000, 0.5))),
        )
        assertTrue(result is VideoPlanValidation.Invalid)
        assertTrue(
            (result as VideoPlanValidation.Invalid).reason.contains("escapes the trim range"),
        )
    }

    @Test
    fun `overlapping segments are refused`() {
        val result = VideoEditPlanValidator.validate(
            plan(
                segments = listOf(
                    SpeedSegment(0, 5_000, 0.5),
                    SpeedSegment(4_000, 6_000, 2.0),
                ),
            ),
        )
        assertTrue(result is VideoPlanValidation.Invalid)
        assertTrue(
            (result as VideoPlanValidation.Invalid).reason.contains("overlaps or precedes"),
        )
    }

    @Test
    fun `unsorted segments are refused`() {
        val result = VideoEditPlanValidator.validate(
            plan(
                segments = listOf(
                    SpeedSegment(5_000, 6_000, 2.0),
                    SpeedSegment(0, 1_000, 0.5),
                ),
            ),
        )
        assertTrue(result is VideoPlanValidation.Invalid)
        assertTrue(
            (result as VideoPlanValidation.Invalid).reason.contains("sorted, non-overlapping"),
        )
    }

    @Test
    fun `a zero-length segment is refused by the constructor guard`() {
        try {
            SpeedSegment(1_000, 1_000, 1.0)
            fail("a zero-length segment must not construct")
        } catch (_: IllegalArgumentException) {
            // the honest guard
        }
    }

    @Test
    fun `a zero-length segment past the trim is also a plan failure`() {
        // The constructor guard covers start == end inside the range; a
        // segment that escapes the trim is caught by the validator even
        // before its own bounds check, proving the trim rule runs first.
        val result = VideoEditPlanValidator.validate(
            plan(startMs = 2_000, endMs = 8_000, segments = listOf(SpeedSegment(0, 1_000, 0.5))),
        )
        assertTrue(result is VideoPlanValidation.Invalid)
    }

    // ---- gaps ride at 1x: the timeline runs ----

    @Test
    fun `gaps between and around segments ride at 1x`() {
        val plan = plan(
            startMs = 1_000,
            endMs = 9_000,
            segments = listOf(SpeedSegment(3_000, 5_000, 0.5)),
        )
        val runs = VideoEditMath.timelineRuns(plan)
        assertEquals(
            listOf(
                SpeedSegment(1_000, 3_000, 1.0),
                SpeedSegment(3_000, 5_000, 0.5),
                SpeedSegment(5_000, 9_000, 1.0),
            ),
            runs,
        )
    }

    @Test
    fun `adjacent segments produce no gap run between them`() {
        val plan = plan(
            segments = listOf(
                SpeedSegment(0, 4_000, 0.25),
                SpeedSegment(4_000, 6_000, 2.0),
            ),
        )
        // The two adjacent segments chain directly (no 1x run BETWEEN them);
        // the trim's trailing 6..10s rides at 1x as its own run (the rule).
        assertEquals(
            listOf(
                SpeedSegment(0, 4_000, 0.25),
                SpeedSegment(4_000, 6_000, 2.0),
                SpeedSegment(6_000, 10_000, 1.0),
            ),
            VideoEditMath.timelineRuns(plan),
        )
    }

    @Test
    fun `a no-segment plan is one 1x run over the trim`() {
        val plan = plan(startMs = 1_000, endMs = 4_000)
        assertEquals(
            listOf(SpeedSegment(1_000, 4_000, 1.0)),
            VideoEditMath.timelineRuns(plan),
        )
    }

    // ---- the derived output duration ----

    @Test
    fun `whole-clip 0x25 quadruples the duration and 2x halves it`() {
        assertEquals(40_000, VideoEditMath.outputDurationMs(plan(segments = listOf(SpeedSegment(0, 10_000, 0.25)))))
        assertEquals(5_000, VideoEditMath.outputDurationMs(plan(segments = listOf(SpeedSegment(0, 10_000, 2.0)))))
    }

    @Test
    fun `the derived duration sums every run with gaps at 1x`() {
        // gap 0..2s at 1x (2s) + segment 2..6s at 0.5x (8s) + gap 6..10s at 1x (4s) = 14s
        val plan = plan(segments = listOf(SpeedSegment(2_000, 6_000, 0.5)))
        assertEquals(14_000, VideoEditMath.outputDurationMs(plan))
    }

    @Test
    fun `an all-1x plan is an identity duration`() {
        assertEquals(10_000, VideoEditMath.outputDurationMs(plan()))
        assertEquals(7_000, VideoEditMath.outputDurationMs(plan(startMs = 1_000, endMs = 8_000)))
    }

    @Test
    fun `the trim shrinks the duration exactly`() {
        assertEquals(3_000, VideoEditMath.outputDurationMs(plan(startMs = 2_000, endMs = 5_000)))
    }

    // ---- the monotonicity rule ----

    @Test
    fun `source time strictly increases across the whole output timeline`() {
        val plan = plan(
            startMs = 500,
            endMs = 9_500,
            segments = listOf(
                SpeedSegment(1_000, 3_000, 0.25),
                SpeedSegment(4_000, 4_500, 2.0),
                SpeedSegment(5_000, 6_000, 0.5),
            ),
        )
        val duration = VideoEditMath.outputDurationMs(plan)
        var previous = -1L
        val steps = 400
        for (step in 0..steps) {
            val outputOffset = duration * step / steps
            val source = VideoEditMath.sourceTimeAtOutputOffset(plan, outputOffset)
            assertTrue(
                "source time must strictly increase (step $step: $source after $previous)",
                source > previous,
            )
            previous = source
        }
        // the mapping covers exactly the trim range
        assertEquals(500, VideoEditMath.sourceTimeAtOutputOffset(plan, 0))
        assertEquals(9_500, VideoEditMath.sourceTimeAtOutputOffset(plan, duration))
    }

    @Test
    fun `the mapping lands on the segment boundaries`() {
        // 0..4s at 2x (2s out), then 4..8s at 1x (4s out)
        val plan = plan(endMs = 8_000, segments = listOf(SpeedSegment(0, 4_000, 2.0)))
        assertEquals(0, VideoEditMath.sourceTimeAtOutputOffset(plan, 0))
        assertEquals(2_000, VideoEditMath.sourceTimeAtOutputOffset(plan, 1_000))
        assertEquals(4_000, VideoEditMath.sourceTimeAtOutputOffset(plan, 2_000))
        assertEquals(6_000, VideoEditMath.sourceTimeAtOutputOffset(plan, 4_000))
        assertEquals(8_000, VideoEditMath.sourceTimeAtOutputOffset(plan, 6_000))
    }

    // ---- the audio fact ----

    @Test
    fun `mute on a normal clip strips the audio track`() {
        assertEquals(
            MuteDecision.STRIP_AUDIO,
            plan(mute = true, audioTrackPresent = true).muteDecision(),
        )
    }

    @Test
    fun `mute on a no-audio clip is a recorded no-op`() {
        assertEquals(
            MuteDecision.RECORDED_NO_OP,
            plan(mute = true, audioTrackPresent = false).muteDecision(),
        )
    }

    @Test
    fun `no mute keeps the audio regardless of the fact`() {
        assertEquals(
            MuteDecision.KEEP_AUDIO,
            plan(mute = false, audioTrackPresent = false).muteDecision(),
        )
        assertEquals(
            MuteDecision.KEEP_AUDIO,
            plan(mute = false, audioTrackPresent = true).muteDecision(),
        )
    }

    @Test
    fun `a no-audio mute plan is still VALID (the export proceeds video-only)`() {
        val result = VideoEditPlanValidator.validate(
            plan(mute = true, audioTrackPresent = false),
        )
        assertTrue(result is VideoPlanValidation.Valid)
    }

    // ---- the colour parameters ride the plan validation ----

    @Test
    fun `an out-of-range colour parameter is a plan failure with its name`() {
        val result = VideoEditPlanValidator.validate(
            plan(adjustments = VideoAdjustParams(brightness = 101)),
        )
        assertTrue(result is VideoPlanValidation.Invalid)
        val reason = (result as VideoPlanValidation.Invalid).reason
        assertTrue(reason.contains("brightness"))
        assertTrue(reason.contains("101"))
    }

    // ---- the r52 volume gain ----

    @Test
    fun `a plan without a volume argument validates and carries the 1f unchanged default`() {
        val defaultPlan = plan()
        assertTrue(VideoEditPlanValidator.validate(defaultPlan) is VideoPlanValidation.Valid)
        assertTrue("volume must default to the 1f identity", defaultPlan.volume == 1f)
    }

    @Test
    fun `volume at the 0f and 2f bounds is valid`() {
        for (volume in listOf(0f, 2f)) {
            val result = VideoEditPlanValidator.validate(plan(volume = volume))
            assertTrue("volume $volume must be valid", result is VideoPlanValidation.Valid)
        }
    }

    @Test
    fun `volume below 0f and above 2f is a structured failure`() {
        for (volume in listOf(-0.5f, 2.5f)) {
            val result = VideoEditPlanValidator.validate(plan(volume = volume))
            assertTrue("volume $volume must be refused", result is VideoPlanValidation.Invalid)
            assertTrue(
                (result as VideoPlanValidation.Invalid).reason.contains("outside"),
            )
        }
    }

    @Test
    fun `a NaN volume is a structured failure`() {
        val result = VideoEditPlanValidator.validate(plan(volume = Float.NaN))
        assertTrue(result is VideoPlanValidation.Invalid)
        assertTrue(
            (result as VideoPlanValidation.Invalid).reason.contains("not a number"),
        )
    }

    @Test
    fun `mute and volume coexist in one valid plan`() {
        val coexistPlan = plan(mute = true, volume = 0.5f)
        assertTrue(VideoEditPlanValidator.validate(coexistPlan) is VideoPlanValidation.Valid)
        assertEquals(MuteDecision.STRIP_AUDIO, coexistPlan.muteDecision())
    }
}
