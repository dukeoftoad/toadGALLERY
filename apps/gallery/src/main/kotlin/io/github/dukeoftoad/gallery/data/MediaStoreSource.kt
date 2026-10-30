package io.github.dukeoftoad.gallery.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Reads the on-device media timeline (Phase 3, issues #25 and #29).
 *
 * URI-first per ADR-0006: every item is anchored by its MediaStore `_id` and
 * surfaced as a content `Uri`. No filesystem paths ever leave this class.
 *
 * v1 scope: cursor walks over `MediaStore.Images.Media` + `MediaStore.Video.Media`
 * (timeline, bucket aggregation, per-bucket detail). Paging 3, async source,
 * and Moments grouping are explicit later-Phase-3 follow-ups.
 *
 * Privacy (ADR-0014 / ADR-0010): this class only reads local content URIs.
 * It does not, and cannot, perform any network access --- the app holds no
 * INTERNET permission and Coil is wired without its network artifact.
 */
class MediaStoreSource(private val context: Context) {

    /**
     * Returns every image and video on the device, newest-first.
     *
     * Called off the main thread by the ViewModel. Returns an empty list when
     * the user has granted access but has no media, or when the cursor is
     * empty for any reason --- the caller distinguishes "empty library" from
     * "permission denied" via the permission state, not via this result.
     */
    fun queryTimeline(): List<MediaItem> {
        val out = ArrayList<MediaItem>(256)
        out += queryCollection(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            isVideo = false,
        )
        out += queryCollection(
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            isVideo = true,
        )
        return out.sortedByDescending { it.dateAddedSec }
    }

    /**
     * Returns every folder/bucket that holds at least one image or video,
     * most recently updated first (issue #29).
     *
     * The same bucket can appear in both the Images and Video collections;
     * this merges them into a single `Album` with a combined count and the
     * newest item as the cover thumbnail.
     */
    fun queryAlbums(): List<Album> {
        data class BucketAcc(
            var name: String,
            var count: Int,
            var latestSec: Long,
            var coverUri: Uri,
        )

        val buckets = HashMap<Long, BucketAcc>(64)
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        ).forEach { (collection, isVideo) ->
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
            )
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val bucketCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.BUCKET_ID)
                val nameCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                while (cursor.moveToNext()) {
                    if (bucketCol < 0) break
                    val id = cursor.getLong(idCol)
                    val bucketId = cursor.getLong(bucketCol)
                    val bucketName = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val dateAdded = cursor.getLong(dateCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val acc = buckets[bucketId]
                    if (acc == null) {
                        buckets[bucketId] = BucketAcc(
                            name = bucketName ?: "", // resolved later from the other collection
                            count = 1,
                            latestSec = dateAdded,
                            coverUri = uri,
                        )
                    } else {
                        acc.count += 1
                        // A bucket name is sometimes null for one collection
                        // (e.g. screenshots without EXIF) but present in the
                        // other; keep the first non-empty name seen.
                        if (acc.name.isEmpty() && !bucketName.isNullOrEmpty()) {
                            acc.name = bucketName
                        }
                        if (dateAdded > acc.latestSec) {
                            acc.latestSec = dateAdded
                            acc.coverUri = uri
                        }
                    }
                }
            }
        }
        return buckets.map { (bucketId, acc) ->
            Album(
                bucketId = bucketId,
                bucketName = acc.name.ifEmpty { "Album" },
                itemCount = acc.count,
                coverUri = acc.coverUri,
                latestDateSec = acc.latestSec,
            )
        }.sortedByDescending { it.latestDateSec }
    }

    /**
     * Returns the contents of one bucket, newest-first, plus its display name
     * (issue #29 detail view). The name comes from the bucket's own rows, so
     * the detail screen needs no extra lookup.
     */
    fun queryBucket(bucketId: Long): BucketContent {
        val items = ArrayList<MediaItem>(64)
        var name: String? = null
        listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true,
        ).forEach { (collection, isVideo) ->
            items += queryCollection(
                collection = collection,
                isVideo = isVideo,
                selection = "${MediaStore.MediaColumns.BUCKET_ID} = ?",
                selectionArgs = arrayOf(bucketId.toString()),
                onRow = { cursorRow -> if (name.isNullOrEmpty()) name = cursorRow },
            )
        }
        return BucketContent(
            bucketName = name ?: "Album",
            items = items.sortedByDescending { it.dateAddedSec },
        )
    }

    private fun queryCollection(
        collection: Uri,
        isVideo: Boolean,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        onRow: ((String?) -> Unit)? = null,
    ): List<MediaItem> {
        // Pixel dimensions (both collections) feed the aspect-ratio-aware
        // staggered grid (issue #34); the duration column (Video collection
        // only) feeds the duration overlay (issue #33).
        //
        // "width"/"height" are plain MediaStore string columns, available far
        // below the API 29 `MediaColumns` aliases and honored by both
        // collections because they share the same underlying files table;
        // "duration" has been a `Video.VideoColumns` column since API 1. All
        // reads go through `getColumnIndexOrNull`, so a missing column simply
        // yields 0 ("unknown") instead of failing.
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Images.ImageColumns.WIDTH,
            MediaStore.Images.ImageColumns.HEIGHT,
        ) + if (isVideo) {
            arrayOf(MediaStore.Video.VideoColumns.DURATION)
        } else {
            emptyArray<String>()
        }
        val out = ArrayList<MediaItem>(128)
        // `_ID` is required; MIME_TYPE may be null for some HEIC/RAW captures
        // so we fall back to the generic image/video mimetype.
        val fallbackMime = if (isVideo) "video/*" else "image/*"
        context.contentResolver
            .query(collection, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val mimeCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val nameCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val widthCol = cursor.getColumnIndexOrNull(MediaStore.Images.ImageColumns.WIDTH)
                val heightCol = cursor.getColumnIndexOrNull(MediaStore.Images.ImageColumns.HEIGHT)
                // Only the Video collection projects the duration column; -1
                // keeps every image row at durationMs = 0 ("unknown").
                val durationCol = if (isVideo) {
                    cursor.getColumnIndexOrNull(MediaStore.Video.VideoColumns.DURATION)
                } else {
                    -1
                }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    val dateAdded = cursor.getLong(dateCol)
                    val bucketName = if (nameCol >= 0) cursor.getString(nameCol) else null
                    onRow?.invoke(bucketName)
                    out += MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        mimeType = mime ?: fallbackMime,
                        dateAddedSec = dateAdded,
                        isVideo = isVideo,
                        width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                    )
                }
            }
        return out
    }

    private fun android.database.Cursor.getColumnIndexOrNull(name: String): Int =
        try { getColumnIndexOrThrow(name) } catch (_: IllegalArgumentException) { -1 }
}

/**
 * Contents of one MediaStore bucket, for the album detail view (issue #29).
 * The name is taken from the bucket's own rows; `"Album"` is the fallback
 * when MediaStore has no display name for the folder.
 */
data class BucketContent(
    val bucketName: String,
    val items: List<MediaItem>,
)
