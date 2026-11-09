package com.toadgallery.gallery.data.media.dates

/**
 * Where a capture date came from, declared in PRECEDENCE ORDER: the
 * [ordinal] of each entry IS its rank (0 = most trustworthy), and
 * [MediaDateResolver] relies on that ordering instead of re-encoding it
 * in a second table that could drift.
 *
 * The order follows the Phase 4 contract
 * (`docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`, item 3:
 * "documented precedence/fallback resolver for EXIF/XMP/QuickTime/
 * MediaStore") and the general principle that the tag closest to the
 * camera shutter is the least likely to have been rewritten by export
 * tools, sync clients or filesystem copies.
 */
enum class CaptureDateSource {
    /**
     * Rank 0 --- EXIF `DateTimeOriginal`, the instant the shutter opened.
     * Highest trust: written by the camera at capture and only corrupted
     * by deliberate rewriting.
     */
    EXIF_ORIGINAL,

    /**
     * Rank 1 --- EXIF `DateTimeDigitized` (`CreateDate`), when the sensor
     * data was digitized. Usually identical to EXIF_ORIGINAL; it wins only
     * when the original tag is absent.
     */
    EXIF_DIGITIZED,

    /**
     * Rank 2 --- XMP date fields (`xmp:CreateDate`, `photoshop:DateCreated`).
     * Sidecar/XMP editors rewrite these freely, so they rank below both
     * EXIF tags, but they still describe the capture event, not the file.
     */
    XMP,

    /**
     * Rank 3 --- container creation time (QuickTime/MP4 `mvhd`/`tkhd`
     * creation timestamps), the only capture-like date videos have. It is
     * second-granular and some muxers set it to the encode time, so it
     * ranks below every still-image source.
     */
    CONTAINER,

    /**
     * Rank 4 --- MediaStore `date_taken`/`date_added`, already epoch
     * millis and already the provider's own best guess. A fallback only:
     * it reflects import/copy time on many devices.
     */
    MEDIASTORE,

    /**
     * Rank 5 --- no usable source at all. The resolver never resolves a
     * candidate to this rank; it appears when a caller must construct a
     * [MediaDates] before any extraction has run, or when extraction found
     * nothing and no fallback existed.
     */
    UNKNOWN,
}

/**
 * The full date picture of one media item (Phase 4 contract: "store
 * added, modified, capture date, source tag, timezone offset and
 * confidence" --- the confidence aspect is carried by
 * [ResolvedCaptureDate.sourcesDisagree] at resolve time).
 *
 * TIMEZONE SEMANTICS --- the part the audit flags as a decision that must
 * land before Moments or chronological clustering:
 * - [dateAddedMillis] and [dateModifiedMillis] are MediaStore values and
 *   are already epoch millis (UTC-based); they need no offset.
 * - EXIF and XMP timestamps are NAIVE --- they carry no zone. The
 *   extractor interpreted them using an ASSUMED local zone (the EXIF
 *   `OffsetTimeOriginal` when present, else the device zone), and
 *   [timezoneOffsetMinutes] records THAT assumption, minutes east of UTC,
 *   so a later pass can reinterpret or correct the instant without
 *   re-reading the file's bytes.
 * - [captureDateMillis] is the already-interpreted instant produced by
 *   [MediaDateResolver]; [captureDateSource] says which tag won. It can
 *   legitimately be NEGATIVE epoch millis: pre-1970 capture dates
 *   (scanned film archives, historical photos) are real instants, not
 *   malformed values --- the resolver keeps them, and only UNKNOWN-source
 *   candidates are ever discarded (audit finding F).
 * - [subsecondMillis] is the fractional part of the capture instant,
 *   kept SEPARATE from [captureDateMillis] because EXIF subsecond tags
 *   are optional and every other source has second granularity only ---
 *   folding it in would invent precision the source never had.
 */
data class MediaDates(
    /** MediaStore `date_added` in epoch millis (UTC-based, no offset). */
    val dateAddedMillis: Long,
    /** MediaStore `date_modified` in epoch millis (UTC-based, no offset). */
    val dateModifiedMillis: Long,
    /** Resolved capture instant, or `null` when nothing usable existed. */
    val captureDateMillis: Long? = null,
    /** Which tag won the resolution; [CaptureDateSource.UNKNOWN] when no capture date was found. */
    val captureDateSource: CaptureDateSource = CaptureDateSource.UNKNOWN,
    /** Assumed zone used to interpret the naive capture tag, minutes east of UTC; `null` for epoch-based sources. */
    val timezoneOffsetMinutes: Int? = null,
    /** Sub-second part of the capture instant, 0..999; `0` when the source had no subsecond tag. */
    val subsecondMillis: Int = 0,
)
