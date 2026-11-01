package io.github.dukeoftoad.gallery.data.media.access

import android.content.ContentResolver
import android.net.Uri
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Production [MediaAccess] over the Android `ContentResolver` (Phase 4
 * contract change 5): resolves an eligible item's canonical content Uri,
 * re-checks media permission (gate 2 of 3), and reads the ORIGINAL bytes
 * bounded by the caller's cap --- never a filesystem path, never a
 * plaintext temp file.
 *
 * Thin and NOT unit-tested, exactly like `AndroidVaultKeys` (ADR-0016):
 * every branch here is ContentResolver plumbing --- open stream, catch
 * the two exception classes the platform documents, count bytes --- with
 * no logic worth a JVM test; Robolectric has no real MediaStore, and the
 * CONTRACT (gate discipline, cap semantics, vault unrepresentability) is
 * pinned by `MediaAccessContractTest` against fakes. Behavioural notes:
 *
 *  - **Never writes plaintext temp files.** The audit forbids them: an
 *    original spilled to cache is a privacy bug. Bytes live in this
 *    process's memory only, bounded by `maxBytes`, and the app holds no
 *    INTERNET permission (ADR-0014) so nothing can leave the device.
 *  - **Oversized items are refused, not truncated.** If the stream holds
 *    even one byte MORE than the cap the read returns `null`: a still
 *    image beyond 64 MiB (the default cap) is not a sane input for the
 *    AI pipeline --- it is a mislabeled video or a pathological
 *    container, and half of it would be worse than none.
 *  - **Cancellation** between stream chunks via the caller's coroutine
 *    scope (adaptation rule 5); a WorkManager-owned read that dies
 *    mid-stream simply never resumes.
 *  - **Diagnostics** (W1 style): every `null` outcome leaves one INFO
 *    line in [DiagLog] under "ToadMedia" --- outcome class and exception
 *    class name only, never URIs (they embed display names) or bytes.
 */
class ContentResolverMediaAccess(
    private val resolver: ContentResolver,
    private val permissionGate: MediaPermissionGate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaAccess {

    override suspend fun open(media: EligiblePublicMedia): MediaReadHandle? {
        val uriText: String = media.identity.canonicalUri
        if (uriText.isBlank()) {
            // A key-parsed identity carries no access handle by design
            // (MediaIdentity.fromKeyString) --- the caller must re-resolve
            // the Uri from the catalogue before reading.
            DiagLog.i(TAG, "open: blank canonical uri -> null")
            return null
        }
        if (!permissionGate.stillHasAccess(media)) {
            // Gate 2 of 3 (enqueue / open / pre-persist): the grant that
            // was valid at enqueue time may be gone by now.
            DiagLog.i(TAG, "open: permission gate refused at read time -> null")
            return null
        }
        return ResolverReadHandle(resolver, Uri.parse(uriText), ioDispatcher)
    }

    /**
     * Lazy handle: holds only the resolved Uri and dispatcher --- no open
     * stream --- so a queued item that is never read costs nothing.
     */
    private class ResolverReadHandle(
        private val resolver: ContentResolver,
        private val uri: Uri,
        private val ioDispatcher: CoroutineDispatcher,
    ) : MediaReadHandle {

        override suspend fun readBytes(maxBytes: Long): ByteArray? {
            if (maxBytes <= 0L) return null
            return withContext(ioDispatcher) {
                val input = try {
                    resolver.openInputStream(uri)
                } catch (e: SecurityException) {
                    // Revoked between gate and stream-open; the OS is the
                    // final authority even behind our own gate.
                    DiagLog.i(TAG, "read: ${e.javaClass.name} at open -> null")
                    null
                } catch (e: IOException) {
                    // FileNotFoundException included: a vanished row is the
                    // NORMAL way a removable volume disappears mid-batch.
                    DiagLog.i(TAG, "read: ${e.javaClass.name} at open -> null")
                    null
                }
                if (input == null) {
                    // openInputStream also returns null (no exception) for
                    // an unresolvable Uri.
                    DiagLog.i(TAG, "read: no stream -> null")
                    return@withContext null
                }
                input.use { stream ->
                    try {
                        val out = ByteArrayOutputStream(initialCapacityBytes(maxBytes))
                        val chunk = ByteArray(CHUNK_BYTES)
                        while (true) {
                            // Cancellation point between chunks: a cancelled
                            // indexing scope aborts without a watchdog.
                            coroutineContext.ensureActive()
                            val read: Int = stream.read(chunk)
                            if (read < 0) break
                            if (out.size().toLong() + read > maxBytes) {
                                // More bytes than the cap --- refuse the
                                // whole item rather than buffer gigabytes.
                                DiagLog.i(TAG, "read: exceeds cap -> null")
                                return@withContext null
                            }
                            out.write(chunk, 0, read)
                        }
                        out.toByteArray()
                    } catch (e: SecurityException) {
                        DiagLog.i(TAG, "read: ${e.javaClass.name} mid-stream -> null")
                        null
                    } catch (e: IOException) {
                        DiagLog.i(TAG, "read: ${e.javaClass.name} mid-stream -> null")
                        null
                    }
                }
            }
        }
    }

    private companion object {
        /** Diagnostics tag for this Android seam (W1 style, like ToadVault). */
        const val TAG = "ToadMedia"

        /** Stream chunk size: 64 KiB --- one syscall's worth per iteration. */
        const val CHUNK_BYTES: Int = 64 * 1024

        /**
         * ByteArrayOutputStream initial-capacity ceiling: pre-sizing to the
         * full cap (up to 64 MiB) would double peak memory for the common
         * small-image case; 1 MiB lets the stream grow into large items
         * without pre-committing.
         */
        const val INITIAL_CAPACITY_CEILING_BYTES: Long = 1024 * 1024

        fun initialCapacityBytes(maxBytes: Long): Int =
            minOf(maxBytes, INITIAL_CAPACITY_CEILING_BYTES)
                .coerceAtLeast(1L)
                .toInt()
    }
}
