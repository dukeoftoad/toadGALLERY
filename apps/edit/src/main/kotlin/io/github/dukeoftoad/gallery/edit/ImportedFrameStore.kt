package io.github.dukeoftoad.gallery.edit

import java.io.File

/**
 * The user-imported frame store (r38, Phase 7): the SAF-picked PNG/WebP
 * files copied VERBATIM into the editor's app-private `imported-frames/`
 * directory, completing the roadmap's Phase 7 frame bullet ("the user
 * can import their own").
 *
 * The `ImportedStickerStore` contract verbatim (image-shaped; the store
 * never decodes images --- the magic-byte + size check IS the
 * parse-before-write step, and a payload that later fails to decode is
 * skipped at render time, never thrown). Ids derive from the picked
 * file-name stem, collision-suffixed against the bundled registry
 * ([BundledFrames.ORDERED]) and existing imports; the file keeps its
 * magic's extension; the directory listing IS the index; delete is
 * idempotent and path-safe. An imported frame stretches to the FULL
 * output canvas (the import assumes a frame authored at the canvas
 * aspect --- `frames.md` decision 4).
 *
 * Pure Kotlin (JVM-testable). The id/slug helpers duplicate the family
 * convention rather than coupling the stores.
 */
class ImportedFrameStore(private val dir: File) {

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    /** One imported frame in the listing: its id, its stored file. */
    data class ImportedFrame(val id: String, val fileName: String)

    sealed interface ImportResult {
        data class Imported(val frame: ImportedFrame) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    /** The imported frames, id-sorted (a stable chip row order). */
    fun list(): List<ImportedFrame> =
        dir.listFiles { file -> file.isFile && (file.name.endsWith(".png") || file.name.endsWith(".webp")) }
            .orEmpty()
            .map { file -> file.name.substringBeforeLast('.') }
            .filter { isValidId(it) }
            .sorted()
            .map { id -> ImportedFrame(id = id, fileName = fileNameFor(id)) }

    /**
     * Imports one image: validate FIRST (a rejection writes nothing),
     * derive a collision-free id, then store the bytes atomically.
     */
    fun import(bytes: ByteArray, suggestedName: String?): ImportResult {
        val kind = detectKind(bytes)
            ?: return ImportResult.Rejected("the file is not a PNG or WebP image")
        if (bytes.size > MAX_BYTES) {
            return ImportResult.Rejected("the frame is over the ${MAX_BYTES / 1_000_000} MB cap")
        }
        val stem = suggestedName?.substringBeforeLast('.', missingDelimiterValue = suggestedName) ?: ""
        val id = collisionFreeId(slugId(stem))
        val fileName = "$id.$kind"
        val target = File(dir, fileName)
        if (target.exists()) {
            // collisionFreeId guarantees a fresh name; unreachable
            // defensive honesty, not an expected path.
            return ImportResult.Rejected("could not derive a free id for the frame")
        }
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return ImportResult.Rejected("the imported frame could not be stored")
        }
        return ImportResult.Imported(ImportedFrame(id = id, fileName = fileName))
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

    /** Deletes one imported frame; false when absent (idempotent). */
    fun delete(id: String): Boolean {
        if (!isValidId(id)) return false
        return File(dir, "$id.png").delete() || File(dir, "$id.webp").delete()
    }

    // ---- internals ----

    private fun fileNameFor(id: String): String {
        val png = File(dir, "$id.png")
        return if (png.isFile) png.name else "$id.webp"
    }

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

    /** Free means: no bundled frame owns it and no stored import owns it. */
    private fun isFree(id: String): Boolean =
        BundledFrames.byId(id) == null && fileFor(id) == null

    companion object {
        /** The import size cap (the sticker store's, verbatim). */
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
