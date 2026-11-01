package io.github.dukeoftoad.gallery.data.media.changes

import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MetadataCacheState

/**
 * One row of an authoritative MediaStore scan, in the shape reconciliation
 * needs (Phase 4 contract, `docs/phase-4/phase-4-contract-audit-and-ente-ai
 * -plan.md`, items "MediaRevision" and "Incremental discovery").
 *
 * Deliberately PURE (no Android imports): the scan pipeline --- rows in,
 * [CatalogueReconciler] diff, catalogue writes out --- is plain logic and
 * must stay unit-testable on the JVM (ADR-0016; only the actual
 * ContentResolver cursor walk in `MediaStoreSource` is Android-bound).
 * A [MediaScanRow] carries exactly the MediaStore-observed revision fields
 * plus the identity anchors; it deliberately does NOT carry
 * [CatalogueRecord.metadataWriteRevision] or
 * [CatalogueRecord.metadataCacheState], because those are IN-APP state that
 * a provider scan cannot know --- conflating the two sides is precisely the
 * bug the reconciler's rules exist to prevent (an in-app metadata write must
 * not look like an external edit).
 *
 * The natural producer is a cursor walk over `MediaStoreSource` building
 * rows from `MediaIdentity.toKeyString()` + the provider columns;
 * [toRecord] then converts a fresh row into the catalogue cache entry.
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
     * The MediaStore per-volume generation token (API 30+), part of the
     * observed revision; `null` below API 30.
     */
    val generationToken: String? = null,
) {
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
            metadataWriteRevision = 0,
            metadataCacheState = MetadataCacheState.EMPTY,
            lastSeenAtMillis = 0L,
        )
}
