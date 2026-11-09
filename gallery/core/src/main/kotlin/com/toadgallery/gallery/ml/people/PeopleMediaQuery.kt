package com.toadgallery.gallery.ml.people

import android.content.ContentResolver
import android.content.ContentUris
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.MediaStore

/**
 * One photo of a person (r25, Workstream 6): the light MediaStore-backed
 * row the Person detail grid renders. Deliberately NOT a
 * `data/MediaItem` --- the detail grid needs only identity + ordering +
 * pixels, and the viewer hand-off synthesizes the full [MediaItem] at
 * tap time (see `PersonDetailViewModel`), so the People domain keeps its
 * own minimal shape.
 *
 * [fileId] is the MediaStore `_id` of a JPEG on the external-primary
 * volume (the only kind the r23/r24 pipeline indexes); [uri] is the
 * content URI anchored by that id (ADR-0006);
 * [dateAddedSec]/[width]/[height] come from the same MediaStore row
 * (`0`/`null` = unknown, the same convention `MediaItem` pins).
 */
data class PersonPhoto(
    val fileId: Long,
    val uri: Uri,
    val dateAddedSec: Long?,
    val width: Int,
    val height: Int,
)

/**
 * The PURE SQL-parameters discipline behind the People photo query (r25):
 * SQLite's classic bound-variable limit (SQLITE_MAX_VARIABLE_NUMBER,
 * 999 before SQLite 3.32) means an `_ID IN (?,?,...)` selection over a
 * whole cluster must be split into chunks of at most [DEFAULT_LIMIT]
 * placeholders, one query per chunk.
 *
 * [chunkIds] is the tested half: deduplicate (one photo can contribute
 * SEVERAL faces to one cluster --- the grid shows each photo once),
 * order-preserving, then cut into chunks of at most [limit]. [inClause]
 * builds the matching `"_ID IN (?,?,…)"` selection for a chunk's size.
 *
 * Pure Kotlin over plain values; JVM-tested in
 * `MediaStoreSelectionChunkingTest` (ADR-0016).
 */
object MediaStoreSelection {

    /**
     * The chunk bound: 999 bound variables per statement, the classic
     * SQLite default the platform's provider still honors conservatively.
     */
    const val DEFAULT_LIMIT = 999

    /**
     * Splits [fileIds] into id chunks of at most [limit] entries:
     * duplicates removed (first occurrence keeps its position), order
     * otherwise preserved. An empty input yields no chunks; a
     * non-positive [limit] is coerced to 1 (one id per statement --- the
     * degenerate but still-correct shape, pinned by tests).
     */
    fun chunkIds(fileIds: List<Long>, limit: Int = DEFAULT_LIMIT): List<List<Long>> {
        val bound = limit.coerceAtLeast(1)
        val distinct = fileIds.distinct()
        if (distinct.isEmpty()) return emptyList()
        return distinct.chunked(bound)
    }

    /**
     * The `"_ID IN (?,?,…)"` selection with [count] placeholders for one
     * chunk produced by [chunkIds]; the caller binds the chunk's ids as
     * selection args (never interpolated into SQL). [column] defaults to
     * the literal `"_id"` --- the value of
     * `MediaStore.MediaColumns._ID`, spelled here as a compile-time
     * constant so this PURE object never loads an android.* class on the
     * JVM test classpath.
     */
    fun inClause(count: Int, column: String = "_id"): String =
        "$column IN (" + List(count.coerceAtLeast(1)) { "?" }.joinToString(",") + ")"
}

/**
 * The production [PersonPhoto] query (r25): maps a cluster's MediaStore
 * file ids to rows through the Images collection, chunked by
 * [MediaStoreSelection] (the ~999 bound-variable limit).
 *
 * Android-adjacent glue, untested per ADR-0016 (the chunking half it
 * shares with the repository is the pure, tested [MediaStoreSelection]):
 *  - the query follows `MediaStoreSource`'s house style --- a projection
 *    of `_ID`/`MIME_TYPE`/`DATE_ADDED`/`WIDTH`/`HEIGHT`, `getColumnIndexOrNull`
 *    fallbacks for columns a cursor may lack, and the r19 guarded-retry
 *    against pre-Q providers that reject the pixel-dimension columns
 *    (the retry drops WIDTH/HEIGHT, rows then map `0`/`0` --- the same
 *    honest "unknown" the house source maps);
 *  - one photo per row (the caller deduped the ids), sorted NEWEST FIRST
 *    (`date_added` descending, `_id` tiebreak --- the same total order
 *    `TimelineSort` pins for every other media grid);
 *  - photos whose MediaStore row is gone (deleted/trashed/vault-moved)
 *    simply do not come back --- their face rows still sit in the
 *    cluster, which the r24 queue's orphan pass cleans up in its own
 *    time; this query never fabricates rows.
 *
 * Returns the binding the [PeopleRepository] receives as its
 * `queryPhotos` seam parameter (pure JVM tests inject fakes instead).
 */
fun queryPersonPhotos(
    resolver: ContentResolver,
    fileIds: List<Long>,
): List<PersonPhoto> {
    val out = HashMap<Long, PersonPhoto>(fileIds.size)
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
        rows.forEach { row -> out[row.fileId] = row }
    }
    // Newest first, _id tiebreak --- TimelineSort's total order discipline.
    return out.values.sortedWith(
        compareByDescending<PersonPhoto> { it.dateAddedSec ?: 0L }.thenByDescending { it.fileId },
    )
}

private fun queryChunk(
    resolver: ContentResolver,
    selection: String,
    args: Array<String>,
    withDimensions: Boolean,
): List<PersonPhoto> {
    val projection = mutableListOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DATE_ADDED,
    )
    if (withDimensions) {
        projection += MediaStore.Images.ImageColumns.WIDTH
        projection += MediaStore.Images.ImageColumns.HEIGHT
    }
    val out = ArrayList<PersonPhoto>(args.size)
    resolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection.toTypedArray(),
        selection,
        args,
        null,
    )?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        val dateCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.DATE_ADDED)
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
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            out.add(
                PersonPhoto(
                    fileId = id,
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    ),
                    dateAddedSec = if (dateCol >= 0) cursor.getLong(dateCol) else null,
                    width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                    height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
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
