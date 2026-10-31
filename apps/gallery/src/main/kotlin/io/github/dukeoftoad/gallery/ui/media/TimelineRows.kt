package io.github.dukeoftoad.gallery.ui.media

import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ui.settings.GroupBy
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One slot of the grouped timeline (roadmap Phase 3: appearance settings
 * slice): either a full-line date [Header] or a [Cell] pointing back at its
 * position in the paging snapshot.
 *
 * [Cell.index] is the index into `LazyPagingItems.itemSnapshotList` --- NOT
 * the index inside the row list --- so the grid can read the item, key the
 * slot by its `uri` and hand selection/tap code the same snapshot position
 * it always used. That indirection is what lets headers interleave with the
 * paging windows without touching any call site's window math.
 */
sealed interface TimelineRow {

    /**
     * A full-line group header rendered before the first cell of a new
     * group. [groupKey] is the stable LazyLayout key suffix (`"d<epochDay>"`
     * for days, `"m<year>-<month>"` for months); [epochDay] doubles as the
     * label date (the month's first day for [isMonth] groups, the day
     * itself otherwise); [isMonth] picks the label pattern in
     * [formatGroupLabel].
     */
    data class Header(val groupKey: String, val epochDay: Long, val isMonth: Boolean) : TimelineRow

    /** One media cell: [index] into the paging snapshot (see the file KDoc). */
    data class Cell(val index: Int) : TimelineRow
}

/**
 * Builds the grouped-timeline row model over a paging snapshot.
 *
 * Grouping rules (all deterministic --- same input list + [groupBy] + [zone]
 * always produce the same rows, so the grid recomputes safely as Paging
 * appends windows or refreshes, and stable keys survive both):
 *  - [GroupBy.NONE]: one [TimelineRow.Cell] per non-null item, snapshot
 *    indices preserved, no headers --- the flat timeline this app shipped
 *    with (issues #25/#28), row-for-row.
 *  - [GroupBy.DAY]: a [TimelineRow.Header] before the first cell of each new
 *    day of `dateAddedSec` in [zone] (`groupKey = "d<epochDay>"`).
 *  - [GroupBy.MONTH]: the same with [YearMonth] groups; the header's
 *    [TimelineRow.Header.epochDay] is the month's FIRST day
 *    (`groupKey = "m<year>-<month>"`, unpadded month).
 *
 * Null (not-yet-loaded paging placeholder) items are skipped entirely: they
 * produce no cell and never trigger a header, so an appended window that
 * lands mid-group merges into it instead of slicing a stray header between
 * placeholder gaps.
 *
 * The date math deliberately uses `Instant.atZone(zone).toLocalDate()`
 * (java.time-8 API, present since minSdk 26) rather than
 * `LocalDate.ofInstant(...)` (Java 9, only on much newer Android runtimes)
 * --- both compute the identical calendar date.
 *
 * This is the generic core on purpose: production calls the [MediaItem]
 * overload below, but `MediaItem` embeds `android.net.Uri`, which a plain
 * JVM test cannot construct --- so, exactly like `WindowPagingSource` and
 * `ViewerWindow` (issues #28/#50), the logic is generic over the payload and
 * `TimelineRowsTest` pins it with `List<Long>` epoch-second stand-ins.
 */
fun <T> buildTimelineRows(
    items: List<T?>,
    dateAddedSecOf: (T) -> Long,
    groupBy: GroupBy,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>(items.size)
    when (groupBy) {
        GroupBy.NONE -> {
            for ((index, item) in items.withIndex()) {
                if (item != null) rows.add(TimelineRow.Cell(index))
            }
        }
        GroupBy.DAY -> {
            var currentDay = Long.MIN_VALUE // no LocalDate can equal this sentinel
            for ((index, item) in items.withIndex()) {
                if (item == null) continue
                val day = Instant.ofEpochSecond(dateAddedSecOf(item))
                    .atZone(zone)
                    .toLocalDate()
                    .toEpochDay()
                if (day != currentDay) {
                    rows.add(TimelineRow.Header(groupKey = "d$day", epochDay = day, isMonth = false))
                    currentDay = day
                }
                rows.add(TimelineRow.Cell(index))
            }
        }
        GroupBy.MONTH -> {
            var currentMonth: YearMonth? = null
            for ((index, item) in items.withIndex()) {
                if (item == null) continue
                val month = YearMonth.from(
                    Instant.ofEpochSecond(dateAddedSecOf(item)).atZone(zone).toLocalDate()
                )
                if (month != currentMonth) {
                    rows.add(
                        TimelineRow.Header(
                            groupKey = "m${month.year}-${month.monthValue}",
                            epochDay = month.atDay(1).toEpochDay(),
                            isMonth = true,
                        )
                    )
                    currentMonth = month
                }
                rows.add(TimelineRow.Cell(index))
            }
        }
    }
    return rows
}

/**
 * The production [buildTimelineRows]: groups a `LazyPagingItems` snapshot
 * (`List<MediaItem?>`) by each item's `dateAddedSec`. A trivial delegate to
 * the generic core above; the grouping rules and their tests live there.
 */
fun buildTimelineRows(
    items: List<MediaItem?>,
    groupBy: GroupBy,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TimelineRow> = buildTimelineRows(items, { it.dateAddedSec }, groupBy, zone)

/**
 * The user-facing label of one group header, via
 * `DateTimeFormatter.ofPattern`: "d MMMM yyyy" for day groups ("5 March
 * 2026"), "MMMM yyyy" for month groups ("March 2026"). The pattern text is
 * localized by [locale], matching the rest of the app's date presentation;
 * [TimelineRow.Header.epochDay] is the date being labeled (a month group
 * labels its first day, whose month is the group).
 */
fun formatGroupLabel(header: TimelineRow.Header, locale: Locale): String {
    val pattern = if (header.isMonth) "MMMM yyyy" else "d MMMM yyyy"
    return LocalDate.ofEpochDay(header.epochDay)
        .format(DateTimeFormatter.ofPattern(pattern, locale))
}
