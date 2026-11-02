package io.github.dukeoftoad.gallery.data.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import io.github.dukeoftoad.gallery.data.diag.DiagLog
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
 *    mark/reset'd header buffer: bounds first, then the real decode, then a
 *    tail drain to EOF: [BitmapFactory.decodeStream] stops at end-of-image
 *    and closing a `CipherInputStream` does NOT run `doFinal`, so the drain
 *    (not the decode, not the close) is the read that verifies the trailing
 *    GCM tag --- without it a tampered or truncated blob could publish
 *    pixels with NO authentication failure. A drain failure degrades to
 *    the same placeholder as every other tier.
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
 *    down, and an OOM decoding one entry does not crash the app. W1: every
 *    fallback decision point logs one INFO line (tag "ToadVault") with the
 *    entry id and the tier that failed --- ids only, never names, never
 *    plaintext; the placeholder stays the only visible consequence.
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
     * per-item failure (see the class KDoc's degradation contract). W1:
     * every null outcome leaves one INFO line (entry id + failed tier) in
     * [DiagLog] --- no plaintext, no names, no stack frames.
     */
    fun decode(entry: VaultIndexEntry): Bitmap? {
        val tier = if (entry.isVideo) TIER_VIDEO_FRAME else TIER_PHOTO
        return try {
            if (entry.isVideo) decodeVideoFrame(entry) else decodePhoto(entry)
        } catch (t: Throwable) {
            // Honest degradation: no crash, no plaintext in logcat --- the
            // throwable's class only (ids and tiers, that is the contract).
            DiagLog.i(
                TAG,
                "thumb: id=${entry.id} tier=$tier threw ${t.javaClass.name} -> placeholder",
            )
            null
        }
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
            if (!resetOk) {
                // W1: the header lookahead blew past the mark --- degrade.
                DiagLog.i(TAG, "thumb: id=${entry.id} tier=$TIER_PHOTO_HEADER -> placeholder")
                return null
            }
            // Pass 2: the real decode, downsampled so only bounded pixels
            // enter the heap even for a 48 MP original.
            val options = BitmapFactory.Options().apply {
                inSampleSize = VaultThumbnailPolicy.inSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                )
            }
            val decoded = BitmapFactory.decodeStream(buffered, null, options)
            if (decoded == null) {
                // W1: the platform decoder refused the authenticated bytes.
                DiagLog.i(TAG, "thumb: id=${entry.id} tier=$TIER_PHOTO_DECODE -> placeholder")
                return null
            }
            // The tail drain: decodeStream stops at end-of-image, so the
            // trailing GCM tag was never consumed --- and closing a
            // CipherInputStream does NOT run doFinal. Reading to EOF inside
            // this same `use` block is the ONLY step that authenticates the
            // tail; any throw there (AEADBadTagException, surfaced by the
            // cipher stream as an IOException) means the blob was tampered
            // or truncated, and the decoded pixels must NOT be published ---
            // the class's tamper contract says placeholder, never
            // half-truth.
            return try {
                val drain = ByteArray(DRAIN_BUFFER_BYTES)
                while (buffered.read(drain) > 0) {
                    // The final read is the authentication; nothing to do
                    // with the drained bytes (they are tag/padding only).
                }
                scaleToTarget(decoded)
            } catch (e: Exception) {
                DiagLog.i(TAG, "thumb: id=${entry.id} tier=$TIER_PHOTO_TAIL -> placeholder")
                null
            }
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
                val frame = retriever.getFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                if (frame == null) {
                    // W1: the documented `videoFrame is a NULL pointer`
                    // class of failures --- tier video-frame, placeholder.
                    DiagLog.i(TAG, "thumb: id=${entry.id} tier=$TIER_VIDEO_FRAME -> placeholder")
                    return null
                }
                return scaleToTarget(frame)
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
        /** Diagnostics tag shared by the vault's Android seams (W1). */
        const val TAG = "ToadVault"

        /** Photo tier: the outer per-item catch's coarse label (W1). */
        const val TIER_PHOTO = "photo"

        /** Photo tier: the bounds pass's header mark/reset failed (W1). */
        const val TIER_PHOTO_HEADER = "photo-header"

        /** Photo tier: the downsampled decode itself failed (W1). */
        const val TIER_PHOTO_DECODE = "photo-decode"

        /**
         * Photo tier: the post-decode tail drain failed GCM authentication
         * --- the blob was tampered or truncated past end-of-image (W1).
         */
        const val TIER_PHOTO_TAIL = "photo-tail"

        /** Video tier: the frame extraction returned no frame (W1). */
        const val TIER_VIDEO_FRAME = "video-frame"

        /**
         * Header lookahead for the bounds pass: image dimensions live within
         * the first 64 KiB for every format the platform decoder handles;
         * a pathological header beyond it just fails the mark/reset and the
         * item degrades to its placeholder.
         */
        const val HEADER_BUFFER_BYTES = 64 * 1024

        /**
         * The tail-drain buffer: small on purpose --- the drain exists only
         * to push the cipher stream through its final GCM-tag read, never
         * to hold the (tag-only) trailing bytes anywhere.
         */
        const val DRAIN_BUFFER_BYTES = 8 * 1024
    }
}
