package io.github.dukeoftoad.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [TimelineSort] (issue #66): the SQL sort-order mapping the
 * windowed/bucket queries send, and the in-memory ordering the snapshot
 * paths use --- pinned on the plain JVM per ADR-0016 (the enum is
 * deliberately Android-free, unlike the [MediaStoreSource] methods that
 * consume it). Since r13 the file also carries the catalogue path's
 * in-memory ordering twin, [TimelineSort.effectiveComparator], pinned in
 * the effective-instant section below.
 */
class TimelineSortTest {

    /** Lightweight stand-in for `MediaItem`'s sort keys (Uri is not JVM-buildable). */
    private data class Row(val dateAddedSec: Long, val id: Long)

    /**
     * Stand-in for a catalogue record's effective-order keys: the effective
     * instant (already `captureDateMillis ?: dateAdded`, in MILLIS) and the
     * local media key (the catalogue path's tiebreak).
     */
    private data class EffectiveRow(val effectiveMillis: Long, val key: String)

    private val burst = listOf(
        Row(dateAddedSec = 100, id = 3),
        Row(dateAddedSec = 100, id = 1),
        Row(dateAddedSec = 100, id = 2),
        Row(dateAddedSec = 300, id = 7),
        Row(dateAddedSec = 200, id = 5),
    )

    private val effectiveBurst = listOf(
        EffectiveRow(effectiveMillis = 100_000, key = "external_primary:3"),
        EffectiveRow(effectiveMillis = 100_000, key = "external_primary:1"),
        EffectiveRow(effectiveMillis = 100_000, key = "external_primary:2"),
        EffectiveRow(effectiveMillis = 300_000, key = "external_primary:7"),
        EffectiveRow(effectiveMillis = 200_000, key = "external_primary:5"),
        // A pre-1970 (negative) capture instant: legitimate, and simply the
        // smallest effective instant there is.
        EffectiveRow(effectiveMillis = -1_500_000, key = "external_primary:9"),
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

    // ------------------------------------------------------------------
    // effectiveComparator (r13): the catalogue path's in-memory twin
    // ------------------------------------------------------------------

    @Test
    fun `effectiveComparator newest first orders by effective instant descending with key tiebreak`() {
        assertEquals(
            // 300k is newest; the three tied-at-100k rows fall back to the
            // local media key DESC, exactly like the _id DESC discipline.
            listOf(
                EffectiveRow(effectiveMillis = 300_000, key = "external_primary:7"),
                EffectiveRow(effectiveMillis = 200_000, key = "external_primary:5"),
                EffectiveRow(effectiveMillis = 100_000, key = "external_primary:3"),
                EffectiveRow(effectiveMillis = 100_000, key = "external_primary:2"),
                EffectiveRow(effectiveMillis = 100_000, key = "external_primary:1"),
                // The negative (pre-1970) instant is simply the oldest.
                EffectiveRow(effectiveMillis = -1_500_000, key = "external_primary:9"),
            ),
            effectiveBurst.sortedWith(
                TimelineSort.NEWEST_FIRST.effectiveComparator(
                    dateOf = { it.effectiveMillis },
                    keyOf = { it.key },
                ),
            ),
        )
    }

    @Test
    fun `effectiveComparator oldest first is the exact reverse walk of the same order`() {
        val newest = effectiveBurst.sortedWith(
            TimelineSort.NEWEST_FIRST.effectiveComparator({ it.effectiveMillis }, { it.key }),
        )
        val oldest = effectiveBurst.sortedWith(
            TimelineSort.OLDEST_FIRST.effectiveComparator({ it.effectiveMillis }, { it.key }),
        )
        // Ascending the timeline is the same total order walked backwards ---
        // the discipline that keeps catalogue windows stable in BOTH
        // directions, exactly like sortItems above.
        assertEquals(newest.reversed(), oldest)
    }

    @Test
    fun `effectiveComparator orders pre-1970 negative instants oldest`() {
        val scanned = EffectiveRow(effectiveMillis = -1_500_000, key = "external_primary:9")
        val oldest = listOf(
            scanned,
            EffectiveRow(effectiveMillis = 0, key = "external_primary:0"), // epoch capture: legitimate
            EffectiveRow(effectiveMillis = 100_000, key = "external_primary:1"),
        ).sortedWith(
            TimelineSort.OLDEST_FIRST.effectiveComparator({ it.effectiveMillis }, { it.key }),
        )
        assertEquals(scanned, oldest.first())
        // ...and the resolved-epoch capture (0) is NOT the oldest --- only a
        // negative instant precedes it, mirroring the null-vs-0 distinction
        // the effective-instant rule documents on MediaItem.
        assertEquals(0L, oldest[1].effectiveMillis)
    }

    @Test
    fun `effectiveComparator breaks ties totally so windows cannot reorder`() {
        // Same effective instant, keys in shuffled order: the comparator
        // must pin ONE sequence (window boundary stability), not merely
        // "equal" --- compare(a, b) and compare(b, a) must disagree.
        val a = EffectiveRow(effectiveMillis = 100_000, key = "external_primary:1")
        val b = EffectiveRow(effectiveMillis = 100_000, key = "external_primary:2")
        val newest = TimelineSort.NEWEST_FIRST
            .effectiveComparator<EffectiveRow>({ it.effectiveMillis }, { it.key })
        val oldest = TimelineSort.OLDEST_FIRST
            .effectiveComparator<EffectiveRow>({ it.effectiveMillis }, { it.key })
        org.junit.Assert.assertTrue("b before a newest-first (key DESC)", newest.compare(a, b) > 0)
        org.junit.Assert.assertTrue("a before b oldest-first (key ASC)", oldest.compare(a, b) < 0)
    }
}
