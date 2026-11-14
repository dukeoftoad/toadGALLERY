package com.toadgallery.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The sticker compositing pass — the ONLY place a sticker renders, shared by preview and
 * export. Runs after the pixel pipeline, below the text pass; layers draw in list order,
 * top LAST. An unresolvable stickerId renders nothing but keeps its recipe state.
 */
object StickerCompositor {

    /** What a sticker id resolves to: an authored vector, or a user-imported bitmap. */
    sealed interface Resolved {
        class Vector(val shape: StickerShape) : Resolved
        class Bitmap(val bitmap: android.graphics.Bitmap) : Resolved
    }

    fun draw(
        target: Bitmap,
        layers: List<StickerLayer>,
        resolver: (String) -> Resolved?,
        originX: Float,
        originY: Float,
        canvasW: Float,
        canvasH: Float,
    ) {
        if (layers.isEmpty()) return
        val specs = StickerLayout.place(layers, originX, originY, canvasW, canvasH)
        val canvas = Canvas(target)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val path = Path()
        for (spec in specs) {
            // Tolerance: an unresolvable id renders nothing; the recipe keeps the layer.
            val resolved = resolver(spec.layer.stickerId) ?: continue
            canvas.save()
            canvas.rotate(spec.layer.rotationDeg.toFloat(), spec.centerX, spec.centerY)
            when (resolved) {
                is Resolved.Vector -> {
                    val box = StickerShape.DESIGN_BOX
                    val half = box / 2f
                    // Design box maps onto the size square, centred: pixel = center + (authored - 50) * s.
                    val s = spec.sizePx / box
                    for (part in resolved.shape.parts) {
                        path.reset()
                        for (op in part.ops) {
                            when (op) {
                                is StickerShape.Op.MoveTo ->
                                    path.moveTo(spec.centerX + (op.x - half) * s, spec.centerY + (op.y - half) * s)
                                is StickerShape.Op.LineTo ->
                                    path.lineTo(spec.centerX + (op.x - half) * s, spec.centerY + (op.y - half) * s)
                                is StickerShape.Op.CubicTo -> path.cubicTo(
                                    spec.centerX + (op.c1x - half) * s, spec.centerY + (op.c1y - half) * s,
                                    spec.centerX + (op.c2x - half) * s, spec.centerY + (op.c2y - half) * s,
                                    spec.centerX + (op.x - half) * s, spec.centerY + (op.y - half) * s,
                                )
                                is StickerShape.Op.Close -> path.close()
                            }
                        }
                        fill.color = part.colorArgb.toInt()
                        fill.alpha = spec.alpha
                        canvas.drawPath(path, fill)
                    }
                }
                is Resolved.Bitmap -> {
                    val bm = resolved.bitmap
                    if (bm.width < 1 || bm.height < 1) continue
                    // An imported bitmap scales to fit the size square, aspect preserved (never stretched).
                    val scale = spec.sizePx / maxOf(bm.width, bm.height)
                    val dw = bm.width * scale
                    val dh = bm.height * scale
                    bitmapPaint.alpha = spec.alpha
                    canvas.drawBitmap(
                        bm,
                        null,
                        RectF(
                            spec.centerX - dw / 2f,
                            spec.centerY - dh / 2f,
                            spec.centerX + dw / 2f,
                            spec.centerY + dh / 2f,
                        ),
                        bitmapPaint,
                    )
                }
            }
            canvas.restore()
        }
    }
}
