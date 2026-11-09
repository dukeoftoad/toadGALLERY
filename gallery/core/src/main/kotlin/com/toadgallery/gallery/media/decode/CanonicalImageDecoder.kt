package com.toadgallery.gallery.media.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix

/**
 * The canonical decoded image every AI-side consumer (face detection,
 * CLIP embedding, the pixel-parity tests) works against (Phase 4
 * contract change 6).
 *
 * **Contract, fixed by this type:**
 *  - [width] x [height] are the UPRIGHT dimensions --- EXIF rotation is
 *    already applied; consumers never see a sideways face.
 *  - [argb] is row-major ARGB_8888 with row stride [width]: pixel
 *    (x, y) is `argb[y * width + x]`, channel order A-R-G-B from the
 *    high byte down, exactly what `Bitmap.getPixels` produces and what
 *    the Ente-derived preprocessing expects after its own RGB reorder.
 *  - Colour space is ASSUMED sRGB (the device-independent default
 *    `BitmapFactory` produces when no preferred colour space is set).
 *    Wide-gamut originals are decoded through the platform's conversion;
 *    the pipeline never has to wonder which profile a pixel carries.
 *
 * Note on equality: [argb] is an `IntArray`, so the data-class `equals`
 * compares it by reference --- two `DecodedImage`s with equal content
 * are not `==`. Tests compare pixels with `assertArrayEquals`; nobody
 * in production has a reason to compare whole images.
 */
data class DecodedImage(
    /** Upright output width; matches the plan's target width. */
    val width: Int,
    /** Upright output height; matches the plan's target height. */
    val height: Int,
    /** Row-major ARGB_8888 pixels, row stride [width], sRGB assumed. */
    val argb: IntArray,
)

/**
 * The decode half of the canonical contract: original bytes plus a
 * [CanonicalDecodePlan] in, one canonical [DecodedImage] out --- or
 * `null` when the bytes are malformed/undecodable.
 *
 * `null` is the audit's "unsupported formats are skipped WITH A REASON"
 * behaviour made deterministic: the caller records UNSUPPORTED (or
 * CORRUPT, when the format matched but the bytes did not) against the
 * item's eligibility row and moves on; one bad file never aborts a
 * batch and never crashes the process.
 */
interface CanonicalImageDecoder {

    /**
     * Decodes [bytes] strictly according to [plan]: sample, rotate to
     * upright, scale to the plan's target dimensions. Returns `null` on
     * malformed/undecodable input. Implementations must NOT catch
     * `OutOfMemoryError` --- the plan's memory budget is the guard, and
     * an OOM means the budget was wrong (a bug to fix in the planner),
     * not a per-item degrade to swallow while the heap is already dying.
     */
    fun decode(bytes: ByteArray, plan: CanonicalDecodePlan): DecodedImage?
}

/**
 * Production [CanonicalImageDecoder] on `BitmapFactory` --- the thin,
 * deliberately NOT JVM-tested Android seam (ADR-0016, same split as
 * `AndroidVaultKeys` vs `VaultCrypto`): every decision (sample size,
 * rotation, target dims, budget) was already made by the pure
 * [CanonicalDecodePlanner]; this class only executes it, so the only
 * thing a unit test could check here is `BitmapFactory` itself.
 *
 * Execution order, and why:
 *  1. `decodeByteArray` with the plan's [CanonicalDecodePlan.inSampleSize]
 *     and ARGB_8888 --- sampling FIRST, inside the codec, so the big
 *     allocation never exists.
 *  2. `Matrix` rotation for 90/180/270 --- the upright contract.
 *  3. `createScaledBitmap` to the plan's target dims (bilinear) --- the
 *     power-of-two sampling granularity rarely lands exactly on target,
 *     so the final exact-size step lives here, where the pixels are
 *     already bounded by the sampling rule.
 *  4. `getPixels` into the [DecodedImage] buffer, then every
 *     intermediate bitmap is recycled --- the pipeline's heap returns to
 *     flat after each item, which is what makes a 10k-item batch
 *     survivable.
 *
 * **No OOM catch.** `BitmapFactory.decodeByteArray` signals garbage
 * input by returning `null` (that IS the malformed case); an
 * `OutOfMemoryError` escaping this method means the planner's budget
 * was violated or the heap was already exhausted --- swallowing it
 * while the runtime is dying corrupts state and hides the bug. The
 * planner's budget guard is the designed protection; see
 * [CanonicalDecodePlanner.plan].
 */
class AndroidBitmapDecoder : CanonicalImageDecoder {

    override fun decode(bytes: ByteArray, plan: CanonicalDecodePlan): DecodedImage? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = plan.inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val sampled: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null

        val upright: Bitmap = if (plan.orientationDegrees == 0) {
            sampled
        } else {
            val rotation = Matrix().apply { postRotate(plan.orientationDegrees.toFloat()) }
            Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, rotation, true)
        }

        val scaled: Bitmap =
            if (upright.width == plan.targetWidth && upright.height == plan.targetHeight) {
                upright
            } else {
                Bitmap.createScaledBitmap(upright, plan.targetWidth, plan.targetHeight, true)
            }

        // Copy out, then recycle every intermediate (deduplicated when the
        // steps collapsed to the same instance) --- heap stays flat.
        val width: Int = scaled.width
        val height: Int = scaled.height
        val argb = IntArray(width * height)
        scaled.getPixels(argb, 0, width, 0, 0, width, height)
        if (sampled !== upright && sampled !== scaled) sampled.recycle()
        if (upright !== scaled) upright.recycle()
        scaled.recycle()
        return DecodedImage(width = width, height = height, argb = argb)
    }
}
