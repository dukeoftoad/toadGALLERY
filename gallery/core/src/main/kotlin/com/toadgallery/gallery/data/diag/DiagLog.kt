package com.toadgallery.gallery.data.diag

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * App-wide diagnostics logger (W1 vault-diagnostics round): a bounded
 * in-memory ring buffer that ALSO mirrors every entry to `android.util.Log`.
 *
 * ## Why it exists
 * A device running build versionCode 11 reported "vault PIN creating
 * failed" with zero diagnostics: the failing seams swallowed exceptions
 * into generic snackbars and logcat showed nothing. This object is the
 * always-on memory those seams now write to --- [snapshot] feeds the
 * Settings > About > Diagnostics screen, so a user-submitted report carries
 * the recent past with it, and the logcat mirror keeps `adb logcat` useful
 * for local debugging.
 *
 * ## Privacy contract (ADR-0014)
 * Diagnostics, not telemetry: counts, ids, sizes and exception
 * class+message ONLY --- never PIN content or length, key material, or
 * vault display names (callers police this; the contract lives here so no
 * call site has to guess). The app holds no INTERNET permission, so logcat
 * is the only egress that exists and reading it requires adb or physical
 * access to the device; the ring itself never leaves the process. RELEASE
 * BUILDS LOG TOO --- that is the point: user devices are the ones that
 * need diagnosing, and the volumes are a handful of lines per user action.
 *
 * ## Bounds + thread safety
 * At most [CAP] entries are retained (oldest evicted first), so memory is
 * flat regardless of process lifetime. All ring access is `synchronized`
 * on the ring; the shared [SimpleDateFormat] is only ever touched under
 * that same monitor (it is not thread-safe on its own). Entry stamps are
 * UTC so a report reads unambiguously on any device timezone.
 *
 * ## Test seams (ADR-0016)
 * [clock] and [sink] are internal vars: JVM tests pin the clock and swap
 * the sink for a recorder, so `android.util.Log` --- unmocked on the JVM
 * --- is never touched from the unit suite.
 */
object DiagLog {

    /**
     * Ring capacity: the newest [CAP] entries survive, oldest evicted
     * first. 400 lines is a few user actions' worth of context at this
     * app's volumes --- bounded memory, no rotation files, no I/O.
     */
    const val CAP: Int = 400

    /**
     * INFO-level entry. Ring line format:
     * `"MM-dd HH:mm:ss.SSS I/Tag: message"` (UTC stamp --- see the class
     * KDoc); [snapshot] lists entries oldest-first, and the mirror sink
     * receives the message WITHOUT the stamp (logcat adds its own).
     */
    fun i(tag: String, msg: String) {
        log('I', tag, msg, tr = null)
    }

    /**
     * WARN-level entry. When [tr] != null the SAME entry carries the
     * throwable and its cause chain as extra ` -> class: message` lines
     * (the throwable first, then up to [MAX_CAUSE_DEPTH] causes; a
     * self-referencing or cyclic cause stops the walk) --- class+message
     * only, never stack frames (frames are noise at diagnostic volume).
     */
    fun w(tag: String, msg: String, tr: Throwable? = null) {
        log('W', tag, msg, tr)
    }

    /** ERROR-level entry; the throwable rendering rule is [w]'s. */
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        log('E', tag, msg, tr)
    }

    /**
     * The buffered entries, oldest-first, as an immutable copy: attempts to
     * mutate the result throw, and ring churn after a snapshot never
     * rewrites one already taken.
     */
    fun snapshot(): List<String> = synchronized(ring) {
        Collections.unmodifiableList(ArrayList(ring))
    }

    /** Drops every buffered entry. */
    fun clear() {
        synchronized(ring) { ring.clear() }
    }

    /**
     * The clock behind every entry's stamp ([System.currentTimeMillis] by
     * default). Test seam: JVM tests pin it so entry formats assert
     * against exact strings.
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * The mirror sink: receives (level, tag, message) for every entry, the
     * message already formatted (cause chain included) but WITHOUT the
     * timestamp prefix. Default: `android.util.Log.println` under the
     * "ToadDiag" logcat tag, priority mapped I/W/E -> INFO/WARN/ERROR.
     * Test seam: JVM tests substitute a recorder so `android.util.Log` is
     * never touched (ADR-0016).
     */
    internal var sink: (level: Char, tag: String, msg: String) -> Unit =
        { level, tag, msg ->
            val priority = when (level) {
                'W' -> Log.WARN
                'E' -> Log.ERROR
                else -> Log.INFO
            }
            Log.println(priority, LOGCAT_TAG, "$tag: $msg")
        }

    /** The bounded ring; the monitor every access synchronizes on. */
    private val ring = ArrayDeque<String>()

    /**
     * The entry stamp format (`MM-dd HH:mm:ss.SSS`, pinned to UTC so
     * entries are unambiguous across device timezones). NOT thread-safe
     * alone --- only touched while holding the ring's monitor.
     */
    private val stamp: SimpleDateFormat =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun log(level: Char, tag: String, msg: String, tr: Throwable?) {
        val full = if (tr == null) msg else "$msg\n${renderCauseChain(tr)}"
        synchronized(ring) {
            ring.addLast("${stamp.format(Date(clock()))} $level/$tag: $full")
            while (ring.size > CAP) {
                ring.removeFirst() // oldest line = least valuable line
            }
        }
        // Outside the lock: the sink is arbitrary code (test recorders, and
        // logcat itself); a slow or re-entrant sink must never stall other
        // threads' logging or the ring's eviction.
        sink(level, tag, full)
    }

    /**
     * The ` -> class: message` lines for [tr] and up to [MAX_CAUSE_DEPTH]
     * of its causes. The depth cap alone bounds ANY cycle; the identity
     * check additionally short-circuits the `cause == itself` spelling the
     * moment it appears.
     */
    private fun renderCauseChain(tr: Throwable): String {
        val lines = mutableListOf(chainLine(tr))
        var current = tr
        var depth = 0
        while (depth < MAX_CAUSE_DEPTH) {
            val cause = current.cause ?: break
            if (cause === current) break // cause == itself: stop, never hang
            lines += chainLine(cause)
            current = cause
            depth++
        }
        return lines.joinToString("\n")
    }

    /**
     * One chain line: ` -> java.io.IOException: provider refused` --- the
     * message colon appears only when the message exists.
     */
    private fun chainLine(t: Throwable): String =
        " -> ${t.javaClass.name}" + (t.message?.let { ": $it" } ?: "")

    /** The logcat tag every mirrored line lands under. */
    private const val LOGCAT_TAG = "ToadDiag"

    /** How deep [renderCauseChain] walks; also the cycle terminator. */
    private const val MAX_CAUSE_DEPTH = 3
}
