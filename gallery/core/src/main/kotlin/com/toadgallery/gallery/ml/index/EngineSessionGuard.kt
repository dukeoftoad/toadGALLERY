package com.toadgallery.gallery.ml.index

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.toadgallery.gallery.data.diag.DiagLog
import java.util.concurrent.TimeUnit

/**
 * r50 (F-38, the UI-side half of the r48 engine-teardown fix): the native
 * engine and its face-index store are PROCESS-GLOBAL singletons (the Rust
 * side's `engine::ENGINE` / `store::STORE` OnceLock statics), so ANY
 * `closeFaceIndex()` / `release()` call tears the session out from under
 * whichever queue chain is mid-batch at that moment --- the UI adapters'
 * `close()` (People / Magic Search / Albums `onCleared`), the Settings
 * self-test's `finally { release() }` and the two reset buttons all fired
 * unconditionally and killed a live chain with
 * `IllegalStateException("engine is not initialized")` (retry x3 -> the
 * terminal Failed card; the "indexing are failing" device report class).
 *
 * r48 already removed the WORKERS' half of the bug (the per-run teardown);
 * this object is the shared guard for the remaining UI-side call sites:
 * teardown is only safe when NEITHER unique chain has live (ENQUEUED or
 * RUNNING) WorkManager work. Skipping a teardown is always safe --- every
 * engine user re-initializes or re-opens lazily (the facade's re-init
 * guarantee, dogfooded by the self-test itself), so a skipped release
 * leaks nothing.
 *
 * Android-adjacent glue (WorkManager queries), untested per ADR-0016; the
 * pure queue contracts it protects are covered by the JVM suites.
 */
object EngineSessionGuard {

    private const val TAG = "EngineSessionGuard"

    /** How long [awaitIndexChainsIdle] polls before giving up. */
    private const val AWAIT_TIMEOUT_MILLIS = 15_000L

    /** The poll cadence of [awaitIndexChainsIdle] (a WorkManager-DB read). */
    private const val POLL_MILLIS = 250L

    /** Per-chain query budget (the future resolves from the local WM DB). */
    private const val QUERY_TIMEOUT_MILLIS = 5_000L

    /**
     * Waits (bounded) until BOTH chains are terminal. Cancels must be
     * issued by the CALLER first (this guard never cancels on its own).
     * Returns true when idle was reached, false on timeout --- the caller
     * decides whether to proceed anyway (the r48 shape, now race-free in
     * the common case) or skip the teardown (queue-safe).
     */
    suspend fun awaitIndexChainsIdle(
        context: Context,
        timeoutMillis: Long = AWAIT_TIMEOUT_MILLIS,
    ): Boolean = awaitIdle(context, listOf(FACE_INDEX_WORK_NAME, CLIP_INDEX_WORK_NAME), timeoutMillis)

    /**
     * Waits (bounded) until the CLIP chain ALONE is terminal --- the
     * semantic reset's shape: it cancels only the CLIP chain, so it must
     * not stall waiting for a FACE run it never asked to stop.
     */
    suspend fun awaitClipChainIdle(
        context: Context,
        timeoutMillis: Long = AWAIT_TIMEOUT_MILLIS,
    ): Boolean = awaitIdle(context, listOf(CLIP_INDEX_WORK_NAME), timeoutMillis)

    private suspend fun awaitIdle(
        context: Context,
        chains: List<String>,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (liveChainCount(context, chains) > 0) {
            if (System.nanoTime() >= deadline) {
                val names = chains.joinToString("/")
                DiagLog.e(
                    TAG,
                    "index chains $names still live after " +
                        "${timeoutMillis}ms await --- a worker is stuck past its cancellation " +
                        "(a long JNI call cannot be interrupted)",
                )
                return false
            }
            kotlinx.coroutines.delay(POLL_MILLIS)
        }
        return true
    }

    /**
     * True when EITHER index chain has live work (ENQUEUED or RUNNING).
     * The conservative default on any query fault is `true` (assume live):
     * a wrong "live" answer only SKIPS a teardown, never kills a queue.
     */
    fun anyIndexChainLive(context: Context): Boolean =
        liveChainCount(context, listOf(FACE_INDEX_WORK_NAME, CLIP_INDEX_WORK_NAME)) > 0

    /** ENQUEUED/RUNNING work across [chains]; a query fault counts as live. */
    private fun liveChainCount(context: Context, chains: List<String>): Int {
        val wm = try {
            WorkManager.getInstance(context)
        } catch (e: Exception) {
            // WorkManager not initialized (the Roborazzi/JVM suites never
            // install one) or the WM service faulted: assume EVERY chain
            // live. The guard's only decisions are "skip this teardown"
            // and "keep waiting" --- both are safe under a wrong "live"
            // (a skipped close leaks nothing; every engine user re-opens
            // lazily), and both are UNSAFE under a wrong "idle" (an
            // unconditional close is exactly the mid-batch kill F-38
            // exists to prevent).
            DiagLog.e(TAG, "WorkManager unavailable; assuming all ${chains.size} chains live: ${e.message}")
            return chains.size
        }
        var live = 0
        for (name in chains) {
            try {
                val infos = wm.getWorkInfosForUniqueWork(name)
                    .get(QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                if (infos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                ) {
                    live++
                }
            } catch (e: Exception) {
                // WorkManager not initialized / DB fault: assume live (the
                // conservative answer for a teardown decision).
                DiagLog.e(TAG, "chain '$name' liveness query fault: ${e.message}")
                live++
            }
        }
        return live
    }
}
