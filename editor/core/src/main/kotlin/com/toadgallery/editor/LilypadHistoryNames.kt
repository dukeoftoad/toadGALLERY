package com.toadgallery.editor

import kotlin.math.roundToInt

// The history strip's pure naming layer: diffs two recipes into a semantic Change; the
// strings resolve at the surface (localizable). One gesture names itself; wide reverts
// read the first matching family in fixed order; unmatched diffs fall back to Family.Edit.

/** Diffs two recipes into the semantic change the strip's chip announces. */
internal object LilypadHistoryNames {

    /** One committed change as structured data; the surface resolves each case to a label. */
    internal sealed interface Change

    /**
     * An adjustment slider landed at [value] (the NEW value — the direction is the story).
     * [key] is the [ADJUST_SLIDERS] key, resolved by the same spec the board renders from.
     */
    internal data class Slider(val key: String, val value: Int) : Change

    /** Straighten landed at [degrees] (whole degrees, the panel's display unit). */
    internal data class Straighten(val degrees: Int) : Change

    /** A curve edit; [points] is the state's non-identity control-point count. */
    internal data class Curve(val points: Int) : Change

    /** The look selection changed: [lutId] ("" when deselected) at [intensity]. */
    internal data class Look(val lutId: String, val intensity: Int) : Change

    /** A change whose label is a single word --- the families of [Family]. */
    internal data class Plain(val family: Family) : Change

    /** The single-word families; the surface maps each to its string. */
    internal enum class Family {
        Crop, Rotate, Flip, Perspective,
        Color, Text, Stickers, Draw, Regions, Frame,
        Sky, Bokeh, Retouch, Erase,
        Edit,
    }

    /**
     * The semantic change between [previous] and [next] (the chip a jump TO [next] lands
     * on): first matching family in fixed order — sliders, curve, look, HSL, geometry,
     * layers, AI — else the generic fallback.
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
     * Non-identity control points across the model's curves: the master's when off the
     * diagonal, plus every non-null channel list. The identity state counts 0.
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
