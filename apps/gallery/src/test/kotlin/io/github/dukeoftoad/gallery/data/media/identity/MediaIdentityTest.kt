package io.github.dukeoftoad.gallery.data.media.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 4 identity contract (`docs/phase-4/phase-4-contract-audit-and-
 * ente-ai-plan.md`): the local media key must round-trip, distinguish
 * volumes with colliding `_id`s, and refuse garbage instead of crashing.
 */
class MediaIdentityTest {

    private fun identity(
        volume: String = "external_primary",
        id: Long = 42,
        page: String? = null,
    ) = MediaIdentity(
        volumeName = volume,
        mediaStoreId = id,
        canonicalUri = "content://media/external/$volume/images/$id",
        pageId = page,
    )

    @Test
    fun `media key round-trips`() {
        val key = identity().toKeyString()
        assertEquals("external_primary:42", key)
        assertEquals(42L, MediaIdentity.fromKeyString(key)!!.mediaStoreId)
        assertEquals("external_primary", MediaIdentity.fromKeyString(key)!!.volumeName)
    }

    @Test
    fun `same id on different volumes produces different keys`() {
        val a = identity(volume = "external_primary", id = 7)
        val b = identity(volume = "0123-4567", id = 7)
        assertTrue(a.toKeyString() != b.toKeyString())
        assertEquals("0123-4567", MediaIdentity.fromKeyString(b.toKeyString())!!.volumeName)
    }

    @Test
    fun `page identity round-trips and stays distinct from its parent`() {
        val page = identity(page = "tiff:2")
        val parent = identity(page = null)
        assertEquals("external_primary:42:tiff%3A2", page.toKeyString())
        assertTrue(page.toKeyString() != parent.toKeyString())
        val parsed = MediaIdentity.fromKeyString(page.toKeyString())!!
        assertEquals("tiff:2", parsed.pageId)
        assertNull(parsed.copy(pageId = null).pageId)
    }

    @Test
    fun `volume names containing the separator are percent-encoded safely`() {
        val odd = identity(volume = "we:ird")
        val parsed = MediaIdentity.fromKeyString(odd.toKeyString())!!
        assertEquals("we:ird", parsed.volumeName)
    }

    @Test
    fun `malformed keys are rejected without throwing`() {
        assertNull(MediaIdentity.fromKeyString(""))
        assertNull(MediaIdentity.fromKeyString("only-one-part"))
        assertNull(MediaIdentity.fromKeyString("external_primary"))
        assertNull(MediaIdentity.fromKeyString("external_primary:not-a-number"))
        assertNull(MediaIdentity.fromKeyString("external_primary:1:2:3:4:5"))
        assertNull(MediaIdentity.fromKeyString(":42"))
    }
}
