package io.github.dukeoftoad.gallery.metadata.exif

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.roundToLong

/**
 * One EXIF rational (a numerator/denominator pair of 32-bit values as
 * stored in TIFF-style IFDs). Kept as exact integers rather than a Double
 * so the conversion can FAIL on a zero denominator --- a corrupt IFD
 * produces `0/0`, and silently turning that into a coordinate or a
 * shutter speed would be worse than refusing.
 */
data class Rational(
    val numerator: Long,
    val denominator: Long,
) {
    /**
     * The rational as a Double, or `null` when [denominator] is zero
     * (unparseable/corrupt storage). A negative denominator is preserved
     * as-is; semantic sign validation is the caller's job (EXIF keeps the
     * GPS sign in the reference character, so GPS parsing rejects negative
     * components outright).
     */
    fun toDouble(): Double? =
        if (denominator == 0L) null else numerator.toDouble() / denominator.toDouble()
}

/**
 * Pure EXIF arithmetic and string parsing --- the heart of the metadata
 * layer (Phase 4, Aves-informed assessment: value conversions must be
 * correct and honestly total, never silently lossy).
 *
 * Every function here is plain Kotlin over plain values, with no Android
 * dependency, so `ExifMathTest` pins GPS conversion, exposure formatting,
 * orientation mapping and date parsing byte-for-byte on the JVM (ADR-0016
 * seam pattern: `AndroidExifReader` stays a thin untested shell and
 * delegates every computation here). Aves deliberately reimplements this
 * math rather than trusting per-device framework paths; we centralize it
 * for the same reason --- plus it will serve the future IPTC/XMP wrappers
 * unchanged, and the GPS conversion intentionally does NOT call
 * `ExifInterface.getLatLong()` so the validation rules below are ours and
 * testable.
 *
 * `java.time` is used freely: minSdk 26 has it (API 26+), so no desugaring
 * is needed and the same code runs on the JVM unit tests.
 */
object ExifMath {

    /** GPS latitudes are bounded to +/-90 decimal degrees. */
    private const val LATITUDE_LIMIT = 90.0

    /** GPS longitudes are bounded to +/-180 decimal degrees. */
    private const val LONGITUDE_LIMIT = 180.0

    /**
     * Exposures at or faster than this cutoff (1/4 s) render as fractions
     * --- the camera convention ("1/250 s"); anything slower renders as
     * decimal seconds ("0.5 s", "1.6 s", "30 s") because "1/2 s" reads
     * worse than "0.5 s" once the denominator is small. Aves uses a
     * similar nominal-value table; a single cutoff keeps it testable.
     */
    private const val FRACTION_CUTOFF_SECONDS = 0.25

    /**
     * Beyond this denominator the fraction stops being a camera nominal
     * value and the decimal path takes over (defensive: real EXIF stops
     * around 1/32000, but corrupt values must not produce
     * "1/1800000000 s").
     */
    private const val MAX_FRACTION_DENOMINATOR = 1_000_000L

    /**
     * EXIF DateTimeOriginal format: `yyyy:MM:dd HH:mm:ss`, colon-separated
     * date, exactly 19 characters. Strict resolver so impossible dates
     * (Feb 30, month 13, a non-leap Feb 29) are rejected instead of
     * rolled over. `uuuu` (not `yyyy`) because STRICT resolving of a
     * year-of-era would demand an era field.
     */
    private val EXIF_DATE_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT)

    /**
     * Converts an EXIF GPS degrees/minutes/seconds triple plus its
     * reference character into signed decimal degrees.
     *
     * The reference carries the sign ("S"/"W" negate); N/E stay positive.
     * EXIF stores the components unsigned, so any negative component means
     * a corrupt block and is rejected. Validation, all returning `null`:
     *  - [reference] must be a single "N"/"S"/"E"/"W" (case-insensitive,
     *    surrounding whitespace tolerated --- some writers pad);
     *  - every rational must have a non-zero denominator;
     *  - minutes and seconds must be below 60 (a DMS value of 61' is
     *    malformed, not 1 degree 1 minute);
     *  - the signed result must land in the reference's range --- latitude
     *    -90..90, longitude -180..180 --- which also catches a latitude
     *    value paired with an E/W reference only when it exceeds 180.
     *
     * Returns `null` on any violation: a wrong coordinate is worse than no
     * coordinate, because Places and the map view would show the photo
     * somewhere it was never taken.
     */
    fun gpsRationalToDecimalDegrees(
        degrees: Rational,
        minutes: Rational,
        seconds: Rational,
        reference: String,
    ): Double? {
        val isLatitude: Boolean
        val negate: Boolean
        when (reference.trim().uppercase(Locale.ROOT)) {
            "N" -> {
                isLatitude = true
                negate = false
            }
            "S" -> {
                isLatitude = true
                negate = true
            }
            "E" -> {
                isLatitude = false
                negate = false
            }
            "W" -> {
                isLatitude = false
                negate = true
            }
            else -> return null
        }
        // EXIF keeps the sign in the reference character; signed
        // components mean a corrupt block. Denominators are unsigned too
        // --- a negative one flips the component's sign behind the range
        // check's back (51/-1 masquerades as -51 degrees), so it is
        // rejected up front rather than normalized.
        if (degrees.numerator < 0L || minutes.numerator < 0L || seconds.numerator < 0L) {
            return null
        }
        if (
            degrees.denominator <= 0L ||
            minutes.denominator <= 0L ||
            seconds.denominator <= 0L
        ) {
            return null
        }
        val deg = degrees.toDouble() ?: return null
        val min = minutes.toDouble() ?: return null
        val sec = seconds.toDouble() ?: return null
        if (min >= 60.0 || sec >= 60.0) return null
        var decimal = deg + min / 60.0 + sec / 3600.0
        if (negate) decimal = -decimal
        val limit = if (isLatitude) LATITUDE_LIMIT else LONGITUDE_LIMIT
        if (decimal < -limit || decimal > limit) return null
        // Normalize a West-of-Greenwich zero to +0.0 so cache keys and
        // string rendering stay stable ("W 0" is 0.0, not -0.0).
        if (decimal == 0.0) decimal = 0.0
        return decimal
    }

    /**
     * An EXIF ExposureTime rational as seconds. `null` when the
     * denominator is zero or negative (the EXIF spec defines exposure time
     * as a positive rational; anything else is corrupt) or when the
     * numerator is negative. A zero numerator yields `0.0` --- it is
     * representable, and callers format it as "0 s" rather than crash.
     */
    fun exposureTimeToSeconds(numerator: Long, denominator: Long): Double? {
        if (denominator <= 0L || numerator < 0L) return null
        return numerator.toDouble() / denominator.toDouble()
    }

    /**
     * A shutter speed for display: "1/250 s" for exposures at or faster
     * than 1/4 s (camera nominal values --- the denominator is the RECIPROCAL
     * rounded to the nearest integer, so 0.004 renders as "1/250 s"), and
     * decimal seconds otherwise ("0.5 s" below 10 s, plain "30 s" above).
     *
     * Documented choice: a single 1/4 s cutoff instead of a table of
     * nominal values, because every camera value at or faster than 1/4 s
     * has a small integer denominator (4, 5, 6, 8, 10, 15, ...) while
     * slower speeds are usually stored as exact decimals the fraction form
     * would mangle ("1/2 s" vs "0.5 s" is a coin flip; "1/1.3 s" is
     * noise). Non-positive or non-finite input degrades to "0 s" rather
     * than throwing --- the Details view must never crash on bad metadata.
     */
    fun formatExposure(seconds: Double): String {
        if (!seconds.isFinite() || seconds <= 0.0) return "0 s"
        if (seconds <= FRACTION_CUTOFF_SECONDS) {
            val denominator = (1.0 / seconds).roundToLong()
            if (denominator in 2..MAX_FRACTION_DENOMINATOR) {
                return "1/$denominator s"
            }
            // Absurdly fast (corrupt) values fall through to the decimal
            // path rather than printing "1/1800000000 s".
        }
        return if (seconds < 10.0) {
            String.format(Locale.ROOT, "%.1f s", seconds)
        } else {
            String.format(Locale.ROOT, "%.0f s", seconds)
        }
    }

    /**
     * An EXIF Orientation tag value (1..8) to the whole-number rotation
     * that displays the image upright: 1/2 -> 0, 3/4 -> 180, 5/6 -> 90,
     * 7/8 -> 270. Mirrored values (2/4/5/7) map to the rotation of their
     * unmirrored sibling --- the flip bit matters for the future canonical
     * decoder contract (audit item 6) but not for a rotation-only summary.
     * `null` for anything else (0, 9, negative --- corrupt IFDs exist).
     */
    fun orientationTagToDegrees(orientationValue: Int): Int? =
        when (orientationValue) {
            1, 2 -> 0
            3, 4 -> 180
            5, 6 -> 90
            7, 8 -> 270
            else -> null
        }

    /**
     * An EXIF datetime string (`yyyy:MM:dd HH:mm:ss` --- the spec's
     * colon-separated form, strictly 19 characters) to epoch millis, or
     * `null` on anything malformed: wrong separators, single-digit
     * fields, impossible dates (Feb 30, month 13, a non-leap Feb 29),
     * hour 24, or a leap second (`23:59:60` --- EXIF has no sub-second or
     * leap representation; the separate SubSecTimeOriginal tag is out of
     * scope this round). Null/blank input is `null`, never an exception.
     *
     * EXIF datetimes are naive LOCAL times with no timezone. This returns
     * them anchored at UTC purely so callers get a deterministic,
     * cacheable/comparable Long --- the value is deliberately labeled
     * "raw EXIF" in [MediaMetadataSnapshot][io.github.dukeoftoad.gallery.metadata.MediaMetadataSnapshot.exifCaptureDateMillis]
     * and the timezone-aware MediaDates resolver (audit item 3) will
     * supersede it.
     */
    fun parseExifDateTime(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        if (trimmed.length != 19) return null
        val parsed: LocalDateTime = try {
            LocalDateTime.parse(trimmed, EXIF_DATE_TIME_FORMAT)
        } catch (e: DateTimeParseException) {
            return null
        }
        return parsed.toInstant(ZoneOffset.UTC).toEpochMilli()
    }

    /**
     * Parses the string form `androidx.exifinterface` hands back for
     * rational-valued tags: `"numerator/denominator"` or a plain
     * `"numerator"` (denominator 1). GPS values arrive as three of these
     * joined by commas --- the caller splits. `null` on blank input,
     * non-numeric parts, or extra slashes; refusing beats guessing.
     */
    fun parseRationalText(text: String?): Rational? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val slash = trimmed.indexOf('/')
        return if (slash < 0) {
            val numerator = trimmed.toLongOrNull() ?: return null
            Rational(numerator, 1L)
        } else {
            val numerator = trimmed.substring(0, slash).toLongOrNull() ?: return null
            val denominator = trimmed.substring(slash + 1).toLongOrNull() ?: return null
            Rational(numerator, denominator)
        }
    }

    /**
     * Parses an integer-valued tag from its `androidx.exifinterface`
     * string form (plain `"400"` or rational `"400/1"`) to a non-negative
     * Int. `null` on blank input, garbage, negative values (ISO and pixel
     * dimensions are unsigned by spec) or anything that overflows Int.
     */
    fun parseNonNegativeIntText(text: String?): Int? {
        val rational = parseRationalText(text) ?: return null
        val value = rational.toDouble() ?: return null
        if (value < 0.0) return null
        val rounded = value.roundToLong()
        if (rounded > Int.MAX_VALUE.toLong()) return null
        return rounded.toInt()
    }
}
