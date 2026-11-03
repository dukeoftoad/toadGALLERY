package io.github.dukeoftoad.gallery.ml.search

import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure ranking half of Magic Search (r26, Workstream
 * 7; ADR-0016): vecdb order preserved VERBATIM, dedupe by file id
 * (first occurrence wins), and the limit honored AFTER the dedupe.
 */
class MagicSearchRankingTest {

    private fun match(fileId: Long, distance: Float) = MlEngine.ClipMatch(fileId, distance)

    @Test
    fun `the vecdb order is preserved verbatim - mapping, not sorter`() {
        val matches = listOf(
            match(7, 0.11f),
            match(3, 0.42f),
            match(9, 0.50f),
        )

        assertEquals(listOf(7L, 3L, 9L), MagicSearchRanking.rank(matches, limit = 10))
        assertEquals(listOf(7L, 3L, 9L), MagicSearchRanking.rank(matches, limit = 3))
    }

    @Test
    fun `duplicate file ids dedupe with the first occurrence keeping its position`() {
        val matches = listOf(
            match(5, 0.10f),
            match(5, 0.12f), // a repeat of the same photo
            match(2, 0.30f),
            match(5, 0.31f), // another repeat, later and worse
            match(8, 0.40f),
        )

        assertEquals(listOf(5L, 2L, 8L), MagicSearchRanking.rank(matches, limit = 10))
    }

    @Test
    fun `the limit is honored after the dedupe - a duplicate never consumes a slot`() {
        val matches = listOf(
            match(5, 0.10f),
            match(5, 0.12f), // deduped away first...
            match(2, 0.30f),
            match(8, 0.40f),
        )

        // With limit 2 the photo id 8 (not the duplicate) fills the slot:
        assertEquals(listOf(5L, 2L), MagicSearchRanking.rank(matches, limit = 2))
    }

    @Test
    fun `empty input ranks empty and non-positive limits coerce to one`() {
        assertEquals(emptyList<Long>(), MagicSearchRanking.rank(emptyList(), limit = 10))
        assertEquals(listOf(4L), MagicSearchRanking.rank(listOf(match(4, 0.1f)), limit = 0))
        assertEquals(listOf(4L), MagicSearchRanking.rank(listOf(match(4, 0.1f)), limit = -3))
    }
}
