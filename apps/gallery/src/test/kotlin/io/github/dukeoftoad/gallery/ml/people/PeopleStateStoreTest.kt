package io.github.dukeoftoad.gallery.ml.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * JVM tests for the r25 people state (Workstream 6): the PURE
 * [PeopleState] transitions ([renamed], [withHidden], [prunedTo]) and
 * the production [PeopleStateFile]'s persistence discipline
 * (`FaceIndexStateFile`'s exact write contract --- atomic
 * temp-then-move, forgiving parse; ADR-0016).
 *
 * What is pinned:
 * - a full round-trip (names + hidden) survives save/load, and the
 *   atomic write leaves no `.tmp` behind;
 * - a MISSING file is the clean empty state (nothing was ever saved),
 *   while a CORRUPT / wrong-version / hand-edited file recovers to
 *   empty WITH the honest note flag ([PeopleStateLoad
 *   .recoveredFromCorruptFile]) --- the user's names were lost, and
 *   the UI must be able to say so;
 * - a blank rename REMOVES the entry ("Unnamed" is absence, not a
 *   stored empty string); names are trimmed; no-op transitions return
 *   the SAME instance;
 * - hidden add/remove round-trips through [withHidden];
 * - [prunedTo] drops entries whose cluster ids no longer resolve
 *   (cluster ids are NOT stable across re-clustering passes) and is a
 *   no-op (same instance) when nothing is stale.
 */
class PeopleStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newFile(): Pair<File, PeopleStateFile> {
        val dir = tmp.newFolder()
        val file = File(dir, "people-state.json")
        return file to PeopleStateFile(file)
    }

    // ---- PeopleStateFile persistence ----

    @Test
    fun `the state file round-trips names and hidden`() {
        val (file, store) = newFile()
        val state = PeopleState(
            names = mapOf("c1" to "Alice", "c2" to "Bob"),
            hidden = setOf("c3", "c4"),
        )

        runBlocking { store.save(state) }
        val loaded = runBlocking { store.load() }

        assertEquals(state, loaded.state)
        assertFalse(loaded.recoveredFromCorruptFile)
        // The atomic write leaves no temp file behind:
        assertEquals(setOf("people-state.json"), file.parentFile!!.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `saving twice replaces atomically and loading an absent file is clean-empty`() {
        val (file, store) = newFile()

        val first = runBlocking { store.load() }
        assertEquals(PeopleState.EMPTY, first.state)
        assertFalse(first.recoveredFromCorruptFile)

        runBlocking { store.save(PeopleState(names = mapOf("c1" to "Alice"), hidden = setOf("c2"))) }
        runBlocking { store.save(PeopleState(names = mapOf("c9" to "Zoe"), hidden = emptySet())) }
        val loaded = runBlocking { store.load() }
        assertEquals(PeopleState(names = mapOf("c9" to "Zoe")), loaded.state)
        assertEquals(setOf("people-state.json"), file.parentFile!!.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `a corrupt or wrong-version file recovers empty WITH the honest note`() {
        val (file, store) = newFile()

        // Garbage bytes:
        file.writeText("not json at all")
        var loaded = runBlocking { store.load() }
        assertEquals(PeopleState.EMPTY, loaded.state)
        assertTrue(loaded.recoveredFromCorruptFile)

        // A future schema version is not ours to interpret:
        file.writeText("""{"version":2,"names":{},"hidden":[]}""")
        loaded = runBlocking { store.load() }
        assertEquals(PeopleState.EMPTY, loaded.state)
        assertTrue(loaded.recoveredFromCorruptFile)

        // A MISSING file is NOT corrupt (nothing was ever lost):
        file.delete()
        loaded = runBlocking { store.load() }
        assertEquals(PeopleState.EMPTY, loaded.state)
        assertFalse(loaded.recoveredFromCorruptFile)
    }

    @Test
    fun `hand-edited rows degrade forgivingly - blank names and empty ids are skipped`() {
        val (file, store) = newFile()
        file.writeText(
            """
            {"version":1,
             "names":{"c1":"Alice","c2":"  ","":"Nope"},
             "hidden":["c3","c4",""]}
            """.trimIndent(),
        )
        val loaded = runBlocking { store.load() }
        assertEquals(PeopleState(names = mapOf("c1" to "Alice"), hidden = setOf("c3", "c4")), loaded.state)
        assertFalse(loaded.recoveredFromCorruptFile)
    }

    // ---- the pure PeopleState transitions ----

    @Test
    fun `a blank rename removes the entry - unnamed is absence`() {
        val state = PeopleState(names = mapOf("c1" to "Alice", "c2" to "Bob"))

        val cleared = state.renamed("c1", "")
        assertEquals(PeopleState(names = mapOf("c2" to "Bob")), cleared)

        // Whitespace-only is blank too; input is trimmed on the way in:
        val trimmed = cleared.renamed("c2", "   ")
        assertEquals(PeopleState.EMPTY, trimmed)

        val trimmedSet = PeopleState.EMPTY.renamed("c1", "  Alice  ")
        assertEquals(PeopleState(names = mapOf("c1" to "Alice")), trimmedSet)

        // Removing an absent entry is a no-op (same instance):
        assertTrue(trimmed.renamed("nope", "") === trimmed)
        // Setting the same value is a no-op too:
        assertTrue(trimmedSet.renamed("c1", "Alice") === trimmedSet)
    }

    @Test
    fun `hidden membership adds and removes`() {
        val base = PeopleState(names = mapOf("c1" to "Alice"))

        val hidden = base.withHidden("c1", hidden = true)
        assertEquals(PeopleState(names = mapOf("c1" to "Alice"), hidden = setOf("c1")), hidden)

        // Idempotent in both directions (no-op returns the same instance):
        assertTrue(hidden.withHidden("c1", hidden = true) === hidden)
        val unhidden = hidden.withHidden("c1", hidden = false)
        assertEquals(base, unhidden)
        assertTrue(base.withHidden("c1", hidden = false) === base)
    }

    @Test
    fun `prunedTo drops stale cluster ids - names and hidden both`() {
        // Cluster ids are NOT stable across re-clustering passes: after a
        // recluster only the live ids mean anything.
        val stale = PeopleState(
            names = mapOf("live" to "Alice", "dead" to "Bob"),
            hidden = setOf("live-2", "dead-2"),
        )

        val pruned = stale.prunedTo(setOf("live", "live-2"))
        assertEquals(
            PeopleState(names = mapOf("live" to "Alice"), hidden = setOf("live-2")),
            pruned,
        )

        // Nothing stale -> same instance (the repository's save-back signal):
        assertTrue(pruned.prunedTo(setOf("live", "live-2")) === pruned)
        assertTrue(PeopleState.EMPTY.prunedTo(emptySet()) === PeopleState.EMPTY)
    }
}
