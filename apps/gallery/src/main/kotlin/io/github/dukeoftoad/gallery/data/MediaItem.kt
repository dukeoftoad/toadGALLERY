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
 *
 * The r13 timeline round adds [captureDateMillis]: the resolved capture
 * instant the scan pipeline's index pass read out of the item's own metadata
 * (EXIF/XMP/video headers) and persisted in the media catalogue. MediaStore
 * rows carry no such value, so every MediaStore-fed surface (the fallback
 * timeline, albums, favorites, trash, search) leaves it `null` --- only the
 * catalogue-backed timeline path populates it.
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
    /**
     * The resolved capture instant in epoch millis (r13), or `null`.
     *
     * THE EFFECTIVE INSTANT RULE: the instant that orders and groups the
     * timeline is `captureDateMillis ?: dateAddedSec * 1000` --- the
     * capture date when the scan pipeline resolved one, else the
     * MediaStore `date_added` instant. `null` therefore means "not
     * resolved (yet), or this item came from a MediaStore-fed surface",
     * never "captured at the epoch": a RESOLVED capture of the epoch is
     * `0L` and legitimately wins over `dateAddedSec`.
     *
     * Can be NEGATIVE: pre-1970 capture instants are legitimate (scanned
     * family archives; see the MediaDates contract) and flow through the
     * date math like any other instant.
     *
     * Display/ordering only, NEVER identity: the item stays anchored by
     * its MediaStore `_id` + content [uri] (ADR-0006). A capture date that
     * changes (re-scan, re-resolution) re-orders the timeline; it never
     * re-keys a cell or detaches an AI output from its item.
     */
    val captureDateMillis: Long? = null,
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
