package com.toadgallery.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The sticker compositing pass (r35, Phase 7 v2) --- the ONLY place a
 * sticker is rendered, shared by the live preview and the export (the
 * contract's "same code path, no second renderer"): both callers hand
 * the SAME function their target bitmap, the recipe's layers and their
 * output-canvas rectangle, exactly as `TextCompositor` takes them.
 *
 * Pipeline placement (`stickers.md` decision 3): a compositing pass
 * AFTER the whole pixel pipeline and BELOW the text pass --- stickers
 * are scene objects, captions float above them. Within the family,
 * layers draw in list order, top LAST.
 *
 * Tolerance tiers, mirroring the family contract: an unresolvable
 * `stickerId` (a version skew, a deleted import) renders NOTHING but
 * keeps its recipe state --- the user's layer survives round-trips.
 * Never a crash, never a dropped session.
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
            // The tolerance contract: an id this session cannot resolve
            // (a deleted import, a version skew) renders nothing --- the
            // recipe keeps the layer, the canvas skips it.
            val resolved = resolver(spec.layer.stickerId) ?: continue
            canvas.save()
            canvas.rotate(spec.layer.rotationDeg.toFloat(), spec.centerX, spec.centerY)
            when (resolved) {
                is Resolved.Vector -> {
                    val box = StickerShape.DESIGN_BOX
                    val half = box / 2f
                    // The design box maps onto the size square, centred
                    // on the anchor: pixel = center + (authored - 50) * s.
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
                    // An imported bitmap scales to FIT inside the size
                    // square preserving its aspect --- a non-square import
                    // lands as itself, never stretched.
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
