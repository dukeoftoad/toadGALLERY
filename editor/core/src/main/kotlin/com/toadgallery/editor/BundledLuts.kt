package com.toadgallery.editor

import java.io.InputStream

/**
 * The bundled-look registry + loader. The five looks are PROJECT-AUTHORED:
 * `tools/gen_bundled_luts.py` deterministically generates every committed `.cube`
 * (no third-party LUT data); the assets ship inside the APK (`assets/luts/`), keeping
 * the zero-network editor's no-egress guarantee. Pure Kotlin (JVM-testable): the
 * platform injects the stream opener; a look that cannot be read or parsed is SKIPPED,
 * never thrown (the conformance test pins that every committed asset parses).
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
     * Parses ONE look through [opener]. Returns null when the asset is missing or does
     * not parse --- the caller treats that as "this look does not exist in this build".
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
     * Parses every bundled look into an id-keyed map; entries that fail are absent
     * (the UI shows only what parsed). Built once per session start and shared by the
     * preview and the export --- the same [CubeLut] instances, so the look cannot differ.
     */
    fun loadAll(opener: (String) -> InputStream): Map<String, CubeLut> {
        val loaded = LinkedHashMap<String, CubeLut>(ORDERED.size)
        for (entry in ORDERED) {
            load(entry, opener)?.let { loaded[entry.id] = it }
        }
        return loaded
    }
}
