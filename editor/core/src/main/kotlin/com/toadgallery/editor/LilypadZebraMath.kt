package com.toadgallery.editor

// The clipping zebra's arithmetic as PURE DATA (pinned on the JVM by LilypadZebraMathTest;
// the overlay bitmap builder in ZebraOverlay.kt only READS these rules). The zebra marks
// tones where information is GONE, not merely bright: red stripes where ANY channel sits at
// or above the highlight pin (that channel's detail is clipped at the top --- a saturated
// red petal at R=252 has lost its red micro-contrast even though the pixel is not white);
// blue stripes where any channel sits at or below the shadow pin. The per-channel rule never
// calls a pixel clipped that still carries detail in every channel.
//
// The pins are FIXED constants, not settings (250/255 and 5/255 are the ~2% headroom bands
// every serious editor defaults to). The stripe geometry is DETERMINISTIC: fixed period,
// duty and phase, opposite diagonals for the two zones so a boundary pixel reads as one or
// the other, never a moire of both.

/** The clipping zebra's pure arithmetic: the zone classification and the stripe tiling. */
internal object LilypadZebraMath {

    /** A channel at or above this is highlight-clipped (250 of 255). */
    const val HIGHLIGHT_THRESHOLD = 250

    /** A channel at or below this is shadow-clipped (5 of 255). */
    const val SHADOW_THRESHOLD = 5

    /**
     * The overlay grid's downsample factor: one zebra cell per 4x4 block of preview pixels,
     * point-sampled at the block's corner. The preview is bounded (<= 2048), so the overlay
     * stays a ~200k-cell pass --- cheap enough to recompute on every drag tick.
     */
    const val DOWNSAMPLE = 4

    /** The stripe cycle, in overlay-grid cells. */
    const val STRIPE_PERIOD = 6

    /** The on-cells per cycle: half on, half off. */
    const val STRIPE_DUTY = 3

    /** The highlight stripes' color: the chart's RED channel tint (semantic content, not chrome). */
    const val HIGHLIGHT_STRIPE_ARGB: Long = 0xFFF0524AL

    /** The shadow stripes' color: the chart's BLUE channel tint (semantic content, not chrome). */
    const val SHADOW_STRIPE_ARGB: Long = 0xFF4C8BF5L

    /** The stripes' opacity at bake time: present, never opaque enough to hide the photo. */
    const val STRIPE_ALPHA_255 = 153

    /** The three zones a pixel falls into. */
    enum class Zone { NONE, HIGHLIGHT, SHADOW }

    /**
     * HIGHLIGHT when any channel sits at or above [HIGHLIGHT_THRESHOLD] (highlight wins ---
     * a pixel clipped at the top of one channel is clipped), SHADOW when any channel sits
     * at or below [SHADOW_THRESHOLD], NONE between the pins.
     */
    fun classify(r: Int, g: Int, b: Int): Zone = when {
        r >= HIGHLIGHT_THRESHOLD || g >= HIGHLIGHT_THRESHOLD || b >= HIGHLIGHT_THRESHOLD -> Zone.HIGHLIGHT
        r <= SHADOW_THRESHOLD || g <= SHADOW_THRESHOLD || b <= SHADOW_THRESHOLD -> Zone.SHADOW
        else -> Zone.NONE
    }

    /**
     * The highlight stripe at overlay cell (x, y): on when (x + y) wraps
     * into the duty band --- a fixed diagonal, phase zero.
     */
    fun highlightStripeOn(x: Int, y: Int): Boolean =
        (((x + y) % STRIPE_PERIOD) + STRIPE_PERIOD) % STRIPE_PERIOD < STRIPE_DUTY

    /**
     * The shadow stripe at overlay cell (x, y): the OPPOSITE diagonal
     * (x - y), so the two zones read differently where they meet at a
     * hard edge.
     */
    fun shadowStripeOn(x: Int, y: Int): Boolean =
        (((x - y) % STRIPE_PERIOD) + STRIPE_PERIOD) % STRIPE_PERIOD < STRIPE_DUTY
}
