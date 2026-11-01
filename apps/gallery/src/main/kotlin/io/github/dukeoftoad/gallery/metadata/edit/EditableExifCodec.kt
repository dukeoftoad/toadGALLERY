package io.github.dukeoftoad.gallery.metadata.edit

import io.github.dukeoftoad.gallery.metadata.exif.ExifMath
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Canonical tag keys for the EXIF block toadGALLERY WRITES this round
 * (plus the two Windows tag pairs that are modelled but not writable ---
 * see [MediaEditCapability.UNSUPPORTED_FIELD]). Named constants rather
 * than string literals at use sites for the same reason
 * `ExifTags` (the read twin) gives: the values intentionally match the
 * `androidx.exifinterface.ExifInterface` TAG_* string values, so the
 * writer shell copies attributes straight through by name, and tests
 * build expectations against the same constants.
 *
 * VERIFIED against the pinned androidx.exifinterface 1.4.1 sources
 * (https://maven.google.com/androidx/exifinterface/exifinterface/1.4.1/exifinterface-1.4.1-sources.jar):
 * `setAttribute` only honors tag names present in the library's writable
 * tag map (`sExifTagMapsForWriting`). Every tag in [READ_TAGS] is in
 * that map: DateTimeOriginal, OffsetTimeOriginal and UserComment in the
 * EXIF-private IFD list, Copyright in the primary TIFF IFD list, and the
 * four GPS tags in the GPS IFD list. "Rating"/"RatingPercent"/
 * "XPKeywords" are NOT in it --- `setAttribute` SILENTLY IGNORES unknown
 * names (no throw, no write), so this round's writer refuses those fields
 * up front instead of faking a write the library would drop.
 */
object EditableExifTags {
    /** EXIF capture datetime, `yyyy:MM:dd HH:mm:ss` in naive local time. */
    const val DATE_TIME_ORIGINAL = "DateTimeOriginal"

    /** ISO 8601 zone offset (`±HH:MM`) interpreting [DATE_TIME_ORIGINAL]. */
    const val OFFSET_TIME_ORIGINAL = "OffsetTimeOriginal"

    /** GPS latitude as three comma-joined rationals (D, M, S), unsigned. */
    const val GPS_LATITUDE = "GPSLatitude"

    /** GPS latitude reference character ("N"/"S") --- carries the sign. */
    const val GPS_LATITUDE_REF = "GPSLatitudeRef"

    /** GPS longitude as three comma-joined rationals (D, M, S), unsigned. */
    const val GPS_LONGITUDE = "GPSLongitude"

    /** GPS longitude reference character ("E"/"W"). */
    const val GPS_LONGITUDE_REF = "GPSLongitudeRef"

    /** Copyright string (EXIF 0x8298). */
    const val COPYRIGHT = "Copyright"

    /** User comment (EXIF 0x9286). */
    const val USER_COMMENT = "UserComment"

    /** Windows keywords tag (0x9c9e) --- NOT in the pinned writer's tag map. */
    const val XP_KEYWORDS = "XPKeywords"

    /** Windows rating tag (0x4746) --- NOT in the pinned writer's tag map. */
    const val RATING = "Rating"

    /** Windows rating-percent tag (0x4749) --- NOT in the pinned writer's tag map. */
    const val RATING_PERCENT = "RatingPercent"

    /** Every tag [MediaMetadataWriter.readCurrentValues] extracts for the editor. */
    val READ_TAGS: List<String> = listOf(
        DATE_TIME_ORIGINAL,
        OFFSET_TIME_ORIGINAL,
        GPS_LATITUDE,
        GPS_LATITUDE_REF,
        GPS_LONGITUDE,
        GPS_LONGITUDE_REF,
        COPYRIGHT,
        USER_COMMENT,
    )
}

/**
 * One tag write: [value] is the attribute string to set, or `null` to
 * REMOVE the tag (verified androidx 1.4.1 `setAttribute` semantics: a
 * null value removes the attribute from the IFD, so "clear this field"
 * is expressible without inventing sentinel strings).
 */
data class ExifTagWrite(val tag: String, val value: String?)

/**
 * The editor's raw input for one field. [primary]/[secondary] map per
 * field (DATE_TIME: datetime text + offset text; GPS: latitude +
 * longitude; COPYRIGHT/COMMENT: the single text; the rest take none).
 * Blank strings mean "empty" at this layer; the codec decides whether
 * empty means "clear the tag" (offset, GPS, copyright, comment) or is
 * invalid (the datetime itself must always be a real instant).
 */
data class MetadataEditInput(val primary: String = "", val secondary: String = "")

/** Why the codec refused an input. */
enum class FieldInvalidReason {
    /** The primary datetime is malformed / an impossible date. */
    DATETIME,

    /** The secondary offset is malformed (blank is fine --- it clears). */
    OFFSET,

    /** The latitude is not a decimal number in -90..90. */
    LATITUDE,

    /** The longitude is not a decimal number in -180..180. */
    LONGITUDE,

    /** The field is outside the pinned writer's field set this round. */
    UNSUPPORTED_FIELD,
}

/** The codec's verdict on one editor input. */
sealed interface FieldEditResult {
    /** The exact tag writes to apply (possibly all-clears). */
    data class Valid(val writes: List<ExifTagWrite>) : FieldEditResult

    /** The input was refused; the editor renders the matching hint. */
    data class Invalid(val reason: FieldInvalidReason) : FieldEditResult
}

/**
 * The current values of the editable fields, as read back through the
 * same androidx reader the writer uses (`getAttribute` on the writable
 * tags --- every one of them IS in the library's reading map, verified).
 * `null` per field when the tag is absent (or, for GPS, when the block is
 * incomplete/unparseable --- the same "a half GPS block is corrupt, not
 * partial data" rule [io.github.dukeoftoad.gallery.metadata.exif.ParsedExif]
 * applies on the read path).
 */
data class EditableExifCurrentValues(
    val dateTimeOriginal: String?,
    val offsetTimeOriginal: String?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
    val copyright: String?,
    val userComment: String?,
)

/**
 * Pure field-to-EXIF-tag mapping and validation for the write round: the
 * editor's input text becomes a list of [ExifTagWrite]s (or an honest
 * [FieldEditResult.Invalid]), and the writer's read-back tag strings
 * become [EditableExifCurrentValues]. Everything here is plain Kotlin
 * over plain values (ADR-0016) and reuses the JVM-tested [ExifMath] for
 * every datetime/offset/rational decision --- the writer never validates
 * by itself, it only executes.
 */
object EditableExifCodec {

    /**
     * The editor PREFILL for [field] from the values read back for the
     * item (`null` values -> empty strings, i.e. "not set"); the pure
     * twin of the write direction, so the dialog seeds exactly what a
     * subsequent [writesFor] would re-parse. Outside the writer's pinned
     * field set the prefill is empty (no editor opens there).
     */
    fun prefill(field: MediaEditField, values: EditableExifCurrentValues?): MetadataEditInput =
        when (field) {
            MediaEditField.DATE_TIME -> MetadataEditInput(
                primary = values?.dateTimeOriginal ?: "",
                secondary = values?.offsetTimeOriginal ?: "",
            )
            MediaEditField.GPS -> MetadataEditInput(
                primary = values?.gpsLatitude?.toString() ?: "",
                secondary = values?.gpsLongitude?.toString() ?: "",
            )
            MediaEditField.COPYRIGHT -> MetadataEditInput(primary = values?.copyright ?: "")
            MediaEditField.COMMENT -> MetadataEditInput(primary = values?.userComment ?: "")
            MediaEditField.KEYWORDS,
            MediaEditField.RATING,
            MediaEditField.COLOUR_LABEL,
            -> MetadataEditInput()
        }

    /**
     * Validates [input] for [field] and builds the tag writes.
     *
     *  - DATE_TIME: primary must parse as a strict EXIF datetime
     *    ([ExifMath.parseExifDateTime]); the secondary offset, when
     *    non-blank, must parse as `±HH:MM`
     *    ([ExifMath.parseExifOffsetTime]). Writes the normalized
     *    datetime + the offset (blank offset CLEARS the offset tag ---
     *    the camera convention of "no zone evidence" is a legitimate
     *    state, and the dates package records the assumption).
     *  - GPS: both coordinates must be decimal degrees in range; BOTH
     *    blank clears all four GPS tags (an honest "remove location");
     *    exactly one blank is invalid (EXIF GPS is a lat+lng pair, and a
     *    half block is corrupt, not partial data). Writes the DMS
     *    rationals + reference characters ([decimalToDmsText]).
     *  - COPYRIGHT / COMMENT: the trimmed text is written; blank CLEARS
     *    the tag.
     *  - KEYWORDS / RATING / COLOUR_LABEL: [FieldInvalidReason.UNSUPPORTED_FIELD]
     *    --- the pinned androidx writer cannot persist these tags (see
     *    [EditableExifTags]); the capability gate refuses them before the
     *    editor opens, and this is the codec's defensive second line.
     */
    fun writesFor(field: MediaEditField, input: MetadataEditInput): FieldEditResult {
        return when (field) {
            MediaEditField.DATE_TIME -> {
                val dateTime = input.primary.trim()
                if (ExifMath.parseExifDateTime(dateTime) == null) {
                    return FieldEditResult.Invalid(FieldInvalidReason.DATETIME)
                }
                val offsetText = input.secondary.trim()
                val writes = mutableListOf(ExifTagWrite(EditableExifTags.DATE_TIME_ORIGINAL, dateTime))
                if (offsetText.isEmpty()) {
                    writes.add(ExifTagWrite(EditableExifTags.OFFSET_TIME_ORIGINAL, null))
                } else {
                    val offsetMinutes = ExifMath.parseExifOffsetTime(offsetText)
                        ?: return FieldEditResult.Invalid(FieldInvalidReason.OFFSET)
                    writes.add(
                        ExifTagWrite(
                            EditableExifTags.OFFSET_TIME_ORIGINAL,
                            offsetTimeText(offsetMinutes),
                        ),
                    )
                }
                FieldEditResult.Valid(writes)
            }
            MediaEditField.GPS -> {
                val latitudeText = input.primary.trim()
                val longitudeText = input.secondary.trim()
                if (latitudeText.isEmpty() && longitudeText.isEmpty()) {
                    // An honest "remove location": the whole GPS block goes.
                    return FieldEditResult.Valid(
                        listOf(
                            ExifTagWrite(EditableExifTags.GPS_LATITUDE, null),
                            ExifTagWrite(EditableExifTags.GPS_LATITUDE_REF, null),
                            ExifTagWrite(EditableExifTags.GPS_LONGITUDE, null),
                            ExifTagWrite(EditableExifTags.GPS_LONGITUDE_REF, null),
                        ),
                    )
                }
                val latitude = latitudeText.toDoubleOrNull()
                if (latitude == null || latitude < -90.0 || latitude > 90.0) {
                    return FieldEditResult.Invalid(FieldInvalidReason.LATITUDE)
                }
                val longitude = longitudeText.toDoubleOrNull()
                if (longitude == null || longitude < -180.0 || longitude > 180.0) {
                    return FieldEditResult.Invalid(FieldInvalidReason.LONGITUDE)
                }
                FieldEditResult.Valid(gpsWrites(latitude, longitude))
            }
            MediaEditField.COPYRIGHT -> FieldEditResult.Valid(
                listOf(textOrClear(EditableExifTags.COPYRIGHT, input.primary)),
            )
            MediaEditField.COMMENT -> FieldEditResult.Valid(
                listOf(textOrClear(EditableExifTags.USER_COMMENT, input.primary)),
            )
            MediaEditField.KEYWORDS,
            MediaEditField.RATING,
            MediaEditField.COLOUR_LABEL,
            -> FieldEditResult.Invalid(FieldInvalidReason.UNSUPPORTED_FIELD)
        }
    }

    /**
     * The four GPS tag writes for one coordinate pair: reference
     * characters carry the signs (N/S, E/W --- mirroring androidx's own
     * `setLatLong`, which writes unsigned components plus a reference),
     * components as `"d/1,m/1,s/10000000"` rationals.
     */
    fun gpsWrites(latitude: Double, longitude: Double): List<ExifTagWrite> = listOf(
        ExifTagWrite(EditableExifTags.GPS_LATITUDE_REF, if (latitude >= 0.0) "N" else "S"),
        ExifTagWrite(EditableExifTags.GPS_LATITUDE, decimalToDmsText(latitude)),
        ExifTagWrite(EditableExifTags.GPS_LONGITUDE_REF, if (longitude >= 0.0) "E" else "W"),
        ExifTagWrite(EditableExifTags.GPS_LONGITUDE, decimalToDmsText(longitude)),
    )

    /**
     * Unsigned decimal degrees to the EXIF `"d/1,m/1,s/10000000"` form.
     *
     * The component math mirrors androidx's private `convertDecimalDegree`
     * (verified in the 1.4.1 sources): degrees and minutes truncated,
     * seconds rounded to 1e-7 s. ONE deliberate fix on top: androidx can
     * emit a seconds value that rounds to `10000000/10000000` (60.0 s) or
     * a minute that reaches 60 --- a malformed DMS our own strict reader
     * ([ExifMath.gpsRationalToDecimalDegrees]) rejects (minutes and
     * seconds must be < 60). This writer normalizes the carry instead, so
     * every value it produces re-parses under the reader it will be shown
     * through: 60.0 s rolls into the minute, 60' rolls into the degree.
     */
    fun decimalToDmsText(decimal: Double): String {
        val magnitude = abs(decimal)
        var degrees = floor(magnitude).toLong()
        var minutes = floor((magnitude - degrees) * 60.0).toLong()
        var seconds = ((magnitude - degrees - minutes / 60.0) * 3600.0 * 1e7).roundToLong()
        if (seconds >= SECONDS_PER_MINUTE_X_1E7) {
            seconds -= SECONDS_PER_MINUTE_X_1E7
            minutes += 1
        }
        if (minutes >= 60L) {
            minutes -= 60L
            degrees += 1L
        }
        return "$degrees/1,$minutes/1,$seconds/$SECONDS_DENOMINATOR"
    }

    /**
     * Signed minutes east of UTC as the EXIF `±HH:MM` offset text
     * (`+02:00`, `-07:30`, `+00:00`) --- the exact form
     * [ExifMath.parseExifOffsetTime] accepts back, and the same output
     * shape as the read-path display formatter (the two are twins on
     * purpose; this one lives here so the write codec never reaches into
     * the UI package).
     */
    fun offsetTimeText(offsetMinutes: Int): String {
        val sign = if (offsetMinutes < 0) "-" else "+"
        val magnitude = abs(offsetMinutes)
        return String.format(
            java.util.Locale.ROOT,
            "%s%02d:%02d",
            sign,
            magnitude / 60,
            magnitude % 60,
        )
    }

    /**
     * Assembles [EditableExifCurrentValues] from the raw `getAttribute`
     * strings the writer read back (exactly the form androidx hands
     * over). GPS goes through the same [ExifMath] DMS-to-decimal path as
     * the read layer, so an edit round-trip parses identically.
     */
    fun assembleCurrentValues(tagValues: Map<String, String>): EditableExifCurrentValues =
        EditableExifCurrentValues(
            dateTimeOriginal = nonBlank(tagValues, EditableExifTags.DATE_TIME_ORIGINAL),
            offsetTimeOriginal = nonBlank(tagValues, EditableExifTags.OFFSET_TIME_ORIGINAL),
            gpsLatitude = gpsDecimal(
                tagValues,
                EditableExifTags.GPS_LATITUDE,
                EditableExifTags.GPS_LATITUDE_REF,
            ),
            gpsLongitude = gpsDecimal(
                tagValues,
                EditableExifTags.GPS_LONGITUDE,
                EditableExifTags.GPS_LONGITUDE_REF,
            ),
            copyright = nonBlank(tagValues, EditableExifTags.COPYRIGHT),
            userComment = nonBlank(tagValues, EditableExifTags.USER_COMMENT),
        )

    /** Trimmed text, or `null` when absent/blank; blank clears the tag. */
    private fun textOrClear(tag: String, text: String): ExifTagWrite =
        ExifTagWrite(tag, text.trim().takeIf { it.isNotEmpty() })

    private fun nonBlank(tagValues: Map<String, String>, tag: String): String? =
        tagValues[tag]?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * One GPS coordinate from its `"d/d,m/m,s/s"` string plus reference
     * character to signed decimal degrees, or `null` on any shape/range
     * failure --- the same rules as the read layer's
     * `ParsedExif.parseGpsCoordinate` (a missing reference or an
     * unparseable component means the block is unusable, and the honest
     * current value is "none").
     */
    private fun gpsDecimal(tagValues: Map<String, String>, valueTag: String, refTag: String): Double? {
        val text = nonBlank(tagValues, valueTag) ?: return null
        val reference = nonBlank(tagValues, refTag) ?: return null
        val parts = text.split(",")
        if (parts.size != 3) return null
        val degrees = ExifMath.parseRationalText(parts[0]) ?: return null
        val minutes = ExifMath.parseRationalText(parts[1]) ?: return null
        val seconds = ExifMath.parseRationalText(parts[2]) ?: return null
        return ExifMath.gpsRationalToDecimalDegrees(degrees, minutes, seconds, reference)
    }

    private const val SECONDS_DENOMINATOR = 10000000L

    /** 60.0 s expressed in 1e-7 s units: the carry threshold. */
    private const val SECONDS_PER_MINUTE_X_1E7 = 60L * SECONDS_DENOMINATOR
}
