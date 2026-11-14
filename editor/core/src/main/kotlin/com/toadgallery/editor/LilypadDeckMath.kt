package com.toadgallery.editor

// ---------------------------------------------------------------------------
// The r67 Lilypad foundation round (Phase 1 of the editor rebirth plan,
// docs/editor/lilypad-design.md): the Deck's arithmetic as PURE DATA ---
// the three sheet states, their heights, and the snap rules, with zero
// compose/android imports (the ADR-0011 split: pure logic tested pure ---
// LilypadDeckMathTest pins every rule on the JVM; LilypadDeck.kt only
// READS these numbers).
//
// THE DECK CONTRACT. The r54 chassis hosted the tool panels in a fixed
// `heightIn(max = 300.dp)` scroll column --- a window that cramped every
// panel and hid the Adjust board behind its own scroll. The Deck replaces
// it with ONE sheet, THREE states, the state CALLER-OWNED:
//
//   Collapsed --- a 64dp peek: the drag handle plus a sliver of the
//                first row, the photo unobstructed;
//   Half       --- the working surface, 45% of the available height;
//   Full       --- the immersive surface, 78% (Phase 2's curve deck; no
//                call site references it in Phase 1).
//
// Heights never exceed the available space, and a degenerate (zero or
// negative) available height degrades to zero --- the sheet never lies
// with a negative or infinite number.
//
// (r67 recovery note: the r67 working tree was lost mid-round; this file
// was re-derived from the Deck contract above and the test matrix, not
// restored verbatim --- the lost-tree recovery documented in the round's
// delivery report.)
// ---------------------------------------------------------------------------

/**
 * The Deck's three chassis states. The declaration order IS the snap
 * ladder: Collapsed < Half < Full (a drag moves at most ONE step along
 * it, per [LilypadDeckMath.snapAfterDrag]).
 */
internal enum class LilypadDeckState { Collapsed, Half, Full }

/**
 * The Deck's pure arithmetic: state heights and the snap rules
 * (see the file header for the contract).
 */
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
     * The state's height in px given the available [maxHeightPx] and the
     * [density] scale. Collapsed is a FIXED dp height (never a fraction
     * --- the peek is a shape, not a share), clamped down if the chassis
     * is smaller than the peek itself; Half and Full are plain fractions
     * of the available height (0.45 and 0.78 are both < 1, so they never
     * overflow by construction). A degenerate chassis (maxHeightPx <= 0)
     * returns exactly 0 --- the sheet never lies with a negative number.
     * On any real chassis (available height far beyond the 64dp peek) the
     * states order strictly: Collapsed < Half < Full < maxHeight.
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
