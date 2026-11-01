package io.github.dukeoftoad.gallery.data.media.revision

/**
 * The result of [MediaRevision.diff]: which CLASS of change happened, and
 * therefore which downstream work must be redone.
 *
 * The audit (`docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`)
 * requires that "a changed revision invalidates only affected AI outputs";
 * this enum IS that mapping --- each entry documents exactly what the
 * incremental indexer and the Ente-derived ML store may keep and what
 * they must throw away.
 */
enum class RevisionDelta {
    /**
     * The revisions are equal. Nothing to redo: the cached metadata
     * snapshot, face embeddings and CLIP embeddings all stay valid, and
     * the incremental scan skips the item entirely.
     */
    UNCHANGED,

    /**
     * Only [MediaRevision.metadataWriteRevision] moved --- the app itself
     * rewrote EXIF/IPTC/XMP (rating, description, GPS strip, ...) and the
     * bytes-level fields are stable.
     *
     * Downstream mapping: re-read the METADATA snapshot (audit item 9:
     * "refresh metadata, increment revision, schedule only the required
     * re-index operations") but do NOT re-run any AI model. Face
     * embeddings and CLIP embeddings are functions of the pixels, which
     * did not change, so they stay valid untouched.
     */
    METADATA_CHANGED,

    /**
     * [MediaRevision.sizeBytes], [MediaRevision.dateModifiedMillis],
     * [MediaRevision.generationToken] or [MediaRevision.contentFingerprint]
     * changed --- the item was replaced, externally edited or re-encoded.
     *
     * Downstream mapping: drop the cached metadata snapshot AND every
     * pixel-derived AI output for this item --- face embeddings (plus the
     * face rows and person clusters derived from them) and CLIP image
     * embeddings (plus semantic-search index entries derived from them)
     * --- then re-extract metadata and re-run the models. This is the
     * only delta that invalidates AI work.
     */
    CONTENT_CHANGED;

    /**
     * Whether the cached metadata snapshot must be re-read. True for every
     * delta except [UNCHANGED] --- even a metadata-only write changes
     * user-visible fields (rating, description) that the details view
     * shows, so the snapshot must be refreshed.
     */
    val requiresMetadataRefresh: Boolean
        get() = this != UNCHANGED

    /**
     * Whether pixel-derived AI outputs (face embeddings, CLIP embeddings
     * and everything computed from them) must be invalidated and
     * recomputed. Only [CONTENT_CHANGED] qualifies --- the audit's
     * "invalidates only affected AI outputs" means a metadata write never
     * burns model inference.
     */
    val invalidatesAiOutputs: Boolean
        get() = this == CONTENT_CHANGED
}
