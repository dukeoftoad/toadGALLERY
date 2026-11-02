package io.github.dukeoftoad.gallery.data

import io.github.dukeoftoad.gallery.data.filters.TimelineFilterMediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure search query-building logic (issue #62): the
 * short-query gate, the LIKE escaping contract (`%`/`_`/backslash + the
 * `ESCAPE '\'` clause), the media-type selection variants, and the
 * sort/LIMIT clause.
 *
 * Runs in plain `testDebugUnitTest` (ADR-0016 --- the binding CI gate): no
 * emulator, no Robolectric, which is exactly why everything under test
 * lives in `SearchQuery.kt` free of android.* imports --- the Android
 * layer (`MediaStoreSource.querySearch`) hands the built
 * [SearchQuery.Spec] straight to `ContentResolver.query`.
 *
 * Since r19 the media-type pins also carry regression cross-checks: the
 * platform numbers (MEDIA_TYPE_IMAGE = 1, MEDIA_TYPE_AUDIO = 2,
 * MEDIA_TYPE_VIDEO = 3) are verified against the r17 filter engine's
 * JVM-pure constants, so the pre-r19 `2 == video` drift cannot come back.
 */
class SearchQueryTest {

    // ------------------------------------------------------------------
    // isSearchable: the short-query gate
    // ------------------------------------------------------------------

    @Test
    fun `empty and blank queries are not searchable`() {
        assertFalse(SearchQuery.isSearchable(""))
        assertFalse(SearchQuery.isSearchable("   "))
    }

    @Test
    fun `a single character is below the minimum length`() {
        assertFalse(SearchQuery.isSearchable("a"))
        assertFalse(SearchQuery.isSearchable(" a "))
    }

    @Test
    fun `two characters are searchable`() {
        assertTrue(SearchQuery.isSearchable("ab"))
    }

    @Test
    fun `the gate trims before measuring`() {
        // "  ab  " trims to the 2-character "ab" -> searchable.
        assertTrue(SearchQuery.isSearchable("  ab  "))
    }

    // ------------------------------------------------------------------
    // escapeLikePattern / likeArgument: the LIKE contract
    // ------------------------------------------------------------------

    @Test
    fun `plain text passes through unescaped`() {
        assertEquals("sunset", SearchQuery.escapeLikePattern("sunset"))
    }

    @Test
    fun `percent is escaped with the escape char`() {
        assertEquals("100\\%", SearchQuery.escapeLikePattern("100%"))
    }

    @Test
    fun `underscore is escaped with the escape char`() {
        assertEquals("\\_", SearchQuery.escapeLikePattern("_"))
    }

    @Test
    fun `backslash itself is escaped`() {
        assertEquals("\\\\", SearchQuery.escapeLikePattern("\\"))
    }

    @Test
    fun `a wildcard-heavy query escapes every special char`() {
        assertEquals(
            "a\\%b\\_c\\\\d",
            SearchQuery.escapeLikePattern("a%b_c\\d"),
        )
    }

    @Test
    fun `like argument trims then wraps in wildcards`() {
        assertEquals("%sunset%", SearchQuery.likeArgument("  sunset  "))
    }

    @Test
    fun `like argument escapes the typed wildcards`() {
        // A literal "100%" must match "100%", not "100<anything>".
        assertEquals("%100\\%%", SearchQuery.likeArgument("100%"))
    }

    @Test
    fun `like argument escapes an all-underscore query`() {
        assertEquals("%\\_\\_%", SearchQuery.likeArgument("__"))
    }

    // ------------------------------------------------------------------
    // mediaTypeClause: the All / Photos / Videos selection variants
    // ------------------------------------------------------------------

    @Test
    fun `ALL uses the images-videos IN list`() {
        // Same MEDIA_TYPE IN (1, 3) semantics as the timeline's unified
        // Files queries --- keeps downloads/documents (and audio) out of
        // results.
        assertEquals("media_type IN (1, 3)", SearchQuery.mediaTypeClause(SearchMediaType.ALL))
    }

    @Test
    fun `IMAGES selects media_type 1`() {
        assertEquals("media_type = 1", SearchQuery.mediaTypeClause(SearchMediaType.IMAGES))
    }

    @Test
    fun `VIDEOS selects media_type 3`() {
        // MEDIA_TYPE_VIDEO = 3 (2 is MEDIA_TYPE_AUDIO); r19 fixed the
        // pre-existing `= 2`, which selected audio rows and no videos.
        assertEquals("media_type = 3", SearchQuery.mediaTypeClause(SearchMediaType.VIDEOS))
    }

    @Test
    fun `the type numbers match the filter engine's constants`() {
        // Anti-drift cross-check: the r17 engine (TimelineFilterMediaStore)
        // holds JVM-pure constants of the same platform values, so if
        // either side ever changes alone, this pin breaks loudly.
        assertEquals(
            "media_type = ${TimelineFilterMediaStore.MEDIA_TYPE_IMAGE}",
            SearchQuery.mediaTypeClause(SearchMediaType.IMAGES),
        )
        assertEquals(
            "media_type = ${TimelineFilterMediaStore.MEDIA_TYPE_VIDEO}",
            SearchQuery.mediaTypeClause(SearchMediaType.VIDEOS),
        )
    }

    @Test
    fun `audio's number appears in no media-type clause`() {
        // Platform constants (MediaStore.Files.FileColumns):
        // MEDIA_TYPE_IMAGE = 1, MEDIA_TYPE_AUDIO = 2, MEDIA_TYPE_VIDEO = 3.
        // The pre-r19 literals read IN (1, 2) / `= 2` --- images+AUDIO ---
        // so audio files leaked into "All" results and the Videos chip
        // found no actual video. AUDIO's 2 must appear in NEITHER chip's
        // clause.
        SearchMediaType.entries.forEach { chip ->
            assertFalse(SearchQuery.mediaTypeClause(chip).contains("2"))
        }
    }

    // ------------------------------------------------------------------
    // sortOrder: newest-first total order + the LIMIT clause
    // ------------------------------------------------------------------

    @Test
    fun `sort order is newest first with the id tiebreak and default limit`() {
        assertEquals(
            "date_added DESC, _id DESC LIMIT 1000",
            SearchQuery.sortOrder(),
        )
    }

    @Test
    fun `sort order honors a custom limit`() {
        assertEquals(
            "date_added DESC, _id DESC LIMIT 50",
            SearchQuery.sortOrder(50),
        )
    }

    @Test
    fun `non-positive limits are clamped to 1`() {
        assertEquals(
            "date_added DESC, _id DESC LIMIT 1",
            SearchQuery.sortOrder(0),
        )
    }

    // ------------------------------------------------------------------
    // build: the whole Spec (selection + args + sort), per filter
    // ------------------------------------------------------------------

    @Test
    fun `build for ALL combines the IN clause with the escaped LIKE`() {
        assertEquals(
            SearchQuery.Spec(
                selection = "(media_type IN (1, 3)) AND _display_name LIKE ? ESCAPE '\\'",
                selectionArgs = listOf("%sunset%"),
                sortOrder = "date_added DESC, _id DESC LIMIT 1000",
            ),
            SearchQuery.build("sunset", SearchMediaType.ALL),
        )
    }

    @Test
    fun `build for IMAGES swaps in the single-type clause`() {
        assertEquals(
            SearchQuery.Spec(
                selection = "(media_type = 1) AND _display_name LIKE ? ESCAPE '\\'",
                selectionArgs = listOf("%dsc%"),
                sortOrder = "date_added DESC, _id DESC LIMIT 1000",
            ),
            SearchQuery.build("dsc", SearchMediaType.IMAGES),
        )
    }

    @Test
    fun `build for VIDEOS swaps in the video clause`() {
        assertEquals(
            SearchQuery.Spec(
                selection = "(media_type = 3) AND _display_name LIKE ? ESCAPE '\\'",
                selectionArgs = listOf("%mpg%"),
                sortOrder = "date_added DESC, _id DESC LIMIT 1000",
            ),
            SearchQuery.build("mpg", SearchMediaType.VIDEOS),
        )
    }

    @Test
    fun `build escapes wildcards in the bound argument`() {
        val spec = SearchQuery.build("100%", SearchMediaType.ALL)
        assertEquals(listOf("%100\\%%"), spec.selectionArgs)
        // The escape clause and the escaped pattern are one contract:
        // the selection must always carry ESCAPE '\'.
        assertTrue(spec.selection.endsWith("ESCAPE '\\'"))
    }

    @Test
    fun `build honors a custom limit in the sort order`() {
        val spec = SearchQuery.build("sunset", SearchMediaType.ALL, limit = 25)
        assertEquals("date_added DESC, _id DESC LIMIT 25", spec.sortOrder)
    }
}
