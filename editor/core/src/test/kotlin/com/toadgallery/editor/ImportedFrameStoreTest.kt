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
 * The user-imported frame store: PNG/WebP magic-before-write, verbatim bytes,
 * collision-suffixed ids that never shadow the bundled set, listing-as-index, idempotent delete.
 */
class ImportedFrameStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var lastDir: File? = null

    private fun store(): ImportedFrameStore {
        val dir = tmp.newFolder()
        lastDir = dir
        return ImportedFrameStore(dir)
    }

    private fun png(bytes: Int = 64): ByteArray =
        byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
            byteArrayOf(0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()) +
            ByteArray(bytes - 8) { 'x'.code.toByte() }

    private fun webp(bytes: Int = 64): ByteArray =
        byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()) +
            ByteArray(4) { '0'.code.toByte() } +
            byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()) +
            ByteArray(bytes - 12) { 'y'.code.toByte() }

    @Test
    fun `a PNG imports under a png extension, verbatim`() {
        val store = store()
        val payload = png()
        val result = store.import(payload, "Ornate Border.png")
        val imported = (result as ImportedFrameStore.ImportResult.Imported).frame
        assertEquals("ornate-border", imported.id)
        assertEquals("ornate-border.png", imported.fileName)
        assertArrayEquals(payload, File(lastDir!!, imported.fileName).readBytes())
    }

    @Test
    fun `a WebP imports under a webp extension`() {
        val store = store()
        val result = store.import(webp(), "vignette.webp")
        assertEquals("vignette.webp", (result as ImportedFrameStore.ImportResult.Imported).frame.fileName)
    }

    @Test
    fun `garbage and short payloads are rejected before any write`() {
        val store = store()
        assertTrue(store.import(ByteArray(64) { 7 }, "bad.png") is ImportedFrameStore.ImportResult.Rejected)
        assertTrue(store.import(ByteArray(8) { 'R'.code.toByte() }, "tiny.webp") is ImportedFrameStore.ImportResult.Rejected)
        assertEquals(emptyList<ImportedFrameStore.ImportedFrame>(), store.list())
    }

    @Test
    fun `an over-cap payload is rejected`() {
        val store = store()
        val result = store.import(png(ImportedFrameStore.MAX_BYTES + 1), "poster.png")
        assertTrue(result is ImportedFrameStore.ImportResult.Rejected)
        assertEquals("the frame is over the 2 MB cap", (result as ImportedFrameStore.ImportResult.Rejected).reason)
    }

    @Test
    fun `an import never shadows a bundled frame`() {
        val store = store()
        // "Film.png" would slug to "film" --- a bundled id.
        val result = store.import(png(), "Film.png")
        assertEquals("film-2", (result as ImportedFrameStore.ImportResult.Imported).frame.id)
        assertTrue(BundledFrames.byId("film") != null) // the bundled set untouched
    }

    @Test
    fun `a same-named second import suffixes again and the listing sorts`() {
        val store = store()
        store.import(png(), "Film.png") // film-2
        store.import(webp(), "Film.webp") // film-3
        store.import(png(), "aa.png")
        assertEquals(listOf("aa", "film-2", "film-3"), store.list().map { it.id })
    }

    @Test
    fun `delete removes whichever extension and is idempotent`() {
        val store = store()
        store.import(webp(), "one.webp")
        assertTrue(store.delete("one"))
        assertFalse(store.delete("one"))
        assertFalse(store.isImported("one"))
        assertEquals(0, store.list().size)
    }

    @Test
    fun `delete rejects non-id-shaped ids without touching the tree`() {
        val store = store()
        store.import(png(), "keep.png")
        assertFalse(store.delete("../keep"))
        assertEquals(listOf("keep"), store.list().map { it.id })
    }

    @Test
    fun `a rejected import leaves the store byte-identical`() {
        val store = store()
        store.import(png(), "keep.png")
        val snapshot: (File) -> List<Pair<String, List<Byte>>> = { dir ->
            dir.listFiles()!!.map { it.name to it.readBytes().toList() }
        }
        val before = snapshot(lastDir!!)
        store.import(ByteArray(32) { 9 }, "bad.png")
        store.import(png(ImportedFrameStore.MAX_BYTES + 1), "big.png")
        assertEquals(before.sortedBy { it.first }, snapshot(lastDir!!).sortedBy { it.first })
    }
}
