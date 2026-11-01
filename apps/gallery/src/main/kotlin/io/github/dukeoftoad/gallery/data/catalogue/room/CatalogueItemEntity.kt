package io.github.dukeoftoad.gallery.data.catalogue.room

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The Room row for one live [CatalogueRecord][io.github.dukeoftoad.gallery
 * .data.catalogue.CatalogueRecord] --- the Phase 4 media catalogue (ADR-0006
 * persistence layer; audit item "Room catalogue").
 *
 * Android-adjacent persistence code: untested per ADR-0016 (the repo
 * pattern --- all logic lives in pure types covered by JVM tests). The
 * columns mirror [CatalogueRecord][io.github.dukeoftoad.gallery.data
 * .catalogue.CatalogueRecord] one-to-one; the ONLY translation is
 * [metadataCacheState], stored as the enum's [name] because Room has no
 * built-in enum column mapping and a global `TypeConverter` would be
 * overkill for a single field --- the mapping lives in
 * [RoomMediaCatalogue] instead, next to its reverse.
 *
 * Scope guard (audit): face/CLIP vectors and all engine-owned AI state are
 * NOT columns here and never will be --- they live in the ML stores keyed
 * by `localMediaKey`.
 */
@Entity(tableName = "catalogue_items")
data class CatalogueItemEntity(
    /** Primary key: the stable local media key (MediaIdentity.toKeyString()). */
    @PrimaryKey val localMediaKey: String,
    /** The resolved content `Uri` string; access handle, not identity. */
    val canonicalUri: String,
    /** MIME type as reported by the provider. */
    val mimeType: String,
    /** File size in bytes; part of the MediaStore-observed revision. */
    val sizeBytes: Long,
    /** Provider `date_modified` in epoch millis. */
    val dateModifiedMillis: Long,
    /** Provider `date_added` in epoch millis. */
    val dateAddedMillis: Long,
    /** MediaStore per-row generation token (`MediaColumns.GENERATION_MODIFIED` of THIS row); `null` below API 30. */
    val generationToken: String?,
    /** In-app metadata write counter; excluded from scan reconciliation. */
    val metadataWriteRevision: Int,
    /**
     * The [MetadataCacheState][io.github.dukeoftoad.gallery.data.catalogue
     * .MetadataCacheState] name as a plain string.
     */
    val metadataCacheState: String,
    /** Epoch millis of the last scan that observed this row live. */
    val lastSeenAtMillis: Long,
    /**
     * Resolved capture instant in epoch millis (see the matching
     * [CatalogueRecord] field); `null` when never resolved or when the
     * item's metadata carried no trustworthy tag. Legitimately negative
     * for pre-1970 capture dates.
     */
    val resolvedCaptureDateMillis: Long?,
    /**
     * Zone assumption (minutes east of UTC) used to interpret the winning
     * naive tag; `null` for epoch-based winning sources and when
     * [resolvedCaptureDateMillis] is `null`.
     */
    val resolvedCaptureDateZoneOffsetMinutes: Int?,
    /**
     * The winning
     * [CaptureDateSource][io.github.dukeoftoad.gallery.data.media.dates
     * .CaptureDateSource] as its [name][kotlin.Enum.name] string --- the
     * same stringly-enum convention as [metadataCacheState], for the same
     * reason (no global TypeConverter for a single field; the mapping
     * lives next to its reverse in [RoomMediaCatalogue] and is a straight
     * name copy here since the record's field is already a string).
     */
    val resolvedCaptureDateSource: String?,
)
