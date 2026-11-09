package com.toadgallery.editor

import java.io.InputStream

/**
 * The bundled-look registry + loader (r33, Phase 7 v2).
 *
 * The five looks are PROJECT-AUTHORED: `tools/gen_bundled_luts.py`
 * deterministically generates every committed `.cube` from the
 * documented formulas (no third-party LUT data --- the licensing note
 * is `docs/phase-7/bundled-luts.md`), and the assets ship inside the
 * editor APK (`assets/luts/`), so the zero-network editor keeps its
 * no-egress guarantee: a look is bytes in the APK, never a download.
 *
 * This object is PURE Kotlin (JVM-testable): the platform side injects
 * the stream opener (`context.assets::open`-shaped); a look that cannot
 * be read or parsed is SKIPPED, never thrown --- a broken bundle
 * degrades to fewer chips, and the conformance test pins that every
 * COMMITTED asset parses, so a skip means a broken build, not bad data.
 */
object BundledLuts {

    /** One bundled look: the registry id and its asset file name. */
    data class Entry(val id: String, val fileName: String) {
        init {
            require(id.isNotBlank()) { "id must not be blank" }
            require(fileName.endsWith(".cube")) { "fileName must be a .cube" }
        }
    }

    /** The chip-row order: the order the UI shows the looks. */
    val ORDERED: List<Entry> = listOf(
        Entry("warm-film", "warm-film.cube"),
        Entry("cool-chrome", "cool-chrome.cube"),
        Entry("silver-mono", "silver-mono.cube"),
        Entry("matte-fade", "matte-fade.cube"),
        Entry("vivid-punch", "vivid-punch.cube"),
    )

    /** The entry for [id], or null when this build does not bundle it. */
    fun byId(id: String): Entry? = ORDERED.firstOrNull { it.id == id }

    /** The asset path for an entry (the injected opener receives this). */
    fun assetPath(entry: Entry): String = "luts/${entry.fileName}"

    /**
     * Parses ONE look through [opener] (which must produce the asset's
     * bytes for the path from [assetPath]). Returns null when the asset
     * is missing or does not parse --- the caller treats that as
     * "this look does not exist in this build" and moves on.
     */
    fun load(entry: Entry, opener: (String) -> InputStream): CubeLut? = try {
        opener(assetPath(entry)).use { stream ->
            when (val parsed = CubeLutParser.parse(stream.readBytes().decodeToString(), id = entry.id)) {
                is CubeLutParser.Result.Valid -> parsed.lut
                is CubeLutParser.Result.Invalid -> null
            }
        }
    } catch (_: java.io.IOException) {
        null
    }

    /**
     * Parses every bundled look into an id-keyed map; entries that fail
     * are absent (the UI shows only what parsed). The map is built once
     * per session start and shared by the preview and the export --- the
     * same [CubeLut] instances, so the look cannot differ between them.
     */
    fun loadAll(opener: (String) -> InputStream): Map<String, CubeLut> {
        val loaded = LinkedHashMap<String, CubeLut>(ORDERED.size)
        for (entry in ORDERED) {
            load(entry, opener)?.let { loaded[entry.id] = it }
        }
        return loaded
    }
}
