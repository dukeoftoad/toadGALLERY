package io.github.dukeoftoad.gallery.data.media.dates

import kotlin.math.abs

/**
 * A single raw capture-date observation from one source, already
 * converted to epoch millis by the extractor --- a naive EXIF/XMP string
 * arrives here already interpreted with the EXIF offset when present or
 * the assumed device zone otherwise, and the zone used is carried in
 * [timezoneOffsetMinutes] so the assumption stays auditable.
 *
 * The resolver treats candidates as unordered evidence: list order never
 * affects precedence, only same-source ties (see [MediaDateResolver]).
 */
data class DateCandidate(
    /** The tag this observation came from; drives precedence. */
    val source: CaptureDateSource,
    /**
     * The interpreted instant in epoch millis. ANY Long value is a usable
     * instant: negative values are legitimate pre-1970 capture dates
     * (scanned film archives, historical photos), and `0` is the epoch
     * itself, not a sentinel --- parse failures never reach the resolver
     * as a value (the extractors return no candidate for them).
     */
    val epochMillis: Long,
    /** Zone used to interpret a naive tag, minutes east of UTC; `null` for epoch-based sources. */
    val timezoneOffsetMinutes: Int? = null,
    /** Sub-second part, 0..999; `0` when the source had no subsecond tag. */
    val subsecondMillis: Int = 0,
)

/**
 * The outcome of [MediaDateResolver.resolve]: the winning instant, its
 * provenance, and whether the losing sources corroborated it.
 *
 * [sourcesDisagree] is the audit's confidence signal: a photo whose EXIF
 * and MediaStore dates sit minutes apart was almost certainly re-saved or
 * imported badly, and clustering passes may want to distrust or surface
 * such items instead of silently ordering them.
 */
data class ResolvedCaptureDate(
    /** The winning instant in epoch millis. */
    val epochMillis: Long,
    /** Which source won; see [CaptureDateSource] for the ranking. */
    val source: CaptureDateSource,
    /** Zone assumption of the winning candidate; `null` for epoch-based sources. */
    val timezoneOffsetMinutes: Int? = null,
    /** Sub-second part of the winning candidate. */
    val subsecondMillis: Int = 0,
    /** Whether any lower-precedence source sat more than [MediaDateResolver.NEAR_EQUAL_TOLERANCE_MS] away. */
    val sourcesDisagree: Boolean = false,
)

/**
 * Pure, deterministic resolver for the capture date (Phase 4 contract,
 * item 3: "documented precedence/fallback resolver for EXIF/XMP/
 * QuickTime/MediaStore").
 *
 * The rules, deliberately simple enough to audit:
 * 1. [CaptureDateSource.UNKNOWN] candidates are skipped --- never a
 *    crash, and never a silent winner either: an UNKNOWN candidate says
 *    "no provenance", which is the fallback's job, not a rank. Every
 *    epoch value on a ranked source is usable, INCLUDING negative ones:
 *    a negative [DateCandidate.epochMillis] is a legitimate pre-1970
 *    capture date (scanned film archives, historical photos), not
 *    malformed input --- discarding it silently drops every
 *    pre-1970 item out of the timeline (audit finding F). `0` is the
 *    epoch itself and equally valid; the contract defines no 0-sentinel
 *    here (EXIF's unparseable "  :  :  " strings never become
 *    candidates --- the extractors simply emit nothing for them).
 * 2. The winner is the usable candidate with the best (lowest) rank;
 *    same-rank duplicates keep the FIRST in input order, so callers that
 *    list candidates in extraction order get stable results.
 * 3. Any other source more than [NEAR_EQUAL_TOLERANCE_MS] away from the
 *    winner sets [ResolvedCaptureDate.sourcesDisagree] --- the winner
 *    still resolves normally; the flag is information, not an error.
 *    The gap is measured with overflow-safe arithmetic, because with
 *    pre-1970 instants in play the naive `abs(a - b)` can wrap for
 *    adversarially extreme values (audit finding F's same class of bug
 *    as the decode planner's).
 * 4. No usable candidates: a non-null [fallbackDateAddedMillis] resolves
 *    as a MEDIASTORE date (it IS a MediaStore value), and `null` fallback
 *    plus no candidates is a caller bug --- `resolve` throws
 *    [IllegalArgumentException], because every caller of this contract
 *    has at least the MediaStore `date_added` row in hand and silently
 *    returning a fake "0" instant would corrupt the timeline.
 */
object MediaDateResolver {
    /**
     * Two sources closer than this (in millis) count as agreeing. EXIF,
     * XMP and container timestamps are second-granular and routinely sit
     * a second or two apart around the true instant (independent rounding
     * at both ends, muxer encode-vs-capture skew); 2000 ms absorbs that
     * noise without masking genuinely conflicting dates.
     */
    const val NEAR_EQUAL_TOLERANCE_MS: Long = 2_000L

    /**
     * Picks the capture date from [candidates] by precedence. See the
     * object KDoc for the full rule set; the tolerance comparison runs on
     * whole-second epoch values only, because container and MediaStore
     * sources carry no subsecond precision and a subsecond delta can
     * never be told apart from truncation there.
     */
    fun resolve(
        candidates: List<DateCandidate>,
        fallbackDateAddedMillis: Long?,
    ): ResolvedCaptureDate {
        val usable = candidates.filter {
            // Pre-1970 instants are NEGATIVE epoch millis and perfectly
            // valid (audit finding F); the only unusable candidate is a
            // ranked-source-less UNKNOWN. See the object KDoc, rule 1.
            it.source != CaptureDateSource.UNKNOWN
        }
        if (usable.isEmpty()) {
            val fallback = requireNotNull(fallbackDateAddedMillis) {
                "no usable capture-date candidates and no date_added fallback: " +
                    "every resolver call site has the MediaStore row in hand"
            }
            return ResolvedCaptureDate(
                epochMillis = fallback,
                source = CaptureDateSource.MEDIASTORE,
            )
        }
        // minByOrNull returns the FIRST minimal element, which is exactly
        // the stable same-rank tie-break promised in the object KDoc; the
        // non-null assertion is backed by the isEmpty early return above.
        val winner = usable.minByOrNull { it.source.ordinal }!!
        val sourcesDisagree = usable.any { candidate ->
            candidate.source != winner.source &&
                gapExceedsTolerance(candidate.epochMillis, winner.epochMillis)
        }
        return ResolvedCaptureDate(
            epochMillis = winner.epochMillis,
            source = winner.source,
            timezoneOffsetMinutes = winner.timezoneOffsetMinutes,
            subsecondMillis = winner.subsecondMillis,
            sourcesDisagree = sourcesDisagree,
        )
    }

    /**
     * Whether two epoch-millis instants sit more than
     * [NEAR_EQUAL_TOLERANCE_MS] apart, WITHOUT the `abs(a - b)` wrap:
     * subtraction overflows when the operands have opposite signs and
     * their true difference leaves the Long range (and `abs` alone is
     * broken for `Long.MIN_VALUE`), which negative pre-1970 instants make
     * reachable in principle. The wrap is detected by the classic sign
     * test (`a` and `b` differ in sign, and so do `a` and the wrapped
     * delta); a wrapped difference is by definition larger than any
     * tolerance, so it flags disagreement.
     */
    private fun gapExceedsTolerance(a: Long, b: Long): Boolean {
        val delta = a - b
        // `abs` of Long.MIN_VALUE is Long.MIN_VALUE (negative), which would
        // defeat the comparison below; that delta --- e.g. MIN_VALUE - 0 or
        // -1 - MAX_VALUE --- is always far beyond the tolerance.
        if (delta == Long.MIN_VALUE) return true
        val wrapped = (a xor b) < 0 && (a xor delta) < 0
        return wrapped || abs(delta) > NEAR_EQUAL_TOLERANCE_MS
    }
}
