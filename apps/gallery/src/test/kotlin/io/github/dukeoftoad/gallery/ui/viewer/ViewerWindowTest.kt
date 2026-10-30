package io.github.dukeoftoad.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [ViewerWindow] (issue #50).
 *
 * No emulator, no Robolectric (ADR-0016) --- `MediaItem` embeds
 * `android.net.Uri` and cannot be constructed on the plain JVM, so, exactly
 * like `WindowPagingSourceTest` (issue #28), the tests instantiate the
 * generic type with `List<Int>`: the clamping contract is type-agnostic and
 * production only substitutes `ViewerWindow<MediaItem>`.
 */
class ViewerWindowTest {

    @Test
    fun `in-range index is kept as the start page`() {
        val window = ViewerWindow.of(listOf(10, 20, 30, 40), 2)
        assertEquals(listOf(10, 20, 30, 40), window.items)
        assertEquals(2, window.startIndex)
    }

    @Test
    fun `negative index clamps to the first page`() {
        val window = ViewerWindow.of(listOf(10, 20, 30), -3)
        assertEquals(0, window.startIndex)
    }

    @Test
    fun `index past the end clamps to the last page`() {
        // The tapped item can disappear between the tap and the navigation
        // (MediaStore refresh rebuilt the snapshot); the viewer must land on
        // the last valid page, not crash.
        val window = ViewerWindow.of(listOf(10, 20, 30), 7)
        assertEquals(2, window.startIndex)
    }

    @Test
    fun `empty window clamps to zero and stays navigable`() {
        val window = ViewerWindow.of(emptyList<Int>(), 5)
        assertEquals(0, window.items.size)
        assertEquals(0, window.startIndex)
    }

    @Test
    fun `single-item window starts on page zero`() {
        val window = ViewerWindow.of(listOf(42), 0)
        assertEquals(0, window.startIndex)
    }

    @Test
    fun `window preserves the caller's item order`() {
        // The viewer pages newest-first, exactly as the grid showed them ---
        // no re-sorting happens between grid and viewer.
        val items = listOf(9, 7, 5, 3, 1)
        assertEquals(items, ViewerWindow.of(items, 0).items)
    }
}
