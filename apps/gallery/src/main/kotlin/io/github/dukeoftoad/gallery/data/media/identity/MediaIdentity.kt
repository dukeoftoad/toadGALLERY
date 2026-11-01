package io.github.dukeoftoad.gallery.data.media.identity

/**
 * The durable identity of one indexable media item (Phase 4 contract,
 * `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`).
 *
 * Identity is anchored by WHERE the item lives in the MediaStore --- its
 * storage [volumeName] plus the provider row id ([mediaStoreId]) --- never
 * by a filesystem path or display name, which rename freely and would
 * silently reattach AI outputs (faces, embeddings, clusters) to the wrong
 * photo. The [canonicalUri] is the resolved content `Uri` string for the
 * same row; it is the ACCESS handle (ADR-0006 URI-first), while
 * [volumeName] + [mediaStoreId] are the STABLE identity. Removable volumes
 * are distinguished by their volume name, so the same `_id` on two
 * different SD cards never collides into one key.
 *
 * [pageId] covers embedded sub-items (for example a page inside a
 * multi-page TIFF or an item inside a collection) once Phase 4's format
 * work reaches them; it is `null` for every ordinary single-item row,
 * which is everything the current catalogue tracks.
 *
 * The serialization ([toKeyString]/[fromKeyString]) is the "local media
 * key" that downstream engines (the Ente-derived ML store maps its integer
 * `file_id` onto exactly this string) persist in their own databases; it
 * must therefore stay stable across process restarts, sorting changes and
 * metadata refreshes. The format is `volume:id` or `volume:id:page`, with
 * the volume name percent-encoded when it contains the `:` separator
 * (volume names are volume UUIDs or `external_primary`/`internal`, so this
 * never fires in practice, but the parser stays total).
 */
data class MediaIdentity(
    /** MediaStore volume name (`external_primary`, a volume UUID, ...). */
    val volumeName: String,
    /** The MediaStore `_id` of the row within [volumeName]. */
    val mediaStoreId: Long,
    /** The resolved content `Uri` string; access handle, not identity. */
    val canonicalUri: String,
    /** Optional embedded-item identity (multi-page/collection members). */
    val pageId: String? = null,
) {
    /** The stable local media key; see the class KDoc for the format. */
    fun toKeyString(): String {
        val volume = volumeName.replace("%", "%25").replace(":", "%3A")
        return buildString {
            append(volume)
            append(KEY_SEPARATOR)
            append(mediaStoreId)
            if (pageId != null) {
                append(KEY_SEPARATOR)
                append(pageId.replace("%", "%25").replace(":", "%3A"))
            }
        }
    }

    companion object {
        private const val KEY_SEPARATOR = ":"

        /**
         * Parses a key produced by [toKeyString]. Returns `null` for
         * anything malformed (wrong segment count, non-numeric id) ---
         * callers treat that as an unknown item, never a crash.
         */
        fun fromKeyString(key: String): MediaIdentity? {
            val parts = key.split(KEY_SEPARATOR)
            val volumeName: String
            val idPart: String
            val pageId: String?
            when (parts.size) {
                2 -> {
                    volumeName = parts[0]
                    idPart = parts[1]
                    pageId = null
                }
                3 -> {
                    volumeName = parts[0]
                    idPart = parts[1]
                    pageId = parts[2]
                }
                else -> return null
            }
            val id = idPart.toLongOrNull() ?: return null
            if (volumeName.isEmpty()) return null
            return MediaIdentity(
                volumeName = volumeName.replace("%3A", ":").replace("%25", "%"),
                mediaStoreId = id,
                // A parsed key carries no access handle; the caller
                // re-resolves the Uri from the catalogue when needed.
                canonicalUri = "",
                pageId = pageId?.replace("%3A", ":")?.replace("%25", "%"),
            )
        }
    }
}
