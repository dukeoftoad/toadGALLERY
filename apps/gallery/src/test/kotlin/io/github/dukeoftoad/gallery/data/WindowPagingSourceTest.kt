package io.github.dukeoftoad.gallery.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [WindowPagingSource] (Phase 3, issue #28).
 *
 * These run on the JVM inside `testDebugUnitTest` --- no emulator, no
 * Robolectric. That is exactly why the source is generic: production
 * instantiates it as `WindowPagingSource<MediaItem>`, but `MediaItem`
 * embeds `android.net.Uri`, which a plain JVM test cannot construct. Here
 * the window backend is a 250-element `List<Int>` sliced with the same
 * `[offset, offset + limit)` contract as `MediaStoreSource.queryTimelineWindow`,
 * so every rule below also pins the contract the MediaStore loader must honor.
 *
 * The Paging 3 core types ([PagingSource], [PagingState], [PagingConfig]) are
 * pure JVM classes from `paging-common`, so nothing below trips the unit-test
 * "not mocked" android wall; loads run on the test thread via `runBlocking`
 * (kotlinx-coroutines-test is deliberately not a dependency of this project).
 * LoadParams is built through the public `Refresh`/`Append` constructors ---
 * the `LoadParams.refresh(...)`/`append(...)` companion factories only exist
 * from Paging 3.4, and this project pins 3.3.2.
 */
class WindowPagingSourceTest {

    private val pageSize = 50

    /** Fake window backend with the exact slice contract of the MediaStore loader. */
    private fun slicingLoader(
        dataset: List<Int> = (0 until 250).toList(),
    ): suspend (offset: Int, limit: Int) -> List<Int> =
        { offset, limit -> dataset.drop(offset).take(limit) }

    /** Loads through [PagingSource.load] and unwraps the expected Page, failing on Error. */
    @Suppress("UNCHECKED_CAST") // the Page<*, *> probe guarantees the payload type
    private fun loadPage(
        source: WindowPagingSource<Int>,
        params: PagingSource.LoadParams<Int>,
    ): PagingSource.LoadResult.Page<Int, Int> = runBlocking {
        val result = source.load(params)
        val page = result as? PagingSource.LoadResult.Page<*, *>
            ?: error("Expected LoadResult.Page, got $result")
        page as PagingSource.LoadResult.Page<Int, Int>
    }

    @Test
    fun `refresh at key 0 returns the first window`() {
        val source = WindowPagingSource(slicingLoader())
        val page = loadPage(
            source,
            PagingSource.LoadParams.Refresh(key = null, loadSize = pageSize, placeholdersEnabled = false),
        )
        assertEquals("window [0, 50)", (0 until pageSize).toList(), page.data)
        assertNull("key 0 is the head of the timeline: nothing to prepend", page.prevKey)
        assertEquals("full window opens the next one at offset 50", 50, page.nextKey)
        assertEquals("positional before-count is the window offset", 0, page.itemsBefore)
    }

    @Test
    fun `append at a window boundary returns the next window`() {
        val source = WindowPagingSource(slicingLoader())
        val page = loadPage(
            source,
            PagingSource.LoadParams.Append(key = 50, loadSize = pageSize, placeholdersEnabled = false),
        )
        assertEquals("window [50, 100)", (50 until 100).toList(), page.data)
        assertEquals("previous window starts one load size back", 0, page.prevKey)
        assertEquals("the stream continues at offset 100", 100, page.nextKey)
        assertEquals(50, page.itemsBefore)
    }

    @Test
    fun `a final partial window ends the stream`() {
        val source = WindowPagingSource(slicingLoader())
        // 250 rows, 70 requested: the last window holds only the 40 remaining.
        val page = loadPage(
            source,
            PagingSource.LoadParams.Append(key = 210, loadSize = 70, placeholdersEnabled = false),
        )
        assertEquals((210 until 250).toList(), page.data)
        assertNull("short window == end-of-list signal: no next key", page.nextKey)
        assertEquals("clamped one window back", 140, page.prevKey)
    }

    @Test
    fun `append past the end returns an empty terminal window`() {
        val source = WindowPagingSource(slicingLoader())
        // 250 == a whole number of 50-row windows: exhaustion surfaces as an
        // empty window whose next key is null.
        val page = loadPage(
            source,
            PagingSource.LoadParams.Append(key = 250, loadSize = pageSize, placeholdersEnabled = false),
        )
        assertEquals(emptyList<Int>(), page.data)
        assertNull(page.nextKey)
    }

    @Test
    fun `an empty dataset yields an empty page with no next key`() {
        val source = WindowPagingSource(slicingLoader(emptyList()))
        val page = loadPage(
            source,
            PagingSource.LoadParams.Refresh(key = null, loadSize = pageSize, placeholdersEnabled = false),
        )
        assertEquals("clean library, not an error", emptyList<Int>(), page.data)
        assertNull(page.prevKey)
        assertNull(page.nextKey)
    }

    @Test
    fun `a throwing loader surfaces as a LoadResult Error`() {
        runBlocking {
            val boom = IllegalStateException("cursor exploded")
            val source = WindowPagingSource<Int> { _, _ -> throw boom }
            val result = source.load(
                PagingSource.LoadParams.Refresh(key = null, loadSize = pageSize, placeholdersEnabled = false),
            )
            val error = result as? PagingSource.LoadResult.Error<*, *>
                ?: error("Expected LoadResult.Error, got $result")
            assertEquals("the loader's failure is passed through for the UI retry path", boom, error.throwable)
        }
    }

    @Test
    fun `a cancelled load propagates CancellationException instead of becoming an Error`() {
        val marker = CancellationException("load cancelled by Paging")
        val source = WindowPagingSource<Int> { _, _ -> throw marker }
        var rethrown: CancellationException? = null
        // Catch INSIDE runBlocking: the identity of the escaping exception
        // must be checked at the call site, without relying on how
        // runBlocking itself propagates cancellation out of the coroutine.
        runBlocking {
            try {
                source.load(
                    PagingSource.LoadParams.Refresh(key = null, loadSize = pageSize, placeholdersEnabled = false),
                )
            } catch (cancellation: CancellationException) {
                rethrown = cancellation
            }
        }
        assertTrue(
            "load() must rethrow the loader's CancellationException unwrapped for Paging's " +
                "load cancellation, never convert it to a LoadResult.Error",
            rethrown === marker,
        )
    }

    @Test
    fun `getRefreshKey snaps the anchor to the window lattice`() {
        val source = WindowPagingSource(slicingLoader())
        fun stateAt(anchor: Int, size: Int) = PagingState<Int, Int>(
            pages = emptyList(),
            anchorPosition = anchor,
            config = PagingConfig(pageSize = size, enablePlaceholders = false),
            leadingPlaceholderCount = 0,
        )
        // 120 sits inside the 50-row window [100, 150): the refresh restarts
        // at the window start 100 --- never at the mid-window 120 - 50 = 70
        // the old `anchor - pageSize` arithmetic produced (a key like that
        // makes the prepend chain overlap loaded ranges; duplicate grid keys).
        assertEquals("120 floors to the start of its 50-row window", 100, source.getRefreshKey(stateAt(120, 50)))
        // 130 inside a 60-row window --- the production page size: [120, 180).
        assertEquals("130 floors to the start of its 60-row window", 120, source.getRefreshKey(stateAt(130, 60)))
        // 59 sits inside the head window [0, 60): floor to the aligned head.
        assertEquals("59 floors into the head window", 0, source.getRefreshKey(stateAt(59, 60)))
        // An anchor exactly on a window start is already lattice-aligned.
        assertEquals("0 is its own window's start", 0, source.getRefreshKey(stateAt(0, 50)))
    }

    @Test
    fun `getRefreshKey returns 0 without an anchor and never below the head`() {
        val source = WindowPagingSource(slicingLoader())
        val config = PagingConfig(pageSize = pageSize, enablePlaceholders = false)
        val noAnchor = PagingState<Int, Int>(emptyList(), null, config, 0)
        assertEquals("no anchor: restart the timeline at the head", 0, source.getRefreshKey(noAnchor))
        val nearTop = PagingState<Int, Int>(emptyList(), 30, config, 0)
        assertEquals("30 floors into the head window [0, 50)", 0, source.getRefreshKey(nearTop))
        assertTrue("the refresh key is never negative", source.getRefreshKey(nearTop) >= 0)
    }
}
