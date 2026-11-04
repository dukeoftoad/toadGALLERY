package io.github.dukeoftoad.gallery.edit

import io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy
import java.io.File
import java.security.MessageDigest

/**
 * The non-destructive edit recipe (r30, Phase 6 v1; ADR-0011): the FULL
 * declarative state of an edit session, serialized as a versioned JSON
 * sidecar in the editor's app-private storage --- never overwriting the
 * original bytes (the source URI stays read-only by construction), and the
 * session survives process death: the sidecar is saved on every committed
 * change, restored on relaunch, and cleared only when the user exports.
 *
 * The codec is ADDITIVE-versioned like the launch contract: v1 fields are
 * frozen; v2 may add optional fields; an unknown key is IGNORED on parse
 * (forward compatibility), a wrong-typed or out-of-range value is a
 * structured parse failure (never a crash, never a silent default).
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
    // ---- Colour (the full-pixel stage) ----
    val saturation: Int = 0,
    val temperature: Int = 0,
    val tint: Int = 0,
    // ---- Colour grade + detail ----
    val hslBands: HslBands = HslBands(),
    val vignette: Int = 0,
    val sharpness: Int = 0,
    // ---- Filter (r33, Phase 7 v2: the .cube look layer) ----
    val lut: LutSelection? = null,
    // ---- Text (r34, Phase 7 v2: the OFL-font layer system) ----
    // Absent = a pre-r34 sidecar / a clean session; empty normalises to
    // absent. Unresolvable fontIds round-trip verbatim (the tolerance
    // contract; see TextLayers.kt).
    val textLayers: TextLayers? = null,
    // ---- Stickers (r35, Phase 7 v2: the sticker layer system) ----
    // Absent = a pre-r35 sidecar / a clean session; empty normalises to
    // absent. Unresolvable stickerIds round-trip verbatim and render
    // NOTHING at render time (the tolerance contract; see
    // StickerLayers.kt) --- there is no default sticker to degrade to.
    val stickerLayers: StickerLayers? = null,
) {

    val isGeometryIdentity: Boolean
        get() = quarterTurns % 4 == 0 && !horizontalFlip && !verticalFlip &&
            straightenDeg == 0.0 && crop == CropRect(0.0, 0.0, 1.0, 1.0) &&
            perspective == PerspectiveQuad.IDENTITY

    val isAdjustmentIdentity: Boolean
        get() = exposure == 0 && brightness == 0 && contrast == 0 && highlights == 0 &&
            shadows == 0 && saturation == 0 && temperature == 0 && tint == 0 &&
            hslBands.isIdentity && vignette == 0 && sharpness == 0

    /** A selected look at intensity 0 is dialed out --- no contribution. */
    val isFilterIdentity: Boolean
        get() = lut == null || lut.isIdentity

    /** No text layers --- nothing for the compositing pass to draw. */
    val isTextIdentity: Boolean
        get() = textLayers == null || textLayers.isIdentity

    /** No sticker layers --- nothing for the compositing pass to draw. */
    val isStickerIdentity: Boolean
        get() = stickerLayers == null || stickerLayers.isIdentity

    val isIdentity: Boolean
        get() = isGeometryIdentity && isAdjustmentIdentity && isFilterIdentity &&
            isTextIdentity && isStickerIdentity

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
 * The selected LUT look (r33, Phase 7 v2): WHICH bundled `.cube` look
 * and HOW MUCH of it. The id is the [BundledLuts] registry id; an id
 * the registry cannot resolve (a version skew, a hand-edited sidecar)
 * SURVIVES the round-trip untouched --- the recipe is the user's data ---
 * but the pipeline stage degrades to no-look rather than crashing.
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
 * The sidecar codec: `org.json` (the platform parser at runtime; the real
 * artifact on the JVM test classpath, exactly the gallery's r23 pattern).
 * The wire shape is one flat object + one nested `hsl` object; every field
 * optional on parse (defaults to identity) for forward compatibility.
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
        root.put("saturation", recipe.saturation)
        root.put("temperature", recipe.temperature)
        root.put("tint", recipe.tint)
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
        recipe.lut?.let {
            root.put(
                "lut",
                org.json.JSONObject()
                    .put("lutId", it.lutId)
                    .put("intensity", it.intensity),
            )
        }
        // r34: the key is written ONLY for a non-empty layer list, so a
        // pre-r34 sidecar and a clean session round-trip byte-clean.
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
        // r35: the same rule for stickers --- the key exists only for a
        // non-empty list, so a pre-r35 sidecar round-trips byte-clean.
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
            saturation = root.optInt("saturation", 0).coerceIn(-100, 100),
            temperature = root.optInt("temperature", 0).coerceIn(-100, 100),
            tint = root.optInt("tint", 0).coerceIn(-100, 100),
            hslBands = hsl,
            vignette = root.optInt("vignette", 0).coerceIn(-100, 100),
            sharpness = root.optInt("sharpness", 0).coerceIn(-100, 100),
            lut = readLut(root.optJSONObject("lut")),
            textLayers = readTextLayers(root.optJSONArray("textLayers")),
            stickerLayers = readStickerLayers(root.optJSONArray("stickerLayers")),
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
     * The optional `lut` object: absent (a pre-r33 sidecar) or carrying
     * a blank id means NO look; the intensity coerces like every other
     * Int slider. An unknown-but-present id is KEPT --- the recipe is
     * the user's data; resolution is the applier's problem.
     */
    private fun readLut(lut: org.json.JSONObject?): LutSelection? {
        if (lut == null) return null
        val id = lut.optString("lutId", "").trim()
        if (id.isEmpty()) return null
        return LutSelection(lutId = id, intensity = lut.optInt("intensity", 100).coerceIn(0, 100))
    }

    /**
     * The optional `textLayers` array (r34): absent or empty means NO text
     * (a pre-r34 sidecar loads clean). Tolerance rules, mirroring the LUT
     * contract: an entry that is not an object, or whose id is blank, is
     * SKIPPED (a layer without identity cannot be re-targeted by the UI,
     * the drag or undo/redo); a blank fontId means "no face chosen" and
     * reads as the registry default; an UNKNOWN non-blank fontId is KEPT
     * verbatim --- the recipe is the user's data, resolution is the
     * renderer's problem ([BundledFonts.effectiveId]). Numerics coerce
     * like every other slider; a malformed color reads as the default
     * color; text truncates at the model cap (the tolerant-read
     * convention --- a hand-edited sidecar never loses its whole session).
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
     * The optional `stickerLayers` array (r35): absent or empty means NO
     * stickers (a pre-r35 sidecar loads clean). Tolerance rules, the
     * r34 set with the one image-shaped delta: an entry that is not an
     * object, or whose id is blank, is SKIPPED; a blank `stickerId` is a
     * SKIP TOO --- with no default shape to degrade to, "no sticker
     * chosen" cannot be rendered (the r34 blank fontId read as the
     * default face; a fake sticker would draw something the user never
     * chose). An UNKNOWN non-blank stickerId is KEPT verbatim --- the
     * recipe is the user's data, resolution is the compositor's problem
     * (it renders nothing). Numerics coerce like every other slider
     * (the tolerant-read convention).
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

    /** The wire form is 8 uppercase hex digits (AARRGGBB); anything else reads as the default color. */
    private fun readColorArgb(s: String): Long =
        if (s.length == 8 && s.all { it in "0123456789abcdefABCDEF" }) s.toLong(16)
        else TextLayer.DEFAULT_COLOR

    private fun bandJson(band: HslBandAdjust): org.json.JSONObject =
        org.json.JSONObject()
            .put("hue", band.hue)
            .put("saturation", band.saturation)
            .put("luminance", band.luminance)
}

/**
 * The sidecar store: one JSON file per source media item inside the
 * editor's app-private `edit-sidecars/` directory, keyed by the SHA-256 of
 * the source URI (no filesystem path or URI text ever becomes a filename).
 * Writes are atomic (tmp + rename); a CORRUPT sidecar is deleted and
 * reported absent (self-healing, the repo's established state-file
 * pattern) --- the user's edit session degrades to a fresh one, never to a
 * crash or a half-applied recipe.
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
 * The export-time choices (NOT part of the recipe --- the recipe is the
 * edit state; format/quality are per-export decisions). JPEG, PNG and
 * WebP only: the platform Bitmap has NO HEIC encoder (HEIC is decode-only
 * in this app's manifest filter), so offering it would be a lie --- the
 * honest set is what the encoder can actually write.
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
) {
    init {
        require(quality in 1..100) { "quality must be in 1..100" }
    }
}
