package io.github.dukeoftoad.gallery.data.media.dates

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the PURE capture-date write mapping
 * ([resolveCaptureDateWrite]) --- the r12 decision table, carried forward
 * by the r20 round unchanged (the combined single-read pass calls exactly
 * this function; its end-to-end matrix lives in
 * `CombinedExifIndexerTest` in the scan-pipeline package, where the
 * indexer itself now lives).
 */
class CaptureDateResolveWriteTest {

    private val t0 = 1_700_000_000_000L

    private fun record(dateAddedMillis: Long = 500L): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = "external_primary:1",
            canonicalUri = "content://media/external/images/1",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            dateModifiedMillis = 1_000L,
            dateAddedMillis = dateAddedMillis,
        )

    @Test
    fun `no candidates persist null - never the date_added fallback`() {
        val resolved = resolveCaptureDateWrite(
            record = record(dateAddedMillis = 777L),
            evidence = CaptureDateEvidence(emptyList(), MetadataAvailability.ABSENT),
        )
        // Persisting the MediaStore date_added (import time on many
        // devices) as a "resolved capture date" would corrupt the
        // timeline; null is the honest answer for no trustworthy tag.
        assertNull(resolved)
    }

    @Test
    fun `all-unknown candidates take the null path too`() {
        val resolved = resolveCaptureDateWrite(
            record = record(dateAddedMillis = 777L),
            evidence = CaptureDateEvidence(
                candidates = listOf(DateCandidate(CaptureDateSource.UNKNOWN, 123L)),
                availability = MetadataAvailability.UNREADABLE,
            ),
        )
        assertNull(resolved)
    }

    @Test
    fun `resolver disagreement still persists the winner`() {
        val resolved = resolveCaptureDateWrite(
            record = record(dateAddedMillis = 500L),
            evidence = CaptureDateEvidence(
                candidates = listOf(
                    DateCandidate(CaptureDateSource.EXIF_ORIGINAL, t0),
                    DateCandidate(CaptureDateSource.MEDIASTORE, t0 + 600_000L),
                ),
                availability = MetadataAvailability.PRESENT,
            ),
        )
        // The winner lands normally...
        assertEquals(t0, resolved!!.epochMillis)
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
        // ...and the disagreement flag rides along at resolve time only:
        // deliberately NOT persisted this round (no column), documented
        // choice --- flag observability is a later round's schema change.
        assertTrue(resolved.sourcesDisagree)
    }

    @Test
    fun `pre-1970 negative instants survive into the write`() {
        val resolved = resolveCaptureDateWrite(
            record = record(),
            evidence = CaptureDateEvidence(
                candidates = listOf(
                    DateCandidate(
                        source = CaptureDateSource.EXIF_ORIGINAL,
                        epochMillis = -123_456L,
                        timezoneOffsetMinutes = -450,
                    ),
                ),
                availability = MetadataAvailability.PRESENT,
            ),
        )
        // A negative instant is a legitimate pre-1970 capture date
        // (scanned film), not malformed input --- audit finding F.
        assertEquals(-123_456L, resolved!!.epochMillis)
        assertEquals(-450, resolved.timezoneOffsetMinutes)
        assertEquals(CaptureDateSource.EXIF_ORIGINAL, resolved.source)
    }
}
