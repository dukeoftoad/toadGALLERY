package io.github.dukeoftoad.gallery.data.vault

import java.util.concurrent.atomic.AtomicLong

/**
 * The pure, JVM-tested policy half of vault thumbnails (issue #58): the
 * downsample computation and the temporary-file naming/lifecycle rules that
 * [VaultThumbnailDecoder] (the thin Android half) executes. Keeping the
 * arithmetic here means the exact halving contract and the plaintext-temp
 * policy are pinned by `VaultThumbnailPolicyTest` without any Android
 * machinery (ADR-0016).
 *
 * The issue #69 race guard also lives here (see [VaultLockGeneration] and
 * [shouldPublishThumbnail]): `VaultViewModel.ensureThumbnails` checks the
 * unlocked state BEFORE each decode, but a decode is a suspend call that
 * spends real time in file I/O + cipher + BitmapFactory --- a lock /
 * background event (ADR-0017 auto-lock) can land in the middle. The batch
 * captures the lock-session generation at launch and re-checks it and the
 * unlocked state IMMEDIATELY before publishing each bitmap; a bitmap decoded
 * under generation G may only enter memory while the session still is G and
 * still unlocked.
 *
 * Privacy (ADR-0010/ADR-0014): thumbnails are decoded from DECRYPTED vault
 * bytes in memory only (issue #58 hard rule --- no plaintext vault media ever
 * persists on disk; the one unavoidable exception, a video temp file inside
 * app-private `cacheDir` for `MediaMetadataRetriever`, follows the naming
 * policy below and is deleted in a `finally` immediately after the frame
 * extraction). The app holds no INTERNET permission, so nothing can leave.
 */
object VaultThumbnailPolicy {

    /** Decoded thumbnail edge bound; rows render at 48dp, so 256px is ample. */
    const val TARGET_EDGE_PX = 256

    /** Temporary plaintext video file prefix inside `cacheDir` (never external). */
    const val TEMP_PREFIX = "vault-thumb-"

    /** Temporary plaintext video file suffix; also the stale-sweep matcher. */
    const val TEMP_SUFFIX = ".tmp"

    /**
     * The largest power-of-two [BitmapFactory] `inSampleSize` that keeps the
     * decoded image at least [targetEdge] on BOTH edges (the classic
     * BitmapFactory docs halving loop, guarded): decoding a 4000x3000 photo
     * for a 256px thumbnail loads ~1/256 of the pixels instead of the whole
     * 48 MP ARGB buffer.
     *
     * Degenerate inputs (missing dimensions, non-positive target) return 1
     * --- the caller then attempts an honest full decode and catches the
     * failure per item (issue #58: honest degradation, never a crash).
     */
    fun inSampleSize(width: Int, height: Int, targetEdge: Int = TARGET_EDGE_PX): Int {
        if (width <= 0 || height <= 0 || targetEdge <= 0) return 1
        if (width <= targetEdge && height <= targetEdge) return 1
        val halfWidth = width / 2
        val halfHeight = height / 2
        var sample = 1
        while (halfWidth / sample >= targetEdge && halfHeight / sample >= targetEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * The temp-file name for entry [entryId]'s video frame extraction:
     * `vault-thumb-<id>.tmp` inside app-private `cacheDir`. One stable name
     * per entry id keeps concurrent decodes collision-free (an entry decodes
     * at most once per unlock), and the fixed prefix/suffix pair is what
     * [cleanStaleTempFiles] matches to sweep crash leftovers.
     */
    fun tempFileName(entryId: Long): String = "$TEMP_PREFIX$entryId$TEMP_SUFFIX"

    /**
     * True when [name] matches the temp-file policy shape (the stale-sweep
     * test): the exact prefix, a non-empty decimal entry id, and the exact
     * suffix. Anything else in `cacheDir` is not ours to judge --- the same
     * rule as `VaultStore.sweepOrphans`.
     */
    fun isTempFileName(name: String): Boolean {
        if (!name.startsWith(TEMP_PREFIX) || !name.endsWith(TEMP_SUFFIX)) return false
        val id = name.removePrefix(TEMP_PREFIX).removeSuffix(TEMP_SUFFIX)
        if (id.isEmpty() || id.any { it !in '0'..'9' }) return false
        return id.toLongOrNull()?.let { it > 0 } == true
    }

    /**
     * Issue #69: whether a decoded bitmap may be published into the
     * in-memory thumbnail map. True ONLY when BOTH hold:
     *  - [capturedGeneration] equals [currentGeneration] --- no lock/unlock
     *    session boundary was crossed while the decode was suspended;
     *  - [isUnlocked] --- the session is still unlocked right now.
     *
     * This is the immediate post-decode re-check the race demanded: the
     * pre-decode guard alone cannot help, because the auto-lock
     * (`VaultViewModel.onBackgrounded`) clears the map and can land in the
     * middle of `repository.decodeThumbnail`, after which the still-running
     * batch would otherwise repopulate plaintext pixels into locked-session
     * memory.
     */
    fun shouldPublishThumbnail(
        capturedGeneration: Long,
        currentGeneration: Long,
        isUnlocked: Boolean,
    ): Boolean = capturedGeneration == currentGeneration && isUnlocked
}

/**
 * The monotonic lock-session generation (issue #69): advances at EVERY
 * boundary crossing of the vault's lock/unlock session line --- entering the
 * unlocked session, and leaving it (auto-lock/background). A thumbnail batch
 * captures [current] at launch and must re-validate against it immediately
 * before publishing each bitmap (see [VaultThumbnailPolicy.shouldPublishThumbnail]).
 *
 * Pure JVM (a bare [AtomicLong]) so the token semantics are pinned by
 * `VaultLockGenerationTest` without Android machinery; the ViewModel keeps a
 * single instance.
 */
class VaultLockGeneration {
    private val counter = AtomicLong(0L)

    /** The generation a batch launched under. */
    fun current(): Long = counter.get()

    /**
     * Bumps and returns the fresh generation; call at each session
     * boundary (unlock success and lock alike). Monotonic: a stale capture
     * can never match again, even after unlock-unlock-lock-unlock sequences.
     */
    fun advance(): Long = counter.incrementAndGet()
}
