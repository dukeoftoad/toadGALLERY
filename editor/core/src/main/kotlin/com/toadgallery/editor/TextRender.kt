package com.toadgallery.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The text compositing pass — the ONLY place text is rendered, shared by the
 * live preview and the export ("same code path, no second renderer"): both
 * callers hand the same target bitmap, the recipe's layers and their
 * output-canvas rect (export: the pipeline's full output at origin 0,0;
 * preview: the crop rect over the post-geometry frame, so text lands where the
 * cropped output will carry it).
 *
 * Pipeline placement: a compositing pass AFTER the whole pixel pipeline, not a
 * pixel stage — text sits above everything, and its anchor fractions belong to
 * the OUTPUT canvas (a later crop re-expresses the center in the new canvas).
 *
 * Tolerance: an unresolvable fontId renders in the DEFAULT face; a broken
 * default asset falls to the PLATFORM default face; a blank-text layer renders
 * NOTHING but keeps its recipe state. Never a crash, never a dropped layer.
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
            // The color's alpha byte is authored; the paint alpha is its product with the opacity slider.
            paint.color = spec.layer.colorArgb.toInt()
            paint.alpha = spec.alpha
            // Vertical centring on the anchor: the anchor is the VISUAL middle of the line, not its baseline.
            val baseline = spec.centerY - (paint.ascent() + paint.descent()) / 2f
            canvas.save()
            canvas.rotate(spec.layer.rotationDeg.toFloat(), spec.centerX, spec.centerY)
            canvas.drawText(spec.layer.text, spec.centerX, baseline, paint)
            canvas.restore()
        }
    }
}
