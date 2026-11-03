package io.github.dukeoftoad.gallery.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * r34: the merged look registry (bundled + user imports) and the store's
 * import round-trip feeding it. The store's own unit matrix lives in
 * [ImportedLutStoreTest]; this file pins the REGISTRY contract (the
 * disjointness/precedence guarantees the merged map's readers rely on)
 * and the end-to-end import -> list -> load -> resolve path the editor's
 * session map performs on every refresh.
 */
class LookRegistryImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A minimal valid identity 2-cube: sample(0.5,0.5,0.5) = (0.5,0.5,0.5). */
    private val identityCube: String = listOf(
        "TITLE \"test identity\"",
        "LUT_3D_SIZE 2",
        "0 0 0",
        "1 0 0",
        "0 1 0",
        "1 1 0",
        "0 0 1",
        "1 0 1",
        "0 1 1",
        "1 1 1",
    ).joinToString(separator = "\n", postfix = "\n")

    /** A minimal valid red-lift 2-cube: every output's red channel is pushed to 1. */
    private val redLiftCube: String = listOf(
        "TITLE \"test red lift\"",
        "LUT_3D_SIZE 2",
        "1 0 0",
        "1 0 0",
        "1 1 0",
        "1 1 0",
        "1 0 1",
        "1 0 1",
        "1 1 1",
        "1 1 1",
    ).joinToString(separator = "\n", postfix = "\n")

    private fun parseOrThrow(text: String): CubeLut =
        when (val result = CubeLutParser.parse(text)) {
            is CubeLutParser.Result.Valid -> result.lut
            is CubeLutParser.Result.Invalid -> throw AssertionError("test cube rejected: ${result.reason}")
        }

    private fun newStore(): ImportedLutStore = ImportedLutStore(tmp.newFolder("imported-luts"))

    // ---- LookRegistry.merge ----

    @Test
    fun `merge with no imports is content-equal to the bundled map`() {
        val bundled = linkedMapOf("warm-film" to parseOrThrow(identityCube))
        val merged = LookRegistry.merge(bundled, emptyMap())
        assertEquals(bundled.keys, merged.keys)
        assertEquals(bundled["warm-film"], merged["warm-film"])
    }

    @Test
    fun `merge appends imports after the bundled entries in given order`() {
        val bundled = linkedMapOf("warm-film" to parseOrThrow(identityCube))
        val imported = linkedMapOf(
            "alpha-look" to parseOrThrow(identityCube),
            "beta-look" to parseOrThrow(redLiftCube),
        )
        val merged = LookRegistry.merge(bundled, imported)
        assertEquals(listOf("warm-film", "alpha-look", "beta-look"), merged.keys.toList())
    }

    @Test
    fun `merge refuses to let an import shadow a bundled look`() {
        // The store collision-suffixes so this "cannot happen"; the registry
        // pins the defensive contract anyway: the BUNDLED look wins.
        val bundledLut = parseOrThrow(identityCube)
        val importedLut = parseOrThrow(redLiftCube)
        val merged = LookRegistry.merge(
            linkedMapOf("warm-film" to bundledLut),
            linkedMapOf("warm-film" to importedLut),
        )
        assertEquals(bundledLut, merged["warm-film"])
        assertEquals(1, merged.size)
    }

    @Test
    fun `the merged map is unmodifiable`() {
        val merged = LookRegistry.merge(
            linkedMapOf("warm-film" to parseOrThrow(identityCube)),
            emptyMap(),
        )
        var threw = false
        try {
            @Suppress("UNCHECKED_CAST")
            (merged as MutableMap<String, CubeLut>)["rogue"] = parseOrThrow(redLiftCube)
        } catch (_: UnsupportedOperationException) {
            threw = true
        }
        assertTrue("the session map must not be mutable", threw)
    }

    // ---- import -> list -> load -> resolve (the session-refresh path) ----

    @Test
    fun `imported look round-trips through the store with its parsed title`() {
        val store = newStore()
        val result = store.import(identityCube.toByteArray(), "my first look.cube")
        assertTrue(result is ImportedLutStore.ImportResult.Imported)
        val imported = (result as ImportedLutStore.ImportResult.Imported).lut
        assertEquals("test identity", imported.title)

        val listed = store.list()
        assertEquals(listOf(imported.id), listed.map { it.id })
        assertEquals("test identity", listed.single().title)

        val loaded = store.load(imported.id)
        assertTrue(loaded != null)
        // The hand value: the identity cube at the centre is the centre.
        assertEquals(0.5, loaded!!.sample(0.5, 0.5, 0.5)[0], 1e-9)
        assertEquals(0.5, loaded.sample(0.5, 0.5, 0.5)[1], 1e-9)
        assertEquals(0.5, loaded.sample(0.5, 0.5, 0.5)[2], 1e-9)
    }

    @Test
    fun `the session refresh keeps bundled and imported looks in one map`() {
        val store = newStore()
        val bundled = linkedMapOf("warm-film" to parseOrThrow(identityCube))
        val importResult = store.import(redLiftCube.toByteArray(), "lift.cube")
        assertTrue(importResult is ImportedLutStore.ImportResult.Imported)
        val userLut = (importResult as ImportedLutStore.ImportResult.Imported).lut

        // The activity's loadSessionLooks() body, pinned here against the
        // store so the merge contract and the store stay wired together.
        val session = LookRegistry.merge(bundled, store.loadAll())
        assertEquals(setOf("warm-film", userLut.id), session.keys)
        // The user look resolves and samples as the red-lift cube does:
        // any output's red channel is 1 in this cube's corner lattice.
        val resolved = session[userLut.id]!!
        assertEquals(1.0, resolved.sample(0.25, 0.75, 0.5)[0], 1e-9)
    }

    @Test
    fun `a deleted import drops out of the refreshed session map (the tolerance path)`() {
        val store = newStore()
        val importResult = store.import(redLiftCube.toByteArray(), "gone.cube")
        val id = (importResult as ImportedLutStore.ImportResult.Imported).lut.id
        val before = LookRegistry.merge(emptyMap(), store.loadAll())
        assertTrue(before.containsKey(id))

        assertTrue(store.delete(id))
        assertFalse(store.isImported(id))
        assertNull(store.load(id))
        val after = LookRegistry.merge(emptyMap(), store.loadAll())
        assertFalse("the deleted import must be absent from the refresh", after.containsKey(id))
    }

    @Test
    fun `a rejected import writes nothing and the refresh is unchanged`() {
        val store = newStore()
        store.import(redLiftCube.toByteArray(), "keeper.cube")
        val beforeList = store.list()
        val beforeFiles = store.list().map { it.fileName }.sorted()

        val rejected = store.import("not a cube at all".toByteArray(), "broken.cube")
        assertTrue(rejected is ImportedLutStore.ImportResult.Rejected)

        assertEquals(beforeList.map { it.id }, store.list().map { it.id })
        assertEquals(beforeFiles, store.list().map { it.fileName }.sorted())
    }
}
