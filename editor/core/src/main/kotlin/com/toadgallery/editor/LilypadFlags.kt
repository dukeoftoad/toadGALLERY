package com.toadgallery.editor

// ---------------------------------------------------------------------------
// The Lilypad rounds' feature flags (r67 foundation + r68 pro surfaces):
// the compile-time switches that decide which chassis surfaces are live.
//
// Each flag is deliberately a compile-time val, not a runtime setting:
// it is the round's revert seam, reviewed in the PR that flips it,
// exactly like the chassis seams before it (r54's tab IA, r63c's rail).
// When a later phase confirms a surface, its flag and its fallback both
// retire.
//
// What the flags do NOT gate: the engine (the recipe, the codec, the
// pipeline), the palette and the panels' own behavior contracts --- a
// seam covers one SURFACE, never a contract.
// ---------------------------------------------------------------------------

/**
 * The Lilypad rounds' chassis switches, each one a reviewed revert seam:
 * flip it in a one-line reviewed change; nothing else may read it.
 *
 *  - `decksEnabled` = true hosts the ten tool panels inside [LilypadDeck]
 *    (the three-state bottom sheet: a 64dp collapsed peek, the 45% half
 *    working surface, the 78% full immersive state); false restores the
 *    r54 fixed window. The flag covers the PANEL CONTAINER only.
 *  - `curveDeckEnabled` (r68) = true gives the Adjust board its Curve
 *    entry chip and the Deck's Full state the curve editor; false keeps
 *    the board exactly its r67 shape (the Full state stays unreferenced,
 *    as it was in Phase 1). The flag covers the SURFACE only --- the
 *    recipe's curve field, its codec and the pipeline seam are engine
 *    contracts, never gated.
 *  - `historyStripEnabled` (r68) = true gives the Main header its
 *    History entry and the chassis the bottom-anchored scrubber strip;
 *    false removes both. The flag covers the STRIP only --- the
 *    undo/redo ring, its icons and its semantics are untouched.
 *  - `timelineZoomEnabled` (r69) = true gives the video editor's Main
 *    screen the zoomable timeline strip (pinch-zoom with detents, pan
 *    while zoomed, the TrimStrip's trim/scrub/tap contract otherwise);
 *    false restores the r63d fixed-view TrimStrip. The flag covers the
 *    STRIP SURFACE only --- the trim drafts, the thumbnails contract and
 *    the seek callbacks are the video chassis's own contracts, never
 *    gated.
 *  - `cornerDragEnabled` (r72) = true gives the Geometry tool screen
 *    the perspective corner overlay (the displaced quad's outline and
 *    four draggable handles over the preview --- Phase 4's corner
 *    drag); false removes the overlay, leaving the r68 keystone rows
 *    the model's only surface. The flag covers the OVERLAY SURFACE
 *    only --- the quad, its engine stage, the keystone rows and the
 *    export scaling are engine contracts, never gated.
 */
internal object LilypadFlags {
    val decksEnabled: Boolean = true

    /** r68: the curve deck's revert seam (the Adjust entry + the Full-state host). */
    val curveDeckEnabled: Boolean = true

    /** r68: the history strip's revert seam (the header entry + the strip). */
    val historyStripEnabled: Boolean = true

    /** r69: the zoomable timeline strip's revert seam (the video Main screen). */
    val timelineZoomEnabled: Boolean = true

    /** r72: the perspective corner overlay's revert seam (the Geometry screen). */
    val cornerDragEnabled: Boolean = true
}
