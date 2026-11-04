package io.github.dukeoftoad.gallery.edit

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The LUT filter engine (r33, Phase 7 v2): parses the Adobe `.cube` 3D LUT
 * format and samples it with trilinear interpolation, blended by an
 * intensity factor.
 *
 * The engine is PURE Kotlin (no Android imports) so it is JVM-testable
 * exactly like the rest of the r30 pipeline core. Only the asset LOADER
 * ([BundledLuts]) touches the platform (via an injected stream opener).
 *
 * Format contract (the subset this parser accepts --- anything else is a
 * structured [CubeLutParser.Result.Invalid], never a crash):
 *  - `TITLE "..."` optional, at most once; the human-readable look name.
 *  - `LUT_3D_SIZE N` REQUIRED, exactly once, before any data line;
 *    N in 2..64 (the spec allows any size >= 2; 64 caps a LUT at 262144
 *    samples = ~3 MB of floats, far beyond any bundled look).
 *  - `DOMAIN_MIN r g b` / `DOMAIN_MAX r g b` optional, at most once each;
 *    defaults 0 0 0 / 1 1 1. A degenerate (zero-width) channel domain is
 *    rejected --- a sampler could never place an input inside it.
 *  - `LUT_1D_SIZE` is REJECTED: this is a 3D LUT engine; a 1D LUT file is
 *    a different tool, and silently mis-reading it would be a lie.
 *  - `RANGE_MIN` / `RANGE_MAX` / `RANGE` lines are ACCEPTED and IGNORED:
 *    this engine clamps every OUTPUT sample to [0, 1], which is the
 *    default output range; a file declaring a custom output range still
 *    renders (clamped) rather than failing the whole look.
 *  - Blank lines and `#` comment lines are tolerated anywhere.
 *  - Data lines are exactly three whitespace-separated finite floats, in
 *    RED-GREEN-BLUE order, R index incrementing fastest (line 1 is the
 *    (0,0,0) grid point, line 2 is (1,0,0), ...), exactly N^3 of them.
 *    Table values outside [0,1] are tolerated (extended-range LUTs) and
 *    clamped at SAMPLE time, never at parse time --- the file is the
 *    author's data; the output pixel range is the engine's policy.
 *
 * A parsed table is IMMUTABLE and shared: the editor holds at most the
 * bundled set in memory (17^3 samples = ~60 KB each), and sampling never
 * mutates it.
 */
class CubeLut(
    /** The registry id ("" when parsed standalone, e.g. in tests). */
    val id: String,
    val title: String,
    val size: Int,
    val domainMinR: Double,
    val domainMinG: Double,
    val domainMinB: Double,
    val domainMaxR: Double,
    val domainMaxG: Double,
    val domainMaxB: Double,
    /** N^3 samples x 3 channels, R index fastest: index = (r + g*N + b*N*N) * 3. */
    private val table: FloatArray,
) {

    /** Samples the LUT at an input colour; output channels clamp to [0, 1]. */
    fun sample(rIn: Double, gIn: Double, bIn: Double): DoubleArray {
        val out = DoubleArray(3)
        sampleInto(rIn, gIn, bIn, out)
        return out
    }

    /**
     * The allocation-free sampler core (the pixel loop below runs at
     * preview resolutions --- millions of samples --- so every sample
     * must be garbage-free; [sample] is the allocating convenience
     * wrapper for single probes).
     */
    fun sampleInto(rIn: Double, gIn: Double, bIn: Double, out: DoubleArray) {
        // Grid coordinates inside [0, N-1], clamped to the domain edges.
        val x = gridCoord(rIn, domainMinR, domainMaxR)
        val y = gridCoord(gIn, domainMinG, domainMaxG)
        val z = gridCoord(bIn, domainMinB, domainMaxB)
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val x1 = min(x0 + 1, size - 1)
        val y1 = min(y0 + 1, size - 1)
        val z1 = min(z0 + 1, size - 1)
        val fx = x - x0
        val fy = y - y0
        val fz = z - z0

        for (ch in 0..2) {
            val c000 = tableValue(x0, y0, z0, ch)
            val c100 = tableValue(x1, y0, z0, ch)
            val c010 = tableValue(x0, y1, z0, ch)
            val c110 = tableValue(x1, y1, z0, ch)
            val c001 = tableValue(x0, y0, z1, ch)
            val c101 = tableValue(x1, y0, z1, ch)
            val c011 = tableValue(x0, y1, z1, ch)
            val c111 = tableValue(x1, y1, z1, ch)
            val e0 = c000 + (c100 - c000) * fx
            val e1 = c010 + (c110 - c010) * fx
            val e2 = c001 + (c101 - c001) * fx
            val e3 = c011 + (c111 - c011) * fx
            val bottom = e0 + (e1 - e0) * fy
            val top = e2 + (e3 - e2) * fy
            out[ch] = (bottom + (top - bottom) * fz).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Applies this look to an ARGB pixel array IN PLACE at [intensity]
     * (0.0 = the original colour untouched, 1.0 = the full look; the
     * blend is a straight lerp per channel). The ALPHA channel is never
     * touched --- a look must not punch holes in transparent regions
     * (straighten's expanded canvas fills with transparent pixels).
     */
    fun applyTo(pixels: IntArray, intensity: Double) {
        if (intensity <= 0.0) return
        val t = intensity.coerceIn(0.0, 1.0)
        val mapped = DoubleArray(3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val r = ((p shr 16) and 0xFF) / 255.0
            val g = ((p shr 8) and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0
            sampleInto(r, g, b, mapped)
            // The intensity blend: original + (look - original) * t.
            val outR = ((r + (mapped[0] - r) * t) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            val outG = ((g + (mapped[1] - g) * t) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            val outB = ((b + (mapped[2] - b) * t) * 255.0 + 0.5).toInt().coerceIn(0, 255)
            pixels[i] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
        }
    }

    private fun gridCoord(value: Double, dMin: Double, dMax: Double): Double =
        ((value - dMin) / (dMax - dMin) * (size - 1)).coerceIn(0.0, (size - 1).toDouble())

    private fun tableValue(r: Int, g: Int, b: Int, ch: Int): Double =
        table[(r + g * size + b * size * size) * 3 + ch].toDouble()
}

/** The structured parser: a `.cube` text either parses or reports why it cannot. */
object CubeLutParser {

    const val MIN_SIZE: Int = 2
    const val MAX_SIZE: Int = 64

    sealed interface Result {
        data class Valid(val lut: CubeLut) : Result
        data class Invalid(val reason: String) : Result
    }

    fun parse(text: String, id: String = ""): Result {
        var title: String? = null
        var size: Int? = null
        var domainMin: DoubleArray? = null
        var domainMax: DoubleArray? = null
        val samples = ArrayList<Double>(3 * 64 * 64 * 64 / 4) // grown as parsed; capacity hint only

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val keyword = line.substringBefore(' ').uppercase()
            when (keyword) {
                "TITLE" -> {
                    if (title != null) return Result.Invalid("duplicate TITLE")
                    val rest = line.substringAfter(' ').trim()
                    if (rest.length < 2 || !rest.startsWith("\"") || !rest.endsWith("\"")) {
                        return Result.Invalid("TITLE must be one double-quoted string")
                    }
                    title = rest.substring(1, rest.length - 1)
                }
                "LUT_3D_SIZE" -> {
                    if (size != null) return Result.Invalid("duplicate LUT_3D_SIZE")
                    val n = line.substringAfter(' ').trim().toIntOrNull()
                        ?: return Result.Invalid("LUT_3D_SIZE must be an integer")
                    if (n < MIN_SIZE || n > MAX_SIZE) {
                        return Result.Invalid("LUT_3D_SIZE $n outside $MIN_SIZE..$MAX_SIZE")
                    }
                    size = n
                }
                "LUT_1D_SIZE" ->
                    return Result.Invalid("LUT_1D_SIZE is not supported (this is a 3D LUT engine)")
                "DOMAIN_MIN", "DOMAIN_MAX" -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size != 4) {
                        return Result.Invalid("$keyword must have exactly three values")
                    }
                    val values = DoubleArray(3)
                    for (i in 0..2) {
                        val v = parts[i + 1].toDoubleOrNull()
                            ?: return Result.Invalid("$keyword value '${parts[i + 1]}' is not a number")
                        if (v.isNaN() || v.isInfinite()) {
                            return Result.Invalid("$keyword value is not finite")
                        }
                        values[i] = v
                    }
                    if (keyword == "DOMAIN_MIN") {
                        if (domainMin != null) return Result.Invalid("duplicate DOMAIN_MIN")
                        domainMin = values
                    } else {
                        if (domainMax != null) return Result.Invalid("duplicate DOMAIN_MAX")
                        domainMax = values
                    }
                }
                "RANGE_MIN", "RANGE_MAX", "RANGE" -> {
                    // Accepted and ignored: outputs clamp to [0, 1] (the default
                    // output range) regardless of a declared custom range.
                }
                else -> {
                    // A data line: exactly three finite floats.
                    if (size == null) {
                        return Result.Invalid("data line before LUT_3D_SIZE")
                    }
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size != 3) {
                        return Result.Invalid("data line must have exactly three values: '$line'")
                    }
                    for (part in parts) {
                        val v = part.toDoubleOrNull()
                            ?: return Result.Invalid("data value '$part' is not a number")
                        if (v.isNaN() || v.isInfinite()) {
                            return Result.Invalid("data value is not finite")
                        }
                        samples.add(v)
                    }
                }
            }
        }

        val n = size ?: return Result.Invalid("missing LUT_3D_SIZE")
        val expected = n * n * n * 3
        if (samples.size != expected) {
            return Result.Invalid("expected $expected values ($n^3 samples x 3), found ${samples.size}")
        }
        val dMin = domainMin ?: doubleArrayOf(0.0, 0.0, 0.0)
        val dMax = domainMax ?: doubleArrayOf(1.0, 1.0, 1.0)
        for (i in 0..2) {
            if (dMax[i] <= dMin[i]) {
                return Result.Invalid("degenerate domain: channel $i has max <= min")
            }
        }
        val table = FloatArray(expected)
        for (i in 0 until expected) table[i] = samples[i].toFloat()
        return Result.Valid(
            CubeLut(
                id = id,
                title = title ?: "",
                size = n,
                domainMinR = dMin[0], domainMinG = dMin[1], domainMinB = dMin[2],
                domainMaxR = dMax[0], domainMaxG = dMax[1], domainMaxB = dMax[2],
                table = table,
            ),
        )
    }
}
