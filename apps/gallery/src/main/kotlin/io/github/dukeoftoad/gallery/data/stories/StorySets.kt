package io.github.dukeoftoad.gallery.data.stories

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One auto-generated story of the r27 Stories shelf: a RECENT TIME
 * CLUSTER of the timeline --- a run of items whose consecutive effective
 * instants never gap more than [StorySets.STORY_GAP_MILLIS] apart ---
 * with at least [StorySets.MIN_STORY_ITEMS] members.
 *
 * Derived and zero-persistence by construction (the r17
 * [io.github.dukeoftoad.gallery.data.filters.DerivedCollections]
 * doctrine): a story is nothing but a named slice of the CURRENT
 * timeline order; nothing is stored, and the next derivation recomputes
 * it from whatever the catalogue says then.
 *
 * [memberIndexes] are ascending indexes into the input list (the
 * timeline's newest-first order), so [coverIndex] --- the first member
 * --- is the story's NEWEST photo (the shelf's cover card), and playback
 * hands the members to the existing viewer in the SAME order the
 * timeline shows them (no silent reordering between surfaces).
 */
data class StorySet(
    /** Stable LazyLayout key: the cluster's newest member's epoch day. */
    val id: String,
    /** The story's OLDEST member's calendar day (the range's start). */
    val startEpochDay: Long,
    /** The story's NEWEST member's calendar day (the range's end). */
    val endEpochDay: Long,
    /** Ascending indexes into the input list. */
    val memberIndexes: List<Int>,
) {
    /** How many items the story plays. */
    val count: Int get() = memberIndexes.size

    /** The shelf's cover: the newest member (see the class KDoc). */
    val coverIndex: Int get() = memberIndexes.first()
}

/**
 * The Stories shelf's pure engine (r27): turns an ordered (newest-first)
 * item list into the recent time clusters worth playing.
 *
 * WHY GAP CLUSTERING (and not "one story per day"): the roadmap's story
 * is a "short auto-generated highlight reel per day/event"; a rigid
 * per-day cut would split one evening across a midnight into two
 * half-stories and glue unrelated weeks into one "story" whenever a
 * library has exactly one photo per day. A GAP rule matches how people
 * actually shoot: bursts with minutes-to-hours between frames, and clear
 * silence between events. 36 hours (a long weekend day plus travel
 * either side, still well under "two separate occasions") is the v1 gap,
 * frozen as [STORY_GAP_MILLIS] and pinned by tests.
 *
 * The three honest cuts, all pure and all pinned by [StorySetsTest]:
 *  1. **Min size**: clusters with fewer than [MIN_STORY_ITEMS] members
 *     are not stories (a single photo is not a highlight reel; two is a
 *     pair, not a moment);
 *  2. **Count cap**: at most [MAX_STORIES], the MOST RECENT ones (the
 *     shelf is "recent highlights", not "every trip since 2009" --- the
 *     timeline below already covers history);
 *  3. **Order**: stories in input order (newest first), members in input
 *     order --- deterministic for a given input, recomputed reactively
 *     as the paging snapshot grows.
 *
 * The date math interprets each item's effective instant (the caller's
 * [instantOf], the r13 `captureDateMillis ?: dateAddedSec * 1000` rule
 * in production) in [zone]; the story's [StorySet.startEpochDay] /
 * [StorySet.endEpochDay] are CALENDAR days, so a story that spans a
 * midnight in the user's zone labels both days honestly.
 */
object StorySets {

    /**
     * The v1 story gap: consecutive items more than this apart (epoch
     * millis) start a new cluster. 36 hours.
     */
    const val STORY_GAP_MILLIS: Long = 36L * 60 * 60 * 1000

    /** Fewer members than this and a cluster is not a story. */
    const val MIN_STORY_ITEMS: Int = 3

    /** The shelf shows at most this many stories (the most recent ones). */
    const val MAX_STORIES: Int = 8

    /**
     * Derives the story sets of [items] (the timeline's newest-first
     * order; nulls are not-yet-loaded paging placeholders and join no
     * cluster), interpreting each item's effective instant from
     * [instantOf] in [zone].
     */
    fun <T> stories(
        items: List<T?>,
        instantOf: (T) -> Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<StorySet> {
        if (items.isEmpty()) return emptyList()
        val clusters = ArrayList<MutableList<Int>>(4)
        var current: MutableList<Int>? = null
        var previousInstant = 0L
        for (index in items.indices) {
            val item = items[index] ?: continue
            val instant = instantOf(item)
            val members = current
            if (members != null && instant >= previousInstant - STORY_GAP_MILLIS &&
                instant <= previousInstant + STORY_GAP_MILLIS
            ) {
                // Within the gap of the previous member: same cluster.
                // (The two-sided compare keeps a chronologically ascending
                // input honest too, though production input is newest-first
                // and therefore descending.)
                members.add(index)
            } else {
                members?.let { clusters.add(it) }
                current = mutableListOf(index)
            }
            previousInstant = instant
        }
        current?.let { clusters.add(it) }
        return clusters.asSequence()
            .filter { it.size >= MIN_STORY_ITEMS }
            .take(MAX_STORIES)
            .map { members ->
                val days = members.map { index ->
                    Instant.ofEpochMilli(instantOf(items[index]!!)).atZone(zone).toLocalDate()
                }
                StorySet(
                    id = "s" + days.max().toEpochDay(),
                    startEpochDay = days.min().toEpochDay(),
                    endEpochDay = days.max().toEpochDay(),
                    memberIndexes = members,
                )
            }
            .toList()
    }

    /**
     * The shelf card's date-range label: `"5 March 2026"` when the story
     * spans one calendar day, `"5 – 9 March 2026"` when it spans several
     * (both ends formatted as full localized dates, the timeline header
     * vocabulary; pre-1970 dates flow through like any other).
     */
    fun rangeLabel(story: StorySet, locale: Locale): String {
        val start = LocalDate.ofEpochDay(story.startEpochDay)
        val end = LocalDate.ofEpochDay(story.endEpochDay)
        val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
        return if (start == end) {
            start.format(formatter)
        } else {
            start.format(formatter) + " – " + end.format(formatter)
        }
    }
}
