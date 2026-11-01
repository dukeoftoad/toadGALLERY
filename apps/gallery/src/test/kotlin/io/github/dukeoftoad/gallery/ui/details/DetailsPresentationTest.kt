package io.github.dukeoftoad.gallery.ui.details

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.metadata.CameraInfo
import io.github.dukeoftoad.gallery.metadata.CaptureDateSources
import io.github.dukeoftoad.gallery.metadata.ExposureInfo
import io.github.dukeoftoad.gallery.metadata.GpsCoordinates
import io.github.dukeoftoad.gallery.metadata.MediaMetadataSnapshot
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import io.github.dukeoftoad.gallery.metadata.MetadataValue
import io.github.dukeoftoad.gallery.ui.details.DetailsPresentation.DetailsField
import io.github.dukeoftoad.gallery.ui.details.DetailsPresentation.DetailsSection
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [DetailsPresentation] mapper behind the Phase 4
 * Details screen (no android.*, no Robolectric --- ADR-0016; the Compose
 * surface itself is untested by repo convention). The pins follow the
 * audited contract the screen exists to honor:
 *
 *  - the four [MetadataAvailability] states stay VISIBLY distinct per row
 *    (a null value plus the state --- the composable renders a different
 *    string per state, so "no GPS" can never read as "GPS corrupt");
 *  - section order is stable (File, Capture date, Camera, Exposure, GPS,
 *    Metadata blocks);
 *  - the capture date carries its timezone provenance explicitly (the
 *    roadmap's "timezone semantics stated explicitly");
 *  - IPTC/XMP render UNSUPPORTED/ABSENT from the format matrix, never
 *    faked.
 */
class DetailsPresentationTest {

    private val locale = Locale.US

    /** A fully-loaded JPEG-style snapshot, the happy-path fixture. */
    private fun fullSnapshot() = MediaMetadataSnapshot(
        mimeType = "image/jpeg",
        width = 4032,
        height = 3024,
        orientationDegrees = 0,
        fileSizeBytes = 4_812_345L,
        camera = MetadataValue.present(CameraInfo("Pixel", "Pixel 8", "Pixel 8 main")),
        exposure = MetadataValue.present(
            ExposureInfo(fNumber = 1.68, exposureSeconds = 0.004, iso = 400, focalLengthMm = 6.81),
        ),
        gps = MetadataValue.present(GpsCoordinates(37.77264, -122.42345)),
        captureDates = CaptureDateSources(
            exif = MetadataAvailability.PRESENT,
            container = MetadataAvailability.ABSENT,
            xmp = MetadataAvailability.UNSUPPORTED,
        ),
        exifCaptureDateMillis = 1_715_900_000_000L,
    )

    /** The provenance-complete catalogue record the r12 scan writes. */
    private fun record() = CatalogueRecord(
        localMediaKey = "external_primary:42",
        canonicalUri = "content://media/external_primary/images/media/42",
        mimeType = "image/jpeg",
        sizeBytes = 4_812_345L,
        dateModifiedMillis = 0L,
        dateAddedMillis = 0L,
        resolvedCaptureDateMillis = 1_715_897_600_000L,
        resolvedCaptureDateZoneOffsetMinutes = -420,
        resolvedCaptureDateSource = "EXIF_ORIGINAL",
    )

    private fun rows(
        snapshot: MediaMetadataSnapshot? = fullSnapshot(),
        record: CatalogueRecord? = record(),
        mimeType: String = "image/jpeg",
        exifBlockAvailability: MetadataAvailability = MetadataAvailability.PRESENT,
        gpsVisible: Boolean = true,
        gpsHiddenNote: String? = null,
    ): List<DetailsPresentation.DetailRow> = DetailsPresentation.rows(
        snapshot = snapshot,
        record = record,
        mimeType = mimeType,
        exifBlockAvailability = exifBlockAvailability,
        locale = locale,
        gpsVisible = gpsVisible,
        gpsHiddenNote = gpsHiddenNote,
    )

    private fun row(rows: List<DetailsPresentation.DetailRow>, field: DetailsField) =
        rows.first { it.field == field }

    // ---- section order / structure ----

    @Test
    fun `sections appear in stable order`() {
        val seen = rows().map { it.section }.distinct()
        assertEquals(
            listOf(
                DetailsSection.FILE,
                DetailsSection.CAPTURE_DATE,
                DetailsSection.CAMERA,
                DetailsSection.EXPOSURE,
                DetailsSection.GPS,
                DetailsSection.METADATA_BLOCKS,
            ),
            seen,
        )
    }

    @Test
    fun `happy path rows are present with values`() {
        val r = rows()
        assertEquals(MetadataAvailability.PRESENT, row(r, DetailsField.MIME_TYPE).availability)
        assertEquals("image/jpeg", row(r, DetailsField.MIME_TYPE).value)
        assertEquals("4.8 MB", row(r, DetailsField.FILE_SIZE).value)
        assertEquals("4032 × 3024", row(r, DetailsField.DIMENSIONS).value)
        assertEquals("Pixel", row(r, DetailsField.CAMERA_MAKE).value)
        assertEquals("f/1.68", row(r, DetailsField.APERTURE).value)
        assertEquals("1/250 s", row(r, DetailsField.SHUTTER).value)
        assertEquals("ISO 400", row(r, DetailsField.ISO).value)
        assertEquals("6.81 mm", row(r, DetailsField.FOCAL_LENGTH).value)
    }

    // ---- honest degradation ----

    @Test
    fun `missing snapshot degrades exif rows to the block availability`() {
        val r = rows(snapshot = null, exifBlockAvailability = MetadataAvailability.UNREADABLE)
        assertEquals(MetadataAvailability.UNREADABLE, row(r, DetailsField.DIMENSIONS).availability)
        assertNull(row(r, DetailsField.DIMENSIONS).value)
        assertEquals(MetadataAvailability.UNREADABLE, row(r, DetailsField.CAMERA_MODEL).availability)
        assertEquals(MetadataAvailability.UNREADABLE, row(r, DetailsField.GPS_DECIMAL).availability)
    }

    @Test
    fun `unsupported format keeps capability statement on every exif row`() {
        val r = rows(
            snapshot = null,
            record = null,
            mimeType = "image/avif",
            exifBlockAvailability = MetadataAvailability.UNSUPPORTED,
        )
        assertEquals(MetadataAvailability.UNSUPPORTED, row(r, DetailsField.CAMERA_MAKE).availability)
        assertEquals(MetadataAvailability.UNSUPPORTED, row(r, DetailsField.EXIF_BLOCK).availability)
        assertEquals(MetadataAvailability.ABSENT, row(r, DetailsField.FILE_SIZE).availability)
    }

    @Test
    fun `absent gps block reads absent while unreadable reads unreadable`() {
        // A file with no GPS tags: the block is ABSENT --- never "corrupt".
        val absent = fullSnapshot().copy(gps = MetadataValue.absent())
        assertEquals(
            MetadataAvailability.ABSENT,
            row(rows(absent), DetailsField.GPS_DECIMAL).availability,
        )
        // A file whose GPS block exists but is corrupt: UNREADABLE --- a
        // diagnostics signal the UI must not flatten into "none".
        val unreadable = fullSnapshot().copy(gps = MetadataValue.unreadable())
        assertEquals(
            MetadataAvailability.UNREADABLE,
            row(rows(unreadable), DetailsField.GPS_DECIMAL).availability,
        )
    }

    @Test
    fun `location gate hides present gps without faking availability`() {
        // The ACCESS_MEDIA_LOCATION consent flow (r14) hides coordinates on
        // an ungranted 29+ device. Availability stays PRESENT --- the block
        // really did contain coordinates --- and the value carries the
        // localized note instead of numbers. Availability must never be
        // faked to ABSENT, and the hidden state must not leak digits.
        val note = "Hidden — allow Location data in Settings"
        val hidden = rows(gpsVisible = false, gpsHiddenNote = note)
        assertEquals(
            MetadataAvailability.PRESENT,
            row(hidden, DetailsField.GPS_DECIMAL).availability,
        )
        assertEquals(note, row(hidden, DetailsField.GPS_DECIMAL).value)
        assertEquals(note, row(hidden, DetailsField.GPS_DMS).value)
        // The default (visible) path still renders numbers.
        val visible = rows()
        assertTrue(row(visible, DetailsField.GPS_DECIMAL).value!!.contains("37.7"))
        // A gate-hidden item WITHOUT a note degrades to an honest blank
        // value (the ViewModel always passes the note; the default proves
        // the presentation has no hidden global state).
        val noNote = rows(gpsVisible = false)
        assertEquals(null, row(noNote, DetailsField.GPS_DECIMAL).value)
    }

    @Test
    fun `per-field nulls inside a present block read absent`() {
        // A missing LensModel must not hide a good Make (ParsedExif's
        // degradation rule): the row is PRESENT-block with an ABSENT field.
        val partial = fullSnapshot().copy(
            camera = MetadataValue.present(CameraInfo(make = "Pixel", model = "Pixel 8", lensModel = null)),
        )
        val r = rows(partial)
        assertEquals(MetadataAvailability.PRESENT, row(r, DetailsField.CAMERA_MAKE).availability)
        assertEquals(
            MetadataAvailability.ABSENT,
            row(r, DetailsField.CAMERA_LENS).availability,
        )
    }

    @Test
    fun `flash row stays honestly unsupported`() {
        // No flash field exists in the snapshot this round; the row must
        // say UNSUPPORTED, never a fake ABSENT.
        assertEquals(MetadataAvailability.UNSUPPORTED, row(rows(), DetailsField.FLASH).availability)
        assertNull(row(rows(), DetailsField.FLASH).value)
    }

    @Test
    fun `iptc and xmp headers come from the format matrix without faking`() {
        val r = rows()
        // JPEG: both wrappers are declared UNSUPPORTED this round.
        assertEquals(MetadataAvailability.UNSUPPORTED, row(r, DetailsField.IPTC_BLOCK).availability)
        assertEquals(MetadataAvailability.UNSUPPORTED, row(r, DetailsField.XMP_BLOCK).availability)
        // PNG: IPTC is declared ABSENT by the matrix --- the row follows.
        val png = rows(snapshot = null, mimeType = "image/png", exifBlockAvailability = MetadataAvailability.ABSENT)
        assertEquals(MetadataAvailability.ABSENT, row(png, DetailsField.IPTC_BLOCK).availability)
        assertEquals(MetadataAvailability.UNSUPPORTED, row(png, DetailsField.XMP_BLOCK).availability)
    }

    // ---- capture date + timezone provenance ----

    @Test
    fun `capture date renders in the recorded zone with provenance`() {
        val r = rows()
        // 2024-05-16 15:13:20 at -07:00 (the resolver's recorded
        // assumption) --- NOT the device zone, NOT UTC.
        assertEquals("2024-05-16 15:13:20", row(r, DetailsField.CAPTURE_DATE).value)
        assertEquals("-07:00 (from file)", row(r, DetailsField.CAPTURE_ZONE).value)
        assertEquals("EXIF_ORIGINAL", row(r, DetailsField.CAPTURE_SOURCE).value)
    }

    @Test
    fun `no-zone capture date states the utc assumption`() {
        val r = rows(record = record().copy(resolvedCaptureDateZoneOffsetMinutes = null))
        // The instant now renders anchored at UTC (the documented no-zone
        // convention) and the zone row is ABSENT --- honest "no decision".
        assertEquals("2024-05-16 22:13:20", row(r, DetailsField.CAPTURE_DATE).value)
        assertEquals(MetadataAvailability.ABSENT, row(r, DetailsField.CAPTURE_ZONE).availability)
        assertNull(row(r, DetailsField.CAPTURE_ZONE).value)
    }

    @Test
    fun `snapshot-only capture date falls back to the raw exif anchor`() {
        // Not-in-catalogue fallback: no record, the snapshot's raw
        // UTC-anchored millis carry the date with no zone provenance.
        val r = rows(record = null)
        assertEquals(MetadataAvailability.PRESENT, row(r, DetailsField.CAPTURE_DATE).availability)
        assertEquals("2024-05-16 22:53:20", row(r, DetailsField.CAPTURE_DATE).value)
        assertEquals(MetadataAvailability.ABSENT, row(r, DetailsField.CAPTURE_ZONE).availability)
    }

    @Test
    fun `no capture date anywhere reads absent`() {
        val r = rows(
            snapshot = fullSnapshot().copy(exifCaptureDateMillis = null),
            record = record().copy(resolvedCaptureDateMillis = null, resolvedCaptureDateSource = null),
        )
        assertEquals(MetadataAvailability.ABSENT, row(r, DetailsField.CAPTURE_DATE).availability)
        assertNull(row(r, DetailsField.CAPTURE_DATE).value)
    }

    // ---- formatters ----

    @Test
    fun `dms formatting with hemisphere carry`() {
        assertEquals("37° 46' 21.5\" N", DetailsPresentation.decimalToDms(37.77264, isLatitude = true))
        assertEquals("122° 25' 24.4\" W", DetailsPresentation.decimalToDms(-122.42345, isLatitude = false))
        // Rounding carry: 0.99999° = 3599.964" rounds to 3600.0" -> the
        // next whole degree, never "0° 59' 60.0\"".
        assertEquals("1° 0' 0.0\" E", DetailsPresentation.decimalToDms(0.99999, isLatitude = false))
        assertEquals("1° 0' 59.6\" E", DetailsPresentation.decimalToDms(1.0165555, isLatitude = false))
        // Minute carry: 59' 60" impossible -> 60' rolls into the degree.
        assertEquals("2° 0' 0.0\" S", DetailsPresentation.decimalToDms(-1.9999999, isLatitude = true))
    }

    @Test
    fun `zone offset text is iso notation`() {
        assertEquals("+02:00", DetailsPresentation.zoneOffsetText(120))
        assertEquals("-07:30", DetailsPresentation.zoneOffsetText(-450))
        assertEquals("+00:00", DetailsPresentation.zoneOffsetText(0))
        assertEquals("UTC (assumed)", DetailsPresentation.zoneOffsetText(null))
    }

    @Test
    fun `gps decimals render locale-stable`() {
        val r = rows()
        assertEquals("37.772640, -122.423450", row(r, DetailsField.GPS_DECIMAL).value)
        assertEquals(
            "37° 46' 21.5\" N 122° 25' 24.4\" W",
            row(r, DetailsField.GPS_DMS).value,
        )
    }

    @Test
    fun `gps boundary values survive formatting`() {
        assertTrue(
            DetailsPresentation.decimalToDms(-90.0, isLatitude = true).endsWith("S"),
        )
        assertTrue(
            DetailsPresentation.decimalToDms(180.0, isLatitude = false).endsWith("E"),
        )
    }

    // ---- record-shaped fallback ----

    @Test
    fun `null record with null snapshot still renders a full row list`() {
        val r = rows(snapshot = null, record = null)
        // Every section survives, degraded honestly; MIME stays present
        // (it came from the route/provider, not the record).
        val sections = r.map { it.section }.distinct()
        assertEquals(DetailsPresentation.DetailsSection.entries.toList(), sections)
        assertEquals(MetadataAvailability.PRESENT, row(r, DetailsField.MIME_TYPE).availability)
        assertEquals(MetadataAvailability.ABSENT, row(r, DetailsField.CAPTURE_DATE).availability)
    }
}
