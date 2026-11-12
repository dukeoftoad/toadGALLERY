package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r74 pins: the speed-ramp model, the shared look's sampler and
 * grammar, the 4K/HDR preflight, and the composition-lane speed bounds
 * (the plan doc's 0.25x..4x --- the v1 plan's own frozen 0.25x..2x is
 * untouched, pinned by its own test file).
 */
class VideoRampLookPreflightTest {

    // ---- the speed ramp ----

    @Test
    fun `an empty ramp is valid and falls back to the whole-clip factor`() {
        val trim = TimeRange(0, 10_000)
        assertTrue(SpeedRampValidator.validate(emptyList(), trim) is SpeedRampValidation.Valid)
        assertEquals(1.0, SpeedRampMath.factorAt(emptyList(), 5_000, 1.0), 0.0)
        assertEquals(10_000, SpeedRampMath.outputDurationMs(emptyList(), trim, 1.0))
    }

    @Test
    fun `keyframes must strictly increase - steps are SpeedSegments' grammar`() {
        val trim = TimeRange(0, 10_000)
        val ramp = listOf(
            SpeedKeyframe(2_000, 1.0),
            SpeedKeyframe(2_000, 2.0), // same instant: refused
        )
        val result = SpeedRampValidator.validate(ramp, trim)
        assertTrue(result is SpeedRampValidation.Invalid)
        assertTrue(
            (result as SpeedRampValidation.Invalid).reason.contains("not strictly after"),
        )
    }

    @Test
    fun `keyframes must stay inside the trim window`() {
        val result = SpeedRampValidator.validate(
            listOf(SpeedKeyframe(11_000, 1.0)),
            TimeRange(0, 10_000),
        )
        assertTrue(result is SpeedRampValidation.Invalid)
        assertTrue(
            (result as SpeedRampValidation.Invalid).reason.contains("escapes the trim window"),
        )
    }

    @Test
    fun `the factor interpolates linearly between keyframes`() {
        val ramp = listOf(
            SpeedKeyframe(0, 1.0),
            SpeedKeyframe(10_000, 2.0),
        )
        assertEquals(1.0, SpeedRampMath.factorAt(ramp, 0, 1.0), 0.0001)
        assertEquals(1.5, SpeedRampMath.factorAt(ramp, 5_000, 1.0), 0.0001)
        assertEquals(2.0, SpeedRampMath.factorAt(ramp, 10_000, 1.0), 0.0001)
        // Outside: clamped to the ends.
        assertEquals(1.0, SpeedRampMath.factorAt(ramp, -100, 1.0), 0.0001)
        assertEquals(2.0, SpeedRampMath.factorAt(ramp, 20_000, 1.0), 0.0001)
    }

    @Test
    fun `a linear 1x-to-2x ramp halves the output - the integral is honest`() {
        val ramp = listOf(
            SpeedKeyframe(0, 1.0),
            SpeedKeyframe(10_000, 2.0),
        )
        // Integral of 1/factor over 10s where factor goes 1 -> 2:
        // ln(2) * 10s ~ 6.93s. The midpoint rule at 512 slices is within
        // 10ms of the true value; assert the honest window.
        val out = SpeedRampMath.outputDurationMs(ramp, TimeRange(0, 10_000), 1.0)
        assertTrue("expected ~6931ms, was $out", out in 6_920..6_940)
    }

    @Test
    fun `the composition lane carries the plan doc's 0x25-to-4x bounds`() {
        assertEquals(0.25, ClipTimelineValidator.MIN_SPEED_FACTOR, 0.0)
        assertEquals(4.0, ClipTimelineValidator.MAX_SPEED_FACTOR, 0.0)
        // The entry's constructor enforces them.
        ClipTimelineEntry("a", 10_000, true, TimeRange(0, 10_000), speedFactor = 4.0)
        try {
            ClipTimelineEntry("a", 10_000, true, TimeRange(0, 10_000), speedFactor = 4.5)
            throw AssertionError("4.5x must be refused on the timeline lane")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("outside"))
        }
        // The v1 plan lane stays frozen at 2.0 (the r42 contract).
        assertEquals(2.0, VideoEditPlanValidator.MAX_SPEED_FACTOR, 0.0)
    }

    @Test
    fun `the composition gate validates each entry's ramp`() {
        val bad = ClipTimeline(
            listOf(
                ClipTimelineEntry(
                    "a", 10_000, true, TimeRange(0, 10_000),
                    ramp = listOf(SpeedKeyframe(50_000, 1.0)), // escapes the trim
                ),
            ),
        )
        val result = ClipTimelineValidator.validate(bad)
        assertTrue(result is ClipTimelineValidation.Invalid)
        assertTrue(
            (result as ClipTimelineValidation.Invalid).reason.contains("clip 0"),
        )
    }

    // ---- the shared look ----

    /** A 2x2x2 identity cube (the parser's degenerate floor). */
    private fun identityCube(size: Int = 2): VideoLutLook {
        val data = DoubleArray(size * size * size * 3)
        var i = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val v = DoubleArray(3)
                    // The .cube identity: out = domain-mapped input.
                    val span = 1.0 / (size - 1)
                    data[i++] = r * span
                    data[i++] = g * span
                    data[i++] = b * span
                }
            }
        }
        return VideoLutLook(
            lutId = "identity",
            intensity = 100,
            size = size,
            domainMin = 0.0,
            domainMax = 1.0,
            data = data,
        )
    }

    @Test
    fun `an identity cube samples back the input`() {
        val cube = identityCube()
        val sampled = VideoLutSampler.sample(cube, 0.25, 0.5, 0.75)
        assertEquals(0.25, sampled.first, 0.02)
        assertEquals(0.5, sampled.second, 0.02)
        assertEquals(0.75, sampled.third, 0.02)
    }

    @Test
    fun `intensity zero is an exact identity - the r33 grammar`() {
        val look = identityCube().copy(intensity = 0)
        val sampled = VideoLutSampler.sample(look, 0.2, 0.4, 0.6)
        assertEquals(0.2, sampled.first, 0.0)
        assertEquals(0.4, sampled.second, 0.0)
        assertEquals(0.6, sampled.third, 0.0)
        assertEquals(0f, VideoLutLookValidator.intensityFactor(look), 0f)
    }

    @Test
    fun `a monochrome cube maps everything to its colour`() {
        // Every sample is (0.9, 0.1, 0.1): a red wash.
        val look = VideoLutLook(
            lutId = "wash",
            intensity = 100,
            size = 2,
            domainMin = 0.0,
            domainMax = 1.0,
            data = DoubleArray(8 * 3) { if (it % 3 == 0) 0.9 else 0.1 },
        )
        val sampled = VideoLutSampler.sample(look, 0.3, 0.3, 0.3)
        assertEquals(0.9, sampled.first, 0.0001)
        assertEquals(0.1, sampled.second, 0.0001)
        assertEquals(0.1, sampled.third, 0.0001)
    }

    @Test
    fun `the intensity blends linearly between original and cube output`() {
        val look = VideoLutLook(
            lutId = "wash",
            intensity = 50,
            size = 2,
            domainMin = 0.0,
            domainMax = 1.0,
            data = DoubleArray(8 * 3) { if (it % 3 == 0) 1.0 else 0.0 },
        )
        val sampled = VideoLutSampler.sample(look, 0.0, 0.0, 0.0)
        assertEquals(0.5, sampled.first, 0.0001)
        assertEquals(0.0, sampled.second, 0.0001)
        assertEquals(0.0, sampled.third, 0.0001)
    }

    @Test
    fun `the cube table shape is constructor-pinned`() {
        try {
            VideoLutLook("bad", 100, size = 3, domainMin = 0.0, domainMax = 1.0, data = DoubleArray(8))
            throw AssertionError("a mis-sized table must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("size^3"))
        }
    }

    // ---- the 4K/HDR preflight ----

    private fun facts(
        api: Int = 34,
        w: Int = 4096,
        h: Int = 2160,
        hdr: Boolean = true,
    ) = VideoExportPreflight.DeviceFacts(apiLevel = api, maxEncoderWidth = w, maxEncoderHeight = h, hdrDecodePath = hdr)

    @Test
    fun `SDR is always offered`() {
        val verdict = VideoExportPreflight.check(VideoExportPreflight.Tier.SDR, facts(api = 21, w = 0, h = 0, hdr = false))
        assertTrue(verdict is VideoExportPreflight.Verdict.Offered)
    }

    @Test
    fun `4K rides only a UHD-capable encoder`() {
        assertTrue(
            VideoExportPreflight.check(VideoExportPreflight.Tier.UHD_SDR, facts()) is
                VideoExportPreflight.Verdict.Offered,
        )
        val weak = VideoExportPreflight.check(VideoExportPreflight.Tier.UHD_SDR, facts(w = 1920, h = 1080))
        assertTrue(weak is VideoExportPreflight.Verdict.Refused)
        assertTrue(
            (weak as VideoExportPreflight.Verdict.Refused).reason.contains("UHD-capable encoder"),
        )
    }

    @Test
    fun `HDR10 requires the API-33 gate - the plan doc's own rule`() {
        val old = VideoExportPreflight.check(VideoExportPreflight.Tier.HDR10, facts(api = 31))
        assertTrue(old is VideoExportPreflight.Verdict.Refused)
        assertTrue(
            (old as VideoExportPreflight.Verdict.Refused).reason.contains("Android 33+"),
        )
    }

    @Test
    fun `HDR10 without an HDR decode path is refused - never silent SDR`() {
        val noHdr = VideoExportPreflight.check(VideoExportPreflight.Tier.HDR10, facts(hdr = false))
        assertTrue(noHdr is VideoExportPreflight.Verdict.Refused)
        assertTrue(
            (noHdr as VideoExportPreflight.Verdict.Refused).reason.contains("SDR wearing an HDR badge"),
        )
    }

    @Test
    fun `a capable device is offered HDR10`() {
        assertTrue(
            VideoExportPreflight.check(VideoExportPreflight.Tier.HDR10, facts()) is
                VideoExportPreflight.Verdict.Offered,
        )
    }
}
