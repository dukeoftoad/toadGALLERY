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

    // ---- the r26 overlay books ----

    @Test
    fun `the state file round-trips the r26 overlay books`() {
        val (file, store) = newFile()
        val state = PeopleState(
            names = mapOf("c1" to "Alice"),
            hidden = setOf("c2"),
            memberships = mapOf("c1" to setOf(101L, 102L), "c2" to setOf(103L)),
            merges = listOf(PeopleMerge("c1", setOf(101L, 102L, 103L))),
            removals = mapOf("c1" to setOf(104L)),
        )

        runBlocking { store.save(state) }
        val loaded = runBlocking { store.load() }

        assertEquals(state, loaded.state)
        assertFalse(loaded.recoveredFromCorruptFile)
        assertEquals(setOf("people-state.json"), file.parentFile!!.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `an r25 file loads with empty overlay books - the upgrade loses nothing`() {
        val (file, store) = newFile()
        file.writeText(
            """
            {"version":1,
             "names":{"c1":"Alice"},
             "hidden":["c2"]}
            """.trimIndent(),
        )

        val loaded = runBlocking { store.load() }

        // The r25 shape (no memberships/merges/removals keys) is the same
        // schema version: names/hidden intact, the r26 books empty.
        assertEquals(PeopleState(names = mapOf("c1" to "Alice"), hidden = setOf("c2")), loaded.state)
        assertFalse(loaded.recoveredFromCorruptFile)
    }

    @Test
    fun `hand-edited overlay books degrade forgivingly - empty ids and garbage values are skipped`() {
        val (file, store) = newFile()
        file.writeText(
            """
            {"version":1,
             "names":{"c1":"Alice"},
             "hidden":[],
             "memberships":{"c1":[101,102],"":[103],"c3":[]},
             "merges":[{"target":"c1","files":[101,102]},{"target":"","files":[9]},{"files":[9]}],
             "removals":{"c1":[107],"":[108]}}
            """.trimIndent(),
        )

        val loaded = runBlocking { store.load() }

        assertEquals(
            PeopleState(
                names = mapOf("c1" to "Alice"),
                memberships = mapOf("c1" to setOf(101L, 102L)),
                merges = listOf(PeopleMerge("c1", setOf(101L, 102L))),
                removals = mapOf("c1" to setOf(107L)),
            ),
            loaded.state,
        )
        assertFalse(loaded.recoveredFromCorruptFile)
    }

    @Test
    fun `withMembership and withRemoval accumulate and no-op cleanly`() {
        val base = PeopleState(names = mapOf("c1" to "Alice"))

        val withMembership = base.withMembership("c1", setOf(1L, 2L))
        assertEquals(mapOf("c1" to setOf(1L, 2L)), withMembership.memberships)
        // Same fingerprint -> same instance:
        assertTrue(withMembership.withMembership("c1", setOf(1L, 2L)) === withMembership)

        val withRemoval = withMembership.withRemoval("c1", setOf(2L))
        assertEquals(mapOf("c1" to setOf(2L)), withRemoval.removals)
        // Accumulating is additive, idempotent, and an empty set is a no-op:
        val again = withRemoval.withRemoval("c1", setOf(2L)).withRemoval("c1", setOf(3L))
        assertEquals(mapOf("c1" to setOf(2L, 3L)), again.removals)
        assertTrue(withRemoval.withRemoval("c1", emptySet()) === withRemoval)
    }

    @Test
    fun `withoutClusterEdits drops every edit keyed to the id`() {
        val state = PeopleState(
            names = mapOf("c1" to "Alice", "c2" to "Bob"),
            hidden = setOf("c1"),
            memberships = mapOf("c1" to setOf(1L)),
            merges = listOf(PeopleMerge("c1", setOf(1L, 2L))),
            removals = mapOf("c1" to setOf(3L)),
        )

        val cleared = state.withoutClusterEdits("c1")
        assertEquals(PeopleState(names = mapOf("c2" to "Bob")), cleared)

        // Nothing keyed -> same instance:
        assertTrue(cleared.withoutClusterEdits("c2") === cleared)
    }

    @Test
    fun `mergeRecorded folds the source into the target - newest record replaces the older`() {
        val state = PeopleState(
            names = mapOf("src" to "Doomed", "tgt" to "Taylor"),
            memberships = mapOf("src" to setOf(10L), "tgt" to setOf(20L)),
            removals = mapOf("src" to setOf(99L)),
            merges = listOf(PeopleMerge("tgt", setOf(20L))),
        )

        val merged = state.mergeRecorded("src", "tgt", setOf(10L, 20L))

        // The source's name/hidden/membership are gone; its removals follow
        // the person into the target; the target's merge record is REPLACED
        // by the newest merged set:
        assertEquals(mapOf("tgt" to "Taylor"), merged.names)
        assertEquals(mapOf("tgt" to setOf(10L, 20L)), merged.memberships)
        assertEquals(mapOf("tgt" to setOf(99L)), merged.removals)
        assertEquals(listOf(PeopleMerge("tgt", setOf(10L, 20L))), merged.merges)
    }

    @Test
    fun `canonicalized drops fingerprints without edits and empty records`() {
        val state = PeopleState(
            names = mapOf("c1" to "Alice"),
            memberships = mapOf("c1" to setOf(1L), "ghost" to setOf(2L), "empty" to emptySet()),
            removals = mapOf("gone" to setOf(3L), "void" to emptySet()),
            merges = listOf(PeopleMerge("c1", setOf(1L)), PeopleMerge("dead", emptySet())),
        )

        val canonical = state.canonicalized()

        assertEquals(
            PeopleState(
                names = mapOf("c1" to "Alice"),
                memberships = mapOf("c1" to setOf(1L)),
                merges = listOf(PeopleMerge("c1", setOf(1L))),
            ),
            canonical,
        )
        // Already canonical -> same instance:
        assertTrue(canonical.canonicalized() === canonical)
        assertTrue(PeopleState.EMPTY.canonicalized() === PeopleState.EMPTY)
    }

    @Test
    fun `editedClusterIds and carriesEdits cover every book`() {
        val state = PeopleState(
            names = mapOf("named" to "Alice"),
            hidden = setOf("hid"),
            memberships = mapOf("lonely" to setOf(1L)),
            merges = listOf(PeopleMerge("merged", setOf(1L))),
            removals = mapOf("pruned" to setOf(2L)),
        )

        assertEquals(setOf("named", "hid", "merged", "pruned"), state.editedClusterIds)
        assertTrue(state.carriesEdits("named") && state.carriesEdits("hid"))
        assertTrue(state.carriesEdits("merged") && state.carriesEdits("pruned"))
        // A membership fingerprint alone preserves nothing:
        assertFalse(state.carriesEdits("lonely"))
        assertFalse(PeopleState.EMPTY.carriesEdits("anything"))
    }
}
