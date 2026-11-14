package com.toadgallery.editor

// The Deck's arithmetic as pure data (LilypadDeckMathTest pins it): three sheet
// states — Collapsed (64dp peek), Half (45% of available height), Full (78%) —
// with caller-owned state. Heights never exceed the available space, and a
// degenerate (zero or negative) available height degrades to zero.

/**
 * The Deck's three chassis states. The declaration order IS the snap
 * ladder: Collapsed < Half < Full (a drag moves at most ONE step along
 * it, per [LilypadDeckMath.snapAfterDrag]).
 */
internal enum class LilypadDeckState { Collapsed, Half, Full }

/** The Deck's pure arithmetic: state heights and the snap rules. */
internal object LilypadDeckMath {

    /** The collapsed peek height, dp: the handle band plus a content sliver. */
    const val COLLAPSED_HEIGHT_DP = 64

    /** Half state: this fraction of the available height. */
    const val HALF_FRACTION = 0.45f

    /** Full state: this fraction of the available height. */
    const val FULL_FRACTION = 0.78f

    /** Minimum vertical drag (dp) before a snap decision fires. */
    const val SNAP_DRAG_DP = 48f

    /**
     * The state's height in px given [maxHeightPx] and the [density] scale.
     * Collapsed is a FIXED dp height (the peek is a shape, not a share), clamped
     * if the chassis is smaller than the peek; Half and Full are plain fractions
     * (< 1, never overflow by construction). A degenerate chassis returns exactly
     * 0. On any real chassis the states order strictly:
     * Collapsed < Half < Full < maxHeight.
     */
    fun targetHeightPx(state: LilypadDeckState, maxHeightPx: Float, density: Float): Float {
        if (maxHeightPx <= 0f) return 0f
        return when (state) {
            LilypadDeckState.Collapsed -> (COLLAPSED_HEIGHT_DP * density).coerceAtMost(maxHeightPx)
            LilypadDeckState.Half -> maxHeightPx * HALF_FRACTION
            LilypadDeckState.Full -> maxHeightPx * FULL_FRACTION
        }
    }

    /**
     * The state after a handle drag: [dragDeltaPx] < 0 means dragged UP
     * (expand), > 0 means dragged DOWN (collapse). A drag past
     * [SNAP_DRAG_DP] scaled to px moves EXACTLY ONE step along the snap
     * ladder (Collapsed < Half < Full); a drag below the threshold changes
     * nothing; the ladder's ends are walls (no wrap-around).
     */
    fun snapAfterDrag(
        current: LilypadDeckState,
        dragDeltaPx: Float,
        density: Float,
    ): LilypadDeckState {
        val threshold = SNAP_DRAG_DP * density
        if (threshold <= 0f) return current
        return when {
            dragDeltaPx <= -threshold -> when (current) {
                LilypadDeckState.Collapsed -> LilypadDeckState.Half
                LilypadDeckState.Half -> LilypadDeckState.Full
                LilypadDeckState.Full -> LilypadDeckState.Full
            }
            dragDeltaPx >= threshold -> when (current) {
                LilypadDeckState.Collapsed -> LilypadDeckState.Collapsed
                LilypadDeckState.Half -> LilypadDeckState.Collapsed
                LilypadDeckState.Full -> LilypadDeckState.Half
            }
            else -> current
        }
    }

    /**
     * The tap toggle: the handle's tap cycles the WORKING pair ---
     * Collapsed -> Half -> Collapsed --- and any expanded state collapses
     * (Full is reached by dragging or programmatically, never by the
     * working toggle).
     */
    fun tapToggle(current: LilypadDeckState): LilypadDeckState = when (current) {
        LilypadDeckState.Collapsed -> LilypadDeckState.Half
        LilypadDeckState.Half -> LilypadDeckState.Collapsed
        LilypadDeckState.Full -> LilypadDeckState.Collapsed
    }
}
