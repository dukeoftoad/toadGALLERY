package com.toadgallery.gallery.data.filters

/**
 * The MEDIASTORE selection projection of a [TimelineFilter] (r17 round):
 * the selection fragment + bound args the legacy timeline path feeds to
 * `MediaStoreSource.queryTimelineWindow`'s unified `MediaStore.Files`
 * query.
 *
 * ## Where each fragment runs
 * The fragment is AND-composed AFTER the caller's base selection
 * (`media_type IN (IMAGE, VIDEO)` --- the timeline's existing images+videos
 * base, built from the platform constants on the android side). It targets
 * the UNIFIED FILES collection, where `media_type`, `mime_type`,
 * `is_favorite`, `bucket_id` and `date_added` are all real columns; the
 * pre-R snapshot path does NOT use this projection (it filters in memory
 * with [matches], which keeps its window slicing exact over the fully
 * materialized snapshot).
 *
 * ## Bound args (two classes of values, deliberately)
 * The enum-driven dimensions (media type, favorites) project as FIXED
 * LITERALS --- their values come from this object's constants, never from
 * user input, so there is no injection surface and nothing to bind (the
 * same class of literal the timeline's base selection itself interpolates
 * from SDK constants). The VALUE-driven dimensions (bucket, date range)
 * project BOUND ARGUMENTS
 * ([MediaStoreFilterSelection.selectionArgs]): their values are data, not
 * vocabulary. Both halves run on the windowed Bundle-args protocol
 * (`ContentResolver.query(uri, projection, bundle, signal)`, API 30+ for
 * the timeline's LIMIT/OFFSET windows): the selection string rides
 * `QUERY_ARG_SQL_SELECTION` and the args ride
 * `QUERY_ARG_SQL_SELECTION_ARGS`, the protocol's documented placeholder
 * companion ("Clients should never include user supplied values directly
 * in the selection string ... use standard placeholder notation ...
 * then supply a corresponding value in QUERY_ARG_SQL_SELECTION_ARGS" ---
 * https://developer.android.com/reference/android/content/ContentResolver,
 * both constants API 26+; the timeline's windowed leg is API 30+ anyway).
 * No surfaced chip or derived collection can produce an arg; the args
 * exist for the engine-only dimensions (see [TimelineFilter]'s KDoc).
 *
 * ## Column-name and value literals (compile-time invariants)
 * Spelled out so this file stays JVM-pure (the `SearchQuery` convention);
 * each equals the value of an SDK constant, verified against the reference
 * pages:
 *  - `"media_type"`  == `MediaStore.Files.FileColumns.MEDIA_TYPE`
 *    ("media_type");
 *  - `"mime_type"`   == `MediaStore.MediaColumns.MIME_TYPE` ("mime_type");
 *  - `"is_favorite"` == `MediaStore.MediaColumns.IS_FAVORITE` (inlined by
 *    the pure `Favorites.COLUMN_IS_FAVORITE` for years --- Q+ provider
 *    column);
 *  - `"bucket_id"`   == `MediaStore.MediaColumns.BUCKET_ID`;
 *  - `"date_added"`  == `MediaStore.MediaColumns.DATE_ADDED` (seconds);
 *  - `1` / `3` == `MEDIA_TYPE_IMAGE` / `MEDIA_TYPE_VIDEO` (the reference
 *    page https://developer.android.com/reference/android/provider
 *    /MediaStore.Files.FileColumns lists the constant values: IMAGE = 1,
 *    AUDIO = 2, VIDEO = 3, PLAYLIST = 4, SUBTITLE = 5, DOCUMENT = 6).
 *
 *  NOTE for reviewers: the PRE-EXISTING `SearchQuery`/`Favorites` SQL
 *  literals claim `2` is `MEDIA_TYPE_VIDEO`; the reference page says 2 is
 *  `MEDIA_TYPE_AUDIO` (video is 3). This engine uses the verified values
 *  and does not touch the older files (out of r17 scope; flagged in the
 *  round report).
 *
 * ## GIF matching
 * `LIKE 'image/gif'` has no wildcards, so SQLite evaluates it as a
 * case-insensitive equality for ASCII --- the exact semantic of
 * `MediaItem.isGif` (`equals(..., ignoreCase = true)`) and of
 * [TimelineFilter.matches]. The catalogue projection
 * ([TimelineFilterCatalogue]) uses the same shape for the same reason.
 *
 * ## Arg typing (the SQLite affinity note)
 * [MediaStoreFilterSelection.selectionArgs] is a list of STRINGS because
 * the Bundle protocol carries the args as a String array. SQLite applies
 * NUMERIC affinity when a TEXT bound value is compared against a column
 * of INTEGER affinity, so the numeric strings bind as numbers --- the
 * same coercion the legacy `selectionArgs: Array<String>` API relies on
 * everywhere (e.g. `queryBucket`'s `BUCKET_ID = ?`).
 *
 * Privacy (ADR-0014): these strings only ever narrow a local MediaStore
 * query; no network path exists.
 */
object TimelineFilterMediaStore {

    /** `media_type` value of an image row (== `MEDIA_TYPE_IMAGE`). */
    const val MEDIA_TYPE_IMAGE = 1

    /** `media_type` value of a video row (== `MEDIA_TYPE_VIDEO`). */
    const val MEDIA_TYPE_VIDEO = 3

    /**
     * One projected MediaStore filter: the selection fragment to AND after
     * the caller's base selection (`null` = no narrowing), plus any bound
     * arguments it references, in order (always empty for literal-only
     * fragments --- i.e. for every filter the r17 UI can surface). A
     * `List` (not `Array`) so the JVM tests get structural equality,
     * exactly like `SearchQuery.Spec`.
     */
    data class MediaStoreFilterSelection(
        val selection: String?,
        val selectionArgs: List<String>,
    )

    /**
     * Projects [filter] onto the unified-Files selection fragment.
     *
     * Clause order is FIXED (type, then favorites, then bucket, then date
     * range) and AND-joined, so the fragment for a given filter value is a
     * deterministic string the tests pin verbatim.
     */
    fun selection(filter: TimelineFilter): MediaStoreFilterSelection {
        val clauses = ArrayList<String>(4)
        val args = ArrayList<String>(2)
        when (typeCoverageOf(filter.types)) {
            FilterTypeCoverage.ALL -> Unit
            FilterTypeCoverage.PHOTOS_ONLY ->
                clauses += "media_type = $MEDIA_TYPE_IMAGE"
            FilterTypeCoverage.VIDEOS_ONLY ->
                clauses += "media_type = $MEDIA_TYPE_VIDEO"
            FilterTypeCoverage.GIFS_ONLY ->
                clauses += "mime_type LIKE '$GIF_MIME_TYPE'"
            FilterTypeCoverage.VIDEOS_AND_GIFS ->
                clauses += "(media_type = $MEDIA_TYPE_VIDEO OR " +
                    "mime_type LIKE '$GIF_MIME_TYPE')"
        }
        if (filter.favoritesOnly) {
            clauses += "is_favorite = 1"
        }
        filter.bucketId?.let { bucket ->
            clauses += "bucket_id = ?"
            args += bucket.toString()
        }
        filter.dateRange?.let { range ->
            // date_added is provider SECONDS; both bounds stay inclusive at
            // second granularity (the same contract the in-memory predicate
            // and the catalogue projection implement).
            clauses += "date_added BETWEEN ? AND ?"
            args += (range.startMillis / 1000).toString()
            args += (range.endMillis / 1000).toString()
        }
        if (filter.excludeScreenshots) {
            // r76 Clean Feed: exclude screenshot folders by the OEM naming
            // convention, case-insensitively. SQL `NOT LIKE` never matches
            // NULL, and rows without a resolvable bucket display name must
            // NOT vanish from the clean feed (the in-memory predicate's
            // fail-open twin) --- hence the explicit `OR ... IS NULL`.
            clauses += "(bucket_display_name IS NULL OR " +
                "bucket_display_name NOT LIKE '%screenshot%')"
        }
        return MediaStoreFilterSelection(
            selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(separator = " AND "),
            selectionArgs = args,
        )
    }
}
