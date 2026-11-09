package com.toadgallery.gallery.media.decode

import kotlin.math.max
import kotlin.math.min

/**
 * The deterministic result of [CanonicalDecodePlanner.plan]: everything a
 * decoder needs to turn original bytes into the canonical upright image
 * WITHOUT improvising (Phase 4 contract change 6).
 *
 * The plan is computed PURELY (JVM-tested, no `android.*`) from source
 * dimensions, EXIF orientation and two policy knobs, so two devices ---
 * or the JVM test suite and a phone --- always agree on sampling, target
 * size and the memory estimate before a single byte is decoded. That is
 * what keeps OOMs out of the pipeline: the decision that can blow the
 * heap is made where it can be tested, not inside the decoder where it
 * cannot.
 *
 * Fields:
 *  - [srcWidth]/[srcHeight] --- the ENCODED dimensions, exactly as the
 *    container reports them (pre-rotation).
 *  - [orientationDegrees] --- EXIF orientation collapsed to a pure
 *    rotation of 0/90/180/270. Mirrored orientations (2/4/5/7) are the
 *    metadata layer's job to normalize before planning; the planner
 *    refuses anything else rather than silently dropping a flip.
 *  - [inSampleSize] --- the power-of-two sub-sampling hint handed to
 *    `BitmapFactory` (see [CanonicalDecodePlanner.plan] for the exact
 *    contract being targeted).
 *  - [targetWidth]/[targetHeight] --- the UPRIGHT output dimensions
 *    (rotation already accounted for): the final image the decoder must
 *    produce, fitted inside the max-dimension cap with aspect preserved.
 *  - [swapDimensionsForUpright] --- true for 90/270, where rotation
 *    exchanges width and height. The decoder needs this even though
 *    [targetWidth]/[targetHeight] are already upright, because sampling
 *    happens on the ENCODED bitmap BEFORE the rotation matrix runs.
 *  - [estimatedBitmapBytes] --- [targetWidth] * [targetHeight] * 4
 *    (ARGB_8888): the size of the FINAL bitmap the pipeline will hold.
 */
data class CanonicalDecodePlan(
    /** Encoded width as reported by the container (pre-rotation). */
    val srcWidth: Int,
    /** Encoded height as reported by the container (pre-rotation). */
    val srcHeight: Int,
    /** Pure rotation applied after sampling: 0, 90, 180 or 270. */
    val orientationDegrees: Int,
    /** Power-of-two `BitmapFactory` sampling hint; see [CanonicalDecodePlanner.plan]. */
    val inSampleSize: Int,
    /** Upright output width; aspect-preserving fit within the cap. */
    val targetWidth: Int,
    /** Upright output height; aspect-preserving fit within the cap. */
    val targetHeight: Int,
    /** True for 90/270: rotation swaps the encoded dimensions. */
    val swapDimensionsForUpright: Boolean,
    /** targetWidth * targetHeight * 4 (ARGB_8888) --- the final bitmap's footprint. */
    val estimatedBitmapBytes: Long,
)

/**
 * Pure, fully JVM-tested planner behind the canonical decode contract
 * (Phase 4 contract change 6). [plan] answers, before any decode:
 * how much to sample, what the upright output size is, and whether the
 * item is decodable at all within the memory budget --- deterministically,
 * so "too large" is an UNSUPPORTED verdict the caller records, not an
 * OOM roulette at 2 a.m. on a low-RAM device.
 */
object CanonicalDecodePlanner {

    /**
     * Plans the canonical decode of one still image, or `null` when the
     * inputs are invalid or the item cannot be decoded within
     * [memoryBudgetBytes] (the caller records UNSUPPORTED / too-large and
     * moves on --- deterministic skip, no OOM roulette).
     *
     * **Validity.** Non-positive [srcWidth]/[srcHeight], an
     * [orientationDegrees] outside 0/90/180/270, or a [maxDimension]
     * below 8 yields `null`: there is no sane decode to plan, and a
     * nonsense plan that "sort of works" is worse than none.
     *
     * **Memory budget (evaluated at sample size 1).** The guard compares
     * the FULL-RESOLUTION ARGB_8888 footprint --- [srcWidth] *
     * [srcHeight] * 4 --- against [memoryBudgetBytes], evaluated with
     * overflow-safe arithmetic: containers report dimensions as raw
     * `Int`s, so an adversarial or corrupt file can claim
     * `Int.MAX_VALUE`-scale edges whose pixel/byte product overflows
     * `Long`; the guard divides the budget down per dimension instead of
     * multiplying the dimensions up, so no magnitude can wrap into a
     * falsely-accepted plan. Sample 1, the
     * smallest sample size, is the most memory ANY decode path could
     * demand, and `inSampleSize` is only a HINT: `BitmapFactory` documents
     * that codecs may honour it approximately, and some paths (HEIF/RAW
     * through certain decoders, region decoders) can materialize close to
     * the full-resolution bitmap before any scaling. If even the
     * sample-1 footprint exceeds the budget, the planner refuses the
     * item outright rather than gamble on decoder behaviour; a budget
     * above the full-size footprint makes the sample-1 estimate the
     * binding worst case, which is the honest number to budget against.
     *
     * **Sampling.** The smallest power-of-two [CanonicalDecodePlan.inSampleSize]
     * such that BOTH encoded dimensions divided by it fit their needed
     * dimensions (the target dims mapped back into encoded orientation
     * --- sampling runs BEFORE rotation). This encodes the
     * `BitmapFactory` contract the audit's "pixel parity" gate targets:
     * the sample size is a hint, and a decoder honouring it may return
     * dimensions up to just under 2x (src / sampleSize) per edge. The
     * smallest sample that satisfies `src / sampleSize <= needed`
     * therefore guarantees the decoded intermediate stays within
     * 2x the needed dims --- never more than ~4x the target's PIXELS on
     * the heap --- while never over-sampling past what the target needs
     * (a smaller sample would decode larger than needed and waste
     * memory; a larger one would fall below the target and blur).
     *
     * **Upright target.** For 90/270 the upright dimensions swap. The
     * target is the aspect-preserving fit inside [maxDimension] (the
     * longest upright edge, floored, never below 1px). Sources already
     * inside the cap are NOT upscaled: resampling adds no information
     * for the models and costs memory --- the encoder pads/scales to its
     * own input size anyway.
     *
     * **Estimate.** [CanonicalDecodePlan.estimatedBitmapBytes] is
     * targetWidth * targetHeight * 4 (ARGB_8888), the FINAL bitmap the
     * pipeline holds; the transient decode intermediate is bounded by
     * the sampling rule above.
     *
     * @param srcWidth encoded width; must be positive.
     * @param srcHeight encoded height; must be positive.
     * @param orientationDegrees pure rotation: 0, 90, 180 or 270.
     * @param maxDimension longest allowed UPRIGHT edge of the output; at
     *   least 8. The 1024 default is the AI input size: Ente's detection
     *   and embedding models consume ~640-1024px inputs, so a longer
     *   edge buys model accuracy nothing and costs pixels plenty.
     * @param memoryBudgetBytes heap the whole decode may demand; see the
     *   budget paragraph above. The 48 MiB default matches the
     *   intermediate-decode headroom the planner's own sampling rule
     *   keeps (~4x a 1024-edge target's pixels, plus slack).
     */
    fun plan(
        srcWidth: Int,
        srcHeight: Int,
        orientationDegrees: Int,
        maxDimension: Int = DEFAULT_MAX_DIMENSION,
        memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET_BYTES,
    ): CanonicalDecodePlan? {
        if (srcWidth <= 0 || srcHeight <= 0) return null
        if (orientationDegrees != ORIENTATION_0 &&
            orientationDegrees != ORIENTATION_90 &&
            orientationDegrees != ORIENTATION_180 &&
            orientationDegrees != ORIENTATION_270
        ) {
            return null
        }
        if (maxDimension < MIN_MAX_DIMENSION) return null

        // Budget guard at the smallest sample size (1): the full-resolution
        // ARGB footprint is the one allocation the planner cannot rule out,
        // because inSampleSize is a hint, not a promise.
        //
        // Overflow-safe by construction (audit finding J): the naive
        // `srcWidth.toLong() * srcHeight.toLong() * BYTES_PER_PIXEL` wraps
        // for Int.MAX_VALUE-scale dimension pairs (product ~1.8e19 >
        // Long.MAX), and the wrapped value can go NEGATIVE, slipping
        // through a `> budget` check. Comparing by division instead ---
        // `H > (budget / 4) / W` --- is the exact same integer inequality
        // (W * H * 4 <= budget  <=>  H <= (budget / 4) / W for W > 0, both
        // sides floor division) with no multiplication anywhere, so every
        // magnitude is handled and the budget boundary stays inclusive.
        // A negative budget divides to a negative bound and refuses
        // everything, deterministically.
        val maxPixelsAtBudget: Long = memoryBudgetBytes / BYTES_PER_PIXEL
        if (srcHeight.toLong() > maxPixelsAtBudget / srcWidth.toLong()) return null

        val swapForUpright: Boolean =
            orientationDegrees == ORIENTATION_90 || orientationDegrees == ORIENTATION_270
        val uprightWidth: Int = if (swapForUpright) srcHeight else srcWidth
        val uprightHeight: Int = if (swapForUpright) srcWidth else srcHeight

        // Upright target: fit inside the cap, never upscale, never below 1px.
        val targetWidth: Int
        val targetHeight: Int
        if (uprightWidth <= maxDimension && uprightHeight <= maxDimension) {
            targetWidth = uprightWidth
            targetHeight = uprightHeight
        } else {
            val longSide: Int = max(uprightWidth, uprightHeight)
            val shortSide: Int = min(uprightWidth, uprightHeight)
            // Floor of the exact rational short/long * maxDimension; the
            // floor costs < 1px of aspect fidelity and stays deterministic
            // across devices (no float rounding debates).
            // Overflow-safe (audit finding J): shortSide <= longSide and
            // both are <= Int.MAX_VALUE, so shortSide.toLong() *
            // maxDimension <= (2^31 - 1)^2 ~ 4.6e18 < Long.MAX --- the Long
            // product can never wrap, and the quotient is < maxDimension so
            // the toInt() narrowing is exact.
            val targetShortSide: Int =
                ((shortSide.toLong() * maxDimension) / longSide).toInt().coerceAtLeast(1)
            if (uprightWidth >= uprightHeight) {
                targetWidth = maxDimension
                targetHeight = targetShortSide
            } else {
                targetWidth = targetShortSide
                targetHeight = maxDimension
            }
        }

        // Needed dims in ENCODED orientation: sampling runs before the
        // rotation matrix, so a 90/270 plan needs the target's dims swapped
        // back onto the encoded axes.
        val neededWidth: Int = if (swapForUpright) targetHeight else targetWidth
        val neededHeight: Int = if (swapForUpright) targetWidth else targetHeight

        // Smallest power-of-two sample with BOTH encoded dims fitting their
        // needed dims (see the sampling paragraph of the KDoc). Terminates:
        // once the sample exceeds the encoded width, integer division hits 0.
        var inSampleSize: Int = 1
        while (srcWidth / inSampleSize > neededWidth || srcHeight / inSampleSize > neededHeight) {
            if (inSampleSize >= MAX_IN_SAMPLE_SIZE) {
                // Unreachable for any real image (would need a ~10^6x
                // over-size); the guard exists so the doubling can never
                // overflow Int on adversarial inputs.
                return null
            }
            inSampleSize = inSampleSize shl 1
        }

        return CanonicalDecodePlan(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            orientationDegrees = orientationDegrees,
            inSampleSize = inSampleSize,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            swapDimensionsForUpright = swapForUpright,
            // Overflow-safe (audit finding J): the never-upscale rule keeps
            // each target edge <= its upright source edge, so the target
            // area is <= the source area already proven (above) to satisfy
            // W * H * 4 <= budget in exact integer arithmetic --- the Long
            // product here can never wrap for any plan that reaches it.
            estimatedBitmapBytes = targetWidth.toLong() * targetHeight.toLong() * BYTES_PER_PIXEL,
        )
    }

    /** Default longest upright edge for AI inputs; see [plan]'s KDoc. */
    const val DEFAULT_MAX_DIMENSION: Int = 1024

    /**
     * Default decode memory budget: 48 MiB. Generous against the sampling
     * rule's ~4x-target-pixel intermediate bound, small enough that one
     * decode cannot dominate a low-RAM device's large-heap budget.
     */
    const val DEFAULT_MEMORY_BUDGET_BYTES: Long = 48L * 1024 * 1024

    /**
     * Smallest accepted [plan]'s maxDimension. Below 8px the
     * power-of-two sampling granularity swamps the image entirely ---
     * nothing sane asks for it.
     */
    const val MIN_MAX_DIMENSION: Int = 8

    /** ARGB_8888 bytes per pixel --- the canonical output configuration. */
    const val BYTES_PER_PIXEL: Int = 4

    private const val ORIENTATION_0 = 0
    private const val ORIENTATION_90 = 90
    private const val ORIENTATION_180 = 180
    private const val ORIENTATION_270 = 270

    /**
     * Sampling ceiling: 2^20. No real image needs it; the constant only
     * bounds the doubling loop against Int overflow on absurd inputs.
     */
    private const val MAX_IN_SAMPLE_SIZE: Int = 1 shl 20
}
