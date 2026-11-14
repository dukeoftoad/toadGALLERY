package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Animation-export plan rules: bounds, frame-count derivation, timestamp walk, byte-budget preflight.
 */
class AnimationExportPlanTest {

    private fun plan(
        durationMs: Long = 10_000,
        startMs: Long = 1_000,
        endMs: Long = 6_000,
        width: Int = 480,
        fps: Int = 10,
        format: AnimationFormat = AnimationFormat.GIF,
    ) = AnimationExportPlan(
        durationMs = durationMs,
        startMs = startMs,
        endMs = endMs,
        targetWidthPx = width,
        fps = fps,
        format = format,
    )

    // ---- the bounds ----

    @Test
    fun `an in-bounds plan is valid`() {
        assertTrue(
            AnimationExportPlanValidator.validate(plan()) is AnimationExportValidation.Valid,
        )
    }

    @Test
    fun `the range must be positive and inside the clip`() {
        val zeroLength = AnimationExportPlanValidator.validate(plan(startMs = 3_000, endMs = 3_000))
        assertTrue(zeroLength is AnimationExportValidation.Invalid)
        assertTrue(
            (zeroLength as AnimationExportValidation.Invalid).reason.contains("positive"),
        )

        val backwards = AnimationExportPlanValidator.validate(plan(startMs = 4_000, endMs = 2_000))
        assertTrue(backwards is AnimationExportValidation.Invalid)

        val pastEnd = AnimationExportPlanValidator.validate(plan(endMs = 10_001))
        assertTrue(pastEnd is AnimationExportValidation.Invalid)
        assertTrue(
            (pastEnd as AnimationExportValidation.Invalid).reason.contains("past the clip duration"),
        )

        val beforeStart = AnimationExportPlanValidator.validate(plan(startMs = -1))
        assertTrue(beforeStart is AnimationExportValidation.Invalid)
    }

    @Test
    fun `the width bounds are enforced at the round-pinned caps`() {
        assertEquals(1280, AnimationCaps.MAX_TARGET_WIDTH_PX)
        assertEquals(64, AnimationCaps.MIN_TARGET_WIDTH_PX)
        assertEquals(30, AnimationCaps.MAX_FPS)
        assertEquals(2, AnimationCaps.MIN_FPS)
        assertEquals(32L * 1024 * 1024, AnimationCaps.MAX_OUTPUT_BYTES)

        assertTrue(
            AnimationExportPlanValidator.validate(plan(width = 1280)) is AnimationExportValidation.Valid,
        )
        val tooWide = AnimationExportPlanValidator.validate(plan(width = 1281))
        assertTrue(tooWide is AnimationExportValidation.Invalid)
        assertTrue(
            (tooWide as AnimationExportValidation.Invalid).reason.contains("width"),
        )
        val tooNarrow = AnimationExportPlanValidator.validate(plan(width = 63))
        assertTrue(tooNarrow is AnimationExportValidation.Invalid)
    }

    @Test
    fun `the fps bounds are enforced`() {
        assertTrue(
            AnimationExportPlanValidator.validate(plan(fps = 30)) is AnimationExportValidation.Valid,
        )
        assertTrue(
            AnimationExportPlanValidator.validate(plan(fps = 2)) is AnimationExportValidation.Valid,
        )
        for (fps in listOf(1, 31, 0, -5)) {
            val result = AnimationExportPlanValidator.validate(plan(fps = fps))
            assertTrue("fps $fps must be refused", result is AnimationExportValidation.Invalid)
        }
    }

    // ---- the frame-count derivation ----

    @Test
    fun `the frame count is the range times the fps, rounded`() {
        // 5s at 10fps = 50 frames
        assertEquals(50, AnimationExportPlanValidator.frameCount(plan(), null))
        // 5s at 3fps = 15 frames
        assertEquals(15, AnimationExportPlanValidator.frameCount(plan(fps = 3), null))
        // 1s at 30fps = 30 frames
        assertEquals(
            30,
            AnimationExportPlanValidator.frameCount(plan(startMs = 0, endMs = 1_000, fps = 30), null),
        )
        // 100ms at 10fps = 1 frame (rounded)
        assertEquals(
            1,
            AnimationExportPlanValidator.frameCount(plan(startMs = 0, endMs = 100), null),
        )
    }

    @Test
    fun `a tiny range always yields at least one frame`() {
        assertEquals(
            1,
            AnimationExportPlanValidator.frameCount(plan(startMs = 0, endMs = 10), null),
        )
    }

    @Test
    fun `the requested fps is deduplicated against a known source rate`() {
        // 24fps source, 30 requested -> 24 effective
        assertEquals(
            24.0,
            AnimationExportPlanValidator.effectiveFps(30, 24.0),
            1e-9,
        )
        // 60fps source, 15 requested -> 15 effective
        assertEquals(
            15.0,
            AnimationExportPlanValidator.effectiveFps(15, 60.0),
            1e-9,
        )
        // unknown source -> requested rides
        assertEquals(
            30.0,
            AnimationExportPlanValidator.effectiveFps(30, null),
            1e-9,
        )
        assertEquals(
            30.0,
            AnimationExportPlanValidator.effectiveFps(30, 0.0),
            1e-9,
        )
        assertEquals(
            30.0,
            AnimationExportPlanValidator.effectiveFps(30, -1.0),
            1e-9,
        )
        // the frame count uses the deduplicated rate: 5s x 24 = 120, not 150
        assertEquals(
            120,
            AnimationExportPlanValidator.frameCount(plan(fps = 30), 24.0),
        )
    }

    @Test
    fun `the timestamp walk covers the range at the effective rate`() {
        val timestamps = AnimationExportPlanValidator.frameTimestampsMs(
            plan(startMs = 0, endMs = 1_000, fps = 10),
            null,
        )
        assertEquals(listOf(0L, 100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L), timestamps)
        assertTrue(timestamps.all { it in 0..1_000 })
    }

    // ---- the byte-budget preflight ----

    @Test
    fun `an in-budget plan proceeds with the derived facts`() {
        val outcome = AnimationPreflight.preflight(
            plan = plan(width = 320, fps = 10),
            sourceFrameRateFps = null,
            frameHeightPx = 240,
            webpLosslessSupported = true,
        )
        assertTrue(outcome is AnimationPreflight.Outcome.Proceed)
        val proceed = outcome as AnimationPreflight.Outcome.Proceed
        assertEquals(50, proceed.frameCount)
        assertEquals(50L * 320 * 240, proceed.estimatedBytes)
    }

    @Test
    fun `an over-budget plan is refused BEFORE any render`() {
        // 5s x 30fps at 1280x720 gif: 150 x 921600 bytes = ~131 MiB > 32 MiB
        val outcome = AnimationPreflight.preflight(
            plan = plan(width = 1280, fps = 30),
            sourceFrameRateFps = null,
            frameHeightPx = 720,
            webpLosslessSupported = true,
        )
        assertTrue(outcome is AnimationPreflight.Outcome.Refusal)
        val refusal = (outcome as AnimationPreflight.Outcome.Refusal).reason
        assertTrue(refusal.contains("budget"))
        assertTrue(refusal.contains("shorten the range"))
    }

    @Test
    fun `the webp platform gate refuses below the encoder's android version`() {
        val outcome = AnimationPreflight.preflight(
            plan = plan(format = AnimationFormat.WEBP),
            sourceFrameRateFps = null,
            frameHeightPx = 360,
            webpLosslessSupported = false,
        )
        assertTrue(outcome is AnimationPreflight.Outcome.Refusal)
        assertTrue(
            (outcome as AnimationPreflight.Outcome.Refusal).reason.contains("Android 11"),
        )
    }

    @Test
    fun `webp proceeds on a capable platform with the heavier byte model`() {
        // the webp model doubles the gif model per frame
        val gif = AnimationPreflight.preflight(
            plan(width = 320, fps = 10, format = AnimationFormat.GIF),
            null, 240, true,
        ) as AnimationPreflight.Outcome.Proceed
        val webp = AnimationPreflight.preflight(
            plan(width = 320, fps = 10, format = AnimationFormat.WEBP),
            null, 240, true,
        ) as AnimationPreflight.Outcome.Proceed
        assertEquals(2 * gif.estimatedBytes, webp.estimatedBytes)
    }

    @Test
    fun `an invalid plan is refused by the preflight itself`() {
        val outcome = AnimationPreflight.preflight(
            plan = plan(width = 5000),
            sourceFrameRateFps = null,
            frameHeightPx = 240,
            webpLosslessSupported = true,
        )
        assertTrue(outcome is AnimationPreflight.Outcome.Refusal)
    }

    @Test
    fun `an underivable frame height is refused honestly`() {
        val outcome = AnimationPreflight.preflight(
            plan = plan(),
            sourceFrameRateFps = null,
            frameHeightPx = 0,
            webpLosslessSupported = true,
        )
        assertTrue(outcome is AnimationPreflight.Outcome.Refusal)
        assertTrue(
            (outcome as AnimationPreflight.Outcome.Refusal).reason.contains("frame height"),
        )
    }

    @Test
    fun `the honest animation format set is gif and webp`() {
        assertEquals("image/gif", AnimationFormat.GIF.mimeType)
        assertEquals("gif", AnimationFormat.GIF.extension)
        assertEquals("image/webp", AnimationFormat.WEBP.mimeType)
        assertEquals("webp", AnimationFormat.WEBP.extension)
    }
}
