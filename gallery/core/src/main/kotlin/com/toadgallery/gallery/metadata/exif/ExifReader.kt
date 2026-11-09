package com.toadgallery.gallery.metadata.exif

import androidx.exifinterface.media.ExifInterface
import com.toadgallery.gallery.data.media.dates.CaptureDateSource
import com.toadgallery.gallery.data.media.dates.DateCandidate
import com.toadgallery.gallery.metadata.CameraInfo
import com.toadgallery.gallery.metadata.CaptureDateSources
import com.toadgallery.gallery.metadata.ExposureInfo
import com.toadgallery.gallery.metadata.GpsCoordinates
import com.toadgallery.gallery.metadata.MediaMetadataSnapshot
import com.toadgallery.gallery.metadata.MetadataAvailability
import com.toadgallery.gallery.metadata.MetadataValue
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Canonical tag keys for the EXIF block toadGALLERY reads this round.
 *
 * Named constants rather than string literals at use sites because
 * [ParsedExif]'s input map is keyed by them --- tests build maps against
 * these same constants, so a typo cannot silently make a test assert the
 * wrong thing. The values intentionally match the
 * `androidx.exifinterface.ExifInterface` TAG_* string values, so
 * [AndroidExifReader] can copy attributes straight through by name.
 */
object ExifTags {
    /** EXIF capture datetime, `yyyy:MM:dd HH:mm:ss` in naive local time. */
    const val DATE_TIME_ORIGINAL = "DateTimeOriginal"

    /**
     * EXIF digitization datetime, same naive `yyyy:MM:dd HH:mm:ss` form
     * as [DATE_TIME_ORIGINAL] (EXIF `CreateDate`; usually identical, and
     * the fallback candidate when the original tag is missing).
     */
    const val DATE_TIME_DIGITIZED = "DateTimeDigitized"

    /**
     * ISO 8601 zone offset (`±HH:MM`) that interprets [DATE_TIME_ORIGINAL],
     * added by EXIF 2.3.1. Absent on most legacy files; when missing the
     * naive datetime keeps the UTC-anchor convention and downstream passes
     * record the assumption.
     */
    const val OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal"

    /** The zone offset interpreting [DATE_TIME_DIGITIZED], same form as [OFFSET_TIME_ORIGINAL]. */
    const val OFFSET_TIME_DIGITIZED = "OffsetTimeDigitized"

    /**
     * Fractional seconds of [DATE_TIME_ORIGINAL] as a decimal-digit
     * string (`"123"` = 0.123 s); variable length, digits only.
     */
    const val SUBSEC_TIME_ORIGINAL = "SubSecTimeOriginal"

    /** Camera manufacturer string. */
    const val MAKE = "Make"

    /** Camera model string. */
    const val MODEL = "Model"

    /** Lens model string (missing on many phone cameras). */
    const val LENS_MODEL = "LensModel"

    /** Aperture as a rational whose value is the f-number (28/10 = 2.8). */
    const val F_NUMBER = "FNumber"

    /** Shutter speed as a rational in seconds (1/250). */
    const val EXPOSURE_TIME = "ExposureTime"

    /** ISO speed (legacy tag; plain or rational integer string). */
    const val ISO_SPEED_RATINGS = "ISOSpeedRatings"

    /** ISO speed (modern replacement tag; preferred when both exist). */
    const val PHOTOGRAPHIC_SENSITIVITY = "PhotographicSensitivity"

    /** Focal length in millimeters as a rational. */
    const val FOCAL_LENGTH = "FocalLength"

    /** Pixel width from the EXIF image structure. */
    const val IMAGE_WIDTH = "ImageWidth"

    /** Pixel height from the EXIF image structure. */
    const val IMAGE_LENGTH = "ImageLength"

    /** Orientation tag value 1..8. */
    const val ORIENTATION = "Orientation"

    /** GPS latitude as three comma-joined rationals (D, M, S). */
    const val GPS_LATITUDE = "GPSLatitude"

    /** GPS latitude reference character ("N"/"S"). */
    const val GPS_LATITUDE_REF = "GPSLatitudeRef"

    /** GPS longitude as three comma-joined rationals (D, M, S). */
    const val GPS_LONGITUDE = "GPSLongitude"

    /** GPS longitude reference character ("E"/"W"). */
    const val GPS_LONGITUDE_REF = "GPSLongitudeRef"

    /** Every tag [AndroidExifReader] extracts for [ParsedExif]. */
    val ALL: List<String> = listOf(
        DATE_TIME_ORIGINAL,
        DATE_TIME_DIGITIZED,
        OFFSET_TIME_ORIGINAL,
        OFFSET_TIME_DIGITIZED,
        SUBSEC_TIME_ORIGINAL,
        MAKE,
        MODEL,
        LENS_MODEL,
        F_NUMBER,
        EXPOSURE_TIME,
        ISO_SPEED_RATINGS,
        PHOTOGRAPHIC_SENSITIVITY,
        FOCAL_LENGTH,
        IMAGE_WIDTH,
        IMAGE_LENGTH,
        ORIENTATION,
        GPS_LATITUDE,
        GPS_LATITUDE_REF,
        GPS_LONGITUDE,
        GPS_LONGITUDE_REF,
    )
}

/**
 * The EXIF seam: turns one item's raw bytes (plus its MediaStore MIME
 * type, which the snapshot echoes and which the bytes themselves cannot
 * provide) into a [MediaMetadataSnapshot], or `null` when the item has no
 * EXIF block at all or the block cannot be opened (unsupported format,
 * corrupt stream).
 *
 * The repository layer pairs a `null` here with the
 * [format matrix][com.toadgallery.gallery.metadata.format.MetadataFormatMatrix]
 * to tell "genuinely absent" from "unsupported this round" --- that
 * distinction is the whole point of [MetadataAvailability].
 *
 * `bytes` in, snapshot out keeps the interface JVM-pure; the one Android
 * implementation ([AndroidExifReader]) is the thin shell the seam pattern
 * (ADR-0016) exempts from unit testing.
 */
interface ExifReader {
    /**
     * Reads EXIF metadata from [bytes] (the item's original bytes, not a
     * thumbnail) for an item of [mimeType]. Returns `null` when there is
     * no EXIF block or it cannot be read; individual blocks that exist
     * but fail to parse surface INSIDE the snapshot as UNREADABLE.
     */
    fun read(bytes: ByteArray, mimeType: String): MediaMetadataSnapshot?
}

/**
 * The pure assembly step: builds a [MediaMetadataSnapshot] from already-
 * extracted EXIF tag STRINGS (exactly the form
 * `androidx.exifinterface.ExifInterface.getAttribute` returns), doing all
 * conversion/validation through [ExifMath].
 *
 * This is the unit-tested middle of the EXIF pipeline: [AndroidExifReader]
 * only copies tag strings out of the framework, and every decision after
 * that --- DMS to decimal, availability mapping, graceful degradation ---
 * happens here on the JVM (ADR-0016 seam split, same shape as
 * `MediaFileOps` untested / `FileOpNaming` tested).
 *
 * Degradation rules, per block:
 *  - no tags from the block at all -> [MetadataValue.absent] (or the
 *    corresponding [CaptureDateSources] flag ABSENT);
 *  - tags present but none parse -> UNREADABLE (the block exists; it is
 *    the values that are corrupt);
 *  - some parse, some do not -> PRESENT with the unparseable fields
 *    `null` --- a missing f-number must not hide a good ISO.
 *
 * Capture dates additionally flow through [captureDateCandidates] as
 * resolver-ready [DateCandidate]s (one per datetime tag, EXIF_ORIGINAL
 * ranked above EXIF_DIGITIZED); the snapshot's flat
 * `exifCaptureDateMillis` field keeps its pre-existing raw-UTC-anchor
 * meaning and stays untouched.
 */
class ParsedExif(val tagValues: Map<String, String>) {

    /**
     * Assembles the snapshot. [fileSizeBytes] and [mimeType] come from the
     * caller (MediaStore row / byte count) because the EXIF block carries
     * neither.
     */
    fun toSnapshot(fileSizeBytes: Long, mimeType: String): MediaMetadataSnapshot {
        val width = ExifMath.parseNonNegativeIntText(tagValues[ExifTags.IMAGE_WIDTH]) ?: 0
        val height = ExifMath.parseNonNegativeIntText(tagValues[ExifTags.IMAGE_LENGTH]) ?: 0
        val orientationDegrees = ExifMath.parseNonNegativeIntText(tagValues[ExifTags.ORIENTATION])
            ?.let { ExifMath.orientationTagToDegrees(it) }
            ?: 0
        return MediaMetadataSnapshot(
            mimeType = mimeType,
            width = width,
            height = height,
            orientationDegrees = orientationDegrees,
            fileSizeBytes = fileSizeBytes,
            camera = buildCamera(),
            exposure = buildExposure(),
            gps = buildGps(),
            captureDates = CaptureDateSources(
                exif = exifDateAvailability(),
                // A still image has no container: genuinely ABSENT, and
                // video items get their container flag from the video
                // source instead.
                container = MetadataAvailability.ABSENT,
                // The bytes may well carry XMP we cannot read this round:
                // UNSUPPORTED, never a false ABSENT (see the format matrix).
                xmp = MetadataAvailability.UNSUPPORTED,
            ),
            exifCaptureDateMillis = ExifMath.parseExifDateTime(
                nonBlankTag(ExifTags.DATE_TIME_ORIGINAL),
            ),
        )
    }

    /**
     * Resolver-ready capture-date candidates (audit finding I): one
     * [DateCandidate] per datetime tag that parses, in the list order
     * EXIF_ORIGINAL then EXIF_DIGITIZED (the resolver ranks by source
     * ordinal, not list order --- the order here is just extraction
     * stability).
     *
     * Per-candidate conventions:
     *  - [DateCandidate.epochMillis] interprets the naive local datetime
     *    with the file's own OffsetTime tag when that tag parsed, and
     *    anchors at UTC otherwise --- [ExifMath.parseExifDateTimeWithOffset]
     *    documents exactly that split. The EXIF placeholder `"  :  :  "`
     *    and any other malformed datetime yield NO candidate (never a
     *    sentinel instant).
     *  - [DateCandidate.timezoneOffsetMinutes] is the parsed EXIF offset,
     *    or `null` when the OffsetTime* tag is absent OR unparseable ---
     *    i.e. `null` always means "the extractor made no zone decision",
     *    so the dates package applies its device-zone assumption there
     *    and records it.
     *  - [DateCandidate.subsecondMillis] comes from SubSecTimeOriginal
     *    (EXIF_ORIGINAL only; the Digitized subsecond tag is not read
     *    this round) and defaults to 0, per the MediaDates contract.
     *
     * The [CaptureDateSources.exif] availability flag stays keyed to
     * DateTimeOriginal alone; a file carrying only DateTimeDigitized
     * still yields an EXIF_DIGITIZED candidate here.
     */
    fun captureDateCandidates(): List<DateCandidate> {
        val candidates = mutableListOf<DateCandidate>()
        val originalText = nonBlankTag(ExifTags.DATE_TIME_ORIGINAL)
        if (originalText != null) {
            val offsetMinutes =
                ExifMath.parseExifOffsetTime(nonBlankTag(ExifTags.OFFSET_TIME_ORIGINAL))
            val subsecondMillis =
                ExifMath.parseExifSubsecond(nonBlankTag(ExifTags.SUBSEC_TIME_ORIGINAL))
            val epochMillis = ExifMath.parseExifDateTimeWithOffset(originalText, offsetMinutes)
            if (epochMillis != null) {
                candidates.add(
                    DateCandidate(
                        source = CaptureDateSource.EXIF_ORIGINAL,
                        epochMillis = epochMillis,
                        timezoneOffsetMinutes = offsetMinutes,
                        subsecondMillis = subsecondMillis ?: 0,
                    ),
                )
            }
        }
        val digitizedText = nonBlankTag(ExifTags.DATE_TIME_DIGITIZED)
        if (digitizedText != null) {
            val offsetMinutes =
                ExifMath.parseExifOffsetTime(nonBlankTag(ExifTags.OFFSET_TIME_DIGITIZED))
            val epochMillis = ExifMath.parseExifDateTimeWithOffset(digitizedText, offsetMinutes)
            if (epochMillis != null) {
                candidates.add(
                    DateCandidate(
                        source = CaptureDateSource.EXIF_DIGITIZED,
                        epochMillis = epochMillis,
                        timezoneOffsetMinutes = offsetMinutes,
                        subsecondMillis = 0,
                    ),
                )
            }
        }
        return candidates
    }

    /** Camera strings: PRESENT with per-field nulls, ABSENT when all missing. */
    private fun buildCamera(): MetadataValue<CameraInfo> {
        val make = nonBlankTag(ExifTags.MAKE)
        val model = nonBlankTag(ExifTags.MODEL)
        val lensModel = nonBlankTag(ExifTags.LENS_MODEL)
        if (make == null && model == null && lensModel == null) {
            return MetadataValue.absent()
        }
        return MetadataValue.present(CameraInfo(make, model, lensModel))
    }

    /**
     * Exposure: ABSENT with no tags, UNREADABLE when tags exist but none
     * parse, PRESENT with nulls for individual failures otherwise.
     */
    private fun buildExposure(): MetadataValue<ExposureInfo> {
        val fNumberText = nonBlankTag(ExifTags.F_NUMBER)
        val exposureText = nonBlankTag(ExifTags.EXPOSURE_TIME)
        // The modern tag wins when both exist; the legacy one covers
        // files from before the EXIF 2.3 rename.
        val isoText = nonBlankTag(ExifTags.PHOTOGRAPHIC_SENSITIVITY)
            ?: nonBlankTag(ExifTags.ISO_SPEED_RATINGS)
        val focalText = nonBlankTag(ExifTags.FOCAL_LENGTH)
        if (fNumberText == null && exposureText == null && isoText == null && focalText == null) {
            return MetadataValue.absent()
        }
        val fNumber = fNumberText?.let { ExifMath.parseRationalText(it)?.toDouble() }
        val exposureSeconds = exposureText?.let { text ->
            ExifMath.parseRationalText(text)?.let { rational ->
                ExifMath.exposureTimeToSeconds(rational.numerator, rational.denominator)
            }
        }
        val iso = isoText?.let { ExifMath.parseNonNegativeIntText(it) }
        val focalLengthMm = focalText?.let { ExifMath.parseRationalText(it)?.toDouble() }
        if (fNumber == null && exposureSeconds == null && iso == null && focalLengthMm == null) {
            return MetadataValue.unreadable()
        }
        return MetadataValue.present(
            ExposureInfo(
                fNumber = fNumber,
                exposureSeconds = exposureSeconds,
                iso = iso,
                focalLengthMm = focalLengthMm,
            ),
        )
    }

    /**
     * GPS: needs BOTH latitude and longitude (degrees/minutes/seconds plus
     * reference each) to be PRESENT --- a real GPS IFD always writes both,
     * so a half block is corrupt, not partial data. No GPS tags at all ->
     * ABSENT; anything else that fails to parse or validate -> UNREADABLE.
     */
    private fun buildGps(): MetadataValue<GpsCoordinates> {
        val latitudeText = nonBlankTag(ExifTags.GPS_LATITUDE)
        val latitudeRef = nonBlankTag(ExifTags.GPS_LATITUDE_REF)
        val longitudeText = nonBlankTag(ExifTags.GPS_LONGITUDE)
        val longitudeRef = nonBlankTag(ExifTags.GPS_LONGITUDE_REF)
        if (latitudeText == null && longitudeText == null) {
            return MetadataValue.absent()
        }
        val latitude = parseGpsCoordinate(latitudeText, latitudeRef)
        val longitude = parseGpsCoordinate(longitudeText, longitudeRef)
        if (latitude == null || longitude == null) {
            return MetadataValue.unreadable()
        }
        return MetadataValue.present(GpsCoordinates(latitude, longitude))
    }

    /**
     * One GPS coordinate from its `"d/d,m/m,s/s"` string plus reference
     * character, or `null` on any shape/validation failure --- all range
     * and sign rules live in [ExifMath.gpsRationalToDecimalDegrees].
     */
    private fun parseGpsCoordinate(text: String?, reference: String?): Double? {
        if (text == null || reference == null) return null
        val parts = text.split(",")
        if (parts.size != 3) return null
        val degrees = ExifMath.parseRationalText(parts[0]) ?: return null
        val minutes = ExifMath.parseRationalText(parts[1]) ?: return null
        val seconds = ExifMath.parseRationalText(parts[2]) ?: return null
        return ExifMath.gpsRationalToDecimalDegrees(degrees, minutes, seconds, reference)
    }

    /** EXIF capture-date availability: ABSENT / PRESENT / UNREADABLE. */
    private fun exifDateAvailability(): MetadataAvailability {
        val text = nonBlankTag(ExifTags.DATE_TIME_ORIGINAL) ?: return MetadataAvailability.ABSENT
        return if (ExifMath.parseExifDateTime(text) != null) {
            MetadataAvailability.PRESENT
        } else {
            MetadataAvailability.UNREADABLE
        }
    }

    /** The tag value trimmed, or `null` when absent or whitespace-only. */
    private fun nonBlankTag(tag: String): String? =
        tagValues[tag]?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The one Android [ExifReader]: opens the bytes with
 * `androidx.exifinterface` (ADR-0008: the androidx artifact, NOT the Aves
 * fork) and hands the extracted tag strings to the pure, tested
 * [ParsedExif] assembly.
 *
 * NOT JVM-unit-tested, by design (ADR-0016, same exemption as
 * `MediaFileOps`): this class is straight extraction with no branching
 * logic beyond null handling --- every computation and degradation rule
 * lives in [ExifMath]/[ParsedExif], which carry the JVM suites. The
 * framework's `ExifInterface` needs the Android runtime (it touches
 * android.util logging), so it cannot even load under the plain JUnit
 * runner this repo uses.
 *
 * Two honest framework notes:
 *  - GPS DMS conversion deliberately does NOT use
 *    `ExifInterface.getLatLong()` --- the validation rules (range,
 *    negative components, minutes/seconds < 60) are ours and pinned in
 *    `ExifMathTest` instead of trusting each device's framework build.
 *  - On Android 10+ the platform redacts EXIF location unless
 *    `ACCESS_MEDIA_LOCATION` is granted; the reader does not work around
 *    that --- the tags simply come back absent (audit decision #1 tracks
 *    the permission flow separately).
 */
class AndroidExifReader : ExifReader {

    override fun read(bytes: ByteArray, mimeType: String): MediaMetadataSnapshot? {
        val exif: ExifInterface = try {
            ExifInterface(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            // Unsupported format or corrupt stream: no EXIF to read.
            return null
        }
        val tags = HashMap<String, String>()
        for (tag in ExifTags.ALL) {
            exif.getAttribute(tag)?.let { tags[tag] = it }
        }
        if (tags.isEmpty()) {
            // Parsed but empty: the file carries no EXIF block at all.
            return null
        }
        return ParsedExif(tags).toSnapshot(bytes.size.toLong(), mimeType)
    }
}
