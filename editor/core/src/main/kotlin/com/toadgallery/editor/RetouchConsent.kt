package com.toadgallery.editor

import java.io.File

/**
 * The face-retouch consent machine: retouch is OPT-IN — the editor's first
 * consent surface. Pure, JVM-testable: default-off, grant (per-install
 * persisted), deny (re-asks next use), permanent disable (the Settings
 * kill-switch). The stage refuses under consent-off even with a restored
 * sidecar — the layer stays verbatim in the recipe, the stage skips; disabling
 * also hides the entry. Persisted as one wire name in a single file (absent =
 * default-off); atomic tmp-then-rename writes, forgiving reads.
 */
enum class RetouchConsent(val wireName: String) {
    /** Default-off; the first-use dialog re-asks on the next use after a deny. */
    NOT_GRANTED("not-granted"),

    /** Granted for this install; persisted across process death. */
    GRANTED("granted"),

    /** The permanent kill-switch; only an explicit re-enable path can leave it, and v1 has none. */
    PERMANENTLY_DISABLED("permanently-disabled");

    /** Whether the retouch STAGE may run (the consent-off refusal rule). */
    val stageMayRun: Boolean get() = this == GRANTED
}

/** The pure transitions (JVM-tested in `RetouchConsentTest`). */
object RetouchConsentMachine {

    /** Whether the first-use dialog should be shown for [state]. */
    fun shouldAsk(state: RetouchConsent): Boolean = state == RetouchConsent.NOT_GRANTED

    /** The grant transition: NOT_GRANTED -> GRANTED; PERMANENTLY_DISABLED dominates (no dialog re-grant). */
    fun onGrant(state: RetouchConsent): RetouchConsent =
        if (state == RetouchConsent.PERMANENTLY_DISABLED) state else RetouchConsent.GRANTED

    /** The deny transition: stays NOT_GRANTED so the NEXT use re-asks (deny = not-now, not never). */
    fun onDeny(state: RetouchConsent): RetouchConsent =
        if (state == RetouchConsent.PERMANENTLY_DISABLED) state else RetouchConsent.NOT_GRANTED

    /** The permanent disable (the Settings kill-switch): from ANY state. */
    fun onPermanentlyDisable(state: RetouchConsent): RetouchConsent = RetouchConsent.PERMANENTLY_DISABLED
}

/**
 * Per-install persistence: one wire name in `retouch-consent` under [dir];
 * absent or corrupt reads as NOT_GRANTED — a broken file degrades to
 * default-off, the honest default for an opt-in feature.
 */
class RetouchConsentStore(private val dir: File) {

    private val file: File = File(dir, "retouch-consent")

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    fun load(): RetouchConsent = try {
        if (!file.isFile) return RetouchConsent.NOT_GRANTED
        val text = file.readText().trim()
        RetouchConsent.entries.firstOrNull { it.wireName == text } ?: RetouchConsent.NOT_GRANTED
    } catch (_: RuntimeException) {
        RetouchConsent.NOT_GRANTED
    } catch (_: java.io.IOException) {
        RetouchConsent.NOT_GRANTED
    }

    fun save(state: RetouchConsent): Boolean = try {
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(state.wireName)
        if (tmp.renameTo(file)) {
            true
        } else {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
            file.isFile
        }
    } catch (_: RuntimeException) {
        false
    } catch (_: java.io.IOException) {
        false
    }
}
