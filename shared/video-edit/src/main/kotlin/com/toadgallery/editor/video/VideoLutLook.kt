package com.toadgallery.editor.video

/**
 * A look applied to a video, as DATA (r74, the plan doc's "one language"
 * promise realised on the video lane): the SAME bundled `.cube` LUT ids
 * the photo Looks deck ships, plus the intensity dial the photo lane
 * taught (r33's LutSelection grammar), carried on the video side so a
 * look applied to a photo and the same look on a clip ARE one fact.
 *
 * ONE PARSER, TWO REALIZATIONS: the photo pipeline bakes a `.cube` into
 * its 256-entry per-channel LUT at full intensity and scales by
 * intensity at apply time; the video REALIZATION cannot bake a 3D LUT
 * into the existing 1D-chain shader, so it samples the SAME parsed
 * table as a 3D texture. Both sides read ONE parsed structure ---
 * [VideoLutLook.table] is the parsed cube (R,G,B domain stops), and
 * [applyToPixel] below is the pure reference sampler the tests pin
 * against the photo lane's own bake: same input, same output, one look.
 *
 * WHY THE TABLE RIDES HERE (ADR-0011): parsing `.cube` text is pure
 * arithmetic; the photo side's parser stays the single source (the
 * editor core owns it), and THIS file holds the video-facing model,
 * validation and the reference sampler --- JVM-pinned, no Android, no
 * GL. The GL texture upload is the realization shell's job.
 */
data class VideoLutLook(
    /** The bundled look id (the photo Looks registry's own key space). */
    val lutId: String,
    /**
     * The intensity, 0..100 (r33's grammar verbatim: 100 = the full
     * look, 0 = dialed out --- no contribution, an identity).
     */
    val intensity: Int = 100,
    /**
     * The PARSED cube (supplied by the caller from the shared parser):
     * `size` stops per axis, `domainMin`/`domainMax` the cube's own
     * bounds, `data` the size^3 samples in RED-major .cube order
     * (r fastest, then g, then b --- the .cube spec's order).
     */
    val size: Int,
    val domainMin: Double,
    val domainMax: Double,
    val data: DoubleArray,
) {
    init {
        require(lutId.isNotEmpty()) { "lutId must not be empty" }
        require(intensity in 0..100) { "intensity $intensity outside [0..100]" }
        require(size >= 2) { "a cube needs at least 2 stops per axis, was $size" }
        require(domainMax > domainMin) {
            "domainMax must exceed domainMin, was $domainMin..$domainMax"
        }
        require(data.size == size * size * size * 3) {
            "cube data must hold size^3 RGB triples = ${size * size * size * 3} samples, was ${data.size}"
        }
    }

    /** Two looks are equal by identity facts; the sampled table is content, not identity. */
    override fun equals(other: Any?): Boolean =
        other is VideoLutLook && other.lutId == lutId && other.intensity == intensity

    override fun hashCode(): Int = 31 * lutId.hashCode() + intensity
}

/** The structured validation outcome (the VideoPlanValidation shape). */
sealed interface VideoLutLookValidation {
    /** Valid: a look is optional --- null means "no look", also valid. */
    data class Valid(val look: VideoLutLook?) : VideoLutLookValidation
    data class Invalid(val reason: String) : VideoLutLookValidation
}

/**
 * The validation gate and the pure sampler (the reference the GL upload
 * is pinned against; every rule JVM-pinned by [VideoLutLookTest]).
 */
object VideoLutLookValidator {

    fun validate(look: VideoLutLook?): VideoLutLookValidation {
        if (look == null) return VideoLutLookValidation.Valid(null)
        // The constructor already pinned the table's own shape; the gate
        // re-states the r33 grammar on the video side (one place, the
        // same bounds the photo LutSelection rides).
        if (look.intensity !in 0..100) {
            return VideoLutLookValidation.Invalid(
                "video look intensity ${look.intensity} outside [0..100] --- the r33 grammar",
            )
        }
        return VideoLutLookValidation.Valid(look)
    }

    /**
     * The intensity projection, shared with the photo lane's semantics:
     * the blended channel value is `lerp(original, lutOut, intensity/100)`
     * --- 100 lands the exact cube output, 0 returns the original
     * untouched, and there is no third behaviour. The shader's uniform
     * is exactly this factor; the sampler below applies it the same way.
     */
    fun intensityFactor(look: VideoLutLook): Float = look.intensity / 100f
}

/**
 * The pure reference sampler (ADR-0011): trilinear interpolation over
 * the parsed cube, the identity for the intensity blend, and the exact
 * arithmetic the GL realization is tested against. Input and output are
 * CLAMPED [0,1] triples --- the shader's own space.
 */
object VideoLutSampler {

    /**
     * Samples the look at an RGB triple in [0,1]: the cube's output at
     * the trilinear neighbourhood of the point, blended with the input
     * by the intensity factor. A null look is the identity (the caller
     * skips the stage entirely; this exists so the TEST can state it).
     */
    fun sample(look: VideoLutLook?, r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        if (look == null || look.intensity == 0) return Triple(r, g, b)
        val sampled = sampleCube(look, r, g, b)
        val t = look.intensity / 100.0
        return Triple(
            r + (sampled.first - r) * t,
            g + (sampled.second - g) * t,
            b + (sampled.third - b) * t,
        )
    }

    /** The raw trilinear sample, no intensity blend. */
    fun sampleCube(look: VideoLutLook, r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val span = look.domainMax - look.domainMin
        // Map [domainMin..domainMax] onto the cube's stops, clamped.
        val fx = ((r - look.domainMin) / span * (look.size - 1)).coerceIn(0.0, (look.size - 1).toDouble())
        val fy = ((g - look.domainMin) / span * (look.size - 1)).coerceIn(0.0, (look.size - 1).toDouble())
        val fz = ((b - look.domainMin) / span * (look.size - 1)).coerceIn(0.0, (look.size - 1).toDouble())
        val x0 = fx.toInt().coerceAtMost(look.size - 2)
        val y0 = fy.toInt().coerceAtMost(look.size - 2)
        val z0 = fz.toInt().coerceAtMost(look.size - 2)
        val tx = fx - x0
        val ty = fy - y0
        val tz = fz - z0

        // .cube is RED-major: index = r + g*size + b*size^2.
        fun at(rr: Int, gg: Int, bb: Int): Triple<Double, Double, Double> {
            val base = (rr + gg * look.size + bb * look.size * look.size) * 3
            return Triple(look.data[base], look.data[base + 1], look.data[base + 2])
        }

        // Trilinear over the 8 corners, RED axis innermost.
        val c000 = at(x0, y0, z0)
        val c100 = at(x0 + 1, y0, z0)
        val c010 = at(x0, y0 + 1, z0)
        val c110 = at(x0 + 1, y0 + 1, z0)
        val c001 = at(x0, y0, z0 + 1)
        val c101 = at(x0 + 1, y0, z0 + 1)
        val c011 = at(x0, y0 + 1, z0 + 1)
        val c111 = at(x0 + 1, y0 + 1, z0 + 1)

        fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
        val r00 = Triple(lerp(c000.first, c100.first, tx), lerp(c000.second, c100.second, tx), lerp(c000.third, c100.third, tx))
        val r10 = Triple(lerp(c010.first, c110.first, tx), lerp(c010.second, c110.second, tx), lerp(c010.third, c110.third, tx))
        val r01 = Triple(lerp(c001.first, c101.first, tx), lerp(c001.second, c101.second, tx), lerp(c001.third, c101.third, tx))
        val r11 = Triple(lerp(c011.first, c111.first, tx), lerp(c011.second, c111.second, tx), lerp(c011.third, c111.third, tx))
        val r0 = Triple(lerp(r00.first, r10.first, ty), lerp(r00.second, r10.second, ty), lerp(r00.third, r10.third, ty))
        val r1 = Triple(lerp(r01.first, r11.first, ty), lerp(r01.second, r11.second, ty), lerp(r01.third, r11.third, ty))
        return Triple(
            lerp(r0.first, r1.first, tz).coerceIn(0.0, 1.0),
            lerp(r0.second, r1.second, tz).coerceIn(0.0, 1.0),
            lerp(r0.third, r1.third, tz).coerceIn(0.0, 1.0),
        )
    }
}
