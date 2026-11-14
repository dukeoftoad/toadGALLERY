package com.toadgallery.editor

import com.toadgallery.editor.contract.MetadataPolicy
import java.io.File
import java.security.MessageDigest

/**
 * The full declarative state of an edit session, serialized as a versioned JSON sidecar (the
 * source bytes stay read-only). Additive versioning: unknown keys are IGNORED on parse; a
 * wrong-typed or out-of-range value is a structured parse failure, never a crash or silent default.
 */
data class EditRecipe(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    // ---- Geometry (applied in EditPipeline's documented order) ----
    val quarterTurns: Int = 0,
    val horizontalFlip: Boolean = false,
    val verticalFlip: Boolean = false,
    val straightenDeg: Double = 0.0,
    val crop: CropRect = CropRect(0.0, 0.0, 1.0, 1.0),
    val perspective: PerspectiveQuad = PerspectiveQuad.IDENTITY,
    // ---- Tone (the composed per-channel LUT) ----
    val exposure: Int = 0,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    // ---- Tone (whites/blacks join the LUT chain's far ends; absent reads
    // as 0, so existing sidecars round-trip byte-clean) ----
    val whites: Int = 0,
    val blacks: Int = 0,
    // ---- Colour (the full-pixel stage) ----
    val saturation: Int = 0,
    val temperature: Int = 0,
    val tint: Int = 0,
    // ---- Colour: vibrance, luma-weighted (PixelOps.applyVibrance) ----
    val vibrance: Int = 0,
    // ---- Colour grade + detail ----
    val hslBands: HslBands = HslBands(),
    val vignette: Int = 0,
    val sharpness: Int = 0,
    // ---- Tone curve ----
    // Absent = a clean session / a curve straightened back to the diagonal;
    // schema STAYS 1 (optional key, older readers ignore it). MASTER composes
    // into the tone LUT after shadows; per-channel curves ride as forward
    // state (see ToneCurves.kt).
    val toneCurves: ToneCurves? = null,
    // ---- Filter (.cube look layer) ----
    val lut: LutSelection? = null,
    // ---- Text (OFL-font layer system) ----
    // Empty normalises to absent; unresolvable fontIds round-trip verbatim
    // (the tolerance contract; see TextLayers.kt).
    val textLayers: TextLayers? = null,
    // ---- Stickers ----
    // Empty normalises to absent; unresolvable stickerIds round-trip verbatim
    // and render NOTHING (no default sticker to degrade to; see StickerLayers.kt).
    val stickerLayers: StickerLayers? = null,
    // ---- Drawing (freehand strokes) ----
    // Empty normalises to absent; one gesture = one layer = one undo snapshot;
    // points thinned at commit (see DrawLayers.kt).
    val drawLayers: DrawLayers? = null,
    // ---- Regions (mosaic/blur pixel stage) ----
    // Empty normalises to absent; runs as the pipeline's FINAL pixel stage,
    // after sharpen (see RegionLayers.kt).
    val mosaicRegions: RegionLayers? = null,
    // ---- Frame (single mounting) ----
    // ONE frame at a time; an unresolvable frameId round-trips verbatim and
    // degrades to NO FRAME at render time (see Frames.kt).
    val frame: FrameSelection? = null,
    // ---- AI containers ----
    // PIXEL STAGE families in the pipeline tail: erase -> sky -> bokeh ->
    // retouch (regions stay FINAL). Empty normalises to absent; an
    // unresolvable artifact key round-trips verbatim and renders NOTHING
    // (keys resolve in EditAiStore.kt; see AiLayers.kt).
    val aiErase: AiEraseLayers? = null,
    val sky: SkySelection? = null,
    val bokeh: BokehSelection? = null,
    val retouch: RetouchSelection? = null,
) {

    val isGeometryIdentity: Boolean
        get() = quarterTurns % 4 == 0 && !horizontalFlip && !verticalFlip &&
            straightenDeg == 0.0 && crop == CropRect(0.0, 0.0, 1.0, 1.0) &&
            perspective == PerspectiveQuad.IDENTITY

    val isAdjustmentIdentity: Boolean
        get() = exposure == 0 && brightness == 0 && contrast == 0 && highlights == 0 &&
            shadows == 0 && whites == 0 && blacks == 0 && saturation == 0 &&
            temperature == 0 && tint == 0 && vibrance == 0 &&
            hslBands.isIdentity && vignette == 0 && sharpness == 0 &&
            (toneCurves == null || toneCurves.isIdentity)

    /** A selected look at intensity 0 is dialed out --- no contribution. */
    val isFilterIdentity: Boolean
        get() = lut == null || lut.isIdentity

    /** No text layers --- nothing for the compositing pass to draw. */
    val isTextIdentity: Boolean
        get() = textLayers == null || textLayers.isIdentity

    /** No sticker layers --- nothing for the compositing pass to draw. */
    val isStickerIdentity: Boolean
        get() = stickerLayers == null || stickerLayers.isIdentity

    /** No strokes --- nothing for the compositing pass to draw. */
    val isDrawIdentity: Boolean
        get() = drawLayers == null || drawLayers.isIdentity

    /** No regions --- nothing for the pixel stage to censor. */
    val isRegionIdentity: Boolean
        get() = mosaicRegions == null || mosaicRegions.isIdentity

    /** No frame --- nothing for the mounting pass to draw. */
    val isFrameIdentity: Boolean
        get() = frame == null

    /** No erase layers --- nothing for the erase stage to fill. */
    val isAiEraseIdentity: Boolean
        get() = aiErase == null || aiErase.isIdentity

    /** No sky selection --- nothing for the sky stage to replace. */
    val isSkyIdentity: Boolean
        get() = sky == null

    /** No bokeh selection --- nothing for the bokeh stage to blur. */
    val isBokehIdentity: Boolean
        get() = bokeh == null

    /** No retouch selection --- nothing for the retouch stage to polish. */
    val isRetouchIdentity: Boolean
        get() = retouch == null

    /** Absent AI containers run nothing (the family rule). */
    val isAiIdentity: Boolean
        get() = isAiEraseIdentity && isSkyIdentity && isBokehIdentity && isRetouchIdentity

    val isIdentity: Boolean
        get() = isGeometryIdentity && isAdjustmentIdentity && isFilterIdentity &&
            isTextIdentity && isStickerIdentity && isDrawIdentity && isRegionIdentity &&
            isFrameIdentity && isAiIdentity

    init {
        require(quarterTurns in 0..3) { "quarterTurns must be in 0..3" }
        require(straightenDeg in -45.0..45.0) { "straightenDeg must be in [-45, 45]" }
        for (value in intArrayOf(
            exposure, brightness, contrast, highlights, shadows,
            saturation, temperature, tint, vignette, sharpness,
        )) {
            require(value in -100..100) { "adjustment $value outside [-100, 100]" }
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val MIN_SUPPORTED_SCHEMA_VERSION: Int = 1
    }
}

/**
 * The selected look: WHICH bundled `.cube` and HOW MUCH. The id is a [BundledLuts] registry id;
 * an unresolvable id (version skew, hand-edited sidecar) round-trips untouched --- the recipe is
 * the user's data --- but the pipeline stage degrades to no-look rather than crashing.
 */
data class LutSelection(
    val lutId: String,
    /** 0..100, the recipe's Int-slider convention; /100 at apply time. */
    val intensity: Int = 100,
) {
    init {
        require(lutId.isNotBlank()) { "lutId must not be blank" }
        require(intensity in 0..100) { "intensity must be in 0..100" }
    }

    /** A dialed-out look contributes nothing: the pipeline skips it. */
    val isIdentity: Boolean get() = intensity == 0
}

/**
 * The sidecar codec over `org.json` (the platform parser at runtime; the real artifact on the
 * JVM test classpath). The wire shape is one flat object + one nested `hsl` object; every field
 * is optional on parse (defaults to identity) for forward compatibility.
 */
object EditRecipeCodec {

    sealed interface ParseResult {
        data class Valid(val recipe: EditRecipe) : ParseResult
        data class Invalid(val reason: String) : ParseResult
    }

    fun toJson(recipe: EditRecipe): String {
        val root = org.json.JSONObject()
        root.put("schemaVersion", recipe.schemaVersion)
        root.put("quarterTurns", recipe.quarterTurns)
        root.put("horizontalFlip", recipe.horizontalFlip)
        root.put("verticalFlip", recipe.verticalFlip)
        root.put("straightenDeg", recipe.straightenDeg)
        root.put(
            "crop",
            org.json.JSONObject()
                .put("left", recipe.crop.left)
                .put("top", recipe.crop.top)
                .put("right", recipe.crop.right)
                .put("bottom", recipe.crop.bottom),
        )
        root.put(
            "perspective",
            org.json.JSONObject()
                .put("tlX", recipe.perspective.tlX).put("tlY", recipe.perspective.tlY)
                .put("trX", recipe.perspective.trX).put("trY", recipe.perspective.trY)
                .put("brX", recipe.perspective.brX).put("brY", recipe.perspective.brY)
                .put("blX", recipe.perspective.blX).put("blY", recipe.perspective.blY),
        )
        root.put("exposure", recipe.exposure)
        root.put("brightness", recipe.brightness)
        root.put("contrast", recipe.contrast)
        root.put("highlights", recipe.highlights)
        root.put("shadows", recipe.shadows)
        // The far-end tone pair rides the same flat-key shape (absent keys read as 0).
        root.put("whites", recipe.whites)
        root.put("blacks", recipe.blacks)
        root.put("saturation", recipe.saturation)
        root.put("temperature", recipe.temperature)
        root.put("tint", recipe.tint)
        root.put("vibrance", recipe.vibrance)
        val hsl = org.json.JSONObject()
        hsl.put("red", bandJson(recipe.hslBands.red))
        hsl.put("orange", bandJson(recipe.hslBands.orange))
        hsl.put("yellow", bandJson(recipe.hslBands.yellow))
        hsl.put("green", bandJson(recipe.hslBands.green))
        hsl.put("aqua", bandJson(recipe.hslBands.aqua))
        hsl.put("blue", bandJson(recipe.hslBands.blue))
        hsl.put("purple", bandJson(recipe.hslBands.purple))
        hsl.put("magenta", bandJson(recipe.hslBands.magenta))
        root.put("hsl", hsl)
        root.put("vignette", recipe.vignette)
        root.put("sharpness", recipe.sharpness)
        // Curve key written only when non-null (older sidecars round-trip byte-clean);
        // channel lists ride only when non-empty.
        recipe.toneCurves?.let { curves ->
            val curveObj = org.json.JSONObject()
            curveObj.put("master", curvePointsJson(curves.master))
            if (curves.red.isNotEmpty()) curveObj.put("red", curvePointsJson(curves.red))
            if (curves.green.isNotEmpty()) curveObj.put("green", curvePointsJson(curves.green))
            if (curves.blue.isNotEmpty()) curveObj.put("blue", curvePointsJson(curves.blue))
            root.put("toneCurves", curveObj)
        }
        recipe.lut?.let {
            root.put(
                "lut",
                org.json.JSONObject()
                    .put("lutId", it.lutId)
                    .put("intensity", it.intensity),
            )
        }
        // Key written ONLY for a non-empty layer list (older sidecars round-trip byte-clean).
        recipe.textLayers?.takeIf { it.layers.isNotEmpty() }?.let { tl ->
            val arr = org.json.JSONArray()
            for (layer in tl.layers) {
                arr.put(
                    org.json.JSONObject()
                        .put("id", layer.id)
                        .put("text", layer.text)
                        .put("fontId", layer.fontId)
                        .put("sizePct", layer.sizePct)
                        .put("color", "%08X".format(layer.colorArgb))
                        .put("opacity", layer.opacity)
                        .put("rotationDeg", layer.rotationDeg)
                        .put("centerX", layer.centerX)
                        .put("centerY", layer.centerY),
                )
            }
            root.put("textLayers", arr)
        }
        // Same rule for stickers: key written only for a non-empty list.
        recipe.stickerLayers?.takeIf { it.layers.isNotEmpty() }?.let { sl ->
            val arr = org.json.JSONArray()
            for (layer in sl.layers) {
                arr.put(
                    org.json.JSONObject()
                        .put("id", layer.id)
                        .put("stickerId", layer.stickerId)
                        .put("sizePct", layer.sizePct)
                        .put("opacity", layer.opacity)
                        .put("rotationDeg", layer.rotationDeg)
                        .put("centerX", layer.centerX)
                        .put("centerY", layer.centerY),
                )
            }
            root.put("stickerLayers", arr)
        }
        // Same rule for strokes; points ride as 2-element arrays of output-canvas fractions.
        recipe.drawLayers?.takeIf { it.layers.isNotEmpty() }?.let { dl ->
            val arr = org.json.JSONArray()
            for (layer in dl.layers) {
                val pts = org.json.JSONArray()
                for (p in layer.points) {
                    pts.put(org.json.JSONArray().put(p.x).put(p.y))
                }
                arr.put(
                    org.json.JSONObject()
                        .put("id", layer.id)
                        .put("color", "%08X".format(layer.colorArgb))
                        .put("widthPct", layer.widthPct)
                        .put("opacity", layer.opacity)
                        .put("smoothingPct", layer.smoothingPct)
                        .put("points", pts),
                )
            }
            root.put("drawLayers", arr)
        }
        // Same rule for regions; the kind rides its wire name.
        recipe.mosaicRegions?.takeIf { it.layers.isNotEmpty() }?.let { rl ->
            val arr = org.json.JSONArray()
            for (layer in rl.layers) {
                val pts = org.json.JSONArray()
                for (p in layer.points) {
                    pts.put(org.json.JSONArray().put(p.x).put(p.y))
                }
                arr.put(
                    org.json.JSONObject()
                        .put("id", layer.id)
                        .put("kind", layer.kind.wireName)
                        .put("strengthPct", layer.strengthPct)
                        .put("points", pts),
                )
            }
            root.put("mosaicRegions", arr)
        }
        // Single mounting; the lut object's shape.
        recipe.frame?.let {
            root.put(
                "frame",
                org.json.JSONObject()
                    .put("frameId", it.frameId)
                    .put("widthPct", it.widthPct)
                    .put("color", "%08X".format(it.colorArgb)),
            )
        }
        // Same rule for the AI containers: keys written only for a non-empty state.
        recipe.aiErase?.takeIf { it.layers.isNotEmpty() }?.let { el ->
            val arr = org.json.JSONArray()
            for (layer in el.layers) {
                val pts = org.json.JSONArray()
                for (p in layer.points) {
                    pts.put(org.json.JSONArray().put(p.x).put(p.y))
                }
                arr.put(
                    org.json.JSONObject()
                        .put("id", layer.id)
                        .put("artifactRef", layer.artifactRef)
                        .put("points", pts),
                )
            }
            root.put("aiErase", arr)
        }
        recipe.sky?.let {
            root.put(
                "sky",
                org.json.JSONObject()
                    .put("maskRef", it.maskRef)
                    .put("skyId", it.skyId)
                    .put("opacityPct", it.opacityPct),
            )
        }
        recipe.bokeh?.let {
            root.put(
                "bokeh",
                org.json.JSONObject()
                    .put("depthRef", it.depthRef)
                    .put("blurStrengthPct", it.blurStrengthPct)
                    .put("focalDepthNorm", it.focalDepthNorm),
            )
        }
        recipe.retouch?.let {
            root.put(
                "retouch",
                org.json.JSONObject()
                    .put("boxesRef", it.boxesRef)
                    .put("smoothingPct", it.smoothingPct)
                    .put("eyeBrightenPct", it.eyeBrightenPct)
                    .put("faceSlimPct", it.faceSlimPct),
            )
        }
        return root.toString()
    }

    fun fromJson(text: String): ParseResult = try {
        val root = org.json.JSONObject(text)
        val version = root.optInt("schemaVersion", EditRecipe.CURRENT_SCHEMA_VERSION)
        if (version < EditRecipe.MIN_SUPPORTED_SCHEMA_VERSION || version > EditRecipe.CURRENT_SCHEMA_VERSION) {
            return ParseResult.Invalid("schema version $version outside the supported window")
        }
        val cropObject = root.optJSONObject("crop") ?: org.json.JSONObject()
        val perspectiveObject = root.optJSONObject("perspective") ?: org.json.JSONObject()
        val hslObject = root.optJSONObject("hsl") ?: org.json.JSONObject()

        // The band name order is the HslBands.ordered() contract; keys are
        // written by centre-degree name so the wire shape is stable.
        val hsl = HslBands(
            red = readBand(hslObject, "red"),
            orange = readBand(hslObject, "orange"),
            yellow = readBand(hslObject, "yellow"),
            green = readBand(hslObject, "green"),
            aqua = readBand(hslObject, "aqua"),
            blue = readBand(hslObject, "blue"),
            purple = readBand(hslObject, "purple"),
            magenta = readBand(hslObject, "magenta"),
        )
        val recipe = EditRecipe(
            schemaVersion = version,
            quarterTurns = root.optInt("quarterTurns", 0).let { ((it % 4) + 4) % 4 },
            horizontalFlip = root.optBoolean("horizontalFlip", false),
            verticalFlip = root.optBoolean("verticalFlip", false),
            straightenDeg = root.optDouble("straightenDeg", 0.0),
            crop = CropRect(
                left = cropObject.optDouble("left", 0.0),
                top = cropObject.optDouble("top", 0.0),
                right = cropObject.optDouble("right", 1.0),
                bottom = cropObject.optDouble("bottom", 1.0),
            ).normalized(),
            perspective = PerspectiveQuad(
                tlX = perspectiveObject.optDouble("tlX", 0.0),
                tlY = perspectiveObject.optDouble("tlY", 0.0),
                trX = perspectiveObject.optDouble("trX", 0.0),
                trY = perspectiveObject.optDouble("trY", 0.0),
                brX = perspectiveObject.optDouble("brX", 0.0),
                brY = perspectiveObject.optDouble("brY", 0.0),
                blX = perspectiveObject.optDouble("blX", 0.0),
                blY = perspectiveObject.optDouble("blY", 0.0),
            ),
            exposure = root.optInt("exposure", 0).coerceIn(-100, 100),
            brightness = root.optInt("brightness", 0).coerceIn(-100, 100),
            contrast = root.optInt("contrast", 0).coerceIn(-100, 100),
            highlights = root.optInt("highlights", 0).coerceIn(-100, 100),
            shadows = root.optInt("shadows", 0).coerceIn(-100, 100),
            whites = root.optInt("whites", 0).coerceIn(-100, 100),
            blacks = root.optInt("blacks", 0).coerceIn(-100, 100),
            saturation = root.optInt("saturation", 0).coerceIn(-100, 100),
            temperature = root.optInt("temperature", 0).coerceIn(-100, 100),
            tint = root.optInt("tint", 0).coerceIn(-100, 100),
            vibrance = root.optInt("vibrance", 0).coerceIn(-100, 100),
            hslBands = hsl,
            vignette = root.optInt("vignette", 0).coerceIn(-100, 100),
            sharpness = root.optInt("sharpness", 0).coerceIn(-100, 100),
            toneCurves = readToneCurves(root.optJSONObject("toneCurves")),
            lut = readLut(root.optJSONObject("lut")),
            textLayers = readTextLayers(root.optJSONArray("textLayers")),
            stickerLayers = readStickerLayers(root.optJSONArray("stickerLayers")),
            drawLayers = readDrawLayers(root.optJSONArray("drawLayers")),
            mosaicRegions = readRegions(root.optJSONArray("mosaicRegions")),
            frame = readFrame(root.optJSONObject("frame")),
            aiErase = readAiErase(root.optJSONArray("aiErase")),
            sky = readSky(root.optJSONObject("sky")),
            bokeh = readBokeh(root.optJSONObject("bokeh")),
            retouch = readRetouch(root.optJSONObject("retouch")),
        )
        ParseResult.Valid(recipe)
    } catch (e: org.json.JSONException) {
        ParseResult.Invalid("malformed sidecar JSON: ${e.message}")
    } catch (e: IllegalArgumentException) {
        ParseResult.Invalid("sidecar value out of range: ${e.message}")
    }

    private fun readBand(hsl: org.json.JSONObject, name: String): HslBandAdjust {
        val band = hsl.optJSONObject(name) ?: return HslBandAdjust()
        return HslBandAdjust(
            hue = band.optInt("hue", 0).coerceIn(-180, 180),
            saturation = band.optInt("saturation", 0).coerceIn(-100, 100),
            luminance = band.optInt("luminance", 0).coerceIn(-100, 100),
        )
    }

    /**
     * The optional `lut` object: absent or blank id means NO look; an unknown-but-present id
     * is KEPT (the recipe is the user's data; resolution is the applier's problem). The
     * intensity coerces like every other Int slider.
     */
    private fun readLut(lut: org.json.JSONObject?): LutSelection? {
        if (lut == null) return null
        val id = lut.optString("lutId", "").trim()
        if (id.isEmpty()) return null
        return LutSelection(lutId = id, intensity = lut.optInt("intensity", 100).coerceIn(0, 100))
    }

    /**
     * The optional `textLayers` array: absent/empty means NO text. Tolerance: a non-object
     * or blank-id entry is SKIPPED; a blank fontId reads as the registry default; an UNKNOWN
     * non-blank fontId is KEPT verbatim (resolution is the renderer's problem,
     * [BundledFonts.effectiveId]); numerics coerce; a malformed color reads as the default;
     * text truncates at the model cap (a hand-edited sidecar never loses its session).
     */
    private fun readTextLayers(arr: org.json.JSONArray?): TextLayers? {
        if (arr == null) return null
        val layers = ArrayList<TextLayer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val fontId = o.optString("fontId", "").trim()
                .ifEmpty { BundledFonts.DEFAULT_ID }
                .take(TextLayer.MAX_ID_LENGTH)
            layers.add(
                TextLayer(
                    id = id.take(TextLayer.MAX_ID_LENGTH),
                    text = o.optString("text", "").take(TextLayer.MAX_TEXT_LENGTH),
                    fontId = fontId,
                    sizePct = o.optInt("sizePct", TextLayer.DEFAULT_SIZE_PCT).coerceIn(0, 300),
                    colorArgb = readColorArgb(o.optString("color", "")),
                    opacity = o.optInt("opacity", 100).coerceIn(0, 100),
                    rotationDeg = o.optInt("rotationDeg", 0).coerceIn(-180, 180),
                    centerX = o.optDouble("centerX", 0.5).coerceIn(0.0, 1.0),
                    centerY = o.optDouble("centerY", 0.5).coerceIn(0.0, 1.0),
                ),
            )
        }
        return if (layers.isEmpty()) null else TextLayers(layers)
    }

    /**
     * The optional `stickerLayers` array: absent/empty means NO stickers. Tolerance: a
     * non-object or blank id/stickerId entry is SKIPPED --- with no default shape, "no sticker
     * chosen" cannot be rendered; an UNKNOWN non-blank stickerId is KEPT verbatim (resolution
     * is the compositor's problem: it renders nothing); numerics coerce.
     */
    private fun readStickerLayers(arr: org.json.JSONArray?): StickerLayers? {
        if (arr == null) return null
        val layers = ArrayList<StickerLayer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val stickerId = o.optString("stickerId", "").trim()
                .take(StickerLayer.MAX_ID_LENGTH)
            if (stickerId.isEmpty()) continue
            layers.add(
                StickerLayer(
                    id = id.take(StickerLayer.MAX_ID_LENGTH),
                    stickerId = stickerId,
                    sizePct = o.optInt("sizePct", StickerLayer.DEFAULT_SIZE_PCT).coerceIn(0, 300),
                    opacity = o.optInt("opacity", 100).coerceIn(0, 100),
                    rotationDeg = o.optInt("rotationDeg", 0).coerceIn(-180, 180),
                    centerX = o.optDouble("centerX", 0.5).coerceIn(0.0, 1.0),
                    centerY = o.optDouble("centerY", 0.5).coerceIn(0.0, 1.0),
                ),
            )
        }
        return if (layers.isEmpty()) null else StickerLayers(layers)
    }

    /**
     * The optional `drawLayers` array: absent/empty means NO strokes. A stroke is its own data
     * (no foreign id): a non-object or blank-id entry is SKIPPED; a point that is not a
     * 2-element numeric array is dropped; a layer with zero surviving points is skipped;
     * points clamp to 0..1 and cap at the model cap.
     */
    private fun readDrawLayers(arr: org.json.JSONArray?): DrawLayers? {
        if (arr == null) return null
        val layers = ArrayList<DrawLayer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val ptsArr = o.optJSONArray("points") ?: continue
            val pts = ArrayList<DrawLayer.Point>(ptsArr.length())
            for (j in 0 until ptsArr.length()) {
                val pair = ptsArr.optJSONArray(j) ?: continue
                if (pair.length() != 2) continue
                val x = pair.optDouble(0, Double.NaN)
                val y = pair.optDouble(1, Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                pts.add(DrawLayer.Point(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)))
                if (pts.size >= DrawLayer.MAX_POINTS) break
            }
            if (pts.isEmpty()) continue
            layers.add(
                DrawLayer(
                    id = id.take(DrawLayer.MAX_ID_LENGTH),
                    colorArgb = readColorArgb(o.optString("color", "")),
                    widthPct = o.optInt("widthPct", DrawLayer.DEFAULT_WIDTH_PCT).coerceIn(1, 100),
                    opacity = o.optInt("opacity", 100).coerceIn(0, 100),
                    smoothingPct = o.optInt("smoothingPct", DrawLayer.DEFAULT_SMOOTHING_PCT).coerceIn(0, 100),
                    points = pts,
                ),
            )
        }
        return if (layers.isEmpty()) null else DrawLayers(layers)
    }

    /**
     * The optional `mosaicRegions` array: absent/empty means NO regions. Tolerance (the stroke
     * family's plus the kind): a blank id skips the layer; an unknown `kind` is corrupt data
     * --- SKIPPED, never a crash; a layer with fewer than three surviving points is skipped
     * (a region needs an area); points clamp to 0..1 and cap at the model cap.
     */
    private fun readRegions(arr: org.json.JSONArray?): RegionLayers? {
        if (arr == null) return null
        val layers = ArrayList<RegionLayer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val kind = when (o.optString("kind", "")) {
                RegionKind.MOSAIC.wireName -> RegionKind.MOSAIC
                RegionKind.LINEAR_BLUR.wireName -> RegionKind.LINEAR_BLUR
                else -> continue // an unknown kind is corrupt data: the skip
            }
            val ptsArr = o.optJSONArray("points") ?: continue
            val pts = ArrayList<RegionLayer.Point>(ptsArr.length())
            for (j in 0 until ptsArr.length()) {
                val pair = ptsArr.optJSONArray(j) ?: continue
                if (pair.length() != 2) continue
                val x = pair.optDouble(0, Double.NaN)
                val y = pair.optDouble(1, Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                pts.add(RegionLayer.Point(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)))
                if (pts.size >= RegionLayer.MAX_POINTS) break
            }
            if (pts.size < 3) continue
            layers.add(
                RegionLayer(
                    id = id.take(RegionLayer.MAX_ID_LENGTH),
                    kind = kind,
                    strengthPct = o.optInt("strengthPct", RegionLayer.DEFAULT_STRENGTH_PCT).coerceIn(1, 100),
                    points = pts,
                ),
            )
        }
        return if (layers.isEmpty()) null else RegionLayers(layers)
    }

    /**
     * The optional `frame` object (the `lut` tolerance shape): absent or blank id means NO
     * frame; an UNKNOWN non-blank frameId is KEPT verbatim (the compositor renders nothing);
     * widthPct coerces like every other slider (default 20); a malformed colour reads as
     * the default white.
     */
    private fun readFrame(frame: org.json.JSONObject?): FrameSelection? {
        if (frame == null) return null
        val id = frame.optString("frameId", "").trim()
        if (id.isEmpty()) return null
        return FrameSelection(
            frameId = id.take(FrameSelection.MAX_ID_LENGTH),
            widthPct = frame.optInt("widthPct", FrameSelection.DEFAULT_WIDTH_PCT).coerceIn(1, 100),
            colorArgb = readColorArgb(frame.optString("color", "")),
        )
    }

    /** The wire form is 8 uppercase hex digits (AARRGGBB); anything else reads as the default color. */
    private fun readColorArgb(s: String): Long =
        if (s.length == 8 && s.all { it in "0123456789abcdefABCDEF" }) s.toLong(16)
        else TextLayer.DEFAULT_COLOR

    /** One channel's points as [x, y] pairs; the receiver of readToneCurves' arrays. */
    private fun curvePointsJson(points: List<CurvePoint>): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for (p in points) {
            arr.put(org.json.JSONArray().put(p.x).put(p.y))
        }
        return arr
    }

    /**
     * The optional `toneCurves` object: absent means NO curve. Point tolerance lives in
     * LilypadCurveMath.sanitize (coerce to 0..1, sort by x, drop duplicate-x, cap). A MASTER
     * under two surviving points reads the WHOLE container as absent (the engine is
     * master-driven; a channel-only state has no honest render/edit surface); channels under
     * two points read empty.
     */
    private fun readToneCurves(curves: org.json.JSONObject?): ToneCurves? {
        if (curves == null) return null
        val master = LilypadCurveMath.sanitize(readCurvePoints(curves.optJSONArray("master")))
        if (master.size < LilypadCurveMath.MIN_POINTS) return null
        return ToneCurves(
            master = master,
            red = LilypadCurveMath.sanitize(readCurvePoints(curves.optJSONArray("red"))),
            green = LilypadCurveMath.sanitize(readCurvePoints(curves.optJSONArray("green"))),
            blue = LilypadCurveMath.sanitize(readCurvePoints(curves.optJSONArray("blue"))),
        )
    }

    /** The raw tolerant point read (coordinate coercion and capping live in sanitize). */
    private fun readCurvePoints(arr: org.json.JSONArray?): List<CurvePoint> {
        if (arr == null) return emptyList()
        val pts = ArrayList<CurvePoint>(minOf(arr.length(), LilypadCurveMath.MAX_POINTS))
        for (i in 0 until arr.length()) {
            val pair = arr.optJSONArray(i) ?: continue
            if (pair.length() != 2) continue
            val x = pair.optDouble(0, Double.NaN)
            val y = pair.optDouble(1, Double.NaN)
            if (x.isNaN() || y.isNaN()) continue
            pts.add(CurvePoint(x, y))
            if (pts.size >= LilypadCurveMath.MAX_POINTS) break
        }
        return pts
    }

    /**
     * The optional `aiErase` array: absent/empty means NO erase. Region-family tolerance: a
     * non-object or blank-id entry is SKIPPED, as is a seed under three points (an erase needs
     * an area); an empty artifactRef is KEPT --- the layer round-trips verbatim and renders
     * nothing (no default fill to degrade to).
     */
    private fun readAiErase(arr: org.json.JSONArray?): AiEraseLayers? {
        if (arr == null) return null
        val layers = ArrayList<AiEraseLayer>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            val ptsArr = o.optJSONArray("points") ?: continue
            val pts = ArrayList<AiEraseLayer.Point>(ptsArr.length())
            for (j in 0 until ptsArr.length()) {
                val pair = ptsArr.optJSONArray(j) ?: continue
                if (pair.length() != 2) continue
                val x = pair.optDouble(0, Double.NaN)
                val y = pair.optDouble(1, Double.NaN)
                if (x.isNaN() || y.isNaN()) continue
                pts.add(AiEraseLayer.Point(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0)))
                if (pts.size >= AiEraseLayer.MAX_POINTS) break
            }
            if (pts.size < 3) continue
            layers.add(
                AiEraseLayer(
                    id = id.take(AiEraseLayer.MAX_ID_LENGTH),
                    points = pts,
                    artifactRef = o.optString("artifactRef", "").trim()
                        .take(SkySelection.MAX_REF_LENGTH),
                ),
            )
        }
        return if (layers.isEmpty()) null else AiEraseLayers(layers)
    }

    /**
     * The optional `sky` object (the `frame` tolerance shape): absent or blank skyId means NO
     * sky; a blank maskRef is KEPT verbatim (renders nothing); opacity coerces (default 100).
     */
    private fun readSky(sky: org.json.JSONObject?): SkySelection? {
        if (sky == null) return null
        val id = sky.optString("skyId", "").trim()
        if (id.isEmpty()) return null
        return SkySelection(
            maskRef = sky.optString("maskRef", "").trim().take(SkySelection.MAX_REF_LENGTH),
            skyId = id.take(SkySelection.MAX_ID_LENGTH),
            opacityPct = sky.optInt("opacityPct", SkySelection.DEFAULT_OPACITY_PCT).coerceIn(0, 100),
        )
    }

    /**
     * The optional `bokeh` object: absent means NO bokeh; depthRef is kept verbatim; strength
     * coerces 1..100 (default 40); focal depth is a 0..1 double (default 0.5).
     */
    private fun readBokeh(bokeh: org.json.JSONObject?): BokehSelection? {
        if (bokeh == null) return null
        val focal = bokeh.optDouble("focalDepthNorm", BokehSelection.DEFAULT_FOCAL_DEPTH_NORM)
        return BokehSelection(
            depthRef = bokeh.optString("depthRef", "").trim().take(SkySelection.MAX_REF_LENGTH),
            blurStrengthPct = bokeh.optInt("blurStrengthPct", BokehSelection.DEFAULT_BLUR_STRENGTH_PCT)
                .coerceIn(1, 100),
            focalDepthNorm = if (focal.isNaN()) {
                BokehSelection.DEFAULT_FOCAL_DEPTH_NORM
            } else {
                focal.coerceIn(0.0, 1.0)
            },
        )
    }

    /**
     * The optional `retouch` object: absent means NO retouch; boxesRef is kept verbatim; the
     * three controls coerce 0..100 (default 0, no contribution).
     */
    private fun readRetouch(retouch: org.json.JSONObject?): RetouchSelection? {
        if (retouch == null) return null
        return RetouchSelection(
            boxesRef = retouch.optString("boxesRef", "").trim().take(SkySelection.MAX_REF_LENGTH),
            smoothingPct = retouch.optInt("smoothingPct", 0).coerceIn(0, 100),
            eyeBrightenPct = retouch.optInt("eyeBrightenPct", 0).coerceIn(0, 100),
            faceSlimPct = retouch.optInt("faceSlimPct", 0).coerceIn(0, 100),
        )
    }

    private fun bandJson(band: HslBandAdjust): org.json.JSONObject =
        org.json.JSONObject()
            .put("hue", band.hue)
            .put("saturation", band.saturation)
            .put("luminance", band.luminance)
}

/**
 * The sidecar store: one JSON file per source media item in app-private `edit-sidecars/`,
 * keyed by the SHA-256 of the source URI (no path or URI text ever becomes a filename).
 * Writes are atomic (tmp + rename); a corrupt sidecar is deleted and reported absent
 * (self-healing) --- a fresh session, never a crash or a half-applied recipe.
 */
class EditRecipeStore(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun fileFor(sourceUri: String): File = File(dir, "recipe-${sha256(sourceUri)}.json")

    fun save(sourceUri: String, recipe: EditRecipe) {
        val target = fileFor(sourceUri)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(EditRecipeCodec.toJson(recipe))
        if (!tmp.renameTo(target)) {
            // Cross-device or atomic-rename failure: fall back to a direct
            // write (still one JSON document, never a partial one).
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** The stored recipe, or null when absent (a corrupt file self-heals away). */
    fun load(sourceUri: String): EditRecipe? {
        val file = fileFor(sourceUri)
        if (!file.exists()) return null
        return when (val parsed = EditRecipeCodec.fromJson(file.readText())) {
            is EditRecipeCodec.ParseResult.Valid -> parsed.recipe
            is EditRecipeCodec.ParseResult.Invalid -> {
                file.delete()
                null
            }
        }
    }

    fun clear(sourceUri: String) {
        fileFor(sourceUri).delete()
    }

    companion object {
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Per-export choices (NOT recipe state). JPEG/PNG/WebP only: the platform Bitmap has no HEIC
 * encoder (HEIC is decode-only here), so offering it would be a lie --- the honest set is what
 * the encoder can actually write.
 */
enum class ExportFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    companion object {
        fun fromWire(value: String?): ExportFormat? = entries.firstOrNull { it.name == value }
    }
}

/** The export request the UI hands the pipeline: recipe + format + quality + policy. */
data class ExportRequest(
    val recipe: EditRecipe,
    val format: ExportFormat,
    /** JPEG/WebP quality 1..100 (PNG ignores it --- lossless). */
    val quality: Int,
    val metadataPolicy: MetadataPolicy,
    /**
     * The preview grid width the recipe was authored against: the keystone quad is authored in
     * preview pixels, so [LilypadKeystoneMath.scaledForExport] needs it to retarget the warp at
     * full export resolution. 0 = unknown; a 0 grid never scales (the quad rides verbatim).
     */
    val previewWidth: Int = 0,
    /** [previewWidth]'s height twin. */
    val previewHeight: Int = 0,
) {
    init {
        require(quality in 1..100) { "quality must be in 1..100" }
    }
}
