package com.toadgallery.editor

/**
 * The copy-paste edits transfer (r74, the C6 history-scrubber family's
 * closing move the plan doc names: "copy-paste edits between photos"):
 * a pure projection that lifts the LOOK of one session's recipe onto
 * another photo's session --- the whole point of the feature is that a
 * grade travels, and the honest rule is WHAT travels.
 *
 * WHAT COPIES (the plan doc's C6 contract, read literally): the tonal
 * and colour STATE --- exposure through sharpness, the curve, the HSL
 * bands, the look. WHAT DOES NOT: the GEOMETRY family (crop, rotate,
 * flip, straighten, perspective --- those are THIS photo's facts, not
 * the grade; pasting them onto a different framing is a misfeature
 * every serious editor refuses) and the SCENE layers (text, stickers,
 * strokes, regions, frames, the AI containers --- those are THIS
 * photo's content, and the plan doc's own tolerance contract keeps
 * them where they were drawn).
 *
 * WHY PURE (ADR-0011): the transfer is one data-class copy with an
 * explicit field list, JVM-pinned by [EditTransferTest] --- the surface
 * (the clipboard slot + the two menu actions) is a thin shell over
 * exactly this function, so the rule cannot drift between sessions.
 */
object EditTransfer {

    /**
     * Copies the grade off [source] onto [target]: target's geometry and
     * scene layers stay VERBATIM (they are that photo's own facts), and
     * every grade field --- the twelve sliders, the curves, the HSL
     * bands, the look --- lands from the source. The result is a normal
     * committed recipe through the ordinary undo path: ONE commit, one
     * history step, the whole grade at once (the copy IS one edit).
     */
    fun pasteGrade(source: EditRecipe, target: EditRecipe): EditRecipe = target.copy(
        // ---- The tone family (twelve sliders after r74) ----
        exposure = source.exposure,
        brightness = source.brightness,
        contrast = source.contrast,
        highlights = source.highlights,
        shadows = source.shadows,
        whites = source.whites,
        blacks = source.blacks,
        saturation = source.saturation,
        vibrance = source.vibrance,
        temperature = source.temperature,
        tint = source.tint,
        vignette = source.vignette,
        sharpness = source.sharpness,
        // ---- The curve + HSL + look ----
        toneCurves = source.toneCurves,
        hslBands = source.hslBands,
        lut = source.lut,
    )

    /**
     * True when [source] carries a grade worth pasting (every grade
     * field neutral means the copy would be a no-op --- the surface
     * refuses to advertise a Paste that does nothing, the honest-note
     * convention).
     */
    fun hasGrade(source: EditRecipe): Boolean =
        !(source.exposure == 0 && source.brightness == 0 && source.contrast == 0 &&
            source.highlights == 0 && source.shadows == 0 && source.whites == 0 &&
            source.blacks == 0 && source.saturation == 0 && source.vibrance == 0 &&
            source.temperature == 0 && source.tint == 0 && source.vignette == 0 &&
            source.sharpness == 0 && source.hslBands.isIdentity &&
            (source.toneCurves == null || source.toneCurves.isIdentity) &&
            (source.lut == null || source.lut.isIdentity))
}
