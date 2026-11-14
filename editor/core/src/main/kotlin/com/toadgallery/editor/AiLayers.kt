package com.toadgallery.editor

import kotlin.math.ceil

/**
 * The AI recipe containers (layer types 6-9): aiErase (a LIST of regions), sky, bokeh,
 * retouch (SINGLE each) — pixel stages over commit-time artifacts referenced BY KEY;
 * an unresolvable key round-trips verbatim and renders nothing. Pure, JVM-tested.
 */

/**
 * The magic-erase container: empty normalises back to absent (the family rule). A LIST like
 * the region family — multiple erase regions, list order = render order.
 */
data class AiEraseLayers(val layers: List<AiEraseLayer>) {
    init {
        require(layers.size == layers.map { it.id }.toSet().size) { "duplicate erase layer id" }
    }

    val isIdentity: Boolean get() = layers.isEmpty()

    companion object {
        val EMPTY = AiEraseLayers(emptyList())
    }
}

/**
 * One erase layer: the painted seed (kept for re-edit; commit-time inference refines it)
 * plus the artifact key the render resolves. The `e` id prefix keeps families collision-free.
 */
data class AiEraseLayer(
    val id: String,
    /** 3..MAX_POINTS seed points, fractions of the output canvas (the family anchor convention). */
    val points: List<Point>,
    /**
     * The derived-artifact key (store's `layerId + content hash`): refined mask + inpaint
     * fill, written together. An unresolvable key renders nothing.
     */
    val artifactRef: String = "",
) {
    init {
        require(id.isNotBlank()) { "layer id must not be blank" }
        require(id.length <= MAX_ID_LENGTH) { "layer id longer than $MAX_ID_LENGTH chars" }
        require(points.size in 3..MAX_POINTS) { "an erase seed needs 3..$MAX_POINTS points" }
    }

    /** A seed point: fractions of the output canvas (the family anchor convention). */
    data class Point(val x: Double, val y: Double) {
        init {
            require(x in 0.0..1.0) { "point x must be in 0..1" }
            require(y in 0.0..1.0) { "point y must be in 0..1" }
        }
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_POINTS = 512

        /** The next free session id ("e1", "e2", ...) over `existing`. */
        fun newId(existing: Collection<String>): String {
            var max = 0
            for (id in existing) {
                val n = id.removePrefix("e").toIntOrNull()
                if (n != null && n > max) max = n
            }
            return "e${max + 1}"
        }

        /** Commit-time stride thinning to `cap` points (per-family, same rule as the stroke family). */
        fun thin(points: List<Point>, cap: Int): List<Point> {
            require(cap >= 1) { "the thinning cap must be >= 1" }
            val n = points.size
            if (n <= cap) return points
            val stride = ceil(n / cap.toDouble()).toInt()
            val out = ArrayList<Point>(cap + 1)
            out.add(points[0])
            var i = stride
            while (i < n - 1) {
                out.add(points[i])
                i += stride
            }
            out.add(points[n - 1])
            return out
        }
    }
}

/**
 * The sky replacement selection, SINGLE (one sky at a time). The mask artifact comes from
 * commit-time saliency detection; the sky bitmap is the registry's (bundled or imported).
 */
data class SkySelection(
    /** The sky-mask artifact key; unresolvable renders nothing (degrades to no replacement). */
    val maskRef: String,
    /** The registry id: a `BundledSkies` id, or an `ImportedSkyStore` id. */
    val skyId: String,
    /** 0..100, the blend weight of the sky bitmap over the masked pixels. */
    val opacityPct: Int = DEFAULT_OPACITY_PCT,
) {
    init {
        require(maskRef.length <= MAX_REF_LENGTH) { "maskRef longer than $MAX_REF_LENGTH chars" }
        require(skyId.isNotBlank()) { "skyId must not be blank" }
        require(skyId.length <= MAX_ID_LENGTH) { "skyId longer than $MAX_ID_LENGTH chars" }
        require(opacityPct in 0..100) { "opacityPct must be in 0..100" }
    }

    /** A dialed-out opacity (0) still shows the mask's honest nothing --- the stage runs it. */
    val isIdentity: Boolean get() = false

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_REF_LENGTH = 96
        const val DEFAULT_OPACITY_PCT = 100
    }
}

/**
 * The bokeh selection, SINGLE: the depth artifact comes from commit-time depth inference;
 * strength + focal depth are pure re-renders (no re-inference on slider moves).
 */
data class BokehSelection(
    /** The depth-grid artifact key; unresolvable renders nothing. */
    val depthRef: String,
    /** 1..100, the strength slider. */
    val blurStrengthPct: Int = DEFAULT_BLUR_STRENGTH_PCT,
    /** 0..1, the focal depth in the artifact's normalised 0..255 space (larger = closer). */
    val focalDepthNorm: Double = DEFAULT_FOCAL_DEPTH_NORM,
) {
    init {
        require(depthRef.length <= SkySelection.MAX_REF_LENGTH) { "depthRef longer than ${SkySelection.MAX_REF_LENGTH} chars" }
        require(blurStrengthPct in 1..100) { "blurStrengthPct must be in 1..100" }
        require(focalDepthNorm in 0.0..1.0) { "focalDepthNorm must be in 0..1" }
    }

    companion object {
        const val DEFAULT_BLUR_STRENGTH_PCT = 40
        const val DEFAULT_FOCAL_DEPTH_NORM = 0.5
    }
}

/**
 * The face-retouch selection, SINGLE, opt-in per ADR-0010 §5: one global selection over the
 * detected faces; controls default 0 = no contribution. The stage refuses under consent-off
 * (the layer stays verbatim in the recipe); `RetouchConsent.kt` owns that machine.
 */
data class RetouchSelection(
    /** The face-boxes artifact key; unresolvable renders nothing. */
    val boxesRef: String,
    /** 0..100, the skin smoothing strength. */
    val smoothingPct: Int = 0,
    /** 0..100, the eye-band brighten. */
    val eyeBrightenPct: Int = 0,
    /** 0..100, the horizontal slim displacement. */
    val faceSlimPct: Int = 0,
) {
    init {
        require(boxesRef.length <= SkySelection.MAX_REF_LENGTH) { "boxesRef longer than ${SkySelection.MAX_REF_LENGTH} chars" }
        require(smoothingPct in 0..100) { "smoothingPct must be in 0..100" }
        require(eyeBrightenPct in 0..100) { "eyeBrightenPct must be in 0..100" }
        require(faceSlimPct in 0..100) { "faceSlimPct must be in 0..100" }
    }

    companion object {
        const val MAX_REF_LENGTH = 96
    }
}
