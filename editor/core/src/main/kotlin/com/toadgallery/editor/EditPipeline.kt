package com.toadgallery.editor

/**
 * The edit pipeline: applies a whole [EditRecipe] to a pixel grid in ONE documented stage
 * order, so preview and export agree byte-for-byte by construction:
 *
 *  1. flips (horizontal, then vertical)
 *  2. straighten (expanded canvas, transparent fill)
 *  3. perspective quad warp
 *  4. quarter turns (90-degree steps)
 *  5. crop (the normalized rect over the post-geometry frame)
 *  6. the composed tone LUT (exposure -> brightness -> contrast ->
 *     highlights -> shadows -> curve, one pass; the master curve composes
 *     after the five sliders; the per-channel R/G/B curves compose over
 *     the master --- the three-table apply)
 *  7. saturation
 *  8. temperature / tint
 *  9. per-channel HSL bands
 * 10. LUT filter (the `.cube` look at its intensity)
 * 11. vignette
 * 12. sharpen (unsharp mask)
 * 13. the AI pixel stages: erase -> sky -> bokeh -> retouch (erase first
 *     so later stages see the restored surroundings; sky next so the new
 *     sky exists before the depth-driven blur; bokeh over the replaced
 *     world; retouch polishes the subject last among the AI stages)
 * 14. mosaic/blur regions (the painted regions censor the FULLY graded
 *     pixels; the FINAL pixel stage --- no downstream stage re-processes
 *     the censored output, so sharpening a mosaic edge cannot halo it)
 *
 * The compositing passes (text over everything; stickers below text; strokes between) are NOT
 * stages here: they run after `apply` returns, over the OUTPUT canvas.
 *
 * The look stage sits AFTER the colour grade (a film stock over a developed negative) and
 * BEFORE the spatial effects (vignette/sharpen are position masks; a colour-only look must
 * not shift what they see).
 *
 * The identity recipe returns the input grid EXACTLY (no stage runs), so a saved copy of an
 * untouched session is a pure re-encode; a look at intensity 0 is dialed out (same fast path).
 */
object EditPipeline {

    const val STAGE_ORDER: String =
        "flip -> straighten -> perspective -> quarterTurns -> crop -> " +
            "tone LUT -> saturation -> temperature/tint -> HSL bands -> " +
            "LUT filter -> vignette -> sharpen -> AI erase -> AI sky -> " +
            "AI bokeh -> AI retouch -> mosaic/blur regions"

    /**
     * Applies [recipe] to the grid. [filter] is the caller-RESOLVED `.cube` look for
     * `recipe.lut` (the pipeline is pure --- it never touches assets); the stage runs only
     * when the selection, the resolved look and the id PAIR UP (`filter.id == lutId`) and
     * the intensity is above zero; anything else degrades to no-look, never a crash.
     *
     * [ai] is the caller-RESOLVED artifact bundle (no model ever runs here): a missing
     * entry degrades that stage to nothing, never a crash.
     */
    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        recipe: EditRecipe,
        filter: CubeLut? = null,
        ai: EditAiStageInputs? = null,
    ): GeometryResult {
        if (recipe.isIdentity) return GeometryResult(pixels, width, height)

        // ---- Geometry ----
        var current = pixels
        var w = width
        var h = height
        if (recipe.horizontalFlip) current = flipPixels(current, w, h, horizontal = true)
        if (recipe.verticalFlip) current = flipPixels(current, w, h, horizontal = false)
        if (recipe.straightenDeg != 0.0) {
            val result = straightenPixels(current, w, h, recipe.straightenDeg)
            current = result.pixels; w = result.width; h = result.height
        }
        if (recipe.perspective != PerspectiveQuad.IDENTITY) {
            val result = perspectiveWarp(current, w, h, recipe.perspective)
            current = result.pixels; w = result.width; h = result.height
        }
        if (recipe.quarterTurns % 4 != 0) {
            val rotated = PixelAdjuster.rotateQuarterTurns(current, w, h, recipe.quarterTurns)
            current = rotated.pixels; w = rotated.width; h = rotated.height
        }
        if (recipe.crop != CropRect(0.0, 0.0, 1.0, 1.0)) {
            val rect = recipe.crop.toPixels(w, h)
            val cropped = cropPixels(current, w, h, rect)
            current = cropped.pixels; w = cropped.width; h = cropped.height
        }

        // ---- Tone (one composed per-channel pass) ----
        // The gate opens for any tone slider OR a non-identity curve. The master curve
        // composes after the shadows stage (LutBuilder.ORDER); a state with CHANNEL curves
        // takes the three-table path (LutBuilder.buildChannels + PixelOps.applyChannelLuts);
        // whites/blacks join the same seven-stage build (a 0 value is a zero term, so older
        // recipes bake byte-identical tables).
        val curves = recipe.toneCurves
        if (listOf(
                recipe.exposure,
                recipe.brightness,
                recipe.contrast,
                recipe.highlights,
                recipe.shadows,
                recipe.whites,
                recipe.blacks,
            ).any { it != 0 } ||
            curves?.isIdentity == false
        ) {
            if (curves != null && curves.hasChannelCurves) {
                PixelOps.applyChannelLuts(
                    current,
                    LutBuilder.buildChannels(
                        brightness = recipe.brightness,
                        contrast = recipe.contrast,
                        exposure = recipe.exposure,
                        highlights = recipe.highlights,
                        shadows = recipe.shadows,
                        whites = recipe.whites,
                        blacks = recipe.blacks,
                        toneCurves = curves,
                    ),
                )
            } else {
                val lut = LutBuilder.build(
                    brightness = recipe.brightness,
                    contrast = recipe.contrast,
                    exposure = recipe.exposure,
                    highlights = recipe.highlights,
                    shadows = recipe.shadows,
                    whites = recipe.whites,
                    blacks = recipe.blacks,
                    toneCurves = curves,
                )
                PixelAdjuster.applyTo(current, lut)
            }
        }

        // ---- Colour ----
        // Vibrance leads saturation: the luma-weighted pull runs before the flat pull,
        // so each stage sees the prior stage's output.
        PixelOps.applyVibrance(current, recipe.vibrance)
        PixelOps.applySaturation(current, recipe.saturation)
        PixelOps.applyTemperatureTint(current, recipe.temperature, recipe.tint)
        applyHslBands(current, recipe.hslBands)

        // ---- Filter (the look over the graded pixels) ----
        val selection = recipe.lut
        if (selection != null && selection.intensity > 0 &&
            filter != null && filter.id == selection.lutId
        ) {
            filter.applyTo(current, selection.intensity / 100.0)
        }

        // ---- Grade + detail (display-space: after the crop) ----
        applyVignette(current, w, h, recipe.vignette)
        applySharpen(current, w, h, recipe.sharpness)

        // ---- The AI pixel stages (erase -> sky -> bokeh -> retouch) ----
        // They replay over artifacts produced at COMMIT time; no model runs during render.
        // Grid-relative like the vignette: exact in the export (stage grid = output canvas),
        // approximate in the preview under an active crop. An absent bundle or container
        // runs NOTHING.
        if (ai != null) {
            AiStages.applyAll(current, w, h, recipe, ai)
        }

        // ---- Regions (the final pixel stage, after the AI tail) ----
        // The painted regions censor the fully graded grid; grid-relative like the
        // vignette (exact in the export, approximate in the preview under a crop).
        recipe.mosaicRegions?.let { regions ->
            if (!regions.isIdentity) {
                RegionPass.applyAll(current, w, h, regions)
            }
        }

        return GeometryResult(current, w, h)
    }
}

/**
 * The pure geometry plan: the post-geometry frame dimensions (flips, straighten, perspective,
 * quarter turns --- WITHOUT the crop) plus the crop's pixel rect over that frame. One
 * computation shared by the preview overlay and the export --- they can never disagree.
 */
object EditGeometryPlan {

    data class Plan(val postGeometryWidth: Int, val postGeometryHeight: Int, val cropPixelRect: PixelRect)

    fun plan(recipe: EditRecipe, sourceWidth: Int, sourceHeight: Int): Plan {
        var w = sourceWidth
        var h = sourceHeight
        if (recipe.straightenDeg != 0.0) {
            // The same expanded-canvas math straightenPixels uses.
            val rad = recipe.straightenDeg * kotlin.math.PI / 180.0
            val ac = kotlin.math.abs(kotlin.math.cos(rad))
            val asin = kotlin.math.abs(kotlin.math.sin(rad))
            val outW = (w * ac + h * asin).let { kotlin.math.ceil(it).toInt().coerceAtLeast(1) }
            val outH = (w * asin + h * ac).let { kotlin.math.ceil(it).toInt().coerceAtLeast(1) }
            w = outW; h = outH
        }
        if (recipe.quarterTurns % 2 == 1) {
            val tmp = w; w = h; h = tmp
        }
        val cropRect = recipe.crop.toPixels(w, h)
        return Plan(w, h, cropRect)
    }
}
