package io.github.dukeoftoad.gallery.ml.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the r26 edit-preserving reclustering overlay's PURE
 * planner ([PeopleReclusterRemap] --- ADR-0016: plain values in, plan
 * out, no Android imports). What is pinned:
 * - an in-sync state (every edited id resolves) plans a NO-OP;
 * - names, hidden flags, merges and remove-from-person corrections
 *   all SURVIVE a simulated recluster pass through membership overlap;
 * - the honest fallbacks: a stale edit without a fingerprint (the
 *   pre-r26 upgrade shape) or without any shared file drops;
 * - best-match determinism (most shared files; ties by the bigger
 *   cluster, then the smaller id);
 * - name collisions on one new cluster resolve by overlap, then by the
 *   smaller old id, and a live id's own name always wins;
 * - the engine work the plan asks for: merge re-folds and removal
 *   re-applies, each only when the recluster actually undid the edit.
 */
class PeopleReclusterRemapTest {

    private fun cluster(id: String, faceCount: Int): FaceCluster = FaceCluster(id, faceCount, null)

    // ---- the no-op path ----

    @Test
    fun `an in-sync state plans a no-op`() {
        val state = PeopleState(
            names = mapOf("c1" to "Alice"),
            hidden = setOf("c2"),
            memberships = mapOf("c1" to setOf(1L, 2L), "c2" to setOf(3L)),
            merges = listOf(PeopleMerge("c1", setOf(1L, 2L))),
            removals = mapOf("c2" to setOf(9L)),
        )

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("c1", 2), cluster("c2", 1)),
            liveMemberships = mapOf("c1" to setOf(1L, 2L), "c2" to setOf(3L)),
        )

        assertFalse(plan.hasEngineWork)
        assertTrue(plan.merges.isEmpty() && plan.removals.isEmpty())
        assertTrue(plan.remappedIds.isEmpty() && plan.droppedIds.isEmpty())
        // In sync AND already canonical: the very same instance back.
        assertSame(state, plan.state)
    }

    @Test
    fun `needsReconcile is false until an edited id stops resolving`() {
        val state = PeopleState(names = mapOf("c1" to "Alice"), hidden = setOf("c2"))

        assertFalse(PeopleReclusterRemap.needsReconcile(state, setOf("c1", "c2")))
        assertFalse(PeopleReclusterRemap.needsReconcile(PeopleState.EMPTY, emptySet()))
        assertTrue(PeopleReclusterRemap.needsReconcile(state, setOf("c1")))
        assertTrue(PeopleReclusterRemap.needsReconcile(state, setOf("c1", "c2", "c3")))
    }

    // ---- the survival paths ----

    @Test
    fun `a named person survives a recluster via membership overlap`() {
        val state = PeopleState(
            names = mapOf("old" to "Alice"),
            memberships = mapOf("old" to setOf(10L, 11L, 12L)),
        )
        // The recluster re-rolled the id AND the queue added a photo since:
        val live = listOf(cluster("new", 13))
        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = live,
            liveMemberships = mapOf("new" to setOf(10L, 11L, 12L, 13L)),
        )

        assertEquals(mapOf("old" to "new"), plan.remappedIds)
        assertEquals(mapOf("new" to "Alice"), plan.state.names)
        assertTrue(plan.droppedIds.isEmpty())
        assertFalse(plan.hasEngineWork)
        // The refreshed fingerprint is the live membership (post-plan):
        assertEquals(mapOf("new" to setOf(10L, 11L, 12L, 13L)), plan.state.memberships)
    }

    @Test
    fun `a hidden person survives a recluster`() {
        val state = PeopleState(
            hidden = setOf("old"),
            memberships = mapOf("old" to setOf(1L, 2L)),
        )

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("new", 2), cluster("other", 5)),
            liveMemberships = mapOf("new" to setOf(1L, 2L), "other" to setOf(30L, 31L, 32L, 33L, 34L)),
        )

        assertEquals(setOf("new"), plan.state.hidden)
        assertEquals(mapOf("new" to setOf(1L, 2L)), plan.state.memberships)
        assertTrue(plan.hasEngineWork.not())
    }

    @Test
    fun `a merge split by a recluster folds back together`() {
        // The user merged {10,11} into {20}'s person; the recluster split
        // the merged person back into its two halves:
        val state = PeopleState(
            names = mapOf("t" to "Merged"),
            memberships = mapOf("t" to setOf(10L, 11L, 20L)),
            merges = listOf(PeopleMerge("t", setOf(10L, 11L, 20L))),
        )
        val live = listOf(cluster("x", 2), cluster("y", 1))

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = live,
            liveMemberships = mapOf("x" to setOf(10L, 11L), "y" to setOf(20L)),
        )

        // The re-fold: y's faces move back into the best-overlap home x.
        assertEquals(listOf("y" to "x"), plan.merges)
        // The name and the merge intent follow onto the home:
        assertEquals(mapOf("x" to "Merged"), plan.state.names)
        assertEquals(mapOf("t" to "x"), plan.remappedIds)
        assertEquals(listOf(PeopleMerge("x", setOf(10L, 11L, 20L))), plan.state.merges)
        // The refreshed fingerprint is the PREDICTED post-fold union:
        assertEquals(mapOf("x" to setOf(10L, 11L, 20L)), plan.state.memberships)
        assertTrue(plan.droppedIds.isEmpty())
    }

    @Test
    fun `a merge that survived the recluster plans no re-fold`() {
        val state = PeopleState(
            memberships = mapOf("t" to setOf(10L, 11L)),
            merges = listOf(PeopleMerge("t", setOf(10L, 11L))),
        )

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("z", 2)),
            liveMemberships = mapOf("z" to setOf(10L, 11L)),
        )

        assertFalse(plan.hasEngineWork)
        assertTrue(plan.merges.isEmpty())
        // The intent re-keys onto the surviving continuation:
        assertEquals(listOf(PeopleMerge("z", setOf(10L, 11L))), plan.state.merges)
        assertEquals(mapOf("z" to setOf(10L, 11L)), plan.state.memberships)
    }

    @Test
    fun `a removal the recluster re-derived is re-applied`() {
        // The user removed file 50; the fingerprint is the post-removal
        // membership; the recluster put 50 RIGHT BACK:
        val state = PeopleState(
            names = mapOf("p" to "Pat"),
            memberships = mapOf("p" to setOf(40L, 60L)),
            removals = mapOf("p" to setOf(50L)),
        )

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("q", 3)),
            liveMemberships = mapOf("q" to setOf(40L, 50L, 60L)),
        )

        assertEquals(1, plan.removals.size)
        assertEquals("q", plan.removals[0].first)
        assertEquals(longArrayOf(50L), plan.removals[0].second)
        // The record survives for the NEXT recluster, re-keyed:
        assertEquals(mapOf("q" to setOf(50L)), plan.state.removals)
        // ... and the fingerprint is the predicted post-removal set:
        assertEquals(mapOf("q" to setOf(40L, 60L)), plan.state.memberships)
        assertEquals(mapOf("q" to "Pat"), plan.state.names)
    }

    @Test
    fun `a removal that stayed out plans no engine call`() {
        val state = PeopleState(
            memberships = mapOf("p" to setOf(40L, 60L)),
            removals = mapOf("p" to setOf(50L)),
        )

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("q", 2)),
            liveMemberships = mapOf("q" to setOf(40L, 60L)),
        )

        assertTrue(plan.removals.isEmpty())
        assertFalse(plan.hasEngineWork)
        // The intent still survives, re-keyed, for a future pass:
        assertEquals(mapOf("q" to setOf(50L)), plan.state.removals)
    }

    // ---- the honest fallbacks ----

    @Test
    fun `a stale edit without a fingerprint drops honestly - the pre-r26 shape`() {
        val state = PeopleState(names = mapOf("dead" to "Bob"), hidden = setOf("gone"))

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("new", 3)),
            liveMemberships = mapOf("new" to setOf(1L, 2L, 3L)),
        )

        assertEquals(setOf("dead", "gone"), plan.droppedIds)
        assertEquals(PeopleState.EMPTY, plan.state)
        assertFalse(plan.hasEngineWork)
    }

    @Test
    fun `a stale edit whose fingerprint shares nothing drops honestly`() {
        val state = PeopleState(
            names = mapOf("old" to "Alice"),
            memberships = mapOf("old" to setOf(1L, 2L)),
        )

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("new", 2)),
            liveMemberships = mapOf("new" to setOf(30L, 31L)),
        )

        assertEquals(setOf("old"), plan.droppedIds)
        assertEquals(PeopleState.EMPTY, plan.state)
    }

    @Test
    fun `a merge whose person vanished drops its record`() {
        val state = PeopleState(merges = listOf(PeopleMerge("t", setOf(1L, 2L))))

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("new", 2)),
            liveMemberships = mapOf("new" to setOf(30L, 31L)),
        )

        assertEquals(setOf("t"), plan.droppedIds)
        assertEquals(PeopleState.EMPTY, plan.state)
        assertTrue(plan.merges.isEmpty())
    }

    // ---- determinism over user data ----

    @Test
    fun `best match picks the most shared files - ties by the bigger cluster then the smaller id`() {
        val state = PeopleState(
            names = mapOf("old" to "Alice"),
            memberships = mapOf("old" to setOf(1L, 2L, 3L, 4L)),
        )

        // Overlap tie (2 vs 2) -> the bigger cluster wins:
        var plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("small", 2), cluster("big", 5)),
            liveMemberships = mapOf("small" to setOf(1L, 2L, 9L), "big" to setOf(3L, 4L, 10L, 11L, 12L)),
        )
        assertEquals("big", plan.remappedIds["old"])

        // Overlap AND size tie -> the SMALLER id wins (deterministic):
        plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("y7", 2), cluster("x9", 2)),
            liveMemberships = mapOf("y7" to setOf(1L, 2L), "x9" to setOf(3L, 4L)),
        )
        assertEquals("x9", plan.remappedIds["old"])
    }

    @Test
    fun `two names colliding on one new cluster resolve by overlap then the smaller old id`() {
        // The recluster merged two named people into one cluster:
        var state = PeopleState(
            names = mapOf("a" to "Alice", "b" to "Bob"),
            memberships = mapOf("a" to setOf(1L, 2L, 3L), "b" to setOf(4L)),
        )
        var plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("n", 4)),
            liveMemberships = mapOf("n" to setOf(1L, 2L, 3L, 4L)),
        )
        // Alice's side contributed more files: her name wins.
        assertEquals(mapOf("n" to "Alice"), plan.state.names)

        // A perfect tie -> the smaller old id's name wins, deterministically:
        state = PeopleState(
            names = mapOf("b" to "Bob", "a" to "Alice"),
            memberships = mapOf("b" to setOf(3L, 4L), "a" to setOf(1L, 2L)),
        )
        plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("n", 4)),
            liveMemberships = mapOf("n" to setOf(1L, 2L, 3L, 4L)),
        )
        assertEquals(mapOf("n" to "Alice"), plan.state.names)
    }

    @Test
    fun `a live id keeps its own name over a remapped one`() {
        // A mid-window rename landed on the live id while a pre-recluster
        // name also maps onto it: the live id's own name wins.
        val state = PeopleState(
            names = mapOf("live1" to "Kept", "old" to "Remapped"),
            memberships = mapOf("old" to setOf(7L)),
        )

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("live1", 2)),
            liveMemberships = mapOf("live1" to setOf(7L, 8L)),
        )

        assertEquals(mapOf("live1" to "Kept"), plan.state.names)
    }

    @Test
    fun `the plan output is canonical - no fingerprint without an edit`() {
        val state = PeopleState(
            names = mapOf("live" to "Alice"),
            memberships = mapOf("live" to setOf(1L), "ghost" to setOf(2L)),
        )

        val plan = PeopleReclusterRemap.plan(
            state,
            liveClusters = listOf(cluster("live", 1)),
            liveMemberships = mapOf("live" to setOf(1L)),
        )

        assertEquals(mapOf("live" to setOf(1L)), plan.state.memberships)
        assertFalse("ghost" in plan.state.memberships)
    }
}
