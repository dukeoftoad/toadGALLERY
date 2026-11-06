package io.github.dukeoftoad.gallery.ui.vault

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/**
 * The wrong-PIN unlock throttle (audit D-1, r51 --- the throttle-only half
 * of the fix; `PIN_ITERATIONS` and `MIN_PIN_LENGTH` are deliberately
 * untouched, per the maintainer's recorded decision gate).
 *
 * Before r51 [VaultViewModel.attemptUnlock] had NO attempt counter and NO
 * lockout: the only rate limiter was the PBKDF2 cost itself, so unlimited
 * wrong-PIN retries could grind a 4-digit space with nothing but ~0.1-0.3 s
 * of KDF per guess. This class adds the missing escalation: every
 * consecutive failed unlock grows a lockout delay --- 1st failure is free
 * (honest typo forgiveness), then 1 s, 2 s, 4 s, 8 s, 16 s, 32 s, and 64 s
 * capped at [CAP_MS] (60 s) --- and the delay counts down from the LAST
 * failure, so the ladder both grows with each new failure and decays as
 * time passes without one. A successful unlock resets the counter.
 *
 * ## Persistence (survives process death)
 * The `{failCount, lastFailureAtMs}` pair is persisted as a tiny JSON
 * state file using the repo's established atomic discipline (the
 * `ThingsStateFile` / `PeopleStateFile` pattern): write the `.tmp` sibling,
 * then `Files.move` with [StandardCopyOption.ATOMIC_MOVE], falling back to
 * a plain replacing move where the filesystem refuses atomic renames. A
 * crash mid-write therefore leaves either the old or the new complete
 * file, never a mix --- and the `.tmp` residue, if any, is reclaimed by
 * `VaultStore.sweepOrphans` (root-level `.tmp` sweep) or the vault
 * directory simply being wiped.
 *
 * The file lives in the vault's data directory next to the sealed PIN
 * record (`filesDir/vault/unlock-throttle.json` --- see
 * [VaultViewModel]'s path constant), so it inherits the backup /
 * device-transfer exclusions that keep vault state on the device, and the
 * store's sweep ignores it by name (root files that are neither `.bin`
 * nor `.tmp` are "not ours to judge"). A MISSING or CORRUPT file loads as
 * a fresh counter, never a crash --- unlike the user-authored state files
 * this file holds no data worth an honest-loss flag: it is a rate-limit
 * counter, and an attacker able to rewrite app-private storage has
 * already won the threat model this counter defends.
 *
 * ## Scope (honest)
 *  - Only the PIN path registers failures. Biometric unlock failures do
 *    NOT count: the BiometricPrompt/keystore path has its own device-bound
 *    rate limiting (attempt limits inside the prompt + the invalidated-key
 *    contract), and mixing the two would let a flaky fingerprint reader
 *    lock out the PIN the user then needs.
 *  - The persisted counter is best-effort: if the write fails (disk
 *    full), [registerFailure] returns false and the CALLER logs it, but
 *    the in-memory state still throttles the current process.
 *  - Not internally synchronized: the ViewModel calls every method from
 *    its single-flight unlock flow (the `_busy` gate already guarantees
 *    one unlock attempt at a time).
 *
 * Pure JVM (no `android.*` API) so the ladder, the decay, the reset and
 * the persistence discipline are pinned by `VaultUnlockThrottleTest`
 * without Android machinery (ADR-0016).
 *
 * Privacy (ADR-0014): the file holds two numbers --- a count and a
 * timestamp --- never PIN material. Like every diagnostics-relevant seam
 * here, nothing about the PIN (length, content, near-misses) is recorded.
 */
class VaultUnlockThrottle(
    /** The persisted state file, or null for an in-memory-only throttle (tests). */
    private val stateFile: File? = null,
) {

    /** Consecutive failed unlocks so far (the ladder's index). */
    private var failCount = 0

    /** [System.currentTimeMillis] of the most recent [registerFailure]. */
    private var lastFailureAtMs = 0L

    /**
     * Reads the persisted state. A missing file, a corrupt file, a
     * future schema version or nonsense values all land on the fresh
     * counter --- never a thrown exception into the unlock flow. Idempotent;
     * the ViewModel loads once, before the [VaultUiState.Locked] phase can
     * ever appear.
     */
    fun load() {
        val file = stateFile
        if (file == null || !file.isFile) {
            failCount = 0
            lastFailureAtMs = 0L
            return
        }
        try {
            val root = JSONObject(file.readText())
            if (root.optInt(VERSION_KEY) != SUPPORTED_VERSION) {
                // A future schema is not ours to interpret: fresh counter.
                failCount = 0
                lastFailureAtMs = 0L
                return
            }
            val count = root.optInt(FAIL_COUNT_KEY, 0).coerceIn(0, MAX_TRACKED_FAILURES)
            val lastAt = root.optLong(LAST_FAILURE_AT_KEY, 0L)
            failCount = count
            // A counter of zero carries no meaningful timestamp; a negative
            // or absurd one (hand-edited) is normalized by the decay math
            // (elapsed time grows without bound, remaining hits zero).
            lastFailureAtMs = if (count > 0) lastAt else 0L
        } catch (_: Exception) {
            // Corrupt / partially-written / hand-edited: the honest state is
            // "fresh counter" --- this file is a rate limiter, not user data
            // (see the class KDoc's persistence section).
            failCount = 0
            lastFailureAtMs = 0L
        }
    }

    /**
     * How much longer the vault must refuse to even attempt a PIN
     * verification, in milliseconds, as of [nowMs]. Zero when no lockout
     * is active. The delay decays as time passes since the last failure;
     * a clock running BACKWARDS fails safe (the full delay is kept ---
     * `System.currentTimeMillis` is not monotonic and a flaky clock must
     * never shorten a lockout).
     */
    fun remainingLockoutMs(nowMs: Long): Long {
        val delay = lockoutDelayMs(failCount)
        if (delay == 0L) return 0L
        val elapsed = nowMs - lastFailureAtMs
        if (elapsed < 0L) return delay
        return (delay - elapsed).coerceAtLeast(0L)
    }

    /**
     * Records one failed unlock at [nowMs]: grows the ladder and re-arms
     * the countdown from now. Returns whether the state was PERSISTED ---
     * false means the write failed and the backoff is memory-only for this
     * process (the caller WARN-logs; the session is still throttled).
     */
    fun registerFailure(nowMs: Long): Boolean {
        failCount = (failCount + 1).coerceAtMost(MAX_TRACKED_FAILURES)
        lastFailureAtMs = nowMs
        return persist()
    }

    /**
     * Records a successful unlock at [nowMs]: resets the ladder to zero
     * and persists the reset (a success followed by process death must not
     * resurrect a stale counter). [nowMs] is accepted for API symmetry
     * with [registerFailure] but deliberately unused --- a reset carries
     * no timestamp worth persisting. Returns whether the write persisted.
     */
    fun registerSuccess(nowMs: Long): Boolean {
        failCount = 0
        lastFailureAtMs = 0L
        return persist()
    }

    /**
     * Writes `{failCount, lastFailureAtMs}` through the atomic
     * temp-then-move discipline (see the class KDoc). Best-effort: any
     * I/O failure returns false, never throws into the unlock flow.
     */
    private fun persist(): Boolean {
        val file = stateFile ?: return true // in-memory throttle (tests)
        return try {
            val root = JSONObject()
                .put(VERSION_KEY, SUPPORTED_VERSION)
                .put(FAIL_COUNT_KEY, failCount)
                .put(LAST_FAILURE_AT_KEY, lastFailureAtMs)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
            tmp.writeText(root.toString())
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Layouts without atomic rename still get the durable
                // temp-write + replace ordering; only the atomicity window
                // widens (the same fallback as the ML state files).
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /**
         * The lockout delay after [consecutiveFailures] consecutive failed
         * unlocks: 0 for 0-1 failures, then 2^(n-2) seconds (1 s, 2 s, 4 s,
         * 8 s, 16 s, 32 s, 64 s...) capped at [CAP_MS] (60 s). The shift is
         * clamped before the `shl` so an absurd counter cannot overflow ---
         * everything from 8 failures up is the cap.
         */
        fun lockoutDelayMs(consecutiveFailures: Int): Long {
            if (consecutiveFailures < 2) return 0L
            val seconds = 1L shl (consecutiveFailures - 2).coerceAtMost(MAX_EXPONENT)
            return (seconds * 1000L).coerceAtMost(CAP_MS)
        }

        /**
         * The ladder's ceiling, in milliseconds: from the 8th consecutive
         * failure on, every further failure costs a full minute. A hard
         * cap is a deliberate UX bound --- the escalation must outlast an
         * online brute-force session without becoming an unbounded
         * denial-of-service against the legitimate owner (a cap of
         * minutes, not hours, keeps "I forgot which PIN variant I used"
         * recoverable by waiting, and the PIN_ITERATIONS decision-gate
         * handles the per-attempt cost).
         */
        const val CAP_MS = 60L * 1000L

        /**
         * The largest exponent [lockoutDelayMs] ever evaluates: 2^6 s =
         * 64 s already exceeds [CAP_MS], so the shift clamps here (the
         * overflow guard --- see the function KDoc).
         */
        private const val MAX_EXPONENT = 6

        /**
         * The upper bound on the persisted/tracked counter. The ladder
         * saturates at 8; tracking beyond this bound is meaningless
         * bookkeeping, and the clamp also normalizes a hand-edited absurd
         * value from the state file.
         */
        private const val MAX_TRACKED_FAILURES = 1000

        private const val VERSION_KEY = "version"
        private const val FAIL_COUNT_KEY = "failCount"
        private const val LAST_FAILURE_AT_KEY = "lastFailureAtMs"
        private const val SUPPORTED_VERSION = 1
        private const val TMP_SUFFIX = ".tmp"
    }
}
