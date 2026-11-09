package com.toadgallery.gallery.ml.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Magic Search query gate (r26, Workstream 7): the
 * trimmed 2..200-character bounds, pinned at every edge (ADR-0016 ---
 * [MagicSearchQuery] is pure Kotlin, the
 * `com.toadgallery.gallery.data.SearchQueryTest` discipline applied
 * to the content path).
 */
class MagicSearchQueryTest {

    @Test
    fun `blank and short queries are not searchable`() {
        assertFalse(MagicSearchQuery.isSearchable(""))
        assertFalse(MagicSearchQuery.isSearchable("   "))
        assertFalse(MagicSearchQuery.isSearchable("b"))
        assertFalse(MagicSearchQuery.isSearchable(" b "))
        assertFalse(MagicSearchQuery.isSearchable("\ta\n"))
    }

    @Test
    fun `two trimmed characters are the minimum searchable query`() {
        assertTrue(MagicSearchQuery.isSearchable("be"))
        assertTrue(MagicSearchQuery.isSearchable("  be  "))
    }

    @Test
    fun `the round-pinned cap rejects longer queries instead of truncating them`() {
        // Exactly at the cap passes...
        assertTrue(MagicSearchQuery.isSearchable("b".repeat(MagicSearchQuery.MAX_QUERY_LENGTH)))
        // ...one over does not --- refusing is the honest shape (silently
        // truncating would change what the user asked for):
        assertFalse(MagicSearchQuery.isSearchable("b".repeat(MagicSearchQuery.MAX_QUERY_LENGTH + 1)))
        // Whitespace does not count toward the bound (the gate trims):
        assertTrue(
            MagicSearchQuery.isSearchable(
                " " + "b".repeat(MagicSearchQuery.MAX_QUERY_LENGTH) + " ",
            ),
        )
        assertFalse(
            MagicSearchQuery.isSearchable(
                " " + "b".repeat(MagicSearchQuery.MAX_QUERY_LENGTH + 1) + " ",
            ),
        )
    }

    @Test
    fun `typical content queries pass`() {
        assertTrue(MagicSearchQuery.isSearchable("beach at sunset"))
        assertTrue(MagicSearchQuery.isSearchable("birthday cake"))
        assertTrue(MagicSearchQuery.isSearchable("snowy mountains, no people"))
    }
}
