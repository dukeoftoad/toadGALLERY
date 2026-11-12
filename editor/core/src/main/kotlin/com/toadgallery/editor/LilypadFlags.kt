package com.toadgallery.editor

// ---------------------------------------------------------------------------
// The r67 Lilypad feature flag (Phase 1 of the editor rebirth plan,
// docs/editor/lilypad-design.md): ONE switch that decides whether the photo
// chassis hosts its tool panels in the new three-state LilypadDeck or in
// the r54 fixed window (the heightIn(max = 300dp) scroll column this round
// retires as the DEFAULT but keeps as the explicit fallback).
//
// The flag is deliberately a compile-time val, not a runtime setting: it is
// the phase's revert seam, reviewed in the PR that flips it, exactly like
// the chassis seams before it (r54's tab IA, r63c's rail). When Phase 2
// (the pro surfaces round) lands, the fallback column and this flag both
// retire: the Deck becomes the only chassis.
//
// What the flag does NOT gate: the TuneRail is retired unconditionally
// (the Adjust panel's ProSlider board is the new surface in both states),
// and the Lilypad palette is the theme in both states. The flag covers the
// PANEL CONTAINER only.
// ---------------------------------------------------------------------------

/**
 * The Lilypad round's chassis switches. `decksEnabled` = true hosts the
 * ten tool panels inside [LilypadDeck] (the three-state bottom sheet:
 * a 64dp collapsed peek, the 45% half working surface, the 78% full
 * immersive state); false restores the r54 fixed window. Flipping it is a
 * one-line reviewed change; nothing else may read it.
 */
internal object LilypadFlags {
    val decksEnabled: Boolean = true
}
