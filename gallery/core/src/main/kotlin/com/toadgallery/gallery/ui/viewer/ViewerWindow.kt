package com.toadgallery.gallery.ui.viewer

/**
 * The window of media the full-screen viewer pages over (issue #50), with the
 * page to open first.
 *
 * Deliberately a pure generic value type with zero Android imports: the
 * viewer's only nontrivial logic --- clamping a requested start index into
 * the bounds of the window the grid actually had loaded at tap time --- is
 * pinned by `ViewerWindowTest` on the plain JVM (`testDebugUnitTest`, no
 * emulator per ADR-0016). `MediaItem` embeds `android.net.Uri`, which a JVM
 * test cannot construct, so like `WindowPagingSource` (issue #28) the type is
 * generic and production simply instantiates `ViewerWindow<MediaItem>`.
 *
 * The window is a materialized snapshot of the grid's loaded items captured
 * at tap time (`LazyPagingItems.itemSnapshotList` filtered of placeholder
 * nulls, or the album detail list). It intentionally does NOT follow paging
 * afterwards: the viewer pages within what the user could already see, and a
 * later slice can grow it on demand. Keeping the tap-time window frozen also
 * makes the pager's page count stable for the lifetime of the screen.
 */
data class ViewerWindow<T>(
    val items: List<T>,
    /** A valid page index into [items]: `0` when empty, else `[0, items.size)`. */
    val startIndex: Int,
) {
    companion object {
        /**
         * Builds a window from the grid snapshot and the tapped position,
         * clamping `requestedIndex` into bounds. Every edge the grid can hand
         * over is safe by construction: a negative index, an index past the
         * end (the item was dropped from a fresh snapshot between the tap and
         * the navigation), or an empty window all clamp instead of throwing,
         * and [ViewerScreen] falls back to navigating up when the window is
         * empty.
         */
        fun <T> of(items: List<T>, requestedIndex: Int): ViewerWindow<T> {
            val last = items.size - 1
            return ViewerWindow(
                items = items,
                startIndex = if (last < 0) 0 else requestedIndex.coerceIn(0, last),
            )
        }
    }

    /**
     * The window with page [pageIndex] removed (issue #63: a viewer delete
     * drops the page of the item that left MediaStore), or `null` when
     * nothing remains --- the caller treats that as "close the viewer".
     *
     * The follow-up page is chosen for the one realistic caller shape: the
     * removed page IS the page being shown (the viewer only deletes the
     * current page). The item that FOLLOWED it slides into its index, so
     * the new [ViewerWindow.startIndex] is [pageIndex] when a successor
     * exists and the last remaining page otherwise --- never a blank jump.
     *
     * Pure like the rest of this type: `ViewerWindowTest` pins the
     * follow-up semantics on the plain JVM. The window otherwise stays the
     * frozen tap-time snapshot the class documents; rename deliberately
     * does NOT call this --- a MediaStore rename rewrites `DISPLAY_NAME`
     * only, the row `_id` and therefore the content URI are unchanged, so
     * the pager's URI-keyed pages stay valid as they are.
     */
    fun withoutPageAt(pageIndex: Int): ViewerWindow<T>? {
        if (pageIndex !in items.indices) return if (items.isEmpty()) null else this
        val remaining = items.filterIndexed { index, _ -> index != pageIndex }
        if (remaining.isEmpty()) return null
        return ViewerWindow(
            items = remaining,
            startIndex = if (pageIndex < remaining.size) pageIndex else remaining.lastIndex,
        )
    }
}
