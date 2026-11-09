package com.toadgallery.gallery.ml.things

import com.toadgallery.gallery.data.MediaItem
import com.toadgallery.gallery.ml.engine.MlEngine
import com.toadgallery.gallery.ml.search.MagicSearchEngineSeam
import com.toadgallery.gallery.ml.search.rankedMedia
import kotlinx.coroutines.CancellationException

/**
 * One [ThingsRepository.members] outcome (r39): the honest result or
 * the typed failure, never a paraphrased-away error --- the
 * [com.toadgallery.gallery.ml.search.MagicSearchResult] contract
 * applied to the collections domain.
 */
sealed interface ThingsMemberOutcome {

    /**
     * The collection's members as real [MediaItem]s, in the CLIP
     * ranking's RELEVANCE order (the vecdb's own distance-ascending
     * order, deduped and distance-cut by [ThingsMembership]). CAN BE
     * EMPTY-equivalent never: an empty membership is [Empty], not this.
     */
    data class Members(val items: List<MediaItem>) : ThingsMemberOutcome

    /**
     * The query ran and nothing within the membership cut matched ---
     * including the shape where matches existed but their photos no
     * longer resolve in MediaStore (deleted / trashed / vault-moved
     * between indexing and reading); both are honestly "no members",
     * never an error.
     */
    data object Empty : ThingsMemberOutcome

    /**
     * The engine leg failed: [message] is the engine's own diagnostic
     * text (the facade's IllegalStateException/IllegalArgumentException
     * message), surfaced verbatim.
     */
    data class EngineFailure(val message: String) : ThingsMemberOutcome

    /**
     * No collection with the requested id exists (deleted meanwhile, or
     * a stale route). The UI routes back honestly; never a fake empty
     * membership.
     */
    data object UnknownCollection : ThingsMemberOutcome
}

/**
 * The r39 Things repository (the ente-port exit round): the ONE object
 * the Things surfaces talk to --- the
 * [com.toadgallery.gallery.ml.people.PeopleRepository] pattern
 * applied to named semantic collections. It composes the semantic
 * engine seam ([MagicSearchEngineSeam] --- the SAME seam Magic Search
 * uses, extended with [MlEngine.searchClipsWithin]) with the
 * [ThingsStateStore] persistence and the MediaStore lookup behind
 * [queryMedia], and owns NO CACHE: every membership read re-embeds the
 * query and re-searches the live index, which is exactly the
 * derived-view doctrine (the r26 queue writes CLIP rows between reads;
 * a cached membership would be stale by construction).
 *
 * The membership path, end to end ([members]):
 *  1. the state loads; the collection with the id resolves (else
 *     [ThingsMemberOutcome.UnknownCollection]);
 *  2. the collection's query is embedded by the CLIP text encoder
 *     (the SAME encoder Magic Search's ad-hoc queries use);
 *  3. the store's clip count bounds ONE distance-cut search
 *     ([MlEngine.searchClipsWithin]) --- the cut does the membership,
 *     the bound only says "every row is a candidate";
 *  4. [ThingsMembership.memberFileIds] re-applies the cut, dedupes and
 *     preserves the vecdb's order (the pure, tested half);
 *  5. [queryMedia] maps the ids to REAL MediaStore rows (newest-first
 *     within the query, the house discipline --- the
 *     `queryMagicSearchMedia` binding in production);
 *  6. the repository RE-ORDERS those rows by the ranking's relevance
 *     order (the shared `rankedMedia` rule); ids whose rows no longer
 *     resolve are dropped honestly (never fabricated);
 *  7. no surviving rows -> [ThingsMemberOutcome.Empty]; otherwise
 *     [ThingsMemberOutcome.Members].
 *
 * The engine leg's failures map to [ThingsMemberOutcome.EngineFailure]
 * with the engine's own message verbatim; the mutations
 * ([create]/[rename]/[requery]/[remove]) are typed honestly too: a
 * refused create (blank name) returns null, an unknown id returns
 * false, and every mutation persists the WHOLE state atomically through
 * the store.
 *
 * All functions are `suspend` and expect the CALLER to hop off the main
 * thread (the ViewModels wrap them in `withContext(Dispatchers.IO)`,
 * the house pattern); the engine seam calls are plain blocking reads.
 *
 * Queries NEVER leave the device (ADR-0010 on-device-only AI; ADR-0020
 * for the model delivery that provisioned the index): this repository
 * reads the app-private store and MediaStore, and nothing else.
 *
 * Pure-JVM over injected seams (ADR-0016): `ThingsRepositoryTest` binds
 * a fake engine + an in-memory store; the production wiring (the
 * [MagicSearchEngineSeam] adapter, and the MediaStore [queryMedia]
 * binding) is Android-adjacent and untested per the house rule.
 *
 * @param queryMedia maps the ranked MediaStore file ids to their photos
 * (newest first); the default returns nothing, which keeps this class
 * constructible in pure JVM contexts that never ask for photos.
 */
class ThingsRepository(
    private val engine: MagicSearchEngineSeam,
    private val store: ThingsStateStore,
    private val queryMedia: suspend (fileIds: List<Long>) -> List<MediaItem> = { emptyList() },
) {

    /** The build-shape truth: is the engine library in this APK at all. */
    val engineAvailable: Boolean
        get() = engine.engineAvailable

    /**
     * The persisted collections + the honest corrupt-file note. The
     * store's own failure shape propagates (the caller's run-and-catch
     * maps it to the Error state).
     */
    suspend fun collections(): ThingsStateLoad = store.load()

    /**
     * One collection by id, or null. A convenience read over
     * [collections] for the detail screen's header.
     */
    suspend fun collection(collectionId: String): ThingsCollection? =
        store.load().state.collectionWith(collectionId)

    /**
     * Creates a collection from [rawName]/[rawQuery]: both trimmed; a
     * blank name (or one with no alphanumeric characters) is REFUSED as
     * null; a blank query falls back to the name. Returns the CREATED
     * collection (its derived, collision-suffixed id included), or
     * null. [nowMillis] is the caller-supplied creation timestamp (the
     * store never reads a clock).
     */
    suspend fun create(rawName: String, rawQuery: String, nowMillis: Long): ThingsCollection? {
        val load = store.load()
        val added = load.state.withCollectionAdded(rawName, rawQuery, nowMillis) ?: return null
        store.save(added)
        // The id was derived against the PRE-add state; locating the fresh
        // tail by that derivation keeps the return value the store's own
        // truth (never a parallel construction).
        val slug = collectionIdSlug(rawName.trim()) ?: return null
        return added.collectionWith(load.state.nextCollectionId(slug))
    }

    /**
     * Renames [collectionId]'s collection to [rawName] (a blank name is
     * refused --- false). Returns whether a change happened.
     */
    suspend fun rename(collectionId: String, rawName: String): Boolean {
        val state = store.load().state
        val renamed = state.withCollectionRenamed(collectionId, rawName)
        if (renamed === state) return false
        store.save(renamed)
        return true
    }

    /**
     * Sets [collectionId]'s membership query to [rawQuery] (a blank
     * query resets to the collection's name). Returns whether a change
     * happened.
     */
    suspend fun requery(collectionId: String, rawQuery: String): Boolean {
        val state = store.load().state
        val requeried = state.withCollectionQueried(collectionId, rawQuery)
        if (requeried === state) return false
        store.save(requeried)
        return true
    }

    /**
     * Removes [collectionId]'s collection. Returns whether a collection
     * was removed.
     */
    suspend fun remove(collectionId: String): Boolean {
        val state = store.load().state
        val removed = state.withCollectionRemoved(collectionId)
        if (removed === state) return false
        store.save(removed)
        return true
    }

    /**
     * One collection's membership; see the class KDoc for the path and
     * [ThingsMemberOutcome] for the honest outcome shapes. The
     * [maxDistance] cut defaults to [ThingsMembership.DEFAULT_MAX_DISTANCE].
     */
    suspend fun members(
        collectionId: String,
        maxDistance: Float = ThingsMembership.DEFAULT_MAX_DISTANCE,
    ): ThingsMemberOutcome {
        val collection = store.load().state.collectionWith(collectionId)
            ?: return ThingsMemberOutcome.UnknownCollection

        val rankedFileIds: List<Long> = try {
            val embedding = engine.clipTextEmbedding(collection.query)
            // The bound says "every row is a candidate"; the distance cut
            // does the membership. A zero-row index bounds to 1 (the seam's
            // positive-limit guard) and the search returns nothing --- the
            // honest Empty, never a refused call.
            val bound = engine.clipCount().toInt().coerceAtLeast(1)
            val matches = engine.searchClipsWithin(embedding, maxDistance, bound)
            ThingsMembership.memberFileIds(matches, maxDistance)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // The facade's own engine-failure shape; the message is the
            // honest diagnostic, never a paraphrase.
            return ThingsMemberOutcome.EngineFailure(e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            // The facade's own caller-input guard shape (a broken embedding
            // contract); surfaced the same honest way.
            return ThingsMemberOutcome.EngineFailure(e.message ?: e.javaClass.simpleName)
        }
        if (rankedFileIds.isEmpty()) return ThingsMemberOutcome.Empty

        // The media leg: newest-first rows from the store's ids. The
        // repository holds no cache, so this is a fresh read every time.
        val media = queryMedia(rankedFileIds)
        if (media.isEmpty()) return ThingsMemberOutcome.Empty

        // Relevance order over the transport order: the ranked ids are the
        // display order; rows that no longer resolve drop honestly (the
        // shared Magic Search rule, reused --- one reordering truth).
        val items = rankedMedia(rankedFileIds, media) { it.id }
        return if (items.isEmpty()) {
            ThingsMemberOutcome.Empty
        } else {
            ThingsMemberOutcome.Members(items)
        }
    }

    /**
     * The store's current CLIP row count --- the section's NotIndexed
     * gate input (the MagicSearchRepository.clipCount twin).
     */
    suspend fun clipCount(): Long = engine.clipCount()
}
