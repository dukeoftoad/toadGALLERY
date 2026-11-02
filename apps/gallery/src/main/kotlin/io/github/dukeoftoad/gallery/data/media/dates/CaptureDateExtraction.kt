package io.github.dukeoftoad.gallery.data.media.dates

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.metadata.MetadataAvailability

/**
 * The capture-date extraction contracts (r12 round; file renamed from
 * CaptureDateIndexer.kt by the r20 round, which replaced the standalone
 * indexer with the combined single-read pass --- see
 * [io.github.dukeoftoad.gallery.data.media.changes.CombinedExifIndexer],
 * the successor that consumes these contracts unchanged). What remains in
 * this file is exactly the still-load-bearing half of the r12 round: the
 * evidence type, the eligibility gate, the pure write mapping, and the
 * production eligibility gate. The per-dimension candidate-source seam and
 * its Android implementation were REMOVED with the indexer (the combined
 * source is the one extraction seam now); every availability-state
 * contract they pinned lives on, JVM-tested, through the combined pass.
 */

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
 * The eligibility seam: does this record describe an item this process
 * may still read (public, indexable, permission-covered)? Returns the
 * type-level-proof [EligiblePublicMedia] to open, or `null` for every
 * excluded state --- vault-included, trashed, revoked, unsupported,
 * corrupt. A `null` SKIPS the item entirely (no read, no write); see
 * [CombinedExifIndexer][io.github.dukeoftoad.gallery.data.media.changes
 * .CombinedExifIndexer] for why the vault gate is enforced by type.
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
