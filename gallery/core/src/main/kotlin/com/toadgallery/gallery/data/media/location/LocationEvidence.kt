package com.toadgallery.gallery.data.media.location

import com.toadgallery.gallery.metadata.GpsCoordinates
import com.toadgallery.gallery.metadata.MetadataAvailability

/**
 * The location extraction + provenance contracts (r16 round; file renamed
 * from LocationIndexer.kt by the r20 round, which replaced the standalone
 * location indexer with the combined single-read pass --- see
 * [com.toadgallery.gallery.data.media.changes.CombinedExifIndexer],
 * the successor that consumes these contracts unchanged, and
 * [com.toadgallery.gallery.data.media.changes.CompositeScanIndexHook],
 * which moved to the scan-pipeline package the same round). What remains
 * here is the still-load-bearing half: the provenance enum, the evidence
 * type, the consent gate, the pure write mapping, and the targeted writer
 * seam. The per-dimension candidate-source seam and its Android
 * implementation were REMOVED with the indexer (the combined source is
 * the one extraction seam now); every availability-state contract they
 * pinned lives on, JVM-tested, through the combined pass.
 */

/**
 * Where a persisted item location came from (r16 round), stored as the
 * enum's [name][kotlin.Enum.name] string in the catalogue's
 * `locationSource` column --- the same stringly-enum convention as
 * `resolvedCaptureDateSource` (see
 * [com.toadgallery.gallery.data.catalogue.room.CatalogueItemEntity]).
 */
enum class ItemLocationSource {
    /**
     * The coordinates were parsed from the item's OWN EXIF GPS block
     * (GPSLatitude/GPSLongitude + references, normalized to signed
     * decimal degrees by
     * [com.toadgallery.gallery.metadata.exif.ExifMath
     * .gpsRationalToDecimalDegrees]).
     */
    EXIF_GPS,

    /**
     * The item is EXIF-capable but was indexed while the platform
     * redacts EXIF location: API 29+ with ACCESS_MEDIA_LOCATION
     * ungranted. The coordinates honestly read as ABSENT (the platform
     * withholds the tags --- verified against
     * https://developer.android.com/training/data-storage/shared/media,
     * "Media location permission"), so the row persists `null` latitude
     * and longitude WITH this provenance, which is exactly the set a
     * future grant-triggered re-pass will target (see
     * [LocationConsentReindexPolicy]).
     */
    MEDIA_LOCATION_UNGRANTED,
}

/**
 * The GPS extraction outcome for one item: the parsed [coordinates]
 * (non-null iff [availability] is PRESENT) plus the HONEST availability
 * when there are none --- the same four-state discipline as
 * [com.toadgallery.gallery.data.media.dates.CaptureDateEvidence].
 *
 * Invariant the production extractor upholds and the tests pin:
 * PRESENT iff [coordinates] is non-null; every other state arrives with
 * null coordinates and a specific reason (ABSENT: the file carries no
 * GPS block; UNSUPPORTED: the format is outside the pinned parser set;
 * UNREADABLE: bytes or block could not be read).
 */
data class GpsEvidence(
    /** Signed decimal degrees, or `null` for every non-PRESENT state. */
    val coordinates: GpsCoordinates?,
    /** Availability of the EXIF GPS metadata (see the class KDoc). */
    val availability: MetadataAvailability,
)

/**
 * The consent seam: may EXIF GPS be READ from other apps' media right
 * now? Production answers with the r14 machinery (REUSED, not forked):
 * `LocationReadGate { mediaLocationAccess.isGranted() }` over the same
 * [com.toadgallery.gallery.data.media.location
 * .AndroidMediaLocationAccess] the Settings row and the Details gate
 * use. Below API 29 the answer is always true (the permission does not
 * exist and the platform never redacted anything); on 29+ it is true
 * only while ACCESS_MEDIA_LOCATION is granted
 * (https://developer.android.com/training/data-storage/shared/media).
 */
fun interface LocationReadGate {
    /** True when EXIF GPS reads would NOT be redacted by the platform. */
    fun gpsReadable(): Boolean
}

/** The provenance write the combined index pass derives for one row. */
data class ResolvedLocation(
    /** Decimal-degrees latitude, or `null` when honestly unresolved. */
    val latitude: Double?,
    /** Decimal-degrees longitude, or `null` when honestly unresolved. */
    val longitude: Double?,
    /** Where the outcome came from; `null` = honestly unknown. */
    val source: ItemLocationSource?,
)

/**
 * The PURE mapping half of the location index pass (r16): the consent
 * state, the format capability and the extracted evidence -> the
 * provenance write the index pass will make. Exhaustively JVM-tested
 * (the combined pass's suite carries the table since r20); the pass
 * itself adds nothing but I/O around this function.
 *
 * Rules (mirroring [com.toadgallery.gallery.data.media.dates
 * .resolveCaptureDateWrite]'s "null means honestly unknown" doctrine,
 * with ONE deliberate widening the location round demands):
 *  - NOT readable + EXIF-capable -> null coordinates with
 *    [ItemLocationSource.MEDIA_LOCATION_UNGRANTED]. This is the mission's
 *    "ungranted reads honestly as ABSENT (null, its own provenance)"
 *    state: the row records WHY it has no location, so a later
 *    grant-triggered re-pass can find exactly these rows.
 *  - NOT readable + not EXIF-capable -> null/null/null. The format
 *    cannot carry EXIF GPS at all, so the redaction is moot; claiming
 *    "withheld" for a PNG would be a lie.
 *  - Readable but the item could not be OPENED ([evidence] `null`) ->
 *    null/null/null (could not read: honestly unknown --- the same
 *    honest degradation as a failed read, and for a CHANGED row the
 *    wipe is correct: previously persisted coordinates describe bytes
 *    that no longer exist).
 *  - Readable + PRESENT evidence -> the coordinates with
 *    [ItemLocationSource.EXIF_GPS]. A PRESENT flag with null
 *    coordinates is a seam-contract violation; the defensive path
 *    persists null/null/null rather than trusting it.
 *  - Readable + ABSENT / UNSUPPORTED / UNREADABLE evidence ->
 *    null/null/null. The catalogue's null stays deliberately ambiguous
 *    across the three states (exactly the capture-date doctrine: the
 *    distinction lives in the seam and its tests, not in the row).
 */
internal fun resolveLocationWrite(
    gpsReadable: Boolean,
    exifCapable: Boolean,
    evidence: GpsEvidence?,
): ResolvedLocation = when {
    !gpsReadable && exifCapable ->
        ResolvedLocation(null, null, ItemLocationSource.MEDIA_LOCATION_UNGRANTED)
    !gpsReadable ->
        ResolvedLocation(null, null, null)
    evidence == null ->
        ResolvedLocation(null, null, null)
    evidence.availability == MetadataAvailability.PRESENT && evidence.coordinates != null ->
        ResolvedLocation(
            latitude = evidence.coordinates.latitude,
            longitude = evidence.coordinates.longitude,
            source = ItemLocationSource.EXIF_GPS,
        )
    else ->
        ResolvedLocation(null, null, null)
}

/**
 * The narrow write seam for the location columns: the targeted partial
 * UPDATE statement behind it touches ONLY the three location columns of
 * the row (the same "cannot clobber concurrent writers" guarantee
 * [com.toadgallery.gallery.data.catalogue.MediaCatalogue
 * .updateCaptureDate] documents). Deliberately NOT a new
 * [com.toadgallery.gallery.data.catalogue.MediaCatalogue] member:
 * that interface and its in-memory reference are outside this round's
 * editable scope, and the Room implementation (an end-of-file section in
 * `RoomMediaCatalogue.kt`) satisfies this shape directly.
 */
fun interface ItemLocationWriter {
    /**
     * Persists the location provenance for [localMediaKey]. An unknown or
     * tombstoned key is a no-op, never a resurrection (the UPDATE
     * matches zero rows).
     */
    suspend fun updateLocation(
        localMediaKey: String,
        latitude: Double?,
        longitude: Double?,
        sourceName: String?,
    )
}
