package io.github.dukeoftoad.gallery.metadata.edit

import io.github.dukeoftoad.gallery.metadata.exif.ExifMath
import io.github.dukeoftoad.gallery.metadata.exif.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure write codec (Phase 4 write round; ADR-0016): the
 * field-to-tag mapping, the GPS decimal-to-DMS conversion (and its
 * round-trip back through the read layer's own [ExifMath] rules), the
 * offset text form, the editor prefill and the read-back assembly.
 *
 * The tag names this codec writes are the androidx.exifinterface 1.4.1
 * string values (verified from the pinned sources; see
 * [EditableExifTags]' KDoc), so an [ExifTagWrite] list is byte-literal what
 * the writer shell hands to `setAttribute`.
 */
class EditableExifCodecTest {

    private fun writes(field: MediaEditField, primary: String, secondary: String = ""): FieldEditResult =
        EditableExifCodec.writesFor(field, MetadataEditInput(primary = primary, secondary = secondary))

    private fun validWrites(field: MediaEditField, primary: String, secondary: String = ""): List<ExifTagWrite> =
        writes(field, primary, secondary) as FieldEditResult.Valid

    // ---- DATE_TIME ----

    @Test
    fun `valid datetime plus offset writes both tags`() {
        val writes = validWrites(
            MediaEditField.DATE_TIME,
            "2024:05:17 09:30:00",
            "+02:00",
        )
        assertEquals(
            listOf(
                ExifTagWrite(EditableExifTags.DATE_TIME_ORIGINAL, "2024:05:17 09:30:00"),
                ExifTagWrite(EditableExifTags.OFFSET_TIME_ORIGINAL, "+02:00"),
            ),
            writes,
        )
    }

    @Test
    fun `input is trimmed before writing`() {
        val writes = validWrites(
            MediaEditField.DATE_TIME,
            "  2024:05:17 09:30:00  ",
            " +02:00 ",
        )
        assertEquals(EditableExifTags.DATE_TIME_ORIGINAL, writes[0].tag)
        assertEquals("2024:05:17 09:30:00", writes[0].value)
        assertEquals(EditableExifTags.OFFSET_TIME_ORIGINAL, writes[1].tag)
        assertEquals("+02:00", writes[1].value)
    }

    @Test
    fun `blank offset CLEARS the offset tag`() {
        // "No zone evidence" is a legitimate camera state; clearing the tag
        // is how it is expressed (setAttribute(tag, null) removes, verified).
        val writes = validWrites(MediaEditField.DATE_TIME, "2024:05:17 09:30:00", "   ")
        assertEquals(
            listOf(
                ExifTagWrite(EditableExifTags.DATE_TIME_ORIGINAL, "2024:05:17 09:30:00"),
                ExifTagWrite(EditableExifTags.OFFSET_TIME_ORIGINAL, null),
            ),
            writes,
        )
    }

    @Test
    fun `malformed datetimes are refused with DATETIME`() {
        for (bad in listOf(
            "",                    // empty: the datetime must be a real instant
            "2024:05:17",          // time missing
            "2024-05-17 09:30:00", // wrong separators
            "2024:13:01 00:00:00", // impossible month
            "2024:02:30 00:00:00", // impossible day
            "  :  :     :  ",      // the EXIF placeholder shape
        )) {
            assertEquals(
                FieldInvalidReason.DATETIME,
                (writes(MediaEditField.DATE_TIME, bad) as FieldEditResult.Invalid).reason,
            )
        }
    }

    @Test
    fun `malformed offsets are refused with OFFSET`() {
        for (bad in listOf("2:00", "+2:00", "+0200", "+25:00", "+02:60", "utc", "02:00")) {
            assertEquals(
                FieldInvalidReason.OFFSET,
                (writes(MediaEditField.DATE_TIME, "2024:05:17 09:30:00", bad) as FieldEditResult.Invalid).reason,
            )
        }
    }

    // ---- GPS ----

    @Test
    fun `valid coordinate pair writes four tags with reference characters carrying the signs`() {
        val writes = validWrites(MediaEditField.GPS, "-33.865143", "151.209900")
        // Structure, not exact rounding: the ref tags carry the signs, the
        // value tags are unsigned DMS rationals in the library's
        // `d/1,m/1,s/10000000` form, and each coordinate re-parses (pinned
        // precisely in the round-trip test below).
        assertEquals(4, writes.size)
        assertEquals(EditableExifTags.GPS_LATITUDE_REF to "S", writes[0].tag to writes[0].value)
        assertEquals(EditableExifTags.GPS_LONGITUDE_REF to "E", writes[2].tag to writes[2].value)
        val dms = Regex("^\\d+/1,\\d+/1,\\d+/10000000$")
        assertTrue(writes[1].tag == EditableExifTags.GPS_LATITUDE && writes[1].value!!.matches(dms))
        assertTrue(writes[3].tag == EditableExifTags.GPS_LONGITUDE && writes[3].value!!.matches(dms))
    }

    @Test
    fun `both coordinates blank clears the whole GPS block`() {
        assertEquals(
            listOf(
                ExifTagWrite(EditableExifTags.GPS_LATITUDE, null),
                ExifTagWrite(EditableExifTags.GPS_LATITUDE_REF, null),
                ExifTagWrite(EditableExifTags.GPS_LONGITUDE, null),
                ExifTagWrite(EditableExifTags.GPS_LONGITUDE_REF, null),
            ),
            validWrites(MediaEditField.GPS, "  ", ""),
        )
    }

    @Test
    fun `exactly one blank coordinate is refused --- a half GPS block is corrupt`() {
        assertEquals(
            FieldInvalidReason.LATITUDE,
            (writes(MediaEditField.GPS, "", "151.2") as FieldEditResult.Invalid).reason,
        )
        assertEquals(
            FieldInvalidReason.LONGITUDE,
            (writes(MediaEditField.GPS, "-33.86", " ") as FieldEditResult.Invalid).reason,
        )
    }

    @Test
    fun `out-of-range or non-numeric coordinates are refused`() {
        assertEquals(
            FieldInvalidReason.LATITUDE,
            (writes(MediaEditField.GPS, "91.0", "0.0") as FieldEditResult.Invalid).reason,
        )
        assertEquals(
            FieldInvalidReason.LATITUDE,
            (writes(MediaEditField.GPS, "north", "0.0") as FieldEditResult.Invalid).reason,
        )
        assertEquals(
            FieldInvalidReason.LONGITUDE,
            (writes(MediaEditField.GPS, "0.0", "-180.5") as FieldEditResult.Invalid).reason,
        )
    }

    @Test
    fun `boundary coordinates are valid`() {
        assertTrue(writes(MediaEditField.GPS, "90.0", "-180.0") is FieldEditResult.Valid)
        assertTrue(writes(MediaEditField.GPS, "-90.0", "180.0") is FieldEditResult.Valid)
        assertTrue(writes(MediaEditField.GPS, "0.0", "0.0") is FieldEditResult.Valid)
    }

    @Test
    fun `every DMS write re-parses under the read layer's own rules`() {
        // The write codec must never produce a value our strict reader
        // rejects: round-trip a spread of coordinates through
        // decimalToDmsText -> gpsRationalToDecimalDegrees (the exact pair
        // the Details view uses to show the edit's result).
        for (decimal in listOf(
            0.0, 0.125, 1.0, 37.77264, 51.5007, 89.9999999, -0.0000001,
            -33.865143, -89.9999999, 180.0, -179.9999999, 122.4194,
        )) {
            val isLatitude = decimal >= -90.0 && decimal <= 90.0
            val text = EditableExifCodec.decimalToDmsText(decimal)
            val parts = text.split(",")
            assertEquals(3, parts.size)
            val degrees = ExifMath.parseRationalText(parts[0])
            val minutes = ExifMath.parseRationalText(parts[1])
            val seconds = ExifMath.parseRationalText(parts[2])
            assertTrue(degrees != null && minutes != null && seconds != null)
            val reference = if (isLatitude) {
                if (decimal >= 0.0) "N" else "S"
            } else {
                if (decimal >= 0.0) "E" else "W"
            }
            val parsed = ExifMath.gpsRationalToDecimalDegrees(degrees!!, minutes!!, seconds!!, reference)
            assertTrue(
                "expected $decimal back from $text, got $parsed",
                parsed != null && kotlin.math.abs(parsed - decimal) < 1e-6,
            )
        }
    }

    @Test
    fun `seconds rounding to 60 carries into the minute instead of writing a malformed DMS`() {
        // androidx's own convertDecimalDegree can emit 60.0 s / 60' on
        // rounding; our writer normalizes the carry (the KDoc documents the
        // deliberate fix). 1 - 1e-14 degrees rounds its seconds to exactly
        // 60 s, which must roll into the minute and then the degree.
        assertEquals("1/1,0/1,0/10000000", EditableExifCodec.decimalToDmsText(0.99999999999999))
    }

    // ---- COPYRIGHT / COMMENT ----

    @Test
    fun `copyright and comment write trimmed text`() {
        assertEquals(
            listOf(ExifTagWrite(EditableExifTags.COPYRIGHT, "© toadGALLERY user")),
            validWrites(MediaEditField.COPYRIGHT, "  © toadGALLERY user  "),
        )
        assertEquals(
            listOf(ExifTagWrite(EditableExifTags.USER_COMMENT, "a note")),
            validWrites(MediaEditField.COMMENT, "a note"),
        )
    }

    @Test
    fun `blank copyright and comment CLEAR the tags`() {
        assertEquals(
            listOf(ExifTagWrite(EditableExifTags.COPYRIGHT, null)),
            validWrites(MediaEditField.COPYRIGHT, "   "),
        )
        assertEquals(
            listOf(ExifTagWrite(EditableExifTags.USER_COMMENT, null)),
            validWrites(MediaEditField.COMMENT, ""),
        )
    }

    // ---- Modelled but unwritable fields ----

    @Test
    fun `keywords rating and colour label are refused with UNSUPPORTED_FIELD`() {
        for (field in listOf(
            MediaEditField.KEYWORDS,
            MediaEditField.RATING,
            MediaEditField.COLOUR_LABEL,
        )) {
            assertEquals(
                FieldInvalidReason.UNSUPPORTED_FIELD,
                (writes(field, "anything") as FieldEditResult.Invalid).reason,
            )
        }
    }

    // ---- Offset text form ----

    @Test
    fun `offsetTimeText renders signed HH-colon-MM and re-parses`() {
        assertEquals("+02:00", EditableExifCodec.offsetTimeText(120))
        assertEquals("-07:30", EditableExifCodec.offsetTimeText(-450))
        assertEquals("+00:00", EditableExifCodec.offsetTimeText(0))
        assertEquals("+14:00", EditableExifCodec.offsetTimeText(14 * 60))
        assertEquals("-00:01", EditableExifCodec.offsetTimeText(-1))
        for (minutes in listOf(120, -450, 0, 14 * 60, -14 * 60, 1)) {
            assertEquals(minutes, ExifMath.parseExifOffsetTime(EditableExifCodec.offsetTimeText(minutes)))
        }
    }

    // ---- Prefill ----

    @Test
    fun `prefill seeds each field from the read-back values`() {
        val values = EditableExifCurrentValues(
            dateTimeOriginal = "2024:05:17 09:30:00",
            offsetTimeOriginal = "+02:00",
            gpsLatitude = -33.865143,
            gpsLongitude = 151.2099,
            copyright = "© me",
            userComment = "note",
        )
        assertEquals(
            MetadataEditInput("2024:05:17 09:30:00", "+02:00"),
            EditableExifCodec.prefill(MediaEditField.DATE_TIME, values),
        )
        assertEquals(
            MetadataEditInput("-33.865143", "151.2099"),
            EditableExifCodec.prefill(MediaEditField.GPS, values),
        )
        assertEquals(
            MetadataEditInput("© me", ""),
            EditableExifCodec.prefill(MediaEditField.COPYRIGHT, values),
        )
        assertEquals(
            MetadataEditInput("note", ""),
            EditableExifCodec.prefill(MediaEditField.COMMENT, values),
        )
    }

    @Test
    fun `prefill with null values or null block is empty input`() {
        val empty = EditableExifCurrentValues(
            dateTimeOriginal = null,
            offsetTimeOriginal = null,
            gpsLatitude = null,
            gpsLongitude = null,
            copyright = null,
            userComment = null,
        )
        assertEquals(
            MetadataEditInput(),
            EditableExifCodec.prefill(MediaEditField.DATE_TIME, empty),
        )
        assertEquals(
            MetadataEditInput(),
            EditableExifCodec.prefill(MediaEditField.GPS, null),
        )
        assertEquals(
            MetadataEditInput(),
            EditableExifCodec.prefill(MediaEditField.COPYRIGHT, null),
        )
    }

    // ---- Read-back assembly ----

    @Test
    fun `assembleCurrentValues parses the getAttribute string forms`() {
        // 51/1,30/1,25200000/1e7 = 51 + 30/60 + 2.52/3600 = 51.5007 and
        // 0/1,7/1,285600000/1e7 = 7/60 + 28.56/3600 = 0.1246 (hand-exact
        // rationals, so the parse-back assertion carries no fp rounding).
        val values = EditableExifCodec.assembleCurrentValues(
            mapOf(
                EditableExifTags.DATE_TIME_ORIGINAL to "2024:05:17 09:30:00",
                EditableExifTags.OFFSET_TIME_ORIGINAL to "+09:00",
                EditableExifTags.GPS_LATITUDE to "51/1,30/1,25200000/10000000",
                EditableExifTags.GPS_LATITUDE_REF to "N",
                EditableExifTags.GPS_LONGITUDE to "0/1,7/1,285600000/10000000",
                EditableExifTags.GPS_LONGITUDE_REF to "W",
                EditableExifTags.COPYRIGHT to "© me",
                EditableExifTags.USER_COMMENT to "note",
            ),
        )
        assertEquals("2024:05:17 09:30:00", values.dateTimeOriginal)
        assertEquals("+09:00", values.offsetTimeOriginal)
        assertEquals(51.5007, values.gpsLatitude!!, 1e-9)
        assertEquals(-0.1246, values.gpsLongitude!!, 1e-9)
        assertEquals("© me", values.copyright)
        assertEquals("note", values.userComment)
    }

    @Test
    fun `assembleCurrentValues degrades a half GPS block to null coordinates`() {
        // A latitude without its reference character is corrupt, not partial
        // data: the same rule the read layer applies (a missing reference or
        // an unparseable component means the coordinate is unusable, and a
        // GPS block always writes both --- the editor prefills neither).
        val values = EditableExifCodec.assembleCurrentValues(
            mapOf(
                EditableExifTags.GPS_LATITUDE to "51/1,30/1,25200000/10000000",
                EditableExifTags.GPS_LONGITUDE to "0/1,7/1,285600000/10000000",
            ),
        )
        assertNull(values.gpsLatitude)
        assertNull(values.gpsLongitude)
    }

    @Test
    fun `assembleCurrentValues treats blank strings as unset`() {
        val values = EditableExifCodec.assembleCurrentValues(
            mapOf(
                EditableExifTags.COPYRIGHT to "   ",
                EditableExifTags.USER_COMMENT to "",
            ),
        )
        assertNull(values.copyright)
        assertNull(values.userComment)
        assertNull(values.dateTimeOriginal)
    }

    @Test
    fun `gpsWrites and decimalToDmsText agree with the read-back parse`() {
        val writes = EditableExifCodec.gpsWrites(51.5007, -0.1246)
        val latitudeText = writes.first { it.tag == EditableExifTags.GPS_LATITUDE }.value!!
        val latitudeRef = writes.first { it.tag == EditableExifTags.GPS_LATITUDE_REF }.value!!
        val parts = latitudeText.split(",")
        val decimal = ExifMath.gpsRationalToDecimalDegrees(
            ExifMath.parseRationalText(parts[0]) ?: Rational(0, 1),
            ExifMath.parseRationalText(parts[1]) ?: Rational(0, 1),
            ExifMath.parseRationalText(parts[2]) ?: Rational(0, 1),
            latitudeRef,
        )
        assertEquals(51.5007, decimal!!, 1e-6)
    }
}
