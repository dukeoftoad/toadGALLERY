package com.toadgallery.editor

import java.io.File

/**
 * The user-imported `.cube` look store (r33 extension, Phase 7): the
 * SAF-picked look files copied into the editor's app-private
 * `imported-luts/` directory, completing the roadmap's Phase 7 task-1
 * bullet ("the user can import their own .cube files").
 *
 * Design contract:
 *  - The user's BYTES are stored verbatim (never re-serialized) — the
 *    same "the file is the author's data" philosophy as the parser's
 *    out-of-range tolerance and the recipe's unresolvable-id
 *    round-trip. Writes are atomic (tmp + rename, the sidecar store's
 *    pattern); a REJECTED import leaves the store byte-identical
 *    (parse happens BEFORE any write).
 *  - The id is DERIVED, never trusted: slug of the parsed TITLE (or
 *    the suggested file-name stem), sanitized to `[a-z0-9-]`, capped at
 *    40 chars. An id that would collide with a BUNDLED look
 *    ([BundledLuts.ORDERED]) or an EXISTING import is SUFFIXED
 *    (`-2`, `-3`, ...) — an import can never shadow the bundled set,
 *    so the merged registry's keys are disjoint by construction.
 *  - There is no index file: the directory listing IS the index
 *    (list/loadAll re-derive), so it can never drift from the files.
 *  - Deleting a look a sidecar still references is SAFE by the r33
 *    tolerance contract: the recipe keeps the id verbatim and the
 *    pipeline degrades to no-look.
 *
 * Pure Kotlin (JVM-testable): the caller supplies the bytes (the UI
 * reads the SAF stream through the content resolver) and the store
 * owns everything else.
 */
class ImportedLutStore(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** One imported look in the listing: its id, its stored file, its display title. */
    data class ImportedLut(val id: String, val fileName: String, val title: String)

    sealed interface ImportResult {
        data class Imported(val lut: ImportedLut) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    /** The imported looks, id-sorted (a stable chip row order). */
    fun list(): List<ImportedLut> =
        scanned().map { (id, parsed) ->
            ImportedLut(
                id = id,
                fileName = "$id.cube",
                title = parsed?.title?.takeIf { it.isNotBlank() } ?: id,
            )
        }

    /**
     * Imports one `.cube` file: parse FIRST (a rejection writes
     * nothing), derive a collision-free id, then store the bytes
     * atomically. [suggestedName] (the picked file's display name) is
     * the id fallback when the file carries no usable TITLE.
     */
    fun import(bytes: ByteArray, suggestedName: String?): ImportResult {
        val parsed = when (val result = CubeLutParser.parse(bytes.decodeToString())) {
            is CubeLutParser.Result.Valid -> result.lut
            is CubeLutParser.Result.Invalid ->
                return ImportResult.Rejected(result.reason)
        }
        // The suggestedName's STEM (the extension is the picker's artifact,
        // not the look's name): "Beach 2026.cube" slugs to "beach-2026".
        val stem = suggestedName?.substringBeforeLast('.', missingDelimiterValue = suggestedName) ?: ""
        val baseId = slugId(parsed.title.ifBlank { stem })
        val id = collisionFreeId(baseId)
        val fileName = "$id.cube"
        val target = File(dir, fileName)
        if (target.exists()) {
            // collisionFreeId guarantees a fresh name; this is unreachable
            // defensive honesty, not an expected path.
            return ImportResult.Rejected("could not derive a free id for the look")
        }
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return ImportResult.Rejected("the imported look could not be stored")
        }
        return ImportResult.Imported(
            ImportedLut(id = id, fileName = fileName, title = parsed.title.ifBlank { id }),
        )
    }

    /** The stored look parsed under its id, or null (absent/corrupt → no-look). */
    fun load(id: String): CubeLut? {
        if (!isValidId(id)) return null
        return parseStored(id)
    }

    /** All imported looks parsed; broken files are absent, never thrown. */
    fun loadAll(): Map<String, CubeLut> {
        val loaded = LinkedHashMap<String, CubeLut>()
        for ((id, parsed) in scanned()) {
            parsed?.let { loaded[id] = it }
        }
        return loaded
    }

    fun isImported(id: String): Boolean =
        isValidId(id) && File(dir, "$id.cube").exists()

    /** Deletes one imported look; false when absent (idempotent). */
    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        return File(dir, "$id.cube").delete()
    }

    // ---- internals ----

    /**
     * One directory scan, one parse per file: id-sorted (id, parsed)
     * pairs. Non-id-shaped or corrupt files surface as null parses
     * (absent from loadAll, title-fallback in list) --- never thrown.
     */
    private fun scanned(): List<Pair<String, CubeLut?>> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".cube") }
            .orEmpty()
            .map { file -> file.name.removeSuffix(".cube") }
            .filter { isValidId(it) }
            .sorted()
            .map { id -> id to parseStored(id) }

    private fun collisionFreeId(base: String): String {
        if (isFree(base)) return base
        var n = 2
        while (true) {
            val candidate = "$base-$n"
            if (isFree(candidate)) return candidate
            n++
        }
    }

    /** Free means: no bundled look owns it and no stored import owns it. */
    private fun isFree(id: String): Boolean =
        BundledLuts.byId(id) == null && !File(dir, "$id.cube").exists()

    private fun parseStored(id: String): CubeLut? {
        val file = File(dir, "$id.cube")
        if (!file.isFile) return null
        return when (val parsed = CubeLutParser.parse(file.readText(), id = id)) {
            is CubeLutParser.Result.Valid -> parsed.lut
            is CubeLutParser.Result.Invalid -> null
        }
    }

    companion object {
        /** Ids are `[a-z0-9-]`, 1..40 chars (they become file names). */
        fun isValidId(id: String): Boolean =
            id.length in 1..40 && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

        /** Derives the store id from a title or file-name stem. */
        fun slugId(source: String): String {
            val slug = source.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(40)
                .trim('-')
            return slug.ifEmpty { "imported" }
        }
    }
}
