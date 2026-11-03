package io.github.dukeoftoad.gallery.edit

/**
 * The merged look registry (r34, Phase 7): bundled and user-imported
 * `.cube` looks resolved through ONE map so the preview chip row and the
 * export pipeline cannot disagree --- the same single-map guarantee r33
 * gave the bundled set alone, extended to the imports.
 *
 * Contract:
 *  - The [ImportedLutStore] collision-suffixes an import against BOTH the
 *    bundled registry and the existing imports, so the two id sets are
 *    disjoint by construction. The merge still refuses to let an import
 *    shadow a bundled look (defensive honesty: a colliding import is
 *    DROPPED, never the bundled look) --- if a future store regression
 *    ever produced a collision, the bundled look wins and nothing else
 *    changes.
 *  - The result is unmodifiable: the session map is shared across the
 *    preview, the chip row and the export thread, and nobody mutates it
 *    after publication (a refresh publishes a NEW map).
 *  - Deterministic: bundled entries keep the bundled registry's
 *    insertion order, imports follow id-sorted (the store's list order).
 */
object LookRegistry {

    /**
     * Merges the bundled looks with the parsed imports. [bundled] wins
     * every (defensive) key collision; [imported] entries with fresh ids
     * are appended in their given order.
     */
    fun merge(
        bundled: Map<String, CubeLut>,
        imported: Map<String, CubeLut>,
    ): Map<String, CubeLut> {
        val merged = LinkedHashMap<String, CubeLut>(bundled.size + imported.size)
        merged.putAll(bundled)
        for ((id, lut) in imported) {
            if (!merged.containsKey(id)) merged[id] = lut
        }
        return java.util.Collections.unmodifiableMap(merged)
    }
}
