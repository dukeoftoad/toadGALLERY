package io.github.dukeoftoad.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi

/**
 * Reads the on-device media timeline (Phase 3, issues #25 and #29).
 *
 * URI-first per ADR-0006: every item is anchored by its MediaStore `_id` and
 * surfaced as a content `Uri`. No filesystem paths ever leave this class.
 *
 * v1 scope: cursor walks over `MediaStore.Images.Media` + `MediaStore.Video.Media`
 * (timeline, bucket aggregation, per-bucket detail). The Phase 3 paging data
 * layer (windowed timeline queries, issue #28) lives in a dedicated region at
 * the bottom of this class; the UI-side incremental timeline and Moments
 * grouping are explicit later-Phase-3 follow-ups.
 *
 * Privacy (ADR-0014 / ADR-0010): this class only reads local content URIs.
 * It does not, and cannot, perform any network access --- the app holds no
 * INTERNET permission, so the OS refuses every socket the process could
 * open (kernel-enforced; see `PrivacyEgressTest`). Nothing here depends on
 * image-loader packaging for that guarantee.
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

    // ------------------------------------------------------------------
    // Paging 3 (issue #28): windowed timeline queries; issue #38 adds the
    // timeline change observer that drives generational invalidation.
    //
    // Additive, self-contained region --- the snapshot methods above are
    // untouched. This is the data half of the incremental timeline:
    // [WindowPagingSource] calls [queryTimelineWindow] per page on API 30+;
    // older devices keep the full [queryTimeline] snapshot behind the same
    // pager. The observer pair at the bottom hands MediaStore change
    // notifications to the ViewModel, which debounces them into
    // `PagingSource.invalidate()` calls. The row mapping below is kept small
    // and inline on purpose (see its KDoc for the grid-UX extension plan).
    // ------------------------------------------------------------------

    /**
     * Whether this device can serve the timeline in windows (issue #28).
     *
     * The Bundle-argument query protocol --- `QUERY_ARG_LIMIT` /
     * `QUERY_ARG_OFFSET` over the unified `MediaStore.Files` collection ---
     * is only guaranteed from API 30 (R). Earlier releases either lack the
     * unified Files view of images+videos or do not reliably honor the
     * paging arguments, so they stay on the full-snapshot timeline.
     *
     * This doubles as [queryTimelineWindow]'s runtime tripwire, but call
     * sites guarding that `@RequiresApi(R)` method must use a lexically
     * lint-visible `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R` check
     * instead --- NewApi lint cannot see through this indirection.
     */
    fun supportsWindowedQueries(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Returns the timeline window `[offset, offset + limit)`, newest first
     * (issue #28). This is the window backend of [WindowPagingSource] on
     * API 30+; the caller must check [supportsWindowedQueries] first ---
     * pre-30 devices page over the full [queryTimeline] snapshot instead.
     *
     * Unlike the snapshot path, this is a SINGLE query over the unified
     * `MediaStore.Files` collection (images and videos share one table
     * under scoped storage), filtered to image/video `media_type` --- no
     * client-side merge, so the offset arithmetic is exact.
     *
     * ## Window stability: the `_id` tiebreak is REQUIRED
     * The sort order is `date_added DESC, _id DESC`. Many rows share one
     * `date_added` second (burst shots, screenshots), and a window boundary
     * can fall between them; without a total order the provider would be
     * free to reorder tied rows between windows, silently skipping or
     * duplicating items across pages. The tiebreak pins every window to
     * the same sequence.
     *
     * ## Sort / paging arguments
     * Passed via the Bundle-args `ContentResolver.query` overload with
     * `QUERY_ARG_LIMIT` + `QUERY_ARG_OFFSET` and the raw
     * `QUERY_ARG_SQL_SORT_ORDER` escape hatch (spelling the total order out
     * instead of relying on how a provider composes `QUERY_ARG_SORT_COLUMNS`
     * + `QUERY_ARG_SORT_DIRECTION`). All three keys live on [ContentResolver]
     * --- the `MediaStore.QUERY_ARG_*` names do not exist in the API, so
     * the ContentResolver constants are used directly. MediaProvider's
     * defaults exclude pending and trashed rows, which is what a gallery
     * wants to show anyway.
     *
     * ## URI shape
     * Rows surface as `images/<id>` / `video/<id>` content URIs rather than
     * the queried `files/<id>` form: the Files row id IS the per-type
     * collection id (one table), so windowed items are byte-identical to
     * the snapshot path's --- same LazyGrid keys, same Coil cache entries
     * --- and ADR-0006 still holds (content URIs anchored by `_id`, no
     * filesystem paths).
     *
     * ## Grid-UX metadata (landed at integration)
     * `MediaItem` carries `width`, `height` and `durationMs` (trailing
     * default-valued params) for the aspect-ratio-aware staggered grid
     * (issue #34) and the video duration overlay (issue #33). This window
     * projects and maps exactly the same columns with the same 0-fallback
     * contract as the snapshot path's [queryCollection], so paged and
     * snapshot items are identical in content --- the staggered grid and
     * the overlay behave identically on both paging paths.
     *
     * @param offset zero-based window start in the ordered timeline; Paging
     *  keys are always `>= 0` (see [WindowPagingSource]).
     * @param limit requested window size; must be positive (Paging 3 load
     *  sizes always are).
     */
    @RequiresApi(Build.VERSION_CODES.R) // static contract for lint; the
    // check() below is the runtime tripwire for unguarded callers
    fun queryTimelineWindow(offset: Int, limit: Int): List<MediaItem> {
        check(supportsWindowedQueries()) {
            "Windowed MediaStore queries need API 30+; use queryTimeline() on older devices."
        }
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Images.ImageColumns.WIDTH,
            MediaStore.Images.ImageColumns.HEIGHT,
            MediaStore.Video.VideoColumns.DURATION,
        )
        val queryArgs = Bundle().apply {
            // The bundle form of the media-type selection: images + videos
            // only, straight from the files table's own constants.
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, " +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})",
            )
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            // Stable total order; the _id tiebreak is REQUIRED for window
            // alignment (see method KDoc).
            putString(
                ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "date_added DESC, _id DESC",
            )
        }
        val out = ArrayList<MediaItem>(limit.coerceIn(1, 256))
        context.contentResolver
            .query(collection, projection, queryArgs, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val mimeCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.MIME_TYPE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                // Grid-UX metadata (issues #33/#34): the same columns and the same
                // 0-fallback contract as [queryCollection], so paged and snapshot
                // items are identical in content. duration is only meaningful for
                // video rows; image rows read 0 ("unknown").
                val widthCol = cursor.getColumnIndexOrNull(MediaStore.Images.ImageColumns.WIDTH)
                val heightCol = cursor.getColumnIndexOrNull(MediaStore.Images.ImageColumns.HEIGHT)
                val durationCol = cursor.getColumnIndexOrNull(MediaStore.Video.VideoColumns.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val isVideo = cursor.getInt(typeCol) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                    out += MediaItem(
                        id = id,
                        // Canonical per-type URI (== the snapshot path's), not
                        // files/<id>: see the method KDoc "URI shape" section.
                        uri = ContentUris.withAppendedId(
                            if (isVideo) {
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            } else {
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            },
                            id,
                        ),
                        // Same null-mime fallback as queryCollection: some
                        // HEIC/RAW rows carry no MIME_TYPE, so derive the
                        // generic image/video mimetype from media_type.
                        mimeType = mime ?: if (isVideo) "video/*" else "image/*",
                        dateAddedSec = cursor.getLong(dateCol),
                        isVideo = isVideo,
                        width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        durationMs = if (isVideo && durationCol >= 0) cursor.getLong(durationCol) else 0L,
                    )
                }
            }
        return out
    }

    /**
     * Registers an observer for timeline changes (issue #38, finding 1) and
     * returns it so the owner can unregister later. API 30+ only, like the
     * windowed protocol it serves; below 30 the memoized snapshot cannot
     * drift mid-generation, so those devices keep the documented snapshot
     * behavior (see the owner, `PhotosViewModel`, for the gating).
     *
     * ## Why the unified Files collection + `notifyForDescendants = true`
     * MediaProvider reports row writes on the per-type collection URIs
     * (`images/media`, `video/media`) --- descendants of the unified
     * `MediaStore.Files` URI that [queryTimelineWindow] reads. Observing
     * the Files root WITHOUT descendant notification would miss nearly
     * every real write (camera shot, download, delete), so the observer is
     * registered for the whole subtree.
     *
     * ## Why the callback must invalidate, never append
     * LIMIT/OFFSET windows are positional: after any insert/delete above
     * the current position, the offsets of later windows no longer tile the
     * new dataset, so an appended "next window" could re-emit an item the
     * grid already shows --- a duplicate `uri.toString()` LazyGrid key,
     * which foundation lazy grids throw on. The callback exists to drive
     * `PagingSource.invalidate()`: invalidation ends the generation and the
     * Pager builds a fresh source whose refresh re-anchors via
     * `getRefreshKey`, so every window of the new generation is computed
     * against one consistent dataset. (The owner debounces the
     * notifications because observers fire per changed row during
     * device-wide media scans.)
     *
     * @param onChange invoked on the main looper (the observer's Handler)
     *  whenever MediaStore changes; must be cheap and non-blocking.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    fun registerTimelineObserver(onChange: () -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // `selfChange` is always false for observers of another
                // process's notifications; ignore it and always signal.
                onChange()
            }
        }
        context.contentResolver.registerContentObserver(
            // The same unified collection [queryTimelineWindow] reads
            // (`content://media/external/files`), so the observer sees every
            // write the windows could drift against. MediaStore.Files is
            // API 29+, hence the R contract above.
            MediaStore.Files.getContentUri("external"),
            // notifyForDescendants: see the KDoc --- per-type write URIs are
            // descendants of the Files root this timeline queries.
            true,
            observer,
        )
        return observer
    }

    /**
     * Unregisters an observer previously returned by
     * [registerTimelineObserver]. Safe to call from teardown paths
     * (`ViewModel.onCleared`); re-unregistering an already-released
     * observer is a framework no-op.
     */
    fun unregisterTimelineObserver(observer: ContentObserver) {
        context.contentResolver.unregisterContentObserver(observer)
    }
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
