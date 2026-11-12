package com.toadgallery.editor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The r74 text-overlay model's JVM pins (ADR-0011): every rule the
 * validator states and the envelope math derives, over hand-composed
 * cases only --- no Android, no media3.
 */
class VideoTextOverlayTest {

    private fun overlay(
        id: String = "cap-1",
        text: String = "golden hour",
        startMs: Long = 1_000,
        endMs: Long = 4_000,
        fadeInMs: Int = 0,
        fadeOutMs: Int = 0,
        opacity: Int = 100,
    ) = VideoTextOverlay(
        id = id,
        text = text,
        fontId = "manrope",
        startMs = startMs,
        endMs = endMs,
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        opacity = opacity,
    )

    // ---- the validator's rules ----

    @Test
    fun `an empty list is valid`() {
        val result = VideoTextOverlayValidator.validate(emptyList(), 10_000)
        assertTrue(result is VideoTextOverlayValidation.Valid)
    }

    @Test
    fun `an overlay past the timeline is refused with the honest reason`() {
        val result = VideoTextOverlayValidator.validate(listOf(overlay(endMs = 11_000)), 10_000)
        assertTrue(result is VideoTextOverlayValidation.Invalid)
        assertTrue(
            (result as VideoTextOverlayValidation.Invalid).reason.contains("past the timeline"),
        )
    }

    @Test
    fun `an overlay before the timeline start is refused`() {
        val result = VideoTextOverlayValidator.validate(
            listOf(overlay(startMs = -500, endMs = 1_000)),
            10_000,
        )
        assertTrue(result is VideoTextOverlayValidation.Invalid)
        assertTrue(
            (result as VideoTextOverlayValidation.Invalid).reason.contains("before the timeline start"),
        )
    }

    @Test
    fun `duplicate ids are refused`() {
        val result = VideoTextOverlayValidator.validate(
            listOf(overlay(id = "cap"), overlay(id = "cap", startMs = 5_000, endMs = 6_000)),
            10_000,
        )
        assertTrue(result is VideoTextOverlayValidation.Invalid)
        assertTrue(
            (result as VideoTextOverlayValidation.Invalid).reason.contains("appears twice"),
        )
    }

    @Test
    fun `overlapping fade legs inside a short span are refused`() {
        // 100 ms span, 60+60 ms legs: they overlap.
        val result = VideoTextOverlayValidator.validate(
            listOf(overlay(startMs = 0, endMs = 100, fadeInMs = 60, fadeOutMs = 60)),
            10_000,
        )
        assertTrue(result is VideoTextOverlayValidation.Invalid)
        assertTrue(
            (result as VideoTextOverlayValidation.Invalid).reason.contains("fade legs"),
        )
    }

    @Test
    fun `legs that fit the span are valid`() {
        val result = VideoTextOverlayValidator.validate(
            listOf(overlay(fadeInMs = 100, fadeOutMs = 100)),
            10_000,
        )
        assertTrue(result is VideoTextOverlayValidation.Valid)
    }

    @Test
    fun `overlapping captions are allowed - a timeline is not a photo`() {
        val result = VideoTextOverlayValidator.validate(
            listOf(
                overlay(id = "a", startMs = 0, endMs = 2_000),
                overlay(id = "b", startMs = 1_000, endMs = 3_000),
            ),
            10_000,
        )
        assertTrue(result is VideoTextOverlayValidation.Valid)
    }

    // ---- the tolerance constructor ----

    @Test
    fun `the of-factory trims text to the cap and coerces numerics`() {
        val built = VideoTextOverlay.of(
            id = "cap",
            text = "x".repeat(500),
            fontId = "f",
            startMs = 0,
            endMs = -5, // degenerate: coerced to start+1
            sizePct = 999,
            opacity = 200,
            rotationDeg = 400,
        )
        assertEquals(VideoTextOverlay.MAX_TEXT_LENGTH, built.text.length)
        assertEquals(VideoTextOverlay.MAX_SIZE_PCT, built.sizePct)
        assertEquals(100, built.opacity)
        assertEquals(180, built.rotationDeg)
        assertEquals(1L, built.endMs)
    }

    // ---- the envelope math ----

    @Test
    fun `opacity outside the span is null - not a forced zero`() {
        assertNull(VideoTextOverlayMath.opacityAt(overlay(), 999))
        assertNull(VideoTextOverlayMath.opacityAt(overlay(), 4_000))
    }

    @Test
    fun `a flat span holds the opacity through the middle`() {
        assertEquals(100, VideoTextOverlayMath.opacityAt(overlay(), 1_000))
        assertEquals(100, VideoTextOverlayMath.opacityAt(overlay(), 2_500))
        assertEquals(100, VideoTextOverlayMath.opacityAt(overlay(), 3_999))
    }

    @Test
    fun `the fade-in leg ramps linearly`() {
        val o = overlay(fadeInMs = 1_000)
        assertEquals(0, VideoTextOverlayMath.opacityAt(o, 1_000))
        assertEquals(50, VideoTextOverlayMath.opacityAt(o, 1_500))
        assertEquals(99, VideoTextOverlayMath.opacityAt(o, 1_990))
        assertEquals(100, VideoTextOverlayMath.opacityAt(o, 2_000))
    }

    @Test
    fun `the fade-out leg ramps linearly to the span end`() {
        val o = overlay(fadeOutMs = 1_000) // 1000..4000, out from 3000
        assertEquals(100, VideoTextOverlayMath.opacityAt(o, 3_000))
        assertEquals(50, VideoTextOverlayMath.opacityAt(o, 3_500))
        assertEquals(1, VideoTextOverlayMath.opacityAt(o, 3_990))
    }

    @Test
    fun `a partial-opacity overlay peaks at its own opacity`() {
        val o = overlay(opacity = 40, fadeInMs = 500, fadeOutMs = 500)
        assertEquals(40, VideoTextOverlayMath.opacityAt(o, 2_500))
        assertEquals(0, VideoTextOverlayMath.opacityAt(o, 1_000))
        assertEquals(20, VideoTextOverlayMath.opacityAt(o, 1_250))
    }
}
