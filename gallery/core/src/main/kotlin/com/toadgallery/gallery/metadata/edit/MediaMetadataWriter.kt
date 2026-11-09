package com.toadgallery.gallery.metadata.edit

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.toadgallery.gallery.data.diag.DiagLog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The thin Android WRITE shell of the metadata edit round (Phase 4
 * still-open item: "Writes go back to the file via
 * `androidx.exifinterface`, not to a sidecar database" --- roadmap.md).
 *
 * Every decision lives in the pure, JVM-tested
 * `metadata/edit` siblings (capability gate, input codec, grant machine,
 * outcome classification --- ADR-0016); this class is straight provider
 * and library plumbing and carries the same no-unit-test exemption as
 * `MediaFileOps`/`ContentResolverMediaAccess`. Behaviour, all VERIFIED
 * against the pinned androidx.exifinterface **1.4.1** sources
 * (https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface,
 * sources artifact:
 * https://maven.google.com/androidx/exifinterface/exifinterface/1.4.1/exifinterface-1.4.1-sources.jar)
 * and the storage docs
 * (https://developer.android.com/training/data-storage/shared/media):
 *
 *  - **Open "rw", never "rwt"**: `ContentResolver.openFileDescriptor(uri,
 *    "rw")` is the documented read-and-write open; "rwt" TRUNCATES, which
 *    would destroy the file before the EXIF rewrite --- the docs call
 *    the difference out explicitly.
 *  - **FileDescriptor-backed mutation works**: the
 *    `ExifInterface(FileDescriptor)` constructor supports attribute
 *    mutation "for writable and seekable file descriptors only" (it
 *    dups a seekable fd and keeps the original for saving), and
 *    `saveAttributes()` rewrites the file IN PLACE through that fd when
 *    the instance is fd-backed (it `Os.lseek`s to 0 and streams the
 *    rebuilt image back through the same descriptor). A MediaStore "rw"
 *    fd is seekable.
 *  - **saveAttributes supports JPEG, PNG, WebP ONLY** (verified class
 *    doc + the thrown IOException message); this shell re-checks the
 *    format through [ExifWriteFormats] before touching the file, so the
 *    library's format refusal can only surface as the defensive
 *    FORMAT_REFUSED outcome, never a crash.
 *  - **Null clears a tag**: `setAttribute(tag, null)` removes the
 *    attribute (verified), which is how "clear this field" is expressed.
 *  - **No sidecar, no app-written temp file**: the metadata lands in the
 *    ORIGINAL item's bytes. (androidx itself rebuilds the image through
 *    an internal private temp file inside `saveAttributes` and deletes
 *    it again --- that is the library's documented in-place mechanics,
 *    disclosed here for honesty; this app never writes a sidecar or
 *    persists any copy.)
 *  - **Never crashes, expected or not**: every open/parse/save failure
 *    maps to a [RawWriteOutcome]; the provider's ownership refusal
 *    arrives as `SecurityException` (which covers
 *    `RecoverableSecurityException`, its superclass) and surfaces as
 *    SECURITY for the pure flow to classify. Beyond the expected set,
 *    each method ends in a TERMINAL catch that classifies an unexpected
 *    provider/runtime failure (a wrapped parcel or SQLite error out of
 *    the resolver, a library bug) as the IO-kind outcome and WARN-logs
 *    the exception class --- because the shell's contract is
 *    "classifies, never throws" and `DetailsViewModel`'s launch calls
 *    [write]/[readCurrentValues] with no backstop of its own. The catch
 *    set stays honest: unexpected failures are NOT re-labelled as any
 *    expected kind, they land in the IO bucket as "could not complete
 *    the file operation", which is true of every one of them.
 *    Structured cancellation is the one thing that still propagates
 *    (a cancelled coroutine is not a write failure).
 *
 * POST-WRITE INTERPLAY (the r12/r13 eventual-consistency contract): a
 * successful write changes the item's bytes and therefore its
 * MediaStore revision; the scan pipeline's ContentObserver +
 * `CatalogueReconciler` pick the change up on their next pass and
 * re-index the item (dates re-resolved, metadata cache refreshed) with
 * no action from this class. The Details view additionally re-reads the
 * file immediately after a successful write (its own cache invalidation,
 * in `DetailsViewModel`), so the user sees the edit now while the
 * catalogue catches up on the scan's schedule --- the same
 * eventual-consistency split the file-ops round documents in
 * `docs/architecture/file-ops.md`.
 *
 * Privacy (ADR-0014): local provider I/O only, no network permission
 * exists in the app, no byte can leave the device.
 */
class MediaMetadataWriter(private val resolver: ContentResolver) {

    /**
     * Reads the CURRENT values of the editable tags for [uri] (the
     * editor's prefill), through the same androidx reader the write will
     * go through. `null` when the item cannot be opened or parsed
     * (vanished, revoked, corrupt), and for the terminal-catch family
     * too --- an unexpected provider/runtime failure reads as the same
     * honest "current value unreadable" rather than a crash (see the
     * class KDoc's never-throws bullet); a blank-but-open file yields a
     * values object whose fields are all `null` ("not set").
     *
     * Read mode only ("r"): the read grants the app already holds cover
     * this; the WRITE gate is [write]'s "rw" open. On API 29+ without
     * the ACCESS_MEDIA_LOCATION grant the platform redacts EXIF
     * location, so the GPS fields come back absent here --- the consent
     * gate (r14's MediaLocationAccess) refuses GPS editing in that
     * state anyway, and the two honest behaviours agree.
     */
    suspend fun readCurrentValues(uri: Uri): EditableExifCurrentValues? =
        withContext(Dispatchers.IO) {
            try {
                resolver.openFileDescriptor(uri, MODE_READ)?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    val tags = HashMap<String, String>()
                    for (tag in EditableExifTags.READ_TAGS) {
                        exif.getAttribute(tag)?.let { tags[tag] = it }
                    }
                    EditableExifCodec.assembleCurrentValues(tags)
                }
            } catch (e: SecurityException) {
                DiagLog.i(TAG, "read: ${e.javaClass.name} -> null")
                null
            } catch (e: IOException) {
                DiagLog.i(TAG, "read: ${e.javaClass.name} -> null")
                null
            } catch (ce: CancellationException) {
                // A cancelled coroutine is not a read failure.
                throw ce
            } catch (e: Exception) {
                // Terminal backstop (class KDoc): unexpected
                // provider/runtime failures read as the same honest
                // unreadable outcome instead of crashing the caller.
                DiagLog.w(TAG, "read: ${e.javaClass.name} -> null")
                null
            }
        }

    /**
     * Applies [writes] to the original file behind [uri] (item of
     * [mimeType], already capability-gated by the caller): opens "rw",
     * sets each attribute (null value removes the tag), saves in place,
     * and reports the RAW outcome for the pure
     * [MetadataWriteFlow.classify] to turn into the UI's honest state.
     * Never throws --- for the expected failure set, and for the
     * terminal-catch family of unexpected provider/runtime failures,
     * which classify as [RawWriteOutcome.IO] (WARN-logged class name)
     * because "could not complete the file operation" is true of every
     * one of them (see the class KDoc).
     */
    suspend fun write(
        uri: Uri,
        mimeType: String,
        writes: List<ExifTagWrite>,
    ): RawWriteOutcome = withContext(Dispatchers.IO) {
        // Defense in depth: the capability gate already refused
        // non-writable formats; this re-check keeps the shell honest
        // even if a future caller skips it (mislabeled MediaStore rows
        // included --- the provider's MIME is a claim, the library's
        // saveAttributes refusal is the fact, and this check keeps the
        // two from ever meeting on the file).
        if (!ExifWriteFormats.isWritable(mimeType)) {
            DiagLog.i(TAG, "write: format not in pinned write set -> FORMAT_REFUSED")
            return@withContext RawWriteOutcome.FORMAT_REFUSED
        }
        try {
            resolver.openFileDescriptor(uri, MODE_READ_WRITE)?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                for (write in writes) {
                    exif.setAttribute(write.tag, write.value)
                }
                exif.saveAttributes()
                RawWriteOutcome.SUCCESS
            } ?: run {
                // openFileDescriptor returned null without throwing: an
                // unresolvable Uri (vanished row included).
                DiagLog.i(TAG, "write: no descriptor -> IO")
                RawWriteOutcome.IO
            }
        } catch (e: SecurityException) {
            // Covers RecoverableSecurityException (its superclass):
            // another app's item, consent required --- the raw kind the
            // flow classifier escalates on API 30+.
            DiagLog.i(TAG, "write: ${e.javaClass.name} -> SECURITY")
            RawWriteOutcome.SECURITY
        } catch (e: IOException) {
            DiagLog.i(TAG, "write: ${e.javaClass.name} -> IO")
            RawWriteOutcome.IO
        } catch (ce: CancellationException) {
            // A cancelled coroutine is not a write failure.
            throw ce
        } catch (e: Exception) {
            // Terminal backstop (class KDoc): the caller's launch has no
            // catch of its own, so an unexpected provider/runtime
            // failure classifies as the IO-kind outcome --- WARN, class
            // name only, per the repo's diagnostics convention.
            DiagLog.w(TAG, "write: ${e.javaClass.name} -> IO")
            RawWriteOutcome.IO
        }
    }

    private companion object {
        /** Diagnostics tag for this Android seam (W1 style). */
        const val TAG = "ToadMetaWrite"

        /** Read-only open mode for the prefill read. */
        const val MODE_READ = "r"

        /** Read-write open mode; NOT "rwt", which truncates the file. */
        const val MODE_READ_WRITE = "rw"
    }
}
