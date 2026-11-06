package io.github.dukeoftoad.gallery.data.media.changes

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.dukeoftoad.gallery.data.Favorites
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import kotlin.coroutines.cancellation.CancellationException

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
     *
     * r40 hardening: [isCancelled] is the cooperative cancellation probe
     * the walk consults every [ScanWalkPolicy.CANCELLATION_CHECK_ROWS]
     * rows (see [ScanWalkPolicy] for why a blocking cursor loop needs one).
     * A positive probe aborts the walk with
     * [kotlin.coroutines.cancellation.CancellationException] --- no partial
     * catalogue write, the driver rethrows it untouched. The default is the
     * historic no-op so the interface change stays source-compatible.
     *
     * r51 hardening (audit E-1): a volume the provider cannot answer is a
     * WALK FAILURE, never an empty row list --- a query that hands back a
     * `null` cursor (the resolver's documented "query failed for any
     * reason") throws, so the driver's per-volume catch isolates the volume
     * and NO tombstone/checkpoint lands over the failed walk. An honest
     * empty volume arrives as a NON-null cursor with zero rows and still
     * walks (and tombstones) as before.
     */
    fun scanVolume(volumeName: String, isCancelled: () -> Boolean = { false }): List<MediaScanRow>
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
 * Projection (r19 round): every walk ALSO projects the folder/bucket
 * ride-alongs --- BUCKET_ID, BUCKET_DISPLAY_NAME --- so the catalogue's
 * v5 bucket columns can serve the timeline's folder filter without a
 * second MediaStore round trip. Both columns exist on every collection
 * walked here (the Q+ unified Files collection and the pre-Q Images and
 * Video collections have surfaced the provider's path-derived bucket
 * columns since the gallery era --- `MediaStoreSource.queryCollection`
 * projects the same pair on the same pre-Q legs today), so the pair is
 * projected UNCONDITIONALLY, the DISPLAY_NAME/WIDTH/HEIGHT pattern; a
 * provider that silently drops a column is absorbed by the
 * `getColumnIndexOrNull` reads (the row rides an honest `null` bucket,
 * never a 0 sentinel).
 *
 * DEFENSIVE pre-Q fallback (r19, a flagged audit risk): a provider that
 * REJECTS an unknown projection column throws IllegalArgumentException
 * before a cursor is ever returned. If that happens the walk retries ONCE
 * with the REDUCED projection --- the revision-contract columns only ---
 * and the ride-alongs (including the bucket pair) degrade to their honest
 * null/zero/false defaults for that whole walk. Behavior is identical
 * when the first query succeeds; when it throws, today's whole-volume
 * failure becomes a degraded-but-complete walk.
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

    override fun scanVolume(volumeName: String, isCancelled: () -> Boolean): List<MediaScanRow> {
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
                isCancelled = isCancelled,
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
                isCancelled = isCancelled,
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
                isCancelled = isCancelled,
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
     * `getColumnIndexOrNull` reads below) --- PLUS the r19 folder/bucket
     * ride-alongs `BUCKET_ID` / `BUCKET_DISPLAY_NAME` (see the class KDoc:
     * every walked collection has the pair, so it is unconditional).
     *
     * A provider that rejects a projection column outright (the flagged
     * pre-Q risk) throws IllegalArgumentException from the query itself;
     * the walk then retries ONCE with [REDUCED_PROJECTION] --- the revision
     * contract only --- and every non-contract read below degrades to its
     * honest default (null name/bucket, 0 dimensions, false favorite, null
     * token). Identical behavior when the first query succeeds.
     *
     * Transport-thin by design (ADR-0016): no interpretation of any value
     * happens here --- widths/durations coerce through the cursor's typed
     * getters exactly as `MediaStoreSource` reads them for the UI today.
     *
     * r40 hardening: [isCancelled] is consulted every
     * [ScanWalkPolicy.CANCELLATION_CHECK_ROWS] rows inside the cursor loop
     * ([ScanWalkPolicy] carries the rationale); a positive probe aborts the
     * walk before a single row is returned, let alone written.
     *
     * r51 hardening (audit E-1): a NULL cursor from the resolver (its
     * documented "query fails for any reason" answer, on either the full
     * or the reduced-projection attempt) THROWS instead of silently
     * walking zero rows --- a failed query must never be reconciled as an
     * empty volume (see the null-guard below for the mass-tombstone
     * failure that motivates it).
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
        isCancelled: () -> Boolean,
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
            // Folder/bucket ride-alongs (r19): the same pair
            // MediaStoreSource.queryCollection projects on every leg; see
            // the walk KDoc for why it is unconditional.
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
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
        // The flagged pre-Q risk, made survivable: an OEM provider that
        // rejects an unknown projection column throws before any cursor
        // exists. Retry once with the revision contract alone --- the
        // columns every provider since API 1 accepts --- and let the
        // absence-tolerant reads below degrade honestly. A retry that
        // throws too propagates to the driver's per-volume catch, exactly
        // like today's total failure.
        val queried = try {
            context.contentResolver.query(collection, projection, selection, selectionArgs, null)
        } catch (_: IllegalArgumentException) {
            context.contentResolver
                .query(collection, REDUCED_PROJECTION, selection, selectionArgs, null)
        }
        // r51 (audit E-1): a NULL cursor is the resolver's documented
        // "query failed for any reason" answer --- it must NEVER be read as
        // "the volume is empty". The pre-r51 `queried?.use` swallowed the
        // failure into an empty row list, the reconciler then classified
        // every live record of the volume REMOVED, and the driver
        // tombstoned the whole library AND saved the checkpoint over the
        // failed walk (locking the empty state in until media really
        // changed). Throwing here makes the failure the driver's
        // per-volume catch's problem instead: the volume is isolated, no
        // tombstone, no checkpoint, the next pass retries honestly.
        // IllegalStateException rather than the walk's cancellation type:
        // CancellationException is the ONE exception the driver's
        // per-volume catch RETHROWS (cancellation must kill the whole
        // pass), while an ordinary failure is isolated to this volume ---
        // exactly the E-1 outcome. An honestly empty volume is unaffected:
        // it arrives as a NON-null cursor with zero rows.
        val queriedCursor = queried ?: throw IllegalStateException(
            "MediaStore query returned a null cursor (query failed for any reason) " +
                "volume=$volumeName collection=$collection --- treating as a walk failure, " +
                "never an empty volume",
        )
        queriedCursor.use { cursor ->
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
            // Absence-tolerant too (r19): the reduced-projection fallback
            // drops this column, and an absent token must read as the
            // honest null --- never crash a walk that already survived a
            // rejected projection.
            val generationCol =
                if (includePerRowGeneration) {
                    cursor.getColumnIndexOrNull(MediaStore.MediaColumns.GENERATION_MODIFIED)
                } else {
                    -1
                }
            // Folder/bucket columns (r19): absence-tolerant like the display
            // pair, and the id read is null-AWARE --- `getLong` on a NULL
            // column coerces to 0, which is a REAL bucket id shape, so the
            // honest "not resolvable" is spelled null here, never 0.
            val bucketCol = cursor.getColumnIndexOrNull(MediaStore.MediaColumns.BUCKET_ID)
            val bucketNameCol =
                cursor.getColumnIndexOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            var rowsRead = 0
            while (cursor.moveToNext()) {
                // r40 hardening: the cooperative cancellation probe (the
                // ScanWalkPolicy KDoc carries the full rationale --- a
                // blocking cursor loop must poll or cancellation waits
                // until the whole volume has walked). The probe result
                // throws CancellationException, the one exception the
                // driver's per-volume catch rethrows, so the walk dies
                // before any catalogue write.
                rowsRead++
                if (ScanWalkPolicy.cancellationDue(rowsRead) && isCancelled()) {
                    throw CancellationException(
                        "scan walk cancelled volume=$volumeName rows=$rowsRead",
                    )
                }
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
                    // Folder/bucket ride-alongs (r19, non-revision; see
                    // the MediaScanRow r19 field-group note): the id read
                    // is null-AWARE (a NULL coerced to 0 by getLong would
                    // be a fake-but-plausible bucket id), and the name is
                    // the DATA-column-derived bucket name, never
                    // _display_name.
                    bucketId =
                    if (bucketCol >= 0 && !cursor.isNull(bucketCol)) {
                        cursor.getLong(bucketCol)
                    } else {
                        null
                    },
                    bucketDisplayName =
                    if (bucketNameCol >= 0) {
                        cursor.getString(bucketNameCol)
                    } else {
                        null
                    },
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

        /**
         * The reduced fallback projection (r19): the revision-contract
         * columns alone --- `_ID`, `MIME_TYPE`, `SIZE`, `DATE_MODIFIED`,
         * `DATE_ADDED`, every one a column the provider has accepted since
         * API 1. The one-shot retry for a provider that REJECTED the full
         * projection (see the walk's catch): the ride-alongs it drops
         * degrade to their honest null/zero/false defaults through the
         * walk's absence-tolerant reads.
         */
        private val REDUCED_PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
        )

        /** MIME fallbacks; a mime drift is bookkeeping, never a revision. */
        const val FALLBACK_MIME_IMAGES = "image/*"
        const val FALLBACK_MIME_VIDEOS = "video/*"
        const val FALLBACK_MIME_FILES = "application/octet-stream"
    }
}
