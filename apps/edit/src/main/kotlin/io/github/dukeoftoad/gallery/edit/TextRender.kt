package io.github.dukeoftoad.gallery.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The text compositing pass (r34, Phase 7 v2) --- the ONLY place text is
 * rendered, shared by the live preview and the export (the contract's
 * "same code path, no second renderer"): both callers hand the SAME
 * function their target bitmap, the recipe's layers and their
 * output-canvas rectangle (the export: the pipeline's full output at
 * origin 0,0; the preview: the crop rect over the post-geometry frame,
 * so text lands where the cropped output will carry it).
 *
 * Pipeline placement (`text-tool.md` decision 3): a compositing pass
 * AFTER the whole pixel pipeline (geometry -> adjustments -> LUT ->
 * vignette -> sharpen), not a pixel stage --- text sits above everything,
 * and its anchor fractions belong to the OUTPUT canvas, so a later crop
 * re-expresses the center in the new canvas instead of moving it.
 *
 * Tolerance tiers, mirroring the r33 look contract: an unresolvable
 * fontId renders in the DEFAULT face; a broken default asset falls to the
 * PLATFORM default face; a blank-text layer renders NOTHING but keeps its
 * recipe state (the user's draft survives round-trips). Never a crash,
 * never a dropped layer.
 */
object TextCompositor {

    fun draw(
        target: Bitmap,
        layers: List<TextLayer>,
        fontFor: (String) -> Typeface?,
        originX: Float,
        originY: Float,
        canvasW: Float,
        canvasH: Float,
    ) {
        if (layers.isEmpty()) return
        val specs = TextLayout.place(layers, originX, originY, canvasW, canvasH)
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (spec in specs) {
            if (spec.layer.text.isBlank()) continue
            paint.typeface = fontFor(spec.resolvedFontId)
            paint.textSize = spec.sizePx
            paint.textAlign = Paint.Align.CENTER
            // The color's own alpha byte is authored data; the paint alpha
            // is the tested product of that byte and the opacity slider.
            paint.color = spec.layer.colorArgb.toInt()
            paint.alpha = spec.alpha
            // Vertical centring on the anchor: the standard ascent/descent
            // baseline correction (the anchor is the VISUAL middle of the
            // line, not its baseline).
            val baseline = spec.centerY - (paint.ascent() + paint.descent()) / 2f
            canvas.save()
            canvas.rotate(spec.layer.rotationDeg.toFloat(), spec.centerX, spec.centerY)
            canvas.drawText(spec.layer.text, spec.centerX, baseline, paint)
            canvas.restore()
        }
    }
}
