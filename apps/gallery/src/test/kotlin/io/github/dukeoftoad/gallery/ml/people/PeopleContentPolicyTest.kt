package io.github.dukeoftoad.gallery.ml.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the People content-state mapping (r25, Workstream 6;
 * ADR-0016 --- [PeopleContentPolicy] is pure Kotlin over plain values,
 * so the "which state does the user see" rules are pinned here instead
 * of living implicitly in the ViewModels' control flow; the same shape
 * as [io.github.dukeoftoad.gallery.ui.map.MapContentPolicyTest]).
 *
 * What is pinned:
 * - ERROR DOMINANCE: a non-null error message wins over consent,
 *   engine presence, and any cluster data --- a failed read can never
 *   mask as a presentable screen;
 * - the CONSENT gate decides before capability: consent off shows
 *   ConsentOff even when clusters exist (ADR-0010's opt-in rule ---
 *   showing clustered faces while the toggle says off would present
 *   data the user's own choice withholds);
 * - consent on + engine missing -> EngineMissing (the build shape);
 * - consent + engine + zero RAW clusters -> NotIndexed (the queue has
 *   not run / is still draining) --- while all-HIDDEN clusters are
 *   Ready(empty), a different honest state (the index ran);
 * - Ready: hidden clusters filtered out, names applied (null/blank ->
 *   Unnamed), and the SEAM'S OWN face-count-descending order preserved
 *   verbatim (the policy is a mapping, not a sorter --- the seam's
 *   ordering is the contract).
 */
class PeopleContentPolicyTest {

    private fun cluster(
        id: String,
        faceCount: Int,
        coverFileId: Long? = 1L,
    ): FaceCluster = FaceCluster(clusterId = id, faceCount = faceCount, coverFileId = coverFileId)

    @Test
    fun `error dominates everything - consent, engine and clusters cannot rescue a failed read`() {
        val state = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            clusters = listOf(cluster("c1", 10)),
            hiddenIds = emptySet(),
            names = mapOf("c1" to "Alice"),
            errorMessage = "face index store is not open",
        )
        assertEquals(PeopleContentState.Error("face index store is not open"), state)

        // Also over the consent-off arm and the engine-missing arm:
        assertEquals(
            PeopleContentState.Error("boom"),
            PeopleContentPolicy.decide(false, false, emptyList(), emptySet(), emptyMap(), "boom"),
        )
    }

    @Test
    fun `consent off wins even over live clusters - the opt-in gate`() {
        val state = PeopleContentPolicy.decide(
            consentEnabled = false,
            engineAvailable = true,
            clusters = listOf(cluster("c1", 10)),
            hiddenIds = emptySet(),
            names = mapOf("c1" to "Alice"),
        )
        assertEquals(PeopleContentState.ConsentOff, state)

        // Consent off + engine missing is still ConsentOff (consent first):
        assertEquals(
            PeopleContentState.ConsentOff,
            PeopleContentPolicy.decide(false, false, listOf(cluster("c1", 1)), emptySet(), emptyMap()),
        )
    }

    @Test
    fun `consent on but engine missing is the honest build-shape state`() {
        val state = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = false,
            clusters = emptyList(),
            hiddenIds = emptySet(),
            names = emptyMap(),
        )
        assertEquals(PeopleContentState.EngineMissing, state)
    }

    @Test
    fun `consent and engine with zero raw clusters is not-indexed - including the queue still draining`() {
        val state = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            clusters = emptyList(),
            hiddenIds = emptySet(),
            names = emptyMap(),
        )
        assertEquals(PeopleContentState.NotIndexed, state)
    }

    @Test
    fun `ready filters hidden clusters and applies names - seam order preserved verbatim`() {
        // Seam order is count-desc by contract; deliberately handed over
        // in that order, including a tie, to pin "mapping, not sorter".
        val clusters = listOf(
            cluster("big", 12),
            cluster("named", 9, coverFileId = null),
            cluster("hidden1", 7),
            cluster("tie-a", 3),
            cluster("tie-b", 3),
        )
        val state = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            clusters = clusters,
            hiddenIds = setOf("hidden1"),
            names = mapOf("big" to "Alice", "named" to "Bob", "hidden1" to "Carol", "unknown" to "Unused"),
        ) as PeopleContentState.Ready

        assertEquals(
            listOf(
                VisiblePerson("big", "Alice", 12, 1L),
                VisiblePerson("named", "Bob", 9, null),
                VisiblePerson("tie-a", null, 3, 1L),
                VisiblePerson("tie-b", null, 3, 1L),
            ),
            state.visible,
        )
    }

    @Test
    fun `unnamed is absence - null names and blank stored names both render Unnamed`() {
        val clusters = listOf(cluster("a", 2), cluster("b", 1))
        val state = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            clusters = clusters,
            hiddenIds = emptySet(),
            names = mapOf("a" to "", "b" to "   "), // the store refuses blanks; belt-and-braces
        ) as PeopleContentState.Ready
        assertNull(state.visible[0].name)
        assertNull(state.visible[1].name)
    }

    @Test
    fun `every cluster hidden is ready-empty - NOT not-indexed`() {
        val state = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            clusters = listOf(cluster("c1", 5), cluster("c2", 1)),
            hiddenIds = setOf("c1", "c2"),
            names = emptyMap(),
        )
        assertEquals(PeopleContentState.Ready(emptyList()), state)
    }

    @Test
    fun `the default error message is none - the plain success path`() {
        val state = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            clusters = listOf(cluster("c1", 4)),
            hiddenIds = emptySet(),
            names = emptyMap(),
        )
        assertEquals(
            PeopleContentState.Ready(listOf(VisiblePerson("c1", null, 4, 1L))),
            state,
        )
        assertTrue(state is PeopleContentState.Ready)
    }

    // ---- the r26 hidden half (the People screen's hidden section) ----

    @Test
    fun `hiddenPersons mirrors the visible mapping - hidden clusters with names, seam order preserved`() {
        val clusters = listOf(
            cluster("big", 12),
            cluster("named", 9, coverFileId = null),
            cluster("hidden1", 7),
            cluster("tie-a", 3),
            cluster("tie-b", 3),
        )

        val hidden = PeopleContentPolicy.hiddenPersons(
            clusters = clusters,
            hiddenIds = setOf("hidden1", "tie-a", "tie-b"),
            names = mapOf("big" to "Alice", "named" to "Bob", "hidden1" to "Carol"),
        )

        // Only the hidden clusters, names applied, the seam's own
        // face-count-descending order preserved verbatim:
        assertEquals(
            listOf(
                VisiblePerson("hidden1", "Carol", 7, 1L),
                VisiblePerson("tie-a", null, 3, 1L),
                VisiblePerson("tie-b", null, 3, 1L),
            ),
            hidden,
        )
    }

    @Test
    fun `hiddenPersons skips stale hidden ids - and blank names render Unnamed`() {
        val hidden = PeopleContentPolicy.hiddenPersons(
            clusters = listOf(cluster("c1", 2)),
            hiddenIds = setOf("c1", "stale-gone"),
            names = mapOf("c1" to "   "),
        )

        assertEquals(listOf(VisiblePerson("c1", null, 2, 1L)), hidden)
    }

    @Test
    fun `hiddenPersons is empty when nothing is hidden`() {
        assertTrue(
            PeopleContentPolicy.hiddenPersons(
                clusters = listOf(cluster("c1", 2)),
                hiddenIds = emptySet(),
                names = mapOf("c1" to "Alice"),
            ).isEmpty(),
        )
        assertTrue(
            PeopleContentPolicy.hiddenPersons(
                clusters = emptyList(),
                hiddenIds = setOf("c1"),
                names = emptyMap(),
            ).isEmpty(),
        )
    }
}
