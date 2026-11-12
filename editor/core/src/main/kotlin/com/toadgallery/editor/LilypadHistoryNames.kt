package com.toadgallery.editor

import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// r68: the photo pro round (Phase 2 of the editor rebirth plan,
// docs/editor/lilypad-design.md) --- the history strip's NAMING math as
// PURE DATA (the ADR-0011 split: LilypadHistoryNamesTest pins every
// family on the JVM; HistoryStrip.kt only resolves the labels).
//
// WHAT A CHIP SAYS. The strip shows one chip per COMMITTED snapshot,
// labeled by what that commit DID --- the human change, not the field
// dump. "Exposure +35", not "exposure: 0 -> 35". The diff is
// SEMANTIC, structured data (a sealed [LilypadHistoryNames.Change]);
// the STRINGS resolve at the surface, composable-side, so the pure
// layer never touches a resource and the strip stays localizable.
//
// THE PRIORITY. One commit is usually ONE gesture (the per-commit
// granularity every family pins), so a diff names itself. The
// exceptions are the wide reverts --- a tool Reset, a jump --- which
// touch many fields at once; those read the FIRST family in a fixed,
// documented order (the adjustment sliders first: they are the
// everyday gesture; then the curve, the look, the colour grade, the
// geometry, the layer families, the AI containers). A diff that
// matches NOTHING (a schema-shape change no family describes) answers
// the honest generic [Change.Plain] of [Family.Edit] --- never a wrong
// specific label.
//
// THE CURVE COUNT. A curve chip counts the state's non-identity
// control points across the model's curves ("Curve 5 pts"): the shape
// the user sculpted, at a glance, without speaking a spline.
// ---------------------------------------------------------------------------

/**
 * The history strip's pure naming layer (see the file header): diff two
 * recipes into the SEMANTIC change the strip's chip announces.
 */
internal object LilypadHistoryNames {

    /**
     * One committed change, as structured data. The surface resolves
     * each case into (name, mono value, spoken sentence); the pure
     * layer carries no strings.
     */
    internal sealed interface Change

    /**
     * An adjustment slider landed at [value] (the NEW value --- the
     * direction is the story). [key] is the [ADJUST_SLIDERS] key, so
     * the surface resolves the label through the SAME spec the board
     * renders from (one source of truth for a slider's name).
     */
    internal data class Slider(val key: String, val value: Int) : Change

    /** Straighten landed at [degrees] (whole degrees, the panel's display unit). */
    internal data class Straighten(val degrees: Int) : Change

    /** A curve edit; [points] is the state's non-identity control-point count. */
    internal data class Curve(val points: Int) : Change

    /**
     * The look selection changed: [lutId] ("" when deselected) at
     * [intensity]. The surface resolves the id to the registry's
     * display name.
     */
    internal data class Look(val lutId: String, val intensity: Int) : Change

    /** A change whose label is a single word --- the families of [Family]. */
    internal data class Plain(val family: Family) : Change

    /**
     * The single-word families. The surface maps each to its string;
     * new families land here, never as ad-hoc strings at the call site.
     */
    internal enum class Family {
        Crop, Rotate, Flip, Perspective,
        Color, Text, Stickers, Draw, Regions, Frame,
        Sky, Bokeh, Retouch, Erase,
        Edit,
    }

    /**
     * The semantic change between [previous] and [next] (the chip a
     * jump TO [next] would land on), per the file header's priority:
     * the adjustment sliders ([ADJUST_SLIDERS]'s own order), then the
     * curve, the look, the HSL grade, the geometry family, the layer
     * families, the AI containers, and the honest generic fallback.
     */
    fun changeBetween(previous: EditRecipe, next: EditRecipe): Change {
        // 1. The adjustment sliders --- the everyday gesture.
        for (spec in ADJUST_SLIDERS) {
            val before = spec.get(previous)
            val after = spec.get(next)
            if (before != after) return Slider(spec.key, after)
        }
        // 2. The curve layer (the recipe's null-for-identity field).
        if (previous.toneCurves != next.toneCurves) {
            return Curve(curvePointCount(next.toneCurves))
        }
        // 3. The look (a deselection is a change too: the empty id).
        if (previous.lut != next.lut) {
            return Look(next.lut?.lutId ?: "", next.lut?.intensity ?: 0)
        }
        // 4. The HSL grade.
        if (previous.hslBands != next.hslBands) return Plain(Family.Color)
        // 5. The geometry family.
        if (previous.quarterTurns != next.quarterTurns) return Plain(Family.Rotate)
        if (previous.horizontalFlip != next.horizontalFlip ||
            previous.verticalFlip != next.verticalFlip
        ) {
            return Plain(Family.Flip)
        }
        if (previous.straightenDeg != next.straightenDeg) {
            return Straighten(next.straightenDeg.roundToInt())
        }
        if (previous.crop != next.crop) return Plain(Family.Crop)
        if (previous.perspective != next.perspective) return Plain(Family.Perspective)
        // 6. The layer families.
        if (previous.textLayers != next.textLayers) return Plain(Family.Text)
        if (previous.stickerLayers != next.stickerLayers) return Plain(Family.Stickers)
        if (previous.drawLayers != next.drawLayers) return Plain(Family.Draw)
        if (previous.mosaicRegions != next.mosaicRegions) return Plain(Family.Regions)
        if (previous.frame != next.frame) return Plain(Family.Frame)
        // 7. The AI containers.
        if (previous.aiErase != next.aiErase) return Plain(Family.Erase)
        if (previous.sky != next.sky) return Plain(Family.Sky)
        if (previous.bokeh != next.bokeh) return Plain(Family.Bokeh)
        if (previous.retouch != next.retouch) return Plain(Family.Retouch)
        // 8. The honest generic: a diff no family describes.
        return Plain(Family.Edit)
    }

    /**
     * The state's non-identity control points across the model's
     * curves: the master's when it left the diagonal, plus every
     * channel list that carries one (a channel list is non-null only
     * when it means something). The launch/identity state counts 0.
     */
    fun curvePointCount(curves: ToneCurves?): Int {
        if (curves == null) return 0
        var count = 0
        if (curves.master.isNotEmpty() && curves.master != LilypadCurveMath.IDENTITY) {
            count += curves.master.size
        }
        if (curves.red.isNotEmpty()) count += curves.red.size
        if (curves.green.isNotEmpty()) count += curves.green.size
        if (curves.blue.isNotEmpty()) count += curves.blue.size
        return count
    }
}
