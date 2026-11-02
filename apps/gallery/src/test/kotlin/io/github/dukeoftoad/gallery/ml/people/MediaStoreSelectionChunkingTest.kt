package io.github.dukeoftoad.gallery.ml.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the MediaStore IN-clause chunking (r25, Workstream 6;
 * ADR-0016): [MediaStoreSelection] is the PURE half of the People photo
 * query --- SQLite's classic bound-variable limit (999 before 3.32)
 * means a whole cluster's `_ID IN (?,?,…)` selection must be split into
 * bounded chunks, one query per chunk.
 *
 * What is pinned:
 * - chunks never exceed the limit (999 by default);
 * - DUPLICATE ids collapse (one photo can contribute several faces to
 *   one cluster --- the grid shows each photo once), first occurrence
 *   keeping its position;
 * - order is otherwise preserved (the chunking never re-sorts);
 * - empty input yields no chunks; a non-positive limit coerces to one
 *   id per chunk (degenerate but still correct);
 * - [MediaStoreSelection.inClause] builds the matching placeholder
 *   selection for a chunk's size (ids ride SELECTION ARGS, never the
 *   SQL text).
 */
class MediaStoreSelectionChunkingTest {

    @Test
    fun `small inputs stay in one chunk - order and duplicates`() {
        assertEquals(
            listOf(listOf(1L, 2L, 3L)),
            MediaStoreSelection.chunkIds(listOf(1L, 2L, 3L)),
        )
        // Duplicates collapse to the first occurrence's position:
        assertEquals(
            listOf(listOf(1L, 2L, 3L)),
            MediaStoreSelection.chunkIds(listOf(1L, 2L, 1L, 3L, 2L)),
        )
        assertEquals(emptyList<List<Long>>(), MediaStoreSelection.chunkIds(emptyList()))
    }

    @Test
    fun `the 999 limit splits exactly at the boundary`() {
        val exactly = (1L..999L).toList()
        assertEquals(listOf(exactly), MediaStoreSelection.chunkIds(exactly))

        val over = (1L..1000L).toList()
        val chunks = MediaStoreSelection.chunkIds(over)
        assertEquals(2, chunks.size)
        assertEquals(999, chunks[0].size)
        assertEquals(999L, chunks[0].last())
        assertEquals(listOf(1000L), chunks[1])

        // 1998 -> two full chunks; 1999 -> a third singleton:
        assertEquals(2, MediaStoreSelection.chunkIds((1L..1998L).toList()).size)
        val three = MediaStoreSelection.chunkIds((1L..1999L).toList())
        assertEquals(3, three.size)
        assertEquals(listOf(1999L), three[2])
    }

    @Test
    fun `a custom limit is honored and a non-positive one coerces to one`() {
        assertEquals(
            listOf(listOf(1L, 2L), listOf(3L, 4L), listOf(5L)),
            MediaStoreSelection.chunkIds(listOf(1L, 2L, 3L, 4L, 5L), limit = 2),
        )
        assertEquals(
            listOf(listOf(1L), listOf(2L)),
            MediaStoreSelection.chunkIds(listOf(1L, 2L), limit = 0),
        )
        assertEquals(
            listOf(listOf(1L), listOf(2L)),
            MediaStoreSelection.chunkIds(listOf(1L, 2L), limit = -7),
        )
    }

    @Test
    fun `the in-clause selection matches the chunk size with bound placeholders`() {
        assertEquals("_id IN (?)", MediaStoreSelection.inClause(1))
        assertEquals("_id IN (?,?)", MediaStoreSelection.inClause(2))
        assertEquals(
            "_id IN (" + List(999) { "?" }.joinToString(",") + ")",
            MediaStoreSelection.inClause(999),
        )
        // A custom column and a degenerate count both stay well-formed:
        assertEquals("other IN (?)", MediaStoreSelection.inClause(0, column = "other"))
        // Placeholders only --- ids ride selection args, never the SQL text
        // (pinned by the exact-equality assertions above: no literal ever
        // appears between IN ( … )).
        assertEquals("_id IN (?,?,?)", MediaStoreSelection.inClause(3))
    }
}
