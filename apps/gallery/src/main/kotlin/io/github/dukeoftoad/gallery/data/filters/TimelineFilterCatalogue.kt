package io.github.dukeoftoad.gallery.data.filters

/**
 * The CATALOGUE WHERE projection of a [TimelineFilter] (r17 round): the
 * SQL fragment + bound args the Room catalogue's FILTERED timeline windows
 * (`RoomMediaCatalogue.filteredTimelineWindow`, composed onto the DAO's
 * `@RawQuery` window statement) apply before the ORDER BY / LIMIT /
 * OFFSET, so positional windows keep tiling over the FILTERED order.
 *
 * ## Column inventory (the honest-subset rule)
 * The projection may only touch columns `CatalogueItemEntity` ALREADY
 * persists. The r13 schema carries: `localMediaKey`, `canonicalUri`,
 * `mimeType`, `sizeBytes`, `dateModifiedMillis`, `dateAddedMillis`,
 * `generationToken`, `metadataWriteRevision`, `metadataCacheState`,
 * `lastSeenAtMillis`, the three capture-date provenance columns, and the
 * display ride-alongs (`displayName`, `width`, `height`, `durationMs`,
 * `isFavorite`). Therefore:
 *
 *  - **Media type** is served from `mimeType` --- there is NO `media_type`
 *    column. "Videos" is `mimeType LIKE 'video/%'`, which is the SAME
 *    video-ness rule the catalogue timeline itself already uses (the r13
 *    `CatalogueTimelineItem.isVideo` derives `startsWith("video/")` from
 *    the record's mime), so chip and grid can never disagree. "Photos" is
 *    the complement (`NOT LIKE 'video/%'`), which also catches the
 *    `image/*` / `application/octet-stream` fallback mimes the scan writes
 *    for null-mime rows.
 *  - **Favorites** is served from `isFavorite` (the r13 ride-along).
 *  - **Date range** is served from `dateAddedMillis` (divided back to
 *    seconds so it matches the MediaStore projection's granularity).
 *  - **Album / folder** has NO column --- [canServe] answers `false` for a
 *    bucket filter, and [where] renders the impossible clause (`1 = 0`) so
 *    a misrouted bucket filter is VISIBLY empty, never silently broad.
 *    Surfacing a folder chip therefore waits for catalogue columns
 *    (Phase 5); the MediaStore projection serves it, the UI does not show
 *    it, and the round report documents the gap.
 *
 * ## Column-name literals
 * Room column names equal the entity property names (no @ColumnInfo
 * renames on `CatalogueItemEntity`), so `mimeType`, `isFavorite` and
 * `dateAddedMillis` here are the persisted column names verbatim.
 *
 * ## Bound args
 * Literal-only clauses (type, favorites) carry fixed values from this
 * object; the date-range clause binds its two Long second-bounds. The
 * fragment order is FIXED (type, favorites, date range) and AND-joined ---
 * deterministic, pinned verbatim by `TimelineFilterCatalogueTest`.
 *
 * Privacy (ADR-0006 / ADR-0014): the fragment only ever narrows a local
 * Room query over the on-device catalogue; no network path exists.
 */
object TimelineFilterCatalogue {

    /**
     * One projected catalogue filter: the WHERE fragment (without the
     * `WHERE` keyword; `null` = no narrowing) plus the bound arguments it
     * references, in order.
     */
    data class CatalogueFilterWhere(
        val sql: String?,
        val args: List<Any>,
    )

    /**
     * Whether the catalogue can serve [filter] correctly. `false` exactly
     * when a bucket is set (no bucket column in the schema --- see the
     * class KDoc); the caller must then keep the generation on a backend
     * that CAN serve it (the Photos ViewModel's `timelineSource` ladder).
     */
    fun canServe(filter: TimelineFilter): Boolean = filter.bucketId == null

    /**
     * Projects [filter] onto the catalogue WHERE fragment.
     *
     * Total by construction: a filter [canServe] rejects (bucket set)
     * still projects --- to the impossible clause `1 = 0` --- so a caller
     * that ignores the gate gets a VISIBLE empty window rather than a
     * silently unfiltered one.
     */
    fun where(filter: TimelineFilter): CatalogueFilterWhere {
        if (!canServe(filter)) {
            return CatalogueFilterWhere(sql = "1 = 0", args = emptyList())
        }
        val clauses = ArrayList<String>(3)
        val args = ArrayList<Any>(2)
        when (typeCoverageOf(filter.types)) {
            FilterTypeCoverage.ALL -> Unit
            FilterTypeCoverage.PHOTOS_ONLY ->
                clauses += "mimeType NOT LIKE 'video/%'"
            FilterTypeCoverage.VIDEOS_ONLY ->
                clauses += "mimeType LIKE 'video/%'"
            FilterTypeCoverage.GIFS_ONLY ->
                clauses += "mimeType LIKE '$GIF_MIME_TYPE'"
            FilterTypeCoverage.VIDEOS_AND_GIFS ->
                clauses += "(mimeType LIKE 'video/%' OR " +
                    "mimeType LIKE '$GIF_MIME_TYPE')"
        }
        if (filter.favoritesOnly) {
            // Room maps the Boolean property to an INTEGER 0/1 column.
            clauses += "isFavorite = 1"
        }
        filter.dateRange?.let { range ->
            // dateAddedMillis is epoch MILLIS; divide back to seconds so
            // the bounds match the MediaStore projection and the in-memory
            // predicate exactly (SQLite integer division truncates like
            // Kotlin, and the scan writes exact second*1000 values anyway).
            clauses += "(dateAddedMillis / 1000) BETWEEN ? AND ?"
            args += range.startMillis / 1000
            args += range.endMillis / 1000
        }
        return CatalogueFilterWhere(
            sql = clauses.takeIf { it.isNotEmpty() }?.joinToString(separator = " AND "),
            args = args,
        )
    }
}
