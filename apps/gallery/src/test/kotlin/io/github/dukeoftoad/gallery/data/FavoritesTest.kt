package io.github.dukeoftoad.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [Favorites] rules (issue #66): the favorites
 * query's selection/sort SQL text (pinned exactly, the `SearchQuery`
 * convention) and the sentinel bucket-id resolution the album-detail route
 * relies on. Plain JVM per ADR-0016 --- no emulator, no Robolectric.
 */
class FavoritesTest {

    @Test
    fun `selection filters to images and videos flagged favorite`() {
        assertEquals(
            "media_type IN (1, 2) AND is_favorite = 1",
            Favorites.FAVORITES_SELECTION,
        )
    }

    @Test
    fun `the media-type clause mirrors the timeline semantics`() {
        // 1/2 are MEDIA_TYPE_IMAGE/MEDIA_TYPE_VIDEO; the clause must keep
        // non-media Files rows (downloads, documents) out of the favorites.
        assertTrue(Favorites.FAVORITES_SELECTION.contains("media_type IN (1, 2)"))
    }

    @Test
    fun `sort order is newest first with the id tiebreak`() {
        assertEquals(
            "date_added DESC, _id DESC",
            Favorites.FAVORITES_SORT_ORDER,
        )
    }

    @Test
    fun `the column literal equals the IS_FAVORITE constant value`() {
        // MediaStore.MediaColumns.IS_FAVORITE is an inlined API-30 String
        // constant; the literal keeps this file JVM-pure AND keeps the
        // pre-R projections lint-clean (see the object KDoc).
        assertEquals("is_favorite", Favorites.COLUMN_IS_FAVORITE)
    }

    @Test
    fun `the sentinel resolves only itself`() {
        assertTrue(Favorites.isFavoritesBucket(Favorites.FAVORITES_BUCKET_ID))
    }

    @Test
    fun `zero is not the sentinel`() {
        // 0 is already reserved by the route's render guard
        // (`bucketId != null && bucketId != 0L`), so the sentinel must be a
        // DIFFERENT value the guard still lets through.
        assertFalse(Favorites.isFavoritesBucket(0L))
    }

    @Test
    fun `real bucket ids and other negatives are not the sentinel`() {
        assertFalse(Favorites.isFavoritesBucket(1L))
        assertFalse(Favorites.isFavoritesBucket(6_371_394_013L)) // typical path-hash shaped id
        assertFalse(Favorites.isFavoritesBucket(-2L))
    }

    @Test
    fun `the sentinel is negative so the route guard passes it`() {
        // The album-detail navigation renders only for bucketId != null &&
        // bucketId != 0L; the sentinel rides the same Long argument.
        assertTrue(Favorites.FAVORITES_BUCKET_ID != 0L)
    }
}
