package io.github.dukeoftoad.gallery.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * JVM tests for [VaultStore.storeBatch] (r20 round; the r19-named
 * "vault batch store" follow-up): the ONE-rewrite batch seal cycle, the
 * per-item failure isolation, the whole-batch index-rewrite failure
     * semantics, and the entries the batch leaves behind.
 *
 * The `VaultStoreTest` fixture discipline, reused: a temp directory, a
 * locally generated AES key standing in for AndroidKeyStore, and the
 * documented on-disk layout (`pin.bin`, `index.bin`, `blobs/<id>.bin`).
 * The one PBKDF2-priced call per fixture is `setup`, exactly as in the
 * main suite.
 */
class VaultStoreBatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key: SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(VaultCrypto.KEY_BITS) }
        .generateKey()

    private fun newConfiguredStore(root: File): VaultStore {
        val store = VaultStore(root = root, keys = { key })
        store.setup("1234".toCharArray())
        return store
    }

    private fun item(
        payload: ByteArray,
        length: Long = payload.size.toLong(),
    ): VaultStore.BatchItem =
        VaultStore.BatchItem(
            displayName = "IMG_${payload.size}.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1_000L,
            bytes = ByteArrayInputStream(payload),
            length = length,
        )

    /** An input stream that throws on the first read: a broken source. */
    private class BrokenStream : InputStream() {
        override fun read(): Int = throw java.io.IOException("source vanished")
    }

    private fun brokenItem(): VaultStore.BatchItem =
        VaultStore.BatchItem(
            displayName = "broken.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1_000L,
            bytes = BrokenStream(),
            length = 10L,
        )

    @Test
    fun `a batch seals every item and lands one index rewrite`() {
        val root = tmp.newFolder()
        val store = newConfiguredStore(root)
        val payloads = listOf(
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5),
            byteArrayOf(6, 7, 8, 9),
        )

        val outcomes = store.storeBatch(payloads.map { item(it) })

        assertEquals(3, outcomes.size)
        val entries = outcomes.map { (it as VaultStore.BatchOutcome.Sealed).entry }
        // Distinct, insertion-ordered ids; actual streamed sizes recorded.
        assertEquals(listOf(1L, 2L, 3L), entries.map { it.id })
        assertEquals(listOf(3L, 2L, 4L), entries.map { it.sizeBytes })
        // The index carries exactly the batch, once.
        assertEquals(entries, store.entries())
        // Every blob exists on disk.
        val blobDir = File(root, "blobs")
        entries.forEach { assertTrue(File(blobDir, "${it.id}.bin").isFile) }
    }

    @Test
    fun `an empty batch is a no-op`() {
        val root = tmp.newFolder()
        val store = newConfiguredStore(root)

        val outcomes = store.storeBatch(emptyList())

        assertTrue(outcomes.isEmpty())
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `one broken item fails alone - its siblings still seal`() {
        val root = tmp.newFolder()
        val store = newConfiguredStore(root)
        val good = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))

        val outcomes = store.storeBatch(
            listOf(item(good[0]), brokenItem(), item(good[1])),
        )

        // Positional outcomes: sealed, failed, sealed.
        assertEquals(3, outcomes.size)
        assertTrue(outcomes[0] is VaultStore.BatchOutcome.Sealed)
        assertTrue(outcomes[1] is VaultStore.BatchOutcome.Failed)
        assertTrue(outcomes[2] is VaultStore.BatchOutcome.Sealed)
        // The index carries ONLY the two successes. The broken item's id
        // was claimed, abandoned (no entry, blob deleted) and then
        // RECLAIMED by the next item --- nextId is derived from the
        // entries that actually persisted, so a failed seal leaves no id
        // hole behind (the ids stay dense).
        val entries = store.entries()
        assertEquals(2, entries.size)
        assertEquals(listOf(1L, 2L), entries.map { it.id })
        // No orphan blob lingers for the broken item (its blob was
        // deleted on failure).
        val blobDir = File(root, "blobs")
        assertEquals(2, blobDir.listFiles()!!.size)
    }

    @Test
    fun `a batch over a pre-existing index appends without disturbing it`() {
        val root = tmp.newFolder()
        val store = newConfiguredStore(root)
        val first = store.store(
            displayName = "first.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1_000L,
            bytes = ByteArrayInputStream(byteArrayOf(1, 2)),
            length = 2L,
        )
        assertEquals(1L, first.id)

        val outcomes = store.storeBatch(listOf(item(byteArrayOf(3)), item(byteArrayOf(4, 5))))

        val entries = store.entries()
        assertEquals(3, entries.size)
        assertEquals(first, entries[0])
        assertEquals(2L, (outcomes[0] as VaultStore.BatchOutcome.Sealed).entry.id)
        assertEquals(3L, (outcomes[1] as VaultStore.BatchOutcome.Sealed).entry.id)
    }

    @Test
    fun `a batch before setup is refused`() {
        val root = tmp.newFolder()
        val store = VaultStore(root = root, keys = { key })

        assertThrows(IllegalStateException::class.java) {
            store.storeBatch(listOf(item(byteArrayOf(1))))
        }
    }
}
