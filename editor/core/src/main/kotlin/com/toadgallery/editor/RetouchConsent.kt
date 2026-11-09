package com.toadgallery.editor

import java.io.File

/**
 * The face-retouch consent machine (r41, Phase 10 sub-scope A; the
 * design contract `docs/phase-10/edit-v3-ai.md`, decision 8): retouch is
 * OPT-IN per ADR-0010 §5 --- the FIRST consent surface in the editor.
 *
 * The machine is a pure, JVM-testable slice (the
 * `SemanticContentPolicy` consent-off precedent): default-off, grant
 * (per-install persisted), deny (re-asks next use), permanent disable
 * (the Settings kill-switch). The STAGE refuses under consent-off even
 * with a restored sidecar --- the layer stays verbatim in the recipe,
 * the stage skips; disabling also hides the entry.
 *
 * The persisted form is one wire name in a single file under the
 * editor's app-private storage (absent = default-off); writes are
 * atomic tmp-then-rename, reads forgiving --- the store's discipline.
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

    /**
     * The grant transition: NOT_GRANTED -> GRANTED. A permanently
     * disabled install cannot be re-granted through the dialog (the
     * kill-switch dominates --- the disable is permanent in v1).
     */
    fun onGrant(state: RetouchConsent): RetouchConsent =
        if (state == RetouchConsent.PERMANENTLY_DISABLED) state else RetouchConsent.GRANTED

    /**
     * The deny transition: stays NOT_GRANTED, so the NEXT use re-asks
     * (a deny is a not-now, not a never --- the never is the switch).
     */
    fun onDeny(state: RetouchConsent): RetouchConsent =
        if (state == RetouchConsent.PERMANENTLY_DISABLED) state else RetouchConsent.NOT_GRANTED

    /** The permanent disable (the Settings kill-switch): from ANY state. */
    fun onPermanentlyDisable(state: RetouchConsent): RetouchConsent = RetouchConsent.PERMANENTLY_DISABLED
}

/**
 * The per-install persistence: one wire name in `retouch-consent` under
 * the given directory; absent or corrupt reads as NOT_GRANTED (forgiving,
 * never thrown --- a broken file degrades to default-off, the honest
 * default for an opt-in feature).
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
