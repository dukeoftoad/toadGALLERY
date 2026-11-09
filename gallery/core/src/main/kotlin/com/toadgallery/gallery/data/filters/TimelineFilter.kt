package com.toadgallery.gallery.data.filters

/**
 * The declarative smart-filter model of the Photos timeline (r17 round,
 * Phase 4 still-open item "Aves-style collections and smart-filter chips"
 * --- design reference only, no Dart port).
 *
 * A [TimelineFilter] is a VALUE describing which slice of the library the
 * timeline should show. It is the single source every consumer projects
 * from --- never a query itself:
 *
 *  - the IN-MEMORY projection ([matches]) serves the pre-R MediaStore
 *    snapshot path (the snapshot is fully materialized before slicing, so
 *    filtering in memory keeps the window math exact);
 *  - the MEDIASTORE selection projection
 *    ([TimelineFilterMediaStore.selection]) serves the API 30+ windowed
 *    Bundle-args queries (positional windows MUST filter inside the query,
 *    or offsets stop tiling);
 *  - the CATALOGUE WHERE projection ([TimelineFilterCatalogue.where])
 *    serves the Room catalogue windows (same positional-window rule).
 *
 * ## Dimension set (Aves-style) and what is surfaced
 *  - **Media type** ([types], multi-select): photos / videos / GIFs, where
 *    GIFs are a SUBSET of photos (a GIF is an image). Selecting
 *    Photos + GIFs therefore means just "photos"; selecting Photos + Videos
 *    means "no restriction". All three are surfaced as chips.
 *  - **Favorites** ([favoritesOnly], composes as AND): surfaced as a chip.
 *  - **Album / folder** ([bucketId]): implemented + tested in ALL SQL
 *    projections --- and since the r19 catalogue v5 (the `bucketId` /
 *    `bucketDisplayName` columns, persisted by the scan and served by
 *    [TimelineFilterCatalogue]) the CATALOGUE projection serves it too, so
 *    the dimension is servable on EVERY timeline path. It is still NOT
 *    surfaced as a chip: the folder chip's UI wiring is its own round
 *    (the roadmap follow-up "a catalogue bucket column before the folder
 *    chip can be surfaced" --- the column is what this engine change
 *    unblocks).
 *  - **Date range** ([dateRange]): implemented + tested in all three
 *    projections, but NOT surfaced --- this round has no date-picker UI
 *    (Phase 5 smart-search surface). Bounded at SECOND granularity (see
 *    [FilterDateRange]).
 *
 * ## Purity (ADR-0016, the `SearchQuery`/`TimelineSort` convention)
 * Everything in this file is plain Kotlin with NO `android.*` imports, so
 * `TimelineFilterTest` pins the semantics on the plain JVM. The predicate
 * takes a [TimelineFilterFields] tuple rather than a `MediaItem` because
 * `MediaItem` embeds `android.net.Uri`, which a JVM test cannot construct;
 * the android side maps an item onto the tuple at the thin seam (the same
 * split `TimelineSort.sortItems` uses with its selector lambdas).
 *
 * Privacy (ADR-0006 / ADR-0014): a filter is pure local state --- it only
 * ever narrows which on-device rows a query returns. No network path
 * exists (the app holds no INTERNET permission).
 */

/** The GIF MIME type; the one mime literal the whole engine shares. */
const val GIF_MIME_TYPE: String = "image/gif"

/**
 * One selectable media-type dimension of [TimelineFilter.types].
 *
 * The values are dimension NAMES, not MediaStore `media_type` numbers ---
 * the SQL projections spell those literals out themselves (with their
 * provenance documented per file).
 */
enum class FilterMediaType {
    /** Still images (everything not a video), GIFs included. */
    PHOTOS,

    /** Videos (`media_type` VIDEO; MIME prefix `video/`). */
    VIDEOS,

    /** Animated GIFs --- a SUBSET of [PHOTOS] (`image/gif`). */
    GIFS,
}

/**
 * The effective coverage of a [TimelineFilter.types] set after the
 * GIFs-are-photos subset rule is folded in. This is the canonical
 * reduction BOTH SQL projections and the in-memory predicate switch on ---
 * one place decides "Photos + GIFs == Photos", and every projection
 * inherits the same answer.
 */
internal enum class FilterTypeCoverage {
    /** No type restriction (empty set, or Photos + Videos). */
    ALL,

    /** Photos only (includes GIFs, whether or not GIFs was selected). */
    PHOTOS_ONLY,

    /** Videos only. */
    VIDEOS_ONLY,

    /** GIFs only. */
    GIFS_ONLY,

    /** The union of videos and GIFs (Videos + GIFs selected, no Photos). */
    VIDEOS_AND_GIFS,
}

/** The canonical reduction of a type-selection set (GIFs are photos). */
internal fun typeCoverageOf(types: Set<FilterMediaType>): FilterTypeCoverage = when {
    types.isEmpty() -> FilterTypeCoverage.ALL
    FilterMediaType.PHOTOS in types && FilterMediaType.VIDEOS in types -> FilterTypeCoverage.ALL
    FilterMediaType.PHOTOS in types -> FilterTypeCoverage.PHOTOS_ONLY
    FilterMediaType.VIDEOS in types && FilterMediaType.GIFS in types -> FilterTypeCoverage.VIDEOS_AND_GIFS
    FilterMediaType.VIDEOS in types -> FilterTypeCoverage.VIDEOS_ONLY
    else -> FilterTypeCoverage.GIFS_ONLY
}

/**
 * The date-range dimension: items whose ADDED instant falls in
 * **[startMillis, endMillis]**, both ends INCLUSIVE, compared at SECOND
 * granularity (MediaStore `date_added` is seconds; the catalogue stores
 * `dateAddedMillis` and the projections divide back to seconds so all three
 * agree exactly --- see [TimelineFilterMediaStore] and
 * [TimelineFilterCatalogue]).
 *
 * Both bounds are epoch millis; a range whose start exceeds its end simply
 * matches nothing (total, never crashing).
 */
data class FilterDateRange(
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * The android-free field tuple the in-memory predicate evaluates. The
 * android seam maps a `MediaItem` (or any stand-in) onto this shape; the
 * [TimelineFilterFields.isVideo] flag is the AUTHORITATIVE type signal on
 * MediaStore-fed paths (it comes from `media_type`), while
 * [TimelineFilterFields.mimeType] carries the GIF test
 * (`MediaItem.isGif`'s rule: `mimeType.equals("image/gif", ignoreCase)`).
 */
data class TimelineFilterFields(
    val mimeType: String,
    val isVideo: Boolean,
    val isFavorite: Boolean,
    val dateAddedSec: Long,
)

/**
 * One declarative timeline filter. Defaults describe the UNFILTERED
 * timeline: a fresh `TimelineFilter()` matches every item and projects to
 * "no selection / no WHERE" --- the pre-r17 behavior, exactly.
 */
data class TimelineFilter(
    /** Which media-type chips are selected (empty = no restriction). */
    val types: Set<FilterMediaType> = emptySet(),
    /** Whether only favorited items match (composes as AND with [types]). */
    val favoritesOnly: Boolean = false,
    /**
     * The folder/bucket dimension (MediaStore `bucket_id`). Not surfaced in
     * the r17 UI; the r19 catalogue v5 `bucketId`/`bucketDisplayName`
     * columns made every SQL projection servable (see the dimension note
     * above), so surfacing is now a pure UI round. In-memory evaluation of
     * a bucket filter is impossible ([TimelineFilterFields] carries no
     * bucket), so [matches] deliberately returns `false` for every item ---
     * a VISIBLE empty result if a caller ever routes a bucket filter
     * through memory, never a silent pass-through (the pre-R snapshot leg
     * keeps that fail-closed guard; the catalogue leg now serves the
     * dimension for real).
     */
    val bucketId: Long? = null,
    /** The added-instant range; see [FilterDateRange]. Not surfaced in r17. */
    val dateRange: FilterDateRange? = null,
) {
    /** Whether this filter narrows anything at all (drives the All chip). */
    val isActive: Boolean
        get() = types.isNotEmpty() || favoritesOnly || bucketId != null || dateRange != null
}

/**
 * The IN-MEMORY projection: does [fields] pass this filter?
 *
 * Total and side-effect free. The SQL projections must answer the same
 * question for the same logical row --- the JVM tests pin the equivalent
 * shapes side by side (see `TimelineFilterMediaStoreTest` /
 * `TimelineFilterCatalogueTest`).
 */
fun TimelineFilter.matches(fields: TimelineFilterFields): Boolean {
    // A bucket filter cannot be evaluated in memory (no bucket in the
    // tuple); fail CLOSED so a misrouted bucket filter is visibly empty
    // rather than silently broad.
    if (bucketId != null) return false
    if (favoritesOnly && !fields.isFavorite) return false
    when (typeCoverageOf(types)) {
        FilterTypeCoverage.ALL -> Unit
        FilterTypeCoverage.PHOTOS_ONLY -> if (fields.isVideo) return false
        FilterTypeCoverage.VIDEOS_ONLY -> if (!fields.isVideo) return false
        FilterTypeCoverage.GIFS_ONLY ->
            if (!fields.mimeType.equals(GIF_MIME_TYPE, ignoreCase = true)) return false
        FilterTypeCoverage.VIDEOS_AND_GIFS ->
            if (!fields.isVideo && !fields.mimeType.equals(GIF_MIME_TYPE, ignoreCase = true)) {
                return false
            }
    }
    if (dateRange != null) {
        if (fields.dateAddedSec < dateRange.startMillis / 1000 ||
            fields.dateAddedSec > dateRange.endMillis / 1000
        ) {
            return false
        }
    }
    return true
}

/**
 * The chip-toggle helper: returns the filter with [type]'s membership
 * flipped, leaving every other dimension untouched. Toggling the last
 * selected type off yields the empty set --- i.e. back to All --- which is
 * the honest multi-select semantics of the chips row.
 */
fun TimelineFilter.withTypeToggled(type: FilterMediaType): TimelineFilter =
    copy(
        types = if (type in types) {
            types - type
        } else {
            types + type
        },
    )

/**
 * The favorites-chip toggle: flips [TimelineFilter.favoritesOnly], leaving
 * every other dimension untouched.
 */
fun TimelineFilter.withFavoritesToggled(): TimelineFilter =
    copy(favoritesOnly = !favoritesOnly)
