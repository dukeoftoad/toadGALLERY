package io.github.dukeoftoad.gallery.data

import android.net.Uri

/**
 * One item in the toadGALLERY media timeline (Phase 3, issue #25).
 *
 * Identity follows ADR-0006: the item is anchored by its MediaStore `_id` and
 * accessed via a content `Uri` constructed from that id, never via a filesystem
 * path. The `dateAddedSec` value drives the timeline ordering (newest first);
 * the Phase 5 "Moments" grouping will layer on top of this same value.
 *
 * The grid UX slice (issues #33 and #34) adds the metadata those features need:
 * pixel `width`/`height` drive the aspect-ratio-aware staggered grid cell
 * sizing (#34), and `durationMs` drives the video duration overlay (#33).
 * GIFs are detected purely from the mimetype --- `mimeType == "image/gif"`
 * (see [isGif]) --- for the play indicator; the mimetype is otherwise
 * preserved for the future viewer and for Coil's decoder dispatch.
 *
 * The three later values are trailing properties with `0` defaults, which means
 * "unknown": MediaStore rows can lack the underlying columns, callers may
 * construct items without them, and the UI treats `0` dimensions as a
 * square-aspect fallback and `0` duration as "no overlay".
 *
 * Issue #66 adds [isFavorite] in the same trailing-default style: mapped from
 * the MediaStore `is_favorite` column wherever the provider exposes it
 * (Android 10+; below that it stays `false`, matching the read gate), it
 * drives the heart overlay on grid cells and the viewer's favorite toggle.
 * The flag itself is system-managed state in MediaStore (see
 * `data/Favorites.kt`), never app-private storage.
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mimeType: String,
    val dateAddedSec: Long,
    val isVideo: Boolean,
    /** Pixel width from MediaStore; `0` means "unknown". */
    val width: Int = 0,
    /** Pixel height from MediaStore; `0` means "unknown". */
    val height: Int = 0,
    /** Video duration in milliseconds from MediaStore; `0` means "unknown". */
    val durationMs: Long = 0,
    /** MediaStore `is_favorite` flag (issue #66); `false` when unprojected. */
    val isFavorite: Boolean = false,
) {
    /**
     * Whether this item is an animated GIF, for the play indicator
     * (issue #33). A case-insensitive mimetype compare is enough --- no
     * MediaStore round-trip, and a computed `get()` keeps it out of
     * `copy`/`equals` identity.
     */
    val isGif: Boolean
        get() = mimeType.equals("image/gif", ignoreCase = true)
}
