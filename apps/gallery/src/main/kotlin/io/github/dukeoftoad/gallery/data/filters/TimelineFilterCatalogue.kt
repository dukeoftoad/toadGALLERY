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
 * persists. The r19 schema carries: `localMediaKey`, `canonicalUri`,
 * `mimeType`, `sizeBytes`, `dateModifiedMillis`, `dateAddedMillis`,
 * `generationToken`, `metadataWriteRevision`, `metadataCacheState`,
 * `lastSeenAtMillis`, the three capture-date provenance columns, the
 * display ride-alongs (`displayName`, `width`, `height`, `durationMs`,
 * `isFavorite`), and the folder/bucket pair (`bucketId`,
 * `bucketDisplayName`; Room v5). Therefore:
 *
 *  - **Media type** is served from `mimeType` --- there is NO `media_type`
 *    column. "Videos" is `mimeType LIKE 'video/%'`, which is the SAME
 *    video-ness rule the catalogue timeline itself already uses (the r13
 *    `CatalogueTimelineItem.isVideo` derives `startsWith("video/")` from
 *    the record's mime), so chip and grid can never disagree. "Photos" is
 *    the complement (`NOT LIKE 'video/%'`), which also catches the
 *    `image/`-prefixed and `application/octet-stream` fallback mimes the
 *    scan writes for null-mime rows (Kotlin block comments nest, so the
 *    literal `image/`-star glob is deliberately spelled out this way).
 *  - **Favorites** is served from `isFavorite` (the r13 ride-along).
 *  - **Album / folder** is served from `bucketId` (the r19 Room v5
 *    column): `bucketId = ?` with the id as a BOUND arg --- the exact
 *    bucket-id-matching semantics of the MediaStore projection's
 *    `bucket_id = ?` ([TimelineFilterMediaStore]), so the two paths can
 *    never disagree about which folder a filter means. Matching is
 *    id-based by design; `bucketDisplayName` is persisted for
 *    folder-surface labeling only and is deliberately NOT a filter column
 *    (names collide across volumes, ids do not).
 *  - **Date range** is served from `dateAddedMillis` (divided back to
 *    seconds so it matches the MediaStore projection's granularity).
 *
 * ## Column-name literals
 * Room column names equal the entity property names (no @ColumnInfo
 * renames on `CatalogueItemEntity`), so `mimeType`, `isFavorite`,
 * `bucketId` and `dateAddedMillis` here are the persisted column names
 * verbatim.
 *
 * ## Bound args
 * Literal-only clauses (type, favorites) carry fixed values from this
 * object; the VALUE-driven dimensions (bucket, date range) bind their args
 * --- their values are data, not vocabulary. The fragment order is FIXED
 * (type, favorites, bucket, date range --- the same order the MediaStore
 * projection composes) and AND-joined --- deterministic, pinned verbatim
 * by `TimelineFilterCatalogueTest`.
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
     * Whether the catalogue can serve [filter] correctly. Since the r19
     * Room v5 bucket columns, EVERY dimension the engine defines is
     * servable from persisted columns, so this answers `true` for every
     * filter value --- the answer is not constant-by-laziness: it is the
     * honest-subset rule RE-VERIFIED against the column inventory, and the
     * gate stays because callers route on it (the Photos ViewModel's
     * backend ladder; the DerivedCollections invariant). A future dimension
     * the catalogue cannot serve must flip this back to `false` for
     * exactly that dimension --- and update its [where] clause (see
     * below).
     */
    fun canServe(filter: TimelineFilter): Boolean = true

    /**
     * Projects [filter] onto the catalogue WHERE fragment.
     *
     * Total by construction: every dimension projects onto a persisted
     * column (see [canServe] --- the v5 bucket column closed the last
     * gap), so every [TimelineFilter] value produces a fragment. Clause
     * order is fixed (type, favorites, bucket, date range) and AND-joined,
     * so the fragment for a given filter value is a deterministic string
     * the tests pin verbatim.
     */
    fun where(filter: TimelineFilter): CatalogueFilterWhere {
        val clauses = ArrayList<String>(4)
        val args = ArrayList<Any>(3)
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
        filter.bucketId?.let { bucket ->
            // The v5 column: the folder dimension, matched by id with the
            // id as a BOUND arg (a value, not vocabulary) --- the same
            // bucket-id semantics as the MediaStore projection's
            // `bucket_id = ?`. SQL `=` never matches NULL, which is
            // exactly right: rows whose bucket is the honest "not
            // resolvable on this API path" null belong to NO folder
            // window, never to a fake bucket 0.
            clauses += "bucketId = ?"
            args += bucket
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
