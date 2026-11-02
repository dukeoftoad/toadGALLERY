package io.github.dukeoftoad.gallery.data.media.location

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.media.access.MediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadHandle
import io.github.dukeoftoad.gallery.data.media.changes.CatalogueReconciler
import io.github.dukeoftoad.gallery.data.media.changes.ScanIndexHook
import io.github.dukeoftoad.gallery.data.media.dates.CaptureDateEligibilityGate
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.metadata.GpsCoordinates
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import io.github.dukeoftoad.gallery.metadata.exif.AndroidExifReader
import io.github.dukeoftoad.gallery.metadata.exif.ExifReader
import io.github.dukeoftoad.gallery.metadata.format.MetadataFormatMatrix
import kotlin.coroutines.cancellation.CancellationException

/**
 * Where a persisted item location came from (r16 round), stored as the
 * enum's [name][kotlin.Enum.name] string in the catalogue's
 * `locationSource` column --- the same stringly-enum convention as
 * `resolvedCaptureDateSource` (see
 * [io.github.dukeoftoad.gallery.data.catalogue.room.CatalogueItemEntity]).
 */
enum class ItemLocationSource {
    /**
     * The coordinates were parsed from the item's OWN EXIF GPS block
     * (GPSLatitude/GPSLongitude + references, normalized to signed
     * decimal degrees by
     * [io.github.dukeoftoad.gallery.metadata.exif.ExifMath
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
 * [io.github.dukeoftoad.gallery.data.media.dates.CaptureDateEvidence].
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
 * The extraction seam (ADR-0016): pulls GPS coordinates out of one
 * item's original bytes through the [MediaReadHandle] read boundary.
 * Keeping this an interface is what makes [LocationIndexer]'s decisions
 * JVM-testable with a fake; the real EXIF plumbing stays behind the one
 * Android implementation, [AndroidGpsCandidateSource].
 */
interface GpsCandidateSource {

    /**
     * Extracts coordinates from the bytes [handle] exposes for an item of
     * [mimeType]. Never throws for an unreadable item --- unparseable
     * bytes yield [GpsEvidence] with null coordinates and
     * [MetadataAvailability.UNREADABLE] (one bad item never aborts a
     * batch, the same honest-degradation contract as the capture-date
     * source).
     */
    suspend fun extractCoordinates(handle: MediaReadHandle, mimeType: String): GpsEvidence
}

/**
 * The production [GpsCandidateSource] (ADR-0016-thin, deliberately
 * untested): reads the item's original bytes ONCE through the read
 * boundary and hands them to the EXISTING reader assembly
 * ([ExifReader] -> [io.github.dukeoftoad.gallery.metadata.exif.ParsedExif]
 * -> [io.github.dukeoftoad.gallery.metadata.exif.ExifMath]) --- the same
 * GPS DMS-to-decimal validation the r10/r14 Details path uses, reused
 * rather than forked. Every decision lives in those JVM-tested types.
 *
 * ORDER NOTE (deliberate deviation from
 * [io.github.dukeoftoad.gallery.data.media.dates
 * .AndroidCaptureDateCandidateSource], which reads bytes before checking
 * the format matrix): the capability check runs FIRST here. GPS in this
 * round exists only in EXIF, so a video/AVIF/unknown item can never
 * yield coordinates, and buffering up to 64 MiB of video bytes to learn
 * that would be pure waste on the two common formats the capture-date
 * pass cares about but this pass never can.
 */
class AndroidGpsCandidateSource(
    private val exifReader: ExifReader = AndroidExifReader(),
) : GpsCandidateSource {

    override suspend fun extractCoordinates(handle: MediaReadHandle, mimeType: String): GpsEvidence {
        val capability = MetadataFormatMatrix.capabilitiesFor(mimeType)
        if (capability.exif != MetadataAvailability.PRESENT) {
            // A capability statement about the FORMAT, never a claim
            // about this file's bytes --- the bytes are not read at all.
            return GpsEvidence(null, capability.exif)
        }
        val bytes = handle.readBytes(MediaReadHandle.DEFAULT_MAX_BYTES)
            ?: return GpsEvidence(null, MetadataAvailability.UNREADABLE)
        // The reader's `null` conflates "no EXIF block at all" with
        // "the block could not be opened" (its documented contract);
        // ABSENT is chosen because within an EXIF-capable format the
        // no-block case dominates by orders of magnitude, and the
        // distinction does not survive this seam anyway --- both states
        // map to the same persisted write (null/null/null).
        val snapshot = exifReader.read(bytes, mimeType)
            ?: return GpsEvidence(null, MetadataAvailability.ABSENT)
        val gps = snapshot.gps
        return if (gps.availability == MetadataAvailability.PRESENT && gps.value != null) {
            GpsEvidence(gps.value, MetadataAvailability.PRESENT)
        } else {
            GpsEvidence(null, gps.availability)
        }
    }
}

/**
 * The consent seam: may EXIF GPS be READ from other apps' media right
 * now? Production answers with the r14 machinery (REUSED, not forked):
 * `LocationReadGate { mediaLocationAccess.isGranted() }` over the same
 * [io.github.dukeoftoad.gallery.data.media.location
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

/** The provenance write [LocationIndexer] derives for one row. */
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
 * provenance write the indexer will make. Exhaustively JVM-tested
 * ([LocationIndexerTest]); the indexer itself adds nothing but I/O
 * around this function.
 *
 * Rules (mirroring [io.github.dukeoftoad.gallery.data.media.dates
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
 * [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
 * .updateCaptureDate] documents). Deliberately NOT a new
 * [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue] member:
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

/**
 * The scan driver's location index pass (r16 round, Phase 4 still-open
 * item "on-device map view"): for every row a scan just applied to the
 * catalogue, extracts the item's EXIF GPS ONCE through the
 * [MediaReadHandle] read boundary and persists latitude/longitude +
 * provenance onto the row via the targeted [ItemLocationWriter] partial
 * update.
 *
 * THE CONSENT SUBTLETY (verified against
 * https://developer.android.com/training/data-storage/shared/media): on
 * API 29+ the platform redacts EXIF location from other apps' media
 * unless ACCESS_MEDIA_LOCATION is granted. Rather than reading bytes the
 * platform will hand back redacted, the indexer consults the
 * [LocationReadGate] FIRST (once per batch --- a scan pass is seconds
 * long and consent flips mid-batch are not a real state); ungranted
 * means the honest ABSENT write (null coordinates + the
 * MEDIA_LOCATION_UNGRANTED provenance) with NO bytes read. Below API 29
 * the gate is always readable and the pass behaves like any EXIF read.
 *
 * ELIGIBILITY: the SAME discipline as
 * [io.github.dukeoftoad.gallery.data.media.dates.CaptureDateIndexer] ---
 * the type of the injected gate is literally
 * [CaptureDateEligibilityGate] (reused, not forked: "may this process
 * read this record's bytes" is not a date-specific question), so vault /
 * trashed / malformed rows are skipped entirely: no bytes read, no
 * catalogue write, and the type-level proof
 * ([io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia])
 * makes vault media unrepresentable downstream.
 *
 * Changed rows index their NEW state
 * ([CatalogueReconciler.ChangedRecord.new]) only, exactly like the
 * capture-date indexer: the write is keyed by the new row's local media
 * key and touches only the location columns.
 */
class LocationIndexer(
    private val locationWriter: ItemLocationWriter,
    private val mediaAccess: MediaAccess,
    private val candidateSource: GpsCandidateSource,
    private val eligibilityGate: CaptureDateEligibilityGate,
    private val locationReadGate: LocationReadGate,
) : ScanIndexHook {

    /**
     * The hook the scan driver calls after applying a scan's added/changed
     * rows. Each row is indexed independently: one unreadable item never
     * aborts the batch --- and that promise is now STRUCTURAL: the per-row
     * index step runs inside its own catch, so a Room disk error or an EXIF
     * parse blowup in one row is logged (DiagLog, class+message) and
     * skipped while the batch continues. A failed row keeps its existing
     * provenance (its write never ran), and because its revision did not
     * move the next scan classifies it UNCHANGED --- this hook never sees
     * it again; the NULL-provenance healing rescan pass is the named
     * later-round follow-up (the capture-date indexer documents the same
     * remainder). The consent gate is read ONCE per call --- see the
     * class KDoc.
     */
    override suspend fun onRecordsApplied(
        added: List<CatalogueRecord>,
        changed: List<CatalogueReconciler.ChangedRecord>,
    ) {
        val gpsReadable = locationReadGate.gpsReadable()
        for (record in added) {
            indexOne(record, gpsReadable)
        }
        for (change in changed) {
            // The NEW state's key/uri/mime drive the pass (the sanctioned
            // row->record shape, metadataCacheState EMPTY, revision 0 ---
            // used only for gating and the evidence read; the targeted
            // update writes location columns alone, never this record).
            indexOne(change.new.toRecord(), gpsReadable)
        }
    }

    private suspend fun indexOne(record: CatalogueRecord, gpsReadable: Boolean) {
        // The per-row failure boundary: everything this hook owns for ONE
        // row runs inside the catch below, so a Room disk error or an EXIF
        // parse blowup costs that row's indexing alone --- logged and
        // skipped, never the batch (see onRecordsApplied's KDoc for the
        // NULL-provenance follow-up).
        try {
            // Gate first: an ineligible item is skipped WITHOUT a catalogue
            // write (vault gate by type; a tombstoned key additionally makes
            // the writer's UPDATE a no-op --- the indexer never resurrects).
            val media = eligibilityGate.eligiblePublicMedia(record) ?: return
            val exifCapable =
                MetadataFormatMatrix.capabilitiesFor(record.mimeType).exif == MetadataAvailability.PRESENT
            val resolved = if (!gpsReadable) {
                // No bytes read: the platform would hand back redacted tags
                // anyway (class KDoc), so the write is decided from the
                // consent state + format capability alone.
                resolveLocationWrite(gpsReadable = false, exifCapable = exifCapable, evidence = null)
            } else {
                val handle = mediaAccess.open(media)
                val evidence = if (handle == null) null else candidateSource.extractCoordinates(handle, record.mimeType)
                resolveLocationWrite(gpsReadable = true, exifCapable = exifCapable, evidence = evidence)
            }
            locationWriter.updateLocation(
                localMediaKey = record.localMediaKey,
                latitude = resolved.latitude,
                longitude = resolved.longitude,
                sourceName = resolved.source?.name,
            )
        } catch (ce: CancellationException) {
            // Structured cancellation is not a per-row failure --- let it
            // propagate (the same discipline every catch-all here keeps).
            throw ce
        } catch (t: Throwable) {
            DiagLog.e(
                TAG,
                "location index failed key=${record.localMediaKey} (batch unaffected)",
                t,
            )
        }
    }

    private companion object {
        /** Diagnostics tag for the per-row failure-isolation lines. */
        private const val TAG = "LocationIndexer"
    }
}

/**
 * Fans ONE [ScanIndexHook] callback out to N hooks (r16 wiring seam):
 * the driver accepts a single hook, and the pipeline now has TWO
 * indexers (capture dates since r12, locations since r16), so this
 * adapter runs them in order with PER-HOOK failure isolation --- a
 * throwing indexer is logged to [DiagLog] and the NEXT one still runs,
 * which is the same "a broken hook must never fail the scan" contract
 * the driver itself upholds around the whole invocation
 * ([MediaScanDriver][io.github.dukeoftoad.gallery.data.media.changes
 * .MediaScanDriver] wraps the composite call in its own catch-all too;
 * this class keeps one indexer's failure from starving its sibling).
 *
 * Defined here, next to its first additional consumer, so this round
 * touches no file inside the scan-pipeline package; when a third
 * indexer lands, the class moves to `data/media/changes` unchanged.
 */
class CompositeScanIndexHook(
    private val hooks: List<ScanIndexHook>,
) : ScanIndexHook {

    override suspend fun onRecordsApplied(
        added: List<CatalogueRecord>,
        changed: List<CatalogueReconciler.ChangedRecord>,
    ) {
        for (hook in hooks) {
            try {
                hook.onRecordsApplied(added, changed)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DiagLog.e(TAG, "scan index hook failed (sibling hooks unaffected)", t)
            }
        }
    }

    private companion object {
        /** Diagnostics tag for the fan-out's failure isolation lines. */
        const val TAG = "CompositeIndexHook"
    }
}
