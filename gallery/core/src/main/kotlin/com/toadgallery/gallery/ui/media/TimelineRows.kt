package com.toadgallery.gallery.ui.media

import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.data.moments.MomentGrouper
import com.toadgallery.gallery.data.moments.MomentGranularity
import com.toadgallery.gallery.ui.settings.GroupBy
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
 *
 * Since r27 (the Moments timeline round) a header also carries [isWeek]:
 * `true` for the WEEK grain's ISO-week groups ("Week starting …"),
 * `false` for the pre-r27 day/month groups. Defaulted so every existing
 * constructor call site and equality assertion stays source- and
 * behavior-identical.
 */
sealed interface TimelineRow {

    /**
     * A full-line group header rendered before the first cell of a new
     * group. [groupKey] is the stable LazyLayout key suffix (`"d<epochDay>"`
     * for days, `"m<year>-<month>"` for months, `"w<year>-<week>"` for
     * ISO weeks since r27); [epochDay] doubles as the label date (the
     * month's first day for [isMonth] groups, the week's MONDAY for
     * [isWeek] groups, the day itself otherwise); [isMonth] picks the
     * label pattern in [formatGroupLabel].
     */
    data class Header(
        val groupKey: String,
        val epochDay: Long,
        val isMonth: Boolean,
        /** Whether this header labels an ISO-week group (r27, Moments). */
        val isWeek: Boolean = false,
    ) : TimelineRow

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
 *    day of the EFFECTIVE instant ([effectiveDateMillisOf], in [zone])
 *    (`groupKey = "d<epochDay>"`).
 *  - [GroupBy.MONTH]: the same with [YearMonth] groups; the header's
 *    [TimelineRow.Header.epochDay] is the month's FIRST day
 *    (`groupKey = "m<year>-<month>"`, unpadded month).
 *  - [GroupBy.WEEK] (r27, the Moments round): the same with ISO-8601 week
 *    groups --- a thin projection of the pure [MomentGrouper]'s WEEK
 *    output (`groupKey = "w<weekBasedYear>-<week>"`, anchor = the week's
 *    Monday, [TimelineRow.Header.isWeek] = true). A Sunday-night photo and
 *    the Monday-morning photo after it share ONE header; a photo from the
 *    PREVIOUS Sunday starts a new one.
 *
 * ## The effective instant (r13)
 * [effectiveDateMillisOf] returns the instant that orders and groups the
 * timeline IN EPOCH MILLIS. The production rule is the EFFECTIVE instant:
 * the resolved capture date when the catalogue populated one (`MediaItem
 * .captureDateMillis`), else the MediaStore `date_added` instant
 * (`dateAddedSec * 1000`) --- capture-date precedence is the whole r13
 * behavior change (a photo imported today but captured last year groups
 * under last year). A `null` capture date simply falls back to `dateAdded`;
 * MediaStore-fed surfaces never populate the capture field, so they keep
 * the exact pre-r13 grouping.
 *
 * NEGATIVE instants are legitimate (pre-1970 captures from scanned
 * archives): `Instant.atZone(zone).toLocalDate()` handles them like any
 * other instant, and the resulting epochDay is simply negative --- the
 * `"d<epochDay>"` key and label math stay correct unchanged.
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
 * `TimelineRowsTest` pins it with `List<Long>` epoch-MILLI stand-ins.
 */
fun <T> buildTimelineRows(
    items: List<T?>,
    effectiveDateMillisOf: (T) -> Long,
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
                val day = Instant.ofEpochMilli(effectiveDateMillisOf(item))
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
                    Instant.ofEpochMilli(effectiveDateMillisOf(item)).atZone(zone).toLocalDate()
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
        GroupBy.WEEK -> {
            // r27 (the Moments round): a thin projection of the pure
            // MomentGrouper's WEEK output onto rows --- the grouping math
            // (ISO weeks, week-based-year keys, Monday anchors) lives in
            // the pure, JVM-tested engine, and this branch only maps its
            // sections to Header/Cell pairs. The DAY/MONTH branches above
            // keep their own streaming implementations untouched (their
            // behavior is pinned by the pre-r27 tests; the grouper's DAY
            // mode is pinned to AGREE with the DAY branch by
            // MomentGrouperTest's equivalence tests).
            for (section in MomentGrouper.group(
                items,
                effectiveDateMillisOf,
                MomentGranularity.WEEK,
                zone,
            )) {
                rows.add(
                    TimelineRow.Header(
                        groupKey = section.groupKey,
                        epochDay = section.anchorEpochDay,
                        isMonth = false,
                        isWeek = true,
                    )
                )
                section.memberIndexes.forEach { rows.add(TimelineRow.Cell(it)) }
            }
        }
    }
    return rows
}

/**
 * The production [buildTimelineRows]: groups a `LazyPagingItems` snapshot
 * (`List<MediaItem?>`) by each item's EFFECTIVE instant --- the resolved
 * capture date when the catalogue-backed timeline populated one, else the
 * `dateAddedSec` instant (`captureDateMillis ?: dateAddedSec * 1000`, the
 * rule documented on `MediaItem.captureDateMillis`). MediaStore-fed
 * surfaces never populate the capture field, so their grouping is
 * byte-identical to the pre-r13 render. A trivial delegate to the generic
 * core above; the grouping rules and their tests live there.
 */
fun buildTimelineRows(
    items: List<MediaItem?>,
    groupBy: GroupBy,
    zone: ZoneId = ZoneId.systemDefault(),
): List<TimelineRow> = buildTimelineRows(
    items,
    // The effective-instant rule (r13): capture-date precedence, dateAdded
    // fallback --- identical to `it.dateAddedSec * 1000` whenever
    // captureDateMillis is null (every MediaStore-fed item).
    { it.captureDateMillis ?: it.dateAddedSec * 1000 },
    groupBy,
    zone,
)

/**
 * The grid key of one cell row (the r8 device-crash fix): the media key
 * when the snapshot slot still holds its item, else the positional
 * fallback --- and NEVER a throw.
 *
 * The staggered grid queries keys for indices in its layout's nearest
 * range, which can be one frame STALER than the paging data: a refresh
 * (sort change, collection observer, generation swap) resets the page
 * store while the last-composed rows still describe the longer pre-refresh
 * list, so a key lookup can arrive for an index the store no longer holds.
 * `LazyPagingItems.get` (the page-store read) throws
 * `IndexOutOfBoundsException` in exactly that window --- the r8 crash,
 * `Index: 60, Size: 60` --- so this helper reads the SNAPSHOT list with a
 * bounds-checked [List.getOrNull] instead: an out-of-range or emptied slot
 * degrades to the `"cell_<index>"` positional key, which cannot collide
 * with the uri keys real items use, and the next recomposition re-derives
 * rows for the new store anyway. Generic for the same JVM-test reason as
 * [buildTimelineRows]; the production call site passes
 * `LazyPagingItems.itemSnapshotList` and `{ it.uri.toString() }`.
 */
fun <T> timelineCellKey(
    snapshot: List<T?>,
    index: Int,
    keyOf: (T) -> String,
): String = snapshot.getOrNull(index)?.let(keyOf) ?: "cell_$index"

/**
 * The user-facing label of one group header, via
 * `DateTimeFormatter.ofPattern`: "d MMMM yyyy" for day groups ("5 March
 * 2026"), "MMMM yyyy" for month groups ("March 2026"). The pattern text is
 * localized by [locale], matching the rest of the app's date presentation;
 * [TimelineRow.Header.epochDay] is the date being labeled (a month group
 * labels its first day, whose month is the group).
 *
 * WEEK headers (r27) label their Monday with the same "d MMMM yyyy"
 * pattern; the grid wraps that label in the localized "Week starting %s"
 * prefix (`moments_week_of`) --- the pure function stays pattern-only and
 * the localized PROSE lives in the string resource, the same split the
 * plurals use.
 */
fun formatGroupLabel(header: TimelineRow.Header, locale: Locale): String {
    val pattern = if (header.isMonth) "MMMM yyyy" else "d MMMM yyyy"
    return LocalDate.ofEpochDay(header.epochDay)
        .format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The moment sections' header COUNTS (r27, the Moments round): how many
 * cells follow each header before the next header (or the end of the
 * rows) --- the "March 2026 · 42 items" suffix the grouped timeline
 * renders. A pure post-pass over the built row list: [buildTimelineRows]
 * streams headers before it can know their sizes, so the count is
 * measured afterwards, exactly once, deterministically.
 *
 * Returns a [groupKey] -> count map covering EVERY header in [rows]
 * (headers always have at least one following cell by construction, but
 * the map answers 0 rather than being absent for a degenerate tail).
 * The grid derives this alongside its rows (same `derivedStateOf`, see
 * `MediaPagingGrid`); because the rows recompute as Paging appends
 * windows, a trailing header's count honestly GROWS with each window ---
 * the same reactivity the rows themselves have.
 */
fun headerCellCounts(rows: List<TimelineRow>): Map<String, Int> {
    if (rows.isEmpty()) return emptyMap()
    val counts = LinkedHashMap<String, Int>(rows.size / 8)
    var openKey: String? = null
    for (row in rows) {
        when (row) {
            is TimelineRow.Header -> {
                openKey = row.groupKey
                // A repeated key (impossible from buildTimelineRows, which
                // emits a header only on CHANGE) would need care; putIfAbsent
                // semantics via the explicit contains check keep the FIRST
                // header's count the authoritative one either way.
                if (!counts.containsKey(row.groupKey)) counts[row.groupKey] = 0
            }
            is TimelineRow.Cell -> openKey?.let { key ->
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
    }
    return counts
}
