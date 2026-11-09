package com.toadgallery.gallery.ml.search

import com.toadgallery.gallery.ml.engine.MlEngine

/**
 * The pure ranking half of Magic Search (r26, Workstream 7):
 * `List<ClipMatch>` -> ranked file ids.
 *
 * [rank] preserves the vecdb's own order (distance ascending, the store
 * search's contract) VERBATIM --- the same "mapping, not sorter" rule
 * the People policy pins; the engine's ordering IS the relevance
 * signal. On top of that order it applies exactly two guarantees the
 * store does not promise:
 *  1. DEDUPE by file id (first occurrence wins): a photo is one row in
 *     the CLIP index by construction (the r26 queue writes ONE
 *     whole-photo embedding per file), but a future engine that shards
 *     or re-embeds could hand back repeats, and the results grid shows
 *     each photo once;
 *  2. the LIMIT is honored AFTER the dedupe (a duplicate must never
 *     consume one of the result slots a unique photo could fill).
 *
 * ## What v1 deliberately does NOT do
 * No distance-to-similarity conversion and no threshold cut: the
 * distances stay inside [MlEngine.ClipMatch] and the v1 UI never
 * surfaces them (a raw CLIP distance is not a percentage anyone can
 * interpret, and an honest similarity figure would need the cosine
 * normalization assumption spelled out --- `similarity = 1 - distance`
 * holds only for normalized vectors). Score display and threshold
 * tuning are named follow-ups in `docs/phase-5/magic-search.md`; until
 * then the ranking is order-only, which is exactly what the results
 * grid renders.
 *
 * Pure Kotlin over plain values; JVM-tested in `MagicSearchRankingTest`
 * (ADR-0016).
 */
object MagicSearchRanking {

    /**
     * The ranked file ids for [matches]: vecdb order preserved verbatim,
     * duplicates dropped (first occurrence keeps its position), at most
     * [limit] ids returned (non-positive limits coerce to 1 --- the
     * defensive floor, the same convention `SearchQuery.sortOrder`
     * pins).
     */
    fun rank(matches: List<MlEngine.ClipMatch>, limit: Int): List<Long> {
        val bound = limit.coerceAtLeast(1)
        val seen = HashSet<Long>(matches.size)
        val out = ArrayList<Long>(matches.size.coerceAtMost(bound))
        for (match in matches) {
            if (match.fileId in seen) continue
            seen.add(match.fileId)
            out.add(match.fileId)
            if (out.size == bound) break
        }
        return out
    }
}
