package com.toadgallery.editor

/**
 * The prototype's real "modify" step: a per-channel 256-entry LUT applying brightness +
 * contrast, plus a whole-image quarter-turn rotation. Pure Kotlin, exercised on the JVM.
 *
 * The math (parameters in [-100, 100]):
 *  - brightness b: `out = clamp(v + b * 2.55)` (+100 is exactly white-clipping);
 *  - contrast c: mid-grey 128 pivot, `out = clamp((v - 128) * f + 128)` where
 *    `f = (259 * (c * 2.55 + 255)) / (255 * (259 - c * 2.55))` (f ~2.0 at +100,
 *    f ~0.008 at -100). Brightness composes FIRST then contrast; identical
 *    parameters always produce identical tables (preview and export bytes agree).
 */
object PixelAdjuster {

    const val MIN = -100
    const val MAX = 100

    /** The exact 8-bit clamping rule every path shares. */
    fun clamp8(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }

    /** Builds the composed brightness-then-contrast LUT for the two parameters. */
    fun buildLut(brightness: Int, contrast: Int): IntArray {
        require(brightness in MIN..MAX) { "brightness $brightness outside [$MIN..$MAX]" }
        require(contrast in MIN..MAX) { "contrast $contrast outside [$MIN..$MAX]" }
        val brightShift = brightness * 2.55
        val contrastPercent = contrast * 2.55
        val factor = (259.0 * (contrastPercent + 255.0)) / (255.0 * (259.0 - contrastPercent))
        return IntArray(256) { v ->
            val brightened = v + brightShift
            val contrasted = (brightened - 128.0) * factor + 128.0
            clamp8(Math.round(contrasted).toInt())
        }
    }

    /**
     * Applies the adjustment to ARGB pixels in place (returns the same array for
     * fluent use). Alpha is never touched.
     */
    fun applyTo(pixels: IntArray, lut: IntArray): IntArray {
        require(lut.size == 256) { "LUT must have 256 entries" }
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = lut[(p ushr 16) and 0xFF]
            val g = lut[(p ushr 8) and 0xFF]
            val b = lut[p and 0xFF]
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return pixels
    }

    /**
     * The rotation transform for the export stage: [quarterTurns] 90-degree
     * counter-clockwise turns of an [width] x [height] grid, returned as a new array
     * plus the swapped output dimensions. Pure grid math.
     */
    fun rotateQuarterTurns(
        pixels: IntArray,
        width: Int,
        height: Int,
        quarterTurns: Int,
    ): RotatedPixels {
        require(pixels.size == width * height) { "pixel count mismatch" }
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return RotatedPixels(pixels, width, height)
        val outWidth = if (turns % 2 == 1) height else width
        val outHeight = if (turns % 2 == 1) width else height
        val out = IntArray(pixels.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (nx, ny) = when (turns) {
                    1 -> Pair(y, width - 1 - x)              // 90° CCW
                    2 -> Pair(width - 1 - x, height - 1 - y)  // 180°
                    else -> Pair(height - 1 - y, x)           // 270° CCW = 90° CW
                }
                out[ny * outWidth + nx] = pixels[y * width + x]
            }
        }
        return RotatedPixels(out, outWidth, outHeight)
    }

    data class RotatedPixels(val pixels: IntArray, val width: Int, val height: Int)
}
