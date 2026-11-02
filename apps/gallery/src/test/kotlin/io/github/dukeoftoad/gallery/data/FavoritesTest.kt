package io.github.dukeoftoad.gallery.data

import io.github.dukeoftoad.gallery.data.filters.TimelineFilterMediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [Favorites] rules (issue #66): the favorites
 * query's selection/sort SQL text (pinned exactly, the `SearchQuery`
 * convention) and the sentinel bucket-id resolution the album-detail route
 * relies on. Plain JVM per ADR-0016 --- no emulator, no Robolectric.
 *
 * Since r19 the selection pins also carry the media-type regression
 * cross-checks: the platform numbers (MEDIA_TYPE_IMAGE = 1,
 * MEDIA_TYPE_AUDIO = 2, MEDIA_TYPE_VIDEO = 3) are verified against the
 * r17 filter engine's JVM-pure constants, so the pre-r19 `2 == video`
 * drift cannot come back.
 */
class FavoritesTest {

    @Test
    fun `selection filters to images and videos flagged favorite`() {
        // Platform constants (MediaStore.Files.FileColumns):
        // MEDIA_TYPE_IMAGE = 1, MEDIA_TYPE_AUDIO = 2, MEDIA_TYPE_VIDEO = 3;
        // images+videos is therefore IN (1, 3).
        assertEquals(
            "media_type IN (1, 3) AND is_favorite = 1",
            Favorites.FAVORITES_SELECTION,
        )
    }

    @Test
    fun `the media-type clause mirrors the timeline semantics`() {
        // 1/3 are MEDIA_TYPE_IMAGE/MEDIA_TYPE_VIDEO; the clause must keep
        // non-media Files rows (downloads, documents) out of the favorites.
        assertTrue(Favorites.FAVORITES_SELECTION.contains("media_type IN (1, 3)"))
    }

    @Test
    fun `the selection numbers match the filter engine's constants`() {
        // Anti-drift cross-check: the r17 engine (TimelineFilterMediaStore)
        // holds JVM-pure constants of the same platform values, so if
        // either side ever changes alone, this pin breaks loudly.
        assertEquals(
            "media_type IN (" +
                "${TimelineFilterMediaStore.MEDIA_TYPE_IMAGE}, " +
                "${TimelineFilterMediaStore.MEDIA_TYPE_VIDEO}) AND is_favorite = 1",
            Favorites.FAVORITES_SELECTION,
        )
    }

    @Test
    fun `audio is not favorites material`() {
        // MEDIA_TYPE_AUDIO = 2: the pre-r19 selection read IN (1, 2) ---
        // images+AUDIO --- so a favorited audio row would have surfaced in
        // the pseudo-album while every actual video was missed. Pin the
        // bug's shape out: audio's 2 belongs in NO media_type slot of the
        // selection.
        assertFalse(Favorites.FAVORITES_SELECTION.contains("(1, 2)"))
        assertFalse(Favorites.FAVORITES_SELECTION.contains("= 2"))
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
