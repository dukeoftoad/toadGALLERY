package com.toadgallery.gallery.metadata.format

import com.toadgallery.gallery.metadata.MetadataAvailability

/**
 * One row of the parser capability matrix (Phase 4 decision #4:
 * "Select and pin the metadata parser set ... maintain a capability matrix
 * rather than promising every format",
 * `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`).
 *
 * This is a statement about toadGALLERY's PINNED PARSER SET for a MIME
 * type, not a claim about any specific file's bytes: `exif = PRESENT`
 * means "when a file of this type carries EXIF, our reader parses it",
 * while the per-file truth (a JPEG with no GPS block) lives in the
 * snapshot's [MetadataValue] fields. Keeping the two layers apart is what
 * lets the UI say "this photo has no GPS" (file truth) and "AVIF EXIF is
 * not supported in this build" (capability truth) without conflating
 * them.
 *
 * Fields:
 *  - [exif]/[containerMetadata]/[iptc]/[xmp]: per-block availability as
 *    declared for this round's parsers (PRESENT only where a parser
 *    actually reads the block; ABSENT where the format has no standard
 *    carrier for it; UNSUPPORTED where it may carry one but we do not
 *    parse it yet);
 *  - [editable]: whether the pinned metadata WRITER supports this format
 *    this round (audit item 9's write path only lands for formats this
 *    flag covers);
 *  - [notes]: the honest why, surfaced by the Details view and the docs.
 */
data class FormatCapability(
    val mimeType: String,
    val exif: MetadataAvailability,
    val containerMetadata: MetadataAvailability,
    val iptc: MetadataAvailability,
    val xmp: MetadataAvailability,
    val editable: Boolean,
    val notes: String? = null,
)
