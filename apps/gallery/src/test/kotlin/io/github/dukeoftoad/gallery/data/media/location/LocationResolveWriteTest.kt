package io.github.dukeoftoad.gallery.data.media.location

import io.github.dukeoftoad.gallery.metadata.GpsCoordinates
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the PURE location write mapping ([resolveLocationWrite])
 * --- the r16 decision table, carried forward by the r20 round unchanged
 * (the combined single-read pass calls exactly this function; its
 * end-to-end matrix lives in `CombinedExifIndexerTest` in the
 * scan-pipeline package, where the pass now lives).
 */
class LocationResolveWriteTest {

    private val sf = GpsCoordinates(latitude = 37.7749, longitude = -122.4194)

    @Test
    fun `the resolveLocationWrite decision table is exhaustive and honest`() {
        val present = GpsEvidence(sf, MetadataAvailability.PRESENT)

        // Readable + PRESENT -> coordinates + EXIF_GPS.
        val hit = resolveLocationWrite(gpsReadable = true, exifCapable = true, evidence = present)
        assertEquals(37.7749, hit.latitude!!, 0.0)
        assertEquals(-122.4194, hit.longitude!!, 0.0)
        assertEquals(ItemLocationSource.EXIF_GPS, hit.source)

        // Ungranted + EXIF-capable -> withheld provenance, no coordinates.
        assertEquals(
            ResolvedLocation(null, null, ItemLocationSource.MEDIA_LOCATION_UNGRANTED),
            resolveLocationWrite(gpsReadable = false, exifCapable = true, evidence = present),
        )
        // Ungranted + not EXIF-capable -> plain nulls (the redaction is moot).
        assertEquals(
            ResolvedLocation(null, null, null),
            resolveLocationWrite(gpsReadable = false, exifCapable = false, evidence = present),
        )
        // Ungranted + unopenable is the same story: no bytes, no claim.
        assertEquals(
            ResolvedLocation(null, null, ItemLocationSource.MEDIA_LOCATION_UNGRANTED),
            resolveLocationWrite(gpsReadable = false, exifCapable = true, evidence = null),
        )
        // Readable + failed open -> honest nulls (for a changed row, stale
        // coordinates must NOT survive as truth).
        assertEquals(
            ResolvedLocation(null, null, null),
            resolveLocationWrite(gpsReadable = true, exifCapable = true, evidence = null),
        )
        // Readable + each non-PRESENT availability -> nulls, one per reason.
        for (availability in listOf(
            MetadataAvailability.ABSENT,
            MetadataAvailability.UNSUPPORTED,
            MetadataAvailability.UNREADABLE,
        )) {
            assertEquals(
                ResolvedLocation(null, null, null),
                resolveLocationWrite(
                    gpsReadable = true,
                    exifCapable = true,
                    evidence = GpsEvidence(null, availability),
                ),
            )
        }
    }

    @Test
    fun `a PRESENT flag with null coordinates is defended against - never a trusted half-write`() {
        val broken = GpsEvidence(null, MetadataAvailability.PRESENT)
        assertEquals(
            ResolvedLocation(null, null, null),
            resolveLocationWrite(gpsReadable = true, exifCapable = true, evidence = broken),
        )
    }

    @Test
    fun `a prime-meridian fix survives as a coordinate - W is negative, not absent`() {
        // A Western-hemisphere fix parsed at the meridian edge is a REAL
        // coordinate (ExifMath already normalizes -0.0 to +0.0); the
        // mapping must not "helpfully" null anything.
        val greenwich = GpsCoordinates(latitude = 51.4779, longitude = 0.0)
        val resolved = resolveLocationWrite(
            gpsReadable = true,
            exifCapable = true,
            evidence = GpsEvidence(greenwich, MetadataAvailability.PRESENT),
        )
        assertEquals(51.4779, resolved.latitude!!, 0.0)
        assertEquals(0.0, resolved.longitude!!, 0.0)
        assertEquals(ItemLocationSource.EXIF_GPS, resolved.source)
    }
}
