package io.github.dukeoftoad.gallery.data

import android.net.Uri

/**
 * One folder/bucket in the toadGALLERY Albums view (Phase 3, issue #29).
 *
 * MediaStore groups media by *bucket*: the folder containing the file, keyed
 * by `BUCKET_ID` (a stable hash of the path on most devices). Identity follows
 * ADR-0006: the cover thumbnail is a content `Uri` built from the bucket's
 * most recent item --- never a filesystem path.
 *
 * The same bucket can hold both images and videos; the source layer merges
 * both collections before producing this value.
 */
data class Album(
    val bucketId: Long,
    val bucketName: String,
    val itemCount: Int,
    val coverUri: Uri,
    val latestDateSec: Long,
)
