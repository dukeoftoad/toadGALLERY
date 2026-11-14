package com.toadgallery.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * The stroke compositing pass — the ONLY place a freehand stroke renders, shared by preview
 * and export. Runs after the pixel pipeline, between the sticker pass and the text pass;
 * layers draw in list order, top LAST.
 */
object DrawCompositor {

    fun draw(
        target: Bitmap,
        layers: List<DrawLayer>,
        originX: Float,
        originY: Float,
        canvasW: Float,
        canvasH: Float,
    ) {
        if (layers.isEmpty()) return
        val specs = DrawLayout.place(layers, originX, originY, canvasW, canvasH)
        val canvas = Canvas(target)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        for (spec in specs) {
            val pts = spec.pointsPx
            if (pts.isEmpty()) continue
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = spec.widthPx
            paint.color = spec.layer.colorArgb.toInt()
            paint.alpha = spec.alpha
            if (pts.size == 1) {
                // A tap is a dot: filled circle at the mapped point (a zero-length path draws nothing).
                paint.style = Paint.Style.FILL
                canvas.drawCircle(pts[0].first, pts[0].second, spec.widthPx / 2f, paint)
                continue
            }
            path.reset()
            path.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) {
                path.lineTo(pts[i].first, pts[i].second)
            }
            canvas.drawPath(path, paint)
        }
    }
}
