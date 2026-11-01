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
 *
 * The r13 display/shape ride-alongs (displayName, width, height,
 * durationMs, isFavorite) mirror the record's same-named fields: nullable /
 * defaulted columns, plain `ALTER TABLE ... ADD COLUMN` additions in
 * [MediaCatalogueDatabase.MIGRATION_2_3], and --- as on the record ---
 * NON-REVISION state that never participates in reconciliation.
 *
 * The r16 location provenance columns (latitude, longitude,
 * locationSource) follow the r12 capture-date pattern (nullable derived
 * state, `ADD COLUMN` only in [MediaCatalogueDatabase.MIGRATION_3_4]) but
 * deliberately have NO counterpart on
 * [CatalogueRecord][io.github.dukeoftoad.gallery.data.catalogue
 * .CatalogueRecord] this round: the map surface reads them through a
 * dedicated projection (see `CatalogueDao`'s r16 section), and widening the
 * record + [io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue]
 * interface is the Phase 5 Places index's contract to settle.
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

    /** The provider `_display_name`; `null` when the provider reported none (r13 ride-along). */
    val displayName: String?,

    /** Pixel width; `0` means "unknown" (r13 ride-along, non-revision). */
    val width: Int,

    /** Pixel height; `0` means "unknown" (r13 ride-along, non-revision). */
    val height: Int,

    /** Video duration in milliseconds; `0` for stills / "unknown" (r13 ride-along, non-revision). */
    val durationMs: Long,

    /** The MediaStore `is_favorite` flag as the last scan saw it (r13 ride-along, non-revision). */
    val isFavorite: Boolean,

    /**
     * EXIF GPS latitude in signed decimal degrees (r16 round), or `null`.
     * Nullable for the same honest reasons as
     * [resolvedCaptureDateMillis]: rows scanned before the location
     * indexer existed were never resolved, and rows whose GPS block was
     * absent/unreadable/withheld resolve to no location at all ---
     * `null` means "no persisted location", whichever reason produced
     * it. NON-REVISION derived state (like the capture-date provenance
     * columns): a location re-read never participates in reconciliation.
     *
     * DEFAULTS TO `null` (like its siblings) because
     * [CatalogueRecord][io.github.dukeoftoad.gallery.data.catalogue
     * .CatalogueRecord] carries no location fields this round: the
     * record->row mapper constructs the entity WITHOUT them, so a scan
     * upsert writes the honest "no persisted location" and the location
     * index pass re-fills the row right after the batch (the same
     * wipe-and-refill sequencing the capture-date columns ride). Kotlin
     * default values are invisible to Room (the generated CREATE TABLE
     * and the v4 schema JSON declare no column default), so this is a
     * construction-site convenience, not schema.
     */
    val latitude: Double? = null,

    /**
     * EXIF GPS longitude in signed decimal degrees; `null` whenever
     * [latitude] is `null` (a real GPS fix always writes both). Defaults
     * to `null` for the reason [latitude]'s KDoc gives.
     */
    val longitude: Double? = null,

    /**
     * Where the location outcome came from, as the
     * [ItemLocationSource][io.github.dukeoftoad.gallery.data.media.location
     * .ItemLocationSource] enum's [name][kotlin.Enum.name] string --- the
     * same stringly-enum convention as [resolvedCaptureDateSource], for
     * the same reason. `EXIF_GPS` for a parsed fix; the special
     * `MEDIA_LOCATION_UNGRANTED` value marks rows first scanned while
     * the platform redacts EXIF location (API 29+ without
     * ACCESS_MEDIA_LOCATION) --- exactly the set a grant-triggered
     * re-pass would target; `null` for every other no-location outcome
     * (no GPS block, unreadable, unsupported format, never indexed).
     * Defaults to `null` for the reason [latitude]'s KDoc gives.
     */
    val locationSource: String? = null,
)
