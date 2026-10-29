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
 * `isVideo` is the only type signal v1 needs (video duration overlay and GIF
 * play indicators arrive in a later Phase 3 slice). Mimetype is preserved for
 * the future viewer and for Coil's decoder dispatch.
 */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mimeType: String,
    val dateAddedSec: Long,
    val isVideo: Boolean,
)
