package com.toadgallery.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the clipping ZEBRA: the canvas-level
// overlay that paints the photo's clipped tones in deterministic diagonal
// stripes while the Zebra chip is on.
//
// THE RENDER PATH is the histogram's, verbatim: a pure READER of state the
// preview render already produces (the SAME pipelined pixels the canvas
// shows, before the scene layers composite --- a sticker is not a tonal
// state). [buildZebraOverlay] runs off the UI thread inside the preview's
// render pass, only while the overlay is enabled --- the pass costs one
// classification per 4x4 block of the bounded working grid (~200k cells),
// cheap enough to follow a drag tick for tick.
//
// THE OVERLAY ITSELF paints nothing the preview did not: each clipped
// block becomes a stripe cell from [LilypadZebraMath]'s pinned tiling (a
// highlight block carries the chart's red tint, a shadow block its blue
// --- semantic content, the same exemption the histogram's channel colors
// carry). A fully unclipped photo returns NULL (no overlay at all, never
// an all-transparent bitmap pretending to be one). The composable draws
// with no pointer input --- it cannot intercept a touch, so the canvas's
// compare hold, zoom and crop gestures ride untouched underneath it.
// ---------------------------------------------------------------------------

/**
 * The zebra raster: one overlay cell per [LilypadZebraMath.DOWNSAMPLE]^2
 * block of the [width] x [height] grid, point-sampled at the block's
 * corner, striped per the pinned tiling. Returns null when nothing clips
 * (the honest empty state --- no overlay, not a blank one).
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
 * The canvas overlay: [zebra] drawn bounds-fit over the preview (the same
 * ContentScale.Fit the preview Image uses, so the raster --- the same
 * aspect by construction --- lands exactly on the photo). A null [zebra]
 * (the overlay off, or an unclipped photo) draws nothing. The overlay
 * carries no semantics of its own and installs no pointer input: the Zebra
 * chip owns the toggle's a11y, and the canvas's gestures stay live.
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
