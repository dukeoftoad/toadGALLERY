package io.github.dukeoftoad.gallery.edit

/**
 * The edit pipeline (r30, Phase 6 v1): applies a whole [EditRecipe] to a
 * pixel grid in ONE documented stage order, so preview and export agree
 * byte-for-byte by construction:
 *
 *  1. flips (horizontal, then vertical)
 *  2. straighten (expanded canvas, transparent fill)
 *  3. perspective quad warp
 *  4. quarter turns (90-degree steps)
 *  5. crop (the normalized rect over the post-geometry frame)
 *  6. the composed tone LUT (exposure -> brightness -> contrast ->
 *     highlights -> shadows, one pass)
 *  7. saturation
 *  8. temperature / tint
 *  9. per-channel HSL bands
 * 10. vignette
 * 11. sharpen (unsharp mask)
 *
 * The identity recipe returns the input grid EXACTLY (no stage runs), so a
 * saved copy of an untouched session is a pure re-encode --- and the
 * crop-presets UI can trust the geometry plan below for its overlay math.
 */
object EditPipeline {

    const val STAGE_ORDER: String =
        "flip -> straighten -> perspective -> quarterTurns -> crop -> " +
            "tone LUT -> saturation -> temperature/tint -> HSL bands -> vignette -> sharpen"

    fun apply(
        pixels: IntArray,
        width: Int,
        height: Int,
        recipe: EditRecipe,
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
        if (listOf(recipe.exposure, recipe.brightness, recipe.contrast, recipe.highlights, recipe.shadows)
            .any { it != 0 }
        ) {
            val lut = LutBuilder.build(
                brightness = recipe.brightness,
                contrast = recipe.contrast,
                exposure = recipe.exposure,
                highlights = recipe.highlights,
                shadows = recipe.shadows,
            )
            PixelAdjuster.applyTo(current, lut)
        }

        // ---- Colour ----
        PixelOps.applySaturation(current, recipe.saturation)
        PixelOps.applyTemperatureTint(current, recipe.temperature, recipe.tint)
        applyHslBands(current, recipe.hslBands)

        // ---- Grade + detail (display-space: after the crop) ----
        applyVignette(current, w, h, recipe.vignette)
        applySharpen(current, w, h, recipe.sharpness)

        return GeometryResult(current, w, h)
    }
}

/**
 * The pure geometry plan: the post-geometry frame dimensions (flips ->
 * straighten -> perspective -> quarter turns, WITHOUT the crop) plus the
 * crop's pixel rect over that frame. One computation shared by the preview
 * overlay (draw the crop rect at the right place) and the export (crop
 * after the geometry) --- they can never disagree.
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
