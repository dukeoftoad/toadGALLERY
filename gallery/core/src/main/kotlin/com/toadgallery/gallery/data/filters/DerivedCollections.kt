package com.toadgallery.gallery.data.filters

/**
 * The derived collections of the r17 Collections section (Phase 4
 * still-open item "Aves-style collections and smart-filter chips").
 *
 * A collection here is NOTHING BUT A NAMED [TimelineFilter] preset ---
 * derived, zero persistence, no schema, no stored state. Tapping one on
 * the Albums tab hands its filter to the Photos timeline (see
 * [PhotosFilterHandoff]), which applies it through the SAME engine its own
 * filter chips use; there is exactly one filtering implementation and one
 * timeline rendering it.
 *
 * ## The honest-subset rule, applied
 * Only collections BOTH timeline paths can serve correctly are listed:
 * every preset's filter passes [TimelineFilterCatalogue.canServe] (no
 * bucket dimension --- the catalogue has no bucket column), and its
 * MediaStore projection needs only real unified-Files columns ---
 * `DerivedCollectionsTest` pins the catalogue invariant, so a future
 * preset that would lie on the catalogue path fails the build instead of
 * shipping a lying chip. (The MediaStore windowed leg serves every engine
 * dimension: enum dims as fixed literals, value dims through the Bundle
 * protocol's `QUERY_ARG_SQL_SELECTION_ARGS` --- see
 * [TimelineFilterMediaStore].)
 *
 * ## API-level visibility (the favorites caveat)
 * The MediaStore `is_favorite` provider column is Q+ (see `Favorites`);
 * below Q every MediaStore-fed read maps `isFavorite = false`, so a
 * favorites collection there would be an always-empty card --- the same
 * reason the favorites pseudo-album card only exists while favorites do.
 * [visibleOn] encodes that rule as one pure, JVM-tested decision the
 * Albums section evaluates with the device's API level.
 *
 * ## Phase 5 boundary (deliberate)
 * User-defined saved collections (smart search / auto-albums / "save this
 * filter as a collection") are roadmap Phase 5 and need persistence +
 * smart-query infrastructure; nothing here stores anything. The favorites
 * pseudo-album card on the Albums tab (issue #66) remains a SEPARATE,
 * direct view; the "Favorites" entry below instead opens the timeline
 * favorites FILTER, chips and all.
 */
object DerivedCollections {

    /** Stable identifier of a derived collection (UI state only). */
    const val ID_PHOTOS = "photos"
    const val ID_VIDEOS = "videos"
    const val ID_GIFS = "gifs"
    const val ID_FAVORITES = "favorites"

    /**
     * One derived collection: a stable [id] (the Albums section keys its
     * label mapping on it) plus the [filter] preset it applies. The
     * user-visible label is a UI concern (string resources, mapped in the
     * Albums Collections section).
     */
    data class DerivedCollection(
        val id: String,
        val filter: TimelineFilter,
    )

    /** The collections available today, in display order. */
    val all: List<DerivedCollection> = listOf(
        DerivedCollection(
            id = ID_PHOTOS,
            filter = TimelineFilter(types = setOf(FilterMediaType.PHOTOS)),
        ),
        DerivedCollection(
            id = ID_VIDEOS,
            filter = TimelineFilter(types = setOf(FilterMediaType.VIDEOS)),
        ),
        DerivedCollection(
            id = ID_GIFS,
            filter = TimelineFilter(types = setOf(FilterMediaType.GIFS)),
        ),
        DerivedCollection(
            id = ID_FAVORITES,
            filter = TimelineFilter(favoritesOnly = true),
        ),
    )

    /**
     * The collections visible at [apiLevel] (`Build.VERSION.SDK_INT` at
     * the call site): everything, minus the favorites entry below
     * Android 10 (Q, API 29) --- the `is_favorite` provider column the
     * favorites dimension reads is Q+ (see `Favorites`), so below Q the
     * card would promise a view that can never fill. Pure and total; the
     * order of [all] is preserved.
     */
    fun visibleOn(apiLevel: Int): List<DerivedCollection> =
        if (apiLevel >= MIN_API_FOR_FAVORITES) {
            all
        } else {
            all.filter { it.id != ID_FAVORITES }
        }

    /**
     * The first API level where the `is_favorite` provider column exists
     * (Android 10 / Q); spelled as a number so this file stays JVM-pure
     * (`Build.VERSION_CODES.Q` is an android constant).
     */
    const val MIN_API_FOR_FAVORITES = 29
}
