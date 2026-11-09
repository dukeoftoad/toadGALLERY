package com.toadgallery.gallery.ml.things

import com.toadgallery.gallery.ml.engine.MlEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the pure membership half of a Things collection (r39;
 * ADR-0016 --- [ThingsMembership] is pure Kotlin over plain values).
 *
 * What is pinned:
 * - the distance cut is re-applied INCLUSIVELY (`<=`), so a seam that
 *   ever returns uncut rows cannot inflate a collection (defense in
 *   depth around the engine-side cut);
 * - DEDUPE by file id keeps the FIRST occurrence's position (the
 *   MagicSearchRanking contract);
 * - the vecdb's own order is preserved VERBATIM (mapping, not sorter);
 * - the round-pinned default is exactly 0.70.
 */
class ThingsMembershipTest {

    private fun match(fileId: Long, distance: Float) = MlEngine.ClipMatch(fileId, distance)

    @Test
    fun `the cut is inclusive and re-applied to whatever the seam returned`() {
        val matches = listOf(
            match(1, 0.0f),
            match(2, 0.70f), // exactly at the default cut: a MEMBER
            match(3, 0.70f + 0.001f), // just past it: not a member
            match(4, 1.0f),
        )
        assertEquals(listOf(1L, 2L), ThingsMembership.memberFileIds(matches))
        // A tighter caller cut is honored (the parameter IS the rule):
        assertEquals(listOf(1L), ThingsMembership.memberFileIds(matches, maxDistance = 0.5f))
        // A cut above everything keeps everything:
        assertEquals(listOf(1L, 2L, 3L, 4L), ThingsMembership.memberFileIds(matches, maxDistance = 2.0f))
    }

    @Test
    fun `duplicates keep the first occurrence and the vecdb order is preserved verbatim`() {
        val matches = listOf(
            match(9, 0.1f),
            match(4, 0.2f),
            match(9, 0.3f), // a repeat of 9: dropped, 4 keeps its position
            match(1, 0.4f),
        )
        assertEquals(listOf(9L, 4L, 1L), ThingsMembership.memberFileIds(matches))
    }

    @Test
    fun `an empty match list is the empty membership`() {
        assertEquals(emptyList<Long>(), ThingsMembership.memberFileIds(emptyList()))
    }

    @Test
    fun `the round-pinned default cut is exactly zero point seven`() {
        assertEquals(0.7f, ThingsMembership.DEFAULT_MAX_DISTANCE)
    }
}
