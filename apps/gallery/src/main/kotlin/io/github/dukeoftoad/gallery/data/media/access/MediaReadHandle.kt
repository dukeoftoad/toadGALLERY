package io.github.dukeoftoad.gallery.data.media.access

/**
 * One bounded read of an original media item's bytes (Phase 4 contract,
 * `docs/phase-4/phase-4-contract-audit-and-ente-ai-plan.md`, change 5).
 *
 * This is the audit's byte-oriented READ BOUNDARY between the gallery's
 * URI-first MediaStore layer and everything downstream (metadata parsers
 * now, the Ente-derived ML pipeline later): the AI side receives BYTES,
 * nothing else. Three properties make the boundary worth an interface:
 *
 *  - **No raw filesystem paths cross this line.** A [MediaReadHandle] is
 *    obtained only from a [MediaAccess] that resolved an
 *    `EligiblePublicMedia`'s content Uri; callers never see a `File`, a
 *    canonical path or a file descriptor, so nothing downstream can
 *    re-derive a path, follow a symlink out of the vault, or persist a
 *    path that renames out from under the index (ADR-0006 URI-first).
 *  - **No full-file buffering beyond the cap.** [MediaReadHandle.readBytes]
 *    buffers at most its `maxBytes` argument; an item with MORE bytes
 *    than the cap fails with `null` instead of being loaded to "find
 *    out". The default ([MediaReadHandle.DEFAULT_MAX_BYTES], 64 MiB) is
 *    deliberately generous --- the AI pipeline needs FULL-RESOLUTION
 *    stills (face detail dies in thumbnails), and 64 MiB already exceeds
 *    every sane still image, including large RAW exports. The cap exists
 *    purely as a memory guard against pathological blobs (a mislabeled
 *    video, a multi-gigabyte TIFF), not as a quality knob.
 *  - **Cancellation is the caller's coroutine scope.** Reads are
 *    `suspend`; cancelling the indexing scope aborts a read in progress
 *    between stream chunks. There is no watchdog thread and no timeout
 *    parameter --- the WorkManager-owned coordinator (adaptation rule 5)
 *    already owns both.
 *
 * **Permission re-check (gate 2 of 3).** The audit's three-gate rule
 * (enqueue / OPEN / pre-persist) requires eligibility and media
 * permission to be re-verified immediately before bytes flow. That check
 * belongs to the OPENER --- see [MediaAccess.open] and
 * [MediaPermissionGate] --- not to this interface: a handle is inert
 * until read, and the OS re-enforces access at stream-open time
 * regardless (a `SecurityException` still collapses to `null` on read).
 *
 * `null` from a read means "no bytes, do not retry blindly": IO failure,
 * revoked permission, or item-larger-than-cap. The caller records the
 * reason class and moves on; one unreadable item never aborts a batch
 * (the same honest-degradation contract as the vault thumbnail decoder).
 *
 * Bytes (not descriptors) were chosen for v1 because the Ente bridge
 * (reuse map, row "Kotlin host integration") takes byte input; if memory
 * profiling later demands descriptor-passing, this interface --- not the
 * callers --- is the seam that changes.
 */
interface MediaReadHandle {

    /**
     * One bounded read of the original bytes; `null` on IO/permission
     * failure or if the item exceeds [maxBytes]. Implementations must not
     * buffer more than [maxBytes] plus one chunk, must honour coroutine
     * cancellation, and must never write plaintext temp files (the audit
     * forbids them outright --- a spilled original is a privacy bug, not
     * a perf trade).
     */
    suspend fun readBytes(maxBytes: Long = DEFAULT_MAX_BYTES): ByteArray?

    companion object {
        /**
         * Default read cap: 64 MiB. AI needs full-resolution stills, and
         * the largest legitimate still images (50 MP JPEGs, exported
         * RAWs) stay comfortably below this; anything above is a
         * mislabeled video or a pathological container that the pipeline
         * must refuse rather than buffer. 64 MiB is therefore a MEMORY
         * GUARD, not a format judgement --- the eligibility/format matrix
         * (contract change 7) owns format policy.
         */
        const val DEFAULT_MAX_BYTES: Long = 64L * 1024 * 1024
    }
}
