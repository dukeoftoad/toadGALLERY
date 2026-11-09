package com.toadgallery.gallery.ml.things

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM tests for the r39 Things state + its file persistence (the
 * `PeopleStateStoreTest` discipline, Things edition; ADR-0016 --- the
 * mutations are pure Kotlin and the store is plain org.json + java.io,
 * so everything pins on the JVM).
 *
 * What is pinned:
 * - the ID DERIVATION (slug + collision suffix) and its refused shapes;
 * - every mutation helper's honest contract (blank refusals, the
 *   query-defaults-to-name rule, the id-never-changes-on-rename rule,
 *   the no-op same-instance signals);
 * - the FILE round-trip (save -> load is the same state), the missing
 *   file (the empty state, no flag), the corrupt file (the empty state
 *   + the flag), the future version (the empty state + the flag), and
 *   the forgiving per-entry skip.
 */
class ThingsStateStoreTest {

    // ---- the id derivation ----

    @Test
    fun `slugs fold case, separators and padding - and refuse names with no alphanumerics`() {
        assertEquals("dogs", collectionIdSlug("  Dogs "))
        assertEquals("a-beach-at-sunset", collectionIdSlug("A beach -- at SUNSET!"))
        // Non-ASCII folds to the separator run like any other
        // non-alphanumeric character (the honest ASCII slug rule).
        assertEquals("caf-42", collectionIdSlug("Café 42"))
        assertNull(collectionIdSlug("   "))
        assertNull(collectionIdSlug("---"))
        assertNull(collectionIdSlug("!?*"))
    }

    @Test
    fun `the next id takes the bare slug when free and the lowest suffix when taken`() {
        val state = ThingsState(
            listOf(
                ThingsCollection("dogs", "Dogs", "dogs", 1L),
                ThingsCollection("dogs-2", "More dogs", "dogs", 2L),
            )
        )
        assertEquals("cats", state.nextCollectionId("cats"))
        assertEquals("dogs-3", state.nextCollectionId("dogs"))
    }

    // ---- the mutations ----

    @Test
    fun `add trims, refuses blank names, defaults the query to the name and derives the id`() {
        val first = ThingsState.EMPTY.withCollectionAdded("  Dogs ", "", nowMillis = 10L)!!
        assertEquals(listOf(ThingsCollection("dogs", "Dogs", "Dogs", 10L)), first.collections)

        // The custom query rides verbatim (trimmed); the collision suffix:
        val second = first.withCollectionAdded("dogs", "puppies running", 20L)!!
        assertEquals(
            listOf(
                ThingsCollection("dogs", "Dogs", "Dogs", 10L),
                ThingsCollection("dogs-2", "dogs", "puppies running", 20L),
            ),
            second.collections,
        )

        // The refused shapes (same null):
        assertNull(ThingsState.EMPTY.withCollectionAdded("   ", "query", 1L))
        assertNull(ThingsState.EMPTY.withCollectionAdded("!?*", "query", 1L))
    }

    @Test
    fun `rename keeps the id, refuses blanks, and no-ops unknown ids`() {
        val state = ThingsState.EMPTY.withCollectionAdded("Dogs", "", 1L)!!
        val renamed = state.withCollectionRenamed("dogs", "  Good dogs ")
        assertEquals("Good dogs", renamed.collectionWith("dogs")!!.name)
        assertEquals("the id is the identity, the name is the label", "dogs", renamed.collections.single().collectionId)

        // A blank name is refused (same instance):
        assertSame(state, state.withCollectionRenamed("dogs", "   "))
        // An unknown id is a no-op (same instance):
        assertSame(state, state.withCollectionRenamed("cats", "Cats"))
        // An unchanged name is a no-op too:
        assertSame(state, state.withCollectionRenamed("dogs", "Dogs"))
    }

    @Test
    fun `requery sets the query, resets to the name on blank, and no-ops unknown ids`() {
        val state = ThingsState.EMPTY.withCollectionAdded("Dogs", "puppies", 1L)!!
        val requeried = state.withCollectionQueried("dogs", "  dogs running ")
        assertEquals("dogs running", requeried.collectionWith("dogs")!!.query)

        // A blank query resets to the collection's own NAME (the default):
        val reset = requeried.withCollectionQueried("dogs", "   ")
        assertEquals("Dogs", reset.collectionWith("dogs")!!.query)

        assertSame(state, state.withCollectionQueried("cats", "x"))
        assertSame(state, state.withCollectionQueried("dogs", "puppies"))
    }

    @Test
    fun `remove drops exactly the named collection and no-ops unknown ids`() {
        val state = ThingsState.EMPTY
            .withCollectionAdded("Dogs", "", 1L)!!
            .withCollectionAdded("Cats", "", 2L)!!
        val removed = state.withCollectionRemoved("dogs")
        assertEquals(listOf("cats"), removed.collections.map { it.collectionId })
        assertSame(state, state.withCollectionRemoved("nope"))
    }

    // ---- the file store ----

    private fun createTempDir(): File =
        kotlin.io.path.createTempDirectory("things-state").toFile()

    private fun tempStoreFile(): File =
        File(createTempDir(), THINGS_STATE_FILE_NAME)

    @Test
    fun `the store round-trips the whole state and a missing file is the empty state`() =
        runBlocking {
            val file = tempStoreFile()
            val store = ThingsStateFile(file)

            // Missing file: empty, no flag.
            val missing = store.load()
            assertEquals(ThingsState.EMPTY, missing.state)
            assertFalse(missing.recoveredFromCorruptFile)

            val state = ThingsState.EMPTY
                .withCollectionAdded("Dogs", "puppies", 10L)!!
                .withCollectionAdded("Seaside", "a beach at sunset", 20L)!!
            store.save(state)
            val loaded = store.load()
            assertEquals(state, loaded.state)
            assertFalse(loaded.recoveredFromCorruptFile)
        }

    @Test
    fun `a corrupt or future-version file resets with the honest flag`() = runBlocking {
        val file = tempStoreFile()

        file.parentFile?.mkdirs()
        file.writeText("{ not json")
        val corrupt = ThingsStateFile(file).load()
        assertEquals(ThingsState.EMPTY, corrupt.state)
        assertTrue(corrupt.recoveredFromCorruptFile)

        file.writeText("{\"version\":99,\"collections\":[]}")
        val future = ThingsStateFile(file).load()
        assertEquals(ThingsState.EMPTY, future.state)
        assertTrue(future.recoveredFromCorruptFile)
    }

    @Test
    fun `the forgiving parse skips blank-id and blank-name entries and keeps the rest`() = runBlocking {
        val file = tempStoreFile()
        file.parentFile?.mkdirs()
        // The house single-line raw-string fixture shape (the
        // ClipIndexQueueTest convention):
        file.writeText(
            """{"version":1,"collections":[{"id":"dogs","name":"Dogs","query":"dogs","createdAt":10},{"id":"","name":"Ghost","query":"x","createdAt":11},{"id":"phantom","name":"   ","query":"x","createdAt":12},{"id":"cats","name":"Cats","createdAt":13},"not-an-object"]}""",
        )
        val loaded = ThingsStateFile(file).load()
        assertEquals(
            listOf(
                ThingsCollection("dogs", "Dogs", "dogs", 10L),
                // The blank-name entry is skipped; the cats entry has NO
                // query key, so the query falls back to the name.
                ThingsCollection("cats", "Cats", "Cats", 13L),
            ),
            loaded.state.collections,
        )
        assertFalse(loaded.recoveredFromCorruptFile)
    }
}
