package io.github.dukeoftoad.gallery.data.media.changes

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.dukeoftoad.gallery.data.Favorites
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity

/**
 * The scan driver's row supply: which volumes exist right now and the
 * authoritative [MediaScanRow] walk per volume.
 *
 * An interface (not a concrete class) so the driver stays a pure
 * orchestrator: the production implementation is the Android-thin
 * [AndroidMediaScanRowSource], and a future test can feed synthetic rows
 * without a ContentResolver (ADR-0016's tested-logic/thin-adapter split).
 */
interface MediaScanRowSource {

    /**
     * The volume names to consider this pass (`external_primary`, volume
     * UUIDs, ...). A volume that is not mounted simply does not appear ---
     * its catalogue records stay live and its checkpoint untouched, which
     * is exactly the unmounted-removable-card semantics the tombstone
     * policy is built around.
     */
    fun volumes(): List<String>

    /**
     * The authoritative rows of [volumeName]: one [MediaScanRow] per live
     * image/video row the provider reports, in no guaranteed order (the
     * reconciler treats the list as a set keyed by [MediaScanRow
     * .localMediaKey]).
     */
    fun scanVolume(volumeName: String): List<MediaScanRow>
}

/**
 * The production [MediaScanRowSource]: a THIN MediaStore cursor walk in the
 * style of `MediaStoreSource` (the Phase 3 timeline reader), emitting the
 * Phase 4 row shape instead of UI items. Production-thin and untested per
 * ADR-0016 --- every decision about what a row MEANS lives in
 * [CatalogueReconciler]; this class only transports provider columns.
 *
 * Volume surface, by API level (mirroring the MediaChangeFeed contract
 * this driver pairs with):
 * - API 29+: the per-volume `MediaStore.Files` collection --- ONE walk per
 *   volume covers every image and video row, filtered by
 *   `MEDIA_TYPE IN (IMAGE, VIDEO)` so the scan surface matches what
 *   `MediaStoreSource` scans (not documents, not playlists).
 * - API 26-28: no volume-scoped Files Uri exists; the classic external
 *   Images + Video collections are walked under the single external volume
 *   name, and rows carry no per-row generation token (the column does not
 *   exist below R anyway).
 *
 * Projection (r13 round): besides the revision contract (_ID, MIME_TYPE,
 * SIZE, DATE_MODIFIED, DATE_ADDED, GENERATION_MODIFIED on R+), each walk
 * also projects the DISPLAY/SHAPE ride-alongs --- DISPLAY_NAME, WIDTH,
 * HEIGHT, DURATION where the collection has the column, and IS_FAVORITE
 * where the provider has it (Q+) --- so a record mirrors its last-seen
 * provider row well enough to render a timeline cell. They ride along on
 * every row and NEVER enter the reconciler's revision comparison (see the
 * MediaScanRow field-group note).
 *
 * One known one-time cost, accepted: keys below Q are `external:id` while
 * Q+ keys are `external_primary:id`, so an OS upgrade re-sights the whole
 * library as ADDED once. The tombstone/last-seen bookkeeping absorbs it,
 * and pretending otherwise would need a key aliasing layer this contract
 * deliberately does not have.
 *
 * Privacy (ADR-0014): local MediaStore reads only --- no network surface
 * exists on any type touched here, per the app-wide no-INTERNET gate.
 */
class AndroidMediaScanRowSource(
    private val context: Context,
) : MediaScanRowSource {

    override fun volumes(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: MediaStore knows its external volumes; sorted for
            // deterministic diagnostics and stable pass order.
            MediaStore.getExternalVolumeNames(context).toSet().sorted()
        } else {
            // Below Q the classic external volume is the only world there
            // is. The literal spells MediaStore.VOLUME_EXTERNAL, which as a
            // platform constant only exists from API 29 --- inlining the
            // string keeps the pre-Q leg free of NewApi noise. This is the
            // same surface MediaStoreSource's EXTERNAL_CONTENT_URI walks
            // belong to.
            listOf(PRE_Q_VOLUME_EXTERNAL)
        }

    override fun scanVolume(volumeName: String): List<MediaScanRow> {
        val out = ArrayList<MediaScanRow>(256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // The walk must never surface a TRASHED row into the catalogue:
            // the eligibility contract (MediaEligibility) excludes trash, and
            // a trashed row that entered the catalogue would be indexed and
            // dated as if it were a live library item. `IS_TRASHED` only
            // exists from R, so the filter is R-conditional --- below R the
            // platform HAD no trash column and there is nothing to exclude
            // (the Phase 3 trash screen is likewise an R+ surface).
            val mediaTypeFilter = "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ? OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
            walk(
                collection = MediaStore.Files.getContentUri(volumeName),
                volumeName = volumeName,
                selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "($mediaTypeFilter) AND ${MediaStore.MediaColumns.IS_TRASHED} = 0"
                } else {
                    mediaTypeFilter
                },
                selectionArgs = arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                ),
                fallbackMime = FALLBACK_MIME_FILES,
                includePerRowGeneration = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                // The unified Files collection carries the video duration
                // column (NULL for stills) and --- from Q, which is exactly
                // the floor of this leg --- the favorite flag.
                includeDuration = true,
                includeFavorite = true,
                out = out,
            )
        } else {
            walk(
                collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                volumeName = volumeName,
                selection = null,
                selectionArgs = null,
                fallbackMime = FALLBACK_MIME_IMAGES,
                includePerRowGeneration = false,
                // Below Q: the Images collection has no DURATION column and
                // the provider has no is_favorite column at all (favorites
                // are a Q+ surface --- `Favorites`); images therefore ride
                // with durationMs = 0 / isFavorite = false.
                includeDuration = false,
                includeFavorite = false,
                out = out,
            )
            walk(
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                volumeName = volumeName,
                selection = null,
                selectionArgs = null,
                fallbackMime = FALLBACK_MIME_VIDEOS,
                includePerRowGeneration = false,
                // The Video collection has had DURATION since API 1; still no
                // favorite column below Q.
                includeDuration = true,
                includeFavorite = false,
                out = out,
            )
        }
        return out
    }

    /**
     * One cursor walk, appended into [out]. The projection is the revision
     * contract --- `_ID` (identity), `MIME_TYPE`, `SIZE`, `DATE_MODIFIED`,
     * `DATE_ADDED` (observed revision + bookkeeping) and, on API 30+, the
     * row's OWN `GENERATION_MODIFIED` stamp ([MediaScanRow.generationToken];
     * NEVER the per-volume `MediaStore.getGeneration` checkpoint, see the
     * audit finding C note on [MediaScanRow]) --- PLUS the display/shape
     * ride-alongs the r13 timeline renders from: `DISPLAY_NAME`, `WIDTH`,
     * `HEIGHT`, and `DURATION` / `IS_FAVORITE` where the walked collection
     * has those columns ([includeDuration] / [includeFavorite] --- a
     * projection naming a column the provider rejects throws before a
     * cursor is ever returned, so absence is decided HERE, at the call
     * sites, while a column the provider silently drops is absorbed by the
     * `getColumnIndexOrNull` reads below).
     *
     * Transport-thin by design (ADR-0016): no interpretation of any value
     * happens here --- widths/durations coerce through the cursor's typed
     * getters exactly as `MediaStoreSource` reads them for the UI today.
     */
    private fun walk(
        collection: Uri,
        volumeName: String,
        selection: String?,
        selectionArgs: Array<String>?,
        fallbackMime: String,
        includePerRowGeneration: Boolean,
        includeDuration: Boolean,
        includeFavorite: Boolean,
        out: ArrayList<MediaScanRow>,
    ) {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_ADDED)
            // Display/shape ride-alongs. WIDTH/HEIGHT use the
            // Images.ImageColumns string constants --- available far below
            // the API 29 MediaColumns aliases and honored by every
            // collection here because they share the underlying files table
            // (the same convention MediaStoreSource reads them with).
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.Images.ImageColumns.WIDTH)
            add(MediaStore.Images.ImageColumns.HEIGHT)
            if (includeDuration) {
                add(MediaStore.Video.VideoColumns.DURATION)
            }
            if (includeFavorite) {
                // The literal, not MediaColumns.IS_FAVORITE: that SDK
                // constant only exists from API 30 while the column itself
                // is Q+ (see Favorites.COLUMN_IS_FAVORITE's KDoc).
                add(Favorites.COLUMN_IS_FAVORITE)
            }
            if (includePerRowGeneration) {
                add(MediaStore.MediaColumns.GENERATION_MODIFIED)
            }
        }.toTypedArray()
        context.contentResolver
            .query(collection, projection, selection, selectionArgs, null)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                // The display/shape columns go through the same
                // absence-tolerant reads MediaStoreSource uses: a column the
                // provider dropped from the projection simply yields the
                // row's zero/none/false defaults instead of failing the walk.
                val displayNameCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.DISPLAY_NAME)
                val widthCol = cursor.getColumnIndexOrNull(MediaStore.Images.ImageColumns.WIDTH)
                val heightCol = cursor.getColumnIndexOrNull(MediaStore.Images.ImageColumns.HEIGHT)
                val durationCol =
                    if (includeDuration) {
                        cursor.getColumnIndexOrNull(MediaStore.Video.VideoColumns.DURATION)
                    } else {
                        -1
                    }
                val favoriteCol =
                    if (includeFavorite) {
                        cursor.getColumnIndexOrNull(Favorites.COLUMN_IS_FAVORITE)
                    } else {
                        -1
                    }
                val generationCol =
                    if (includePerRowGeneration) {
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
                    } else {
                        -1
                    }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    // The access handle (ADR-0006 URI-first): the walked
                    // collection Uri plus the row id --- the same resolution
                    // MediaStoreSource hands the UI.
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    out += MediaScanRow(
                        localMediaKey = MediaIdentity(
                            volumeName = volumeName,
                            mediaStoreId = id,
                            canonicalUri = uri,
                        ).toKeyString(),
                        canonicalUri = uri,
                        // MIME_TYPE is nullable for some HEIC/RAW captures;
                        // a generic fallback keeps the row shape total, and
                        // a mime-only drift never counts as a revision
                        // change (the reconciler ignores it).
                        mimeType = cursor.getString(mimeCol) ?: fallbackMime,
                        sizeBytes = cursor.getLong(sizeCol),
                        // DATE_MODIFIED / DATE_ADDED are provider SECONDS;
                        // the row contract is epoch millis.
                        dateModifiedMillis = cursor.getLong(dateModifiedCol) * 1000L,
                        dateAddedMillis = cursor.getLong(dateAddedCol) * 1000L,
                        generationToken =
                        if (generationCol >= 0) {
                            cursor.getLong(generationCol).toString()
                        } else {
                            null
                        },
                        // Display/shape ride-alongs (non-revision; see the
                        // MediaScanRow field-group note): width/height are
                        // string columns read through the typed getter, a
                        // NULL duration (stills in the Files walk) reads as
                        // 0, and a missing favorite column is `false`.
                        displayName =
                        if (displayNameCol >= 0) {
                            cursor.getString(displayNameCol)
                        } else {
                            null
                        },
                        width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                        height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                        durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                        isFavorite = favoriteCol >= 0 && cursor.getInt(favoriteCol) == 1,
                    )
                }
            }
    }

    /** The MediaStoreSource absence discipline: -1 for a column this cursor does not carry. */
    private fun android.database.Cursor.getColumnIndexOrNull(name: String): Int =
        try { getColumnIndexOrThrow(name) } catch (_: IllegalArgumentException) { -1 }

    private companion object {
        /** The classic pre-Q external volume name (see [volumes]). */
        const val PRE_Q_VOLUME_EXTERNAL = "external"

        /** MIME fallbacks; a mime drift is bookkeeping, never a revision. */
        const val FALLBACK_MIME_IMAGES = "image/*"
        const val FALLBACK_MIME_VIDEOS = "video/*"
        const val FALLBACK_MIME_FILES = "application/octet-stream"
    }
}
