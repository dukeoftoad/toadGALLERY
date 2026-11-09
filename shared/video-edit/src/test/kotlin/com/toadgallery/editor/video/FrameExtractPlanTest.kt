package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame-extraction plan rules (r42, decision 5): the timestamp
 * validation and the honest format set.
 */
class FrameExtractPlanTest {

    private fun validate(timestampMs: Long, durationMs: Long = 8_000): FrameExtractValidation =
        FrameExtractPlanValidator.validate(
            FrameExtractPlan(
                durationMs = durationMs,
                timestampMs = timestampMs,
                format = FrameFormat.JPEG,
            ),
        )

    @Test
    fun `timestamps at both ends of the clip are valid`() {
        assertTrue(validate(0) is FrameExtractValidation.Valid)
        assertTrue(validate(8_000) is FrameExtractValidation.Valid)
    }

    @Test
    fun `a timestamp inside the clip is valid`() {
        assertTrue(validate(4_231) is FrameExtractValidation.Valid)
    }

    @Test
    fun `a timestamp before the clip is a structured failure`() {
        val result = validate(-1)
        assertTrue(result is FrameExtractValidation.Invalid)
        assertTrue((result as FrameExtractValidation.Invalid).reason.contains("before the clip start"))
    }

    @Test
    fun `a timestamp past the duration is a structured failure`() {
        val result = validate(8_001)
        assertTrue(result is FrameExtractValidation.Invalid)
        assertTrue((result as FrameExtractValidation.Invalid).reason.contains("past the clip duration"))
    }

    @Test
    fun `a non-positive duration fact is a structured failure`() {
        val result = FrameExtractPlanValidator.validate(
            FrameExtractPlan(durationMs = 0, timestampMs = 0, format = FrameFormat.PNG),
        )
        assertTrue(result is FrameExtractValidation.Invalid)
        assertTrue((result as FrameExtractValidation.Invalid).reason.contains("unreadable source"))
    }

    @Test
    fun `the honest format set is jpeg and png`() {
        assertEquals("image/jpeg", FrameFormat.JPEG.mimeType)
        assertEquals("jpg", FrameFormat.JPEG.extension)
        assertEquals("image/png", FrameFormat.PNG.mimeType)
        assertEquals("png", FrameFormat.PNG.extension)
        assertEquals(2, FrameFormat.entries.size)
    }
}
