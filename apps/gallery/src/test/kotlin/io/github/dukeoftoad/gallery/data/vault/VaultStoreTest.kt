package io.github.dukeoftoad.gallery.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * JVM tests for [VaultStore] (issue #32, ADR-0017).
 *
 * The store is pure JVM by design (`java.io` + the [VaultCrypto] seam), so
 * the EXACT production on-disk layout --- sealed PIN record, sealed index,
 * id-named sealed blobs, crash-safety orderings, the integrity sweep ---
 * runs under `testDebugUnitTest` against a temp directory and a locally
 * generated AES-256 key (no AndroidKeyStore, no emulator; ADR-0016). The
 * layout the tests reach into (`pin.bin`, `index.bin`, `blobs/<id>.bin`)
 * is the documented contract from the [VaultStore] KDoc.
 *
 * PBKDF2 budget: every [VaultStore.setup]/[VaultStore.verifyPin] call costs
 * one 210k-round derivation (~0.1-0.3 s JVM), so the suite derives about a
 * dozen times total instead of once per assertion.
 *
 * Privacy (ADR-0010/ADR-0014): everything here stays inside the temp
 * directory; the app holds no INTERNET permission, so no vault byte can
 * ever leave a device.
 */
class VaultStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The vault's media key in tests: locally generated, standing in for AndroidKeyStore. */
    private val key: SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(VaultCrypto.KEY_BITS) }
        .generateKey()

    /** A second, unrelated key: what a wiped/re-provisioned Keystore looks like. */
    private val otherKey: SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(VaultCrypto.KEY_BITS) }
        .generateKey()

    // ---- Fixtures ------------------------------------------------------

    /** A store plus its root directory, so tests can reach the documented files. */
    private class Fixture(val root: File, val store: VaultStore) {
        val blobDir: File get() = File(root, "blobs")
        fun blobOf(id: Long): File = File(blobDir, "$id.bin")
    }

    private fun newFixture(): Fixture {
        val root = tmp.newFolder()
        return Fixture(root, VaultStore(root = root, keys = { key }))
    }

    private fun configuredFixture(): Fixture =
        newFixture().apply { store.setup(PIN.toCharArray()) }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    // ---- Setup + PIN ---------------------------------------------------

    @Test
    fun `a fresh store is not configured and its sweep is a no-op`() {
        val fixture = newFixture()
        assertFalse(fixture.store.isConfigured())
        assertEquals(0, fixture.store.sweepOrphans())
    }

    @Test
    fun `setup persists the PIN record and a sealed empty index`() {
        val fixture = configuredFixture()
        assertTrue(fixture.store.isConfigured())
        assertTrue(fixture.store.entries().isEmpty())
    }

    @Test
    fun `verifyPin accepts the setup pin and rejects a wrong one`() {
        val fixture = configuredFixture()
        assertTrue(fixture.store.verifyPin(PIN.toCharArray()))
        assertFalse(fixture.store.verifyPin("0000".toCharArray()))
    }

    @Test
    fun `double setup is rejected and the original pin still rules`() {
        val fixture = configuredFixture()
        // The documented behavior (VaultStore.setup KDoc): re-running setup
        // must never silently swap the PIN under an existing vault.
        assertThrows(IllegalStateException::class.java) {
            fixture.store.setup("9999".toCharArray())
        }
        assertTrue(fixture.store.verifyPin(PIN.toCharArray()))
        assertFalse(fixture.store.verifyPin("9999".toCharArray()))
    }

    // ---- Store / list / open -------------------------------------------

    @Test
    fun `store lists the entry with metadata intact and open streams back the exact bytes`() {
        val fixture = configuredFixture()
        val payload = randomBytes(300_000)
        val entry = fixture.store.store(
            displayName = DISPLAY_NAME,
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = ADDED_AT,
            bytes = ByteArrayInputStream(payload),
            // Deliberately wrong: the store records the ACTUAL streamed size.
            length = 999_999L,
        )
        val entries = fixture.store.entries()
        assertEquals(listOf(entry), entries)
        assertEquals(DISPLAY_NAME, entries.single().displayName)
        assertEquals("image/jpeg", entries.single().mimeType)
        assertEquals(payload.size.toLong(), entries.single().sizeBytes)
        assertEquals(ADDED_AT, entries.single().addedAtMillis)
        assertFalse(entries.single().isVideo)
        val opened = fixture.store.open(entry).use { it.readBytes() }
        assertTrue("open() must stream back the exact plaintext", payload.contentEquals(opened))
    }

    @Test
    fun `an id freed by delete may be reused without collision`() {
        val fixture = configuredFixture()
        val first = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(4)),
            length = 4L,
        )
        fixture.store.delete(first)
        val second = fixture.store.store(
            displayName = "b.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 2L,
            bytes = ByteArrayInputStream(ByteArray(4)),
            length = 4L,
        )
        // The documented allocation rule (VaultIndex.nextId): one past the
        // CURRENT maximum, so a deleted tail id is re-allocated. That is
        // collision-free --- delete() removed the blob first, and any crash
        // orphan left behind is reclaimed by the sweep or safely overwritten.
        assertEquals(first.id, second.id)
        val opened = fixture.store.open(second).use { it.readBytes() }
        assertEquals(4, opened.size)
    }

    @Test
    fun `file names and raw bytes never leak the display name`() {
        val fixture = configuredFixture()
        val secretName = "very-private-leaf.jpg"
        fixture.store.store(
            displayName = secretName,
            mimeType = "image/png",
            isVideo = true,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(16)),
            length = 16L,
        )
        // Blob files are named by stable id, never by content (ADR-0017).
        assertEquals(listOf("1.bin"), fixture.blobDir.list()!!.toList())
        // The index is sealed: its raw bytes must not contain the name.
        val rawIndex = File(fixture.root, "index.bin").readBytes()
        assertFalse(String(rawIndex, Charsets.ISO_8859_1).contains(secretName))
    }

    // ---- Tamper / truncation -------------------------------------------

    @Test
    fun `a tampered blob fails authentication while reading`() {
        val fixture = configuredFixture()
        val payload = randomBytes(64 * 1024)
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        val blob = fixture.blobOf(entry.id)
        val sealed = blob.readBytes()
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 1).toByte()
        blob.writeBytes(sealed)
        assertThrows(Exception::class.java) {
            fixture.store.open(entry).use { it.readBytes() }
        }
    }

    @Test
    fun `a truncated blob fails authentication while reading`() {
        val fixture = configuredFixture()
        val payload = randomBytes(64 * 1024)
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        val blob = fixture.blobOf(entry.id)
        val sealed = blob.readBytes()
        // Half the blob: the IV survives, the GCM tag does not.
        blob.writeBytes(sealed.copyOfRange(0, sealed.size / 2))
        assertThrows(Exception::class.java) {
            fixture.store.open(entry).use { it.readBytes() }
        }
    }

    // ---- Crash safety + sweep ------------------------------------------

    @Test
    fun `a failed seal leaves no index entry and no blob`() {
        val fixture = configuredFixture()
        assertThrows(IOException::class.java) {
            fixture.store.store(
                displayName = "a.jpg",
                mimeType = "image/jpeg",
                isVideo = false,
                addedAtMillis = 1L,
                bytes = ExplodingInputStream(),
                length = 100L,
            )
        }
        assertTrue(fixture.store.entries().isEmpty())
        val blobs = fixture.blobDir.list()
        assertTrue(blobs != null && blobs.isEmpty())
    }

    @Test
    fun `an orphan blob with no index entry is swept away`() {
        val fixture = configuredFixture()
        val payload = randomBytes(1024)
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        // The crash shape from ADR-0017: a sealed blob whose index write
        // never landed.
        val orphan = File(fixture.blobDir, "42.bin").apply { writeBytes(ByteArray(32)) }
        assertEquals(1, fixture.store.sweepOrphans())
        assertFalse(orphan.exists())
        // The indexed blob survives untouched.
        assertTrue(fixture.blobOf(entry.id).isFile)
        assertEquals(listOf(entry), fixture.store.entries())
    }

    @Test
    fun `the sweep also reclaims stale tmp files`() {
        val fixture = configuredFixture()
        val stale = File(fixture.root, "index.bin.tmp").apply { writeBytes(ByteArray(8)) }
        assertEquals(1, fixture.store.sweepOrphans())
        assertFalse(stale.exists())
    }

    // ---- Delete / restoreOut -------------------------------------------

    @Test
    fun `delete removes the index entry and the blob file`() {
        val fixture = configuredFixture()
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(8)),
            length = 8L,
        )
        fixture.store.delete(entry)
        assertTrue(fixture.store.entries().isEmpty())
        assertFalse(fixture.blobOf(entry.id).exists())
        // Deleting an unknown id is a loud failure, never a silent no-op.
        assertThrows(VaultStoreException::class.java) { fixture.store.delete(entry) }
    }

    @Test
    fun `restoreOut streams the plaintext and deliberately keeps the entry`() {
        val fixture = configuredFixture()
        val payload = "restore me".toByteArray()
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        val sink = ByteArrayOutputStream()
        fixture.store.restoreOut(entry, sink)
        assertTrue(payload.contentEquals(sink.toByteArray()))
        // ADR-0017 restore order: the caller deletes via delete() only after
        // the plaintext landed durably --- restoreOut must not pre-delete.
        assertEquals(listOf(entry), fixture.store.entries())
        assertTrue(fixture.blobOf(entry.id).isFile)
    }

    // ---- Wipe / wrong key ----------------------------------------------

    @Test
    fun `wipe destroys the pin record, the index and every blob`() {
        val fixture = configuredFixture()
        fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(8)),
            length = 8L,
        )
        fixture.store.wipe()
        assertFalse(fixture.store.isConfigured())
        assertFalse(fixture.root.exists())
        assertThrows(VaultStoreException::class.java) { fixture.store.entries() }
    }

    @Test
    fun `a store under a different key cannot read the sealed state`() {
        val fixture = configuredFixture()
        fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(8)),
            length = 8L,
        )
        // What a lost AndroidKeyStore key looks like (ADR-0017: factory
        // reset makes the vault permanently unopenable): the files are all
        // still there, but every read fails authentication.
        val stranger = VaultStore(root = fixture.root, keys = { otherKey })
        assertThrows(VaultStoreException::class.java) { stranger.entries() }
        assertThrows(VaultStoreException::class.java) { stranger.verifyPin(PIN.toCharArray()) }
    }

    // ---- Helpers -------------------------------------------------------

    /** Emits a few bytes, then dies: the shape of a provider stream failing mid-copy. */
    private class ExplodingInputStream(private val goodBytes: Int = 10) : InputStream() {
        private var served = 0

        override fun read(): Int =
            if (served < goodBytes) {
                served++
                0x41
            } else {
                throw IOException("provider stream died mid-copy")
            }
    }

    private companion object {
        const val PIN = "8642"
        const val DISPLAY_NAME = "IMG_20261030_101010.jpg"
        const val ADDED_AT = 1_768_000_000_000L
    }
}
