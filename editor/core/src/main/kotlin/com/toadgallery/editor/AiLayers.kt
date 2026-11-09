package com.toadgallery.editor

import kotlin.math.ceil

/**
 * The r41 AI recipe containers (Phase 10 sub-scope A; the design contract
 * is `docs/phase-10/edit-v3-ai.md`, decision 4) --- layer types 6-9, the
 * additive families after the r38 frame:
 *
 *  - `aiErase` (6): a LIST, the region family's shape (per-layer id +
 *    the painted seed points, the shared stride-thinning cap, the `newId`
 *    prefix convention);
 *  - `sky` (7): SINGLE, the `LutSelection`/`FrameSelection` shape;
 *  - `bokeh` (8): SINGLE;
 *  - `retouch` (9): SINGLE.
 *
 * All four are PIXEL STAGE families, not compositing passes: they run in
 * `EditPipeline`'s documented tail (erase -> sky -> bokeh -> retouch ->
 * the regions, which stay FINAL), over artifacts produced ONCE at commit
 * time by the inference seam (`EditAiEngine.kt`) and persisted in the
 * derived-artifact store (`EditAiStore.kt`). The recipe references
 * artifacts BY KEY; an unresolvable key round-trips verbatim and renders
 * NOTHING at render time --- the frame/sticker tolerance contract, never
 * a crash.
 *
 * Pure Kotlin, JVM-tested (the repo's ADR-0011 convention).
 */

/**
 * The recipe's additive magic-erase container: absent (null) = a pre-r41
 * sidecar or a clean session; empty normalises BACK to absent (the family
 * rule). A LIST like the region family: multiple erase regions, list
 * order = render order.
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
 * One erase layer: the painted seed (kept for re-edit and re-apply; the
 * inference at commit refines it) plus the artifact key the render
 * resolves. The `e` prefix keeps the families collision-free.
 */
data class AiEraseLayer(
    val id: String,
    /** 3..MAX_POINTS seed points, fractions of the output canvas (the family anchor convention). */
    val points: List<Point>,
    /**
     * The derived-artifact key (the store's `layerId + content hash`
     * convention): the refined mask + the inpaint fill, written together.
     * An unresolvable key renders nothing (the tolerance contract).
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

        /**
         * The next free session id over `existing` ("e1", "e2", ...): the
         * family `newId` convention, the `e` prefix keeping the families
         * collision-free by construction.
         */
        fun newId(existing: Collection<String>): String {
            var max = 0
            for (id in existing) {
                val n = id.removePrefix("e").toIntOrNull()
                if (n != null && n > max) max = n
            }
            return "e${max + 1}"
        }

        /**
         * The commit-time thinning --- the stride rule the stroke family
         * defined, duplicated per family (the stores' convention: the
         * families stay independent, the algorithm documented in both).
         */
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
 * The sky replacement selection (7), the `LutSelection`/`FrameSelection`
 * shape: ONE sky at a time (the mounting rule). The mask artifact comes
 * from the saliency detection at commit; the sky bitmap is the registry's
 * (bundled parametric OR user-imported through `ImportedSkyStore`).
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
 * The bokeh selection (8), SINGLE: the depth artifact comes from the
 * depth model at commit; strength + focal depth are pure re-renders over
 * it (no re-inference on any slider move).
 */
data class BokehSelection(
    /** The depth-grid artifact key; unresolvable renders nothing. */
    val depthRef: String,
    /** 1..100, the strength slider (the r37 kernels' formula at apply). */
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
 * The face-retouch selection (9), SINGLE, OPT-IN per ADR-0010 §5: one
 * global selection over the detected faces. The three task-named
 * controls default 0 = no contribution; the boxes artifact comes from
 * the face localization at commit. The stage REFUSES under consent-off
 * even with a restored sidecar (the layer stays verbatim in the recipe,
 * the stage skips) --- `RetouchConsent.kt` owns that machine.
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
