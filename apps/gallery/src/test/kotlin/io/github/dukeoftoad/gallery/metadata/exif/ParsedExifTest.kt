package io.github.dukeoftoad.gallery.metadata.exif

import io.github.dukeoftoad.gallery.metadata.CameraInfo
import io.github.dukeoftoad.gallery.metadata.ExposureInfo
import io.github.dukeoftoad.gallery.metadata.GpsCoordinates
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [ParsedExif] --- the pure assembly step between
 * `androidx.exifinterface` string extraction and the
 * [io.github.dukeoftoad.gallery.metadata.MediaMetadataSnapshot]
 * (ADR-0016 seam pattern). The tag maps here are exactly the strings
 * `ExifInterface.getAttribute` returns, so the degradation rules (absent
 * vs unreadable vs partial) are pinned without any Android runtime.
 */
class ParsedExifTest {

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    fun `full tag map assembles a complete snapshot`() {
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.MAKE to "Google",
                ExifTags.MODEL to "Pixel 8",
                ExifTags.LENS_MODEL to "Pixel 8 main camera",
                ExifTags.F_NUMBER to "17/10",
                ExifTags.EXPOSURE_TIME to "1/250",
                ExifTags.ISO_SPEED_RATINGS to "400",
                ExifTags.FOCAL_LENGTH to "48/10",
                ExifTags.IMAGE_WIDTH to "4080",
                ExifTags.IMAGE_LENGTH to "3072",
                ExifTags.ORIENTATION to "6",
                ExifTags.DATE_TIME_ORIGINAL to "2024:05:17 09:30:00",
                ExifTags.GPS_LATITUDE to "51/1,30/1,0/1",
                ExifTags.GPS_LATITUDE_REF to "N",
                ExifTags.GPS_LONGITUDE to "0/1,7/1,30/1",
                ExifTags.GPS_LONGITUDE_REF to "W",
            ),
        ).toSnapshot(fileSizeBytes = 4_000_000L, mimeType = "image/jpeg")

        assertEquals("image/jpeg", snapshot.mimeType)
        assertEquals(4_000_000L, snapshot.fileSizeBytes)
        assertEquals(4080, snapshot.width)
        assertEquals(3072, snapshot.height)
        assertEquals(90, snapshot.orientationDegrees)
        assertEquals(MetadataAvailability.PRESENT, snapshot.camera.availability)
        assertEquals(
            CameraInfo("Google", "Pixel 8", "Pixel 8 main camera"),
            snapshot.camera.value,
        )
        assertEquals(MetadataAvailability.PRESENT, snapshot.exposure.availability)
        assertEquals(
            ExposureInfo(
                fNumber = 1.7,
                exposureSeconds = 0.004,
                iso = 400,
                focalLengthMm = 4.8,
            ),
            snapshot.exposure.value,
        )
        assertEquals(MetadataAvailability.PRESENT, snapshot.gps.availability)
        val gps = snapshot.gps.value!!
        assertEquals(51.5, gps.latitude, 1e-12)
        assertEquals(-0.125, gps.longitude, 1e-12)
        assertEquals(MetadataAvailability.PRESENT, snapshot.captureDates.exif)
        assertEquals(MetadataAvailability.ABSENT, snapshot.captureDates.container)
        assertEquals(MetadataAvailability.UNSUPPORTED, snapshot.captureDates.xmp)
        assertNotNull(snapshot.exifCaptureDateMillis)
    }

    @Test
    fun `photographic sensitivity replaces the legacy iso tag`() {
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.ISO_SPEED_RATINGS to "garbage",
                ExifTags.PHOTOGRAPHIC_SENSITIVITY to "800",
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        val exposure = snapshot.exposure.value!!
        assertEquals(800, exposure.iso)
    }

    // ------------------------------------------------------------------
    // Missing tags -> ABSENT
    // ------------------------------------------------------------------

    @Test
    fun `empty tag map reports every block absent`() {
        val snapshot = ParsedExif(emptyMap())
            .toSnapshot(fileSizeBytes = 100L, mimeType = "image/jpeg")

        assertEquals(MetadataAvailability.ABSENT, snapshot.camera.availability)
        assertNull(snapshot.camera.value)
        assertEquals(MetadataAvailability.ABSENT, snapshot.exposure.availability)
        assertEquals(MetadataAvailability.ABSENT, snapshot.gps.availability)
        assertEquals(MetadataAvailability.ABSENT, snapshot.captureDates.exif)
        assertNull(snapshot.exifCaptureDateMillis)
        // Structural fields degrade to the "unknown" convention.
        assertEquals(0, snapshot.width)
        assertEquals(0, snapshot.height)
        assertEquals(0, snapshot.orientationDegrees)
    }

    @Test
    fun `camera block with only a make is present with null fields`() {
        val snapshot = ParsedExif(mapOf(ExifTags.MAKE to "   Nikon   "))
            .toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.PRESENT, snapshot.camera.availability)
        assertEquals(CameraInfo("Nikon", null, null), snapshot.camera.value)
    }

    @Test
    fun `whitespace-only tags count as missing`() {
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.MAKE to "   ",
                ExifTags.MODEL to "",
                ExifTags.GPS_LATITUDE to " ",
                ExifTags.DATE_TIME_ORIGINAL to "\t",
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.ABSENT, snapshot.camera.availability)
        assertEquals(MetadataAvailability.ABSENT, snapshot.gps.availability)
        assertEquals(MetadataAvailability.ABSENT, snapshot.captureDates.exif)
    }

    // ------------------------------------------------------------------
    // Present-but-corrupt -> UNREADABLE
    // ------------------------------------------------------------------

    @Test
    fun `garbage exposure numbers report the block unreadable`() {
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.F_NUMBER to "f/2.8",
                ExifTags.EXPOSURE_TIME to "0/0",
                ExifTags.ISO_SPEED_RATINGS to "ISO 400",
                ExifTags.FOCAL_LENGTH to "24mm",
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.UNREADABLE, snapshot.exposure.availability)
        assertNull(snapshot.exposure.value)
    }

    @Test
    fun `one salvageable exposure value keeps the block present`() {
        // A corrupt f-number must not hide a good ISO --- partial data
        // degrades per-field, not per-block.
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.F_NUMBER to "f/2.8",
                ExifTags.ISO_SPEED_RATINGS to "100",
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.PRESENT, snapshot.exposure.availability)
        val exposure = snapshot.exposure.value!!
        assertNull(exposure.fNumber)
        assertNull(exposure.exposureSeconds)
        assertEquals(100, exposure.iso)
        assertNull(exposure.focalLengthMm)
    }

    @Test
    fun `negative exposure time reports unreadable`() {
        val snapshot = ParsedExif(
            mapOf(ExifTags.EXPOSURE_TIME to "-1/250"),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.UNREADABLE, snapshot.exposure.availability)
    }

    @Test
    fun `malformed gps strings report the block unreadable`() {
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.GPS_LATITUDE to "not,rationals,here",
                ExifTags.GPS_LATITUDE_REF to "N",
                ExifTags.GPS_LONGITUDE to "0/1,7/1,30/1",
                ExifTags.GPS_LONGITUDE_REF to "W",
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.UNREADABLE, snapshot.gps.availability)
        assertNull(snapshot.gps.value)
    }

    @Test
    fun `gps with only one half reports the block unreadable`() {
        // Real GPS IFDs always write both halves; a lone latitude is a
        // corrupt block, not partial coordinates.
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.GPS_LATITUDE to "51/1,30/1,0/1",
                ExifTags.GPS_LATITUDE_REF to "N",
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.UNREADABLE, snapshot.gps.availability)
    }

    @Test
    fun `out-of-range gps coordinates report the block unreadable`() {
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.GPS_LATITUDE to "91/1,0/1,0/1",
                ExifTags.GPS_LATITUDE_REF to "N",
                ExifTags.GPS_LONGITUDE to "0/1,7/1,30/1",
                ExifTags.GPS_LONGITUDE_REF to "W",
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.UNREADABLE, snapshot.gps.availability)
    }

    @Test
    fun `gps with a garbage reference reports the block unreadable`() {
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.GPS_LATITUDE to "51/1,30/1,0/1",
                ExifTags.GPS_LATITUDE_REF to "X",
                ExifTags.GPS_LONGITUDE to "0/1,7/1,30/1",
                ExifTags.GPS_LONGITUDE_REF to "W",
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.UNREADABLE, snapshot.gps.availability)
    }

    @Test
    fun `malformed capture date reports the exif source unreadable`() {
        val snapshot = ParsedExif(
            mapOf(ExifTags.DATE_TIME_ORIGINAL to "yesterday, probably"),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/jpeg")
        assertEquals(MetadataAvailability.UNREADABLE, snapshot.captureDates.exif)
        assertNull(snapshot.exifCaptureDateMillis)
    }

    // ------------------------------------------------------------------
    // Structural fields degrade without failing the snapshot
    // ------------------------------------------------------------------

    @Test
    fun `garbage structural tags fall back to unknown conventions`() {
        val snapshot = ParsedExif(
            mapOf(
                ExifTags.IMAGE_WIDTH to "lots",
                ExifTags.IMAGE_LENGTH to "-1",
                ExifTags.ORIENTATION to "9", // outside 1..8 -> no rotation
            ),
        ).toSnapshot(fileSizeBytes = 1L, mimeType = "image/png")
        assertEquals(0, snapshot.width)
        assertEquals(0, snapshot.height)
        assertEquals(0, snapshot.orientationDegrees)
        // The echoed mime type is the caller's, never derived from bytes.
        assertEquals("image/png", snapshot.mimeType)
    }
}
