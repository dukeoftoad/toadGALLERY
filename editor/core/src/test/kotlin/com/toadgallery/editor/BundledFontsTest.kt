package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Bundled-font conformance: registry self-consistency, every TTF byte-identical to its
 * sha256 provenance pin, and each SIL OFL 1.1 license sitting beside its font.
 */
class BundledFontsTest {

    // ---- Registry ----

    @Test
    fun `the registry is unique, ordered and self-consistent`() {
        assertEquals(4, BundledFonts.ORDERED.size)
        val ids = BundledFonts.ORDERED.map { it.id }
        assertTrue("ids must be unique", ids.size == ids.toSet().size)
        val files = BundledFonts.ORDERED.map { it.fileName }
        assertTrue("file names must be unique", files.size == files.toSet().size)
        for (entry in BundledFonts.ORDERED) {
            assertEquals(entry, BundledFonts.byId(entry.id))
            assertEquals("fonts/${entry.id}/${entry.fileName}", BundledFonts.assetPath(entry))
            assertEquals("fonts/${entry.id}/OFL.txt", BundledFonts.licensePath(entry))
        }
        assertNull(BundledFonts.byId("no-such-font"))
    }

    @Test
    fun `the registry pins the four r34 slots with the default first`() {
        assertEquals(
            listOf("fira-sans", "playfair-display", "caveat", "space-mono"),
            BundledFonts.ORDERED.map { it.id },
        )
        assertEquals("fira-sans", BundledFonts.DEFAULT_ID)
        assertNotNull(BundledFonts.byId(BundledFonts.DEFAULT_ID))
        // The chip labels are the faces' proper names (not localized).
        assertEquals(
            listOf("Fira Sans", "Playfair Display", "Caveat", "Space Mono"),
            BundledFonts.ORDERED.map { it.displayName },
        )
    }

    @Test
    fun `an unknown fontId degrades to the default face, a bundled one resolves to itself`() {
        assertEquals("caveat", BundledFonts.effectiveId("caveat"))
        assertEquals(BundledFonts.DEFAULT_ID, BundledFonts.effectiveId("ghost-font"))
        assertEquals(BundledFonts.DEFAULT_ID, BundledFonts.effectiveId(""))
    }

    // ---- The committed assets (the licensing gate's executable form) ----

    private fun assetsDir(): File {
        val candidates = listOf(
            File("src/main/assets/fonts"), // working dir = the module (Gradle unit tests)
            File("apps/edit/src/main/assets/fonts"), // working dir = the repo root
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("bundled-font assets not found; tried ${candidates.map { it.path }}")
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    /** The provenance pins for the committed TTFs. */
    private val pinnedSha256 = mapOf(
        "fira-sans" to "c29556a2719bf613ef3d5e070e40d903a8965d9c081beca1375dc1e6e0f93c23",
        "playfair-display" to "c40f2293766a503bc70cce9e512ef844a4ccb7cbcde792fe2ea31d191917d8d6",
        "caveat" to "0bdb6b660482d31531b3945849fba5916b3ef8695da7024a9e6b9ee3c4157988",
        "space-mono" to "95837e182baeeada83368f7748db28357f0a1b75c6b84ff7065b5edf933c8e18",
    )

    @Test
    fun `every committed TTF is byte-identical to its provenance pin`() {
        for (entry in BundledFonts.ORDERED) {
            val file = File(assetsDir(), "${entry.id}/${entry.fileName}")
            assertTrue("asset ${entry.fileName} must exist", file.isFile)
            assertTrue("asset ${entry.fileName} must not be empty", file.length() > 0)
            assertEquals(
                "asset ${entry.fileName} must match the sha256 pin (verbatim bundling)",
                pinnedSha256.getValue(entry.id),
                sha256(file),
            )
        }
    }

    @Test
    // Backticked JVM-test names cannot contain dots (dex rejects them);
    // the version lives in the assertion below, not the name.
    fun `every font carries the SIL Open Font License beside it`() {
        for (entry in BundledFonts.ORDERED) {
            val license = File(assetsDir(), "${entry.id}/OFL.txt")
            assertTrue("license for ${entry.id} must sit beside the TTF", license.isFile)
            val text = license.readText()
            assertTrue(
                "the license beside ${entry.id} must be the SIL Open Font License 1.1",
                text.contains("SIL OPEN FONT LICENSE Version 1.1") ||
                    text.contains("SIL Open Font License, Version 1.1"),
            )
        }
    }

    @Test
    fun `the bundle stays inside the round's size budget`() {
        val total = BundledFonts.ORDERED.sumOf { entry ->
            File(assetsDir(), "${entry.id}/${entry.fileName}").length()
        }
        // The committed byte total plus budget headroom keeps an accidental double-bundle from passing.
        assertTrue("four TTFs must stay under 1.3 MB, got $total", total in 1L..1_300_000L)
    }
}
