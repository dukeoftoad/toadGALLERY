package io.github.dukeoftoad.gallery.ui.viewer

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
}
