package com.toadgallery.gallery.data.vault

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * One entry of the hidden vault's index (issue #32, ADR-0017).
 *
 * The index is the vault's only catalog: it records everything the UI needs
 * to list an entry (name, type, size) without touching the sealed blob, and
 * everything [VaultStore] needs to find the blob again ([id] is the blob
 * file name, so a file listing never leaks a display name). Pure JVM on
 * purpose --- no `android.*` import --- so the round-trip contract is pinned
 * by `VaultIndexTest` on the plain JVM (ADR-0016).
 *
 * Privacy (ADR-0014): the entry is metadata for an on-device encrypted
 * store; it is never transmitted anywhere (the app holds no INTERNET
 * permission) and it exists on disk only inside the sealed index blob.
 */
data class VaultIndexEntry(
    /** Stable id; also the sealed blob's file name. Positive, unique per vault. */
    val id: Long,
    /** The original MediaStore display name (with extension), shown in the vault list. */
    val displayName: String,
    /** The original mimetype (e.g. `image/jpeg`); guides the restore insert. */
    val mimeType: String,
    /** Plaintext byte size of the sealed media (what the UI shows, not the blob size). */
    val sizeBytes: Long,
    /** When the item was moved into the vault, epoch milliseconds. */
    val addedAtMillis: Long,
    /** Whether the media is a video (drives the row icon and the restore collection). */
    val isVideo: Boolean,
)

/**
 * Hand-rolled serialization of the vault index (issue #32, ADR-0017).
 *
 * A compact line format instead of a JSON dependency (the project deliberately
 * has none): a fixed header line, then one line per entry, TAB-separated,
 * UTF-8. Text fields ([VaultIndexEntry.displayName], [VaultIndexEntry.mimeType])
 * are backslash-escaped (`\\`, `\t`, `\n`, `\r`) so ANY legal display name
 * round-trips exactly; numeric fields are plain decimals; the video flag is
 * `0`/`1`. The whole file is sealed by [VaultStore] under the vault key, so
 * the parser's job is not secrecy but HONESTY: a malformed line is rejected
 * with [VaultIndexException] rather than silently dropped or half-parsed.
 *
 * Pure JVM (like the rest of `data/vault`) so the format contract is pinned
 * by JVM tests; privacy is on-device-only (ADR-0010/ADR-0014) --- nothing
 * here can leave the device because the app holds no INTERNET permission.
 */
object VaultIndex {

    /** First line of every serialized index; a version fence for future changes. */
    const val FORMAT_HEADER = "toadvault-index-v1"

    /** Fields per entry line: id, displayName, mimeType, sizeBytes, addedAt, isVideo. */
    private const val FIELD_COUNT = 6

    /** Thrown when raw bytes do not parse as a well-formed index. */
    class VaultIndexException(message: String) : Exception(message)

    /**
     * Serializes [entries] (any order; [VaultStore] persists in insertion
     * order). Always produces the canonical form: header line, then one line
     * per entry, each line terminated by `\n`.
     */
    fun serialize(entries: List<VaultIndexEntry>): ByteArray {
        val out = ByteArrayOutputStream(entries.size * 64 + 32)
        out.write((FORMAT_HEADER + "\n").toByteArray(StandardCharsets.UTF_8))
        for (entry in entries) {
            val line = listOf(
                entry.id.toString(),
                escape(entry.displayName),
                escape(entry.mimeType),
                entry.sizeBytes.toString(),
                entry.addedAtMillis.toString(),
                if (entry.isVideo) "1" else "0",
            ).joinToString(separator = "\t")
            out.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
        }
        return out.toByteArray()
    }

    /**
     * Parses bytes produced by [serialize]. Strict by design: a missing or
     * unknown header, a wrong field count, non-numeric numbers, a bad flag,
     * a non-positive id, a negative size or timestamp, an empty display
     * name, an unknown escape, or a duplicate id all fail with
     * [VaultIndexException] --- the caller must treat the whole index as
     * rejected, never salvage a prefix. A trailing newline after the last
     * entry is optional (both spellings parse).
     */
    fun deserialize(raw: ByteArray): List<VaultIndexEntry> {
        val text = String(raw, StandardCharsets.UTF_8)
        val lines = text.split('\n')
        // "a\n" -> ["a", ""]; a canonical file always ends with \n, so the
        // final empty piece is the terminator, not an entry. A file without
        // the trailing newline simply has no empty final piece.
        val body = if (lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.dropLast(1)
        } else {
            lines
        }
        if (body.isEmpty() || body.first() != FORMAT_HEADER) {
            throw VaultIndexException("not a vault index (missing or unknown header)")
        }
        val entries = mutableListOf<VaultIndexEntry>()
        val seenIds = mutableSetOf<Long>()
        for ((index, line) in body.drop(1).withIndex()) {
            val entryNumber = index + 1
            if (line.isEmpty()) {
                throw VaultIndexException("empty entry line $entryNumber")
            }
            val fields = line.split('\t')
            if (fields.size != FIELD_COUNT) {
                throw VaultIndexException(
                    "entry line $entryNumber has ${fields.size} fields, expected $FIELD_COUNT",
                )
            }
            val id = fields[0].toLongOrNull()
                ?: throw VaultIndexException("entry line $entryNumber has a malformed id")
            if (id <= 0) {
                throw VaultIndexException("entry line $entryNumber has a non-positive id")
            }
            if (!seenIds.add(id)) {
                throw VaultIndexException("entry line $entryNumber repeats id $id")
            }
            val displayName = unescape(fields[1], entryNumber)
            if (displayName.isEmpty()) {
                throw VaultIndexException("entry line $entryNumber has an empty display name")
            }
            val mimeType = unescape(fields[2], entryNumber)
            val sizeBytes = fields[3].toLongOrNull()
                ?: throw VaultIndexException("entry line $entryNumber has a malformed size")
            if (sizeBytes < 0) {
                throw VaultIndexException("entry line $entryNumber has a negative size")
            }
            val addedAtMillis = fields[4].toLongOrNull()
                ?: throw VaultIndexException("entry line $entryNumber has a malformed timestamp")
            if (addedAtMillis < 0) {
                throw VaultIndexException("entry line $entryNumber has a negative timestamp")
            }
            val isVideo = when (fields[5]) {
                "0" -> false
                "1" -> true
                else -> throw VaultIndexException("entry line $entryNumber has a malformed video flag")
            }
            entries += VaultIndexEntry(
                id = id,
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                addedAtMillis = addedAtMillis,
                isVideo = isVideo,
            )
        }
        return entries
    }

    /**
     * The next free stable id for a new entry: one past the current maximum.
     * Ids stay positive and unique among LIVE entries (the index rejects
     * duplicates); an id freed by a delete may be re-allocated later, which
     * is collision-free --- the blob file was removed first, and any crash
     * orphan left behind is reclaimed by the sweep or safely overwritten.
     */
    fun nextId(entries: List<VaultIndexEntry>): Long =
        (entries.maxOfOrNull { it.id } ?: 0L) + 1L

    // ------------------------------------------------------------------
    // Escaping (text fields only)
    // ------------------------------------------------------------------

    /** Escapes `\`, TAB, LF and CR so field/line structure can never be forged. */
    private fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(char)
            }
        }
    }

    /** Inverse of [escape]; any other char after a backslash is rejected. */
    private fun unescape(text: String, entryNumber: Int): String = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char != '\\') {
                append(char)
                i++
                continue
            }
            if (i + 1 >= text.length) {
                throw VaultIndexException("entry line $entryNumber ends with a dangling escape")
            }
            when (val next = text[i + 1]) {
                '\\' -> append('\\')
                't' -> append('\t')
                'n' -> append('\n')
                'r' -> append('\r')
                else -> throw VaultIndexException(
                    "entry line $entryNumber has an unknown escape \\$next",
                )
            }
            i += 2
        }
    }
}
