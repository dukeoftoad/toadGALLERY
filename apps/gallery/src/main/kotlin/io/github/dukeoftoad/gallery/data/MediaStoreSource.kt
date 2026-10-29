package io.github.dukeoftoad.gallery.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * Reads the on-device media timeline (Phase 3, issue #25).
 *
 * URI-first per ADR-0006: every item is anchored by its MediaStore `_id` and
 * surfaced as a content `Uri`. No filesystem paths ever leave this class.
 *
 * v1 scope: a single cursor walk over `MediaStore.Images.Media` UNION
 * `MediaStore.Video.Media`, ordered newest-first. Paging 3, async source,
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

    private fun queryCollection(collection: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val out = ArrayList<MediaItem>(128)
        // `_ID` is required; MIME_TYPE may be null for some HEIC/RAW captures
        // so we fall back to the generic image/video mimetype.
        val fallbackMime = if (isVideo) "video/*" else "image/*"
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val mimeCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                val dateAdded = cursor.getLong(dateCol)
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    mimeType = mime ?: fallbackMime,
                    dateAddedSec = dateAdded,
                    isVideo = isVideo,
                )
            }
        }
        return out
    }

    private fun android.database.Cursor.getColumnIndexOrNull(name: String): Int =
        try { getColumnIndexOrThrow(name) } catch (_: IllegalArgumentException) { -1 }
}
