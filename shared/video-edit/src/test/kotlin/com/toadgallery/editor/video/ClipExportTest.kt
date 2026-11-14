package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Composition-export pins: per-entry projection (order, trims, speeds, per-clip mute), validator
 * totals, and the v1 equivalence (a single-clip composition projects to the v1 plan's run specs).
 */
class ClipExportTest {

    private fun entry(
        uri: String,
        durationMs: Long,
        startMs: Long,
        endMs: Long,
        speed: Double = 1.0,
        audio: Boolean = true,
    ) = ClipTimelineEntry(
        sourceUri = uri,
        durationMs = durationMs,
        audioTrackPresent = audio,
        trim = TimeRange(startMs, endMs),
        speedFactor = speed,
    )

    private fun request(
        timeline: ClipTimeline,
        mute: Boolean = false,
        volume: Float = 1f,
        adjustments: VideoAdjustParams = VideoAdjustParams(),
    ) = ClipExportRequest(
        timeline = timeline,
        mute = mute,
        volume = volume,
        adjustments = adjustments,
        workingDirPath = "/cache",
    )

    // ---- the projection ----

    @Test
    fun `the projection carries every entry's uri, trim and speed in order`() {
        val specs = ClipExportProjection.itemSpecs(
            request(
                ClipTimeline(
                    listOf(
                        entry("content://a", 10_000, 500, 4_000),
                        entry("content://b", 6_000, 0, 6_000, speed = 0.5),
                        entry("content://c", 8_000, 2_000, 7_900, speed = 2.0),
                    ),
                ),
            ),
        )
        assertEquals(3, specs.size)
        assertEquals("content://a", specs[0].sourceUri)
        assertEquals(TimeRange(500, 4_000), specs[0].range)
        assertEquals(1.0, specs[0].speedFactor, 0.0)
        assertEquals("content://b", specs[1].sourceUri)
        assertEquals(0.5, specs[1].speedFactor, 0.0)
        assertEquals("content://c", specs[2].sourceUri)
        assertEquals(2.0, specs[2].speedFactor, 0.0)
    }

    @Test
    fun `an unmuted composition keeps every clip's audio`() {
        val specs = ClipExportProjection.itemSpecs(
            request(
                ClipTimeline(
                    listOf(
                        entry("content://a", 10_000, 0, 10_000, audio = true),
                        entry("content://b", 10_000, 0, 10_000, audio = false),
                    ),
                ),
                mute = false,
            ),
        )
        assertEquals(listOf(false, false), specs.map { it.removeAudio })
    }

    @Test
    fun `a muted composition strips the track only on the clips that have one`() {
        val specs = ClipExportProjection.itemSpecs(
            request(
                ClipTimeline(
                    listOf(
                        entry("content://a", 10_000, 0, 10_000, audio = true),
                        entry("content://b", 10_000, 0, 10_000, audio = false),
                        entry("content://c", 10_000, 0, 10_000, audio = true),
                    ),
                ),
                mute = true,
            ),
        )
        // The mixed composition's honest resolution: strip, recorded no-op, strip --- per clip, never blanket.
        assertEquals(listOf(true, false, true), specs.map { it.removeAudio })
    }

    // ---- the v1 equivalence (the two resolvers cannot drift) ----

    @Test
    fun `a single-clip composition projects to the v1 plan's run specs`() {
        for (speed in listOf(0.25, 0.5, 1.0, 1.5, 2.0)) {
            for (mute in listOf(false, true)) {
                val timeline = ClipTimeline(
                    listOf(
                        entry(
                            "content://media/7",
                            durationMs = 20_000,
                            startMs = 1_000,
                            endMs = 19_000,
                            speed = speed,
                            audio = true,
                        ),
                    ),
                )
                val plan = VideoEditPlan(
                    durationMs = 20_000,
                    trim = TimeRange(1_000, 19_000),
                    speedSegments = listOf(SpeedSegment(1_000, 19_000, speed)),
                    audioTrackPresent = true,
                    mute = mute,
                )
                val specs = ClipExportProjection.itemSpecs(request(timeline, mute = mute))
                val runs = VideoEditMath.timelineRuns(plan)
                assertEquals("factor $speed mute $mute: one spec per run", runs.size, specs.size)
                for (i in runs.indices) {
                    assertEquals("factor $speed mute $mute run $i uri", "content://media/7", specs[i].sourceUri)
                    assertEquals("factor $speed mute $mute run $i range", TimeRange(runs[i].startMs, runs[i].endMs), specs[i].range)
                    assertEquals("factor $speed mute $mute run $i speed", runs[i].speedFactor, specs[i].speedFactor, 0.0)
                }
                assertEquals(
                    "factor $speed mute $mute: the strip decision matches the v1 mute resolution",
                    plan.muteDecision() == MuteDecision.STRIP_AUDIO,
                    specs[0].removeAudio,
                )
                assertEquals(
                    "factor $speed mute $mute: the output duration agrees",
                    VideoEditMath.outputDurationMs(plan),
                    ClipTimelineMath.outputDurationMs(timeline),
                )
            }
        }
    }

    // ---- the validator ----

    @Test
    fun `a valid composition request passes`() {
        val outcome = ClipExportValidator.validate(
            request(ClipTimeline(listOf(entry("content://a", 10_000, 0, 10_000)))),
        )
        assertTrue(outcome is ClipExportValidation.Valid)
    }

    @Test
    fun `an empty composition is refused with the timeline's reason`() {
        val outcome = ClipExportValidator.validate(request(ClipTimeline(emptyList())))
        val reason = (outcome as ClipExportValidation.Invalid).reason
        assertTrue(reason.contains("at least one clip"))
    }

    @Test
    fun `a trim escaping its clip is refused`() {
        val outcome = ClipExportValidator.validate(
            request(ClipTimeline(listOf(entry("content://a", 5_000, 0, 9_000)))),
        )
        assertTrue(outcome is ClipExportValidation.Invalid)
    }

    @Test
    fun `a NaN volume is refused before the bounds`() {
        val outcome = ClipExportValidator.validate(
            request(
                ClipTimeline(listOf(entry("content://a", 10_000, 0, 10_000))),
                volume = Float.NaN,
            ),
        )
        val reason = (outcome as ClipExportValidation.Invalid).reason
        assertTrue(reason.contains("NaN"))
    }

    @Test
    fun `a volume past the ceiling is refused`() {
        val outcome = ClipExportValidator.validate(
            request(
                ClipTimeline(listOf(entry("content://a", 10_000, 0, 10_000))),
                volume = 5f,
            ),
        )
        val reason = (outcome as ClipExportValidation.Invalid).reason
        assertTrue(reason.contains("volume"))
    }

    @Test
    fun `an out-of-range adjustment is refused by the shared projection`() {
        val outcome = ClipExportValidator.validate(
            request(
                ClipTimeline(listOf(entry("content://a", 10_000, 0, 10_000))),
                adjustments = VideoAdjustParams(brightness = 200),
            ),
        )
        assertTrue(outcome is ClipExportValidation.Invalid)
    }
}
