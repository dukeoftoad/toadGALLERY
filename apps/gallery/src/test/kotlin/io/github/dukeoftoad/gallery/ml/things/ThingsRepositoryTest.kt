package io.github.dukeoftoad.gallery.ml.things

import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import io.github.dukeoftoad.gallery.ml.search.MagicSearchEngineSeam
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the r39 Things repository over a FAKE
 * [MagicSearchEngineSeam] + an in-memory store (ADR-0016: the repository
 * is pure composition over injected seams; the production
 * `MagicSearchEngineAdapter`/MediaStore bindings are the untested
 * Android-adjacent half) --- the `MagicSearchRepositoryTest` shape,
 * collections edition. The media leg binds `{ emptyList() }` for the
 * same reason that test does (`MediaItem` embeds `android.net.Uri`, a
 * plain JVM test cannot construct it); the RELEVANCE-order rule the
 * repository applies is `rankedMedia`, already pinned Uri-free by the
 * r26 suite.
 *
 * What is pinned (the composition the section/detail ViewModels drive):
 * - [ThingsRepository.members] embeds the collection's OWN query, bounds
 *   ONE distance-cut search by the store's clip count, and maps the
 *   cut+deduped ids through the pure planner;
 * - the engine leg's failure shapes (the facade's
 *   `IllegalStateException`/`IllegalArgumentException`) become
 *   [ThingsMemberOutcome.EngineFailure] with the message VERBATIM;
 * - a missing collection is [ThingsMemberOutcome.UnknownCollection];
 *   zero in-distance matches is [ThingsMemberOutcome.Empty];
 * - the mutations persist the WHOLE state and return their honest
 *   booleans (refused create = null).
 */
class ThingsRepositoryTest {

    // ---- the fakes ----

    /** The in-memory [ThingsStateStore] (load/save over a plain var). */
    private class MemoryStore(var state: ThingsState = ThingsState.EMPTY) : ThingsStateStore {
        var corrupt = false

        override suspend fun load(): ThingsStateLoad =
            if (corrupt) {
                ThingsStateLoad(ThingsState.EMPTY, recoveredFromCorruptFile = true)
            } else {
                ThingsStateLoad(state, recoveredFromCorruptFile = false)
            }

        override suspend fun save(state: ThingsState) {
            this.state = state
        }
    }

    /**
     * Scripted engine: one deterministic embedding per text, a scripted
     * within-search result, a scripted clip count; records every call so
     * the composition itself is assertable. The session-lifecycle
     * members are inert (the adapter owns the session, the repository
     * never drives it --- pinned below).
     */
    private class FakeEngine(
        override val engineAvailable: Boolean = true,
        private val matches: List<MlEngine.ClipMatch> = emptyList(),
        private val clipCount: Long = 3L,
        private val textFailure: RuntimeException? = null,
        private val searchFailure: RuntimeException? = null,
    ) : MagicSearchEngineSeam {

        val textCalls = mutableListOf<String>()
        val withinCalls = mutableListOf<Triple<FloatArray, Float, Int>>()

        override fun semanticState(): MlEngine.SemanticState = MlEngine.SemanticState.Uninitialized
        override fun initSemantic(modelsDir: String): MlEngine.SemanticState =
            MlEngine.SemanticState.Uninitialized
        override fun openFaceIndex(dir: String): Boolean = true

        override fun clipTextEmbedding(text: String): FloatArray {
            textCalls.add(text)
            textFailure?.let { throw it }
            return FloatArray(512) { (it + text.length).toFloat() }
        }

        override fun searchClips(queryEmbedding: FloatArray, limit: Int): List<MlEngine.ClipMatch> =
            emptyList()

        override fun searchClipsWithin(
            queryEmbedding: FloatArray,
            maxDistance: Float,
            limit: Int,
        ): List<MlEngine.ClipMatch> {
            withinCalls.add(Triple(queryEmbedding, maxDistance, limit))
            searchFailure?.let { throw it }
            return matches
        }

        override fun clipCount(): Long = clipCount
    }

    // ---- the membership path ----

    @Test
    fun `members embeds the collection's own query and bounds one within-search by the clip count`() =
        runBlocking {
            val store = MemoryStore()
            store.state = store.state.withCollectionAdded("Seaside", "a beach at sunset", 1L)!!
            val engine = FakeEngine(
                matches = listOf(MlEngine.ClipMatch(7, 0.2f), MlEngine.ClipMatch(3, 0.69f)),
                clipCount = 42L,
            )
            val repository = ThingsRepository(engine = engine, store = store, queryMedia = { emptyList() })

            val outcome = repository.members("seaside")
            // The query text reached the encoder VERBATIM (the collection's
            // own query, not the name):
            assertEquals(listOf("a beach at sunset"), engine.textCalls)
            // ONE within-search, cut at the round-pinned default, bounded by
            // the clip count:
            assertEquals(1, engine.withinCalls.size)
            assertEquals(ThingsMembership.DEFAULT_MAX_DISTANCE, engine.withinCalls.single().second)
            assertEquals(42, engine.withinCalls.single().third)
            // The default-query shape (a collection created without one):
            // zero in-distance matches -> Empty (the media leg binds empty,
            // so Members cannot be produced on the JVM --- the r26 suite's
            // documented Uri constraint; the engine path above is the
            // coverage).
            assertTrue(outcome is ThingsMemberOutcome.Empty)
        }

    @Test
    fun `members without any in-distance match is Empty`() = runBlocking {
        val store = MemoryStore()
        store.state = store.state.withCollectionAdded("Dogs", "", 1L)!!
        val engine = FakeEngine(matches = emptyList(), clipCount = 5L)
        val repository = ThingsRepository(engine = engine, store = store, queryMedia = { emptyList() })

        assertEquals(ThingsMemberOutcome.Empty, repository.members("dogs"))
        assertEquals(listOf("Dogs"), engine.textCalls)
    }

    @Test
    fun `a missing collection is UnknownCollection - never a fake empty membership`() = runBlocking {
        val repository = ThingsRepository(
            engine = FakeEngine(),
            store = MemoryStore(),
            queryMedia = { emptyList() },
        )
        assertEquals(ThingsMemberOutcome.UnknownCollection, repository.members("ghost"))
    }

    @Test
    fun `the engine leg's failures become EngineFailure with the message verbatim`() = runBlocking {
        val store = MemoryStore()
        store.state = store.state.withCollectionAdded("Dogs", "", 1L)!!

        val textFailure = ThingsRepository(
            engine = FakeEngine(textFailure = IllegalStateException("semantic models are not initialized; call nativeInitSemantic(modelsDir) first")),
            store = store,
            queryMedia = { emptyList() },
        )
        assertEquals(
            ThingsMemberOutcome.EngineFailure(
                "semantic models are not initialized; call nativeInitSemantic(modelsDir) first",
            ),
            textFailure.members("dogs"),
        )

        val searchFailure = ThingsRepository(
            engine = FakeEngine(searchFailure = IllegalArgumentException("query embedding must be 512 floats; got 511")),
            store = store,
            queryMedia = { emptyList() },
        )
        assertEquals(
            ThingsMemberOutcome.EngineFailure("query embedding must be 512 floats; got 511"),
            searchFailure.members("dogs"),
        )
    }

    // ---- the mutations ----

    @Test
    fun `create persists, derives the id, defaults the query and refuses blank names`() = runBlocking {
        val store = MemoryStore()
        val repository = ThingsRepository(engine = FakeEngine(), store = store, queryMedia = { emptyList() })

        val created = repository.create("  Dogs ", "puppies", nowMillis = 10L)!!
        assertEquals("dogs", created.collectionId)
        assertEquals("Dogs", created.name)
        assertEquals("puppies", created.query)
        assertEquals(listOf(created), store.state.collections)

        // The collision suffix against the existing id:
        val second = repository.create("Dogs", "", nowMillis = 20L)!!
        assertEquals("dogs-2", second.collectionId)
        assertEquals("the blank query defaults to the name", "Dogs", second.query)

        assertNull("a blank name is refused", repository.create("   ", "x", 1L))
        assertNull("a no-alphanumeric name is refused", repository.create("!?*", "x", 1L))
        assertEquals("nothing was persisted by the refusals", 2, store.state.collections.size)
    }

    @Test
    fun `rename, requery and remove persist and return their honest booleans`() = runBlocking {
        val store = MemoryStore()
        store.state = store.state.withCollectionAdded("Dogs", "puppies", 1L)!!
        val repository = ThingsRepository(engine = FakeEngine(), store = store, queryMedia = { emptyList() })

        assertTrue(repository.rename("dogs", "Good dogs"))
        assertEquals("Good dogs", store.state.collectionWith("dogs")!!.name)
        assertFalse("a blank rename is refused", repository.rename("dogs", "   "))
        assertFalse("an unknown id is a no-op", repository.rename("cats", "Cats"))

        assertTrue(repository.requery("dogs", "dogs at the beach"))
        assertEquals("dogs at the beach", store.state.collectionWith("dogs")!!.query)
        assertTrue("a blank query resets to the name", repository.requery("dogs", ""))
        assertEquals("Good dogs", store.state.collectionWith("dogs")!!.query)
        assertFalse(repository.requery("cats", "x"))

        assertTrue(repository.remove("dogs"))
        assertEquals(ThingsState.EMPTY, store.state)
        assertFalse("a second remove is a no-op", repository.remove("dogs"))
    }

    @Test
    fun `collections and collection delegate to the store and engineAvailable to the seam`() = runBlocking {
        val store = MemoryStore()
        store.state = store.state.withCollectionAdded("Dogs", "", 1L)!!
        val engine = FakeEngine(engineAvailable = false, clipCount = 9L)
        val repository = ThingsRepository(engine = engine, store = store, queryMedia = { emptyList() })

        assertEquals(store.state.collections, repository.collections().state.collections)
        assertEquals(store.state.collectionWith("dogs"), repository.collection("dogs"))
        assertNull(repository.collection("cats"))
        assertFalse(repository.engineAvailable)
        assertEquals(9L, repository.clipCount())
    }
}
