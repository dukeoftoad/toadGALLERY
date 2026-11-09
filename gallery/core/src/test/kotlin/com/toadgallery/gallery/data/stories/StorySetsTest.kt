package com.toadgallery.gallery.data.stories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * JVM tests for the Stories shelf's engine (r27, the MI-style
 * organisational UX round; ADR-0016 --- [StorySets] is pure Kotlin, so
 * every clustering cut is pinned here instead of living implicitly in the
 * shelf's composition).
 *
 * What is pinned:
 * - the GAP rule: consecutive items more than [StorySets.STORY_GAP_MILLIS]
 *   apart start a new cluster; a gap of EXACTLY the limit stays one
 *   cluster (the two-sided compare's boundary), the limit plus one
 *   millisecond splits;
 * - the MIN size cut: clusters smaller than [StorySets.MIN_STORY_ITEMS]
 *   are not stories;
 * - the COUNT cap: at most [StorySets.MAX_STORIES], the most recent ones
 *   (the shelf is recent highlights, not every trip since 2009);
 * - order: stories in input order (newest first), members in input
 *   order --- deterministic for a given input;
 * - nulls (not-yet-loaded paging placeholders) join no cluster;
 * - the story's date range (CALENDAR days in the grouping zone, so a
 *   story spanning a midnight labels both days honestly) and the stable
 *   `s<newest epochDay>` id;
 * - [StorySets.rangeLabel]: `"5 March 2026"` for a single-day story,
 *   `"5 March 2026 – 9 March 2026"` for a span.
 *
 * All dates use a fixed zone (never the system default) so the math is
 * deterministic on any machine; the engine is driven with `List<Long?>`
 * epoch-milli stand-ins (no `MediaItem`: it embeds `android.net.Uri`,
 * which a plain JVM test cannot construct).
 */
class StorySetsTest {

    /** UTC midnight of [date] plus [hour] hours, as an epoch MILLI. */
    private fun millisOf(date: LocalDate, hour: Int = 0): Long =
        date.atStartOfDay(ZoneOffset.UTC).plusHours(hour.toLong()).toInstant().toEpochMilli()

    /** UTC-millis stand-ins clustered by the engine. */
    private fun stories(items: List<Long?>): List<StorySet> =
        StorySets.stories(items, { it }, ZoneOffset.UTC)

    /** A fresh story-shaped cluster: [count] items [stepMillis] apart, newest FIRST. */
    private fun burst(startMillis: Long, count: Int, stepMillis: Long = 3_600_000L): List<Long> =
        (0 until count).map { startMillis - it * stepMillis }

    // ------------------------------------------------------------------
    // The gap rule
    // ------------------------------------------------------------------

    @Test
    fun `items within the gap cluster into one story`() {
        // A Saturday-out burst: three photos an hour apart.
        val start = millisOf(LocalDate.of(2026, 3, 7), 14)
        val sets = stories(burst(start, count = 3))
        assertEquals(1, sets.size)
        assertEquals(listOf(0, 1, 2), sets.single().memberIndexes)
    }

    @Test
    fun `a gap of exactly the limit stays one cluster`() {
        // The boundary the two-sided compare draws: consecutive items
        // EXACTLY STORY_GAP_MILLIS apart (36h) are still one occasion.
        val start = millisOf(LocalDate.of(2026, 3, 7), 14)
        val sets = stories(
            listOf(start, start - StorySets.STORY_GAP_MILLIS, start - 2 * StorySets.STORY_GAP_MILLIS),
        )
        assertEquals(1, sets.size)
        assertEquals(3, sets.single().count)
    }

    @Test
    fun `a gap of one millisecond past the limit splits the cluster`() {
        val start = millisOf(LocalDate.of(2026, 3, 7), 14)
        val sets = stories(
            listOf(start, start - StorySets.STORY_GAP_MILLIS - 1, start - 2 * (StorySets.STORY_GAP_MILLIS + 1)),
        )
        // Every consecutive pair is past the limit: three single-item
        // clusters, and singles are not stories.
        assertTrue(sets.isEmpty())
    }

    @Test
    fun `two bursts separated by silence become two stories in input order`() {
        // Newest-first input: last Saturday's burst, then a burst from
        // ten days before it.
        val recent = millisOf(LocalDate.of(2026, 3, 7), 14)
        val older = millisOf(LocalDate.of(2026, 2, 25), 10)
        val items = burst(recent, count = 4) + burst(older, count = 3)
        val sets = stories(items)
        assertEquals(2, sets.size)
        // The most recent story leads (input order), members ascending.
        assertEquals(listOf(0, 1, 2, 3), sets[0].memberIndexes)
        assertEquals(listOf(4, 5, 6), sets[1].memberIndexes)
        assertEquals(LocalDate.of(2026, 3, 7).toEpochDay(), sets[0].endEpochDay)
        assertEquals(LocalDate.of(2026, 2, 25).toEpochDay(), sets[1].endEpochDay)
    }

    // ------------------------------------------------------------------
    // The min-size cut
    // ------------------------------------------------------------------

    @Test
    fun `clusters below the minimum size are not stories`() {
        val day = millisOf(LocalDate.of(2026, 3, 7), 14)
        assertTrue("a single photo is not a highlight reel", stories(burst(day, count = 1)).isEmpty())
        assertTrue("two is a pair, not a moment", stories(burst(day, count = 2)).isEmpty())
        assertEquals("three is the smallest story", 1, stories(burst(day, count = 3)).size)
    }

    @Test
    fun `a small cluster never swallows a qualifying one`() {
        // One lone photo between two real bursts: it joins no story and
        // splits nothing.
        val burst1 = millisOf(LocalDate.of(2026, 3, 7), 14)
        val lone = millisOf(LocalDate.of(2026, 3, 3), 9)
        val burst2 = millisOf(LocalDate.of(2026, 2, 27), 16)
        val items = burst(burst1, count = 3) + listOf(lone) + burst(burst2, count = 3)
        val sets = stories(items)
        assertEquals(2, sets.size)
        assertEquals(listOf(0, 1, 2), sets[0].memberIndexes)
        assertEquals(listOf(4, 5, 6), sets[1].memberIndexes)
    }

    // ------------------------------------------------------------------
    // The count cap
    // ------------------------------------------------------------------

    @Test
    fun `at most MAX_STORIES survive and the most recent ones win`() {
        // Ten qualifying bursts, ten days apart: only the first
        // MAX_STORIES in input order (the most recent ones) survive.
        val items = (0 until 10).flatMap { i ->
            burst(millisOf(LocalDate.of(2026, 3, 7).minusDays(i.toLong() * 10), 12), count = 3)
        }
        val sets = stories(items)
        assertEquals(StorySets.MAX_STORIES, sets.size)
        // The newest burst leads; the cut keeps the FIRST eight, so the
        // story shown last is the 8th-newest burst (indexes 21..23).
        assertEquals(LocalDate.of(2026, 3, 7).toEpochDay(), sets.first().endEpochDay)
        assertEquals(LocalDate.of(2026, 3, 7).minusDays(70).toEpochDay(), sets.last().endEpochDay)
    }

    // ------------------------------------------------------------------
    // Null placeholders
    // ------------------------------------------------------------------

    @Test
    fun `null placeholders join no cluster and never split one`() {
        val start = millisOf(LocalDate.of(2026, 3, 7), 14)
        val sets = stories(listOf(start, null, start - 3_600_000L, null, start - 7_200_000L))
        assertEquals(1, sets.size)
        assertEquals(listOf(0, 2, 4), sets.single().memberIndexes)
    }

    @Test
    fun `empty and null-only inputs yield no stories`() {
        assertTrue(stories(emptyList()).isEmpty())
        assertTrue(stories(listOf<Long?>(null, null)).isEmpty())
    }

    // ------------------------------------------------------------------
    // The date range and the id
    // ------------------------------------------------------------------

    @Test
    fun `a story spanning midnight labels both calendar days`() {
        // 22:00 / 23:00 on the 7th and 00:30 on the 8th: one cluster (the
        // widest gap 90 minutes) whose range honestly spans both days ---
        // THREE members, the min-3 cut would drop a two-photo "story".
        val evening = millisOf(LocalDate.of(2026, 3, 7), 22)
        val night = millisOf(LocalDate.of(2026, 3, 7), 23)
        val morning = millisOf(LocalDate.of(2026, 3, 8), 0) + 30 * 60_000L
        val sets = stories(listOf(morning, night, evening))
        assertEquals(1, sets.size)
        val story = sets.single()
        assertEquals(LocalDate.of(2026, 3, 7).toEpochDay(), story.startEpochDay)
        assertEquals(LocalDate.of(2026, 3, 8).toEpochDay(), story.endEpochDay)
        // The id is the NEWEST member's day (the stable shelf key).
        assertEquals("s${LocalDate.of(2026, 3, 8).toEpochDay()}", story.id)
        // The cover is the newest member (member order == input order).
        assertEquals(0, story.coverIndex)
    }

    @Test
    fun `a single-day story collapses start and end onto that day`() {
        val day = millisOf(LocalDate.of(2026, 3, 7), 14)
        val sets = stories(burst(day, count = 3))
        val story = sets.single()
        assertEquals(story.startEpochDay, story.endEpochDay)
        assertEquals(LocalDate.of(2026, 3, 7).toEpochDay(), story.startEpochDay)
        assertEquals("s${story.endEpochDay}", story.id)
    }

    // ------------------------------------------------------------------
    // rangeLabel
    // ------------------------------------------------------------------

    @Test
    fun `rangeLabel renders a single-day story as one localized date`() {
        val day = LocalDate.of(2026, 3, 7)
        val story = StorySet(
            id = "s${day.toEpochDay()}",
            startEpochDay = day.toEpochDay(),
            endEpochDay = day.toEpochDay(),
            memberIndexes = listOf(0, 1, 2),
        )
        assertEquals("7 March 2026", StorySets.rangeLabel(story, Locale.ENGLISH))
    }

    @Test
    fun `rangeLabel renders a multi-day story as an en-dash span`() {
        val start = LocalDate.of(2026, 3, 5)
        val end = LocalDate.of(2026, 3, 9)
        val story = StorySet(
            id = "s${end.toEpochDay()}",
            startEpochDay = start.toEpochDay(),
            endEpochDay = end.toEpochDay(),
            memberIndexes = listOf(0, 1, 2, 3),
        )
        assertEquals("5 March 2026 – 9 March 2026", StorySets.rangeLabel(story, Locale.ENGLISH))
    }

    @Test
    fun `rangeLabel honors the locale parameter`() {
        // March is "mars" in French: proves the locale flows into the
        // formatter instead of the English pattern winning silently.
        val day = LocalDate.of(2026, 3, 7)
        val story = StorySet(
            id = "s${day.toEpochDay()}",
            startEpochDay = day.toEpochDay(),
            endEpochDay = day.toEpochDay(),
            memberIndexes = listOf(0, 1, 2),
        )
        assertEquals("7 mars 2026", StorySets.rangeLabel(story, Locale.FRENCH))
    }
}
