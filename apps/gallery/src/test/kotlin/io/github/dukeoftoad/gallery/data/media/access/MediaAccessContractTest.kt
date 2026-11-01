package io.github.dukeoftoad.gallery.data.media.access

import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Phase 4 access CONTRACT (`docs/phase-4/phase-4-contract-audit-and-
 * ente-ai-plan.md`, change 5): [MediaAccess]/[MediaReadHandle] cannot be
 * exercised against a real ContentResolver on the JVM (ADR-0016 keeps
 * the suite emulator-free), so these tests pin the parts that MUST NOT
 * drift, against in-file fakes:
 *
 *  - the vault gate: only an [EligiblePublicMedia] can be handed to
 *    [MediaAccess] --- and only `PUBLIC_INDEXABLE` eligibility can mint
 *    one, so vault/trashed/revoked/unsupported/corrupt media are
 *    unrepresentable at the call site (compile-level, plus the unwrap
 *    identity check so the wrapper cannot silently lose its payload);
 *  - the byte-cap semantics of a well-behaved [MediaReadHandle]:
 *    payload larger than the cap -> null, exactly-sized cap -> the
 *    bytes, and the default cap is the documented 64 MiB.
 */
class MediaAccessContractTest {

    private fun identity(id: Long = 42): MediaIdentity =
        MediaIdentity(
            volumeName = "external_primary",
            mediaStoreId = id,
            canonicalUri = "content://media/external/external_primary/images/$id",
        )

    private fun eligible(id: Long = 42): EligiblePublicMedia =
        MediaEligibility.PUBLIC_INDEXABLE.asEligible(identity(id))
            ?: error("PUBLIC_INDEXABLE eligibility must always mint a wrapper")

    // ------------------------------------------------------------------
    // The vault gate: eligibility -> wrapper -> access
    // ------------------------------------------------------------------

    @Test
    fun `the eligible wrapper unwraps to exactly the identity it vouches for`() {
        val wrapper: EligiblePublicMedia = eligible(id = 4242)
        assertEquals(4242L, wrapper.identity.mediaStoreId)
        assertEquals("external_primary", wrapper.identity.volumeName)
        assertEquals(
            "content://media/external/external_primary/images/4242",
            wrapper.identity.canonicalUri,
        )
        assertEquals("external_primary:4242", wrapper.identity.toKeyString())
    }

    @Test
    fun `no non-public eligibility state can mint an eligible wrapper`() {
        val media: MediaIdentity = identity()
        val excluded = listOf(
            MediaEligibility.VAULT_EXCLUDED,
            MediaEligibility.TRASHED,
            MediaEligibility.PERMISSION_REVOKED,
            MediaEligibility.unsupported("image/x-tiled-tiff"),
            MediaEligibility.corrupt("exif"),
        )
        for (eligibility in excluded) {
            assertNull(
                "state ${eligibility.state} must never produce an eligible wrapper",
                eligibility.asEligible(media),
            )
        }
    }

    @Test
    fun `media access consumes the wrapper and yields a handle for its identity`() = runBlocking {
        val access = FakeMediaAccess()
        val handle: MediaReadHandle = access.open(eligible(id = 7))
            ?: error("an eligible item must open")
        val bytes: ByteArray = handle.readBytes() ?: error("the fake handle always yields bytes")
        assertEquals(FakeMediaAccess.PAYLOAD_BYTES, bytes.size)
        assertEquals(1, access.opened.size)
        assertEquals(7L, access.opened.single().mediaStoreId)
    }

    // ------------------------------------------------------------------
    // The byte-cap semantics of a well-behaved handle
    // ------------------------------------------------------------------

    @Test
    fun `read handle refuses a cap below the payload size`() = runBlocking {
        val handle: MediaReadHandle = FakeMediaReadHandle(ByteArray(FakeMediaAccess.PAYLOAD_BYTES))
        assertNull(handle.readBytes(maxBytes = 10))
        assertNull(handle.readBytes(maxBytes = (FakeMediaAccess.PAYLOAD_BYTES - 1).toLong()))
    }

    @Test
    fun `read handle returns the payload at exactly its size`() = runBlocking {
        val payload = ByteArray(FakeMediaAccess.PAYLOAD_BYTES) { (it % 251).toByte() }
        val handle: MediaReadHandle = FakeMediaReadHandle(payload)
        assertArrayEquals(payload, handle.readBytes(maxBytes = payload.size.toLong()))
    }

    @Test
    fun `the default cap is 64 MiB and covers ordinary stills`() = runBlocking {
        assertEquals(64L * 1024 * 1024, MediaReadHandle.DEFAULT_MAX_BYTES)
        val handle: MediaReadHandle = FakeMediaReadHandle(ByteArray(FakeMediaAccess.PAYLOAD_BYTES))
        // readBytes() with no argument uses DEFAULT_MAX_BYTES.
        val bytes: ByteArray = handle.readBytes() ?: error("default cap covers the payload")
        assertEquals(FakeMediaAccess.PAYLOAD_BYTES, bytes.size)
    }

    @Test
    fun `a handle whose payload exceeds the default cap returns null by contract`() = runBlocking {
        val oversized = ByteArray(MediaReadHandle.DEFAULT_MAX_BYTES.toInt() + 1)
        val handle: MediaReadHandle = FakeMediaReadHandle(oversized)
        assertNull(handle.readBytes())
    }
}

/**
 * JVM fake of [MediaAccess]: proves the interface is implementable
 * without any Android type, records what it opened, and hands back a
 * [FakeMediaReadHandle] --- exactly what the future indexing
 * coordinator's tests will do.
 */
private class FakeMediaAccess : MediaAccess {

    val opened: MutableList<MediaIdentity> = mutableListOf()

    override suspend fun open(media: EligiblePublicMedia): MediaReadHandle? {
        opened += media.identity
        return FakeMediaReadHandle(ByteArray(PAYLOAD_BYTES))
    }

    companion object {
        /** 100 bytes: small enough to reason about, larger than the tiny caps used below. */
        const val PAYLOAD_BYTES: Int = 100
    }
}

/**
 * JVM fake of [MediaReadHandle] honoring the cap contract: the payload
 * comes back whole only when it fits [maxBytes]; anything larger is a
 * null, mirroring the production seam's refuse-don't-truncate rule.
 */
private class FakeMediaReadHandle(private val payload: ByteArray) : MediaReadHandle {

    override suspend fun readBytes(maxBytes: Long): ByteArray? =
        if (payload.size.toLong() > maxBytes) null else payload.copyOf()
}
