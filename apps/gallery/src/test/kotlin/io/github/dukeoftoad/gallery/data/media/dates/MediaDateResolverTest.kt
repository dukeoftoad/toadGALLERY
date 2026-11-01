package io.github.dukeoftoad.gallery.data.media.dates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Phase 4 capture-date resolver
 * (`docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`, item 3 and
 * "Phase 4 decisions required before sign-off" #3: the date resolver and
 * timezone semantics must be defined before Moments or chronological
 * clustering is built).
 *
 * The resolver is pinned on precedence, the disagreement flag, pre-1970
 * (negative) instants surviving the filter, unknown-source survival and
 * the empty/fallback contract --- the exact behaviors a mis-built
 * timeline would silently get wrong.
 */
class MediaDateResolverTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `precedence walks the full chain as higher sources drop out`() {
        // Deliberately listed worst-first: input order must never
        // influence the outcome.
        val worstFirst = listOf(
            DateCandidate(CaptureDateSource.MEDIASTORE, t0 + 60_000L),
            DateCandidate(CaptureDateSource.CONTAINER, t0 + 30_000L),
            DateCandidate(CaptureDateSource.EXIF_DIGITIZED, t0 + 20_000L),
            DateCandidate(CaptureDateSource.XMP, t0 + 10_000L),
            DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
        )
        // Each removal of the current winner promotes the next rank.
        val expectedWinners = listOf(
            CaptureDateSource.EXIF_ORIGINAL,
            CaptureDateSource.EXIF_DIGITIZED,
            CaptureDateSource.XMP,
            CaptureDateSource.CONTAINER,
            CaptureDateSource.MEDIASTORE,
        )
        expectedWinners.forEachIndexed { promotedCount, expected ->
            val remaining = worstFirst.filter { candidate ->
                expectedWinners.indexOf(candidate.source) >= promotedCount
            }
            val resolved = MediaDateResolver.resolve(remaining, fallbackDateAddedMillis = null)
            assertEquals("after $promotedCount promotion(s)", expected, resolved.source)
        }
    }

    @Test
    fun `near-equal sources keep the higher precedence without flagging disagreement`() {
        val resolved = MediaDateResolver.resolve(
            listOf(
                // 1500 ms apart: inside the 2000 ms tolerance, so the two
                // sources are treated as corroborating each other.
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
                DateCandidate(CaptureDateSource.MEDIASTORE, t0 + 1_500L),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(t0, resolved.epochMillis)
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
        assertFalse(resolved.sourcesDisagree)
    }

    @Test
    fun `conflicting sources still resolve to highest precedence but flag disagreement`() {
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
                DateCandidate(CaptureDateSource.XMP, t0 + 5_000L),
                DateCandidate(CaptureDateSource.MEDIASTORE, t0 + 600_000L),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(t0, resolved.epochMillis)
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
        assertTrue(resolved.sourcesDisagree)
    }

    @Test
    fun `exactly the tolerance apart counts as agreeing`() {
        // The boundary is inclusive: 2000 ms apart is noise, not conflict.
        val agreeing = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
                DateCandidate(CaptureDateSource.CONTAINER, t0 + MediaDateResolver.NEAR_EQUAL_TOLERANCE_MS),
            ),
            fallbackDateAddedMillis = null,
        )
        assertFalse(agreeing.sourcesDisagree)
        val conflicting = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
                DateCandidate(
                    CaptureDateSource.CONTAINER,
                    t0 + MediaDateResolver.NEAR_EQUAL_TOLERANCE_MS + 1L,
                ),
            ),
            fallbackDateAddedMillis = null,
        )
        assertTrue(conflicting.sourcesDisagree)
    }

    @Test
    fun `the winner's subsecond and timezone offset are carried, losers' are not`() {
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.XMP, t0 + 90_000L, timezoneOffsetMinutes = 0, subsecondMillis = 999),
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0, timezoneOffsetMinutes = 120, subsecondMillis = 500),
                DateCandidate(CaptureDateSource.CONTAINER, t0 + 60_000L, timezoneOffsetMinutes = -330, subsecondMillis = 1),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(t0, resolved.epochMillis)
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
        assertEquals(120, resolved.timezoneOffsetMinutes)
        assertEquals(500, resolved.subsecondMillis)
    }

    @Test
    fun `subsecond differences alone never flag disagreement`() {
        // The tolerance runs on whole-second epoch values; a subsecond
        // delta between sources is indistinguishable from truncation.
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0, subsecondMillis = 0),
                DateCandidate(CaptureDateSource.EXIF_DIGITIZED, t0, subsecondMillis = 900),
            ),
            fallbackDateAddedMillis = null,
        )
        assertFalse(resolved.sourcesDisagree)
        assertEquals(0, resolved.subsecondMillis)
    }

    @Test
    fun `negative epochs are valid pre-1970 instants and win precedence normally`() {
        // 1969-07-20T20:17:40Z (Apollo 11 landing) = -14182940 epoch
        // seconds --- a NEGATIVE instant the old `epochMillis >= 0` filter
        // discarded, silently dropping pre-1970 items out of the timeline
        // (audit finding F).
        val apollo11 = -14_182_940_000L
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, apollo11),
                DateCandidate(CaptureDateSource.XMP, t0),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
        assertEquals(apollo11, resolved.epochMillis)
        // The pre-1970 winner still wins precedence normally; the flag
        // honestly reports that the sources DO disagree here: 1969 vs the
        // t0 baseline is a ~1.7e12 ms gap, far beyond the 2 s tolerance.
        assertTrue(resolved.sourcesDisagree)
    }

    @Test
    fun `an early-1900s instant resolves from every source in the chain`() {
        // 1903-12-17T10:35:00Z = -2084102700 epoch seconds; each ranked
        // source must be able to carry (and win with) a pre-1970 value.
        val kittyHawk = -2_084_102_700_000L
        for (source in listOf(
            CaptureDateSource.EXIF_ORIGINAL,
            CaptureDateSource.EXIF_DIGITIZED,
            CaptureDateSource.XMP,
            CaptureDateSource.CONTAINER,
            CaptureDateSource.MEDIASTORE,
        )) {
            val resolved = MediaDateResolver.resolve(
                listOf(DateCandidate(source, kittyHawk)),
                fallbackDateAddedMillis = null,
            )
            assertEquals("source $source", source, resolved.source)
            assertEquals("source $source", kittyHawk, resolved.epochMillis)
        }
    }

    @Test
    fun `all-negative candidates resolve without falling back`() {
        // Even with a fallback in hand, usable pre-1970 candidates outrank
        // it: the fallback is for ABSENT provenance, not old dates.
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.XMP, -14_182_940_000L),
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, -2_084_102_700_000L),
            ),
            fallbackDateAddedMillis = t0,
        )
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
        assertEquals(-2_084_102_700_000L, resolved.epochMillis)
    }

    @Test
    fun `the epoch itself is a valid instant, not a sentinel`() {
        // The contract defines no 0-as-unknown sentinel here: EXIF's
        // unparseable "  :  :  " strings never become candidates at all
        // (the extractors emit nothing for a parse failure), so 0 wins
        // exactly like any other instant.
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, 0L),
                DateCandidate(CaptureDateSource.MEDIASTORE, t0),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(0L, resolved.epochMillis)
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
    }

    @Test
    fun `negative and positive instants within tolerance agree across the epoch`() {
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, -1_000L),
                DateCandidate(CaptureDateSource.MEDIASTORE, 500L),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(-1_000L, resolved.epochMillis)
        assertFalse(resolved.sourcesDisagree)
    }

    @Test
    fun `an extreme cross-epoch delta flags disagreement without wrapping`() {
        // abs(MAX_VALUE - MIN_VALUE) wraps in Long math (and abs of
        // MIN_VALUE is MIN_VALUE); the overflow-safe gap check must still
        // flag the disagreement instead of reporting a ~1 ms near-equality.
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, Long.MIN_VALUE),
                DateCandidate(CaptureDateSource.MEDIASTORE, Long.MAX_VALUE),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(Long.MIN_VALUE, resolved.epochMillis)
        assertTrue(resolved.sourcesDisagree)
    }

    @Test
    fun `unknown-source candidates are skipped as provenance-free noise`() {
        val resolved = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.UNKNOWN, t0 + 123_456L),
                DateCandidate(CaptureDateSource.CONTAINER, t0),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(CaptureDateSource.CONTAINER, resolved.source)
        assertEquals(t0, resolved.epochMillis)
        // ALL candidates UNKNOWN + a fallback: the fallback is what wins.
        val fallenBack = MediaDateResolver.resolve(
            listOf(DateCandidate(CaptureDateSource.UNKNOWN, t0 + 86_400_000L)),
            fallbackDateAddedMillis = t0,
        )
        assertEquals(CaptureDateSource.MEDIASTORE, fallenBack.source)
        assertEquals(t0, fallenBack.epochMillis)
    }

    @Test
    fun `empty candidates with a fallback resolve as a mediastore date`() {
        val resolved = MediaDateResolver.resolve(
            emptyList(),
            fallbackDateAddedMillis = t0,
        )
        assertEquals(t0, resolved.epochMillis)
        assertEquals(CaptureDateSource.MEDIASTORE, resolved.source)
        assertNull(resolved.timezoneOffsetMinutes)
        assertEquals(0, resolved.subsecondMillis)
        assertFalse(resolved.sourcesDisagree)
    }

    @Test
    fun `empty candidates with no fallback is a documented caller error`() {
        // Every call site has the MediaStore row in hand; silently
        // returning a fake instant would corrupt the timeline, so the
        // contract is an explicit exception instead.
        assertThrows(IllegalArgumentException::class.java) {
            MediaDateResolver.resolve(emptyList(), fallbackDateAddedMillis = null)
        }
    }

    @Test
    fun `same-source duplicates keep the first observation and stay quiet`() {
        val duplicates = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0, timezoneOffsetMinutes = 60),
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, duplicates.source)
        assertEquals(t0, duplicates.epochMillis)
        assertEquals(60, duplicates.timezoneOffsetMinutes)
        assertFalse(duplicates.sourcesDisagree)
        // Even two DIFFERENT values from the same source disagree with
        // nothing: disagreement is a cross-source signal by definition.
        val split = MediaDateResolver.resolve(
            listOf(
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
                DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0 + 60_000L),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(t0, split.epochMillis)
        assertFalse(split.sourcesDisagree)
    }

    @Test
    fun `an out-of-order near-equal lower source neither wins nor flags`() {
        val resolved = MediaDateResolver.resolve(
            listOf(
                // Listed FIRST and within tolerance, but lower precedence:
                // it still must not win, and must not flag disagreement.
                DateCandidate(CaptureDateSource.MEDIASTORE, t0 - 1_000L),
                DateCandidate(CaptureDateSource.EXIF_DIGITIZED, t0),
            ),
            fallbackDateAddedMillis = null,
        )
        assertEquals(CaptureDateSource.EXIF_DIGITIZED, resolved.source)
        assertEquals(t0, resolved.epochMillis)
        assertFalse(resolved.sourcesDisagree)
    }
}
