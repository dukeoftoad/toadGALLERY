package com.toadgallery.editor

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The user-imported sticker store (r35; the `ImportedLutStoreTest`
 * conventions): PNG/WebP magic-before-write, verbatim bytes, derived
 * collision-suffixed ids that can never shadow the bundled set, the
 * listing-as-index, idempotent delete --- and a rejection leaving the
 * store byte-identical.
 */
class ImportedStickerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The directory handed to the most recent store() (one store per test). */
    private var lastDir: File? = null

    private fun store(): ImportedStickerStore {
        val dir = tmp.newFolder()
        lastDir = dir
        return ImportedStickerStore(dir)
    }

    /** A minimal payload with the PNG magic (the store never decodes images). */
    private fun png(bytes: Int = 64): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
            byteArrayOf(0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()) +
            ByteArray(bytes - 8) { 'x'.code.toByte() }

    /** A minimal payload with the WebP magic. */
    private fun webp(bytes: Int = 64): ByteArray =
        byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
        ) + ByteArray(4) { '0'.code.toByte() } +
            byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()) +
            ByteArray(bytes - 12) { 'y'.code.toByte() }

    // ---- Magic validation (parse-before-write) ----

    @Test
    fun `a PNG imports under a png extension`() {
        val store = store()
        val payload = png()
        val result = store.import(payload, "Beach Ball.png")
        assertTrue(result is ImportedStickerStore.ImportResult.Imported)
        val imported = (result as ImportedStickerStore.ImportResult.Imported).sticker
        assertEquals("beach-ball", imported.id)
        assertEquals("beach-ball.png", imported.fileName)
        // Verbatim: the stored bytes are the user's bytes.
        assertArrayEquals(payload, File(storeDir(store), imported.fileName).readBytes())
    }

    @Test
    fun `a WebP imports under a webp extension`() {
        val store = store()
        val result = store.import(webp(), "arrow.webp")
        assertTrue(result is ImportedStickerStore.ImportResult.Imported)
        assertEquals("arrow.webp", (result as ImportedStickerStore.ImportResult.Imported).sticker.fileName)
    }

    @Test
    fun `garbage is rejected before any write`() {
        val store = store()
        val before = store.list()
        val result = store.import(ByteArray(64) { 7 }, "not-an-image.png")
        assertTrue(result is ImportedStickerStore.ImportResult.Rejected)
        assertEquals(before, store.list()) // byte-identical: nothing written
    }

    @Test
    fun `a short payload cannot masquerade as WebP`() {
        val store = store()
        val result = store.import(ByteArray(8) { 'R'.code.toByte() }, "tiny.webp")
        assertTrue(result is ImportedStickerStore.ImportResult.Rejected)
    }

    @Test
    fun `an over-cap payload is rejected`() {
        val store = store()
        val oversized = png(ImportedStickerStore.MAX_BYTES + 1)
        val result = store.import(oversized, "poster.png")
        assertTrue(result is ImportedStickerStore.ImportResult.Rejected)
        assertEquals("the sticker is over the 2 MB cap", (result as ImportedStickerStore.ImportResult.Rejected).reason)
    }

    // ---- Id derivation + collisions ----

    @Test
    fun `ids derive from the file-name stem and slug like the LUT store`() {
        val store = store()
        val result = store.import(png(), "My Holiday 2026!!.png")
        assertEquals("my-holiday-2026", (result as ImportedStickerStore.ImportResult.Imported).sticker.id)
    }

    @Test
    fun `a stemless import falls back to the imported slug`() {
        val store = store()
        val result = store.import(png(), "???.png")
        assertEquals("imported", (result as ImportedStickerStore.ImportResult.Imported).sticker.id)
    }

    @Test
    fun `an import never shadows a bundled sticker`() {
        val store = store()
        // "Heart.png" would slug to "heart" --- a bundled id.
        val result = store.import(png(), "Heart.png")
        val imported = (result as ImportedStickerStore.ImportResult.Imported).sticker
        assertEquals("heart-2", imported.id) // collision-suffixed
        assertTrue(BundledStickers.byId("heart") != null) // the bundled set untouched
    }

    @Test
    fun `a same-named second import suffixes again`() {
        val store = store()
        store.import(png(), "Heart.png")   // heart-2
        val second = store.import(png(), "Heart.png")
        assertEquals("heart-3", (second as ImportedStickerStore.ImportResult.Imported).sticker.id)
    }

    @Test
    fun `a webp and a png of the same stem coexist`() {
        val store = store()
        store.import(png(), "dot.png")    // dot.png
        val second = store.import(webp(), "dot.webp")
        val imported = (second as ImportedStickerStore.ImportResult.Imported).sticker
        assertEquals("dot-2.webp", imported.fileName)
        assertEquals(2, store.list().size)
    }

    // ---- The listing as the index ----

    @Test
    fun `the listing is id-sorted and round-trips through the store`() {
        val store = store()
        store.import(png(), "zeta.png")
        store.import(webp(), "alpha.webp")
        store.import(png(), "mid.png")
        assertEquals(listOf("alpha", "mid", "zeta"), store.list().map { it.id })
    }

    @Test
    fun `a non-id-shaped file in the directory is ignored by the listing`() {
        val store = store()
        store.import(png(), "real.png")
        File(storeDir(store), "Not An Id.png").writeBytes(png())
        assertEquals(listOf("real"), store.list().map { it.id })
    }

    // ---- fileFor / isImported / delete ----

    @Test
    fun `fileFor resolves under either extension and isImported agrees`() {
        val store = store()
        store.import(webp(), "arrow.webp")
        assertTrue(store.isImported("arrow"))
        assertFalse(store.isImported("heart")) // bundled, not stored
        assertFalse(store.isImported("nope"))
        assertTrue(store.fileFor("arrow")!!.name.endsWith(".webp"))
        org.junit.Assert.assertNull(store.fileFor("nope"))
    }

    @Test
    fun `delete removes whichever extension the sticker stored under`() {
        val store = store()
        store.import(png(), "one.png")
        store.import(webp(), "two.webp")
        assertTrue(store.delete("one"))
        assertTrue(store.delete("two"))
        assertFalse(store.delete("one")) // idempotent: absent deletes false
        assertEquals(0, store.list().size)
        // A sidecar still naming the deleted sticker is SAFE: the recipe
        // keeps the id, the compositor renders nothing (the tolerance
        // contract); isImported now honestly reports false.
        assertFalse(store.isImported("one"))
    }

    @Test
    fun `delete rejects non-id-shaped ids without touching the tree`() {
        val store = store()
        store.import(png(), "keep.png")
        assertFalse(store.delete("../keep"))
        assertEquals(listOf("keep"), store.list().map { it.id })
    }

    // ---- The store never rewrites a rejection ----

    @Test
    fun `a rejected import leaves the store byte-identical`() {
        val store = store()
        store.import(png(), "keep.png")
        // Byte arrays compare by identity under equals(); map to List<Byte>
        // for content comparison (the TemporaryFolder tree, nothing else).
        val snapshot: (File) -> List<Pair<String, List<Byte>>> = { dir ->
            dir.listFiles()!!.map { it.name to it.readBytes().toList() }
        }
        val before = snapshot(storeDir(store))
        store.import(ByteArray(32) { 9 }, "bad.png")
        store.import(png(ImportedStickerStore.MAX_BYTES + 1), "big.png")
        val after = snapshot(storeDir(store))
        assertEquals(before.sortedBy { it.first }, after.sortedBy { it.first })
    }

    private fun storeDir(store: ImportedStickerStore): File {
        // The store's directory is the folder it was constructed with;
        // recorded by store() at creation (one store per test).
        return lastDir!!
    }
}
