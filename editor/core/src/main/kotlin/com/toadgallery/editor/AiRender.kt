package com.toadgallery.editor

/**
 * AI pixel kernels + the stage entry: the four AI stages are PIXEL STAGES
 * between sharpen and the regions stage (which stays FINAL). Stage order is
 * semantic: erase first (later stages see the restored surroundings), then sky,
 * bokeh over the replaced world, retouch polishes the subject last among the AI
 * stages; regions stay final so a mosaic over a retouched face is still a mosaic.
 *
 * Kernels are integer-deterministic, clamped windows, floor division; every
 * read is over a SNAPSHOT of the grid the kernel receives. Pure Kotlin,
 * JVM-tested. No model runs here: the stages replay over the artifacts the
 * inference seam produced at commit time (`EditAiEngine.kt`) and the store
 * persisted (`EditAiStore.kt`).
 */

/** What a sky id resolves to at render time: authored parametric code, or imported pixels. */
sealed interface SkySource {
    /** A `BundledSkies` id; the stage renders it pure at the needed size. */
    data class Parametric(val skyId: String) : SkySource

    /** An imported sky the caller decoded (the `ImportedSkyStore` registry). */
    class Pixels(val pixels: IntArray, val width: Int, val height: Int) : SkySource
}

/**
 * Render-time bundle the AI stages consume: the artifacts loaded from the store
 * (keys resolved by the caller), the resolved sky source, and the retouch
 * consent state. A missing entry degrades — that stage renders nothing, never
 * a crash (the frame/sticker tolerance contract).
 */
class EditAiStageInputs(
    /** The refined erase masks, by erase layer id. */
    val eraseMasks: Map<String, FractionRleMask>,
    /** The erase fills, by erase layer id. */
    val eraseFills: Map<String, FillPatch>,
    /** The sky mask for the sky selection (null = the stage degrades to no replacement). */
    val skyMask: FractionRleMask?,
    /** The resolved sky source (parametric or imported pixels; null = degrade). */
    val skySource: SkySource?,
    /** The bokeh depth grid (null = the stage degrades to no blur). */
    val depth: DepthGrid?,
    /** The retouch face boxes (null = the stage degrades to no retouch). */
    val faceBoxes: FaceBoxes?,
    /**
     * The retouch consent gate: the stage REFUSES when false even with a
     * restored sidecar — the layer stays in the recipe, the stage skips.
     * `RetouchConsent.kt` owns the machine.
     */
    val retouchConsentGranted: Boolean,
) {
    companion object {
        /** The absent bundle: every AI stage runs nothing. */
        val ABSENT = EditAiStageInputs(
            eraseMasks = emptyMap(),
            eraseFills = emptyMap(),
            skyMask = null,
            skySource = null,
            depth = null,
            faceBoxes = null,
            retouchConsentGranted = false,
        )
    }
}

/**
 * The stage entry the pipeline calls (the regions' sibling): every AI container
 * in the documented order, each degrading honestly when its resolved inputs are
 * absent. Absent containers run nothing.
 */
object AiStages {

    fun applyAll(pixels: IntArray, width: Int, height: Int, recipe: EditRecipe, inputs: EditAiStageInputs) {
        // ---- AI erase ----
        val erase = recipe.aiErase
        if (erase != null && !erase.isIdentity) {
            for (layer in erase.layers) {
                val mask = inputs.eraseMasks[layer.id] ?: continue // degrade: render nothing
                val fill = inputs.eraseFills[layer.id] ?: continue
                AiKernels.pasteEraseFill(pixels, width, height, mask, fill)
            }
        }
        // ---- AI sky ----
        val sky = recipe.sky
        if (sky != null && inputs.skyMask != null && inputs.skySource != null) {
            // Source resolves in-kernel (parametric at box size; imported
            // validates dims); unresolvable returns early — never a crash.
            AiKernels.applySkyBlend(
                pixels, width, height, inputs.skyMask, inputs.skySource, sky.opacityPct,
            )
        }
        // ---- AI bokeh ----
        val bokeh = recipe.bokeh
        if (bokeh != null && inputs.depth != null) {
            AiKernels.applyVariableBlur(
                pixels, width, height, inputs.depth, bokeh.blurStrengthPct, bokeh.focalDepthNorm,
            )
        }
        // ---- AI retouch ----
        val retouch = recipe.retouch
        if (retouch != null && inputs.faceBoxes != null && inputs.retouchConsentGranted) {
            // Consent-off refuses even with a restored sidecar: the layer
            // stays in the recipe, the stage skips.
            AiKernels.applyRetouch(pixels, width, height, inputs.faceBoxes, retouch)
        }
    }
}

/**
 * The pure kernels: integer-deterministic, hand-grid-pinned; every kernel reads
 * a SNAPSHOT of the grid it receives.
 */
object AiKernels {

    /**
     * The erase paste: each masked pixel takes the fill patch's nearest sample
     * over its placement (floor mapping); patch alpha forced opaque — the fill
     * is generated content, not a transparency layer.
     */
    fun pasteEraseFill(pixels: IntArray, width: Int, height: Int, mask: FractionRleMask, fill: FillPatch) {
        val gridMask = mask.maskAt(width, height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!gridMask[row + x]) continue
                val fx = (x + 0.5) / width
                val fy = (y + 0.5) / height
                pixels[row + x] = fill.sampleAt(fx, fy) or 0xFF000000.toInt()
            }
        }
    }

    /**
     * The sky cover-crop blend: the sky is scaled to COVER the mask's bounding
     * box (center-crop — a stretched sky reads wrong; the larger relative
     * dimension wins, the other is centered and cropped), blended over masked
     * pixels only at the selection's opacity: out = (src * (100 - o) + sky * o)
     * / 100 per channel, integer floor.
     */
    fun applySkyBlend(
        pixels: IntArray,
        width: Int,
        height: Int,
        mask: FractionRleMask,
        source: SkySource,
        opacityPct: Int,
    ) {
        if (opacityPct <= 0) return
        val gridMask = mask.maskAt(width, height)
        val bounds = mask.boundsAt(width, height) ?: return
        val bw = (bounds.x1 - bounds.x0).coerceAtLeast(1)
        val bh = (bounds.y1 - bounds.y0).coerceAtLeast(1)
        // Parametric renders at the box size (its cover-crop is the identity);
        // imported keeps its own dims, validated here (unresolvable → early return).
        val srcPixels: IntArray
        val sw: Int
        val sh: Int
        when (source) {
            is SkySource.Parametric -> {
                val rendered = BundledSkies.render(source.skyId, bw, bh) ?: return
                srcPixels = rendered
                sw = bw
                sh = bh
            }
            is SkySource.Pixels -> {
                if (source.width < 1 || source.height < 1 ||
                    source.pixels.size != source.width * source.height
                ) return
                srcPixels = source.pixels
                sw = source.width
                sh = source.height
            }
        }
        // The center-crop sub-rect of the source with the box's aspect.
        val cw: Int
        val ch: Int
        val cx0: Int
        val cy0: Int
        if (sw.toLong() * bh > sh.toLong() * bw) {
            // The source is relatively wider: crop its width.
            cw = ((sh * bw) / bh).coerceIn(1, sw)
            ch = sh
            cx0 = (sw - cw) / 2
            cy0 = 0
        } else {
            ch = ((sw * bh) / bw).coerceIn(1, sh)
            cw = sw
            cx0 = 0
            cy0 = (sh - ch) / 2
        }
        for (y in bounds.y0 until bounds.y1) {
            val row = y * width
            for (x in bounds.x0 until bounds.x1) {
                if (!gridMask[row + x]) continue
                val sx = (cx0 + ((x - bounds.x0) * cw) / bw).coerceIn(cx0, cx0 + cw - 1)
                val sy = (cy0 + ((y - bounds.y0) * ch) / bh).coerceIn(cy0, cy0 + ch - 1)
                val skyPx = srcPixels[sy * sw + sx]
                val srcPx = pixels[row + x]
                val r = ((srcPx shr 16) and 0xFF) * (100 - opacityPct) / 100 +
                    ((skyPx shr 16) and 0xFF) * opacityPct / 100
                val g = ((srcPx shr 8) and 0xFF) * (100 - opacityPct) / 100 +
                    ((skyPx shr 8) and 0xFF) * opacityPct / 100
                val b = (srcPx and 0xFF) * (100 - opacityPct) / 100 +
                    (skyPx and 0xFF) * opacityPct / 100
                pixels[row + x] = (srcPx and 0xFF000000.toInt()) or
                    (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
    }

    /**
     * The depth-driven variable blur (bokeh): per-pixel radius
     * r = maxRadius * |depth - focal| / 255 (integer division; 0 leaves the
     * pixel untouched — the focal band stays sharp), maxRadius from
     * max(1, strengthPct x minEdge / 1000). Separable box blur: a horizontal
     * then a vertical pass, each over a SNAPSHOT, each window clamped to the
     * grid and averaged by integer floor division — the prefix-sum windows
     * equal the naive per-pixel loops exactly (pinned in `AiRenderTest`).
     */
    fun applyVariableBlur(
        pixels: IntArray,
        width: Int,
        height: Int,
        depth: DepthGrid,
        blurStrengthPct: Int,
        focalDepthNorm: Double,
    ) {
        if (blurStrengthPct <= 0) return
        val minEdge = minOf(width, height)
        val maxRadius = (blurStrengthPct * minEdge / 1000).coerceAtLeast(1)
        val focal8 = (focalDepthNorm * 255.0 + 0.5).toInt().coerceIn(0, 255)
        fun radiusAt(x: Int, y: Int): Int {
            val d = depth.sampleAt(x, y, width, height)
            val dist = if (d > focal8) d - focal8 else focal8 - d
            return maxRadius * dist / 255
        }
        // Pass 1: horizontal, over a snapshot.
        val snapshot = pixels.copyOf()
        val prefixR = IntArray(width + 1)
        val prefixG = IntArray(width + 1)
        val prefixB = IntArray(width + 1)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val c = snapshot[row + x]
                prefixR[x + 1] = prefixR[x] + ((c shr 16) and 0xFF)
                prefixG[x + 1] = prefixG[x] + ((c shr 8) and 0xFF)
                prefixB[x + 1] = prefixB[x] + (c and 0xFF)
            }
            for (x in 0 until width) {
                val r = radiusAt(x, y)
                if (r == 0) continue
                val lo = (x - r).coerceAtLeast(0)
                val hi = (x + r).coerceAtMost(width - 1)
                val count = hi - lo + 1
                val sr = prefixR[hi + 1] - prefixR[lo]
                val sg = prefixG[hi + 1] - prefixG[lo]
                val sb = prefixB[hi + 1] - prefixB[lo]
                pixels[row + x] = (pixels[row + x] and 0xFF000000.toInt()) or
                    (((sr / count) and 0xFF) shl 16) or (((sg / count) and 0xFF) shl 8) or ((sb / count) and 0xFF)
            }
        }
        // Pass 2: vertical, over a snapshot of pass 1.
        val snapshot2 = pixels.copyOf()
        val colPrefixR = IntArray(height + 1)
        val colPrefixG = IntArray(height + 1)
        val colPrefixB = IntArray(height + 1)
        for (x in 0 until width) {
            for (y in 0 until height) {
                val c = snapshot2[y * width + x]
                colPrefixR[y + 1] = colPrefixR[y] + ((c shr 16) and 0xFF)
                colPrefixG[y + 1] = colPrefixG[y] + ((c shr 8) and 0xFF)
                colPrefixB[y + 1] = colPrefixB[y] + (c and 0xFF)
            }
            for (y in 0 until height) {
                val r = radiusAt(x, y)
                if (r == 0) continue
                val lo = (y - r).coerceAtLeast(0)
                val hi = (y + r).coerceAtMost(height - 1)
                val count = hi - lo + 1
                val sr = colPrefixR[hi + 1] - colPrefixR[lo]
                val sg = colPrefixG[hi + 1] - colPrefixG[lo]
                val sb = colPrefixB[hi + 1] - colPrefixB[lo]
                pixels[y * width + x] = (pixels[y * width + x] and 0xFF000000.toInt()) or
                    (((sr / count) and 0xFF) shl 16) or (((sg / count) and 0xFF) shl 8) or ((sb / count) and 0xFF)
            }
        }
    }

    /**
     * The retouch pass: per face box, in list order, smoothing -> eye-band
     * brighten -> the slim displacement warp. Each sub-kernel reads a SNAPSHOT
     * of the grid it receives (its own writes never feed its own reads).
     */
    fun applyRetouch(pixels: IntArray, width: Int, height: Int, boxes: FaceBoxes, retouch: RetouchSelection) {
        for (box in boxes.boxes) {
            val x0 = (box.left * width).toInt().coerceIn(0, width - 1)
            val x1 = (box.right * width).toInt().coerceIn(0, width - 1)
            val y0 = (box.top * height).toInt().coerceIn(0, height - 1)
            val y1 = (box.bottom * height).toInt().coerceIn(0, height - 1)
            if (x1 <= x0 || y1 <= y0) continue
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            // 1. Skin smoothing: uniform box blur over the box's pixels,
            //    radius over the box's min edge.
            if (retouch.smoothingPct > 0) {
                val radius = (retouch.smoothingPct * minOf(bw, bh) / 1000).coerceAtLeast(1)
                boxBlurRegion(pixels, width, height, x0, y0, x1, y1, radius)
            }
            // 2. Eye-band brighten: box rows 20%..45% (the box-only artifact
            //    carries no landmarks), delta = eyeBrightenPct * 255 / 200, clamped.
            if (retouch.eyeBrightenPct > 0) {
                val delta = retouch.eyeBrightenPct * 255 / 200
                val bandTop = y0 + (bh * 20) / 100
                val bandEnd = y0 + (bh * 45) / 100
                for (y in bandTop.coerceIn(y0, y1) until bandEnd.coerceAtMost(y1 + 1)) {
                    val row = y * width
                    for (x in x0..x1) {
                        val c = pixels[row + x]
                        val r = (((c shr 16) and 0xFF) + delta).coerceAtMost(255)
                        val g = (((c shr 8) and 0xFF) + delta).coerceAtMost(255)
                        val b = ((c and 0xFF) + delta).coerceAtMost(255)
                        pixels[row + x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            // 3. Slim warp: each column samples further out
            //    (sampleX = x0 + (x - x0) * (100 + slim) / 100), pulling the
            //    content toward the centre; clamped inside the box (LOCAL warp,
            //    not the geometry toolset).
            if (retouch.faceSlimPct > 0) {
                val snapshot = pixels.copyOf()
                for (y in y0..y1) {
                    val row = y * width
                    for (x in x0..x1) {
                        val sampleX = x0 + ((x - x0) * (100 + retouch.faceSlimPct)) / 100
                        pixels[row + x] = snapshot[row + sampleX.coerceIn(x0, x1)]
                    }
                }
            }
        }
    }

    /**
     * A uniform box blur restricted to a rect (the retouch smoothing):
     * separable passes, each over a snapshot, each window clamped to the RECT
     * (not the grid — the smoothing must not bleed the box's surroundings into
     * the face), averaged by integer floor division.
     */
    private fun boxBlurRegion(
        pixels: IntArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        radius: Int,
    ) {
        val rw = x1 - x0 + 1
        // Horizontal pass over a snapshot.
        val snapshot = pixels.copyOf()
        val prefixR = IntArray(rw + 1)
        val prefixG = IntArray(rw + 1)
        val prefixB = IntArray(rw + 1)
        for (y in y0..y1) {
            val row = y * width
            for (i in 0 until rw) {
                val c = snapshot[row + x0 + i]
                prefixR[i + 1] = prefixR[i] + ((c shr 16) and 0xFF)
                prefixG[i + 1] = prefixG[i] + ((c shr 8) and 0xFF)
                prefixB[i + 1] = prefixB[i] + (c and 0xFF)
            }
            for (x in x0..x1) {
                val i = x - x0
                val lo = (i - radius).coerceAtLeast(0)
                val hi = (i + radius).coerceAtMost(rw - 1)
                val count = hi - lo + 1
                val sr = prefixR[hi + 1] - prefixR[lo]
                val sg = prefixG[hi + 1] - prefixG[lo]
                val sb = prefixB[hi + 1] - prefixB[lo]
                pixels[row + x] = (pixels[row + x] and 0xFF000000.toInt()) or
                    (((sr / count) and 0xFF) shl 16) or (((sg / count) and 0xFF) shl 8) or ((sb / count) and 0xFF)
            }
        }
        // Vertical pass over a snapshot of the horizontal result.
        val snapshot2 = pixels.copyOf()
        val rh = y1 - y0 + 1
        val colPrefixR = IntArray(rh + 1)
        val colPrefixG = IntArray(rh + 1)
        val colPrefixB = IntArray(rh + 1)
        for (x in x0..x1) {
            for (i in 0 until rh) {
                val c = snapshot2[(y0 + i) * width + x]
                colPrefixR[i + 1] = colPrefixR[i] + ((c shr 16) and 0xFF)
                colPrefixG[i + 1] = colPrefixG[i] + ((c shr 8) and 0xFF)
                colPrefixB[i + 1] = colPrefixB[i] + (c and 0xFF)
            }
            for (y in y0..y1) {
                val i = y - y0
                val lo = (i - radius).coerceAtLeast(0)
                val hi = (i + radius).coerceAtMost(rh - 1)
                val count = hi - lo + 1
                val sr = colPrefixR[hi + 1] - colPrefixR[lo]
                val sg = colPrefixG[hi + 1] - colPrefixG[lo]
                val sb = colPrefixB[hi + 1] - colPrefixB[lo]
                pixels[y * width + x] = (pixels[y * width + x] and 0xFF000000.toInt()) or
                    (((sr / count) and 0xFF) shl 16) or (((sg / count) and 0xFF) shl 8) or ((sb / count) and 0xFF)
            }
        }
    }
}
