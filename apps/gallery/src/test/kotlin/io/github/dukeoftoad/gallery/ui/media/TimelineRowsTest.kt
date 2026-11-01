package io.github.dukeoftoad.gallery.ui.media

import io.github.dukeoftoad.gallery.ui.settings.GroupBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * JVM tests for [TimelineRows] (roadmap Phase 3: appearance settings slice).
 *
 * No emulator, no Robolectric (ADR-0016) --- and no `MediaItem`: it embeds
 * `android.net.Uri`, which a plain JVM test cannot construct, so (exactly
 * like `WindowPagingSourceTest`/`ViewerWindowTest`, issues #28/#50) every
 * rule below drives the GENERIC [buildTimelineRows] core with `List<Long>`
 * epoch-second stand-ins and the identity extractor. The production
 * `MediaItem` overload is a one-line delegate onto that core (see its
 * KDoc), so pinning the core pins the timeline the grid renders.
 *
 * All dates use fixed zones --- never the system default --- so the grouping
 * math is deterministic on any machine (and the Asia/Tokyo boundary rule
 * below pins exactly why [buildTimelineRows] takes a zone at all).
 */
class TimelineRowsTest {

    /** UTC midnight of [date], as an epoch second (the test item payload). */
    private fun secOf(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    /** Groups [items] (nulls = not-yet-loaded paging placeholders). */
    private fun rows(
        items: List<Long?>,
        groupBy: GroupBy,
        zone: ZoneId = ZoneOffset.UTC,
    ): List<TimelineRow> = buildTimelineRows(items, { it }, groupBy, zone)

    // ------------------------------------------------------------------
    // NONE: the flat pre-slice timeline
    // ------------------------------------------------------------------

    @Test
    fun `NONE passes every item through as a cell with preserved indices`() {
        val rows = rows(listOf(10L, 11L, 12L), GroupBy.NONE)
        assertEquals(
            "no headers, one cell per item, snapshot indices kept",
            listOf(
                TimelineRow.Cell(0),
                TimelineRow.Cell(1),
                TimelineRow.Cell(2),
            ),
            rows,
        )
    }

    @Test
    fun `NONE skips null placeholders but keeps the surviving indices`() {
        val rows = rows(listOf(10L, null, 12L), GroupBy.NONE)
        assertEquals(listOf(TimelineRow.Cell(0), TimelineRow.Cell(2)), rows)
    }

    @Test
    fun `empty snapshot yields no rows in every mode`() {
        GroupBy.entries.forEach { groupBy ->
            assertEquals("empty input -> empty rows ($groupBy)", emptyList<TimelineRow>(), rows(emptyList(), groupBy))
        }
    }

    // ------------------------------------------------------------------
    // DAY grouping
    // ------------------------------------------------------------------

    @Test
    fun `DAY groups multiple days with a header before each new day`() {
        val day1 = LocalDate.of(2026, 3, 5)
        val day2 = LocalDate.of(2026, 3, 6)
        val rows = rows(
            // Same-day captures land under one header even at different times.
            listOf(secOf(day1) + 3_600, secOf(day1) + 7_200, secOf(day2), secOf(day2) + 60),
            GroupBy.DAY,
        )
        assertEquals(
            listOf(
                TimelineRow.Header("d${day1.toEpochDay()}", day1.toEpochDay(), isMonth = false),
                TimelineRow.Cell(0),
                TimelineRow.Cell(1),
                TimelineRow.Header("d${day2.toEpochDay()}", day2.toEpochDay(), isMonth = false),
                TimelineRow.Cell(2),
                TimelineRow.Cell(3),
            ),
            rows,
        )
    }

    @Test
    fun `DAY single day emits exactly one header before all cells`() {
        val day = LocalDate.of(2026, 3, 5)
        val rows = rows(
            listOf(secOf(day), secOf(day) + 86_399, secOf(day) + 1),
            GroupBy.DAY,
        )
        assertEquals(4, rows.size) // 1 header + 3 cells, no interleaved repeats
        assertTrue(rows.first() is TimelineRow.Header)
        assertTrue(rows.drop(1).all { it is TimelineRow.Cell })
    }

    @Test
    fun `DAY header epochDay and groupKey are stable across rebuilds`() {
        // The paging grid recomputes rows as windows append; equal inputs
        // must yield equal rows (LazyLayout keys rely on this).
        val items = listOf(secOf(LocalDate.of(2026, 3, 5)), secOf(LocalDate.of(2026, 3, 7)))
        assertEquals(rows(items, GroupBy.DAY), rows(items, GroupBy.DAY))
        val header = rows(items, GroupBy.DAY).first() as TimelineRow.Header
        assertEquals(LocalDate.of(2026, 3, 5).toEpochDay(), header.epochDay)
        assertEquals("d${header.epochDay}", header.groupKey)
        assertFalse(header.isMonth)
    }

    // ------------------------------------------------------------------
    // MONTH grouping
    // ------------------------------------------------------------------

    @Test
    fun `MONTH groups by year-month with first-day epochDay and m-key`() {
        val feb = LocalDate.of(2026, 2, 20)
        val mar1 = LocalDate.of(2026, 3, 1)
        val mar31 = LocalDate.of(2026, 3, 31)
        val rows = rows(listOf(secOf(feb), secOf(mar1), secOf(mar31)), GroupBy.MONTH)
        assertEquals(
            listOf(
                TimelineRow.Header("m2026-2", LocalDate.of(2026, 2, 1).toEpochDay(), isMonth = true),
                TimelineRow.Cell(0),
                // 1 March and 31 March share the March header.
                TimelineRow.Header("m2026-3", LocalDate.of(2026, 3, 1).toEpochDay(), isMonth = true),
                TimelineRow.Cell(1),
                TimelineRow.Cell(2),
            ),
            rows,
        )
    }

    @Test
    fun `MONTH separates same month names in different years`() {
        val thisYear = LocalDate.of(2026, 3, 5)
        val nextYear = LocalDate.of(2027, 3, 5)
        val headers = rows(listOf(secOf(thisYear), secOf(nextYear)), GroupBy.MONTH)
            .filterIsInstance<TimelineRow.Header>()
        assertEquals(listOf("m2026-3", "m2027-3"), headers.map { it.groupKey })
        assertEquals(listOf(true, true), headers.map { it.isMonth })
    }

    // ------------------------------------------------------------------
    // Null placeholders: skipped, never a header trigger
    // ------------------------------------------------------------------

    @Test
    fun `null placeholders are skipped without emitting headers`() {
        val day1 = LocalDate.of(2026, 3, 5)
        val day2 = LocalDate.of(2026, 3, 6)
        // A null BETWEEN two same-day items must not split the day group.
        val rows = rows(listOf(secOf(day1), null, secOf(day1)), GroupBy.DAY)
        assertEquals(
            listOf(
                TimelineRow.Header("d${day1.toEpochDay()}", day1.toEpochDay(), false),
                TimelineRow.Cell(0),
                TimelineRow.Cell(2),
            ),
            rows,
        )
    }

    @Test
    fun `null placeholders never trigger a new group in MONTH mode`() {
        val mar = LocalDate.of(2026, 3, 5)
        val rows = rows(listOf(null, null, secOf(mar), null), GroupBy.MONTH)
        assertEquals(
            listOf(
                TimelineRow.Header("m2026-3", LocalDate.of(2026, 3, 1).toEpochDay(), true),
                TimelineRow.Cell(2),
            ),
            rows,
        )
    }

    // ------------------------------------------------------------------
    // Zone math: the same instant can land on different days
    // ------------------------------------------------------------------

    @Test
    fun `same epoch second falls on different days in different zones`() {
        // 2026-03-05T20:00Z is still 5 March in UTC but already 6 March in
        // Asia/Tokyo (UTC+9): the header must follow the rendered zone.
        val instant = Instant.parse("2026-03-05T20:00:00Z").epochSecond
        val utc = rows(listOf(instant), GroupBy.DAY, ZoneOffset.UTC).first() as TimelineRow.Header
        val tokyo = rows(
            listOf(instant),
            GroupBy.DAY,
            ZoneId.of("Asia/Tokyo"),
        ).first() as TimelineRow.Header
        assertEquals(LocalDate.of(2026, 3, 5).toEpochDay(), utc.epochDay)
        assertEquals(LocalDate.of(2026, 3, 6).toEpochDay(), tokyo.epochDay)
        assertEquals("day X vs day X+1", utc.epochDay + 1, tokyo.epochDay)
    }

    @Test
    fun `month groups also follow the rendered zone`() {
        // 2026-04-30T20:00Z is April in UTC but already 1 May in Tokyo.
        val instant = Instant.parse("2026-04-30T20:00:00Z").epochSecond
        val tokyo = rows(
            listOf(instant),
            GroupBy.MONTH,
            ZoneId.of("Asia/Tokyo"),
        ).first() as TimelineRow.Header
        assertEquals("m2026-5", tokyo.groupKey)
    }

    // ------------------------------------------------------------------
    // formatGroupLabel
    // ------------------------------------------------------------------

    @Test
    fun `formatGroupLabel renders day headers as d MMMM yyyy in English`() {
        val header = TimelineRow.Header("d0", LocalDate.of(2026, 3, 5).toEpochDay(), isMonth = false)
        assertEquals("5 March 2026", formatGroupLabel(header, Locale.ENGLISH))
    }

    @Test
    fun `formatGroupLabel renders month headers as MMMM yyyy in English`() {
        // A month header labels its first day; the label shows only month+year.
        val header = TimelineRow.Header("m2026-3", LocalDate.of(2026, 3, 1).toEpochDay(), isMonth = true)
        assertEquals("March 2026", formatGroupLabel(header, Locale.ENGLISH))
    }

    @Test
    fun `formatGroupLabel honors the locale parameter`() {
        // March is "mars" in French: proves the locale flows into the
        // formatter instead of the English pattern winning silently.
        val dayHeader = TimelineRow.Header("d0", LocalDate.of(2026, 3, 5).toEpochDay(), isMonth = false)
        val monthHeader = TimelineRow.Header("m2026-3", LocalDate.of(2026, 3, 1).toEpochDay(), isMonth = true)
        assertEquals("5 mars 2026", formatGroupLabel(dayHeader, Locale.FRENCH))
        assertEquals("mars 2026", formatGroupLabel(monthHeader, Locale.FRENCH))
    }

    @Test
    fun `formatGroupLabel single-digit days render unpadded`() {
        val header = TimelineRow.Header("d0", LocalDate.of(2026, 1, 9).toEpochDay(), isMonth = false)
        assertEquals("9 January 2026", formatGroupLabel(header, Locale.ENGLISH))
    }

    // ---- timelineCellKey (the r8 device-crash fix) ----------------------
    //
    // The grid's key lambda used LazyPagingItems.get (the page-store read),
    // which throws IndexOutOfBounds when a refresh shrinks the store while
    // the composed rows still describe the longer pre-refresh list (device:
    // "Index: 60, Size: 60"). timelineCellKey reads the snapshot list
    // bounds-checked instead: every rule below is a shape that MUST NOT
    // throw, whatever the stale-layout window throws at it.

    @Test
    fun `cell key is the item key for an in-range slot`() {
        val snapshot = listOf("uri:a", "uri:b", "uri:c")
        assertEquals("uri:b", timelineCellKey(snapshot, 1) { it })
    }

    @Test
    fun `cell key falls back positionally for a null slot`() {
        // enablePlaceholders=false means no null slots in practice, but the
        // snapshot TYPE is List<T?>; a null must degrade, never throw.
        val snapshot = listOf("uri:a", null, "uri:c")
        assertEquals("cell_1", timelineCellKey(snapshot, 1) { it })
    }

    @Test
    fun `cell key degrades for the stale-index refresh window`() {
        // The exact r8 shape: rows were derived from a 120-item snapshot,
        // the store refreshed down to 60, and the grid still asks for the
        // key of index 60. No throw; the positional key cannot collide
        // with a uri key.
        val snapshot = (0 until 60).map { "uri:$it" }
        assertEquals("cell_60", timelineCellKey(snapshot, 60) { it })
    }

    @Test
    fun `cell key degrades for far-out and negative indices`() {
        val snapshot = listOf("uri:a")
        assertEquals("cell_99", timelineCellKey(snapshot, 99) { it })
        assertEquals("cell_-1", timelineCellKey(snapshot, -1) { it })
        assertEquals("cell_0", timelineCellKey(emptyList<String>(), 0) { it })
    }
}
