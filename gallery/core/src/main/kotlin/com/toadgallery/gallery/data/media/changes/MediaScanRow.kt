package com.toadgallery.gallery.data.media.changes

import com.toadgallery.gallery.data.catalogue.CatalogueRecord
import com.toadgallery.gallery.data.catalogue.MetadataCacheState

/**
 * One row of an authoritative MediaStore scan, in the shape reconciliation
 * needs (Phase 4 contract, `docs/phase-4/phase-4-contract-audit-and-ente-ai
 * -plan.md`, items "MediaRevision" and "Incremental discovery").
 *
 * Deliberately PURE (no Android imports): the scan pipeline --- rows in,
 * [CatalogueReconciler] diff, catalogue writes out --- is plain logic and
 * must stay unit-testable on the JVM (ADR-0016; only the actual
 * ContentResolver cursor walk in `MediaStoreSource` is Android-bound).
 * A [MediaScanRow] carries the MediaStore-observed revision fields plus the
 * identity anchors, and --- from the r13 round --- the DISPLAY/SHAPE
 * ride-alongs the timeline renders from (see the field group KDoc below); it
 * deliberately does NOT carry [CatalogueRecord.metadataWriteRevision] or
 * [CatalogueRecord.metadataCacheState], because those are IN-APP state that
 * a provider scan cannot know --- conflating the two sides is precisely the
 * bug the reconciler's rules exist to prevent (an in-app metadata write must
 * not look like an external edit).
 *
 * The natural producer is a cursor walk over `MediaStoreSource` building
 * rows from `MediaIdentity.toKeyString()` + the provider columns;
 * [toRecord] then converts a fresh row into the catalogue cache entry.
 *
 * From the r19 round the row also carries the FOLDER/BUCKET ride-alongs
 * ([bucketId], [bucketDisplayName]) --- the folder dimension the
 * timeline's folder filter matches, row-sourced straight from the
 * provider's own bucket columns (never EXIF-derived, never re-filled by a
 * scan index hook), so they ride the upsert itself; see the r19 note
 * below the constructor for the carry-through contract.
 */
data class MediaScanRow(
    /** The stable local media key (MediaIdentity.toKeyString()). */
    val localMediaKey: String,
    /** The resolved content `Uri` string; access handle, not identity. */
    val canonicalUri: String,
    /** MIME type as reported by the provider. */
    val mimeType: String,
    /** File size in bytes; part of the MediaStore-observed revision. */
    val sizeBytes: Long,
    /** Provider `date_modified` in epoch millis; part of the observed revision. */
    val dateModifiedMillis: Long,
    /** Provider `date_added` in epoch millis. */
    val dateAddedMillis: Long,
    /**
     * The media item's OWN per-row MediaStore generation stamp ---
     * `MediaStore.MediaColumns.GENERATION_MODIFIED` of THIS row (API 30+),
     * part of the observed revision; `null` below API 30.
     *
     * Deliberately NOT the per-volume `MediaStore.getGeneration` value:
     * that is the SCAN CHECKPOINT (see [MediaChangeFeed.currentGeneration])
     * and moves whenever ANY item on the volume changes. Stamping the
     * volume checkpoint here would make every row report as changed after
     * every edit anywhere on the volume --- a full re-index storm (audit
     * finding C). The scan producer must read the row's
     * GENERATION_MODIFIED column into this field.
     */
    val generationToken: String? = null,
    // --- display/shape ride-alongs (r13 round) -----------------------------

    /**
     * The provider `_display_name` ("IMG_20240101_120000.jpg"); `null` when
     * the provider reported none. NON-REVISION ride-along --- see the group
     * note directly after the constructor.
     */
    val displayName: String? = null,

    /**
     * Pixel width as the provider reports it; `0` means "unknown". The grid's
     * aspect-ratio cell sizing (#34) reads this through the catalogue record.
     * NON-REVISION ride-along.
     */
    val width: Int = 0,

    /** Pixel height as the provider reports it; `0` means "unknown". */
    val height: Int = 0,

    /**
     * Video duration in milliseconds; `0` for stills / "unknown" (the same
     * zero-means-unknown convention as `MediaItem.durationMs`). Feeds the
     * duration overlay (#33). NON-REVISION ride-along.
     */
    val durationMs: Long = 0,

    /**
     * The MediaStore `is_favorite` flag (the walk only projects it where the
     * provider has the column, Q+). NON-REVISION ride-along.
     */
    val isFavorite: Boolean = false,

    // --- folder/bucket ride-alongs (r19 round) -----------------------------

    /**
     * The provider `bucket_id` --- the folder/bucket dimension (r19). `null`
     * is the honest "not resolvable on this API path" (the provider dropped
     * the column, or the walk fell back to the reduced projection), never a
     * 0 sentinel. NON-REVISION ride-along --- see the r19 note below.
     */
    val bucketId: Long? = null,

    /**
     * The bucket's display name (`bucket_display_name`, the DATA-column
     * derived name --- NOT `_display_name`), or `null` when the provider
     * reported none. NON-REVISION ride-along --- see the r19 note below.
     */
    val bucketDisplayName: String? = null,
) {
    // NOTE ON THE FIVE DISPLAY/SHAPE FIELDS ABOVE (r13 round): they are
    // NON-REVISION fields in the same class as mimeType/canonicalUri ---
    // ride-alongs on every record write that NEVER trigger re-indexing. The
    // reconciler's revision contract stays exactly size / date_modified /
    // per-row generation, so a favorite toggle or a dimension refresh must
    // not cost an embedding recompute: the timeline needs the fresh pixels,
    // the AI engines do not care, and treating display data as a revision
    // would re-index the whole library the first time the provider
    // back-fills width/height.
    //
    // KNOWN STALENESS EDGE, accepted: below R there is no per-row
    // GENERATION_MODIFIED stamp, and a favorite toggle moves neither size
    // nor date_modified, so on exactly API 29 (Q) the catalogue's
    // isFavorite copy can go stale until the next revision-visible change
    // to that row. Accepted because the favorites surface is Q+ in the
    // first place (below Q the flag is forever false and honest) and
    // because on 30+ the per-row generation moves on every row mutation,
    // so the toggle surfaces as a normal CHANGED row and these ride-alongs
    // refresh with it. Dimensions and display names only change when the
    // bytes change --- which IS a revision change --- so the edge is
    // favorites-only in practice.
    //
    // NOTE ON THE TWO FOLDER/BUCKET FIELDS ABOVE (r19 round): they are
    // NON-REVISION ride-alongs in the display/shape group's sense, but
    // with one crucial difference in how they TRAVEL: display data merely
    // rides the upsert, while the bucket pair is ROW-SOURCED with NO hook
    // refill --- there is no ScanIndexHook for folders, so a row's bucket
    // pair must survive every upsert unchanged (a mapper that nulls them
    // for a later re-fill, the capture-date/location wipe-and-refill
    // pattern, would LOSE the folder for good until the next
    // revision-visible change). They still never enter changedVersus(): a
    // folder move is a provider-row move in practice (MediaStore rewrites
    // date_modified on a move, which IS a revision change), so the pair
    // refreshes with the row's next CHANGED sighting; the same Q-only
    // pathological edge the note above documents applies if a move ever
    // touched no revision field.

    /**
     * Converts the row into the catalogue cache entry for a first sighting
     * (or a resurrection): a brand-new record starts with
     * [MetadataCacheState.EMPTY] --- no metadata snapshot exists yet ---
     * [metadataWriteRevision][CatalogueRecord.metadataWriteRevision] 0 ---
     * no in-app writes have happened --- and
     * [lastSeenAtMillis][CatalogueRecord.lastSeenAtMillis] 0, because "now"
     * is a caller decision (the scan driver stamps the wall clock when the
     * scan completes; a data class must not reach for the system clock
     * itself, which would make it untestable and non-deterministic).
     *
     * The display/shape ride-alongs travel verbatim, so the new record
     * mirrors its first-seen provider row (a CHANGED row's merged record
     * goes through this same mapping in `ScanDriverPolicy
     * .recordForAppliedChange`); they are not revision state and never
     * participate in reconciliation.
     *
     * The folder/bucket pair (r19) travels verbatim the same way: the
     * record mirrors the row's bucket, and NO hook re-fills it later ---
     * this mapping is the ONLY place the folder dimension enters the
     * catalogue.
     */
    fun toRecord(): CatalogueRecord =
        CatalogueRecord(
            localMediaKey = localMediaKey,
            canonicalUri = canonicalUri,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            dateModifiedMillis = dateModifiedMillis,
            dateAddedMillis = dateAddedMillis,
            generationToken = generationToken,
            displayName = displayName,
            width = width,
            height = height,
            durationMs = durationMs,
            isFavorite = isFavorite,
            bucketId = bucketId,
            bucketDisplayName = bucketDisplayName,
            metadataWriteRevision = 0,
            metadataCacheState = MetadataCacheState.EMPTY,
            lastSeenAtMillis = 0L,
        )
}
