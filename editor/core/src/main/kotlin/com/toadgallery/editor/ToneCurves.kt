package com.toadgallery.editor

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the recipe's CURVE layer, the model
// the Full deck edits.
//
// THE SHAPE. A curve is a small list of control points in NORMALIZED image
// space: x is the input tone 0..1 (black left, white right), y the output
// tone 0..1 (bottom black, top white). The MASTER curve re-maps every
// channel identically --- the classic tone curve every serious editor
// leads with. The per-channel curves (red/green/blue) ride the model as
// FORWARD STATE: this round's engine composes the MASTER curve only (the
// per-channel apply path is the coming-next step; see LilypadCurveMath's
// header), but the wire shape lands complete so a later round turns the
// channels on without a sidecar migration.
//
// THE IDENTITY RULE, the recipe's null-for-identity pattern verbatim
// (the `lut`/`textLayers` family): `EditRecipe.toneCurves` is NULL for a
// clean session --- no key on the wire, nothing in the pipeline's gate,
// `isAdjustmentIdentity` true. A non-null [ToneCurves] whose master is
// the two identity points and whose channels are empty IS the identity
// too ([isIdentity]); the panel normalizes such a state back to null at
// commit so a played-with-then-straightened curve leaves no residue.
//
// VALIDATION IS THE MATH'S, NOT THE MODEL'S. The lists carry the user's
// data verbatim (the recipe-is-the-user's-data rule); the tolerant codec
// coerces and caps on read, and [LilypadCurveMath] sorts, de-duplicates
// and guards before any interpolation runs. The editor UI constructs
// valid curves by construction (sorted inserts, neighbor clamps).
// ---------------------------------------------------------------------------

/** One tone-curve control point: normalized input [x] to output [y], both 0..1. */
data class CurvePoint(val x: Double, val y: Double)

/**
 * The recipe's curve state (r68): the master curve plus the per-channel
 * curves (forward state; see the file header). Point lists are the user's
 * data, kept in their stored order --- [LilypadCurveMath] owns sorting and
 * the interpolation guards.
 */
data class ToneCurves(
    /** The master curve; the pipeline composes it after the shadows stage. */
    val master: List<CurvePoint>,
    /** The red channel's curve (forward state --- not yet composed by the engine). */
    val red: List<CurvePoint> = emptyList(),
    /** The green channel's curve (forward state --- not yet composed by the engine). */
    val green: List<CurvePoint> = emptyList(),
    /** The blue channel's curve (forward state --- not yet composed by the engine). */
    val blue: List<CurvePoint> = emptyList(),
) {
    /**
     * The exact identity: the master IS the two identity points and no
     * channel carries a curve --- nothing for any stage to do. A
     * hand-constructed degenerate master (under two points) is NOT the
     * identity by this rule, but the codec never reads one and the math
     * bakes it to the identity LUT anyway --- honest either way.
     */
    val isIdentity: Boolean
        get() = master == IDENTITY_MASTER && red.isEmpty() && green.isEmpty() && blue.isEmpty()

    companion object {
        /**
         * The canonical two-point identity curve: the straight diagonal
         * from black to white. [LilypadCurveMath.bakeLut] returns the EXACT
         * 0..255 ramp for exactly this list.
         */
        val IDENTITY_MASTER: List<CurvePoint> = listOf(
            CurvePoint(0.0, 0.0),
            CurvePoint(1.0, 1.0),
        )
    }
}
