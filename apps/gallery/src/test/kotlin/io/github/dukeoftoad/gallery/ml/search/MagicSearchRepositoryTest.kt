package io.github.dukeoftoad.gallery.ml.search

import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the r26 Magic Search repository (Workstream 7) over a
 * FAKE [MagicSearchEngineSeam] (ADR-0016: the repository is pure
 * composition over injected seams; the production
 * `MagicSearchEngineAdapter`/MediaStore bindings are the untested
 * Android-adjacent half) --- the `PeopleRepositoryTest` shape, query
 * edition.
 *
 * What is pinned (the composition the Content-mode ViewModel drives):
 * - [MagicSearchRepository.search] composes the engine path honestly:
 *   the TRIMMED query text reaches [MagicSearchEngineSeam
 *   .clipTextEmbedding], the returned embedding reaches
 *   [MagicSearchEngineSeam.searchClips] with the caller's limit, and the
 *   store's vecdb order flows through [MagicSearchRanking] into the
 *   media leg's file ids;
 * - the engine leg's own failure shapes (the facade's
 *   `IllegalStateException`/`IllegalArgumentException`) become
 *   [MagicSearchResult.EngineFailure] with the message VERBATIM ---
 *   never a paraphrase, never an escape;
 * - cancellation is never swallowed into a failure result;
 * - zero matches --- and matches whose photos no longer resolve in the
 *   media leg --- are [MagicSearchResult.NoMatches], never an error;
 * - the RELEVANCE-order-over-transport-order rule
 *   ([rankedMedia]: the ranked ids are the display order, the media
 *   query's own order is just the transport, unresolvable ids drop) is
 *   pinned with a Uri-free fake --- `MediaItem` embeds `android.net.Uri`
 *   a plain JVM test cannot construct, so the rule is generic over the
 *   row exactly the way `moveTargets` is (the `MoveTargetsTest` /
 *   `WindowPagingSourceTest` trick the function's KDoc names);
 * - [MagicSearchRepository.clipCount] and [engineAvailable] are the
 *   seam's own truths, delegated unchanged.
 */
class MagicSearchRepositoryTest {

    // ---- the fake ----

    /**
     * Scripted engine: hands back one deterministic embedding per text,
     * a scripted match list, and a scripted clip count; records every
     * call so the composition itself is assertable. The session-lifecycle
     * members ([semanticState]/[initSemantic]/[openFaceIndex]) are inert:
     * the repository never drives them (the adapter owns the session),
     * and the tests pin that they stay undriven.
     */
    private class FakeEngine(
        override val engineAvailable: Boolean = true,
        private val matches: List<MlEngine.ClipMatch> = emptyList(),
        private val clipCount: Long = 0L,
        private val textFailure: RuntimeException? = null,
        private val searchFailure: RuntimeException? = null,
    ) : MagicSearchEngineSeam {

        val textCalls = mutableListOf<String>()
        val searchCalls = mutableListOf<Pair<FloatArray, Int>>()

        override fun semanticState(): MlEngine.SemanticState = MlEngine.SemanticState.Uninitialized
        override fun initSemantic(modelsDir: String): MlEngine.SemanticState =
            MlEngine.SemanticState.Uninitialized
        override fun openFaceIndex(dir: String): Boolean = true

        override fun clipTextEmbedding(text: String): FloatArray {
            textCalls.add(text)
            textFailure?.let { throw it }
            // One deterministic 512-float vector per text (the facade's
            // own dimension guard is not under test here).
            return FloatArray(512) { (it + text.length).toFloat() }
        }

        override fun searchClips(queryEmbedding: FloatArray, limit: Int): List<MlEngine.ClipMatch> {
            searchCalls.add(queryEmbedding to limit)
            searchFailure?.let { throw it }
            return matches
        }

        // r39: the seam's distance-cut twin. The r26 repository never
        // drives it (pinned below --- it is the Things repository's path),
        // so the fake records nothing and returns nothing.
        override fun searchClipsWithin(
            queryEmbedding: FloatArray,
            maxDistance: Float,
            limit: Int,
        ): List<MlEngine.ClipMatch> = emptyList()

        override fun clipCount(): Long = clipCount
    }

    /** A Uri-free stand-in row: exactly the identity the reorder reads. */
    private data class FakeRow(val id: Long, val label: String)

    // ---- the composition ----

    @Test
    fun `search trims the text, embeds once and searches with the caller's limit`() = runBlocking {
        val engine = FakeEngine(
            matches = listOf(MlEngine.ClipMatch(7, 0.10f), MlEngine.ClipMatch(2, 0.30f)),
        )
        val repository = MagicSearchRepository(engine = engine)

        val result = repository.search("  beach at sunset  ", limit = 25)

        // The engine leg saw the TRIMMED text exactly once...
        assertEquals(listOf("beach at sunset"), engine.textCalls)
        // ...and the store search got EXACTLY the embedding the text
        // encoder returned, with the caller's limit (the ranking honors
        // the limit after the dedupe --- pinned in MagicSearchRankingTest):
        assertEquals(1, engine.searchCalls.size)
        assertEquals(25, engine.searchCalls.single().second)
        assertTrue(
            engine.searchCalls.single().first.contentEquals(
                FloatArray(512) { (it + "beach at sunset".length).toFloat() },
            ),
        )
        // The media leg (empty by default) leaves the honest no-matches
        // shape --- the composition above is what this test pins:
        assertEquals(MagicSearchResult.NoMatches, result)
    }

    @Test
    fun `zero matches are no-matches, never an error`() = runBlocking {
        val repository = MagicSearchRepository(engine = FakeEngine(matches = emptyList()))

        assertEquals(MagicSearchResult.NoMatches, repository.search("empty library"))
    }

    @Test
    fun `matches whose photos no longer resolve are no-matches - deleted between index and search`() = runBlocking {
        // The store matched two rows, but the media leg (vault-moved /
        // deleted photos) resolves neither: honest "no results", never a
        // fabricated row and never an error.
        val engine = FakeEngine(
            matches = listOf(MlEngine.ClipMatch(7, 0.10f), MlEngine.ClipMatch(2, 0.30f)),
        )
        val repository = MagicSearchRepository(engine = engine, queryMedia = { emptyList() })

        assertEquals(MagicSearchResult.NoMatches, repository.search("gone photos"))
    }

    // ---- the honest failure shapes ----

    @Test
    fun `an illegal-state engine failure becomes EngineFailure with the message verbatim`() = runBlocking {
        val repository = MagicSearchRepository(
            engine = FakeEngine(
                textFailure = IllegalStateException("nativeInitSemantic first: call initSemantic(modelsDir)"),
            ),
        )

        val result = repository.search("anything")

        assertEquals(
            MagicSearchResult.EngineFailure("nativeInitSemantic first: call initSemantic(modelsDir)"),
            result,
        )
    }

    @Test
    fun `an illegal-argument engine failure becomes EngineFailure with the message verbatim`() = runBlocking {
        val repository = MagicSearchRepository(
            engine = FakeEngine(
                searchFailure = IllegalArgumentException("query embedding must be 512 floats; got 511"),
            ),
        )

        assertEquals(
            MagicSearchResult.EngineFailure("query embedding must be 512 floats; got 511"),
            repository.search("anything"),
        )
    }

    @Test
    fun `cancellation propagates instead of becoming a failure result`() {
        val repository = MagicSearchRepository(
            engine = FakeEngine(textFailure = CancellationException("cancelled mid-embed")),
        )
        try {
            runBlocking { repository.search("anything") }
            throw AssertionError("cancellation must propagate, not fold into EngineFailure")
        } catch (expected: CancellationException) {
            assertEquals("cancelled mid-embed", expected.message)
        }
    }

    // ---- the relevance-order rule (the pure reorder) ----

    @Test
    fun `rankedMedia applies the ranked order over the media leg's own order and drops unresolvable ids`() {
        // The media leg returns rows in ITS order (newest-first in
        // production); the ranked ids are the DISPLAY order:
        val media = listOf(
            FakeRow(2, "newest-but-second-relevant"),
            FakeRow(8, "middle"),
            FakeRow(7, "most-relevant-but-older"),
        )
        val rankedFileIds = listOf(7L, 2L, 8L, 99L) // 99 no longer resolves

        val ordered = rankedMedia(rankedFileIds, media) { it.id }

        assertEquals(
            listOf(
                FakeRow(7, "most-relevant-but-older"),
                FakeRow(2, "newest-but-second-relevant"),
                FakeRow(8, "middle"),
            ),
            ordered,
        )
    }

    @Test
    fun `rankedMedia with nothing resolvable is empty - the NoMatches trigger`() {
        val ordered = rankedMedia(listOf(1L, 2L), listOf(FakeRow(3, "unrelated"))) { it.id }
        assertTrue(ordered.isEmpty())
    }

    // ---- the thin delegations ----

    @Test
    fun `clipCount and engineAvailable are the seam's own truths`() {
        val engine = FakeEngine(clipCount = 4321L, engineAvailable = true)
        val repository = MagicSearchRepository(engine = engine)
        assertEquals(4321L, runBlocking { repository.clipCount() })
        assertTrue(repository.engineAvailable)

        val missing = MagicSearchRepository(engine = FakeEngine(engineAvailable = false))
        assertFalse(missing.engineAvailable)
        assertEquals(0L, runBlocking { missing.clipCount() })
    }
}
