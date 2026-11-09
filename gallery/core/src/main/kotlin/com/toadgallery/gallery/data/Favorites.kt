package com.toadgallery.gallery.data

/**
 * Pure, Android-free constants and rules for MediaStore favorites (issue
 * #66) --- the `SearchQuery` convention: everything here is plain Kotlin
 * with NO `android.*` imports, so `FavoritesTest` pins the exact SQL text
 * and the sentinel resolution on the plain JVM inside `testDebugUnitTest`.
 *
 * ## Favorites semantics (system-managed)
 * A favorite is a MediaStore row flag (`is_favorite`), not app-private
 * state: it is set by a plain provider update, read by every gallery app
 * that honors the column, and survives app uninstall/reinstall of any one
 * gallery. That system-managed nature is exactly why toadGALLERY stores
 * nothing about favorites itself.
 *
 * ## Column-name literal (compile-time invariant, not a magic string)
 * `COLUMN_IS_FAVORITE` == `"is_favorite"` == the value of the SDK constant
 * `MediaStore.MediaColumns.IS_FAVORITE` (that constant was only *exposed*
 * in API 30; the provider column itself is the scoped-storage era, honored
 * from Android 10 / Q). Spelling the literal out keeps this file JVM-pure
 * and keeps the pre-R code paths (which must not reference the R-era
 * constant) lint-clean.
 *
 * ## API level contract
 *  - **Reads**: the column is only projected where the provider is known to
 *    have it (Q+); below Q the queries simply leave `isFavorite` at its
 *    `false` default and the favorites surface stays hidden.
 *  - **Writes** (`MediaFileOps.setFavorite`): gated to Q+ exactly like the
 *    other file operations --- below Q the app holds only read access and
 *    every write would be refused unrecoverably.
 *
 * ## Sentinel bucket id
 * The favorites pseudo-album is not a MediaStore bucket, so the album-detail
 * route (which is keyed by `bucketId: Long`) addresses it with the sentinel
 * [FAVORITES_BUCKET_ID]: real `BUCKET_ID` values are path hashes, negative
 * ids are outside their space in practice, and the navigation guard
 * (`bucketId != null && bucketId != 0L`) passes negatives through. The
 * resolution rule is [isFavoritesBucket]; see its KDoc for the collision
 * honesty note.
 */
object Favorites {

    /**
     * The MediaStore column holding the favorite flag. Equals the inlined
     * value of `MediaStore.MediaColumns.IS_FAVORITE`; see the object KDoc
     * for why it is a literal here.
     */
    const val COLUMN_IS_FAVORITE = "is_favorite"

    /**
     * The `bucketId` sentinel addressing the favorites pseudo-album through
     * the album-detail route. Negative on purpose: `0` is already reserved
     * by the route's render guard (`bucketId != 0L`), and real MediaStore
     * bucket ids are non-negative path hashes in practice.
     */
    const val FAVORITES_BUCKET_ID = -1L

    /**
     * The selection for the favorites query: images and videos (the
     * timeline's `media_type` semantics --- the Files table also holds
     * downloads and documents, which are not gallery favorites) flagged
     * `is_favorite = 1`. One bound-parameter-free literal, pinned by tests.
     *
     * The `media_type` numbers are the platform constants' values
     * (`MediaStore.Files.FileColumns`: IMAGE = 1, AUDIO = 2, VIDEO = 3), so
     * images+videos is `IN (1, 3)`. Before r19 this literal wrongly read
     * `IN (1, 2)` --- images+AUDIO --- which would have surfaced favorited
     * audio rows in the pseudo-album while missing every actual video; the
     * pinned tests now cross-check the numbers against the r17 filter
     * engine's JVM-pure constants of the same platform values
     * (`TimelineFilterMediaStore.MEDIA_TYPE_IMAGE` / `MEDIA_TYPE_VIDEO`)
     * so the pair cannot drift again.
     */
    const val FAVORITES_SELECTION = "media_type IN (1, 3) AND is_favorite = 1"

    /**
     * The favorites query's sort order: newest first with the `_id`
     * tiebreak, identical to [TimelineSort.NEWEST_FIRST]'s clause. The
     * favorites pseudo-album keeps newest-first in v1 (issue #66 documents
     * the scope decision); this constant exists so the pinned text lives in
     * the pure layer rather than in the query method.
     */
    const val FAVORITES_SORT_ORDER = "date_added DESC, _id DESC"

    /**
     * Whether [bucketId] addresses the favorites pseudo-album. The one
     * honest caveat: a real bucket whose `BUCKET_ID` hash happened to be
     * exactly [FAVORITES_BUCKET_ID] would be shadowed --- MediaStore bucket
     * ids are path hashes and negative values are not observed in practice,
     * so the collision is theoretical; if it ever mattered, the sentinel
     * could move without touching stored state (favorites live in
     * MediaStore, not in this app).
     */
    fun isFavoritesBucket(bucketId: Long): Boolean = bucketId == FAVORITES_BUCKET_ID
}
