package com.toadgallery.editor

// The chassis feature flags: deliberately compile-time vals, each one a reviewed
// revert seam. A seam covers one SURFACE, never a contract (the engine is never gated).

/**
 * The Lilypad chassis switches, each a reviewed revert seam: flip in a one-line
 * reviewed change; nothing else may read it.
 *
 *  - `decksEnabled`: hosts the ten tool panels inside [LilypadDeck] (the three-state
 *    bottom sheet: 64dp peek, 45% half, 78% full); false restores the fixed window.
 *    Covers the PANEL CONTAINER only.
 *  - `curveDeckEnabled`: the Adjust board's Curve entry chip + the Deck's Full-state
 *    curve editor; false keeps the board without them. Covers the SURFACE only ---
 *    the recipe's curve field, its codec and the pipeline seam are engine contracts.
 *  - `historyStripEnabled`: the Main header's History entry + the bottom scrubber
 *    strip; false removes both. Covers the STRIP only (undo/redo ring untouched).
 *  - `timelineZoomEnabled`: the video Main screen's zoomable timeline strip (pinch
 *    zoom with detents, pan while zoomed); false restores the fixed-view TrimStrip.
 *    Covers the STRIP SURFACE only (trim drafts, thumbnails, seek callbacks untouched).
 *  - `cornerDragEnabled`: the Geometry screen's perspective corner overlay (displaced
 *    quad outline + four draggable handles); false leaves the keystone rows. Covers
 *    the OVERLAY SURFACE only (quad, engine stage, export scaling are engine contracts).
 */
internal object LilypadFlags {
    val decksEnabled: Boolean = true

    /** The curve deck's revert seam (the Adjust entry + the Full-state host). */
    val curveDeckEnabled: Boolean = true

    /** The history strip's revert seam (the header entry + the strip). */
    val historyStripEnabled: Boolean = true

    /** The zoomable timeline strip's revert seam (the video Main screen). */
    val timelineZoomEnabled: Boolean = true

    /** The perspective corner overlay's revert seam (the Geometry screen). */
    val cornerDragEnabled: Boolean = true
}
