package io.github.dukeoftoad.gallery.data.filters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the derived collections (r17): the honest-subset invariants
 * that keep the Albums Collections section from ever shipping a lying chip,
 * the preset semantics, and the API-level visibility rule.
 */
class DerivedCollectionsTest {

    // ------------------------------------------------------------------
    // The honest-subset invariants (surfacing rule, as code)
    // ------------------------------------------------------------------

    @Test
    fun `every preset is serveable by the catalogue path`() {
        // A preset the catalogue cannot serve (a bucket dimension) would
        // lie during warm starts (the catalogue drives the timeline there),
        // so this invariant is the build-time guard for the surfaced set.
        DerivedCollections.all.forEach { collection ->
            assertTrue(
                "preset ${collection.id} must pass canServe",
                TimelineFilterCatalogue.canServe(collection.filter),
            )
        }
    }

    @Test
    fun `every preset projects literal-only onto MediaStore (no bound args)`() {
        // Surfaced filters stay arg-free even though the Bundle protocol
        // supports args: the enum dimensions are vocabulary, not data, and
        // keeping them literal means the windowed query needs only the
        // selection-string key the timeline has always used.
        DerivedCollections.all.forEach { collection ->
            val projection = TimelineFilterMediaStore.selection(collection.filter)
            assertTrue(
                "preset ${collection.id} must not bind args",
                projection.selectionArgs.isEmpty(),
            )
        }
    }

    @Test
    fun `ids are unique and cover exactly the known set`() {
        val ids = DerivedCollections.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            listOf(
                DerivedCollections.ID_PHOTOS,
                DerivedCollections.ID_VIDEOS,
                DerivedCollections.ID_GIFS,
                DerivedCollections.ID_FAVORITES,
            ),
            ids,
        )
    }

    // ------------------------------------------------------------------
    // Preset semantics (what each card promises)
    // ------------------------------------------------------------------

    @Test
    fun `each preset selects what its name claims`() {
        fun preset(id: String) = DerivedCollections.all.first { it.id == id }

        fun fields(
            mime: String,
            isVideo: Boolean,
            isFavorite: Boolean = false,
        ) = TimelineFilterFields(
            mimeType = mime,
            isVideo = isVideo,
            isFavorite = isFavorite,
            dateAddedSec = 0L,
        )

        val photos = preset(DerivedCollections.ID_PHOTOS).filter
        assertTrue(photos.matches(fields("image/jpeg", isVideo = false)))
        assertFalse(photos.matches(fields("video/mp4", isVideo = true)))

        val videos = preset(DerivedCollections.ID_VIDEOS).filter
        assertTrue(videos.matches(fields("video/mp4", isVideo = true)))
        assertFalse(videos.matches(fields("image/jpeg", isVideo = false)))

        val gifs = preset(DerivedCollections.ID_GIFS).filter
        assertTrue(gifs.matches(fields("image/gif", isVideo = false)))
        assertFalse(gifs.matches(fields("image/jpeg", isVideo = false)))

        val favorites = preset(DerivedCollections.ID_FAVORITES).filter
        assertTrue(favorites.matches(fields("image/jpeg", isVideo = false, isFavorite = true)))
        assertTrue(favorites.matches(fields("video/mp4", isVideo = true, isFavorite = true)))
        assertFalse(favorites.matches(fields("image/jpeg", isVideo = false, isFavorite = false)))
    }

    // ------------------------------------------------------------------
    // API-level visibility (the favorites column is Q+)
    // ------------------------------------------------------------------

    @Test
    fun `visibleOn keeps everything from Q up, in display order`() {
        assertEquals(DerivedCollections.all, DerivedCollections.visibleOn(29))
        assertEquals(DerivedCollections.all, DerivedCollections.visibleOn(36))
    }

    @Test
    fun `visibleOn drops only favorites below Q and preserves the order`() {
        val visible = DerivedCollections.visibleOn(26)
        assertEquals(
            listOf(
                DerivedCollections.ID_PHOTOS,
                DerivedCollections.ID_VIDEOS,
                DerivedCollections.ID_GIFS,
            ),
            visible.map { it.id },
        )
    }
}
