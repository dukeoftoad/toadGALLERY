package com.toadgallery.editor

import java.io.File

/**
 * The user-imported sticker store: SAF-picked PNG/WebP files copied VERBATIM into the
 * app-private `imported-stickers/` directory.
 *
 * Contract:
 *  - The user's BYTES are stored verbatim (never re-encoded); writes are atomic
 *    (tmp + rename) and a REJECTED import leaves the store byte-identical (validation
 *    happens BEFORE any write). The store never decodes images: the magic-byte + size
 *    check IS the parse-before-write step; a payload that fails to decode later is
 *    skipped at render time, never thrown.
 *  - The id is DERIVED, never trusted: slug of the file-name stem, `[a-z0-9-]`, 40 chars;
 *    a collision with a bundled or existing id is SUFFIXED (-2, -3, ...), so an import
 *    never shadows the bundled set (the merged registry's keys are disjoint by construction).
 *  - The file keeps its magic's extension (`$id.png` / `$id.webp`); the directory listing
 *    IS the index, so it can never drift from the files.
 *  - Deleting a sticker a sidecar still references is SAFE: the recipe keeps the id
 *    verbatim and the compositor renders nothing.
 *
 * Pure Kotlin (JVM-testable): the caller supplies the bytes. The id/slug helpers duplicate
 * the `ImportedLutStore` convention rather than coupling the stores.
 */
class ImportedStickerStore(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** One imported sticker in the listing: its id, its stored file, its display title (the id). */
    data class ImportedSticker(val id: String, val fileName: String)

    sealed interface ImportResult {
        data class Imported(val sticker: ImportedSticker) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    /** The imported stickers, id-sorted (a stable chip row order). */
    fun list(): List<ImportedSticker> =
        dir.listFiles { file -> file.isFile && (file.name.endsWith(".png") || file.name.endsWith(".webp")) }
            .orEmpty()
            .map { file -> file.name.substringBeforeLast('.') }
            .filter { isValidId(it) }
            .sorted()
            .map { id -> ImportedSticker(id = id, fileName = fileNameFor(id)) }

    /**
     * Imports one image: validate FIRST (a rejection writes nothing), derive a collision-free
     * id, store atomically. [suggestedName] (the picked file's display name) is the id source.
     */
    fun import(bytes: ByteArray, suggestedName: String?): ImportResult {
        val kind = detectKind(bytes)
            ?: return ImportResult.Rejected("the file is not a PNG or WebP image")
        if (bytes.size > MAX_BYTES) {
            return ImportResult.Rejected("the sticker is over the ${MAX_BYTES / 1_000_000} MB cap")
        }
        val stem = suggestedName?.substringBeforeLast('.', missingDelimiterValue = suggestedName) ?: ""
        val id = collisionFreeId(slugId(stem))
        val fileName = "$id.$kind"
        val target = File(dir, fileName)
        if (target.exists()) {
            // Unreachable (collisionFreeId guarantees a fresh name); defensive, not an expected path.
            return ImportResult.Rejected("could not derive a free id for the sticker")
        }
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return ImportResult.Rejected("the imported sticker could not be stored")
        }
        return ImportResult.Imported(ImportedSticker(id = id, fileName = fileName))
    }

    /** The stored file for [id], or null when absent. */
    fun fileFor(id: String): File? {
        if (!isValidId(id)) return null
        val png = File(dir, "$id.png")
        if (png.isFile) return png
        val webp = File(dir, "$id.webp")
        if (webp.isFile) return webp
        return null
    }

    fun isImported(id: String): Boolean = fileFor(id) != null

    /** Deletes one imported sticker (whichever extension it stored under); false when absent (idempotent). */
    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        val removed = File(dir, "$id.png").delete() || File(dir, "$id.webp").delete()
        return removed
    }

    // ---- internals ----

    private fun fileNameFor(id: String): String {
        val png = File(dir, "$id.png")
        return if (png.isFile) png.name else "$id.webp"
    }

    /** The magic-byte check: PNG (8-byte signature) or WebP (RIFF....WEBP); null = not an image this store takes. */
    private fun detectKind(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        val isPng = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        if (isPng) return "png"
        val isWebP = bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        if (isWebP) return "webp"
        return null
    }

    private fun collisionFreeId(base: String): String {
        if (isFree(base)) return base
        var n = 2
        while (true) {
            val candidate = "$base-$n"
            if (isFree(candidate)) return candidate
            n++
        }
    }

    /** Free means: no bundled sticker owns it and no stored import owns it. */
    private fun isFree(id: String): Boolean =
        BundledStickers.byId(id) == null && fileFor(id) == null

    companion object {
        /** The import size cap: 2 MB of sticker is already a poster. */
        const val MAX_BYTES: Int = 2_000_000

        /** Ids are `[a-z0-9-]`, 1..40 chars (they become file names). */
        fun isValidId(id: String): Boolean =
            id.length in 1..40 && id.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

        /** Derives the store id from a file-name stem. */
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
