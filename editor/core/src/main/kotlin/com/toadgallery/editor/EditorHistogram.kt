package com.toadgallery.editor

/**
 * The r66 tonal histogram (the honest-feedback round): the pure counting
 * core behind the Adjust panel's live chart.
 *
 * WHAT THIS IS: one pass over the preview's pipelined pixels producing
 *  - the three per-channel 256-bin histograms (R, G, B over the packed
 *    ARGB `0xAARRGGBB` layout used throughout the editor --- the PixelOps
 *    convention), and
 *  - the three luma ZONE counts (shadows / midtones / highlights) the
 *    chart's a11y state speaks, using the Rec.601 integer weights the
 *    pipeline's own saturation kernel uses (299/587/114, /1000).
 *
 * WHY IT IS PURE (ADR-0011): the counting is deterministic integer
 * arithmetic over an IntArray, so it is JVM-testable
 * (`EditorHistogramTest`) with no Android surface at all; the UI side
 * (`HistogramChart` in PhotoHistogram.kt) only READS the bins.
 *
 * WHERE IT RUNS: `EditActivity`'s preview render computes it from the
 * SAME pipelined pixels the canvas shows (the uncropped working grid the
 * AI seam reads), off the UI thread, re-keyed with the preview's own
 * keys (the committed recipe + the ONE draft slot) --- so the chart
 * updates live during a drag and can never disagree with the preview. It
 * is a histogram of the TONE pipeline, not of the scene layers:
 * stickers, strokes, captions and frames composite after the count (an
 * annotation is not a tonal state).
 *
 * The zone boundaries are the image-space thirds: luma below
 * [SHADOW_CEILING] is a shadow, luma above [HIGHLIGHT_FLOOR] a
 * highlight, the band between them the midtones. The percents are whole
 * numbers that sum to EXACTLY 100 by construction (the midtone share
 * absorbs the integer remainder --- it is the band between the floored
 * shares, so a one-count rounding is invisible there and nowhere else),
 * so the a11y sentence can never read 99% or 101% of a picture.
 */
internal class EditorHistogram private constructor(
    /** The red channel's 256 bins (bin index = channel value). */
    val red: IntArray,
    /** The green channel's 256 bins. */
    val green: IntArray,
    /** The blue channel's 256 bins. */
    val blue: IntArray,
    /**
     * The luma histogram's 256 bins (r74, the scope cycle's third
     * mode): the Rec.601 luma of every counted pixel into one array ---
     * computed in the SAME single pass, so the luminance scope can
     * never disagree with the RGB charts beside it.
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
     * The largest single bin across the three channels (0 for an empty
     * count): the chart's normalization denominator. A clipped spike
     * (pure black/white) dominating the scale is honest --- the chart
     * shows exactly where the mass went.
     */
    val peak: Int = maxOf(red.maxOrNull() ?: 0, green.maxOrNull() ?: 0, blue.maxOrNull() ?: 0)

    /**
     * The luminance chart's own normalization denominator (r74): the
     * tallest luma bin. Kept SEPARATE from [peak] --- the RGB charts and
     * the luma chart each normalize by their own tallest bin, exactly as
     * the parade normalizes by the shared peak (a chart whose scale is
     * its own data's is the honest scale).
     */
    val lumaPeak: Int = luma.maxOrNull() ?: 0

    /**
     * The three luma zones as whole percents (shadows, midtones,
     * highlights), ALWAYS summing to exactly 100: the shadow and
     * highlight shares floor to integers, the midtone share absorbs the
     * remainder.
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
         * Counts [pixels] (packed ARGB, row-major, any geometry --- the
         * chart is geometry-free). One pass: three bin increments and one
         * zone counter per pixel. Alpha is ignored (the pipeline's output
         * is fully opaque; a transparent preview pixel is not a thing).
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
         * The luminance histogram over an ALREADY-COUNTED histogram's
         * channels (r74): derives the luma bins from the three channel
         * bin arrays --- `(r,g,b)` triples are not recoverable from
         * marginal bins, so this folds each channel's mass into the luma
         * axis by the Rec.601 weights (the luma of a channel VALUE, not
         * of a pixel): an approximation stated honestly, used ONLY when
         * the caller holds bins but not pixels (the test seams). The
         * pixel-pass form above is the true one; this exists so no
         * caller ever recomputes a pass to reach the third mode.
         */
        fun luminanceBinsOfChannels(red: IntArray, green: IntArray, blue: IntArray): IntArray {
            val bins = IntArray(256)
            for (v in 0..255) {
                if (red[v] == 0 && green[v] == 0 && blue[v] == 0) continue
                val lumaOfValue = (v * LUMA_R + v * LUMA_G + v * LUMA_B) / LUMA_DIV
                // Each channel's count at v contributes a pixel whose
                // luma is UNKNOWN (it depends on the other channels); the
                // honest fold weights each channel's mass by its own luma
                // contribution. Grey pixels (r=g=b) land exactly.
                bins[lumaOfValue] += (red[v] + green[v] + blue[v]) / 3
            }
            return bins
        }
    }
}
