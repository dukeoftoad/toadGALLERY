package com.toadgallery.gallery.metadata.exif

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [ExifMath] (Phase 4 metadata layer). No emulator, no
 * Robolectric (ADR-0016): the EXIF math is pure Kotlin by design, so GPS
 * conversion, exposure formatting, orientation mapping and date parsing
 * are pinned value-by-value here before any Android reader touches them.
 */
class ExifMathTest {

    // ------------------------------------------------------------------
    // Rational
    // ------------------------------------------------------------------

    @Test
    fun `rational toDouble divides and rejects zero denominators`() {
        assertEquals(2.8, Rational(28L, 10L).toDouble()!!, 1e-12)
        assertEquals(400.0, Rational(400L, 1L).toDouble()!!, 1e-12)
        assertEquals(-0.5, Rational(1L, -2L).toDouble()!!, 1e-12)
        assertNull(Rational(51L, 0L).toDouble())
        assertNull(Rational(0L, 0L).toDouble())
    }

    // ------------------------------------------------------------------
    // GPS: DMS -> decimal degrees
    // ------------------------------------------------------------------

    @Test
    fun `gps north and east references keep the value positive`() {
        // 51 deg 30 min 0 sec N -> 51.5
        val north = ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 1L), Rational(30L, 1L), Rational(0L, 1L), "N",
        )
        assertEquals(51.5, north!!, 1e-12)
        // 13 deg 22 min 12 sec E -> 13 + 22/60 + 12/3600
        val east = ExifMath.gpsRationalToDecimalDegrees(
            Rational(13L, 1L), Rational(22L, 1L), Rational(1200L, 100L), "E",
        )
        assertEquals(13.37, east!!, 1e-9)
    }

    @Test
    fun `gps south and west references negate the value`() {
        val south = ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 1L), Rational(30L, 1L), Rational(0L, 1L), "S",
        )
        assertEquals(-51.5, south!!, 1e-12)
        val west = ExifMath.gpsRationalToDecimalDegrees(
            Rational(0L, 1L), Rational(7L, 1L), Rational(30L, 1L), "W",
        )
        assertEquals(-0.125, west!!, 1e-12)
    }

    @Test
    fun `gps west of zero normalizes to positive zero`() {
        val west = ExifMath.gpsRationalToDecimalDegrees(
            Rational(0L, 1L), Rational(0L, 1L), Rational(0L, 1L), "W",
        )
        // "W 0" must cache and render as +0.0, not -0.0: the delta-based
        // assert cannot tell them apart (== says they are equal), so the
        // raw bit pattern is pinned.
        assertEquals(0L, java.lang.Double.doubleToRawLongBits(west!!))
    }

    @Test
    fun `gps reference is case-insensitive and whitespace-tolerant`() {
        val lowercase = ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 1L), Rational(30L, 1L), Rational(0L, 1L), "n",
        )
        assertEquals(51.5, lowercase!!, 1e-12)
        val padded = ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 1L), Rational(30L, 1L), Rational(0L, 1L), " N ",
        )
        assertEquals(51.5, padded!!, 1e-12)
    }

    @Test
    fun `gps rejects invalid reference characters`() {
        val value = listOf(Rational(51L, 1L), Rational(30L, 1L), Rational(0L, 1L))
        assertNull(ExifMath.gpsRationalToDecimalDegrees(value[0], value[1], value[2], "X"))
        assertNull(ExifMath.gpsRationalToDecimalDegrees(value[0], value[1], value[2], ""))
        assertNull(ExifMath.gpsRationalToDecimalDegrees(value[0], value[1], value[2], "north"))
        assertNull(ExifMath.gpsRationalToDecimalDegrees(value[0], value[1], value[2], "NS"))
    }

    @Test
    fun `gps latitude range is validated against 90 degrees`() {
        // Exactly at the pole is valid...
        assertEquals(
            90.0,
            ExifMath.gpsRationalToDecimalDegrees(
                Rational(90L, 1L), Rational(0L, 1L), Rational(0L, 1L), "N",
            )!!,
            0.0,
        )
        // ...one degree past it is not.
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(91L, 1L), Rational(0L, 1L), Rational(0L, 1L), "N",
        ))
        // DMS overflow counts too: 89 deg 61 min 0 sec = 90.0166...
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(89L, 1L), Rational(61L, 1L), Rational(0L, 1L), "N",
        ))
    }

    @Test
    fun `gps longitude range is validated against 180 degrees`() {
        assertEquals(
            180.0,
            ExifMath.gpsRationalToDecimalDegrees(
                Rational(180L, 1L), Rational(0L, 1L), Rational(0L, 1L), "E",
            )!!,
            0.0,
        )
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(181L, 1L), Rational(0L, 1L), Rational(0L, 1L), "E",
        ))
    }

    @Test
    fun `gps range follows the reference not the coordinate shape`() {
        // 100 degrees is a legal longitude but not a legal latitude ---
        // the same numbers are rejected under N and accepted under E.
        val degrees = Rational(100L, 1L)
        val zero = Rational(0L, 1L)
        assertNull(ExifMath.gpsRationalToDecimalDegrees(degrees, zero, zero, "N"))
        assertEquals(100.0, ExifMath.gpsRationalToDecimalDegrees(degrees, zero, zero, "E")!!, 0.0)
    }

    @Test
    fun `gps rejects zero denominators in any component`() {
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 0L), Rational(30L, 1L), Rational(0L, 1L), "N",
        ))
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 1L), Rational(30L, 0L), Rational(0L, 1L), "N",
        ))
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 1L), Rational(30L, 1L), Rational(0L, 0L), "N",
        ))
    }

    @Test
    fun `gps rejects negative components`() {
        // EXIF keeps the sign in the reference character; signed
        // components mean a corrupt block.
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(-51L, 1L), Rational(30L, 1L), Rational(0L, 1L), "S",
        ))
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, -1L), Rational(30L, 1L), Rational(0L, 1L), "N",
        ))
    }

    @Test
    fun `gps rejects minutes or seconds of sixty or more`() {
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 1L), Rational(60L, 1L), Rational(0L, 1L), "N",
        ))
        assertNull(ExifMath.gpsRationalToDecimalDegrees(
            Rational(51L, 1L), Rational(30L, 1L), Rational(60L, 1L), "N",
        ))
        // 59.99 seconds is still legal.
        assertEquals(
            51.5 + 59.99 / 3600.0,
            ExifMath.gpsRationalToDecimalDegrees(
                Rational(51L, 1L), Rational(30L, 1L), Rational(5999L, 100L), "N",
            )!!,
            1e-9,
        )
    }

    // ------------------------------------------------------------------
    // Exposure
    // ------------------------------------------------------------------

    @Test
    fun `exposure time divides numerator by denominator`() {
        assertEquals(0.004, ExifMath.exposureTimeToSeconds(1L, 250L)!!, 1e-12)
        assertEquals(5.0, ExifMath.exposureTimeToSeconds(10L, 2L)!!, 1e-12)
        assertEquals(0.0, ExifMath.exposureTimeToSeconds(0L, 250L)!!, 0.0)
    }

    @Test
    fun `exposure time rejects zero and negative denominators`() {
        assertNull(ExifMath.exposureTimeToSeconds(1L, 0L))
        assertNull(ExifMath.exposureTimeToSeconds(1L, -250L))
    }

    @Test
    fun `exposure time rejects negative numerator`() {
        assertNull(ExifMath.exposureTimeToSeconds(-1L, 250L))
    }

    @Test
    fun `short exposures render as camera nominal fractions`() {
        assertEquals("1/250 s", ExifMath.formatExposure(0.004))
        assertEquals("1/1000 s", ExifMath.formatExposure(0.001))
        assertEquals("1/8000 s", ExifMath.formatExposure(1.0 / 8000.0))
        // The cutoff itself (1/4 s) still renders as a fraction.
        assertEquals("1/4 s", ExifMath.formatExposure(0.25))
        // Rounding to the nearest nominal denominator: 1/7.8125 -> 1/8.
        assertEquals("1/8 s", ExifMath.formatExposure(0.128))
    }

    @Test
    fun `slow exposures render as decimals`() {
        // Just past the cutoff, the fraction form would be noise.
        assertEquals("0.3 s", ExifMath.formatExposure(0.26))
        assertEquals("0.5 s", ExifMath.formatExposure(0.5))
        assertEquals("1.0 s", ExifMath.formatExposure(1.0))
        assertEquals("1.6 s", ExifMath.formatExposure(1.6))
        assertEquals("2.0 s", ExifMath.formatExposure(2.0))
        assertEquals("30 s", ExifMath.formatExposure(30.0))
        assertEquals("12 s", ExifMath.formatExposure(12.34))
    }

    @Test
    fun `non-positive and non-finite exposures degrade to zero s`() {
        assertEquals("0 s", ExifMath.formatExposure(0.0))
        assertEquals("0 s", ExifMath.formatExposure(-0.004))
        assertEquals("0 s", ExifMath.formatExposure(Double.NaN))
    }

    @Test
    fun `absurdly fast values fall back to the decimal path`() {
        // A corrupt 1e-9 s must not render as "1/1000000000 s"; it
        // degrades through the decimal formatter instead.
        assertEquals("0.0 s", ExifMath.formatExposure(1e-9))
    }

    // ------------------------------------------------------------------
    // Orientation
    // ------------------------------------------------------------------

    @Test
    fun `orientation tag maps all eight values`() {
        // Unmirrored values, then their mirrored siblings which share the
        // same rotation (the flip bit is out of scope this round).
        assertEquals(0, ExifMath.orientationTagToDegrees(1))
        assertEquals(0, ExifMath.orientationTagToDegrees(2))
        assertEquals(180, ExifMath.orientationTagToDegrees(3))
        assertEquals(180, ExifMath.orientationTagToDegrees(4))
        assertEquals(90, ExifMath.orientationTagToDegrees(5))
        assertEquals(90, ExifMath.orientationTagToDegrees(6))
        assertEquals(270, ExifMath.orientationTagToDegrees(7))
        assertEquals(270, ExifMath.orientationTagToDegrees(8))
    }

    @Test
    fun `orientation tag rejects out-of-range values`() {
        assertNull(ExifMath.orientationTagToDegrees(0))
        assertNull(ExifMath.orientationTagToDegrees(9))
        assertNull(ExifMath.orientationTagToDegrees(-1))
        assertNull(ExifMath.orientationTagToDegrees(Int.MAX_VALUE))
    }

    // ------------------------------------------------------------------
    // DateTimeOriginal
    // ------------------------------------------------------------------

    @Test
    fun `exif datetime parses the strict format at utc anchor`() {
        val expected = LocalDateTime.of(2024, 5, 17, 9, 30, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(
            java.lang.Long.valueOf(expected),
            ExifMath.parseExifDateTime("2024:05:17 09:30:00"),
        )
        // Leading/trailing whitespace (some writers pad) is tolerated.
        assertEquals(
            java.lang.Long.valueOf(expected),
            ExifMath.parseExifDateTime(" 2024:05:17 09:30:00 "),
        )
    }

    @Test
    fun `exif datetime accepts leap-year february 29`() {
        val expected = LocalDateTime.of(2024, 2, 29, 23, 59, 59)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(
            java.lang.Long.valueOf(expected),
            ExifMath.parseExifDateTime("2024:02:29 23:59:59"),
        )
    }

    @Test
    fun `exif datetime rejects impossible dates`() {
        assertNull(ExifMath.parseExifDateTime("2024:13:01 00:00:00"))
        assertNull(ExifMath.parseExifDateTime("2024:00:10 00:00:00"))
        assertNull(ExifMath.parseExifDateTime("2024:05:17 24:00:00"))
        // Non-leap year: Feb 29 simply does not exist.
        assertNull(ExifMath.parseExifDateTime("2023:02:29 12:00:00"))
        assertNull(ExifMath.parseExifDateTime("2024:04:31 12:00:00"))
    }

    @Test
    fun `exif datetime rejects leap seconds`() {
        // EXIF seconds are 00..59; a 23:59:60 (leap-second-looking) value
        // is malformed EXIF, not a real time.
        assertNull(ExifMath.parseExifDateTime("2016:12:31 23:59:60"))
    }

    @Test
    fun `exif datetime rejects wrong shapes and separators`() {
        assertNull(ExifMath.parseExifDateTime("2024-05-17 09:30:00"))
        assertNull(ExifMath.parseExifDateTime("2024:05:17 9:30:00"))
        assertNull(ExifMath.parseExifDateTime("2024:05:17"))
        assertNull(ExifMath.parseExifDateTime("2024:05:17 09:30:00.123"))
        assertNull(ExifMath.parseExifDateTime("2024:05:17 09:30:00x"))
        assertNull(ExifMath.parseExifDateTime(""))
    }

    @Test
    fun `exif datetime rejects null and blank input`() {
        assertNull(ExifMath.parseExifDateTime(null))
        assertNull(ExifMath.parseExifDateTime("   "))
    }

    @Test
    fun `exif datetime rejects the exif placeholder`() {
        // "  :  :  " is the "no date" placeholder some writers put in
        // every datetime field; wrong length, so the strict parse drops it.
        assertNull(ExifMath.parseExifDateTime("  :  :  "))
        assertNull(ExifMath.parseExifDateTimeWithOffset("  :  :  ", 120))
    }

    // ------------------------------------------------------------------
    // OffsetTime parsing (audit finding I)
    // ------------------------------------------------------------------

    @Test
    fun `exif offset time parses plus and minus forms to minutes`() {
        assertEquals(120, ExifMath.parseExifOffsetTime("+02:00"))
        assertEquals(-450, ExifMath.parseExifOffsetTime("-07:30"))
        assertEquals(0, ExifMath.parseExifOffsetTime("+00:00"))
        assertEquals(-30, ExifMath.parseExifOffsetTime("-00:30"))
        assertEquals(825, ExifMath.parseExifOffsetTime("+13:45"))
        assertEquals(840, ExifMath.parseExifOffsetTime("+14:00"))
        // Whitespace padding, same tolerance as the datetime tags.
        assertEquals(120, ExifMath.parseExifOffsetTime(" +02:00 "))
    }

    @Test
    fun `exif offset time rejects malformed forms`() {
        assertNull(ExifMath.parseExifOffsetTime(null))
        assertNull(ExifMath.parseExifOffsetTime(""))
        assertNull(ExifMath.parseExifOffsetTime("   "))
        // Missing sign: the spec requires an explicit +/-.
        assertNull(ExifMath.parseExifOffsetTime("02:00"))
        assertNull(ExifMath.parseExifOffsetTime("+2:00"))
        assertNull(ExifMath.parseExifOffsetTime("+0200"))
        assertNull(ExifMath.parseExifOffsetTime("+02"))
        assertNull(ExifMath.parseExifOffsetTime("++02:00"))
        assertNull(ExifMath.parseExifOffsetTime("+02 00"))
        assertNull(ExifMath.parseExifOffsetTime("+02:60"))
        // 14 h is the real-world ISO 8601 maximum; beyond is corrupt.
        assertNull(ExifMath.parseExifOffsetTime("+15:00"))
        assertNull(ExifMath.parseExifOffsetTime("-23:00"))
        assertNull(ExifMath.parseExifOffsetTime("+ab:00"))
    }

    // ------------------------------------------------------------------
    // Datetime + offset combination (audit finding I)
    // ------------------------------------------------------------------

    @Test
    fun `exif datetime with offset interprets the naive local time as that zone`() {
        // 2024-05-17 09:30:00 at +02:00 is 07:30:00Z --- computed here via
        // OffsetDateTime (a different java.time path than the parser) as a
        // hand-checkable cross-check.
        val expected = java.time.OffsetDateTime
            .of(2024, 5, 17, 9, 30, 0, 0, java.time.ZoneOffset.ofHours(2))
            .toInstant().toEpochMilli()
        assertEquals(
            java.lang.Long.valueOf(expected),
            ExifMath.parseExifDateTimeWithOffset("2024:05:17 09:30:00", 120),
        )
    }

    @Test
    fun `exif datetime with a negative offset shifts the utc instant forward`() {
        // 2024-01-01 00:30:00 at -07:30 is 08:00:00Z on the same day
        // (west-of-UTC zones read a later UTC clock for the same instant).
        val expected = java.time.OffsetDateTime
            .of(2024, 1, 1, 0, 30, 0, 0, java.time.ZoneOffset.ofHoursMinutes(-7, -30))
            .toInstant().toEpochMilli()
        assertEquals(
            java.lang.Long.valueOf(expected),
            ExifMath.parseExifDateTimeWithOffset("2024:01:01 00:30:00", -450),
        )
    }

    @Test
    fun `exif datetime with a zero offset matches the utc anchor`() {
        val text = "2024:05:17 09:30:00"
        val baseline = ExifMath.parseExifDateTime(text)
        assertNotNull(baseline)
        assertEquals(
            java.lang.Long.valueOf(baseline!!),
            ExifMath.parseExifDateTimeWithOffset(text, 0),
        )
    }

    @Test
    fun `exif datetime without offset stays at the utc anchor`() {
        val expected = LocalDateTime.of(2024, 5, 17, 9, 30, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(
            java.lang.Long.valueOf(expected),
            ExifMath.parseExifDateTimeWithOffset("2024:05:17 09:30:00", null),
        )
    }

    @Test
    fun `exif datetime with offset still rejects malformed input and bad offsets`() {
        // The offset never rescues a malformed datetime...
        assertNull(ExifMath.parseExifDateTimeWithOffset("2024-05-17 09:30:00", 120))
        assertNull(ExifMath.parseExifDateTimeWithOffset("2024:02:30 09:30:00", 120))
        assertNull(ExifMath.parseExifDateTimeWithOffset(null, 120))
        assertNull(ExifMath.parseExifDateTimeWithOffset("   ", 120))
        // ...and an offset beyond java.time's own 18 h bound is refused
        // rather than thrown (the parser caps at 14 h; this guards direct
        // callers only).
        assertNull(ExifMath.parseExifDateTimeWithOffset("2024:05:17 09:30:00", 1081))
        assertNull(ExifMath.parseExifDateTimeWithOffset("2024:05:17 09:30:00", -1081))
    }

    // ------------------------------------------------------------------
    // SubSecTimeOriginal (audit finding I)
    // ------------------------------------------------------------------

    @Test
    fun `exif subsecond parses the decimal fraction convention`() {
        assertEquals(123, ExifMath.parseExifSubsecond("123"))
        // Fewer than three digits: zero-pad on the RIGHT ("12" = 0.12 s).
        assertEquals(120, ExifMath.parseExifSubsecond("12"))
        assertEquals(100, ExifMath.parseExifSubsecond("1"))
        assertEquals(790, ExifMath.parseExifSubsecond("79"))
        assertEquals(0, ExifMath.parseExifSubsecond("0"))
        assertEquals(0, ExifMath.parseExifSubsecond("000"))
        assertEquals(999, ExifMath.parseExifSubsecond("999"))
        // Whitespace padding is tolerated, as on every other tag.
        assertEquals(123, ExifMath.parseExifSubsecond(" 123 "))
    }

    @Test
    fun `exif subsecond truncates beyond three digits`() {
        // 0.9994 s is 999.4 ms; truncating keeps the result inside the
        // 0..999 domain where rounding to 1000 would escape it.
        assertEquals(123, ExifMath.parseExifSubsecond("1234"))
        assertEquals(999, ExifMath.parseExifSubsecond("9999"))
        assertEquals(999, ExifMath.parseExifSubsecond("99999999"))
    }

    @Test
    fun `exif subsecond rejects blank and non-digit input`() {
        assertNull(ExifMath.parseExifSubsecond(null))
        assertNull(ExifMath.parseExifSubsecond(""))
        assertNull(ExifMath.parseExifSubsecond("   "))
        assertNull(ExifMath.parseExifSubsecond("abc"))
        assertNull(ExifMath.parseExifSubsecond("1a3"))
        assertNull(ExifMath.parseExifSubsecond("12.5"))
        assertNull(ExifMath.parseExifSubsecond("-12"))
        assertNull(ExifMath.parseExifSubsecond("+12"))
    }

    // ------------------------------------------------------------------
    // Tag text parsing
    // ------------------------------------------------------------------

    @Test
    fun `rational text parses fraction and plain forms`() {
        assertEquals(Rational(28L, 10L), ExifMath.parseRationalText("28/10"))
        assertEquals(Rational(400L, 1L), ExifMath.parseRationalText("400"))
        assertEquals(Rational(1L, 250L), ExifMath.parseRationalText(" 1/250 "))
        assertEquals(Rational(-3L, 4L), ExifMath.parseRationalText("-3/4"))
    }

    @Test
    fun `rational text rejects garbage`() {
        assertNull(ExifMath.parseRationalText(null))
        assertNull(ExifMath.parseRationalText(""))
        assertNull(ExifMath.parseRationalText("   "))
        assertNull(ExifMath.parseRationalText("f/2.8"))
        assertNull(ExifMath.parseRationalText("1/2/3"))
        assertNull(ExifMath.parseRationalText("1/"))
        assertNull(ExifMath.parseRationalText("/2"))
        assertNull(ExifMath.parseRationalText("0.004"))
    }

    @Test
    fun `non-negative int text parses plain and rational forms`() {
        assertEquals(400, ExifMath.parseNonNegativeIntText("400"))
        assertEquals(4080, ExifMath.parseNonNegativeIntText("4080/1"))
        assertEquals(6, ExifMath.parseNonNegativeIntText("6"))
        assertNull(ExifMath.parseNonNegativeIntText(null))
        assertNull(ExifMath.parseNonNegativeIntText("abc"))
        assertNull(ExifMath.parseNonNegativeIntText("-5"))
        assertNull(ExifMath.parseNonNegativeIntText("0/0"))
        assertNull(ExifMath.parseNonNegativeIntText("9999999999"))
    }
}
