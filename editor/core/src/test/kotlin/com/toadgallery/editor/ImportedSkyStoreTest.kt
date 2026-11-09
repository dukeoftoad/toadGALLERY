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
 * The user-imported sky store (r41; the `ImportedFrameStoreTest` shape,
 * sky-sized): PNG/WebP magic-before-write, verbatim bytes, derived
 * collision-suffixed ids that can never shadow the bundled set, the
 * listing-as-index, idempotent delete --- and a rejection leaving the
 * store byte-identical.
 */
class ImportedSkyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var lastDir: File? = null

    private fun store(): ImportedSkyStore {
        val dir = tmp.newFolder()
        lastDir = dir
        return ImportedSkyStore(dir)
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
        val result = store.import(payload, "Storm Front.png")
        val imported = (result as ImportedSkyStore.ImportResult.Imported).sky
        assertEquals("storm-front", imported.id)
        assertEquals("storm-front.png", imported.fileName)
        assertArrayEquals(payload, File(lastDir!!, imported.fileName).readBytes())
    }

    @Test
    fun `a WebP imports under a webp extension`() {
        val store = store()
        val result = store.import(webp(), "sunset.webp")
        assertEquals("sunset.webp", (result as ImportedSkyStore.ImportResult.Imported).sky.fileName)
    }

    @Test
    fun `garbage and short payloads are rejected before any write`() {
        val store = store()
        assertTrue(store.import(ByteArray(64) { 7 }, "bad.png") is ImportedSkyStore.ImportResult.Rejected)
        assertTrue(store.import(ByteArray(8) { 'R'.code.toByte() }, "tiny.webp") is ImportedSkyStore.ImportResult.Rejected)
        assertEquals(emptyList<ImportedSkyStore.ImportedSky>(), store.list())
    }

    @Test
    fun `an over-cap payload is rejected`() {
        val store = store()
        val result = store.import(png(ImportedSkyStore.MAX_BYTES + 1), "poster.png")
        assertTrue(result is ImportedSkyStore.ImportResult.Rejected)
        assertEquals("the sky is over the 2 MB cap", (result as ImportedSkyStore.ImportResult.Rejected).reason)
    }

    @Test
    fun `an import never shadows a bundled sky`() {
        val store = store()
        // "Dusk.png" would slug to "dusk" --- a bundled id.
        val result = store.import(png(), "Dusk.png")
        assertEquals("dusk-2", (result as ImportedSkyStore.ImportResult.Imported).sky.id)
        assertTrue(BundledSkies.byId("dusk") != null) // the bundled set untouched
    }

    @Test
    fun `a same-named second import suffixes again and the listing sorts`() {
        val store = store()
        store.import(png(), "Dusk.png") // dusk-2
        store.import(webp(), "Dusk.webp") // dusk-3
        store.import(png(), "aa.png")
        assertEquals(listOf("aa", "dusk-2", "dusk-3"), store.list().map { it.id })
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
        store.import(png(ImportedSkyStore.MAX_BYTES + 1), "big.png")
        assertEquals(before.sortedBy { it.first }, snapshot(lastDir!!).sortedBy { it.first })
    }
}
