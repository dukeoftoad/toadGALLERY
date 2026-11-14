package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Bundled-look conformance: registry self-consistency plus the committed .cube assets
 * parsing under their ids with exact hand-verified values (grid-exact probes, 1e-5 tolerance).
 */
class BundledLutsTest {

    // ---- Registry ----

    @Test
    fun `the registry is unique, ordered and self-consistent`() {
        assertEquals(5, BundledLuts.ORDERED.size)
        val ids = BundledLuts.ORDERED.map { it.id }
        assertTrue("ids must be unique", ids.size == ids.toSet().size)
        val files = BundledLuts.ORDERED.map { it.fileName }
        assertTrue("file names must be unique", files.size == files.toSet().size)
        for (entry in BundledLuts.ORDERED) {
            assertEquals("${entry.id}.cube", entry.fileName)
            assertEquals(entry, BundledLuts.byId(entry.id))
            assertEquals("luts/${entry.fileName}", BundledLuts.assetPath(entry))
        }
        assertNull(BundledLuts.byId("no-such-look"))
    }

    // ---- The committed assets ----

    private fun assetsDir(): File {
        val candidates = listOf(
            File("src/main/assets/luts"), // working dir = the module (Gradle unit tests)
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("bundled-LUT assets not found; tried ${candidates.map { it.path }}")
    }

    private fun fileOpener(): (String) -> InputStream = { path ->
        File(assetsDir(), path.removePrefix("luts/")).inputStream()
    }

    @Test
    fun `every committed bundled asset parses with its registry id`() {
        for (entry in BundledLuts.ORDERED) {
            val lut = BundledLuts.load(entry, fileOpener())
            assertNotNull("asset ${entry.fileName} must parse", lut)
            lut!!
            assertEquals(entry.id, lut.id)
            assertEquals(17, lut.size)
            assertTrue("title carries the project prefix", lut.title.startsWith("toadGALLERY "))
        }
    }

    @Test
    fun `loadAll through a filesystem opener yields all five looks in order`() {
        val loaded = BundledLuts.loadAll(fileOpener())
        assertEquals(BundledLuts.ORDERED.map { it.id }, loaded.keys.toList())
    }

    @Test
    fun `a broken opener degrades to an absent look, never a crash`() {
        val throwing: (String) -> InputStream = { _ -> throw java.io.IOException("asset missing") }
        assertNull(BundledLuts.load(BundledLuts.ORDERED.first(), throwing))
        assertTrue(BundledLuts.loadAll(throwing).isEmpty())
    }

    // ---- The looks themselves (hand-verified probes) ----

    private fun look(id: String): CubeLut {
        val entry = BundledLuts.byId(id) ?: error("unknown look $id")
        return BundledLuts.load(entry, fileOpener()) ?: error("look $id failed to parse")
    }

    @Test
    fun `warm-film is warm and lifted at mid-grey`() {
        // contrast 1.12 -> 0.5; lift +0.045*(1-0.5) -> 0.5225; gains 1.06/1.0/0.92.
        val out = look("warm-film").sample(0.5, 0.5, 0.5)
        assertEquals(0.55385, out[0], 1e-5)
        assertEquals(0.5225, out[1], 1e-5)
        assertEquals(0.4807, out[2], 1e-5)
    }

    @Test
    fun `cool-chrome pushes blue and holds red at three-quarter grey`() {
        // contrast 1.18 -> 0.795; gains 0.94/1.0/1.07.
        val out = look("cool-chrome").sample(0.75, 0.75, 0.75)
        assertEquals(0.7473, out[0], 1e-5)
        assertEquals(0.795, out[1], 1e-5)
        assertEquals(0.85065, out[2], 1e-5)
    }

    @Test
    fun `silver-mono is luma monochrome with punch`() {
        val greyQuarter = look("silver-mono").sample(0.25, 0.25, 0.25)
        assertEquals(0.23, greyQuarter[0], 1e-5)
        assertEquals(0.23, greyQuarter[1], 1e-5)
        assertEquals(0.23, greyQuarter[2], 1e-5)
        // Pure red: luma 0.299 -> 0.5 + (0.299-0.5)*1.08 = 0.28292 on all channels.
        val red = look("silver-mono").sample(1.0, 0.0, 0.0)
        assertEquals(0.28292, red[0], 1e-5)
        assertEquals(0.28292, red[1], 1e-5)
        assertEquals(0.28292, red[2], 1e-5)
    }

    @Test
    fun `matte-fade lifts blacks and holds whites below paper-white`() {
        val black = look("matte-fade").sample(0.0, 0.0, 0.0)
        assertEquals(0.09, black[0], 1e-5)
        val mid = look("matte-fade").sample(0.5, 0.5, 0.5)
        assertEquals(0.505, mid[0], 1e-5)
        val white = look("matte-fade").sample(1.0, 1.0, 1.0)
        assertEquals(0.92, white[0], 1e-5)
    }

    @Test
    fun `vivid-punch pushes saturation away from luma`() {
        // luma(0.5, 0.25, 0.25) = 0.32475; r -> 0.553977, g/b -> 0.201977.
        val out = look("vivid-punch").sample(0.5, 0.25, 0.25)
        assertEquals(0.553977, out[0], 1e-5)
        assertEquals(0.201977, out[1], 1e-5)
        assertEquals(0.201977, out[2], 1e-5)
    }
}
