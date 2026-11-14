package com.toadgallery.editor

/**
 * Copy-paste edits between photos: lifts the LOOK of one recipe onto another. What copies:
 * the tonal/colour state (sliders, curve, HSL bands, look); geometry and scene layers stay
 * the target photo's own. Pure (ADR-0011), JVM-pinned.
 */
object EditTransfer {

    /**
     * Copies the grade off [source] onto [target]: geometry and scene layers stay verbatim;
     * every grade field lands from the source, as one undoable commit.
     */
    fun pasteGrade(source: EditRecipe, target: EditRecipe): EditRecipe = target.copy(
        // ---- The tone sliders ----
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

    /** True when [source] carries a non-neutral grade (a neutral copy would be a no-op). */
    fun hasGrade(source: EditRecipe): Boolean =
        !(source.exposure == 0 && source.brightness == 0 && source.contrast == 0 &&
            source.highlights == 0 && source.shadows == 0 && source.whites == 0 &&
            source.blacks == 0 && source.saturation == 0 && source.vibrance == 0 &&
            source.temperature == 0 && source.tint == 0 && source.vignette == 0 &&
            source.sharpness == 0 && source.hslBands.isIdentity &&
            (source.toneCurves == null || source.toneCurves.isIdentity) &&
            (source.lut == null || source.lut.isIdentity))
}
