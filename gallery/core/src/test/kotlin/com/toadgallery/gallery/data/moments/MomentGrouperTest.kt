package com.toadgallery.gallery.data.moments

import com.toadgallery.gallery.ui.media.TimelineRow
import com.toadgallery.gallery.ui.media.buildTimelineRows
import com.toadgallery.gallery.ui.settings.GroupBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * JVM tests for the Moments timeline's grouping engine (r27, the MI-style
 * organisational UX round; ADR-0016 --- [MomentGrouper] is pure Kotlin, so
 * every grouping rule is pinned here instead of living implicitly in the
 * timeline's WEEK projection).
 *
 * What is pinned:
 * - DAY granularity: same-calendar-day runs share one section (keys
 *   `"d<epochDay>"`), sections come out in INPUT order (no re-sorting),
 *   member indexes stay ascending;
 * - WEEK granularity: ISO-8601 weeks (Monday-anchored), the
 *   Sunday-night/Monday-morning boundary the DAY grain splits, the
 *   WEEK-BASED year in the key (a 2 January can be week 53 of the year
 *   before), and the Monday anchor date;
 * - nulls (not-yet-loaded paging placeholders) join no section and never
 *   split one;
 * - negative (pre-1970) instants flow through the date math like any
 *   other;
 * - the zone decides the boundary (the same instant can sit in different
 *   weeks in different zones);
 * - DAY EQUIVALENCE with `buildTimelineRows`'s own DAY branch (the
 *   no-drift pin the class KDoc promises: the WEEK branch is a projection
 *   of this engine, and the DAY branch keeps its streaming
 *   implementation, so the two DAY implementations are pinned to agree).
 *
 * All dates use fixed zones --- never the system default --- so the math
 * is deterministic on any machine. The generic [MomentGrouper.group] is
 * driven with `List<Long?>` epoch-milli stand-ins (no `MediaItem`: it
 * embeds `android.net.Uri`, which a plain JVM test cannot construct).
 */
class MomentGrouperTest {

    /** UTC midnight of [date], as an epoch MILLI. */
    private fun millisOf(date: LocalDate, hour: Int = 0): Long =
        date.atStartOfDay(ZoneOffset.UTC).plusHours(hour.toLong()).toInstant().toEpochMilli()

    /** UTC-millis stand-ins grouped at [granularity]. */
    private fun group(
        items: List<Long?>,
        granularity: MomentGranularity,
        zone: ZoneId = ZoneOffset.UTC,
    ): List<MomentSection> = MomentGrouper.group(items, { it }, granularity, zone)

    // ------------------------------------------------------------------
    // DAY granularity
    // ------------------------------------------------------------------

    @Test
    fun `empty input groups to no sections in either grain`() {
        assertTrue(group(emptyList(), MomentGranularity.DAY).isEmpty())
        assertTrue(group(emptyList(), MomentGranularity.WEEK).isEmpty())
    }

    @Test
    fun `DAY groups a same-day run under one section with ascending indexes`() {
        val day = LocalDate.of(2026, 3, 5)
        val sections = group(
            listOf(millisOf(day, 9), millisOf(day, 12), millisOf(day, 18)),
            MomentGranularity.DAY,
        )
        assertEquals(1, sections.size)
        val section = sections.single()
        assertEquals("d${day.toEpochDay()}", section.groupKey)
        assertEquals(day.toEpochDay(), section.anchorEpochDay)
        assertEquals(MomentGranularity.DAY, section.granularity)
        assertEquals(listOf(0, 1, 2), section.memberIndexes)
        assertEquals(3, section.count)
    }

    @Test
    fun `DAY sections come out in input order without re-sorting`() {
        // Newest-first production order: the later day leads. The grouper
        // never re-sorts, so the sections follow the input's order even
        // when it is not "chronological" in either direction.
        val day1 = LocalDate.of(2026, 3, 5)
        val day2 = LocalDate.of(2026, 3, 6)
        val sections = group(
            listOf(millisOf(day2), millisOf(day1), millisOf(day1)),
            MomentGranularity.DAY,
        )
        assertEquals(2, sections.size)
        assertEquals("d${day2.toEpochDay()}", sections[0].groupKey)
        assertEquals(listOf(0), sections[0].memberIndexes)
        assertEquals("d${day1.toEpochDay()}", sections[1].groupKey)
        assertEquals(listOf(1, 2), sections[1].memberIndexes)
    }

    @Test
    fun `DAY splits when the calendar day changes`() {
        val day1 = LocalDate.of(2026, 3, 5)
        val day2 = LocalDate.of(2026, 3, 6)
        val sections = group(
            listOf(millisOf(day1, 23), millisOf(day2, 0), millisOf(day2, 1)),
            MomentGranularity.DAY,
        )
        assertEquals(2, sections.size)
        assertEquals(listOf(0), sections[0].memberIndexes)
        assertEquals(listOf(1, 2), sections[1].memberIndexes)
    }

    // ------------------------------------------------------------------
    // WEEK granularity: ISO-8601 weeks, Monday-anchored
    // ------------------------------------------------------------------

    @Test
    fun `WEEK groups a Monday-to-Sunday run under one Monday-anchored section`() {
        // 2026-03-02 is a Monday; the ISO week runs Mon 2 .. Sun 8 March.
        val monday = LocalDate.of(2026, 3, 2)
        val saturday = LocalDate.of(2026, 3, 7)
        val sunday = LocalDate.of(2026, 3, 8)
        val sections = group(
            listOf(millisOf(sunday), millisOf(monday), millisOf(saturday)),
            MomentGranularity.WEEK,
        )
        assertEquals(1, sections.size)
        val section = sections.single()
        assertEquals("w2026-10", section.groupKey)
        assertEquals(monday.toEpochDay(), section.anchorEpochDay)
        assertEquals(MomentGranularity.WEEK, section.granularity)
        assertEquals(listOf(0, 1, 2), section.memberIndexes)
    }

    @Test
    fun `WEEK splits Sunday night from the Monday morning after it`() {
        // The DAY grain's canonical hard boundary: Sunday 8 March 23:00 vs
        // Monday 9 March 00:30. The WEEK grain must split them too ---
        // they are in different ISO weeks (10 vs 11), NOT one "moment".
        val sundayNight = LocalDate.of(2026, 3, 8)
        val mondayMorning = LocalDate.of(2026, 3, 9)
        val sections = group(
            // Newest first (production order): Monday leads.
            listOf(millisOf(mondayMorning, 0) + 30 * 60_000L, millisOf(sundayNight, 23)),
            MomentGranularity.WEEK,
        )
        assertEquals(2, sections.size)
        assertEquals("w2026-11", sections[0].groupKey)
        assertEquals(mondayMorning.toEpochDay(), sections[0].anchorEpochDay)
        assertEquals("w2026-10", sections[1].groupKey)
        assertEquals(LocalDate.of(2026, 3, 2).toEpochDay(), sections[1].anchorEpochDay)
    }

    @Test
    fun `WEEK joins Saturday and Sunday the DAY grain would still join`() {
        // Control for the test above: Saturday 7 and Sunday 8 March are
        // the SAME ISO week (10) even at the week's very end.
        val saturday = LocalDate.of(2026, 3, 7)
        val sunday = LocalDate.of(2026, 3, 8)
        val sections = group(
            listOf(millisOf(sunday, 22), millisOf(saturday, 1)),
            MomentGranularity.WEEK,
        )
        assertEquals(1, sections.size)
        assertEquals("w2026-10", sections.single().groupKey)
    }

    @Test
    fun `WEEK keys carry the WEEK-BASED year - a 2 January can be week 53`() {
        // 2027-01-02 is a Saturday of the ISO week Mon 2026-12-28 ..
        // Sun 2027-01-03 --- week 53 of the WEEK-BASED year 2026. Keying
        // that photo "w2027-1" would be a lie the label would repeat
        // (week 1 of 2027 starts Mon 2027-01-04).
        val secondOfJanuary2027 = LocalDate.of(2027, 1, 2)
        val sections = group(listOf(millisOf(secondOfJanuary2027)), MomentGranularity.WEEK)
        assertEquals(1, sections.size)
        assertEquals("w2026-53", sections.single().groupKey)
        assertEquals(LocalDate.of(2026, 12, 28).toEpochDay(), sections.single().anchorEpochDay)
    }

    @Test
    fun `WEEK follows the rendered zone for the boundary instant`() {
        // 2026-03-08T20:00Z is Sunday (week 10) in UTC but already Monday
        // 9 March (week 11) in Asia/Tokyo: the section must follow the
        // zone the user's device actually sits in.
        val instant = millisOf(LocalDate.of(2026, 3, 8), 20)
        val utc = group(listOf(instant), MomentGranularity.WEEK, ZoneOffset.UTC).single()
        val tokyo =
            group(listOf(instant), MomentGranularity.WEEK, ZoneId.of("Asia/Tokyo")).single()
        assertEquals("w2026-10", utc.groupKey)
        assertEquals("w2026-11", tokyo.groupKey)
    }

    // ------------------------------------------------------------------
    // Null placeholders and pre-1970 instants
    // ------------------------------------------------------------------

    @Test
    fun `null placeholders join no section and never split one`() {
        val monday = LocalDate.of(2026, 3, 2)
        val sunday = LocalDate.of(2026, 3, 8)
        val sections = group(
            listOf(millisOf(sunday), null, millisOf(monday), null),
            MomentGranularity.WEEK,
        )
        assertEquals(1, sections.size)
        assertEquals(listOf(0, 2), sections.single().memberIndexes)
    }

    @Test
    fun `a null-only input groups to no sections`() {
        assertTrue(group(listOf<Long?>(null, null), MomentGranularity.DAY).isEmpty())
    }

    @Test
    fun `negative pre-1970 instants flow through the date math`() {
        // A scanned archive photo captured in 1930 (negative epoch millis
        // and epochDay): its own week section, never folded into 2026's.
        val scanned = LocalDate.of(1930, 1, 15)
        val modern = LocalDate.of(2026, 3, 5)
        assertTrue("fixture guard: pre-1970 epochDay is negative", scanned.toEpochDay() < 0)
        val sections = group(
            listOf(millisOf(modern), millisOf(scanned)),
            MomentGranularity.DAY,
        )
        assertEquals(2, sections.size)
        assertEquals("d${scanned.toEpochDay()}", sections[1].groupKey)
        assertEquals(scanned.toEpochDay(), sections[1].anchorEpochDay)
    }

    // ------------------------------------------------------------------
    // DAY EQUIVALENCE with buildTimelineRows' own DAY branch (no drift)
    // ------------------------------------------------------------------

    @Test
    fun `DAY sections agree with buildTimelineRows DAY headers and cells`() {
        // The no-drift pin: the WEEK branch of buildTimelineRows is a
        // projection of THIS engine, while its DAY branch keeps the
        // pre-r27 streaming implementation --- so the two DAY
        // implementations are pinned to produce the same sections on the
        // same inputs (keys, anchors, members), whatever the input order.
        val days = listOf(
            LocalDate.of(2026, 3, 5),
            LocalDate.of(2026, 3, 5),
            LocalDate.of(2026, 3, 4),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 5),
        )
        val items: List<Long?> = listOf(
            millisOf(days[0], 8),
            millisOf(days[1], 20),
            millisOf(days[2], 6),
            millisOf(days[3], 23),
            millisOf(days[4], 12),
        )
        val sections = group(items, MomentGranularity.DAY)
        val rows = buildTimelineRows(items, { it }, GroupBy.DAY, ZoneOffset.UTC)
        // The row list interleaves Header/Cell; fold it back into
        // (key, memberIndexes) pairs and compare with the sections.
        val keys = ArrayList<String>(4)
        val members = ArrayList<MutableList<Int>>(4)
        for (row in rows) {
            when (row) {
                is TimelineRow.Header -> {
                    keys.add(row.groupKey)
                    members.add(mutableListOf())
                }
                is TimelineRow.Cell -> members.last().add(row.index)
            }
        }
        assertEquals(sections.map { it.groupKey }, keys)
        assertEquals(sections.map { it.memberIndexes }, members)
        assertEquals(sections.map { it.anchorEpochDay }, rows.filterIsInstance<TimelineRow.Header>().map { it.epochDay })
    }

    @Test
    fun `WEEK sections agree with buildTimelineRows WEEK headers and cells`() {
        // The same no-drift pin for the projection itself: every WEEK
        // section maps one-to-one onto a WEEK header + its cells, with
        // the same key and the same Monday anchor.
        val monday = LocalDate.of(2026, 3, 2)
        val wednesday = LocalDate.of(2026, 3, 4)
        val nextMonday = LocalDate.of(2026, 3, 9)
        val items: List<Long?> = listOf(
            millisOf(nextMonday, 10),
            millisOf(wednesday, 12),
            millisOf(monday, 8),
        )
        val sections = group(items, MomentGranularity.WEEK)
        val rows = buildTimelineRows(items, { it }, GroupBy.WEEK, ZoneOffset.UTC)
        val headers = rows.filterIsInstance<TimelineRow.Header>()
        assertEquals(sections.map { it.groupKey }, headers.map { it.groupKey })
        assertEquals(sections.map { it.anchorEpochDay }, headers.map { it.epochDay })
        assertEquals(listOf(0), sections[0].memberIndexes)
        assertEquals(listOf(1, 2), sections[1].memberIndexes)
        assertTrue(headers.all { it.isWeek && !it.isMonth })
    }
}
