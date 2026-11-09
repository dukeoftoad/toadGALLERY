package com.toadgallery.gallery.data.media.changes

import androidx.exifinterface.media.ExifInterface
import com.toadgallery.gallery.data.catalogue.CatalogueRecord
import com.toadgallery.gallery.data.catalogue.MediaCatalogue
import com.toadgallery.gallery.data.diag.DiagLog
import com.toadgallery.gallery.data.media.access.MediaAccess
import com.toadgallery.gallery.data.media.access.MediaReadHandle
import com.toadgallery.gallery.data.media.dates.CaptureDateEligibilityGate
import com.toadgallery.gallery.data.media.dates.CaptureDateEvidence
import com.toadgallery.gallery.data.media.dates.resolveCaptureDateWrite
import com.toadgallery.gallery.data.media.location.GpsEvidence
import com.toadgallery.gallery.data.media.location.ItemLocationWriter
import com.toadgallery.gallery.data.media.location.LocationReadGate
import com.toadgallery.gallery.data.media.location.resolveLocationWrite
import com.toadgallery.gallery.metadata.MetadataAvailability
import com.toadgallery.gallery.metadata.exif.ExifTags
import com.toadgallery.gallery.metadata.exif.ParsedExif
import com.toadgallery.gallery.metadata.format.MetadataFormatMatrix
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * One row's COMBINED EXIF extraction outcome (r20): the capture-date and
 * GPS evidence produced from ONE read of the item's bytes. `null` [gps]
 * means "not consulted this pass" --- the consent gate refused, so the
 * location write is decided from consent + capability alone (the same
 * meaning a `null` evidence carried in the r16 location indexer).
 */
data class CombinedExifEvidence(
    /** The capture-date evidence (always consulted; dates are never redacted). */
    val date: CaptureDateEvidence,
    /** The GPS evidence, or `null` when the pass may not read location. */
    val gps: GpsEvidence?,
)

/**
 * The combined extraction seam (ADR-0016): pulls BOTH evidence kinds out of
 * ONE [MediaReadHandle] read. JVM tests fake this; the one production
 * implementation is [AndroidCombinedExifSource] below.
 */
fun interface CombinedExifSource {

    /**
     * Extracts both evidence kinds from the bytes [handle] exposes for an
     * item of [mimeType]. Never throws for an unreadable item --- the same
     * honest-degradation contract the two per-dimension sources it replaces
     * kept, with each side preserving its own historical availability
     * mapping (see [AndroidCombinedExifSource] for the one deliberate
     * divergence note).
     */
    suspend fun extract(handle: MediaReadHandle, mimeType: String): CombinedExifEvidence
}

/**
 * The scan driver's COMBINED index pass (r20 round; the r19-named
 * "combined single-read EXIF extraction" follow-up, closed): for every row
 * a scan just applied to the catalogue, the item's bytes are read through
 * the [MediaAccess] boundary **once**, BOTH provenance dimensions are
 * extracted from that single read, and the pass then makes its three
 * targeted writes --- capture date, location, and LAST the healing stamp
 * ([ProvenanceHealingStore.markProvenanceIndexed]) whose presence is what
 * the NULL-provenance healing pass selects on.
 *
 * ## Why a combined pass (what it replaces)
 *
 * The r12 capture-date indexer and the r16 location indexer each opened
 * the item and read its bytes independently: every added/changed row paid
 * TWO content-resolver streams and TWO full EXIF parses. This class is
 * their successor --- the two per-dimension indexers are REMOVED with it
 * (no dead code), while everything they owned that is still load-bearing
 * survives untouched: the evidence types
 * ([CaptureDateEvidence] / [GpsEvidence], still the per-dimension
 * contracts the extraction assembly documents), the pure write-mapping
 * functions ([resolveCaptureDateWrite] / [resolveLocationWrite] --- the
 * pass adds no decision of its own and calls exactly these), the
 * eligibility gate ([CaptureDateEligibilityGate], dimension-agnostic by
 * construction and reused verbatim), and the consent gate
 * ([LocationReadGate], the r14 ACCESS_MEDIA_LOCATION machinery).
 *
 * ## Order of writes (the healing stamp's contract)
 *
 * 1. [MediaCatalogue.updateCaptureDate] --- the date write (may be null;
 *    a CHANGED row's old date describes bytes that no longer exist).
 * 2. [ItemLocationWriter.updateLocation] --- the location write (may be
 *    nulls; same wipe-on-change doctrine).
 * 3. [ProvenanceHealingStore.markProvenanceIndexed] --- ONLY after both
 *    writes returned: a row that fails anywhere stays unstamped, i.e.
 *    selectable by the healing pass, until it succeeds once. The stamp is
 *    the pass's success bit, not a progress marker.
 *
 * ## Consent, eligibility, failure isolation
 *
 * Every rule is the two predecessors' rule, unchanged: the eligibility
 * gate runs FIRST (vault / trashed / malformed rows are skipped without a
 * read --- the type-level proof keeps vault media unrepresentable
 * downstream); the consent gate is consulted ONCE per batch (a scan pass is
 * seconds long; mid-batch consent flips are not a real state) and an
 * ungranted pass reads the bytes anyway (the DATE half needs them ---
 * dates are never redacted) while the location write is decided from
 * consent + format capability alone, exactly the r16 behavior; and the
 * per-row step runs inside its own catch (r19's structural boundary), so
 * one row's Room/EXIF blowup costs that row alone and the stamp never
 * lands for it.
 *
 * Changed rows index their NEW state
 * ([CatalogueReconciler.ChangedRecord.new]) only, exactly like both
 * predecessors; the writes are keyed by the new row's key and touch only
 * their own columns.
 *
 * The pass is JVM-tested end to end with fakes ([CombinedExifIndexerTest],
 * the ported r12/r16 matrices plus the new single-read and stamp pins);
 * the production extraction is ADR-0016-thin and untested.
 */
class CombinedExifIndexer(
    private val catalogue: MediaCatalogue,
    private val locationWriter: ItemLocationWriter,
    private val healingStore: ProvenanceHealingStore,
    private val mediaAccess: MediaAccess,
    private val combinedSource: CombinedExifSource,
    private val eligibilityGate: CaptureDateEligibilityGate,
    private val locationReadGate: LocationReadGate,
) : ScanIndexHook {

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
            // row->record shape, used ONLY for gating and the evidence
            // read; the targeted writes touch their own columns, never
            // this record's other fields).
            indexOne(change.new.toRecord(), gpsReadable)
        }
    }

    private suspend fun indexOne(record: CatalogueRecord, gpsReadable: Boolean) {
        // The per-row failure boundary (r19, structural): everything this
        // pass owns for ONE row runs inside the catch, so a failure costs
        // that row's indexing alone and the stamp never lands --- the row
        // stays selectable for the healing pass (the whole point of the
        // stamp ordering above).
        try {
            // Gate first: an ineligible item is skipped WITHOUT any write
            // (vault gate by type; a tombstoned key additionally makes all
            // three targeted UPDATEs no-ops --- this pass never resurrects).
            val media = eligibilityGate.eligiblePublicMedia(record) ?: return
            val exifCapable =
                MetadataFormatMatrix.capabilitiesFor(record.mimeType).exif == MetadataAvailability.PRESENT

            // ONE open, ONE read. The date half always needs the bytes
            // (dates are never redacted); the GPS half of the evidence is
            // consumed only while the consent gate reads green.
            val handle = mediaAccess.open(media)
            val evidence = if (handle == null) null else combinedSource.extract(handle, record.mimeType)

            // Write 1: the capture date. A null handle maps to the honest
            // UNREADABLE evidence (vanished row / revoked grant) --- for a
            // CHANGED row that deliberately wipes the stale date.
            catalogue.updateCaptureDate(
                record.localMediaKey,
                resolveCaptureDateWrite(
                    record,
                    evidence?.date ?: CaptureDateEvidence(emptyList(), MetadataAvailability.UNREADABLE),
                ),
            )

            // Write 2: the location. Ungranted consent decides from consent
            // + capability alone (no GPS evidence consulted, the r16
            // rule); granted consent consumes the evidence (null handle =
            // "could not read", which honestly resolves to no location).
            val resolved = if (!gpsReadable) {
                resolveLocationWrite(gpsReadable = false, exifCapable = exifCapable, evidence = null)
            } else {
                resolveLocationWrite(
                    gpsReadable = true,
                    exifCapable = exifCapable,
                    evidence = evidence?.gps,
                )
            }
            locationWriter.updateLocation(
                localMediaKey = record.localMediaKey,
                latitude = resolved.latitude,
                longitude = resolved.longitude,
                sourceName = resolved.source?.name,
            )

            // Write 3, LAST: the healing stamp. Both writes above returned,
            // so this row is fully indexed as of now.
            healingStore.markProvenanceIndexed(record.localMediaKey, System.currentTimeMillis())
        } catch (ce: CancellationException) {
            // Structured cancellation is not a per-row failure --- let it
            // propagate (the same discipline every catch-all here keeps).
            throw ce
        } catch (t: Throwable) {
            DiagLog.e(
                TAG,
                "combined EXIF index failed key=${record.localMediaKey} (batch unaffected)",
                t,
            )
        }
    }

    private companion object {
        /** Diagnostics tag for the per-row failure-isolation lines. */
        private const val TAG = "CombinedExifIndexer"
    }
}

/**
 * The production [CombinedExifSource] (ADR-0016-thin, deliberately
 * untested): reads the item's original bytes ONCE through the read
 * boundary, parses the EXIF block ONCE, and feeds the SAME tag map to both
 * pure assemblies --- [ParsedExif] (the dates side, via
 * [ParsedExif.captureDateCandidates] and the snapshot's block-state) and
 * the snapshot's GPS half ([ParsedExif.toSnapshot]'s `gps`, the exact
 * assembly `AndroidExifReader` runs for the r14 Details path). Each side's
 * availability mapping is its predecessor's mapping, stated per branch
 * below; the ONE deliberate unification is the capability-first order
 * (see the ORDER NOTE).
 *
 * ORDER NOTE (this pass adopts the r16 GPS source's order, not the r12
 * date source's): the format-capability check runs BEFORE the bytes are
 * read. The r12 source read the bytes first and discovered afterwards
 * that the format cannot carry EXIF --- pure waste for every PNG/GIF/
 * video row, paid twice in the combined world. The date side's OUTCOME is
 * unchanged (a non-PRESENT capability yields that exact availability,
 * which is what the r12 source returned after its wasted read); only the
 * read is skipped.
 *
 * Branch map (each row states which predecessor behavior it preserves):
 * - capability not PRESENT -> date evidence = that availability with no
 *   candidates, GPS evidence = the same (the r16 capability branch; the
 *   r12 branch returned the same state after reading) --- and NO bytes
 *   are read at all.
 * - read fails (null handle payload) -> both sides UNREADABLE (both
 *   predecessors' failed-read branch).
 * - EXIF block cannot be opened (IOException constructing the parser) ->
 *   date UNREADABLE (the r12 branch) but GPS ABSENT (the r16 branch's
 *   `reader null -> ABSENT`, whose KDoc explains the conflation and why
 *   ABSENT dominates within an EXIF-capable format). The two sides keep
 *   their historical disagreement rather than silently changing a
 *   persisted contract.
 * - parsed but EMPTY tag map -> both sides ABSENT (both predecessors).
 * - parsed -> date candidates or the block's own state (the r12 tail);
 *   GPS from the snapshot's `gps` half (the r16 tail). Invariant kept:
 *   PRESENT iff the evidence carries a value/list.
 */
class AndroidCombinedExifSource : CombinedExifSource {

    override suspend fun extract(handle: MediaReadHandle, mimeType: String): CombinedExifEvidence {
        // Capability first (the ORDER NOTE): for a format the pinned
        // parser set does not read, the bytes are never buffered at all.
        val capability = MetadataFormatMatrix.capabilitiesFor(mimeType)
        if (capability.exif != MetadataAvailability.PRESENT) {
            return CombinedExifEvidence(
                date = CaptureDateEvidence(emptyList(), capability.exif),
                gps = GpsEvidence(null, capability.exif),
            )
        }

        // THE one read this whole class exists for: both dimensions parse
        // the same in-memory bytes afterwards.
        val bytes = handle.readBytes(MediaReadHandle.DEFAULT_MAX_BYTES)
            ?: return CombinedExifEvidence(
                date = CaptureDateEvidence(emptyList(), MetadataAvailability.UNREADABLE),
                gps = GpsEvidence(null, MetadataAvailability.UNREADABLE),
            )

        // ONE parse, ONE tag copy --- the exact AndroidExifReader
        // assembly (ExifInterface over the byte array, ExifTags.ALL
        // copied verbatim), shared by both halves below.
        val tags = HashMap<String, String>()
        try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            for (tag in ExifTags.ALL) {
                exif.getAttribute(tag)?.let { tags[tag] = it }
            }
        } catch (e: IOException) {
            // The block cannot be opened. Each side keeps its historical
            // mapping --- see the class KDoc's branch map.
            return CombinedExifEvidence(
                date = CaptureDateEvidence(emptyList(), MetadataAvailability.UNREADABLE),
                gps = GpsEvidence(null, MetadataAvailability.ABSENT),
            )
        }
        if (tags.isEmpty()) {
            // Parsed but empty: no EXIF block at all.
            return CombinedExifEvidence(
                date = CaptureDateEvidence(emptyList(), MetadataAvailability.ABSENT),
                gps = GpsEvidence(null, MetadataAvailability.ABSENT),
            )
        }

        val parsed = ParsedExif(tags)
        val snapshot = parsed.toSnapshot(bytes.size.toLong(), mimeType)

        // The date half: candidates when any datetime tag parsed, else the
        // block's own honest state (the r12 source's tail, verbatim).
        val dateEvidence = parsed.captureDateCandidates().let { candidates ->
            if (candidates.isNotEmpty()) {
                CaptureDateEvidence(candidates, MetadataAvailability.PRESENT)
            } else {
                CaptureDateEvidence(emptyList(), snapshot.captureDates.exif)
            }
        }

        // The GPS half: the snapshot's own gps availability + value (the
        // r16 source's tail, verbatim).
        val gps = snapshot.gps
        val gpsEvidence = if (gps.availability == MetadataAvailability.PRESENT && gps.value != null) {
            GpsEvidence(gps.value, MetadataAvailability.PRESENT)
        } else {
            GpsEvidence(null, gps.availability)
        }

        return CombinedExifEvidence(date = dateEvidence, gps = gpsEvidence)
    }
}
