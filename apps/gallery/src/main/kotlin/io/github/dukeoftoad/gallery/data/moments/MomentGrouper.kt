package io.github.dukeoftoad.gallery.data.moments

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * One grouped "moment" section of the timeline (r27, the MI-style
 * organisational UX round): a capture-date cluster at DAY or WEEK
 * granularity --- every item whose EFFECTIVE instant falls on the same
 * calendar day (or the same ISO-8601 week) belongs to one section.
 *
 * [groupKey] mirrors the timeline row keys ("d<epochDay>" for days,
 * "w<weekBasedYear>-<week>" for weeks) so a section can key LazyLayout
 * slots exactly like [io.github.dukeoftoad.gallery.ui.media
 * .TimelineRow.Header] does; [anchorEpochDay] is the section's LABEL date
 * (the day itself for DAY granularity, the week's MONDAY for WEEK ---
 * the same first-day-of-period rule the month groups' headers use);
 * [memberIndexes] are ascending indexes into the INPUT list.
 *
 * Pure data (no Android imports) --- produced only by [MomentGrouper].
 */
data class MomentSection(
    /** The stable key suffix: `"d<epochDay>"` / `"w<year>-<week>"`. */
    val groupKey: String,
    /** The label date as an epoch day (the week's Monday for WEEK). */
    val anchorEpochDay: Long,
    /** This section's granularity; drives the label pattern. */
    val granularity: MomentGranularity,
    /** Ascending indexes into the input list. */
    val memberIndexes: List<Int>,
) {
    /** How many items the section holds; drives the header's count. */
    val count: Int get() = memberIndexes.size
}

/**
 * The moment-grouping engine of the r27 Moments timeline: capture-date
 * clusters at DAY or WEEK granularity, one pure generic pass over an
 * ordered item list.
 *
 * THE PATTERN (per the round contract): this class EXTENDS the
 * TimelineSort/TimelineRows discipline rather than replacing it ---
 * [io.github.dukeoftoad.gallery.ui.media.buildTimelineRows]'s WEEK branch
 * is a thin projection of [group]'s WEEK output onto
 * [io.github.dukeoftoad.gallery.ui.media.TimelineRow]s, and the DAY
 * branch of the same function keeps its own streaming implementation
 * UNTOUCHED (its behavior is pinned by the pre-r27 tests; the
 * equivalence test below pins that [group] at DAY granularity agrees
 * with it on shared inputs, so the two can never drift apart silently).
 *
 * Input contract (the same one `buildTimelineRows` holds):
 *  - the list is the timeline's OWN order (newest-first in production);
 *    [group] never re-sorts, so sections come out in the input's order
 *    and each section's members stay in the input's relative order;
 *  - NULL entries (not-yet-loaded paging placeholders) are skipped
 *    entirely: they join no section and never split one;
 *  - the effective instant is supplied by the caller's [instantOf]
 *    (production: `captureDateMillis ?: dateAddedSec * 1000`, the r13
 *    rule) and is interpreted in [zone] --- a Sunday-night photo and a
 *    Monday-morning photo are different DAY sections in UTC but the same
 *    WEEK section everywhere, and a Sunday/Monday pair splits or joins
 *    by the zone the user's device actually sits in;
 *  - NEGATIVE instants are legitimate (pre-1970 scanned archives) and
 *    flow through the date math like any other instant.
 *
 * WEEK granularity uses ISO-8601 weeks (Monday first day, minimal four
 * days in the first week): the section key carries the WEEK-BASED year
 * (a 2 January can belong to week 53 of the previous year, and keying
 * that photo "2027-w1" would be a lie the label would repeat), and the
 * anchor is the week's Monday.
 *
 * Pure Kotlin, JVM-tested ([MomentGrouperTest]) per ADR-0016.
 */
object MomentGrouper {

    /**
     * Groups [items] into moment sections at [granularity], interpreting
     * each item's effective instant (from [instantOf]) in [zone].
     */
    fun <T> group(
        items: List<T?>,
        instantOf: (T) -> Long,
        granularity: MomentGranularity,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MomentSection> {
        if (items.isEmpty()) return emptyList()
        val sections = ArrayList<MomentSection>()
        var currentKey: String? = null
        var currentAnchor = 0L
        var currentMembers: MutableList<Int>? = null
        for (index in items.indices) {
            val item = items[index] ?: continue
            val date = Instant.ofEpochMilli(instantOf(item)).atZone(zone).toLocalDate()
            val key: String
            val anchor: Long
            when (granularity) {
                MomentGranularity.DAY -> {
                    anchor = date.toEpochDay()
                    key = "d$anchor"
                }
                MomentGranularity.WEEK -> {
                    // The Monday of this date's ISO week: ISO weeks run
                    // Monday..Sunday, so previousOrSame(MONDAY) is always
                    // inside the same week-of-week-based-year.
                    val monday = date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
                    anchor = monday.toEpochDay()
                    val weekFields = java.time.temporal.WeekFields.ISO
                    val weekYear = monday.get(weekFields.weekBasedYear())
                    val week = monday.get(weekFields.weekOfWeekBasedYear())
                    key = "w$weekYear-$week"
                }
            }
            val members = currentMembers
            if (key == currentKey && members != null) {
                members.add(index)
            } else {
                // Close the finished section (if any) and open the next.
                members?.let {
                    sections.add(
                        MomentSection(
                            groupKey = currentKey!!,
                            anchorEpochDay = currentAnchor,
                            granularity = granularity,
                            memberIndexes = it,
                        )
                    )
                }
                currentKey = key
                currentAnchor = anchor
                currentMembers = mutableListOf(index)
            }
        }
        currentMembers?.let {
            sections.add(
                MomentSection(
                    groupKey = currentKey!!,
                    anchorEpochDay = currentAnchor,
                    granularity = granularity,
                    memberIndexes = it,
                )
            )
        }
        return sections
    }
}

/**
 * The moment sections' granularity (r27): one section per calendar day,
 * or one per ISO-8601 week. The timeline's existing GroupBy keeps
 * NONE/DAY/MONTH as the persisted setting's vocabulary; WEEK joins it in
 * r27 as the Moments round's new grain, and this enum is the pure
 * grouping engine's own spelling of the two moment grains.
 */
enum class MomentGranularity {
    /** One section per calendar day (in the grouping zone). */
    DAY,

    /** One section per ISO-8601 week (Monday-anchored). */
    WEEK,
}
