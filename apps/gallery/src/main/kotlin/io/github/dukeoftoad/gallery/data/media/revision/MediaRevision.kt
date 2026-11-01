package io.github.dukeoftoad.gallery.data.media.revision

/**
 * The revision token that detects replacement, edit or metadata change for
 * one media item (Phase 4 contract,
 * `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`: "revision
 * token: volume generation + row id + size + dateModified + metadata
 * revision; content hash only where justified").
 *
 * The revision is what makes incremental indexing honest: the catalogue
 * persists the last observed revision next to every derived artifact and
 * compares it against a freshly observed one via [diff]. The resulting
 * [RevisionDelta] tells downstream stages exactly how much work to redo
 * --- an app-side metadata touch must NOT re-run face or CLIP models,
 * while a content change must invalidate every pixel-derived output.
 *
 * Field semantics:
 * - [sizeBytes] and [dateModifiedMillis] come straight from the
 *   MediaStore row and catch the overwhelming majority of real edits ---
 *   any rewrite of the bytes moves at least one of them.
 * - [generationToken] is the MediaStore per-volume generation value,
 *   which only exists on API 30+ --- hence nullable. `null` means "not
 *   observable on this device", never "changed".
 * - [metadataWriteRevision] counts THIS APP's own EXIF/IPTC/XMP writes.
 *   MediaStore cannot see in-place metadata rewrites that preserve size
 *   and mtime, so the writer bumps this counter itself (audit item 9).
 * - [contentFingerprint] is a lightweight content hash, computed only
 *   where justified (formats known to rewrite bytes in place without
 *   moving mtime). It stays `null` on the cheap path so full-library
 *   scans do not hash every file.
 */
data class MediaRevision(
    /** File size in bytes from the MediaStore row; moves on any rewrite. */
    val sizeBytes: Long,
    /** MediaStore `date_modified` in epoch millis; moves on any rewrite. */
    val dateModifiedMillis: Long,
    /** MediaStore generation token (API 30+); `null` when unsupported. */
    val generationToken: String? = null,
    /** App-managed counter of in-app EXIF/IPTC/XMP writes; starts at 0. */
    val metadataWriteRevision: Int = 0,
    /** Optional lightweight content hash; `null` unless justified. */
    val contentFingerprint: String? = null,
) {
    /**
     * Classifies how [other] differs from this revision.
     *
     * The classification is deliberately conservative: when in doubt it
     * reports [RevisionDelta.CONTENT_CHANGED], because re-running metadata
     * extraction and AI on an unchanged photo only wastes battery, while
     * trusting a stale face embedding silently misidentifies people.
     * Concretely:
     * - [sizeBytes], [dateModifiedMillis], or two PRESENT
     *   [generationToken]s / [contentFingerprint]s that disagree force
     *   CONTENT_CHANGED.
     * - A token or fingerprint that merely APPEARS or DISAPPEARS (an OS
     *   upgrade exposing generations, a lazily backfilled hash) is new
     *   information about the same bytes, not evidence of a change ---
     *   treating it as one would invalidate every AI output on every
     *   device upgrade.
     * - Only a [metadataWriteRevision] delta with all content fields
     *   stable yields METADATA_CHANGED; a content change always dominates
     *   a concurrent metadata bump.
     */
    fun diff(other: MediaRevision): RevisionDelta {
        val contentChanged =
            sizeBytes != other.sizeBytes ||
                dateModifiedMillis != other.dateModifiedMillis ||
                differsWhenBothPresent(generationToken, other.generationToken) ||
                differsWhenBothPresent(contentFingerprint, other.contentFingerprint)
        if (!contentChanged) {
            return if (metadataWriteRevision == other.metadataWriteRevision) {
                RevisionDelta.UNCHANGED
            } else {
                RevisionDelta.METADATA_CHANGED
            }
        }
        return RevisionDelta.CONTENT_CHANGED
    }

    /**
     * `true` only when both sides carry a value AND the values disagree.
     * Optional revision fields mean "not observed on this side"; a value
     * appearing or disappearing is a change in OBSERVABILITY, not in
     * content. See [diff] for why that distinction matters.
     */
    private fun differsWhenBothPresent(a: String?, b: String?): Boolean =
        a != null && b != null && a != b
}
