package com.toadgallery.editor.video

/**
 * A look applied to a video, as data: the photo Looks deck's bundled .cube ids plus the
 * intensity dial — a look on a photo and on a clip are one fact. The photo side bakes the
 * cube into its 1D LUT; the video side samples the parsed table as a 3D texture
 * ([VideoLutSampler] is the pure reference the GL realization is tested against). Pure.
 */
data class VideoLutLook(
    /** The bundled look id (the photo Looks registry's own key space). */
    val lutId: String,
    /** The intensity, 0..100: 100 = the full look, 0 = dialed out (an identity). */
    val intensity: Int = 100,
    /**
     * The parsed cube (caller-supplied): `size` stops per axis, `domainMin`/`domainMax`
     * bounds, `data` the size^3 samples in RED-major .cube order (r fastest, then g, then b).
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

/** The validation gate and shared intensity projection; JVM-pinned. */
object VideoLutLookValidator {

    fun validate(look: VideoLutLook?): VideoLutLookValidation {
        if (look == null) return VideoLutLookValidation.Valid(null)
        // The constructor pins the table shape; this re-states the intensity grammar shared with the photo lane.
        if (look.intensity !in 0..100) {
            return VideoLutLookValidation.Invalid(
                "video look intensity ${look.intensity} outside [0..100] --- the r33 grammar",
            )
        }
        return VideoLutLookValidation.Valid(look)
    }

    /**
     * The intensity projection shared with the photo lane: `lerp(original, lutOut,
     * intensity/100)`; 100 = exact cube output, 0 = untouched. The shader uniform is this factor.
     */
    fun intensityFactor(look: VideoLutLook): Float = look.intensity / 100f
}

/**
 * The pure reference sampler: trilinear over the parsed cube plus the intensity blend —
 * the exact arithmetic the GL realization is tested against. Clamped [0,1] in and out.
 */
object VideoLutSampler {

    /**
     * Samples the look at an RGB triple in [0,1]: the trilinear cube output blended with
     * the input by the intensity factor. A null look is the identity.
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
