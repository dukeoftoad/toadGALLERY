package com.toadgallery.gallery.ml.search

import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.ml.engine.MlEngine
import kotlinx.coroutines.CancellationException

/**
 * One [MagicSearchRepository.search] outcome (r26, Workstream 7): the
 * honest result or the typed failure, never a paraphrased-away error.
 */
sealed interface MagicSearchResult {

    /**
     * The ranked matches as real [MediaItem]s, in the CLIP ranking's
     * RELEVANCE order (the vecdb's own distance-ascending order, deduped
     * and limited by [MagicSearchRanking] --- see the repository KDoc for
     * how that order is applied over the media query's newest-first
     * rows). CAN BE EMPTY-equivalent never: an empty outcome is
     * [NoMatches], not this.
     */
    data class Matches(val items: List<MediaItem>) : MagicSearchResult

    /**
     * The query ran and the index held nothing that matched --- including
     * the empty-index shape (nothing indexed yet) and the shape where the
     * index matched rows whose photos no longer resolve in MediaStore
     * (deleted / trashed / vault-moved between indexing and searching);
     * both are honestly "no results for this query", never an error.
     */
    data object NoMatches : MagicSearchResult

    /**
     * The engine leg failed: [message] is the engine's own diagnostic
     * text (the facade's `IllegalStateException`/`IllegalArgumentException`
     * message), surfaced verbatim. The media leg's failures
     * (SecurityException and friends) are NOT this --- they propagate to
     * the caller, whose run-and-catch maps them to the screen's generic
     * Error state.
     */
    data class EngineFailure(val message: String) : MagicSearchResult
}

/**
 * The r26 Magic Search repository (Workstream 7): the ONE object the
 * Search tab's Content mode talks to --- the
 * [com.toadgallery.gallery.ml.people.PeopleRepository] pattern
 * applied to queries. It composes the engine seam
 * ([MagicSearchEngineSeam]) with the MediaStore lookup behind
 * [queryMedia] and owns NO CACHE: every search re-reads the live index,
 * which is exactly the live-store semantics the engine pins (the r26
 * background queue writes CLIP rows between queries; a cached result
 * set would be stale by construction).
 *
 * The query path, end to end:
 *  1. the query text is TRIMMED (the gate's contract --- the ViewModel
 *     already refused unsearchable text; the trim here is the honest
 *     input hygiene, not a second gate);
 *  2. [MagicSearchEngineSeam.clipTextEmbedding] turns it into the
 *     512-float query vector;
 *  3. [MagicSearchEngineSeam.searchClips] returns the store's nearest
 *     rows in the vecdb's own order;
 *  4. [MagicSearchRanking.rank] dedupes + limits that order into the
 *     ranked file ids (order preserved verbatim);
 *  5. [queryMedia] maps the ids to REAL MediaStore rows (newest-first
 *     within the query, the house discipline);
 *  6. the repository RE-ORDERS those rows by the ranking's relevance
 *     order --- the feature's whole point is "ranked photos", and the
 *     newest-first order the media query guarantees is the transport
 *     order, not the display order. Ids whose rows no longer resolve
 *     are dropped honestly (never fabricated);
 *  7. no surviving rows -> [MagicSearchResult.NoMatches]; otherwise
 *     [MagicSearchResult.Matches].
 *
 * The engine leg's failures ([MagicSearchResult.EngineFailure]) carry
 * the engine's own message verbatim; everything else propagates to the
 * caller.
 *
 * All functions are `suspend` and expect the CALLER to hop off the main
 * thread (the ViewModel wraps them in `withContext(Dispatchers.IO)`,
 * the house pattern); the engine seam calls are plain blocking reads.
 *
 * Queries NEVER leave the device (ADR-0010 on-device-only AI; ADR-0020
 * for the model delivery that provisioned the index): this repository
 * reads the app-private store and MediaStore, and nothing else.
 *
 * Pure-JVM over injected seams (ADR-0016): `MagicSearchRepositoryTest`
 * binds a fake engine; the production wiring (the
 * [MagicSearchEngineAdapter] over MlEngine, and the MediaStore
 * [queryMedia] binding from `MagicSearchMediaQuery.kt`) is
 * Android-adjacent and untested per the house rule.
 *
 * @param queryMedia maps the ranked MediaStore file ids to their photos
 * (newest first); the default returns nothing, which keeps this class
 * constructible in pure JVM contexts that never ask for photos.
 */
class MagicSearchRepository(
    private val engine: MagicSearchEngineSeam,
    private val queryMedia: suspend (fileIds: List<Long>) -> List<MediaItem> = { emptyList() },
) {

    /** The build-shape truth: is the engine library in this APK at all. */
    val engineAvailable: Boolean
        get() = engine.engineAvailable

    /**
     * The store's current CLIP row count --- the policy's NotIndexed
     * input. Throws the engine's own failure shape when the session
     * cannot open (the caller's run-and-catch maps it to the Error
     * state); 0 means the index is empty.
     */
    suspend fun clipCount(): Long = engine.clipCount()

    /**
     * One full semantic search; see the class KDoc for the path and
     * [MagicSearchResult] for the honest outcome shapes.
     */
    suspend fun search(
        query: String,
        limit: Int = MagicSearchQuery.DEFAULT_LIMIT,
    ): MagicSearchResult {
        val text = query.trim()
        val matches: List<MlEngine.ClipMatch> = try {
            val embedding = engine.clipTextEmbedding(text)
            engine.searchClips(embedding, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // The facade's own engine-failure shape; the message is the
            // honest diagnostic, never a paraphrase.
            return MagicSearchResult.EngineFailure(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            // The facade's own caller-input guard shape (a broken
            // embedding contract); surfaced the same honest way.
            return MagicSearchResult.EngineFailure(e.message ?: e.javaClass.simpleName)
        }

        val rankedFileIds = MagicSearchRanking.rank(matches, limit)
        if (rankedFileIds.isEmpty()) return MagicSearchResult.NoMatches

        // The media leg: newest-first rows from the store's ids. The
        // repository holds no cache, so this is a fresh read every query.
        val media = queryMedia(rankedFileIds)
        if (media.isEmpty()) return MagicSearchResult.NoMatches

        // Relevance order over the transport order: the ranked ids are
        // the display order; rows that no longer resolve drop honestly.
        val items = rankedMedia(rankedFileIds, media) { it.id }
        return if (items.isEmpty()) {
            MagicSearchResult.NoMatches
        } else {
            MagicSearchResult.Matches(items)
        }
    }
}

/**
 * The pure reordering rule behind [MagicSearchResult.Matches] (r26):
 * [media] (whatever order the query produced it in --- newest-first in
 * production) re-ordered by [rankedFileIds]' RELEVANCE order, with ids
 * that no longer resolve dropped honestly (never fabricated rows).
 *
 * GENERIC over the row (the [idOf] extractor) for the same reason
 * `moveTargets` is: production instantiates it with [MediaItem], which
 * embeds `android.net.Uri` a plain JVM test cannot construct, so
 * `MagicSearchRepositoryTest` pins the rule with a Uri-free fake (the
 * `MoveTargetsTest`/`WindowPagingSourceTest` trick). The repository's
 * own call is the one-line delegate `rankedMedia(ids, media) { it.id }`.
 */
internal fun <T> rankedMedia(
    rankedFileIds: List<Long>,
    media: List<T>,
    idOf: (T) -> Long,
): List<T> {
    val byId = HashMap<Long, T>(media.size)
    media.forEach { byId[idOf(it)] = it }
    val out = ArrayList<T>(media.size)
    for (fileId in rankedFileIds) {
        byId[fileId]?.let { out.add(it) }
    }
    return out
}
