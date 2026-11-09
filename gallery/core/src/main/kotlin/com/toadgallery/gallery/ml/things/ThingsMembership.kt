package com.toadgallery.gallery.ml.things

import com.toadgallery.gallery.ml.engine.MlEngine

/**
 * The pure membership half of a Things collection (r39): the ranked
 * CLIP matches of the collection's query embedding, cut by distance and
 * deduped, into the member file ids.
 *
 * The distance cut runs INSIDE the vecdb ([MlEngine.searchClipsWithin],
 * upstream's own `max_distance`) --- this planner is the SECOND, pure
 * half of the same rule, applied to whatever the seam returned:
 *  1. the CUT (`distance <= maxDistance`, inclusive) is re-applied ---
 *     defense in depth, so a seam that ever returns uncut rows (a fake
 *     in a test, a future engine shape) can never inflate a collection;
 *  2. DEDUPE by file id (first occurrence wins), the
 *     [com.toadgallery.gallery.ml.search.MagicSearchRanking]
 *     contract --- one photo is one member;
 *  3. the vecdb's own order (nearest first) is preserved VERBATIM ---
 *     the ranking IS the display order, the same
 *     mapping-not-sorter rule Magic Search pins.
 *
 * Pure Kotlin over plain values; JVM-tested in `ThingsMembershipTest`
 * (ADR-0016).
 */
object ThingsMembership {

    /**
     * The round-pinned default membership cut (r39): cosine distance
     * <= 0.70 --- similarity >= 0.30 for the L2-normalized
     * MobileCLIP-S2 vectors the index stores. HONESTLY ARBITRARY (the
     * same convention as Magic Search's 200-character query cap): it is
     * a defensible "confident match" operating point for zero-shot
     * text-to-image retrieval, NOT a device-calibrated threshold;
     * per-library tuning is the named follow-up (follow-up.md F-21),
     * and the planner takes its cut as a parameter so a later round can
     * tune without touching this file's callers' semantics.
     */
    const val DEFAULT_MAX_DISTANCE = 0.7f

    /**
     * The member file ids for [matches]: the distance cut re-applied
     * (inclusive), duplicates dropped (first occurrence keeps its
     * position), the vecdb's own order preserved verbatim.
     */
    fun memberFileIds(
        matches: List<MlEngine.ClipMatch>,
        maxDistance: Float = DEFAULT_MAX_DISTANCE,
    ): List<Long> {
        val seen = HashSet<Long>(matches.size)
        val out = ArrayList<Long>(matches.size)
        for (match in matches) {
            if (match.distance > maxDistance) continue
            if (match.fileId in seen) continue
            seen.add(match.fileId)
            out.add(match.fileId)
        }
        return out
    }
}
