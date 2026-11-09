package com.toadgallery.gallery.metadata.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the Windows Photo rating convention (Phase 4 write round;
 * ADR-0016). The bands and canonical values are the verified
 * Microsoft-documented anchors (see [WindowsRatingMath]' KDoc) --- this
 * suite pins them so the model (and the later rounds that make the tags
 * writable) share one tested truth.
 *
 * Scope reminder pinned by the first test pair: androidx.exifinterface
 * 1.4.1 can neither read nor write the Rating/RatingPercent tags, so
 * nothing here reaches a file this round --- the capability gate reports
 * the rating field UNSUPPORTED_FIELD.
 */
class WindowsRatingMathTest {

    // ---- Stars -> RatingPercent (the canonical set values) ----

    @Test
    fun `stars map to the canonical System dot Rating values`() {
        assertEquals(0, WindowsRatingMath.starsToRatingPercent(0))
        assertEquals(1, WindowsRatingMath.starsToRatingPercent(1))
        assertEquals(25, WindowsRatingMath.starsToRatingPercent(2))
        assertEquals(50, WindowsRatingMath.starsToRatingPercent(3))
        assertEquals(75, WindowsRatingMath.starsToRatingPercent(4))
        assertEquals(99, WindowsRatingMath.starsToRatingPercent(5))
    }

    @Test
    fun `stars outside 0 to 5 are refused`() {
        assertNull(WindowsRatingMath.starsToRatingPercent(-1))
        assertNull(WindowsRatingMath.starsToRatingPercent(6))
    }

    // ---- RatingPercent -> stars (the verified bands) ----

    @Test
    fun `rating percent maps through the verified star bands`() {
        // 0 = unrated; 1..12 = 1 star; 13..37 = 2; 38..62 = 3;
        // 63..87 = 4; 88..99 = 5 (every band boundary pinned).
        assertEquals(0, WindowsRatingMath.ratingPercentToStars(0))
        assertEquals(1, WindowsRatingMath.ratingPercentToStars(1))
        assertEquals(1, WindowsRatingMath.ratingPercentToStars(12))
        assertEquals(2, WindowsRatingMath.ratingPercentToStars(13))
        assertEquals(2, WindowsRatingMath.ratingPercentToStars(37))
        assertEquals(3, WindowsRatingMath.ratingPercentToStars(38))
        assertEquals(3, WindowsRatingMath.ratingPercentToStars(62))
        assertEquals(4, WindowsRatingMath.ratingPercentToStars(63))
        assertEquals(4, WindowsRatingMath.ratingPercentToStars(87))
        assertEquals(5, WindowsRatingMath.ratingPercentToStars(88))
        assertEquals(5, WindowsRatingMath.ratingPercentToStars(99))
    }

    @Test
    fun `rating percent outside 0 to 99 is refused`() {
        assertNull(WindowsRatingMath.ratingPercentToStars(-1))
        assertNull(WindowsRatingMath.ratingPercentToStars(100))
    }

    @Test
    fun `stars to percent to stars round-trips for every star count`() {
        for (stars in 0..5) {
            // starsToRatingPercent is Int? (out-of-range stars are refused);
            // inside 0..5 it is never null, so the round-trip is total.
            val percent = requireNotNull(WindowsRatingMath.starsToRatingPercent(stars))
            assertEquals(stars, WindowsRatingMath.ratingPercentToStars(percent))
        }
    }

    // ---- The EXIF Rating (0x4746) tag value ----

    @Test
    fun `the rating tag value IS the star count`() {
        for (stars in 0..5) {
            assertEquals(stars, WindowsRatingMath.starsToRatingTagValue(stars))
            assertEquals(stars, WindowsRatingMath.ratingTagValueToStars(stars))
        }
        assertNull(WindowsRatingMath.starsToRatingTagValue(-1))
        assertNull(WindowsRatingMath.starsToRatingTagValue(7))
        assertNull(WindowsRatingMath.ratingTagValueToStars(6))
    }

    // ---- XPKeywords string form (model-only this round) ----

    @Test
    fun `keywords join trims and drops blanks with the Windows list separator`() {
        assertEquals("a;b;c", WindowsRatingMath.joinKeywords(listOf(" a ", "", "b", "  ", "c")))
        assertEquals("", WindowsRatingMath.joinKeywords(emptyList()))
        assertEquals("", WindowsRatingMath.joinKeywords(listOf(" ", "")))
    }

    @Test
    fun `keywords split trims and drops blanks and round-trips`() {
        assertEquals(
            listOf("a", "b", "c"),
            WindowsRatingMath.splitKeywords(" a ; b;;c ;"),
        )
        assertEquals(emptyList<String>(), WindowsRatingMath.splitKeywords(";; ;"))
        // A list built by join parses back unchanged for sane input.
        val keywords = listOf("holiday", "2024", "beach")
        assertEquals(keywords, WindowsRatingMath.splitKeywords(WindowsRatingMath.joinKeywords(keywords)))
    }
}
