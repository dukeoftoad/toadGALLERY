package com.toadgallery.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

// The clipping zebra: a canvas overlay painting clipped tones in diagonal stripes; a pure
// reader of preview pixels (pre-scene-composite), null when nothing clips, no pointer input.

/**
 * The zebra raster: one cell per [LilypadZebraMath.DOWNSAMPLE]^2 block, point-sampled at
 * the block corner, striped per the pinned tiling. Null when nothing clips.
 */
internal fun buildZebraOverlay(pixels: IntArray, width: Int, height: Int): Bitmap? {
    val step = LilypadZebraMath.DOWNSAMPLE
    val zw = (width / step).coerceAtLeast(1)
    val zh = (height / step).coerceAtLeast(1)
    val alpha = LilypadZebraMath.STRIPE_ALPHA_255 shl 24
    val highlightColor = alpha or LilypadZebraMath.HIGHLIGHT_STRIPE_ARGB.toInt()
    val shadowColor = alpha or LilypadZebraMath.SHADOW_STRIPE_ARGB.toInt()
    val out = IntArray(zw * zh)
    var anyStriped = false
    for (zy in 0 until zh) {
        val sy = (zy * step).coerceAtMost(height - 1)
        for (zx in 0 until zw) {
            val sx = (zx * step).coerceAtMost(width - 1)
            val p = pixels[sy * width + sx]
            val color = when (
                LilypadZebraMath.classify(
                    r = (p shr 16) and 0xFF,
                    g = (p shr 8) and 0xFF,
                    b = p and 0xFF,
                )
            ) {
                LilypadZebraMath.Zone.HIGHLIGHT ->
                    if (LilypadZebraMath.highlightStripeOn(zx, zy)) highlightColor else 0
                LilypadZebraMath.Zone.SHADOW ->
                    if (LilypadZebraMath.shadowStripeOn(zx, zy)) shadowColor else 0
                LilypadZebraMath.Zone.NONE -> 0
            }
            if (color != 0) anyStriped = true
            out[zy * zw + zx] = color
        }
    }
    if (!anyStriped) return null
    return Bitmap.createBitmap(zw, zh, Bitmap.Config.ARGB_8888)
        .also { it.setPixels(out, 0, zw, 0, 0, zw, zh) }
}

/**
 * The canvas overlay: [zebra] drawn ContentScale.Fit over the preview (same aspect by
 * construction, lands exactly on the photo). Null draws nothing; no semantics, no pointer
 * input — the Zebra chip owns the toggle's a11y and canvas gestures stay live.
 */
@Composable
internal fun ZebraOverlay(zebra: ImageBitmap?, modifier: Modifier = Modifier) {
    if (zebra == null) return
    Image(
        bitmap = zebra,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.fillMaxSize(),
    )
}

/** The builder's ImageBitmap form (the chassis state holder's type). */
internal fun buildZebraOverlayImage(pixels: IntArray, width: Int, height: Int): ImageBitmap? =
    buildZebraOverlay(pixels, width, height)?.asImageBitmap()
