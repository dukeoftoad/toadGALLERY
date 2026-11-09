package com.toadgallery.gallery.metadata.edit

/**
 * The Windows Photo rating convention (the mapping between user-facing
 * stars and the two EXIF tags Windows writes), pinned as pure math so
 * later rounds (and the rating display, when the read round reaches it)
 * share one tested truth.
 *
 * THE CONVENTION, verified from Microsoft's own property description
 * (https://learn.microsoft.com/en-us/windows/win32/properties/props-system-rating):
 * `System.Rating` is an integer 1..99 where the star bands are
 * 1..12 = 1 star, 13..37 = 2, 38..62 = 3, 63..87 = 4, 88..99 = 5, with
 * the canonical SET values 1, 25, 50, 75 and 99 (0 = unrated). The EXIF
 * `Rating` tag (0x4746) carries the enumerated star count 0..5, and the
 * EXIF `RatingPercent` tag (0x4749) carries the 0..99 value.
 *
 * HONEST SCOPE NOTE (this round): androidx.exifinterface 1.4.1 can
 * NEITHER read NOR write either tag (verified from the pinned sources:
 * both are absent from the library's reading AND writing tag maps, so
 * `getAttribute`/`setAttribute` silently no-op on them). The math below
 * is therefore model+test only --- the capability gate reports the rating
 * field UNSUPPORTED_FIELD and nothing here reaches a file until a later
 * round swaps the pinned library or extends it.
 */
object WindowsRatingMath {

    /** The canonical System.Rating values for 1..5 stars (verified anchors). */
    private val STARS_TO_PERCENT = intArrayOf(0, 1, 25, 50, 75, 99)

    /** The inclusive star-band boundaries of System.Rating, stars 1..5. */
    private val PERCENT_BAND_LOWER = intArrayOf(1, 13, 38, 63, 88)
    private val PERCENT_BAND_UPPER = intArrayOf(12, 37, 62, 87, 99)

    /**
     * Stars (0 = unrated, 1..5) to the canonical RatingPercent value
     * (0, 1, 25, 50, 75, 99). `null` for anything outside 0..5 --- a
     * corrupt star count is refused, never clamped into a band.
     */
    fun starsToRatingPercent(stars: Int): Int? =
        if (stars in 0..5) STARS_TO_PERCENT[stars] else null

    /**
     * RatingPercent (0..99) to stars using the verified bands: 0 = 0
     * (unrated), 1..12 -> 1, 13..37 -> 2, 38..62 -> 3, 63..87 -> 4,
     * 88..99 -> 5. `null` outside 0..99 (System.Rating is undefined at
     * 100+; negatives are corrupt).
     */
    fun ratingPercentToStars(percent: Int): Int? {
        if (percent == 0) return 0
        if (percent < 0 || percent > 99) return null
        for (stars in 5 downTo 1) {
            if (percent >= PERCENT_BAND_LOWER[stars - 1] && percent <= PERCENT_BAND_UPPER[stars - 1]) {
                return stars
            }
        }
        // Unreachable for 1..99 (the five bands tile it exactly); kept total.
        return 0
    }

    /**
     * Stars to the EXIF `Rating` (0x4746) tag value --- the tag value IS
     * the star count. `null` outside 0..5.
     */
    fun starsToRatingTagValue(stars: Int): Int? = stars.takeIf { it in 0..5 }

    /** The EXIF `Rating` (0x4746) tag value back to stars; `null` outside 0..5. */
    fun ratingTagValueToStars(value: Int): Int? = value.takeIf { it in 0..5 }

    /**
     * Joins keywords into the XPKeywords single-string form: semicolon-
     * separated, surrounding whitespace trimmed, blank entries dropped.
     * Model-only this round (the tag is unwritable through the pinned
     * library --- see the class KDoc); the separator is the Windows
     * Property System's documented list convention for keyword strings,
     * kept here so the model and any future writer agree.
     */
    fun joinKeywords(keywords: List<String>): String =
        keywords.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(";")

    /** Splits an XPKeywords string back into trimmed, non-blank keywords. */
    fun splitKeywords(text: String): List<String> =
        text.split(';').map { it.trim() }.filter { it.isNotEmpty() }
}
