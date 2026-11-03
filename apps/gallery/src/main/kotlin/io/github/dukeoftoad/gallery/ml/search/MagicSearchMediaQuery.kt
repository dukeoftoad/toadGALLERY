package io.github.dukeoftoad.gallery.ml.search

import android.content.ContentResolver
import android.content.ContentUris
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.MediaStore
import io.github.dukeoftoad.gallery.data.Favorites
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ml.people.MediaStoreSelection

/**
 * The production [MediaItem] query behind Magic Search results (r26,
 * Workstream 7): maps the CLIP index's ranked MediaStore file ids to
 * REAL rows through the Images collection, chunked by
 * [MediaStoreSelection] (the r25 People round's pure, JVM-tested
 * 999-bound-variable discipline --- reused, not duplicated, so the two
 * surfaces share one chunking contract).
 *
 * Android-adjacent glue, untested per ADR-0016 (the chunking half it
 * shares with the repository is the pure, tested [MediaStoreSelection]):
 *  - the query follows `MediaStoreSource`'s house style --- a projection
 *    of `_ID`/`MIME_TYPE`/`DATE_ADDED`/`WIDTH`/`HEIGHT` (plus
 *    `is_favorite` on Q+, the favorite-heart convention), nullable-column
 *    fallbacks, and the r19 guarded retry against pre-Q providers that
 *    reject the pixel-dimension columns (the retry drops WIDTH/HEIGHT;
 *    rows then map `0`/`0` --- the same honest "unknown" the house
 *    source maps);
 *  - one row per id (the caller deduped the ids when it ranked them),
 *    returned NEWEST FIRST (`date_added` descending, `_id` tiebreak ---
 *    the same total order every media grid pins). The REPOSITORY then
 *    re-orders these items by the CLIP ranking's relevance order ---
 *    both orders are honest and both are pinned by tests: the query's
 *    order is the house discipline, the ranking's order is the feature;
 *  - photos whose MediaStore row is gone (deleted / trashed /
 *    vault-moved between indexing and searching) simply do not come
 *    back --- their CLIP rows still sit in the store, which the r26
 *    queue's orphan pass cleans up in its own time; this query never
 *    fabricates rows.
 *
 * Returns the binding the [MagicSearchRepository] receives as its
 * `queryMedia` seam parameter (pure JVM tests inject fakes instead).
 */
fun queryMagicSearchMedia(
    resolver: ContentResolver,
    fileIds: List<Long>,
): List<MediaItem> {
    val out = HashMap<Long, MediaItem>(fileIds.size)
    for (chunk in MediaStoreSelection.chunkIds(fileIds)) {
        if (chunk.isEmpty()) continue
        val selection = MediaStoreSelection.inClause(chunk.size)
        val args = chunk.map(Long::toString).toTypedArray()
        // First attempt with the pixel-dimension columns; a pre-Q provider
        // that rejects them (IllegalArgumentException/SQLiteException out of
        // ContentResolver.query --- the r19 audit's crash shape) gets one
        // retry without them, mapping 0/0 ("unknown") instead.
        val rows = try {
            queryChunk(resolver, selection, args, withDimensions = true)
        } catch (_: IllegalArgumentException) {
            queryChunk(resolver, selection, args, withDimensions = false)
        } catch (_: SQLiteException) {
            queryChunk(resolver, selection, args, withDimensions = false)
        }
        rows.forEach { row -> out[row.id] = row }
    }
    // Newest first, _id tiebreak --- the house total order discipline.
    return out.values.sortedWith(
        compareByDescending<MediaItem> { it.dateAddedSec }.thenByDescending { it.id },
    )
}

private fun queryChunk(
    resolver: ContentResolver,
    selection: String,
    args: Array<String>,
    withDimensions: Boolean,
): List<MediaItem> {
    val projection = mutableListOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATE_ADDED,
    )
    if (withDimensions) {
        projection += MediaStore.Images.ImageColumns.WIDTH
        projection += MediaStore.Images.ImageColumns.HEIGHT
    }
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        projection += Favorites.COLUMN_IS_FAVORITE
    }
    val out = ArrayList<MediaItem>(args.size)
    resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection.toTypedArray(),
        selection,
        args,
        null,
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val mimeCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.MIME_TYPE)
        val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val widthCol = if (withDimensions) {
            cursor.getColumnIndexOrNull(MediaStore.Images.ImageColumns.WIDTH)
        } else {
            -1
        }
        val heightCol = if (withDimensions) {
            cursor.getColumnIndexOrNull(MediaStore.Images.ImageColumns.HEIGHT)
        } else {
            -1
        }
        val favoriteCol = cursor.getColumnIndexOrNull(Favorites.COLUMN_IS_FAVORITE)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
            out.add(
                MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    ),
                    // The row's own MIME type, never a guessed one (the
                    // r26 queue indexes JPEGs, but the row is the truth).
                    mimeType = mime ?: "image/jpeg",
                    dateAddedSec = cursor.getLong(dateCol),
                    isVideo = false, // the Images collection, by construction
                    width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                    height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                    isFavorite = favoriteCol >= 0 && cursor.getInt(favoriteCol) == 1,
                ),
            )
        }
    }
    return out
}

private fun android.database.Cursor.getColumnIndexOrNull(name: String): Int =
    try {
        getColumnIndexOrThrow(name)
    } catch (_: IllegalArgumentException) {
        -1
    }
