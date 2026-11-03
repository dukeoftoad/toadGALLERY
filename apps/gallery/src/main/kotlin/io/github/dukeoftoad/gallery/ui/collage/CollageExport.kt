package io.github.dukeoftoad.gallery.ui.collage

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.collage.CollageLayouts
import io.github.dukeoftoad.gallery.data.collage.CollageTemplate
import io.github.dukeoftoad.gallery.data.collage.PixelRect
import io.github.dukeoftoad.gallery.data.copyDisplayName
import java.io.IOException

/**
 * The collage creator's ANDROID seam (r27): the two operations the pure
 * [CollageLayouts] math cannot be --- decoding the photos into software
 * bitmaps and saving the rendered collage as a new MediaStore image.
 *
 * Everything decision-shaped lives in the pure core (slot rects, pixel
 * scaling, center-crop source rects, the name-collision rule via
 * [copyDisplayName]); this file only converts those decisions into
 * loader calls, Canvas draw calls and provider inserts, mirroring the
 * `MediaFileOps` discipline it deliberately borrows from:
 *  - Q+ only for the write ([RequiresApi]; the screen hides the export
 *    action below Android 10, where the app holds no write protocol);
 *  - the insert rides the same `IS_PENDING` + `RELATIVE_PATH` protocol
 *    a copy uses, into the standard Pictures directory (app-contributed
 *    media there needs no consent round-trip on Q+);
 *  - a half-written pending row is deleted on any failure (the row is
 *    app-owned; the cleanup delete needs no consent), so a failed export
 *    never leaves an invisible zombie in the gallery.
 *
 * Privacy (ADR-0006/ADR-0014): every decode is a local content URI
 * through the app's offline loader; the export writes one new local
 * image. No network path exists (no INTERNET permission).
 */

/** The bound on each decoded preview/export bitmap's longer edge, pixels. */
private const val DECODE_EDGE_PIXELS = 1024

/** The collage file's base name; [copyDisplayName] numbers collisions. */
private const val COLLAGE_BASE_NAME = "Collage.jpg"

/**
 * Decodes [photos] into SOFTWARE bitmaps (one per photo; a failed decode
 * leaves its slot `null` --- the honest absent answer, never a fake
 * gray stand-in). One decode pipeline serves BOTH the on-screen preview
 * and the export (see `CollageViewModel`'s KDoc).
 */
internal fun decodeCollageBitmaps(
    app: Application,
    photos: List<MediaItem>,
): List<Bitmap?> = photos.map { photo ->
    runCatching {
        val request = ImageRequest.Builder(app)
            .data(photo.uri)
            // Software bitmaps: the export renders into a software canvas,
            // which a hardware-pinned decode cannot be drawn onto.
            .allowHardware(false)
            // Bound the decode: a slot never shows more than a sixth of a
            // 2048px edge, so 1024 is generous headroom at a fraction of
            // the full-resolution memory.
            .size(DECODE_EDGE_PIXELS)
            .build()
        (app.imageLoader.execute(request) as? SuccessResult)
            ?.drawable
            ?.let { drawable ->
                (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            }
    }.getOrNull()
}

/**
 * Renders [bitmaps] into [template] at [CollageLayouts
 * .EXPORT_EDGE_PIXELS] and saves the JPEG as a NEW image in the
 * Pictures directory, returning its content [Uri].
 *
 * @throws IOException when a photo's bitmap is still missing (the export
 *   refuses to ship a gray box), the provider refuses the insert, or the
 *   byte write fails --- the caller narrates and this function has
 *   already cleaned up any pending row.
 */
@RequiresApi(Build.VERSION_CODES.Q)
internal fun exportCollage(
    app: Application,
    photos: List<MediaItem>,
    bitmaps: List<Bitmap?>,
    template: CollageTemplate,
): Uri {
    if (bitmaps.size != photos.size || bitmaps.any { it == null }) {
        throw IOException("not every photo decoded; refusing to export a partial collage")
    }
    val rendered = renderCollage(bitmaps.filterNotNull(), template)

    val resolver = app.contentResolver
    val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val relativePath = Environment.DIRECTORY_PICTURES
    val displayName = copyDisplayName(COLLAGE_BASE_NAME) { candidate ->
        displayNameTaken(resolver, collection, candidate, relativePath)
    }
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
    }
    val pendingUri = resolver.insert(collection, values)
        ?: throw IOException("provider refused the collage insert")
    try {
        resolver.openOutputStream(pendingUri)?.use { output ->
            if (!rendered.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                throw IOException("JPEG encoder refused the collage bytes")
            }
        } ?: throw IOException("provider offered no output stream for $pendingUri")
        val settled = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (resolver.update(pendingUri, settled, null, null) <= 0) {
            throw IOException("provider did not publish the pending collage")
        }
        return pendingUri
    } catch (e: IOException) {
        runCatching { resolver.delete(pendingUri, null, null) }
        throw e
    } catch (e: Exception) {
        runCatching { resolver.delete(pendingUri, null, null) }
        throw IOException(e.message ?: e.javaClass.simpleName)
    }
}

/**
 * The render itself (the ONLY pure-math consumer outside the tests):
 * a white square canvas, each photo CENTER-CROPPED into its slot via
 * [CollageLayouts.slotPixels] + [CollageLayouts.centerCropSource] ---
 * the same pair the on-screen preview draws with, so what the export
 * saves is what the preview showed.
 */
private fun renderCollage(bitmaps: List<Bitmap>, template: CollageTemplate): Bitmap {
    val edge = CollageLayouts.EXPORT_EDGE_PIXELS
    val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    template.slots.forEachIndexed { index, slot ->
        val bitmap = bitmaps.getOrNull(index) ?: return@forEachIndexed
        val destination = CollageLayouts.slotPixels(slot, edge, edge)
        val destinationRect = destination.toRectF()
        val source = CollageLayouts.centerCropSource(
            bitmapWidth = bitmap.width,
            bitmapHeight = bitmap.height,
            destinationWidth = destination.width,
            destinationHeight = destination.height,
        )
        canvas.drawBitmap(
            bitmap,
            source.toRect(),
            destinationRect,
            paint,
        )
    }
    return out
}

/** Whether a [displayName] is already taken in [relativePath] (the copy-naming predicate). */
private fun displayNameTaken(
    resolver: android.content.ContentResolver,
    collection: Uri,
    displayName: String,
    relativePath: String,
): Boolean = resolver.query(
    collection,
    arrayOf(MediaStore.MediaColumns._ID),
    "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND " +
        "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
    arrayOf(displayName, relativePath),
    null,
)?.use { it.count > 0 } ?: false

/** The pure seam's rect as the graphics layer's RectF. */
private fun PixelRect.toRectF(): RectF = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

/** The pure seam's rect as the graphics layer's Rect. */
private fun PixelRect.toRect(): Rect = Rect(left, top, right, bottom)
