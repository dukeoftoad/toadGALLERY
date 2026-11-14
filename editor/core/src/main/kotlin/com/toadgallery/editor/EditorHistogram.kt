package com.toadgallery.editor

/**
 * The tonal histogram: one pass over the preview's pipelined pixels producing the three
 * per-channel 256-bin histograms (packed ARGB `0xAARRGGBB`, the PixelOps convention)
 * and the three luma ZONE counts (Rec.601 integer weights 299/587/114, /1000) the
 * chart's a11y state speaks. Pure (ADR-0011): deterministic integer arithmetic, JVM-
 * tested; the UI only READS the bins.
 *
 * Runs in the preview render from the SAME pipelined pixels the canvas shows (the
 * uncropped working grid the AI seam reads), off the UI thread, keyed with the
 * preview's keys --- the chart can never disagree with the preview. A histogram of
 * the TONE pipeline only: stickers/strokes/captions/frames composite after the count.
 *
 * Zones are image-space thirds (below [SHADOW_CEILING] shadow, above [HIGHLIGHT_FLOOR]
 * highlight); the whole percents sum to EXACTLY 100 (the midtone share absorbs the
 * integer remainder).
 */
internal class EditorHistogram private constructor(
    /** The red channel's 256 bins (bin index = channel value). */
    val red: IntArray,
    /** The green channel's 256 bins. */
    val green: IntArray,
    /** The blue channel's 256 bins. */
    val blue: IntArray,
    /**
     * The luma histogram's 256 bins (Rec.601 luma of every counted pixel), computed in
     * the SAME single pass --- the luminance scope can never disagree with the RGB charts.
     */
    val luma: IntArray,
    /** The number of pixels counted (each pixel feeds all three channels). */
    val pixelCount: Int,
    /** The luma-zone counts behind [zoneFractions] (shadows: luma < [SHADOW_CEILING]). */
    private val shadowPixels: Int,
    /** The midtone count ([SHADOW_CEILING]..[HIGHLIGHT_FLOOR], inclusive). */
    private val midtonePixels: Int,
    /** The highlight count (luma > [HIGHLIGHT_FLOOR]). */
    private val highlightPixels: Int,
) {
    /**
     * The largest single bin across the three channels (0 for an empty count): the
     * chart's normalization denominator. A clipped spike dominating the scale is
     * honest --- the chart shows exactly where the mass went.
     */
    val peak: Int = maxOf(red.maxOrNull() ?: 0, green.maxOrNull() ?: 0, blue.maxOrNull() ?: 0)

    /**
     * The luminance chart's own normalization denominator: the tallest luma bin, kept
     * SEPARATE from [peak] --- each chart normalizes by its own tallest bin (the parade
     * normalizes by the shared peak): a chart whose scale is its own data's is the honest scale.
     */
    val lumaPeak: Int = luma.maxOrNull() ?: 0

    /**
     * The three luma zones as whole percents (shadows, midtones, highlights), ALWAYS
     * summing to exactly 100: the shadow and highlight shares floor to integers, the
     * midtone share absorbs the remainder.
     */
    fun zoneFractions(): Triple<Int, Int, Int> {
        if (pixelCount == 0) return Triple(0, 0, 0)
        val shadows = shadowPixels * 100 / pixelCount
        val highlights = highlightPixels * 100 / pixelCount
        return Triple(shadows, 100 - shadows - highlights, highlights)
    }

    companion object {
        /** The shadow ceiling: luma strictly below this is a shadow (255/3). */
        const val SHADOW_CEILING = 85

        /** The highlight floor: luma strictly above this is a highlight (2 * 255/3). */
        const val HIGHLIGHT_FLOOR = 170

        // The Rec.601 integer weights (the PixelOps saturation kernel's).
        private const val LUMA_R = 299
        private const val LUMA_G = 587
        private const val LUMA_B = 114
        private const val LUMA_DIV = 1000

        /**
         * Counts [pixels] (packed ARGB, row-major, any geometry --- the chart is
         * geometry-free). One pass: three bin increments + one zone counter per pixel.
         * Alpha is ignored (the pipeline's output is fully opaque).
         */
        fun fromPixels(pixels: IntArray): EditorHistogram {
            val red = IntArray(256)
            val green = IntArray(256)
            val blue = IntArray(256)
            val lumaBins = IntArray(256)
            var shadows = 0
            var midtones = 0
            var highlights = 0
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                red[r]++
                green[g]++
                blue[b]++
                val luma = (r * LUMA_R + g * LUMA_G + b * LUMA_B) / LUMA_DIV
                lumaBins[luma]++
                when {
                    luma < SHADOW_CEILING -> shadows++
                    luma <= HIGHLIGHT_FLOOR -> midtones++
                    else -> highlights++
                }
            }
            return EditorHistogram(
                red = red,
                green = green,
                blue = blue,
                luma = lumaBins,
                pixelCount = pixels.size,
                shadowPixels = shadows,
                midtonePixels = midtones,
                highlightPixels = highlights,
            )
        }

        /**
         * The luminance histogram over an ALREADY-COUNTED histogram's channels: `(r,g,b)`
         * triples are not recoverable from marginal bins, so this folds each channel's
         * mass by the luma of the channel VALUE (not of a pixel) --- an approximation for
         * callers holding bins but not pixels (test seams); grey pixels land exactly.
         */
        fun luminanceBinsOfChannels(red: IntArray, green: IntArray, blue: IntArray): IntArray {
            val bins = IntArray(256)
            for (v in 0..255) {
                if (red[v] == 0 && green[v] == 0 && blue[v] == 0) continue
                val lumaOfValue = (v * LUMA_R + v * LUMA_G + v * LUMA_B) / LUMA_DIV
                // A pixel's true luma is unknown (depends on the other channels); the fold
                // weights each channel's mass by its own luma contribution. Grey lands exactly.
                bins[lumaOfValue] += (red[v] + green[v] + blue[v]) / 3
            }
            return bins
        }
    }
}
