package io.github.dukeoftoad.gallery.ui.albums

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for [moveTargets] (issue #64): the album-detail move picker's
 * exclusion of the bucket currently on screen.
 *
 * These run on the JVM inside `testDebugUnitTest` --- no emulator, no
 * Robolectric. That is exactly why [moveTargets] is generic over the
 * element: production instantiates it with `Album`, which embeds
 * `android.net.Uri` a plain JVM test cannot construct (same trick as
 * `WindowPagingSourceTest`), so the rule below pins the CONTRACT with a
 * lightweight fake and `bucketIdOf = { it.bucketId }`.
 */
class MoveTargetsTest {

    /** Stand-in for `Album` carrying exactly the identity the rule reads. */
    private data class FakeAlbum(val bucketId: Long, val bucketName: String)

    private val albums = listOf(
        FakeAlbum(1, "Camera"),
        FakeAlbum(42, "Screenshots"),
        FakeAlbum(7, "Download"),
        FakeAlbum(99, "Pictures"),
    )

    @Test
    fun `null passes through unchanged`() {
        // "Still loading" must stay distinguishable from "no targets":
        // the dialog renders a spinner for null, an empty list otherwise.
        assertNull(
            moveTargets<FakeAlbum>(null, currentBucketId = 42, bucketIdOf = { it.bucketId }),
        )
    }

    @Test
    fun `the current bucket is excluded`() {
        assertEquals(
            listOf("Camera", "Download", "Pictures"),
            moveTargets(albums, currentBucketId = 42) { it.bucketId }
                ?.map { it.bucketName },
        )
    }

    @Test
    fun `order is preserved`() {
        // The picker mirrors `MediaStoreSource.queryAlbums` ordering
        // (newest-first); the filter must not re-sort it.
        assertEquals(
            listOf(1L, 7L, 99L),
            moveTargets(albums, currentBucketId = 42) { it.bucketId }
                ?.map { it.bucketId },
        )
    }

    @Test
    fun `an all-current list filters to empty, not null`() {
        val onlyCurrent = listOf(FakeAlbum(42, "Screenshots"))
        assertEquals(
            emptyList<FakeAlbum>(),
            moveTargets(onlyCurrent, currentBucketId = 42) { it.bucketId },
        )
    }

    @Test
    fun `a current bucket not present in the list is a no-op`() {
        // Cross-bucket staleness: the picker re-queries on every open, but
        // the bucket id comes from the navigation argument --- if the
        // provider no longer knows it, every album stays a legal target.
        assertEquals(
            albums,
            moveTargets(albums, currentBucketId = 1000) { it.bucketId },
        )
    }
}
