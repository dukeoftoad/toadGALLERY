package com.toadgallery.editor

/**
 * The text-layer system (r34, Phase 7 v2; the design contract is
 * `docs/phase-7/text-tool.md`, decision set 1-5).
 *
 * Pure Kotlin, JVM-tested (the repo's ADR-0011 convention: the model and
 * geometry are pure; only the Typeface/Paint pass lives on the Android
 * side, in `TextRender.kt`, and shares THIS object's layout math for the
 * preview and the export --- one code path, no second renderer).
 *
 * The tolerance contract mirrors r33's `LutSelection`: a pre-r34 sidecar
 * (or a clean session) has no `textLayers` key and loads byte-clean; an
 * UNRESOLVABLE `fontId` round-trips verbatim (the recipe is the user's
 * data) and degrades to the default face at RENDER time --- never a load
 * failure, never a silent layer drop.
 */
data class TextLayer(
    val id: String,
    val text: String,
    val fontId: String,
    /** 0..300, percent of the OUTPUT canvas min-edge for the em size. */
    val sizePct: Int = DEFAULT_SIZE_PCT,
    /** Full 0xAARRGGBB (the alpha byte is authored data; the render multiplies it with [opacity]). */
    val colorArgb: Long = DEFAULT_COLOR,
    /** 0..100. */
    val opacity: Int = 100,
    /** The layer's OWN angle in degrees, -180..180 (never re-rotated by geometry). */
    val rotationDeg: Int = 0,
    /** 0..1 fraction of the output-canvas width. */
    val centerX: Double = 0.5,
    /** 0..1 fraction of the output-canvas height. */
    val centerY: Double = 0.5,
) {
    init {
        require(id.isNotBlank()) { "layer id must not be blank" }
        require(id.length <= MAX_ID_LENGTH) { "layer id longer than $MAX_ID_LENGTH chars" }
        require(text.length <= MAX_TEXT_LENGTH) { "text longer than $MAX_TEXT_LENGTH chars" }
        require(fontId.isNotBlank()) { "fontId must not be blank" }
        require(fontId.length <= MAX_ID_LENGTH) { "fontId longer than $MAX_ID_LENGTH chars" }
        require(sizePct in 0..300) { "sizePct must be in 0..300" }
        require(colorArgb in 0..0xFFFFFFFFL) { "colorArgb outside the unsigned ARGB range" }
        require(opacity in 0..100) { "opacity must be in 0..100" }
        require(rotationDeg in -180..180) { "rotationDeg must be in -180..180" }
        require(centerX in 0.0..1.0) { "centerX must be in 0..1" }
        require(centerY in 0.0..1.0) { "centerY must be in 0..1" }
    }

    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_TEXT_LENGTH = 2000
        const val DEFAULT_SIZE_PCT = 20
        const val DEFAULT_COLOR = 0xFFFFFFFFL

        /**
         * The next free session id over `existing` ("t1", "t2", ...): one
         * past the highest numeric suffix seen, so ids never collide within
         * a session (recipe round-trips preserve them verbatim).
         */
        fun newId(existing: Collection<String>): String {
            var max = 0
            for (id in existing) {
                val n = id.removePrefix("t").toIntOrNull()
                if (n != null && n > max) max = n
            }
            return "t${max + 1}"
        }
    }
}

/**
 * The recipe's additive text container: absent (null) = a pre-r34 sidecar
 * or a clean session; empty is normalised BACK to absent by the codec (a
 * layer list that becomes empty after deletions writes no key).
 */
data class TextLayers(val layers: List<TextLayer>) {
    init {
        // Ids must be unique: the UI's per-layer controls, the drag target
        // and undo/redo all re-target layers by id.
        require(layers.size == layers.map { it.id }.toSet().size) { "duplicate text layer id" }
    }

    val isIdentity: Boolean get() = layers.isEmpty()

    companion object {
        val EMPTY = TextLayers(emptyList())
    }
}

/**
 * The bundled-font registry (r34; the `BundledLuts` pattern). PURE: the
 * platform side turns entries into `Typeface`s (see `TextRender.kt`);
 * the licensing evidence for every entry is `docs/phase-7/bundled-fonts.md`
 * + the §5 ledger rows, and `BundledFontsTest` pins the committed bytes by
 * sha256 at CI time.
 */
object BundledFonts {

    /** One bundled face: the registry id, the asset file name, the display name (a proper noun --- not localized, like the LUT titles are). */
    data class Entry(val id: String, val fileName: String, val displayName: String) {
        init {
            require(id.isNotBlank()) { "id must not be blank" }
            require(fileName.endsWith(".ttf")) { "fileName must be a .ttf" }
            require(displayName.isNotBlank()) { "displayName must not be blank" }
        }
    }

    /** The chip-row order (the order the UI shows the faces). */
    val ORDERED: List<Entry> = listOf(
        Entry("fira-sans", "FiraSans-Regular.ttf", "Fira Sans"),
        Entry("playfair-display", "PlayfairDisplay[wght].ttf", "Playfair Display"),
        Entry("caveat", "Caveat[wght].ttf", "Caveat"),
        Entry("space-mono", "SpaceMono-Regular.ttf", "Space Mono"),
    )

    /** The default face: new layers start here, unresolvable ids degrade here. */
    const val DEFAULT_ID = "fira-sans"

    fun byId(id: String): Entry? = ORDERED.firstOrNull { it.id == id }

    /** The asset path for an entry (the injected AssetManager opener receives this). */
    fun assetPath(entry: Entry): String = "fonts/${entry.id}/${entry.fileName}"

    /** The license path: each font's OFL.txt sits BESIDE its TTF (the licensing gate's rule). */
    fun licensePath(entry: Entry): String = "fonts/${entry.id}/OFL.txt"

    /**
     * The render-time resolution of a layer's [TextLayer.fontId]: a bundled
     * id resolves to itself; ANY other value (a version skew, a hand-edited
     * sidecar) resolves to the default face --- the layer still renders,
     * the id still round-trips verbatim in the recipe.
     */
    fun effectiveId(id: String): String = if (byId(id) != null) id else DEFAULT_ID
}

/**
 * The pure layout pass (JVM-tested): turns the recipe's fraction-space
 * layers into pixel-space draw specs IN LIST ORDER (the draw order: layers
 * render in list order, top LAST). The Android compositor executes these
 * specs verbatim; the preview and the export both call [place] through
 * `TextCompositor`, differing only in their origin/canvas arguments, so
 * preview ≡ export by construction.
 *
 * Text anchors are fractions of the OUTPUT canvas (`text-tool.md`
 * decision 3): the export passes (0, 0, outW, outH); the preview passes
 * the crop rect's origin and dimensions over the post-geometry frame, so
 * text lands where the crop's output will carry it. Text does NOT move
 * fractionally when a crop changes --- its center is re-expressed in the
 * new output canvas (the documented v1 honesty).
 */
object TextLayout {

    /** One layer resolved into pixel space, ready to draw (verbatim layer included). */
    data class Placed(
        val layer: TextLayer,
        /** The registry-resolved face ([BundledFonts.effectiveId]). */
        val resolvedFontId: String,
        /** The em size in output pixels. */
        val sizePx: Float,
        /** The anchor's pixel x within the drawing surface. */
        val centerX: Float,
        /** The anchor's pixel y within the drawing surface. */
        val centerY: Float,
        /** 0..255 paint alpha: the color's alpha byte x the opacity slider. */
        val alpha: Int,
    )

    /** The em size: percent of the canvas min-edge, as the contract defines it. */
    fun sizePxFor(sizePct: Int, canvasMinEdge: Float): Float = sizePct / 100f * canvasMinEdge

    /**
     * The paint alpha: the color's OWN alpha byte scaled by the opacity
     * slider, rounded half-up ((a*opacity + 50) / 100 in integer math ---
     * 0xFF at 100% stays 255; 0xFF at 50% is 128, not 127).
     */
    fun alphaFor(layer: TextLayer): Int {
        val colorAlpha = ((layer.colorArgb ushr 24) and 0xFFL).toInt()
        return (colorAlpha * layer.opacity + 50) / 100
    }

    /**
     * Places every layer in list order. The fraction clamp happens at
     * model/parse time; the pixel coerce here is pure defense (a
     * hand-constructed layer cannot leave 0..1, but the clamp documents
     * the invariant where it is consumed).
     */
    fun place(
        layers: List<TextLayer>,
        originX: Float,
        originY: Float,
        canvasW: Float,
        canvasH: Float,
    ): List<Placed> {
        require(canvasW > 0f && canvasH > 0f) { "canvas must have positive dimensions" }
        val minEdge = minOf(canvasW, canvasH)
        return layers.map { layer ->
            Placed(
                layer = layer,
                resolvedFontId = BundledFonts.effectiveId(layer.fontId),
                sizePx = sizePxFor(layer.sizePct, minEdge),
                centerX = (originX + layer.centerX * canvasW).toFloat().coerceIn(originX, originX + canvasW),
                centerY = (originY + layer.centerY * canvasH).toFloat().coerceIn(originY, originY + canvasH),
                alpha = alphaFor(layer),
            )
        }
    }
}
