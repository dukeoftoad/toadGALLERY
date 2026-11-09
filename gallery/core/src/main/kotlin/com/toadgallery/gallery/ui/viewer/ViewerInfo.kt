package com.toadgallery.gallery.ui.viewer

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore
import com.toadgallery.gallery.data.DataSizeFormats
import com.toadgallery.gallery.ui.media.formatDurationMs
import java.text.DateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The viewer's info sheet (issue #63): item details for the CURRENT page.
 *
 * Split in two halves, following the repo's pure-logic culture
 * (`FileOpNaming`, `DataSizeFormats`, `DurationFormats`):
 *
 *  - [ViewerInfoFormat] is pure and Android-free except `java.text`
 *    formatting, so `ViewerInfoTest` pins every "unknown" edge and the
 *    name-from-URI fallback on the plain JVM (ADR-0016 --- no emulator,
 *    no Robolectric). Every function returns `null` for "unknown" and the
 *    composable maps `null` to the localized `viewer_info_unknown` string
 *    --- the formatter never touches resources.
 *  - [queryViewerInfoLookup] is the single OPTIONAL MediaStore round-trip
 *    (one row query for `DISPLAY_NAME` + `SIZE`, the two columns
 *    [com.toadgallery.gallery.data.MediaItem] does not carry). It is
 *    best-effort by design: a missing/invisible row (trashed, removed,
 *    hidden by another app) yields all-null and the sheet falls back to
 *    what the item itself knows (ADR-0006: the URI is the identity).
 */
object ViewerInfoFormat {

    /**
     * The display name to show: the provider's `DISPLAY_NAME` when the
     * lookup produced one, else the URI-derived fallback
     * ([nameFromUri]), else `null` (unknown). Blank provider names
     * (HEIC/RAW rows can carry none) fall through to the fallback rather
     * than rendering whitespace.
     */
    fun displayName(providerName: String?, uriString: String): String? =
        providerName?.takeIf { it.isNotBlank() } ?: nameFromUri(uriString)

    /**
     * Best-effort file name from a content URI string: the last path
     * segment after stripping any query/fragment. `null` when there is no
     * usable segment --- including the all-digit segment of a MediaStore
     * row URI (`content://media/external/images/media/42` -> "42"),
     * which is the row `_id`, NOT a name; showing "42" as a file name
     * would be a lie.
     */
    fun nameFromUri(uriString: String): String? {
        val withoutQuery = uriString.substringBefore('?').substringBefore('#')
        val segment = withoutQuery.substringAfterLast('/').trim()
        if (segment.isEmpty()) return null
        if (segment.all { it.isDigit() }) return null // a row id, not a name
        return segment
    }

    /**
     * `"w × h"` from the MediaStore pixel dimensions, or `null` when
     * either side is unknown ([com.toadgallery.gallery.data.MediaItem]
     * documents `0` as "unknown").
     */
    fun dimensions(width: Int, height: Int): String? =
        if (width <= 0 || height <= 0) null else "$width × $height"

    /**
     * The video duration via the grid's [formatDurationMs], or `null` for
     * photos/GIFs and unknown (`<= 0`) durations --- the grid hides the
     * overlay for the same sentinel.
     */
    fun duration(durationMs: Long, isVideo: Boolean): String? =
        if (!isVideo || durationMs <= 0) null else formatDurationMs(durationMs)

    /**
     * Human-readable byte size via the recycle bin's [DataSizeFormats], or
     * `null` when the lookup produced none (row gone / column missing).
     */
    fun size(sizeBytes: Long?): String? =
        sizeBytes?.takeIf { it > 0 }?.let(DataSizeFormats::formatBytes)

    /**
     * The `DATE_ADDED` timestamp (MediaStore seconds) as a
     * medium-date/short-time string via `java.text.DateFormat` --- the
     * standard locale-aware formatter, matching how the platform's own
     * detail sheets present media dates. Locale-dependent on purpose
     * (a date is localized prose, unlike a duration); [locale] is a
     * parameter so the JVM test pins the exact output under `Locale.US`.
     * `null` for unknown (`<= 0`) timestamps.
     */
    fun dateAdded(dateAddedSec: Long, locale: Locale): String? {
        if (dateAddedSec <= 0) return null
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
        return format.format(dateAddedSec * 1000L)
    }
}

/**
 * The optional provider lookup behind the info sheet (issue #63): the
 * current `DISPLAY_NAME` and `SIZE` of [uri]. Both fields `null` when the
 * row is gone or invisible to this app (deleted, trashed, hidden) --- the
 * sheet then shows what it can from the item alone.
 */
data class ViewerInfoLookup(
    val displayName: String?,
    val sizeBytes: Long?,
)

/**
 * One row query for [ViewerInfoLookup]; never throws for expected
 * failures (a missing row is a valid state, not an error).
 */
suspend fun queryViewerInfoLookup(resolver: ContentResolver, uri: Uri): ViewerInfoLookup =
    withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use ViewerInfoLookup(null, null)
            ViewerInfoLookup(
                displayName = cursor.getString(0),
                sizeBytes = if (cursor.isNull(1)) null else cursor.getLong(1),
            )
        } ?: ViewerInfoLookup(null, null)
    }
