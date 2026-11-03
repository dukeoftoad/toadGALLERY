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
 * JVM tests for [TimelineRows] (roadmap Phase 3: appearance settings slice;
 * effective-instant grouping since r13).
 *
 * No emulator, no Robolectric (ADR-0016) --- and no `MediaItem`: it embeds
 * `android.net.Uri`, which a plain JVM test cannot construct, so (exactly
 * like `WindowPagingSourceTest`/`ViewerWindowTest`, issues #28/#50) every
 * rule below drives the GENERIC [buildTimelineRows] core with `List<Long>`
 * epoch-MILLI stand-ins and the identity extractor. The production
 * `MediaItem` overload is a one-line delegate onto that core (see its
 * KDoc), so pinning the core pins the timeline the grid renders.
 *
 * Since r13 the seam takes the EFFECTIVE instant in millis; the r13 section
 * drives it with `(captureDateMillis, dateAddedSec)` pairs and the
 * production extractor (`captureDateMillis ?: dateAddedSec * 1000`) to pin
 * capture-date precedence, the null fallback, and pre-1970 (negative)
 * instants.
 *
 * All dates use fixed zones --- never the system default --- so the grouping
 * math is deterministic on any machine (and the Asia/Tokyo boundary rule
 * below pins exactly why [buildTimelineRows] takes a zone at all).
 */
class TimelineRowsTest {

    /** UTC midnight of [date], as an epoch second (the dateAdded unit). */
    private fun secOf(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()

    /** UTC midnight of [date], as an epoch MILLI (the effective-instant unit). */
    /** The r27 WEEK tests' convenience: a calendar date as three ints. */
    private fun millisOf(year: Int, month: Int, day: Int): Long =
        millisOf(java.time.LocalDate.of(year, month, day))

    private fun millisOf(date: LocalDate): Long = secOf(date) * 1000

    /** Groups [items] (nulls = not-yet-loaded paging placeholders). */
    private fun rows(
        items: List<Long?>,
        groupBy: GroupBy,
        zone: ZoneId = ZoneOffset.UTC,
    ): List<TimelineRow> = buildTimelineRows(items, { it }, groupBy, zone)

    /**
     * The production payload shape since r13: a resolved capture date
     * (nullable) plus the MediaStore `dateAddedSec` it falls back to.
     */
    private data class DatedItem(val captureDateMillis: Long?, val dateAddedSec: Long)

    /** Groups [items] by the production effective-instant rule. */
    private fun effectiveRows(
        items: List<DatedItem?>,
        groupBy: GroupBy,
        zone: ZoneId = ZoneOffset.UTC,
    ): List<TimelineRow> = buildTimelineRows(
        items,
        { it.captureDateMillis ?: it.dateAddedSec * 1000 },
        groupBy,
        zone,
    )

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
            listOf(millisOf(day1) + 3_600_000, millisOf(day1) + 7_200_000, millisOf(day2), millisOf(day2) + 60_000),
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
            listOf(millisOf(day), millisOf(day) + 86_399_000, millisOf(day) + 1),
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
        val items = listOf(millisOf(LocalDate.of(2026, 3, 5)), millisOf(LocalDate.of(2026, 3, 7)))
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
        val rows = rows(listOf(millisOf(feb), millisOf(mar1), millisOf(mar31)), GroupBy.MONTH)
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
        val headers = rows(listOf(millisOf(thisYear), millisOf(nextYear)), GroupBy.MONTH)
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
        val rows = rows(listOf(millisOf(day1), null, millisOf(day1)), GroupBy.DAY)
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
        val rows = rows(listOf(null, null, millisOf(mar), null), GroupBy.MONTH)
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
        val instant = Instant.parse("2026-03-05T20:00:00Z").toEpochMilli()
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
        val instant = Instant.parse("2026-04-30T20:00:00Z").toEpochMilli()
        val tokyo = rows(
            listOf(instant),
            GroupBy.MONTH,
            ZoneId.of("Asia/Tokyo"),
        ).first() as TimelineRow.Header
        assertEquals("m2026-5", tokyo.groupKey)
    }

    // ------------------------------------------------------------------
    // The r13 effective instant: capture-date precedence, null fallback,
    // negative (pre-1970) instants
    // ------------------------------------------------------------------

    @Test
    fun `capture date wins over dateAdded for the day header boundary`() {
        // Two photos imported the SAME day (identical dateAdded), captured
        // on different days: the capture dates split the groups --- the
        // r13 behavior change in one rule.
        val added = LocalDate.of(2026, 3, 5)
        val capturedA = LocalDate.of(2026, 3, 4)
        val capturedB = LocalDate.of(2026, 3, 6)
        val rows = effectiveRows(
            listOf(
                DatedItem(captureDateMillis = millisOf(capturedA), dateAddedSec = secOf(added)),
                DatedItem(captureDateMillis = millisOf(capturedB), dateAddedSec = secOf(added)),
            ),
            GroupBy.DAY,
        )
        assertEquals(
            listOf(
                TimelineRow.Header("d${capturedA.toEpochDay()}", capturedA.toEpochDay(), isMonth = false),
                TimelineRow.Cell(0),
                TimelineRow.Header("d${capturedB.toEpochDay()}", capturedB.toEpochDay(), isMonth = false),
                TimelineRow.Cell(1),
            ),
            rows,
        )
    }

    @Test
    fun `same capture day merges items added on different days`() {
        // Capture precedence collapses the boundary: both items were
        // CAPTURED on day1 even though one was imported on day2, so they
        // share day1's header (a dateAdded grouping would have split them).
        val day1 = LocalDate.of(2026, 3, 5)
        val day2 = LocalDate.of(2026, 3, 6)
        val rows = effectiveRows(
            listOf(
                DatedItem(captureDateMillis = millisOf(day1), dateAddedSec = secOf(day1)),
                DatedItem(captureDateMillis = millisOf(day1), dateAddedSec = secOf(day2)),
            ),
            GroupBy.DAY,
        )
        assertEquals(
            listOf(
                TimelineRow.Header("d${day1.toEpochDay()}", day1.toEpochDay(), false),
                TimelineRow.Cell(0),
                TimelineRow.Cell(1),
            ),
            rows,
        )
    }

    @Test
    fun `null capture date falls back to dateAdded`() {
        // Unresolved (null) capture: the item groups under its dateAdded
        // day, exactly like the pre-r13 render --- and it lands WITH a
        // resolved item whose capture (not dateAdded) points at that day.
        val day1 = LocalDate.of(2026, 3, 5)
        val day2 = LocalDate.of(2026, 3, 6)
        val rows = effectiveRows(
            listOf(
                DatedItem(captureDateMillis = null, dateAddedSec = secOf(day2)),
                DatedItem(captureDateMillis = millisOf(day2), dateAddedSec = secOf(day1)),
            ),
            GroupBy.DAY,
        )
        assertEquals(
            listOf(
                TimelineRow.Header("d${day2.toEpochDay()}", day2.toEpochDay(), false),
                TimelineRow.Cell(0),
                TimelineRow.Cell(1),
            ),
            rows,
        )
    }

    @Test
    fun `a negative pre-1970 capture instant lands under its own epochDay`() {
        // A scanned archive photo captured in 1930 (negative epoch millis
        // and negative epochDay) must not crash the math nor fold into the
        // 2026 group: it gets its own header with a negative day key.
        val scanned = LocalDate.of(1930, 1, 15)
        val modern = LocalDate.of(2026, 3, 5)
        assertTrue("fixture guard: pre-1970 epochDay is negative", scanned.toEpochDay() < 0)
        val rows = effectiveRows(
            listOf(
                DatedItem(captureDateMillis = millisOf(scanned), dateAddedSec = secOf(modern)),
                DatedItem(captureDateMillis = null, dateAddedSec = secOf(modern)),
            ),
            GroupBy.DAY,
        )
        assertEquals(
            listOf(
                TimelineRow.Header("d${scanned.toEpochDay()}", scanned.toEpochDay(), false),
                TimelineRow.Cell(0),
                TimelineRow.Header("d${modern.toEpochDay()}", modern.toEpochDay(), false),
                TimelineRow.Cell(1),
            ),
            rows,
        )
    }

    @Test
    fun `month grain follows the effective instant too`() {
        // Same two rules at month grain: a capture date in a different
        // month than dateAdded splits the group (capture wins), and the
        // pre-1970 capture lands under its own (negative-epochDay) month.
        val scannedFeb1930 = LocalDate.of(1930, 2, 10)
        val addedMar2026 = LocalDate.of(2026, 3, 5)
        val capturedFeb2026 = LocalDate.of(2026, 2, 20)
        val rows = effectiveRows(
            listOf(
                DatedItem(captureDateMillis = millisOf(scannedFeb1930), dateAddedSec = secOf(addedMar2026)),
                DatedItem(captureDateMillis = millisOf(capturedFeb2026), dateAddedSec = secOf(addedMar2026)),
                DatedItem(captureDateMillis = null, dateAddedSec = secOf(addedMar2026)),
            ),
            GroupBy.MONTH,
        )
        assertEquals(
            listOf(
                TimelineRow.Header("m1930-2", LocalDate.of(1930, 2, 1).toEpochDay(), isMonth = true),
                TimelineRow.Cell(0),
                TimelineRow.Header("m2026-2", LocalDate.of(2026, 2, 1).toEpochDay(), isMonth = true),
                TimelineRow.Cell(1),
                TimelineRow.Header("m2026-3", LocalDate.of(2026, 3, 1).toEpochDay(), isMonth = true),
                TimelineRow.Cell(2),
            ),
            rows,
        )
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

    // ------------------------------------------------------------------
    // WEEK grouping (r27, the Moments round): the pure MomentGrouper's
    // WEEK output projected onto rows --- the projection itself is also
    // pinned end-to-end by MomentGrouperTest's agreement tests.
    // ------------------------------------------------------------------

    @Test
    fun `WEEK groups a Monday-to-Sunday run under one Monday-anchored header`() {
        // 2026-03-02 is a Monday; the ISO week runs Mon 2 .. Sun 8 March
        // (ISO week 10 of 2026). A Saturday and the Sunday after it share
        // ONE header even at the week's very end.
        val saturday = LocalDate.of(2026, 3, 7)
        val sunday = LocalDate.of(2026, 3, 8)
        val monday = LocalDate.of(2026, 3, 2)
        val rows = rows(listOf(millisOf(sunday), millisOf(saturday)), GroupBy.WEEK)
        assertEquals(
            listOf(
                TimelineRow.Header("w2026-10", monday.toEpochDay(), isMonth = false, isWeek = true),
                TimelineRow.Cell(0),
                TimelineRow.Cell(1),
            ),
            rows,
        )
    }

    @Test
    fun `WEEK splits Sunday night from the Monday morning after it`() {
        // The DAY grain's canonical hard boundary lands in different ISO
        // weeks too (10 vs 11): a new header, anchored on the new week's
        // Monday (2026-03-09).
        val sundayNight = millisOf(LocalDate.of(2026, 3, 8), 23)
        val mondayMorning = millisOf(LocalDate.of(2026, 3, 9)) + 30 * 60_000L
        val rows = rows(listOf(mondayMorning, sundayNight), GroupBy.WEEK)
        val headers = rows.filterIsInstance<TimelineRow.Header>()
        assertEquals(listOf("w2026-11", "w2026-10"), headers.map { it.groupKey })
        assertEquals(
            listOf(LocalDate.of(2026, 3, 9).toEpochDay(), LocalDate.of(2026, 3, 2).toEpochDay()),
            headers.map { it.epochDay },
        )
        assertTrue(headers.all { it.isWeek && !it.isMonth })
    }

    @Test
    fun `WEEK keys carry the week-based year - a 2 January can be week 53`() {
        // 2027-01-02 sits in the ISO week Mon 2026-12-28 .. Sun 2027-01-03
        // --- week 53 of the week-based year 2026 (week 1 of 2027 starts
        // 2027-01-04). Keying it "w2027-1" would be a lie.
        val rows = rows(listOf(millisOf(LocalDate.of(2027, 1, 2))), GroupBy.WEEK)
        val header = rows.single() as TimelineRow.Header
        assertEquals("w2026-53", header.groupKey)
        assertEquals(LocalDate.of(2026, 12, 28).toEpochDay(), header.epochDay)
    }

    @Test
    fun `WEEK null placeholders never trigger a new group`() {
        val saturday = LocalDate.of(2026, 3, 7)
        val rows = rows(listOf(null, millisOf(saturday), null), GroupBy.WEEK)
        assertEquals(
            listOf(
                TimelineRow.Header("w2026-10", LocalDate.of(2026, 3, 2).toEpochDay(), false, isWeek = true),
                TimelineRow.Cell(1),
            ),
            rows,
        )
    }

    @Test
    fun `formatGroupLabel renders a WEEK header as its Monday in d MMMM yyyy`() {
        // The week header labels its ANCHOR Monday; the localized "Week
        // starting …" prose lives in the string resource, not the pure
        // function (the pattern-only split the r27 file KDoc documents).
        val header = TimelineRow.Header(
            "w2026-10",
            LocalDate.of(2026, 3, 2).toEpochDay(),
            isMonth = false,
            isWeek = true,
        )
        assertEquals("2 March 2026", formatGroupLabel(header, Locale.ENGLISH))
    }

    @Test
    fun `WEEK follows the rendered zone for the boundary instant`() {
        // 2026-03-08T20:00Z is Sunday (week 10) in UTC but already Monday
        // 2026-03-09 (week 11) in Asia/Tokyo.
        val instant = Instant.parse("2026-03-08T20:00:00Z").toEpochMilli()
        val utc = rows(listOf(instant), GroupBy.WEEK, ZoneOffset.UTC).single() as TimelineRow.Header
        val tokyo = rows(
            listOf(instant),
            GroupBy.WEEK,
            ZoneId.of("Asia/Tokyo"),
        ).single() as TimelineRow.Header
        assertEquals("w2026-10", utc.groupKey)
        assertEquals("w2026-11", tokyo.groupKey)
    }

    @Test
    fun `the pre-r27 header equality stays intact - isWeek defaults to false`() {
        // The defaulted parameter must keep every existing three-argument
        // construction (and the pre-r27 tests' equality assertions)
        // behavior-identical: a day/month header without the argument is
        // NOT a week header.
        val dayHeader = TimelineRow.Header("d20611", 20611L, isMonth = false)
        assertFalse(dayHeader.isWeek)
        assertEquals(
            TimelineRow.Header("d20611", 20611L, isMonth = false, isWeek = false),
            dayHeader,
        )
    }

    // ------------------------------------------------------------------
    // headerCellCounts (r27, the Moments round): the counted headers
    // ------------------------------------------------------------------

    @Test
    fun `headerCellCounts measures every header's following cells`() {
        val day1 = LocalDate.of(2026, 3, 5)
        val day2 = LocalDate.of(2026, 3, 6)
        val rows = rows(
            listOf(millisOf(day1), millisOf(day1), millisOf(day2)),
            GroupBy.DAY,
        )
        assertEquals(
            mapOf("d${day1.toEpochDay()}" to 2, "d${day2.toEpochDay()}" to 1),
            headerCellCounts(rows),
        )
    }

    @Test
    fun `headerCellCounts covers WEEK headers too`() {
        val rows = rows(
            listOf(
                millisOf(LocalDate.of(2026, 3, 8)), // Sunday, week 10
                millisOf(LocalDate.of(2026, 3, 7)), // Saturday, week 10
                millisOf(LocalDate.of(2026, 3, 2)), // Monday, week 10
                millisOf(LocalDate.of(2026, 2, 25)), // week 9
            ),
            GroupBy.WEEK,
        )
        assertEquals(
            mapOf("w2026-10" to 3, "w2026-9" to 1),
            headerCellCounts(rows),
        )
    }

    @Test
    fun `headerCellCounts answers empty for empty and flat timelines`() {
        assertEquals(emptyMap<String, Int>(), headerCellCounts(emptyList()))
        val flat = rows(listOf(10L, 11L), GroupBy.NONE)
        assertEquals(emptyMap<String, Int>(), headerCellCounts(flat))
    }

    @Test
    fun `headerCellCounts answers zero for a header with no following cells`() {
        // Impossible from buildTimelineRows (a header is emitted only on
        // CHANGE, so a cell always follows), but the degenerate shape
        // must answer 0 rather than being absent or throwing.
        val rows = listOf(
            TimelineRow.Header("d20611", 20611L, isMonth = false),
            TimelineRow.Header("d20612", 20612L, isMonth = false),
            TimelineRow.Cell(0),
        )
        assertEquals(mapOf("d20611" to 0, "d20612" to 1), headerCellCounts(rows))
    }

    @Test
    fun `a cell before any header joins no count`() {
        // The defensive tail: cells are attributed to the OPEN header
        // only; a leading cell (impossible from buildTimelineRows) is
        // dropped, never misattributed.
        val rows = listOf(
            TimelineRow.Cell(0),
            TimelineRow.Header("d20611", 20611L, isMonth = false),
            TimelineRow.Cell(1),
        )
        assertEquals(mapOf("d20611" to 1), headerCellCounts(rows))
    }
}
