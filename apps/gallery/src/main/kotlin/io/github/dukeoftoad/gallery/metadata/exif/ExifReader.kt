package io.github.dukeoftoad.gallery.metadata.exif

import androidx.exifinterface.media.ExifInterface
import io.github.dukeoftoad.gallery.metadata.CameraInfo
import io.github.dukeoftoad.gallery.metadata.CaptureDateSources
import io.github.dukeoftoad.gallery.metadata.ExposureInfo
import io.github.dukeoftoad.gallery.metadata.GpsCoordinates
import io.github.dukeoftoad.gallery.metadata.MediaMetadataSnapshot
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import io.github.dukeoftoad.gallery.metadata.MetadataValue
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
 * [format matrix][io.github.dukeoftoad.gallery.metadata.format.MetadataFormatMatrix]
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
