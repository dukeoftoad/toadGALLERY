package io.github.dukeoftoad.gallery.data.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Decrypts bounded, in-memory thumbnails for the vault list (issue #58).
 *
 * The vault list renders one small image per entry; before this slice it was
 * text-only by design (the old `VaultScreen` KDoc's "SAFE v1" note) because
 * plaintext frames must never touch disk. This decoder keeps that rule:
 *
 *  - **Photos**: unseal straight into a [BufferedInputStream] over the
 *    [VaultCrypto.decryptFrom] stream and let [BitmapFactory] decode with the
 *    [VaultThumbnailPolicy.inSampleSize] downsample --- bounded OUTPUT pixels
 *    (~256px edge), never the whole original buffer. Two passes over one
 *    mark/reset'd header buffer: bounds first, then the real decode.
 *  - **Videos**: `MediaMetadataRetriever` cannot read a stream, so the
 *    plaintext streams into ONE temporary file inside app-private
 *    `cacheDir` (never external storage), a frame is extracted, and the temp
 *    file is deleted in a `finally` RIGHT AFTER the decode --- the
 *    issue-#58 hard requirement: no plaintext vault media persists on disk.
 *    A crash mid-extract can leave one temp file; it lives only in the
 *    system-managed private cache and [cleanStaleTempFiles] removes the
 *    leftover on the next unlock before any new decode.
 *  - **Degradation, honestly**: per-item failures --- unsupported codecs
 *    (`getFrameAtTime` returning null, the documented
 *    `videoFrame is a NULL pointer` JNI log class of failures, see
 *    `ui/media/MediaThumb.kt`'s issue-#50 note), oversized/heap-exhausting
 *    blobs, tampered blobs (GCM tag fails on the final read) --- return null
 *    and the UI shows the same placeholder plate the gallery grid uses.
 *    A `Throwable` is caught PER ITEM: one bad blob never takes the listing
 *    down, and an OOM decoding one entry does not crash the app.
 *
 * Thin by design (the Android seam of [VaultThumbnailPolicy], like
 * `AndroidVaultKeys` vs `VaultCrypto`): no branching logic beyond the
 * photo/video split; NOT JVM-unit-tested (Robolectric has no real decoder;
 * ADR-0016), and the caller ([VaultRepository]) runs everything on
 * `Dispatchers.IO` with bounded concurrency.
 *
 * Privacy (ADR-0006/ADR-0010/ADR-0014): decrypted pixels live in this
 * process's memory only, temp plaintext is app-private and immediately
 * deleted, and the app holds no INTERNET permission --- nothing can leave
 * the device.
 */
class VaultThumbnailDecoder(
    private val cacheDir: File,
    private val store: VaultStore,
) {

    /**
     * One decoded, size-bounded thumbnail for [entry], or null on ANY
     * per-item failure (see the class KDoc's degradation contract).
     */
    fun decode(entry: VaultIndexEntry): Bitmap? = try {
        if (entry.isVideo) decodeVideoFrame(entry) else decodePhoto(entry)
    } catch (t: Throwable) {
        // Honest degradation: log nothing (no plaintext in logcat), no crash.
        null
    }

    /**
     * Deletes stale `vault-thumb-*.tmp` plaintext leftovers (a process death
     * mid-extraction). Called once before each unlock's decode batch; files
     * in `cacheDir` are app-private and the OS may purge them anyway, this
     * just makes the no-plaintext-persists rule hold proactively.
     */
    fun cleanStaleTempFiles() {
        cacheDir.listFiles()
            ?.filter { it.isFile && VaultThumbnailPolicy.isTempFileName(it.name) }
            ?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------
    // Photos: decode downsampled straight off the unseal stream
    // ------------------------------------------------------------------

    private fun decodePhoto(entry: VaultIndexEntry): Bitmap? {
        store.open(entry).use { plain ->
            val buffered = BufferedInputStream(plain, HEADER_BUFFER_BYTES)
            // Pass 1: dimensions only, rewound via mark/reset inside the
            // header buffer (JPEG/PNG/WebP dimensions live near the start).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            buffered.mark(HEADER_BUFFER_BYTES)
            BitmapFactory.decodeStream(buffered, null, bounds)
            val resetOk = runCatching { buffered.reset() }.isSuccess
            if (!resetOk) return null
            // Pass 2: the real decode, downsampled so only bounded pixels
            // enter the heap even for a 48 MP original.
            val options = BitmapFactory.Options().apply {
                inSampleSize = VaultThumbnailPolicy.inSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                )
            }
            return BitmapFactory.decodeStream(buffered, null, options)?.let(::scaleToTarget)
        }
    }

    // ------------------------------------------------------------------
    // Videos: decrypt to a private temp file, grab one frame, delete NOW
    // ------------------------------------------------------------------

    private fun decodeVideoFrame(entry: VaultIndexEntry): Bitmap? {
        val temp = File(cacheDir, VaultThumbnailPolicy.tempFileName(entry.id))
        try {
            FileOutputStream(temp).use { output ->
                store.open(entry).use { plain -> plain.copyTo(output) }
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(temp.absolutePath)
                // OPTION_CLOSEST_SYNC is the cheap keyframe grab; the exact
                // frame would force a decode of the preceding GOP.
                return retriever.getFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )?.let(::scaleToTarget)
            } finally {
                retriever.release()
            }
        } finally {
            // Issue #58 HARD rule: plaintext never persists. Deleted in a
            // finally immediately after the frame extraction, success or not.
            temp.delete()
        }
    }

    // ------------------------------------------------------------------
    // Shared: bound the longest edge to the policy target
    // ------------------------------------------------------------------

    /**
     * Bounds the decoded bitmap's longest edge to the policy target
     * (getFrameAtTime and pass-2 photo decodes can still exceed it),
     * recycling the oversized original. A vault blob can be far larger than
     * the heap --- but the THUMBNAIL never is, by construction here.
     */
    private fun scaleToTarget(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= VaultThumbnailPolicy.TARGET_EDGE_PX) return bitmap
        val scale = VaultThumbnailPolicy.TARGET_EDGE_PX.toFloat() / longest
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private companion object {
        /**
         * Header lookahead for the bounds pass: image dimensions live within
         * the first 64 KiB for every format the platform decoder handles;
         * a pathological header beyond it just fails the mark/reset and the
         * item degrades to its placeholder.
         */
        const val HEADER_BUFFER_BYTES = 64 * 1024
    }
}
