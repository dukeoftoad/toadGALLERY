package io.github.dukeoftoad.gallery.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF

/**
 * The frame compositing pass (r38, Phase 7 v2 --- the campaign's closing
 * round) --- the ONLY place a frame is rendered, shared by the live
 * preview and the export: both callers hand the SAME function their
 * target bitmap and their output-canvas rectangle, exactly as the family
 * compositors take them.
 *
 * Pipeline placement (`frames.md` decision 3): the LAST compositing
 * pass, AFTER the text pass --- the mounting covers the edges of
 * everything (a caption spilling into the border goes under it, the
 * physical metaphor).
 *
 * Tolerance: an unresolvable frameId renders NOTHING but keeps its
 * recipe state (the `LutSelection` contract) --- never a crash. An
 * imported frame stretches to the FULL canvas (the import assumes a
 * frame authored at the canvas aspect).
 */
object FrameCompositor {

    /** What a frame id resolves to: an authored parametric geometry, or a user-imported bitmap. */
    sealed interface Resolved {
        class Parametric(val geometry: BundledFrames.FrameGeometry, val colorArgb: Long) : Resolved
        class Bitmap(val bitmap: android.graphics.Bitmap) : Resolved
    }

    fun draw(
        target: Bitmap,
        selection: FrameSelection,
        resolver: (String) -> Resolved?,
        originX: Float,
        originY: Float,
        canvasW: Float,
        canvasH: Float,
    ) {
        if (canvasW <= 0f || canvasH <= 0f) return
        // Resolve parametric-first, imported-second (the sticker round's split).
        val w = canvasW.toInt()
        val h = canvasH.toInt()
        val resolved: Resolved? = BundledFrames.geometry(selection.frameId)?.let { fn ->
            val t = FrameLayout.thicknessPx(selection.widthPct, minOf(w, h))
            Resolved.Parametric(fn(t, w, h), selection.colorArgb)
        } ?: resolver(selection.frameId)
        if (resolved == null) return // the tolerance contract: no frame, never a crash
        val canvas = Canvas(target)
        val ox = originX
        val oy = originY
        when (resolved) {
            is Resolved.Parametric -> {
                val fill = Paint(Paint.ANTI_ALIAS_FLAG)
                fill.color = resolved.colorArgb.toInt()
                for (rect in resolved.geometry.rects) {
                    canvas.drawRect(
                        RectF(
                            ox + rect.x0.toFloat(),
                            oy + rect.y0.toFloat(),
                            ox + rect.x1.toFloat(),
                            oy + rect.y1.toFloat(),
                        ),
                        fill,
                    )
                }
                if (resolved.geometry.holes.isNotEmpty()) {
                    // The holes punch through to the photo: CLEAR the
                    // hole rects on the composited output.
                    val punch = Paint(Paint.ANTI_ALIAS_FLAG)
                    punch.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    for (hole in resolved.geometry.holes) {
                        canvas.drawRect(
                            RectF(
                                ox + hole.x0.toFloat(),
                                oy + hole.y0.toFloat(),
                                ox + hole.x1.toFloat(),
                                oy + hole.y1.toFloat(),
                            ),
                            punch,
                        )
                    }
                }
            }
            is Resolved.Bitmap -> {
                val bm = resolved.bitmap
                if (bm.width < 1 || bm.height < 1) return
                // An imported frame stretches to the FULL output canvas.
                val stretch = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(bm, null, RectF(ox, oy, ox + canvasW, oy + canvasH), stretch)
            }
        }
    }
}
