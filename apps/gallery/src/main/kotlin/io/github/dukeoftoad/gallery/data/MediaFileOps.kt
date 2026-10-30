package io.github.dukeoftoad.gallery.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.app.RecoverableSecurityException
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Safe, non-destructive file operations on media items (issue #30).
 *
 * Everything goes through [ContentResolver] on MediaStore URIs --- insert /
 * update / delete plus `openInputStream`/`openOutputStream` for byte
 * streaming. No filesystem path is ever read or written (ADR-0006: URIs are
 * the identity; `RELATIVE_PATH` is a MediaStore *column*, addressed through
 * the provider, not a raw path).
 *
 * ## API level contract: Android 10 (Q, API 29) and newer only
 * The whole class is `@RequiresApi(Q)` by design, and the file-ops UI is
 * hidden below Q. Three independent reasons:
 *  1. **Consent protocol**: modifying/deleting media this app did not
 *     contribute throws [RecoverableSecurityException] (Q+); the recovery
 *     requests ([createWriteConsentRequest] / [createDeleteConsentRequest],
 *     both Q+) are the ONLY sanctioned path around it. Below 29 the app
 *     holds only read permissions and the OS would refuse every write
 *     unrecoverably.
 *  2. **Scoped storage inserts**: a copy inserts its new row with
 *     `IS_PENDING` + optional `RELATIVE_PATH`, both Q+ columns.
 *  3. **Media contribution**: on Q+ an app needs NO storage permission to
 *     insert its own media --- which is exactly why [copy] never needs
 *     consent while rename/delete/move do.
 *
 * ## Result model
 * Operations never throw for expected failures; they return [FileOpResult]
 * whose [FileOpOutcome] is the pure kind (`Success` / `NeedsConsent` /
 * `Partial` / `Failure` with [FileOpFailureReason]). Two Android anchors
 * ride along: [FileOpResult.createdUri] (the new copy, for undo and for
 * move's partial semantics --- "never lose the copy") and
 * [FileOpResult.sourceAlbum] (where a moved item came from, for undo).
 * [NeedsConsent] never carries the `IntentSender` itself: the caller
 * aggregates the affected URIs, asks [createWriteConsentRequest] or
 * [createDeleteConsentRequest] once for the whole batch, and retries the op
 * after the user grants.
 *
 * ## Testability
 * [ContentResolver] is a constructor parameter (not a `Context`), and all
 * decision logic (names, validation, aggregation) lives in the pure,
 * JVM-tested `FileOpNaming.kt`; this class stays a thin, provider-talking
 * shell around it.
 *
 * Privacy (ADR-0014): everything here is local provider I/O --- no network
 * permission exists in the app, so no byte can leave the device.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaFileOps(private val resolver: ContentResolver) {

    // ------------------------------------------------------------------
    // Single-item operations (each hops to Dispatchers.IO internally)
    // ------------------------------------------------------------------

    /**
     * Renames [item] to `newName`.
     *
     * The input is validated/sanitized by [checkRenameInput], then the final
     * `DISPLAY_NAME` is composed by [renameDisplayName]: the typed extension
     * is stripped and the original restored, so the item's type never
     * changes. Writes exactly one `DISPLAY_NAME` value via
     * `ContentResolver.update` on the item URI.
     *
     * - Empty names / path separators -> [FileOpFailureReason.INVALID_NAME].
     * - Row gone -> [FileOpFailureReason.NOT_FOUND].
     * - Another app's item -> [FileOpOutcome.NeedsConsent] (the caller
     *   launches a write-consent request and retries).
     */
    suspend fun rename(item: MediaItem, newName: String): FileOpResult = rename(item.uri, newName)

    /** Uri-keyed core of [rename]; also used by rename-undo. */
    suspend fun rename(uri: Uri, newName: String): FileOpResult = withContext(Dispatchers.IO) {
        val check = checkRenameInput(newName)
        if (check !is RenameCheck.Valid) {
            return@withContext FileOpResult(
                FileOpOutcome.Failure(FileOpFailureReason.INVALID_NAME),
                detail = "rejected by checkRenameInput",
            )
        }
        val current = displayNameOf(uri)
            ?: return@withContext FileOpResult(
                FileOpOutcome.Failure(FileOpFailureReason.NOT_FOUND),
            )
        val targetName = renameDisplayName(current, check.baseName)
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, targetName)
            }
            val updated = resolver.update(uri, values, null, null)
            if (updated > 0) {
                FileOpResult(FileOpOutcome.Success)
            } else {
                FileOpResult(FileOpOutcome.Failure(FileOpFailureReason.NOT_FOUND))
            }
        } catch (e: IllegalArgumentException) {
            // The provider is the final authority on illegal names.
            FileOpResult(FileOpOutcome.Failure(FileOpFailureReason.INVALID_NAME), detail = e.message)
        } catch (e: RecoverableSecurityException) {
            FileOpResult(FileOpOutcome.NeedsConsent)
        } catch (e: SecurityException) {
            FileOpResult(FileOpOutcome.NeedsConsent)
        }
    }

    /**
     * Duplicates [item].
     *
     * With [targetAlbum] == null the copy lands **in place** (same folder as
     * the source) --- the "Copy" batch action of the selection bar. With a
     * target album the copy is inserted with that album's `RELATIVE_PATH`
     * (resolved from the bucket's own rows; used internally by [move]).
     *
     * Mechanics: one MediaStore insert (`DISPLAY_NAME` via [copyDisplayName],
     * source mimetype, `IS_PENDING = 1`, optional `RELATIVE_PATH`), then the
     * bytes stream `openInputStream(source)` -> `openOutputStream(copy)`,
     * then `IS_PENDING = 0` publishes the row. A mid-copy failure deletes
     * the app-owned pending row again so no half-written orphan leaks.
     *
     * Inserts of the app's own media need no consent on Q+, so this never
     * returns [FileOpOutcome.NeedsConsent].
     */
    suspend fun copy(item: MediaItem, targetAlbum: AlbumRef? = null): FileOpResult =
        copy(item.uri, targetAlbum)

    /** Uri-keyed core of [copy]; also used by move (and by move-undo). */
    suspend fun copy(uri: Uri, targetAlbum: AlbumRef? = null): FileOpResult = withContext(Dispatchers.IO) {
        val source = querySourceDetails(uri)
            ?: return@withContext FileOpResult(FileOpOutcome.Failure(FileOpFailureReason.NOT_FOUND))
        val relativePath = if (targetAlbum != null) {
            resolveAlbumRelativePath(targetAlbum.bucketId)
                ?: return@withContext FileOpResult(
                    FileOpOutcome.Failure(FileOpFailureReason.PROVIDER),
                    detail = "target album has no rows to take a RELATIVE_PATH from",
                )
        } else {
            // In-place duplicate: keep the source's folder.
            source.relativePath
        }
        val displayName = copyDisplayName(source.displayName ?: FALLBACK_DISPLAY_NAME) { candidate ->
            displayNameTaken(candidate, relativePath)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            // A concrete mimetype (HEIC rows can carry none); wildcards are
            // the pipeline's fallback and are NOT valid insert values, so
            // the provider infers the type from the preserved extension.
            source.mimeType?.takeIf { !it.contains('*') }?.let {
                put(MediaStore.MediaColumns.MIME_TYPE, it)
            }
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            relativePath?.let { put(MediaStore.MediaColumns.RELATIVE_PATH, it) }
        }
        val newUri = try {
            resolver.insert(insertCollectionFor(uri), values)
                ?: return@withContext FileOpResult(
                    FileOpOutcome.Failure(FileOpFailureReason.INSERT_FAILED),
                )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withContext FileOpResult(
                FileOpOutcome.Failure(FileOpFailureReason.INSERT_FAILED),
                detail = e.message,
            )
        }
        try {
            copyBytes(uri, newUri)
            val settled = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(newUri, settled, null, null)
            FileOpResult(FileOpOutcome.Success, createdUri = newUri)
        } catch (e: IOException) {
            // Never leak a half-written pending row (it is app-owned, so the
            // cleanup delete needs no consent).
            runCatching { resolver.delete(newUri, null, null) }
            FileOpResult(FileOpOutcome.Failure(FileOpFailureReason.IO), detail = e.message)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            runCatching { resolver.delete(newUri, null, null) }
            FileOpResult(FileOpOutcome.Failure(FileOpFailureReason.IO), detail = e.message)
        }
    }

    /**
     * Moves [item] into [targetAlbum]: **copy, then delete the original**,
     * strictly sequential per item (issue #30's non-destructive ordering).
     *
     * - Same-album move: succeeds as a no-op (no copy created, no undo
     *   entry --- `createdUri == null` tells the caller so).
     * - Copy succeeded but the original could not be removed:
     *   [FileOpOutcome.Partial] with the delete-phase reason in
     *   [FileOpOutcome.Partial.deleteReason] --- **the copy is kept** (never
     *   lost), and [FileOpResult.sourceAlbum] still carries where the item
     *   came from so the caller can offer undo or a consent retry.
     *   `SECURITY` as the reason means the caller should run one
     *   delete-consent round-trip and then delete the original only (the
     *   copy must not be duplicated).
     * - The original vanishing mid-move (another app deleted it) still
     *   counts as success: the item is where the user wanted it.
     */
    suspend fun move(item: MediaItem, targetAlbum: AlbumRef): FileOpResult =
        move(item.uri, targetAlbum)

    /** Uri-keyed core of [move]; move-undo ("move back") reuses it. */
    suspend fun move(uri: Uri, targetAlbum: AlbumRef): FileOpResult = withContext(Dispatchers.IO) {
        val source = querySourceDetails(uri)
            ?: return@withContext FileOpResult(FileOpOutcome.Failure(FileOpFailureReason.NOT_FOUND))
        if (source.bucketId != null && source.bucketId == targetAlbum.bucketId) {
            // Already there: a copy+delete round-trip would only churn.
            return@withContext FileOpResult(FileOpOutcome.Success)
        }
        val sourceAlbum = source.bucketId?.let { id ->
            AlbumRef(id, source.bucketName.orEmpty().ifEmpty { FALLBACK_ALBUM_NAME })
        }
        val copied = copy(uri, targetAlbum)
        val createdUri = copied.createdUri
            ?: return@withContext copied // copy failed -> move failed; nothing changed
        try {
            // removed > 0: the move completed. removed == 0: the original
            // vanished mid-move (another app deleted it) --- either way the
            // item is where the user wanted it, so both count as success.
            resolver.delete(uri, null, null)
            FileOpResult(FileOpOutcome.Success, createdUri = createdUri, sourceAlbum = sourceAlbum)
        } catch (e: RecoverableSecurityException) {
            FileOpResult(
                FileOpOutcome.Partial(FileOpFailureReason.SECURITY),
                createdUri = createdUri,
                sourceAlbum = sourceAlbum,
            )
        } catch (e: SecurityException) {
            FileOpResult(
                FileOpOutcome.Partial(FileOpFailureReason.SECURITY),
                createdUri = createdUri,
                sourceAlbum = sourceAlbum,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileOpResult(
                FileOpOutcome.Partial(FileOpFailureReason.IO),
                createdUri = createdUri,
                sourceAlbum = sourceAlbum,
                detail = e.message,
            )
        }
    }

    /**
     * Deletes [item] permanently (v1: no recycle bin yet).
     *
     * [DeleteMode.TO_TRASH] is refused loudly --- it arrives with the
     * recycle bin (issue #31), which will set `IS_TRASH = 1` here instead.
     * Until then no caller can silently trade trash semantics for permanent
     * deletion. Another app's item surfaces as [FileOpOutcome.NeedsConsent]
     * (one delete-consent request covers the whole batch; see class KDoc).
     */
    suspend fun delete(item: MediaItem, mode: DeleteMode = DeleteMode.PERMANENT): FileOpResult =
        delete(item.uri, mode)

    /** Uri-keyed core of [delete]; copy-undo deletes app-owned entries through it. */
    suspend fun delete(uri: Uri, mode: DeleteMode = DeleteMode.PERMANENT): FileOpResult =
        withContext(Dispatchers.IO) {
            check(mode == DeleteMode.PERMANENT) {
                "DeleteMode.TO_TRASH is not implemented yet --- it arrives with the recycle bin (#31)."
            }
            try {
                val removed = resolver.delete(uri, null, null)
                if (removed > 0) {
                    FileOpResult(FileOpOutcome.Success)
                } else {
                    FileOpResult(FileOpOutcome.Failure(FileOpFailureReason.NOT_FOUND))
                }
            } catch (e: RecoverableSecurityException) {
                FileOpResult(FileOpOutcome.NeedsConsent)
            } catch (e: SecurityException) {
                FileOpResult(FileOpOutcome.NeedsConsent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileOpResult(FileOpOutcome.Failure(FileOpFailureReason.IO), detail = e.message)
            }
        }

    // ------------------------------------------------------------------
    // Batch orchestration: (index, total) progress, per-item results
    // ------------------------------------------------------------------

    /**
     * Runs [operation] over [items] strictly sequentially, reporting
     * `(completed, total)` progress and per-item results as they land
     * ([onResult] receives the stable item index, so callers pair results
     * with items positionally even on cancellation).
     *
     * Progress fires `0/total` before the first item and `completed/total`
     * after each item --- "Moving 3 of 12…" means three are done. The loop
     * checks cancellation between items (`ensureActive`); per-item
     * operations are cancellable at their own dispatcher boundaries. A
     * cancelled run simply propagates the [CancellationException] with the
     * prefix already delivered through [onResult] --- the caller keeps the
     * honest partial state instead of a fake failure.
     */
    suspend fun <T> runBatch(
        items: List<T>,
        onResult: (index: Int, result: FileOpResult) -> Unit,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
        operation: suspend (item: T) -> FileOpResult,
    ) {
        onProgress(0, items.size)
        for ((index, item) in items.withIndex()) {
            coroutineContext.ensureActive()
            onResult(index, operation(item))
            onProgress(index + 1, items.size)
        }
    }

    // ------------------------------------------------------------------
    // Consent (recovery) request builders --- API 29+, IntentSender out
    // ------------------------------------------------------------------

    /**
     * Builds one system "modify these items?" request covering [uris]
     * (`MediaStore.createWriteRequest`); the UI launches the returned
     * [android.content.IntentSender] via
     * `StartIntentSenderForResult` and retries the op on `RESULT_OK`.
     */
    fun createWriteConsentRequest(uris: Collection<Uri>): android.content.IntentSender =
        MediaStore.createWriteRequest(resolver, uris).intentSender

    /**
     * Builds one system "delete these items?" request covering [uris]
     * (`MediaStore.createDeleteRequest`); see [createWriteConsentRequest]
     * for the launch/retry flow. One request per batch, one dialog per
     * request --- never one dialog per item.
     */
    fun createDeleteConsentRequest(uris: Collection<Uri>): android.content.IntentSender =
        MediaStore.createDeleteRequest(resolver, uris).intentSender

    // ------------------------------------------------------------------
    // Provider probes
    // ------------------------------------------------------------------

    /** Current `DISPLAY_NAME` of [uri], or null when the row is gone. */
    suspend fun displayNameOf(uri: Uri): String? = withContext(Dispatchers.IO) {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    /** Row details a copy/move needs; null when the source row is gone. */
    private data class SourceDetails(
        val displayName: String?,
        val mimeType: String?,
        val relativePath: String?,
        val bucketId: Long?,
        val bucketName: String?,
    )

    private fun querySourceDetails(uri: Uri): SourceDetails? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            SourceDetails(
                displayName = cursor.getString(0),
                mimeType = cursor.getString(1),
                relativePath = cursor.getString(2),
                bucketId = if (cursor.isNull(3)) null else cursor.getLong(3),
                bucketName = cursor.getString(4),
            )
        }
    }

    /** `RELATIVE_PATH` of one row in [bucketId]'s bucket (null: no rows / no path). */
    private fun resolveAlbumRelativePath(bucketId: Long): String? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.BUCKET_ID} = ?",
            arrayOf(bucketId.toString()),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    /**
     * Whether [displayName] is already taken (case-insensitively at the
     * provider's discretion) in [relativePath]. The production `taken`
     * predicate behind [copyDisplayName]'s collision numbering. Pending and
     * trashed rows are excluded by MediaProvider's default query --- both
     * are invisible to other apps, which is what "taken" must mean.
     */
    private fun displayNameTaken(displayName: String, relativePath: String?): Boolean {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val selection = if (relativePath == null) {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        } else {
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        }
        val args = if (relativePath == null) {
            arrayOf(displayName)
        } else {
            arrayOf(displayName, relativePath)
        }
        return resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), selection, args, null)
            ?.use { it.moveToFirst() } == true
    }

    /**
     * Streams source bytes into the pending copy. Both streams come from the
     * resolver (URI-first, ADR-0006); `copyTo` closes nothing early --- the
     * `use` blocks flush the output before the pending flag is cleared.
     */
    private fun copyBytes(source: Uri, target: Uri) {
        resolver.openInputStream(source)?.use { input ->
            resolver.openOutputStream(target)?.use { output ->
                input.copyTo(output)
            } ?: throw IOException("provider offered no output stream for $target")
        } ?: throw IOException("provider offered no input stream for $source")
    }

    /**
     * Which per-type collection a copy of [source] belongs in: read
     * structurally from the content URI's own segments (`images` vs
     * `video`), never from a path. Falls back to Images, the only other
     * collection this app's timeline queries.
     */
    private fun insertCollectionFor(source: Uri): Uri =
        if (source.pathSegments.any { it.equals("video", ignoreCase = true) }) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private companion object {
        /** A row with a null DISPLAY_NAME (should not happen) duplicates as this. */
        const val FALLBACK_DISPLAY_NAME = "copy"

        /** A source bucket without a display name (same fallback as MediaStoreSource). */
        const val FALLBACK_ALBUM_NAME = "Album"
    }
}

/**
 * Result of one file operation on one item (issue #30): the pure
 * [FileOpOutcome] plus the Android anchors the caller needs for undo and
 * consent retries. [createdUri] is non-null for copy/move success and move
 * partials (the new entry --- also the undo anchor for "delete the copy" /
 * "move it back"); [sourceAlbum] is non-null for move success/partials
 * (where the item came from); [detail] is a log-only provider message.
 */
data class FileOpResult(
    val outcome: FileOpOutcome,
    val createdUri: Uri? = null,
    val sourceAlbum: AlbumRef? = null,
    val detail: String? = null,
)
