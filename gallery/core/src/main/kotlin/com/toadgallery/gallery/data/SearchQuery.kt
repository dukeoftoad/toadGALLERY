package com.toadgallery.gallery.data

/**
 * Pure query-building logic for the Search tab v1 (issue #62).
 *
 * Builds the MediaStore selection / selection args / sort order for a
 * file-NAME query over the unified `MediaStore.Files` collection, filtered
 * to images and/or videos, newest first, capped by a LIMIT clause. Advanced
 * smart filters (date, album, type beyond photo/video, OCR…) are roadmap
 * Phase 5 and deliberately out of scope here.
 *
 * ## Why this is a separate, android-free object
 * Everything in this file is plain Kotlin with NO `android.*` imports
 * (the ADR-0016 / `FileOpNaming` convention): the SQL strings are literals,
 * so `SearchQueryTest` can pin the exact selection text, the LIKE escaping
 * and the LIMIT clause in plain `testDebugUnitTest` without an emulator or
 * Robolectric. The Android layer ([MediaStoreSource.querySearch]) hands the
 * built [Spec] straight to `ContentResolver.query`.
 *
 * ## Column-name literals (compile-time invariants, not magic strings)
 * Each literal below equals an inlined `MediaStore` string constant, spelled
 * out so this file stays JVM-pure:
 *  - `"_display_name"` == `MediaStore.MediaColumns.DISPLAY_NAME` (the
 *    constant's value really is `_display_name` --- the provider's own
 *    column, distinct from `OpenableColumns.DISPLAY_NAME`);
 *  - `"media_type"`     == `MediaStore.Files.FileColumns.MEDIA_TYPE`;
 *  - `"date_added"`     == `MediaStore.MediaColumns.DATE_ADDED`;
 *  - `"_id"`            == `MediaStore.MediaColumns._ID`;
 *  - `1` / `2` / `3`    == `MEDIA_TYPE_IMAGE` / `MEDIA_TYPE_AUDIO` /
 *    `MEDIA_TYPE_VIDEO` --- the values the SDK reference page lists for
 *    `MediaStore.Files.FileColumns` (r19 corrected this file's video
 *    literals from `2`, which is AUDIO, to `3`; see [mediaTypeClause]'s
 *    KDoc for the regression pins).
 *
 * ## LIKE escaping (the subtle part)
 * The user query is matched with `LIKE ? ESCAPE '\'` wrapped in `%`
 * wildcards (substring match). `%`, `_` and `\` inside the typed text are
 * SQL wildcards / the escape char, so each is prefixed with the escape
 * backslash first: a literal "100%" must match files named "100%" and NOT
 * every file starting "100". Without `ESCAPE '\'` the escaped pattern would
 * be meaningless; the clause and the escaping are one contract, pinned by
 * the tests together.
 *
 * Privacy (ADR-0006 / ADR-0014): this object only ever produces strings;
 * the query it feeds reads local MediaStore rows only --- no network path
 * exists (the app holds no INTERNET permission).
 */
enum class SearchMediaType {
    /** Images and videos (the gallery's whole media surface). */
    ALL,

    /** Images only (`MEDIA_TYPE_IMAGE`). */
    IMAGES,

    /** Videos only (`MEDIA_TYPE_VIDEO`). */
    VIDEOS,
}

/**
 * The pure builder half of issue #62's data path; see [SearchMediaType]'s
 * KDoc for the file-wide contract. All functions are total and side-effect
 * free.
 */
object SearchQuery {

    /** Queries shorter than this (after trim) never reach MediaStore. */
    const val MIN_QUERY_LENGTH = 2

    /** Default result cap; a hard ceiling keeps the v1 grid bounded. */
    const val DEFAULT_LIMIT = 1000

    /** The `ESCAPE` character of the LIKE contract (a single backslash). */
    const val LIKE_ESCAPE = '\\'

    /**
     * One fully built MediaStore query: selection, bound args, sort order.
     * [selectionArgs] is a `List` (not `Array`) so [Spec] gets structural
     * equality --- the JVM tests assert whole specs, not loose parts.
     */
    data class Spec(
        val selection: String,
        val selectionArgs: List<String>,
        val sortOrder: String,
    )

    /**
     * The short-query gate: `true` once the trimmed query has at least
     * [MIN_QUERY_LENGTH] characters. This is the single source of the
     * "keep typing" behavior --- the ViewModel consults it before every
     * MediaStore round-trip and the tests pin it.
     */
    fun isSearchable(query: String): Boolean =
        query.trim().length >= MIN_QUERY_LENGTH

    /**
     * Prefixes every SQL wildcard (`%`, `_`) and the escape char itself
     * with the escape backslash, so the typed text matches literally inside
     * a `LIKE ... ESCAPE '\'` pattern.
     */
    fun escapeLikePattern(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            if (ch == '%' || ch == '_' || ch == LIKE_ESCAPE) {
                append(LIKE_ESCAPE)
            }
            append(ch)
        }
    }

    /**
     * The bound LIKE argument for a user query: trimmed, escaped, then
     * wrapped in `%` wildcards for substring matching.
     */
    fun likeArgument(query: String): String =
        "%" + escapeLikePattern(query.trim()) + "%"

    /**
     * The media-type clause of the selection for one filter chip. `ALL`
     * uses `IN (1, 3)` --- the same images+videos semantics the timeline's
     * unified Files queries use (`queryTimelineWindow` / `queryTrashed`) ---
     * rather than omitting the clause, so the Files table's non-media rows
     * (downloads, documents) can never leak into results.
     *
     * The numbers are the platform constants' values (IMAGE = 1, AUDIO = 2,
     * VIDEO = 3; see the mapping in the file KDoc). Before r19 both
     * non-image literals were wrong: `ALL` read `IN (1, 2)` --- images+AUDIO,
     * so audio files leaked into "All" results --- and `VIDEOS` read `= 2`,
     * which presented audio rows as videos and found no actual video. The
     * pinned tests now cross-check each clause against the r17 filter
     * engine's JVM-pure constants
     * (`TimelineFilterMediaStore.MEDIA_TYPE_IMAGE` / `MEDIA_TYPE_VIDEO`)
     * and assert AUDIO's `2` appears in no clause.
     */
    fun mediaTypeClause(mediaType: SearchMediaType): String = when (mediaType) {
        SearchMediaType.ALL -> "media_type IN (1, 3)"
        SearchMediaType.IMAGES -> "media_type = 1"
        SearchMediaType.VIDEOS -> "media_type = 3"
    }

    /**
     * The sort clause: newest first with the `_id` DESC tiebreak (burst
     * shots share `date_added` seconds; the tiebreak keeps the order total,
     * the same discipline `queryTimelineWindow` documents), capped by the
     * `LIMIT n` suffix --- the MediaStore/SQLite convention for bounding a
     * query through the string sortOrder parameter. Non-positive limits are
     * clamped to 1 (a defensive floor; the UI always passes the default).
     */
    fun sortOrder(limit: Int = DEFAULT_LIMIT): String =
        "date_added DESC, _id DESC LIMIT ${limit.coerceAtLeast(1)}"

    /**
     * Builds the whole [Spec] for one search request. The selection shape is
     * `(<media-type clause>) AND _display_name LIKE ? ESCAPE '\'` --- one
     * bound parameter, exactly the escaped pattern from [likeArgument].
     */
    fun build(
        query: String,
        mediaType: SearchMediaType,
        limit: Int = DEFAULT_LIMIT,
    ): Spec = Spec(
        selection = "(" + mediaTypeClause(mediaType) + ") AND " +
            "_display_name LIKE ? ESCAPE '\\'",
        selectionArgs = listOf(likeArgument(query)),
        sortOrder = sortOrder(limit),
    )
}
