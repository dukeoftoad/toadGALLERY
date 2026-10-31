package io.github.dukeoftoad.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [TimelineSort] (issue #66): the SQL sort-order mapping the
 * windowed/bucket queries send, and the in-memory ordering the snapshot
 * paths use --- pinned on the plain JVM per ADR-0016 (the enum is
 * deliberately Android-free, unlike the [MediaStoreSource] methods that
 * consume it).
 */
class TimelineSortTest {

    /** Lightweight stand-in for `MediaItem`'s sort keys (Uri is not JVM-buildable). */
    private data class Row(val dateAddedSec: Long, val id: Long)

    private val burst = listOf(
        Row(dateAddedSec = 100, id = 3),
        Row(dateAddedSec = 100, id = 1),
        Row(dateAddedSec = 100, id = 2),
        Row(dateAddedSec = 300, id = 7),
        Row(dateAddedSec = 200, id = 5),
    )

    @Test
    fun `newest first maps to the date_added DESC total order`() {
        assertEquals("date_added DESC, _id DESC", TimelineSort.NEWEST_FIRST.sortOrder)
    }

    @Test
    fun `oldest first maps to the date_added ASC total order`() {
        assertEquals("date_added ASC, _id ASC", TimelineSort.OLDEST_FIRST.sortOrder)
    }

    @Test
    fun `both orders keep the _id tiebreak for window alignment`() {
        // The tiebreak is what keeps LIMIT/OFFSET windows stable across
        // pages (see WindowPagingSource); both directions must carry it.
        TimelineSort.entries.forEach { sort ->
            val clause = sort.sortOrder
            org.junit.Assert.assertTrue(
                "sortOrder must tiebreak on _id: $clause",
                clause.endsWith(", _id DESC") || clause.endsWith(", _id ASC"),
            )
        }
    }

    @Test
    fun `sortItems newest first orders by date descending with id tiebreak`() {
        assertEquals(
            // 300 is newest; the three tied-at-100 rows fall back to _id DESC.
            listOf(
                Row(dateAddedSec = 300, id = 7),
                Row(dateAddedSec = 200, id = 5),
                Row(dateAddedSec = 100, id = 3),
                Row(dateAddedSec = 100, id = 2),
                Row(dateAddedSec = 100, id = 1),
            ),
            TimelineSort.NEWEST_FIRST.sortItems(burst, { it.dateAddedSec }, { it.id }),
        )
    }

    @Test
    fun `sortItems oldest first is the exact reverse walk of the same order`() {
        val newest = TimelineSort.NEWEST_FIRST.sortItems(burst, { it.dateAddedSec }, { it.id })
        val oldest = TimelineSort.OLDEST_FIRST.sortItems(burst, { it.dateAddedSec }, { it.id })
        // Ascending the timeline is the same total order walked backwards ---
        // the discipline that keeps paging stable in BOTH directions.
        assertEquals(newest.reversed(), oldest)
    }

    @Test
    fun `sortItems never mutates the input`() {
        val input = burst.toList()
        TimelineSort.OLDEST_FIRST.sortItems(burst, { it.dateAddedSec }, { it.id })
        assertEquals(input, burst)
    }

    @Test
    fun `sortItems handles empty input`() {
        assertEquals(
            emptyList<Row>(),
            TimelineSort.NEWEST_FIRST.sortItems(
                emptyList<Row>(),
                { it.dateAddedSec },
                { it.id },
            ),
        )
    }
}
