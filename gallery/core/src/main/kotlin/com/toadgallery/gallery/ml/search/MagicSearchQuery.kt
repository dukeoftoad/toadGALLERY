package com.toadgallery.gallery.ml.search

/**
 * The pure text gate for Magic Search queries (r26, Workstream 7): the
 * [com.toadgallery.gallery.data.SearchQuery.isSearchable] discipline
 * applied to the CONTENT query path.
 *
 * A query is searchable when its TRIMMED text is at least
 * [MIN_QUERY_LENGTH] and at most [MAX_QUERY_LENGTH] characters. The
 * minimum is the Search tab's own rule (2 --- the "keep typing" hint's
 * threshold, shared with name search so the two modes feel alike). The
 * maximum is a round-pinned bound, honestly arbitrary in origin: the
 * engine's BPE tokenizer accepts far longer text, but a hand-typed
 * content query is a handful of words, and an explicit cap keeps the
 * embedding input bounded without relying on the engine's own internal
 * truncation. Queries longer than the cap are NOT silently truncated
 * here --- [isSearchable] simply reports false and the UI's keep-typing
 * hint says the query is too long (truncating would change what the user
 * asked for; refusing is the honest shape).
 *
 * Pure Kotlin over plain values; JVM-tested in `MagicSearchQueryTest`
 * (ADR-0016).
 */
object MagicSearchQuery {

    /** Queries shorter than this (after trim) never reach the engine. */
    const val MIN_QUERY_LENGTH = 2

    /**
     * The round-pinned query length cap (after trim); see the object
     * KDoc for the honest origin story.
     */
    const val MAX_QUERY_LENGTH = 200

    /**
     * The result cap handed to the store search (and honored again by
     * the ranking layer): mirrors the name pipeline's
     * `SearchQuery.DEFAULT_LIMIT` bound so the two modes promise the
     * same order of result-set size.
     */
    const val DEFAULT_LIMIT = 1000

    /**
     * The query gate: `true` once the trimmed query has at least
     * [MIN_QUERY_LENGTH] and at most [MAX_QUERY_LENGTH] characters.
     * This is the single source of the content mode's "keep typing"
     * behavior --- the ViewModel consults it before every engine
     * round-trip and the tests pin it.
     */
    fun isSearchable(query: String): Boolean {
        val trimmed = query.trim()
        return trimmed.length >= MIN_QUERY_LENGTH && trimmed.length <= MAX_QUERY_LENGTH
    }
}
