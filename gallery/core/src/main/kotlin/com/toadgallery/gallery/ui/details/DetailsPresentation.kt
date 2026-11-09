package com.toadgallery.gallery.ui.details

import com.toadgallery.gallery.data.DataSizeFormats
import com.toadgallery.gallery.data.catalogue.CatalogueRecord
import com.toadgallery.gallery.data.media.identity.EligiblePublicMedia
import com.toadgallery.gallery.data.media.location.MediaLocationState
import com.toadgallery.gallery.metadata.GpsCoordinates
import com.toadgallery.gallery.metadata.MediaMetadataSnapshot
import com.toadgallery.gallery.metadata.MetadataAvailability
import com.toadgallery.gallery.metadata.edit.EditableExifCurrentValues
import com.toadgallery.gallery.metadata.edit.MediaEditCapability
import com.toadgallery.gallery.metadata.edit.MediaEditField
import com.toadgallery.gallery.metadata.edit.MediaEditGate
import com.toadgallery.gallery.metadata.exif.ExifMath
import com.toadgallery.gallery.metadata.format.MetadataFormatMatrix
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * The ordered display model of the Phase 4 Details screen (roadmap Phase 4:
 * "Metadata Details view with read"), as ONE pure function
 * ([DetailsPresentation.rows]) over the inputs the screen has in hand:
 * the item's [MediaMetadataSnapshot] (r10 read layer, nullable --- no
 * snapshot is an honest state, never a crash) and its
 * [CatalogueRecord] (r12 shape ride-alongs, nullable --- see
 * [DetailsViewModel] for the not-in-catalogue fallback).
 *
 * The UI never formats metadata itself: every string a row shows either
 * comes out of this mapper or is an availability label the composable
 * resolves from resources. That keeps the WHOLE mapping --- section order,
 * per-field degradation, DMS rendering, timezone phrasing --- JVM-pinned
 * (`DetailsPresentationTest`, ADR-0016: pure logic exhaustively tested;
 * the Compose screen itself stays untested by convention).
 *
 * THE AVAILABILITY CONTRACT IS THE WHOLE POINT (the Phase 4 audit's rule,
 * see [MetadataAvailability]): a row never collapses "absent" into
 * "unreadable" into "unsupported". Every row carries the honest four-way
 * state; the composable renders a distinct string per state, so "no GPS"
 * ("None") can never render like "GPS corrupt" ("Unreadable") or "this
 * build cannot read AVIF EXIF" ("Not supported for this format yet").
 */
object DetailsPresentation {

    /** Stable display sections, in the order the screen renders them. */
    enum class DetailsSection {
        /** Identity + storage facts (MIME, size, dimensions). */
        FILE,

        /** The resolved capture instant + its timezone provenance. */
        CAPTURE_DATE,

        /** Camera make / model / lens. */
        CAMERA,

        /** Aperture / shutter / ISO / focal length / flash. */
        EXPOSURE,

        /** GPS coordinates, decimal + DMS. */
        GPS,

        /** The EXIF block itself plus the IPTC/XMP wrappers (later rounds). */
        METADATA_BLOCKS,
    }

    /** One displayable field; the composable maps this to a label resource. */
    enum class DetailsField {
        MIME_TYPE,
        FILE_SIZE,
        DIMENSIONS,
        CAPTURE_DATE,
        CAPTURE_ZONE,
        CAPTURE_SOURCE,
        CAMERA_MAKE,
        CAMERA_MODEL,
        CAMERA_LENS,
        APERTURE,
        SHUTTER,
        ISO,
        FOCAL_LENGTH,
        FLASH,
        GPS_DECIMAL,
        GPS_DMS,
        EXIF_BLOCK,
        IPTC_BLOCK,
        XMP_BLOCK,
    }

    /**
     * One display row: [value] non-null always means
     * [MetadataAvailability.PRESENT] and is shown verbatim; `null` means
     * the composable renders the honest state text for [availability]
     * ("None" / "Unreadable" / "Not supported for this format yet" /
     * "Extracted" for the block rows). [value] strings are either
     * locale-formatted ([locale] is a parameter, like `ViewerInfoFormat`)
     * or locale-stable ASCII (sizes via `DataSizeFormats`, zone offsets),
     * matching the repo's formatter conventions.
     */
    data class DetailRow(
        val section: DetailsSection,
        val field: DetailsField,
        val availability: MetadataAvailability,
        val value: String? = null,
    )

    /**
     * Builds the ordered rows for one item.
     *
     * [snapshot] is the parsed [MediaMetadataSnapshot], or `null` when the
     * EXIF block could not be read at all --- in which case every
     * EXIF-derived row degrades to [exifBlockAvailability] (ABSENT when
     * the parser found no block, UNREADABLE when bytes could not be read,
     * UNSUPPORTED when the format matrix excludes the type). [record] is
     * the catalogue row, or `null` for the ViewModel's not-in-catalogue
     * fallback (identity reconstructed from the MediaStore id + uri).
     */
    fun rows(
        snapshot: MediaMetadataSnapshot?,
        record: CatalogueRecord?,
        mimeType: String,
        exifBlockAvailability: MetadataAvailability,
        locale: Locale,
        gpsVisible: Boolean = true,
        gpsHiddenNote: String? = null,
    ): List<DetailRow> = buildList {
        // ---- FILE ----
        add(DetailRow(DetailsSection.FILE, DetailsField.MIME_TYPE, MetadataAvailability.PRESENT, mimeType))
        val sizeBytes = record?.sizeBytes?.takeIf { it > 0 }
            ?: snapshot?.fileSizeBytes?.takeIf { it > 0 }
        add(
            DetailRow(
                DetailsSection.FILE, DetailsField.FILE_SIZE,
                if (sizeBytes != null) MetadataAvailability.PRESENT else MetadataAvailability.ABSENT,
                sizeBytes?.let(DataSizeFormats::formatBytes),
            ),
        )
        val width = snapshot?.width ?: 0
        val height = snapshot?.height ?: 0
        add(
            DetailRow(
                DetailsSection.FILE, DetailsField.DIMENSIONS,
                if (width > 0 && height > 0) MetadataAvailability.PRESENT else exifBlockAvailability,
                if (width > 0 && height > 0) "$width × $height" else null,
            ),
        )

        // ---- CAPTURE DATE ----
        // Roadmap wording: "capture date with timezone semantics stated
        // explicitly". The record's provenance fields carry EXACTLY that:
        // the winning instant, the zone assumption (minutes east of UTC)
        // that interpreted the naive tag, and the winning source's name.
        // The snapshot's raw EXIF capture millis are the fallback when the
        // record has no resolved date (a pre-indexer scan row or the
        // not-in-catalogue fallback): the same instant, with the zone
        // marked ABSENT because the snapshot anchors naive EXIF datetimes
        // at UTC and carries no offset evidence (see MediaMetadataSnapshot
        // KDoc --- the MediaDates round supersedes this).
        val captureMillis = record?.resolvedCaptureDateMillis ?: snapshot?.exifCaptureDateMillis
        when {
            captureMillis != null -> {
                val zone = record?.resolvedCaptureDateZoneOffsetMinutes
                add(
                    DetailRow(
                        DetailsSection.CAPTURE_DATE, DetailsField.CAPTURE_DATE,
                        MetadataAvailability.PRESENT,
                        formatCaptureInstant(captureMillis, zone, locale),
                    ),
                )
                add(
                    DetailRow(
                        DetailsSection.CAPTURE_DATE, DetailsField.CAPTURE_ZONE,
                        if (zone != null) MetadataAvailability.PRESENT else MetadataAvailability.ABSENT,
                        zone?.let { "${zoneOffsetText(it)} (from file)" },
                    ),
                )
                add(
                    DetailRow(
                        DetailsSection.CAPTURE_DATE, DetailsField.CAPTURE_SOURCE,
                        MetadataAvailability.PRESENT,
                        record?.resolvedCaptureDateSource ?: "EXIF_ORIGINAL",
                    ),
                )
            }
            else -> {
                // No capture date at all: the record's null is deliberately
                // ambiguous (pre-indexer scan OR genuinely no trustworthy
                // tag --- CatalogueRecord documents why); the row says the
                // honest "none", never invents a reason.
                add(DetailRow(DetailsSection.CAPTURE_DATE, DetailsField.CAPTURE_DATE, MetadataAvailability.ABSENT))
            }
        }

        // ---- CAMERA ----
        when (val camera = snapshot?.camera) {
            null -> {
                add(cameraRow(DetailsField.CAMERA_MAKE, exifBlockAvailability))
                add(cameraRow(DetailsField.CAMERA_MODEL, exifBlockAvailability))
                add(cameraRow(DetailsField.CAMERA_LENS, exifBlockAvailability))
            }
            else -> {
                val info = camera.value
                add(cameraRow(DetailsField.CAMERA_MAKE, camera.availability, info?.make))
                add(cameraRow(DetailsField.CAMERA_MODEL, camera.availability, info?.model))
                add(cameraRow(DetailsField.CAMERA_LENS, camera.availability, info?.lensModel))
            }
        }

        // ---- EXPOSURE ----
        when (val exposure = snapshot?.exposure) {
            null -> {
                add(exposureRow(DetailsField.APERTURE, exifBlockAvailability))
                add(exposureRow(DetailsField.SHUTTER, exifBlockAvailability))
                add(exposureRow(DetailsField.ISO, exifBlockAvailability))
                add(exposureRow(DetailsField.FOCAL_LENGTH, exifBlockAvailability))
            }
            else -> {
                val info = exposure.value
                add(exposureRow(DetailsField.APERTURE, exposure.availability, info?.fNumber?.let { "f/${trim(it)}" }))
                add(
                    exposureRow(
                        DetailsField.SHUTTER, exposure.availability,
                        info?.exposureSeconds?.let(ExifMath::formatExposure),
                    ),
                )
                add(exposureRow(DetailsField.ISO, exposure.availability, info?.iso?.let { "ISO $it" }))
                add(exposureRow(DetailsField.FOCAL_LENGTH, exposure.availability, info?.focalLengthMm?.let { "${trim(it)} mm" }))
            }
        }
        // Flash: the pinned parser set does not extract the flash tag this
        // round (no field in MediaMetadataSnapshot). UNSUPPORTED is the
        // honest capability statement --- never a fake ABSENT.
        add(DetailRow(DetailsSection.EXPOSURE, DetailsField.FLASH, MetadataAvailability.UNSUPPORTED))

        // ---- GPS ----
        // Display gating (r14): the ACCESS_MEDIA_LOCATION consent flow owns
        // WHETHER coordinates are shown (MediaLocationDisplayRules.Default;
        // on Android 10+ the platform redacts EXIF location on ungranted
        // reads anyway, and the app's display policy is the conservative
        // side of the same choice). When hidden, the rows stay PRESENT-
        // honest --- the data exists in the file --- but the value carries
        // the localized note pointing at Settings; availability is never
        // faked to ABSENT, because the block really did contain coordinates.
        when (val gps = snapshot?.gps) {
            null -> {
                add(gpsRow(DetailsField.GPS_DECIMAL, exifBlockAvailability))
                add(gpsRow(DetailsField.GPS_DMS, exifBlockAvailability))
            }
            else -> {
                val coordinates = gps.value
                if (gps.availability == MetadataAvailability.PRESENT && !gpsVisible) {
                    add(gpsRow(DetailsField.GPS_DECIMAL, gps.availability, gpsHiddenNote))
                    add(gpsRow(DetailsField.GPS_DMS, gps.availability, gpsHiddenNote))
                } else {
                    add(gpsRow(DetailsField.GPS_DECIMAL, gps.availability, coordinates?.let(::decimalText)))
                    add(gpsRow(DetailsField.GPS_DMS, gps.availability, coordinates?.let { dms(it.latitude, it.longitude) }))
                }
            }
        }
        // ---- METADATA BLOCKS ----
        // The EXIF block itself (the snapshot exists iff the block was
        // extracted), plus the IPTC/XMP wrapper headers straight from the
        // format matrix --- the wrappers are a later round and the rows
        // say so honestly instead of faking content.
        add(
            DetailRow(
                DetailsSection.METADATA_BLOCKS, DetailsField.EXIF_BLOCK,
                if (snapshot != null) MetadataAvailability.PRESENT else exifBlockAvailability,
            ),
        )
        val capability = MetadataFormatMatrix.capabilitiesFor(mimeType)
        add(DetailRow(DetailsSection.METADATA_BLOCKS, DetailsField.IPTC_BLOCK, capability.iptc))
        add(DetailRow(DetailsSection.METADATA_BLOCKS, DetailsField.XMP_BLOCK, capability.xmp))
    }

    /** A camera row: a per-field null inside a PRESENT block reads ABSENT (the tag is missing, not the block). */
    private fun cameraRow(field: DetailsField, availability: MetadataAvailability, value: String? = null) =
        DetailRow(DetailsSection.CAMERA, field, rowAvailability(availability, value), value?.takeIf { it.isNotBlank() })

    private fun exposureRow(field: DetailsField, availability: MetadataAvailability, value: String? = null) =
        DetailRow(DetailsSection.EXPOSURE, field, rowAvailability(availability, value), value)

    private fun gpsRow(field: DetailsField, availability: MetadataAvailability, value: String? = null) =
        DetailRow(DetailsSection.GPS, field, rowAvailability(availability, value), value)

    /** A PRESENT block with a null field degrades the FIELD to ABSENT; other block states pass through. */
    private fun rowAvailability(availability: MetadataAvailability, value: String?): MetadataAvailability =
        if (availability == MetadataAvailability.PRESENT && value == null) {
            MetadataAvailability.ABSENT
        } else {
            availability
        }

    /** Trailing-zero-trimmed decimal rendering, locale-stable (ASCII). */
    private fun trim(value: Double): String =
        value.toString().removeSuffix(".0")

    /** Signed decimal degrees, fixed 6-decimal precision, locale-stable. */
    private fun decimalText(coordinates: GpsCoordinates): String =
        String.format(Locale.ROOT, "%.6f, %.6f", coordinates.latitude, coordinates.longitude)

    /** Both coordinates as one DMS string, latitude first. */
    private fun dms(latitude: Double, longitude: Double): String =
        "${decimalToDms(latitude, isLatitude = true)} ${decimalToDms(longitude, isLatitude = false)}"

    /**
     * Signed decimal degrees to degrees/minutes/seconds with hemisphere
     * letter: `37° 46' 22.0" N`. Seconds carry one decimal; the rounding
     * carry into 60.0" rolls into the minute (and 60' into the degree) so
     * the output never shows an impossible 60. The hemisphere carries the
     * sign --- negative input normalizes to the S/W letter, matching the
     * EXIF reference-character convention the parser normalized to.
     */
    fun decimalToDms(decimal: Double, isLatitude: Boolean): String {
        val hemisphere = when {
            isLatitude && decimal >= 0 -> "N"
            isLatitude -> "S"
            decimal >= 0 -> "E"
            else -> "W"
        }
        var remaining = floor(abs(decimal) * 3600.0 * 10.0 + 0.5) / 10.0
        val degrees = floor(remaining / 3600.0).toInt()
        remaining -= degrees * 3600.0
        val minutes = floor(remaining / 60.0).toInt()
        remaining -= minutes * 60.0
        // 0.1"-precision rounding can push seconds to 60.0; normalize the
        // carry instead of printing an impossible value.
        val seconds: Double
        val shownMinutes: Int
        val shownDegrees: Int
        if (remaining >= 59.95) {
            seconds = 0.0
            shownMinutes = minutes + 1
            shownDegrees = if (shownMinutes == 60) degrees + 1 else degrees
        } else {
            seconds = remaining
            shownMinutes = minutes
            shownDegrees = degrees
        }
        val shownMinutesText = if (shownMinutes == 60) 0 else shownMinutes
        return String.format(
            Locale.ROOT, "%d° %d' %.1f\" %s",
            shownDegrees, shownMinutesText, seconds, hemisphere,
        )
    }

    /**
     * A zone offset in minutes east of UTC as the ISO `±HH:MM` form
     * ("+02:00", "-07:30", "+00:00"); `null` renders "UTC (assumed)" ---
     * the tag carried no zone and the UTC-anchor convention (see
     * `ExifMath.parseExifDateTime`) made the decision. Locale-stable ASCII
     * on purpose: a zone offset is notation, not prose.
     */
    fun zoneOffsetText(offsetMinutes: Int?): String =
        if (offsetMinutes == null) {
            "UTC (assumed)"
        } else {
            val sign = if (offsetMinutes < 0) "-" else "+"
            val absMinutes = abs(offsetMinutes)
            String.format(Locale.ROOT, "%s%02d:%02d", sign, absMinutes / 60, absMinutes % 60)
        }

    /**
     * The capture instant rendered IN the zone that interpreted the tag
     * (the [offsetMinutes] the resolver recorded), so the displayed
     * wall-clock time is the one the camera wrote --- not the viewer
     * device's zone re-interpreting history. `null` offset renders the
     * UTC-anchored instant (the documented no-zone convention). Locale is
     * a parameter so the JVM test pins exact output under `Locale.US`.
     */
    fun formatCaptureInstant(epochMillis: Long, offsetMinutes: Int?, locale: Locale): String {
        // `locale` shapes nothing in this fixed ASCII-digit pattern today,
        // but it stays a parameter: localized digit systems (Devanagari,
        // Arabic-Indic) are one formatter locale change away and the JVM
        // test pins the Locale.US contract.
        val zone = ZoneOffset.ofTotalSeconds((offsetMinutes ?: 0) * 60)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return Instant.ofEpochMilli(epochMillis).atZone(zone).format(formatter)
    }

    // ------------------------------------------------------------------
    // Editable fields (r15 write round)
    // ------------------------------------------------------------------

    /**
     * One row of the Details view's "Editable fields" section (the r15
     * write round): the roadmap field, its honest
     * [MediaEditCapability] (the pure gate's answer for THIS item), the
     * field's CURRENT value as display text (`null` = not set / not
     * readable through the pinned writer's tag set --- keywords, rating
     * and colour label are modelled but unreadable AND unwritable through
     * androidx.exifinterface this round, so they render state text only,
     * exactly like the IPTC/XMP block rows), and whether the writer's
     * read-back produced a values object at all.
     *
     * [valuesReadable] keeps the two `valueText == null` reasons apart:
     * `true` (the read-back succeeded) renders "not set" for an EDITABLE
     * field, while `false` (the read-back itself failed --- the writer
     * could not open the file) renders "current value unreadable" ---
     * "not set" would be a claim about bytes the app could not read, and
     * the honest-states doctrine forbids it.
     */
    data class EditableFieldRow(
        val field: MediaEditField,
        val capability: MediaEditCapability,
        val valueText: String? = null,
        val valuesReadable: Boolean = false,
    )

    /**
     * Builds the editable-fields section: ONE row per roadmap field, in
     * [MediaEditField] declaration order, every refusal visible with its
     * own reason (the honest-states doctrine --- nothing silently
     * omitted just because this round cannot write it).
     *
     * [currentValues] is what the writer's read-back produced for the
     * item (`null` when the format is outside the pinned write set or
     * the values could not be read --- see [EditableFieldRow.valuesReadable]
     * for how the two render differently). [eligible]/[mimeType]/
     * [apiLevel]/[locationState] feed the pure [MediaEditGate] exactly
     * as the write path will consult it.
     */
    fun editableRows(
        eligible: EligiblePublicMedia?,
        mimeType: String,
        apiLevel: Int,
        locationState: MediaLocationState,
        currentValues: EditableExifCurrentValues?,
    ): List<EditableFieldRow> = MediaEditField.entries.map { field ->
        EditableFieldRow(
            field = field,
            capability = MediaEditGate.capabilityFor(
                eligible = eligible,
                mimeType = mimeType,
                field = field,
                apiLevel = apiLevel,
                locationState = locationState,
            ),
            valueText = editValueText(field, currentValues),
            valuesReadable = currentValues != null,
        )
    }

    /**
     * The current value of [field] as display text, or `null` when unset
     * or not readable this round. Locale-stable ASCII on purpose
     * (coordinates and EXIF datetime strings are notation, not prose);
     * the GPS form matches the read view's [decimalText] convention.
     */
    private fun editValueText(
        field: MediaEditField,
        values: EditableExifCurrentValues?,
    ): String? = when (field) {
        MediaEditField.DATE_TIME -> {
            val dateTime = values?.dateTimeOriginal ?: return null
            val offset = values.offsetTimeOriginal
            if (offset == null) dateTime else "$dateTime $offset"
        }
        MediaEditField.GPS -> {
            val latitude = values?.gpsLatitude ?: return null
            val longitude = values.gpsLongitude ?: return null
            String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)
        }
        MediaEditField.COPYRIGHT -> values?.copyright
        MediaEditField.COMMENT -> values?.userComment
        // Not readable through the pinned androidx tag map (verified from
        // the 1.4.1 sources): no value is ever shown for them, only the
        // capability state text --- never a guessed or cached one.
        MediaEditField.KEYWORDS,
        MediaEditField.RATING,
        MediaEditField.COLOUR_LABEL,
        -> null
    }
}
