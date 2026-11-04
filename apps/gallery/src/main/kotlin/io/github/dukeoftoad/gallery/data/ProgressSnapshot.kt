package io.github.dukeoftoad.gallery.data

/**
 * The build-time progress snapshot (F-1, follow-up 2026-11-04): the parse
 * of the repo-root `progress.json` MIRRORED into the APK assets at build
 * time (`copyProgressJson` in apps/gallery/build.gradle.kts →
 * `assets/progress/progress.json`).
 *
 * A deliberately static mirror: the app NEVER fetches progress (the
 * gallery's zero-uninvited-egress posture), the screen says so, and the
 * two scopes --- the overall project and the Ente port workstream ---
 * stay exactly as independent in-app as they are in the file (each bar
 * displays ITS OWN stored percent; nothing aggregates across scopes).
 *
 * Pure Kotlin + JVM-tested (the org.json convention the recipe/sidecar
 * suite established). The parse is TOLERANT: any malformed shape is null
 * (the screen's honest "unavailable in this build" state), never a crash.
 */
data class ProgressPhaseSnapshot(
    val id: String,
    val label: String,
    /** "done" | "in-progress" | "pending"; any other value is kept verbatim and rendered as-is. */
    val status: String,
    val evidence: String?,
)

data class ProgressScopeSnapshot(
    val label: String,
    val sourceOfTruth: String?,
    /** 0..100, as stored --- the dashboard PATCHer recomputes it on every write. */
    val percent: Int,
    val phases: List<ProgressPhaseSnapshot>,
)

data class ProgressSnapshot(
    val updatedAt: String?,
    val project: ProgressScopeSnapshot,
    val entePort: ProgressScopeSnapshot,
) {
    companion object {
        /** Where the mirrored snapshot lives inside the APK. */
        const val ASSET_PATH = "progress/progress.json"

        fun parse(text: String): ProgressSnapshot? = try {
            val root = org.json.JSONObject(text)
            ProgressSnapshot(
                updatedAt = root.optString("updatedAt").ifEmpty { null },
                project = parseScope(root.optJSONObject("project")) ?: return null,
                entePort = parseScope(root.optJSONObject("entePort")) ?: return null,
            )
        } catch (_: org.json.JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

        /** A scope needs a non-empty phase list; everything else defaults honestly. */
        private fun parseScope(o: org.json.JSONObject?): ProgressScopeSnapshot? {
            if (o == null) return null
            val arr = o.optJSONArray("phases") ?: return null
            val phases = ArrayList<ProgressPhaseSnapshot>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = p.optString("id", "").trim()
                if (id.isEmpty()) continue
                phases.add(
                    ProgressPhaseSnapshot(
                        id = id,
                        label = p.optString("label").ifEmpty { id },
                        status = p.optString("status").ifEmpty { "pending" },
                        evidence = p.optString("evidence").ifEmpty { null },
                    ),
                )
            }
            if (phases.isEmpty()) return null
            return ProgressScopeSnapshot(
                label = o.optString("label").ifEmpty { "—" },
                sourceOfTruth = o.optString("sourceOfTruth").ifEmpty { null },
                percent = o.optInt("percent", 0).coerceIn(0, 100),
                phases = phases,
            )
        }
    }
}
