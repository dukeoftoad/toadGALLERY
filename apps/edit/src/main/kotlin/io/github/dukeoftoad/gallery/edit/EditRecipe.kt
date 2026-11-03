package io.github.dukeoftoad.gallery.edit

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * The versioned edit recipe (r30; ADR-0011 --- the non-destructive
 * pipeline's persistence half). The session's [EditParameters] serialize
 * to a JSON sidecar stored in the editor's PRIVATE storage, keyed by the
 * source URI's SHA-256; re-opening the same source in the editor reloads
 * its recipe, so edits are re-editable across sessions without ever
 * touching the original bytes (the export stays the only write path, and
 * it writes a NEW MediaStore row --- the r29 contract).
 *
 * JSON shape (recipe_version 1):
 * ```
 * { "recipe_version": 1,
 *   "source": "<source uri>",
 *   "adjust": { brightness, contrast, exposure, highlights, shadows,
 *               saturation, temperature, tint, vignette, sharpness },
 *   "hsl": { red|orange|yellow|green|aqua|blue|purple|magenta:
 *            { hue, saturation, luminance } },
 *   "geometry": { quarter_turns, straighten_deg, flip_h, flip_v,
 *                 crop: null|{left,top,right,bottom},
 *                 perspective: { tl_x, tl_y, tr_x, tr_y, br_x, br_y,
 *                                 bl_x, bl_y } } }
 * ```
 * Parsing is TOLERANT by design: any missing key falls back to its
 * default (a recipe from an earlier version loads with the newer
 * parameters defaulted), while a recipe_version the reader does not
 * understand, or any structurally invalid value, rejects the whole file
 * (null) --- a corrupt sidecar never corrupts a session; the user starts
 * from the photo, exactly as before sidecars existed.
 */
object EditRecipe {

    const val VERSION: Int = 1

    fun toJson(parameters: EditParameters, sourceUri: String?): JSONObject {
        val adjust = JSONObject()
            .put("brightness", parameters.brightness)
            .put("contrast", parameters.contrast)
            .put("exposure", parameters.exposure)
            .put("highlights", parameters.highlights)
            .put("shadows", parameters.shadows)
            .put("saturation", parameters.saturation)
            .put("temperature", parameters.temperature)
            .put("tint", parameters.tint)
            .put("vignette", parameters.vignette)
            .put("sharpness", parameters.sharpness)
        val hsl = JSONObject()
        for ((name, band) in listOf(
            "red" to parameters.hsl.red, "orange" to parameters.hsl.orange,
            "yellow" to parameters.hsl.yellow, "green" to parameters.hsl.green,
            "aqua" to parameters.hsl.aqua, "blue" to parameters.hsl.blue,
            "purple" to parameters.hsl.purple, "magenta" to parameters.hsl.magenta,
        )) {
            hsl.put(
                name,
                JSONObject()
                    .put("hue", band.hue)
                    .put("saturation", band.saturation)
                    .put("luminance", band.luminance),
            )
        }
        val geometry = JSONObject()
            .put("quarter_turns", parameters.quarterTurns)
            .put("straighten_deg", parameters.straightenDeg)
            .put("flip_h", parameters.flipH)
            .put("flip_v", parameters.flipV)
            .put("crop", parameters.crop?.let { c ->
                JSONObject()
                    .put("left", c.left)
                    .put("top", c.top)
                    .put("right", c.right)
                    .put("bottom", c.bottom)
            } ?: JSONObject.NULL)
            .put(
                "perspective",
                JSONObject()
                    .put("tl_x", parameters.perspective.tlX)
                    .put("tl_y", parameters.perspective.tlY)
                    .put("tr_x", parameters.perspective.trX)
                    .put("tr_y", parameters.perspective.trY)
                    .put("br_x", parameters.perspective.brX)
                    .put("br_y", parameters.perspective.brY)
                    .put("bl_x", parameters.perspective.blX)
                    .put("bl_y", parameters.perspective.blY),
            )
        return JSONObject()
            .put("recipe_version", VERSION)
            .put("source", sourceUri ?: JSONObject.NULL)
            .put("adjust", adjust)
            .put("hsl", hsl)
            .put("geometry", geometry)
    }

    /** Tolerant load; null on unknown version, malformed shape or invalid ranges. */
    fun fromJson(json: JSONObject): EditParameters? {
        return try {
            if (json.optInt("recipe_version", -1) != VERSION) return null
            val adjust = json.optJSONObject("adjust") ?: JSONObject()
            val hslJson = json.optJSONObject("hsl") ?: JSONObject()
            val geometry = json.optJSONObject("geometry") ?: JSONObject()
            fun band(name: String): HslBandAdjust {
                val b = hslJson.optJSONObject(name) ?: JSONObject()
                return HslBandAdjust(
                    hue = b.optInt("hue"),
                    saturation = b.optInt("saturation"),
                    luminance = b.optInt("luminance"),
                )
            }
            val cropJson = geometry.optJSONObject("crop")
            val crop = if (cropJson == null) {
                null
            } else {
                CropRect(
                    left = cropJson.optDouble("left"),
                    top = cropJson.optDouble("top"),
                    right = cropJson.optDouble("right"),
                    bottom = cropJson.optDouble("bottom"),
                ).normalized()
            }
            val persp = geometry.optJSONObject("perspective") ?: JSONObject()
            val parameters = EditParameters(
                brightness = adjust.optInt("brightness"),
                contrast = adjust.optInt("contrast"),
                exposure = adjust.optInt("exposure"),
                highlights = adjust.optInt("highlights"),
                shadows = adjust.optInt("shadows"),
                saturation = adjust.optInt("saturation"),
                temperature = adjust.optInt("temperature"),
                tint = adjust.optInt("tint"),
                vignette = adjust.optInt("vignette"),
                sharpness = adjust.optInt("sharpness"),
                hsl = HslBands(
                    red = band("red"), orange = band("orange"),
                    yellow = band("yellow"), green = band("green"),
                    aqua = band("aqua"), blue = band("blue"),
                    purple = band("purple"), magenta = band("magenta"),
                ),
                quarterTurns = geometry.optInt("quarter_turns"),
                straightenDeg = geometry.optDouble("straighten_deg"),
                flipH = geometry.optBoolean("flip_h"),
                flipV = geometry.optBoolean("flip_v"),
                crop = crop,
                perspective = PerspectiveQuad(
                    tlX = persp.optDouble("tl_x"),
                    tlY = persp.optDouble("tl_y"),
                    trX = persp.optDouble("tr_x"),
                    trY = persp.optDouble("tr_y"),
                    brX = persp.optDouble("br_x"),
                    brY = persp.optDouble("br_y"),
                    blX = persp.optDouble("bl_x"),
                    blY = persp.optDouble("bl_y"),
                ),
            )
            parameters.validate()
            parameters
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NumberFormatException) {
            null
        }
    }
}

/**
 * The sidecar store: one JSON file per source URI in the editor's private
 * storage. Writes are ATOMIC (tmp file + rename) so a process death mid-
 * save never leaves a half-written sidecar; a corrupt file is reported as
 * absent (load -> null) rather than crashing the session open.
 */
class SidecarStore(private val dir: File) {

    /** The directory, created on demand. */
    private fun ensureDir(): File = dir.apply { if (!isDirectory) mkdirs() }

    /** The sidecar file name for a source URI: SHA-256 hex + ".json". */
    fun fileFor(sourceUri: String): File =
        File(ensureDir(), sha256Hex(sourceUri) + ".json")

    /** Atomic save: write to <name>.tmp, then rename over the target. */
    fun save(sourceUri: String, json: JSONObject): Boolean = try {
        val target = fileFor(sourceUri)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(json.toString())
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    } catch (_: Exception) {
        false
    }

    /** Load; null when absent, unreadable or unparseable. */
    fun load(sourceUri: String): JSONObject? = try {
        val file = fileFor(sourceUri)
        if (!file.isFile) return null
        JSONObject(file.readText())
    } catch (_: Exception) {
        null
    }

    /** Load + parse through [EditRecipe]; null on any failure. */
    fun loadRecipe(sourceUri: String): EditParameters? =
        load(sourceUri)?.let { EditRecipe.fromJson(it) }

    companion object {
        /** The editor's store: <filesDir>/edit-sidecars (app-private, never backed up: allowBackup=false). */
        fun inApp(context: Context): SidecarStore =
            SidecarStore(File(context.filesDir, "edit-sidecars"))

        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
