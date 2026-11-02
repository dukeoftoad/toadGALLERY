package io.github.dukeoftoad.gallery.data.media.dates

import androidx.exifinterface.media.ExifInterface
import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
import io.github.dukeoftoad.gallery.data.media.access.MediaAccess
import io.github.dukeoftoad.gallery.data.media.access.MediaReadHandle
import io.github.dukeoftoad.gallery.data.media.changes.CatalogueReconciler
import io.github.dukeoftoad.gallery.data.media.changes.ScanIndexHook
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability
import io.github.dukeoftoad.gallery.metadata.exif.ExifTags
import io.github.dukeoftoad.gallery.metadata.exif.ParsedExif
import io.github.dukeoftoad.gallery.metadata.format.MetadataFormatMatrix
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The scan driver's capture-date index pass (r12 round, Phase 4 roadmap
 * "the date resolver still needs adopting --- it is a contract landing"):
 * for every row a scan just applied to the catalogue, resolves the item's
 * capture date ONCE --- [DateCandidate]s extracted from the item's original
 * bytes through the [MediaReadHandle] read boundary, ranked by
 * [MediaDateResolver] --- and persists the winner onto the row's
 * capture-date provenance fields via the targeted
 * [MediaCatalogue.updateCaptureDate][io.github.dukeoftoad.gallery
 * .data.catalogue.MediaCatalogue.updateCaptureDate] write.
 *
 * WHY PERSIST INTO THE CATALOGUE (and not re-derive at read time): reading
 * EXIF means reading the item's bytes --- a full-resolution read per
 * timeline row per composition. The catalogue row is the cache; the
 * resolved date is exactly the kind of derived, rebuildable fact it
 * exists to hold. The timeline UI consuming these fields is a LATER
 * round; this class only feeds the catalogue.
 *
 * THE AVAILABILITY DISTINCTION SURVIVES (the Phase 4 audit's rule that
 * "no date" must never be collapsed into "unreadable"): the extraction
 * seam ([CaptureDateCandidateSource]) returns a
 * [CaptureDateEvidence.availability] for every no-candidate outcome ---
 * ABSENT (the format genuinely carries no capture-date block, e.g. PNG),
 * UNSUPPORTED (outside the pinned parser set, e.g. AVIF/DNG/video this
 * round), UNREADABLE (bytes could not be read or the block exists but is
 * corrupt) --- and the JVM tests pin that each state takes the SAME
 * "persist null" decision for its OWN reason, so a later round can widen
 * the persisted provenance without re-deriving the distinction. The
 * catalogue's `null` itself stays deliberately ambiguous (pre-indexer
 * rows read `null` too --- see
 * [CatalogueRecord.resolvedCaptureDateMillis]); the distinction lives in
 * the seam and its tests, not in the row.
 *
 * ELIGIBILITY: every item is offered to the injected
 * [CaptureDateEligibilityGate] before anything is read, and a refused
 * item is SKIPPED entirely --- no bytes read, no catalogue write. This is
 * the audit's vault-leakage gate, kept honest by type: the gate can only
 * hand back an [EligiblePublicMedia] (whose construction path runs
 * through `MediaEligibility.asEligible`), so vault / trashed /
 * permission-revoked media is unrepresentable downstream and the indexer
 * structurally cannot read it. The gate is a seam, not policy: the
 * production wiring supplies the real eligibility+vault lookup, because
 * the catalogue record alone cannot answer "is this row vault-excluded?".
 *
 * CONCURRENCY: no locking here, by design --- the catalogue's
 * transaction-per-call guarantee plus the targeted partial update is the
 * whole story. A UI write racing this indexer on the same row lands in
 * disjoint columns and both wins survive; no other interleaving (lost
 * update, torn row) is reachable through
 * [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue]'s surface.
 *
 * The pure mapping half ([resolveCaptureDateWrite]) and the seam are
 * JVM-tested ([CaptureDateIndexerTest]); the Android-backed extraction
 * impl is production-thin and untested per ADR-0016.
 */
class CaptureDateIndexer(
    private val catalogue: MediaCatalogue,
    private val mediaAccess: MediaAccess,
    private val candidateSource: CaptureDateCandidateSource,
    private val eligibilityGate: CaptureDateEligibilityGate,
) : ScanIndexHook {

    /**
     * The hook the scan driver calls after applying a scan's
     * added/changed rows to the catalogue. Each row is indexed
     * independently: one unreadable item never aborts the batch (the same
     * honest-degradation contract as [MediaReadHandle]) --- and that
     * promise is now STRUCTURAL: the per-row index step runs inside its
     * own catch, so a Room disk error or an EXIF parse blowup in one row
     * is logged (DiagLog, class+message) and skipped while the batch
     * continues. A failed row keeps its existing provenance (its write
     * never ran), and because its revision did not move the next scan
     * classifies it UNCHANGED --- this hook never sees it again. The
     * NULL-provenance healing rescan pass (re-offering rows whose
     * indexing failed) is the named later-round follow-up; this round's
     * contract is only that one row's failure never kills the pass.
     *
     * For a changed row only the NEW state
     * ([CatalogueReconciler.ChangedRecord.new]) is consulted --- the old
     * record travelled along for the driver's invalidation decisions, not
     * for this pass; the write is keyed by the new row's local media key
     * and touches only the provenance fields, so the old revision fields
     * are untouched by construction (a partial UPDATE cannot write them).
     */
    override suspend fun onRecordsApplied(
        added: List<CatalogueRecord>,
        changed: List<CatalogueReconciler.ChangedRecord>,
    ) {
        for (record in added) {
            indexOne(record)
        }
        for (change in changed) {
            // The NEW state's key/uri/mime drive the pass; toRecord() is
            // the sanctioned row->record shape (metadataCacheState EMPTY,
            // write revision 0) and is used ONLY for eligibility gating
            // and the resolver's date_added fallback --- the targeted
            // update writes provenance fields alone, never this record.
            indexOne(change.new.toRecord())
        }
    }

    private suspend fun indexOne(record: CatalogueRecord) {
        // The per-row failure boundary: everything this hook owns for ONE
        // row runs inside the catch below, so a Room disk error or an EXIF
        // parse blowup costs that row's indexing alone --- logged and
        // skipped, never the batch (see onRecordsApplied's KDoc for the
        // NULL-provenance follow-up).
        try {
            // Gate first: an ineligible item is skipped WITHOUT a catalogue
            // write. A tombstoned key (item deleted between scan walk and
            // index pass) also fails to gate/open in the production wiring
            // and, even if it did, updateCaptureDate is a no-op there ---
            // the indexer never resurrects a row.
            val media = eligibilityGate.eligiblePublicMedia(record) ?: return
            val handle = mediaAccess.open(media)
            val evidence = if (handle == null) {
                // Cannot open (vanished row, revoked grant): no bytes, so no
                // candidates --- classified UNREADABLE, same honest
                // degradation as a failed read below. Persisting null for a
                // CHANGED row is deliberate: its revision moved, so any
                // previously persisted date describes bytes that no longer
                // exist and must not survive as truth.
                CaptureDateEvidence(noCandidates, MetadataAvailability.UNREADABLE)
            } else {
                candidateSource.extractCandidates(handle, record.mimeType)
            }
            catalogue.updateCaptureDate(
                record.localMediaKey,
                resolveCaptureDateWrite(record, evidence),
            )
        } catch (ce: CancellationException) {
            // Structured cancellation is not a per-row failure --- let it
            // propagate (the same discipline every catch-all here keeps).
            throw ce
        } catch (t: Throwable) {
            DiagLog.e(
                TAG,
                "capture-date index failed key=${record.localMediaKey} (batch unaffected)",
                t,
            )
        }
    }

    private companion object {
        /** Diagnostics tag for the per-row failure-isolation lines. */
        private const val TAG = "CaptureDateIndexer"

        private val noCandidates: List<DateCandidate> = emptyList()
    }
}

/**
 * The candidate-extraction outcome for one item: resolver-ready
 * [candidates] plus the HONEST [availability] of the capture-date
 * metadata when there are none.
 *
 * Invariant the production extractor upholds and the tests pin:
 * [MetadataAvailability.PRESENT] iff [candidates] is non-empty; every
 * other state arrives with an empty list and a specific reason.
 */
data class CaptureDateEvidence(
    /** Ranked-source candidates for [MediaDateResolver]; may be empty. */
    val candidates: List<DateCandidate>,
    /**
     * Availability of the capture-date metadata overall: PRESENT (block
     * parsed, [candidates] carries the evidence), ABSENT (the format
     * genuinely has no such block), UNSUPPORTED (outside the pinned
     * parser set this round), UNREADABLE (bytes or block could not be
     * read/parsed).
     */
    val availability: MetadataAvailability,
)

/**
 * The extraction seam (ADR-0016): pulls capture-date
 * [DateCandidate]s out of one item's original bytes. The indexer's
 * mapping logic consumes only this, which is what makes it JVM-testable
 * with a fake --- the real bytes/EXIF plumbing stays behind the one
 * Android implementation, [AndroidCaptureDateCandidateSource].
 */
interface CaptureDateCandidateSource {

    /**
     * Extracts candidates from the bytes [handle] exposes for an item of
     * [mimeType]. Never throws for an unreadable item --- unparseable
     * bytes yield [CaptureDateEvidence] with an empty candidate list and
     * [MetadataAvailability.UNREADABLE] (one bad item never aborts a
     * batch).
     */
    suspend fun extractCandidates(
        handle: MediaReadHandle,
        mimeType: String,
    ): CaptureDateEvidence
}

/**
 * The eligibility seam: does this record describe an item this process
 * may still read (public, indexable, permission-covered)? Returns the
 * type-level-proof [EligiblePublicMedia] to open, or `null` for every
 * excluded state --- vault-included, trashed, revoked, unsupported,
 * corrupt. A `null` SKIPS the item entirely (no read, no write); see
 * [CaptureDateIndexer] for why the vault gate is enforced by type.
 */
fun interface CaptureDateEligibilityGate {
    suspend fun eligiblePublicMedia(record: CatalogueRecord): EligiblePublicMedia?
}

/**
 * The PURE mapping half of the index pass: extracted evidence + the
 * catalogue record the pass was launched for -> the provenance write the
 * indexer will make. Exhaustively JVM-tested; the indexer itself adds
 * nothing but I/O around this function.
 *
 * Rules (see [MediaDateResolver] for the resolution rules underneath):
 * - No ranked candidate (empty evidence, or evidence carrying only
 *   [CaptureDateSource.UNKNOWN] filler): return `null` --- persist null.
 *   Deliberately NOT the resolver's MediaStore fallback: persisting
 *   `date_added` (import time on many devices) as a "resolved capture
 *   date" would corrupt the timeline, and the row already carries
 *   [CatalogueRecord.dateAddedMillis] for anything that wants it. The
 *   fallback is still passed into the resolver below (its contract
 *   requires a non-null fallback when it could ever reach that branch,
 *   which the ranked-empty guard makes unreachable here).
 * - Otherwise: [MediaDateResolver.resolve]'s winner is persisted as-is,
 *   INCLUDING its timezone assumption ([ResolvedCaptureDate
 *   .timezoneOffsetMinutes]) and pre-1970 negative instants.
 * - [ResolvedCaptureDate.sourcesDisagree] is deliberately NOT persisted
 *   this round (no column exists for it): the winner still lands, and
 *   flag observability rides a later round --- the resolver returns the
 *   flag, nothing here can lose it that a column addition cannot recover.
 */
internal fun resolveCaptureDateWrite(
    record: CatalogueRecord,
    evidence: CaptureDateEvidence,
): ResolvedCaptureDate? {
    // UNKNOWN is "no provenance", never a rank (MediaDateResolver rule 1):
    // filter it here so an all-UNKNOWN list takes the null path instead of
    // silently degrading to the MediaStore fallback.
    val ranked = evidence.candidates.filter { it.source != CaptureDateSource.UNKNOWN }
    if (ranked.isEmpty()) return null
    return MediaDateResolver.resolve(
        candidates = ranked,
        fallbackDateAddedMillis = record.dateAddedMillis,
    )
}

/**
 * The production extraction impl (ADR-0016-thin, deliberately untested):
 * reads the item's original bytes once through [MediaReadHandle], copies
 * the EXIF datetime tag strings out with `androidx.exifinterface`, and
 * hands them to the pure, JVM-tested [ParsedExif] --- the SAME assembly
 * the r10 reader layer uses --- for candidate extraction and
 * availability. Every decision lives in [ParsedExif],
 * [ExifMath][io.github.dukeoftoad.gallery.metadata.exif.ExifMath] and
 * [MetadataFormatMatrix]; this class only moves bytes and strings.
 *
 * Why it does NOT go through the existing [ExifReader][io.github
 * .dukeoftoad.gallery.metadata.exif.ExifReader] interface: that seam's
 * [MediaMetadataSnapshot][io.github.dukeoftoad.gallery.metadata
 * .MediaMetadataSnapshot] predates the resolver contract and flattens the
 * datetime tags away (it carries neither the candidate list nor the
 * per-tag zone offsets), so going through it would silently DROP the
 * EXIF_DIGITIZED candidate and the OffsetTime* evidence. The tag-copy
 * loop below duplicates [AndroidExifReader][io.github.dukeoftoad.gallery
 * .metadata.exif.AndroidExifReader]'s eight lines rather than widening
 * that contract behind its owner's back; when the snapshot grows
 * candidates, this class can shrink to a delegation.
 *
 * Availability mapping (the distinction the audit demands):
 * - `handle.readBytes` fails -> UNREADABLE (no bytes).
 * - The format matrix declares the MIME's EXIF column non-PRESENT
 *   (PNG/GIF ABSENT, AVIF/DNG/video/unknown UNSUPPORTED) -> that exact
 *   state, WITHOUT reading the block --- a capability statement, not a
 *   claim about the file.
 * - EXIF-capable but the block cannot be opened -> UNREADABLE; opened
 *   but empty -> ABSENT; opened and parsed -> PRESENT when any datetime
 *   tag yielded a candidate, otherwise the block's own parsed state
 *   (UNREADABLE when DateTimeOriginal exists but will not parse, ABSENT
 *   when there is no datetime tag at all).
 */
class AndroidCaptureDateCandidateSource : CaptureDateCandidateSource {

    override suspend fun extractCandidates(
        handle: MediaReadHandle,
        mimeType: String,
    ): CaptureDateEvidence {
        val bytes = handle.readBytes(MediaReadHandle.DEFAULT_MAX_BYTES)
            ?: return CaptureDateEvidence(emptyList(), MetadataAvailability.UNREADABLE)

        // Capability first: for a format the pinned parser set does not
        // read, the bytes are never offered to the parser at all --- the
        // honest UNSUPPORTED/ABSENT statement must not depend on whether
        // a parser happens to find something anyway.
        val capability = MetadataFormatMatrix.capabilitiesFor(mimeType)
        if (capability.exif != MetadataAvailability.PRESENT) {
            return CaptureDateEvidence(emptyList(), capability.exif)
        }

        val exif: ExifInterface = try {
            ExifInterface(ByteArrayInputStream(bytes))
        } catch (e: IOException) {
            // Unsupported/corrupt stream: the block exists in capability
            // terms but cannot be read --- UNREADABLE, never ABSENT.
            return CaptureDateEvidence(emptyList(), MetadataAvailability.UNREADABLE)
        }
        val tags = HashMap<String, String>()
        for (tag in ExifTags.ALL) {
            exif.getAttribute(tag)?.let { tags[tag] = it }
        }
        if (tags.isEmpty()) {
            // Parsed but empty: no EXIF block at all.
            return CaptureDateEvidence(emptyList(), MetadataAvailability.ABSENT)
        }
        val parsed = ParsedExif(tags)
        val candidates = parsed.captureDateCandidates()
        if (candidates.isNotEmpty()) {
            return CaptureDateEvidence(candidates, MetadataAvailability.PRESENT)
        }
        // No datetime tag parsed into a candidate: take the block's own
        // honest state from the pure assembly (UNREADABLE when a
        // DateTimeOriginal exists but will not parse, ABSENT when there
        // was no datetime tag at all).
        val blockState = parsed.toSnapshot(bytes.size.toLong(), mimeType)
            .captureDates
            .exif
        return CaptureDateEvidence(emptyList(), blockState)
    }
}

/**
 * The production [CaptureDateEligibilityGate]: re-derives the item's
 * [MediaIdentity] from the catalogue record's stable local media key and
 * admits it as [MediaEligibility.PUBLIC_INDEXABLE] --- or `null` for every
 * record that cannot form a well-formed, URI-carrying identity.
 *
 * Why the gate is THIS thin: the classification work already happened
 * upstream of the catalogue by construction ---
 *
 * - vault items are not MediaStore rows and can never appear in a scan
 *   walk (the vault lives in the app's private storage; the type-level
 *   boundary [MediaEligibility.asEligible] stays the second lock),
 * - trashed rows are excluded AT THE WALK since the r12 round
 *   (`IS_TRASHED = 0` in [AndroidMediaScanRowSource]; below R the column
 *   does not exist and neither did the platform trash),
 * - and a grant revoked between the walk and the read is answered by
 *   [MediaAccess.open]'s own permission gate, one step later with the same
 *   honest UNREADABLE outcome.
 *
 * A malformed key or blank canonical Uri therefore means the record itself
 * is not trustworthy access material, and the item is skipped WITHOUT a
 * catalogue write (nothing about its date can be claimed either way).
 */
class CatalogueRecordEligibilityGate : CaptureDateEligibilityGate {

    override suspend fun eligiblePublicMedia(record: CatalogueRecord): EligiblePublicMedia? {
        val identity = MediaIdentity.fromKeyString(record.localMediaKey) ?: return null
        if (record.canonicalUri.isBlank()) return null
        return MediaEligibility.PUBLIC_INDEXABLE.asEligible(
            identity.copy(canonicalUri = record.canonicalUri),
        )
    }
}
