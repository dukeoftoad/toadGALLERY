package io.github.dukeoftoad.gallery.ui.viewer

import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for the pure [ViewerInfoFormat] half of the viewer info sheet
 * (issue #63). No emulator, no Robolectric (ADR-0016) --- like
 * `FileOpNamingTest`/`DurationFormatsTest`, the contract here is plain
 * string math over simple parameters, with every "unknown" sentinel
 * ([MediaItem][io.github.dukeoftoad.gallery.data.MediaItem] documents `0`
 * and null lookups as unknown) pinned to `null`.
 */
class ViewerInfoTest {

    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun pinUtc() {
        // DateFormat formats in the JVM default zone; pinning it keeps the
        // date test deterministic on any CI runner.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    // ---- display name / name-from-URI ----

    @Test
    fun `provider display name wins when present`() {
        assertEquals(
            "IMG_1234.jpg",
            ViewerInfoFormat.displayName(
                providerName = "IMG_1234.jpg",
                uriString = "content://media/external/images/media/42",
            ),
        )
    }

    @Test
    fun `blank provider name falls back to the uri segment`() {
        assertEquals(
            "beach.jpg",
            ViewerInfoFormat.displayName(
                providerName = "  ",
                uriString = "content://some/provider/beach.jpg",
            ),
        )
    }

    @Test
    fun `no provider name and a row-id uri stays unknown`() {
        assertNull(
            ViewerInfoFormat.displayName(
                providerName = null,
                uriString = "content://media/external/video/media/123",
            ),
        )
    }

    @Test
    fun `name from uri strips query and fragment`() {
        assertEquals(
            "clip.mp4",
            ViewerInfoFormat.nameFromUri("content://provider/clip.mp4?x=1#frag"),
        )
    }

    @Test
    fun `all-digit uri segment is a row id, not a name`() {
        assertNull(ViewerInfoFormat.nameFromUri("content://media/external/images/media/9"))
    }

    @Test
    fun `empty or trailing-slash uri yields no name`() {
        assertNull(ViewerInfoFormat.nameFromUri(""))
        assertNull(ViewerInfoFormat.nameFromUri("content://provider/"))
    }

    // ---- dimensions ----

    @Test
    fun `known dimensions render as width x height`() {
        assertEquals("4032 × 3024", ViewerInfoFormat.dimensions(4032, 3024))
    }

    @Test
    fun `zero width or height means unknown dimensions`() {
        // MediaItem documents 0 as "unknown" (missing MediaStore column).
        assertNull(ViewerInfoFormat.dimensions(0, 3024))
        assertNull(ViewerInfoFormat.dimensions(4032, 0))
        assertNull(ViewerInfoFormat.dimensions(0, 0))
    }

    // ---- duration ----

    @Test
    fun `video duration reuses the grid overlay format`() {
        assertEquals("1:24", ViewerInfoFormat.duration(84_000L, isVideo = true))
        assertEquals("1:00:00", ViewerInfoFormat.duration(3_600_000L, isVideo = true))
    }

    @Test
    fun `photos and unknown durations stay unknown`() {
        assertNull(ViewerInfoFormat.duration(84_000L, isVideo = false))
        assertNull(ViewerInfoFormat.duration(0L, isVideo = true))
    }

    // ---- size ----

    @Test
    fun `size reuses the recycle-bin byte format`() {
        assertEquals("4.5 KB", ViewerInfoFormat.size(4_500L))
        assertEquals("340 MB", ViewerInfoFormat.size(340_000_000L))
    }

    @Test
    fun `missing or non-positive size stays unknown`() {
        assertNull(ViewerInfoFormat.size(null))
        assertNull(ViewerInfoFormat.size(0L))
        assertNull(ViewerInfoFormat.size(-5L))
    }

    // ---- date added ----

    @Test
    fun `date added renders through the medium date short time format`() {
        // 2025-01-12T02:40:00Z, pinned via the UTC default above.
        assertEquals(
            "Jan 12, 2025, 2:40 AM",
            ViewerInfoFormat.dateAdded(1_736_649_600L, Locale.US),
        )
    }

    @Test
    fun `unknown date added sentinel stays unknown`() {
        assertNull(ViewerInfoFormat.dateAdded(0L, Locale.US))
        assertNull(ViewerInfoFormat.dateAdded(-1L, Locale.US))
    }
}
