package io.github.dukeoftoad.gallery.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException

/**
 * A positional, windowed [PagingSource] over any list-shaped backend
 * (Phase 3, issue #28).
 *
 * ## Paging model
 * Keys are *offsets into the full result list*, not item ids: `0` is the
 * start of the timeline (newest item) and a load asks [windowLoader] for
 * the fixed window `[key, key + loadSize)`. Consecutive windows therefore
 * tile the list exactly, with no gaps and no overlaps, as long as the
 * loader honors the slice contract --- see [queryTimelineWindow] for the
 * MediaStore implementation that makes windows deterministic (a stable
 * `date_added DESC, _id DESC` total order; without the `_id` tiebreak,
 * rows sharing one `date_added` second could reorder at a window boundary
 * and get skipped or duplicated between pages).
 *
 * A **short window signals the end of the list**: when the loader returns
 * fewer rows than requested, the remaining rows are the last ones and
 * `nextKey` becomes `null`, ending the append stream. The loader contract
 * is thus "return exactly `min(limit, remaining)` items, newest-first".
 *
 * ## Why this class is generic
 * The production instantiation is `WindowPagingSource<MediaItem>`, but
 * `MediaItem` embeds `android.net.Uri`, which plain JVM unit tests cannot
 * construct without Robolectric (the android.jar on the unit-test
 * classpath throws "not mocked" on `Uri` construction). Keeping the source
 * generic decouples the paging math --- keys, window boundaries, end
 * detection, refresh anchoring --- from the payload type:
 * `WindowPagingSourceTest` exercises all of it over a plain `List<Int>`
 * with zero android dependencies, while production wraps MediaStore rows.
 *
 * ## Count fields ([androidx.paging.PagingSource.LoadResult.Page])
 * `itemsBefore` is set to the window offset (the exact positional count,
 * which is free here) because Paging uses the before-counts to translate a
 * viewport position into the global `anchorPosition` that [getRefreshKey]
 * consumes; the positional math would silently degrade without it.
 * `itemsAfter` is deliberately left at the library's unspecified default:
 * the windowed protocol has no cheap total count (it would need a separate
 * `COUNT(*)` query per page), and the Pager config disables placeholders,
 * so nothing in the UI consumes it.
 *
 * ## Integration notes (PhotosViewModel + PhotosScreen slice)
 *  - Set `initialLoadSize` **equal to `pageSize`** in the `PagingConfig`.
 *    `initialLoadSize` defaults to `pageSize * 3`; a larger first window
 *    makes `prevKey` of the *second* window point into the middle of the
 *    first one, so a prepend triggered by fast upward scrolling would
 *    re-emit rows as duplicates. Equal sizes keep the windows aligned.
 *  - Keep `enablePlaceholders = false` (see the count-fields note above).
 *  - The loader is a `suspend` function invoked from Paging's load
 *    dispatchers; production wraps the blocking MediaStore cursor walk in
 *    `withContext(Dispatchers.IO)` inside the loader lambda.
 *
 * Privacy (ADR-0014): the source never touches the network; it only turns
 * loader callbacks into pages. URI discipline (ADR-0006) is inherited from
 * whatever the loader returns.
 *
 * @param windowLoader returns the window `[offset, offset + limit)` of the
 *  result list, or fewer rows (possibly none) when the list ends earlier.
 *  Must return rows in the single stable list order for every call;
 *  throwing fails the load gracefully (see [load]).
 */
class WindowPagingSource<T : Any>(
    private val windowLoader: suspend (offset: Int, limit: Int) -> List<T>,
) : PagingSource<Int, T>() {

    /**
     * Loads one window. `params.key` is `null` on refresh, which means the
     * head of the timeline (offset `0`); appends/prepends carry the previous
     * window boundary as the key.
     *
     * Failures are funneled into [PagingSource.LoadResult.Error] so the UI
     * can surface a retry; one exception is deliberately *not* swallowed:
     * [CancellationException] must propagate, because Paging cancels loads
     * routinely (fast scrolling, refreshes) and converting that into an
     * error would show spurious "timeline failed" states.
     */
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val start = params.key ?: 0
        val window = try {
            windowLoader(start, params.loadSize)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return LoadResult.Error(failure)
        }
        return LoadResult.Page(
            data = window,
            // Offset 0 is the head of the timeline: nothing older to prepend.
            prevKey = if (start == 0) null else maxOf(0, start - params.loadSize),
            // A short window is the end-of-list signal; a full window's next
            // boundary is exactly `start + window.size` (== start + loadSize).
            nextKey = if (window.size < params.loadSize) null else start + window.size,
            // Exact positional count; see the class KDoc for why it matters.
            itemsBefore = start,
        )
    }

    /**
     * After a refresh (permission re-grant, timeline invalidation), re-lands
     * the user where they were: one page above the last accessed position,
     * clamped at the head. Without an anchor (first ever load) the timeline
     * restarts at `0`.
     */
    override fun getRefreshKey(state: PagingState<Int, T>): Int =
        state.anchorPosition?.let { anchor -> maxOf(0, anchor - state.config.pageSize) } ?: 0
}
