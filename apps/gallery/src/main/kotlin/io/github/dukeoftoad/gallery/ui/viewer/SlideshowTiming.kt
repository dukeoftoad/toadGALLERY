package io.github.dukeoftoad.gallery.ui.viewer

/**
 * The slideshow's pure timing core (r27, the MI-style organisational UX
 * round): the configurable auto-advance interval catalog plus the
 * wrap-around page math, JVM-tested without the Android runtime
 * (ADR-0016) while the coroutine that sleeps-and-advances stays a thin
 * seam inside `ViewerScreen`.
 *
 * ## The interval vocabulary
 * Three fixed intervals (3 / 5 / 10 seconds), the slideshow vocabulary
 * every gallery app ships; [DEFAULT_INTERVAL_MILLIS] is 5 seconds.
 * [coerceInterval] snaps any value to the catalog: an exact option
 * round-trips; a value between two options snaps to the NEAREST (a tie
 * snaps to the SHORTER option --- an impatient user's mistake should
 * cost less, not more); a non-positive or absurd value yields the
 * default (never a crash, never a negative sleep).
 *
 * ## The advance math
 * [nextIndex] is the wrap-around rule: the slideshow LOOPS --- after the
 * last page it returns to the first (a slideshow that stops dead at the
 * end strands the user mid-story; looping is the gallery convention,
 * stated in the screen's KDoc). Degenerate inputs (zero/negative counts)
 * answer 0 --- the only page a one-page window has, and the honest
 * no-op for an empty one.
 *
 * Pure Kotlin, JVM-tested ([SlideshowTimingTest]) per ADR-0016.
 */
object SlideshowTiming {

    /** The selectable auto-advance intervals, ascending, in milliseconds. */
    val INTERVALS_MILLIS: List<Long> = listOf(3_000L, 5_000L, 10_000L)

    /** The default interval: 5 seconds. */
    const val DEFAULT_INTERVAL_MILLIS: Long = 5_000L

    /**
     * Snaps [millis] to the interval catalog: exact match round-trips;
     * the nearest option wins (ties to the shorter); anything
     * non-positive (or a catalog-less degenerate) yields the default.
     */
    fun coerceInterval(millis: Long): Long {
        if (millis <= 0L) return DEFAULT_INTERVAL_MILLIS
        var best = INTERVALS_MILLIS.first()
        var bestDistance = Long.MAX_VALUE
        for (option in INTERVALS_MILLIS) {
            val distance = kotlin.math.abs(option - millis)
            // Strict < keeps the EARLIER (shorter) option on a tie.
            if (distance < bestDistance) {
                bestDistance = distance
                best = option
            }
        }
        return best
    }

    /**
     * The page the slideshow advances to from [currentIndex] in a window
     * of [pageCount] pages: the next page, wrapping to 0 after the last.
     * Counts of 1 or fewer answer 0 (nothing to advance).
     */
    fun nextIndex(currentIndex: Int, pageCount: Int): Int {
        if (pageCount <= 1) return 0
        val next = currentIndex + 1
        return if (next >= pageCount) 0 else next
    }
}

/**
 * The slideshow's crossfade math (r27): the per-page alpha of a simple
 * fade transition --- the viewer's own transition rule, pure so its
 * shape is pinned by JVM tests rather than living implicitly inside a
 * graphics layer lambda.
 *
 * THE RULE: while the pager settles from one page to the next, each
 * visible page's alpha falls linearly with its distance from the SETTLING
 * position --- the page being left fades OUT as the page being entered
 * fades IN, the classic gallery crossfade. [pageAlpha] takes the page
 * index and the pager's current FLOAT position (`currentPage +
 * currentPageOffsetFraction`): the settled page (distance 0) is fully
 * opaque; a page one full swipe away (distance >= 1) is fully
 * transparent; between them the alpha is the complement of the distance.
 * Distances beyond 1 clamp to 0 (never negative --- a negative alpha is
 * an invalid layer property).
 *
 * Applied ONLY while the slideshow runs (the plain swipe keeps the
 * pager's stock slide); Compose animations only, no new dependencies
 * (the round's rule): the pager itself produces the position sweep via
 * `animateScrollToPage`, and this function turns that sweep into alphas.
 */
object ViewerCrossfade {

    /**
     * The crossfade alpha of page [pageIndex] at the pager's float
     * [currentPosition] (see the object KDoc for the rule).
     */
    fun pageAlpha(pageIndex: Int, currentPosition: Float): Float {
        val distance = kotlin.math.abs(currentPosition - pageIndex)
        return (1f - distance).coerceIn(0f, 1f)
    }
}
