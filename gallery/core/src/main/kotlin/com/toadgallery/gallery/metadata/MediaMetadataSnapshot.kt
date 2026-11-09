package com.toadgallery.gallery.metadata

/**
 * Pure data summary of one item's metadata (Phase 4 contract, item 4:
 * `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`).
 *
 * This is the read-only, cacheable projection the Aves-informed audit asks
 * for: everything a Details view or the AI indexer needs about one item's
 * metadata in plain values, with no Android types, so it can live in the
 * future Room catalogue, be compared across revisions, and be produced by
 * JVM-tested assembly code ([ParsedExif] today; IPTC/XMP/video/container
 * wrappers in later rounds). Write support (EXIF edit + revision bump +
 * targeted re-index, audit item 9) is explicitly a LATER round --- nothing
 * here mutates, and no field exists only to serve writing.
 *
 * Per-field absence uses [MetadataValue] rather than nullable payloads so
 * the UI can distinguish "no GPS" from "GPS unreadable" from "GPS exists
 * but this format round cannot read it" (see [MetadataAvailability]).
 *
 * Dates carry only availability here plus the raw EXIF capture millis; the
 * timezone-aware [CaptureDateSources] precedence resolver (EXIF vs XMP vs
 * QuickTime vs MediaStore, with offsets and confidence --- audit item 3) is
 * its own later contract and deliberately NOT flattened into this snapshot.
 */
data class MediaMetadataSnapshot(
    /** Normalized lowercase MIME type, echoed from the MediaStore row. */
    val mimeType: String,
    /** Pixel width; `0` means unknown (same convention as `MediaItem`). */
    val width: Int,
    /** Pixel height; `0` means unknown. */
    val height: Int,
    /**
     * EXIF orientation as a whole-number rotation (0/90/180/270) needed to
     * display the item upright. Mirrored orientations (2/4/5/7) share the
     * rotation of their unmirrored sibling; the flip bit is not captured
     * this round. `0` also means "no/unusable orientation tag".
     */
    val orientationDegrees: Int,
    /** File size in bytes, from the reader that produced the snapshot. */
    val fileSizeBytes: Long,
    /** Camera make/model/lens block, with its honest availability. */
    val camera: MetadataValue<CameraInfo>,
    /** Exposure block (aperture, shutter, ISO, focal length). */
    val exposure: MetadataValue<ExposureInfo>,
    /** GPS coordinates in signed decimal degrees (DMS already applied). */
    val gps: MetadataValue<GpsCoordinates>,
    /**
     * Per-source capture-date availability: which metadata blocks COULD
     * carry a capture date and in what state they are. The Aves-informed
     * audit insists dates preserve their source instead of collapsing into
     * one number; the resolver that picks a winner is the later MediaDates
     * contract.
     */
    val captureDates: CaptureDateSources,
    /**
     * The raw EXIF DateTimeOriginal as epoch millis. EXIF datetimes are
     * naive local times; this value anchors them at UTC purely to get a
     * deterministic, cacheable Long (see [ExifMath.parseExifDateTime] for
     * the documented limitation). Superseded by the MediaDates round.
     */
    val exifCaptureDateMillis: Long? = null,
)

/**
 * Camera identity block: free-form strings, `null` per field when the tag
 * is missing from the EXIF block. All-null with PRESENT availability is
 * therefore impossible --- an all-missing block reads ABSENT.
 */
data class CameraInfo(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
)

/**
 * Exposure triangle + focal length. `null` per field when that specific
 * tag is missing or unparseable while the block as a whole exists.
 */
data class ExposureInfo(
    /** f-number (aperture), e.g. `2.8` from the EXIF rational `28/10`. */
    val fNumber: Double? = null,
    /** Shutter speed in seconds, e.g. `0.004` for a `1/250` tag. */
    val exposureSeconds: Double? = null,
    /** ISO speed, e.g. `400`. */
    val iso: Int? = null,
    /** Focal length in millimeters. */
    val focalLengthMm: Double? = null,
)

/**
 * GPS coordinates in signed decimal degrees --- degrees/minutes/seconds
 * and the N/S/E/W reference are normalized once, at parse time
 * ([ExifMath.gpsRationalToDecimalDegrees]), so every consumer (maps,
 * Places, the privacy scrubber) sees one canonical form.
 */
data class GpsCoordinates(
    /** Latitude, -90..90; north positive. */
    val latitude: Double,
    /** Longitude, -180..180; east positive. */
    val longitude: Double,
)

/**
 * Availability of a capture date per metadata source block (audit item 3
 * groundwork): EXIF (still images), the container (video --- mp4/mov
 * creation time and friends, read via [VideoMetadataSource]), and XMP.
 *
 * Per-source flags rather than one resolved date because the audit's
 * Aves-informed finding is that EXIF original dates, XMP dates, video
 * stream dates and filesystem timestamps must stay distinguishable; the
 * precedence resolver that produces ONE date is the later MediaDates
 * contract and will consume these flags.
 */
data class CaptureDateSources(
    /** EXIF DateTimeOriginal block state for this item. */
    val exif: MetadataAvailability,
    /** Container (video) creation-date block state. */
    val container: MetadataAvailability,
    /** XMP create date block state (no XMP parser this round). */
    val xmp: MetadataAvailability,
)
