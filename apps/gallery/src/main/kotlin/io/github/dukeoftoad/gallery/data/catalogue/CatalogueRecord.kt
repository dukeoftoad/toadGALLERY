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
 *
 * Capture-date provenance ([resolvedCaptureDateMillis] and friends, r12
 * round): the timezone-aware capture date resolved by
 * [io.github.dukeoftoad.gallery.data.media.dates.MediaDateResolver] during
 * the scan's index pass is persisted ON the row so the future timeline/
 * Moments UI never has to re-read the item's bytes. The record stays a dumb
 * data holder --- it neither re-runs the resolver nor interprets the source
 * string (stored as the enum's [name][kotlin.Enum.name] exactly so no enum
 * parse can fail on a value this build predates).
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
     * The media item's OWN per-row MediaStore generation stamp ---
     * `MediaStore.MediaColumns.GENERATION_MODIFIED` of THIS row (API 30+),
     * part of the observed revision. `null` below API 30 --- absence is a
     * platform property, not "no revision", which is why the field is
     * nullable and the reconciler compares it as a plain value.
     *
     * Deliberately NOT the per-volume `MediaStore.getGeneration` value:
     * that is the SCAN CHECKPOINT (see
     * [io.github.dukeoftoad.gallery.data.media.changes.MediaChangeFeed
     * .currentGeneration]) and moves whenever ANY item on the volume
     * changes --- stamping it per row would report every item on the
     * volume as changed after every edit anywhere (audit finding C).
     * Only the per-row generation-modified value participates in
     * per-item revision comparison.
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
    /**
     * The resolved capture instant in epoch millis (the winning candidate
     * of [io.github.dukeoftoad.gallery.data.media.dates.MediaDateResolver]),
     * or `null`. Nullable for TWO honest reasons, not one: rows written by
     * scans that predate the capture-date indexer were never resolved, and
     * rows whose file carried no trustworthy tag (metadata ABSENT,
     * UNREADABLE or UNSUPPORTED per
     * [io.github.dukeoftoad.gallery.metadata.MetadataAvailability]) resolve
     * to no capture date at all --- `null` deliberately means "no persisted
     * capture date", whichever reason produced it. Can be NEGATIVE:
     * pre-1970 instants are legitimate (see the MediaDates contract).
     */
    val resolvedCaptureDateMillis: Long? = null,
    /**
     * The zone assumption (minutes east of UTC) that interpreted the
     * winning NAIVE tag --- the same auditability rule as
     * [io.github.dukeoftoad.gallery.data.media.dates.DateCandidate
     * .timezoneOffsetMinutes]. `null` when the winning source was
     * epoch-based (no zone decision was made) or when
     * [resolvedCaptureDateMillis] itself is `null`.
     */
    val resolvedCaptureDateZoneOffsetMinutes: Int? = null,
    /**
     * The [io.github.dukeoftoad.gallery.data.media.dates.CaptureDateSource]
     * that won the resolution, stored as the enum's [name][kotlin.Enum.name]
     * string so this holder needs no dependency on the dates package's enum
     * at read time. `null` when no capture date was resolved (see
     * [resolvedCaptureDateMillis]).
     */
    val resolvedCaptureDateSource: String? = null,
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
