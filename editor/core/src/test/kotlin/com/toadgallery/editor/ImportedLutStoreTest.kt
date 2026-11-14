package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The user-imported `.cube` store: parse-before-write import, collision-free ids,
 * directory-listing-as-index, and delete semantics.
 */
class ImportedLutStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The store's directory for this test (newFolder() names are random). */
    private lateinit var dir: File

    private fun store(): ImportedLutStore {
        dir = tmp.newFolder()
        return ImportedLutStore(dir)
    }

    /** A small valid look: the size-2 identity cube with a title. */
    private fun cubeBytes(title: String?): ByteArray = buildString {
        if (title != null) appendLine("TITLE \"$title\"")
        appendLine("LUT_3D_SIZE 2")
        for (b in 0..1) for (g in 0..1) for (r in 0..1) appendLine("$r $g $b")
    }.toByteArray()

    private fun imported(result: ImportedLutStore.ImportResult): ImportedLutStore.ImportedLut =
        when (result) {
            is ImportedLutStore.ImportResult.Imported -> result.lut
            is ImportedLutStore.ImportResult.Rejected ->
                throw AssertionError("expected an import, got: ${result.reason}")
        }

    // ---- import ----

    @Test
    fun `an import stores the bytes verbatim and lists with the title`() {
        val store = store()
        val entry = imported(store.import(cubeBytes("My Holiday Look"), suggestedName = null))
        assertEquals("my-holiday-look", entry.id)
        assertEquals("My Holiday Look", entry.title)
        // The stored file is the picked bytes, verbatim.
        val stored = dir.resolve("${entry.id}.cube")
        assertTrue(stored.exists())
        assertTrue(stored.readBytes().contentEquals(cubeBytes("My Holiday Look")))
        assertEquals(listOf(entry), store.list())
    }

    @Test
    fun `a missing title falls back to the suggested file name stem`() {
        val store = store()
        val entry = imported(store.import(cubeBytes(title = null), suggestedName = "Beach 2026.cube"))
        assertEquals("beach-2026", entry.id)
        assertEquals("beach-2026", entry.title) // the title falls back to the id
    }

    @Test
    fun `an invalid cube is rejected and the store stays unchanged`() {
        val store = store()
        val result = store.import("not a cube at all".toByteArray(), suggestedName = "x")
        assertTrue(result is ImportedLutStore.ImportResult.Rejected)
        assertTrue(store.list().isEmpty())
        assertTrue(store.loadAll().isEmpty())
        // No partial file, no tmp leftover.
        assertEquals(0, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `weird titles slug to clean ids and empties fall back`() {
        assertEquals("my-look-2", ImportedLutStore.slugId("  My  LOOK!! 2  "))
        assertEquals("imported", ImportedLutStore.slugId("!!!"))
        assertEquals("imported", ImportedLutStore.slugId(""))
        assertEquals("a".repeat(40), ImportedLutStore.slugId("a".repeat(80)))
    }

    @Test
    fun `re-importing the same look suffixes the id`() {
        val store = store()
        val first = imported(store.import(cubeBytes("Same Look"), null))
        val second = imported(store.import(cubeBytes("Same Look"), null))
        assertEquals("same-look", first.id)
        assertEquals("same-look-2", second.id)
        assertEquals(2, store.list().size)
    }

    @Test
    fun `an import can never shadow a bundled look id`() {
        val store = store()
        // The bundled registry owns "warm-film"; a clashing import id is suffixed (keys stay disjoint).
        val entry = imported(store.import(cubeBytes("Warm Film"), null))
        assertEquals("warm-film-2", entry.id)
        assertNull(BundledLuts.byId(entry.id)) // not a bundled id
        assertNotNull(BundledLuts.byId("warm-film")) // the bundled one is untouched
    }

    // ---- load / persistence ----

    @Test
    fun `load parses the stored look under its id and loadAll merges them all`() {
        val store = store()
        store.import(cubeBytes("Alpha"), null)
        store.import(cubeBytes("Beta"), null)
        val alpha = store.load("alpha")
        assertNotNull(alpha)
        assertEquals("alpha", alpha!!.id)
        assertEquals("Alpha", alpha.title)
        assertEquals(setOf("alpha", "beta"), store.loadAll().keys.toSet())
    }

    @Test
    fun `the store survives its own recreation --- process death`() {
        val dir = tmp.newFolder()
        ImportedLutStore(dir).import(cubeBytes("Persistent"), null)
        val reopened = ImportedLutStore(dir)
        assertEquals(1, reopened.list().size)
        assertEquals("Persistent", reopened.load("persistent")?.title)
    }

    @Test
    fun `a corrupt stored file degrades to absent, never a crash`() {
        val store = store()
        imported(store.import(cubeBytes("Later Broken"), null))
        dir.resolve("later-broken.cube").writeText("garbage")
        assertNull(store.load("later-broken"))
        assertTrue(store.isImported("later-broken")) // the file exists...
        assertFalse(store.loadAll().containsKey("later-broken")) // ...but parses to nothing
        assertEquals("later-broken", store.list().first().title) // the listing falls back to the id
    }

    // ---- delete ----

    @Test
    fun `delete removes exactly one look and stays idempotent`() {
        val store = store()
        store.import(cubeBytes("Keep"), null)
        store.import(cubeBytes("Drop"), null)
        assertTrue(store.delete("drop"))
        assertFalse(store.isImported("drop"))
        assertTrue(store.isImported("keep"))
        assertEquals(listOf("keep"), store.list().map { it.id })
        assertFalse(store.delete("drop")) // idempotent
        assertFalse(store.delete("never-existed"))
        assertFalse(store.delete("../escape-attempt")) // id-shaped paths only
    }
}
