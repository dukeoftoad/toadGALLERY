package io.github.dukeoftoad.gallery.data.catalogue

/**
 * One row of the Phase 4 media catalogue --- the persistent CACHE of a media
 * item's identity and revision state
 * (`docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`, contract item
 * "Room catalogue": persist the media identity, revision, metadata cache
 * state and scan checkpoints).
 *
 * Scope guard, straight from the audit: this row is the CATALOGUE, never the
 * index. Face embeddings, CLIP vectors, cluster assignments and every other
 * AI output live in the engine-owned ML stores (the Ente-derived native ML
 * DB / vector store) keyed by [localMediaKey] --- duplicating them here
 * would create a second source of truth that drifts out of sync with the
 * engines and silently doubles the storage bill. If a field's only consumer
 * is an AI engine, it does not belong in this class.
 *
 * Revision semantics ([sizeBytes] / [dateModifiedMillis] /
 * [generationToken] vs [metadataWriteRevision]): the first three are
 * MEDIASTORE-observed revisions --- a diff in any of them means the provider
 * row changed and downstream AI outputs must be invalidated and recomputed.
 * [metadataWriteRevision] is the IN-APP counterpart: it is bumped by this
 * app's own EXIF/IPTC/XMP writes (audit item "Metadata write invalidation")
 * and deliberately does NOT participate in scan reconciliation, otherwise
 * every in-app edit would masquerade as an external edit and trigger a full
 * re-index of bytes that never changed.
 */
data class CatalogueRecord(
    /**
     * The stable local media key --- [io.github.dukeoftoad.gallery.data.media
     * .identity.MediaIdentity.toKeyString]. Volume name + MediaStore `_id`
     * (+ optional page id); never a path or display name, which rename
     * freely. This is the join key the engine-owned ML stores map their rows
     * onto.
     */
    val localMediaKey: String,
    /** The resolved content `Uri` string; the ACCESS handle, not identity. */
    val canonicalUri: String,
    /** MIME type as reported by the provider (`image/jpeg`, `video/mp4`, ...). */
    val mimeType: String,
    /** File size in bytes; part of the MediaStore-observed revision. */
    val sizeBytes: Long,
    /** Provider `date_modified` in epoch millis; part of the observed revision. */
    val dateModifiedMillis: Long,
    /** Provider `date_added` in epoch millis; ordering/diagnostics only. */
    val dateAddedMillis: Long,
    /**
     * The MediaStore per-volume generation token (API 30+), part of the
     * observed revision. `null` below API 30 --- absence is a platform
     * property, not "no revision", which is why the field is nullable and
     * the reconciler compares it as a plain value.
     */
    val generationToken: String? = null,
    /**
     * Bumped on every in-app metadata write (EXIF/IPTC/XMP). NOT compared by
     * scan reconciliation --- see the class KDoc for the rationale.
     */
    val metadataWriteRevision: Int = 0,
    /** Whether the metadata cache layer holds a usable snapshot for this item. */
    val metadataCacheState: MetadataCacheState = MetadataCacheState.EMPTY,
    /**
     * Epoch millis of the last scan that observed this row live. Lets a
     * future compaction pass distinguish "seen yesterday" from "seen last
     * year" when choosing tombstone-purge cutoffs.
     */
    val lastSeenAtMillis: Long = 0L,
)

/**
 * The state of the app-side metadata cache for one catalogue row (audit
 * item "MediaMetadataSnapshot" --- its availability states are present /
 * absent / unsupported / unreadable; the catalogue only records WHETHER a
 * cached snapshot exists and is still trustworthy, the snapshot itself lives
 * in the metadata cache layer, not in the catalogue).
 */
enum class MetadataCacheState {
    /** No cached snapshot: metadata must be read before use. */
    EMPTY,

    /** A cached snapshot exists and matches the current revision. */
    CACHED,

    /** A cached snapshot exists but predates a revision change: re-read. */
    STALE,
}
