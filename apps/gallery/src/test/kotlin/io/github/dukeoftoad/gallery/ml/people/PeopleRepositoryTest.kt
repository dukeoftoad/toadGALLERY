package io.github.dukeoftoad.gallery.ml.people

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the r25 People repository (Workstream 6) over a FAKE
 * [PeopleEngineSeam] + a fake state store (ADR-0016: the repository is
 * pure composition over injected seams; the production
 * `PeopleEngineAdapter`/`PeopleStateFile`/MediaStore bindings are the
 * untested Android-adjacent half).
 *
 * What is pinned (the composition the ViewModels drive):
 * - [PeopleRepository.snapshot] returns the live clusters with the
 *   persisted names/hidden (pruned to the live cluster ids, pruned copy
 *   saved back --- cluster ids are not stable across re-clustering
 *   passes, so stale entries must not survive a load);
 * - "visible people" --- the exact inputs fed to
 *   [PeopleContentPolicy.decide] by the ViewModels --- is clusters
 *   minus hidden with names applied;
 * - [PeopleRepository.rename] persists through the state store (blank
 *  removes);
 * - [PeopleRepository.merge] calls the SEAM with the arguments in the
 *   dialog's direction (source, target) and the repository's next
 *  snapshot reflects the merged live table (the source cluster gone,
 *  its orphaned name pruned away);
 * - [PeopleRepository.removePhotos] calls the seam with exactly the
 *  given file ids;
 * - [PeopleRepository.hide] persists the hidden membership.
 *
 * The r26 section pins the edit-preserving reclustering overlay
 * end-to-end over the SAME fakes: a simulated recluster pass (the
 * fake's ids and memberships swapped for fresh ones, the photos
 * unchanged) keeps names, hidden flags, merges and remove-from-person
 * corrections alive through `PeopleReclusterRemap`; the unhide flow
 * restores a hidden person with NO engine mutation; a declined
 * re-fold aborts the reconcile without saving and self-heals on the
 * next read.
 */
class PeopleRepositoryTest {

    // ---- the fakes ----

    private class FakeEngine(
        private val clusters: MutableList<FaceCluster>,
        /** file ids per cluster id; merged sources fold into targets. */
        private val files: MutableMap<String, MutableList<Long>>,
        override val engineAvailable: Boolean = true,
    ) : PeopleEngineSeam {

        /** Test switch: a merge the engine would DECLINE (the reconcile's abort path). */
        var declineMerges = false

        val merges = mutableListOf<Pair<String, String>>()
        val removals = mutableListOf<Pair<String, LongArray>>()

        override fun faceClusters(): List<FaceCluster> = clusters.toList()

        override fun clusterFileIds(clusterId: String): List<Long> =
            files[clusterId]?.toList() ?: emptyList()

        override fun mergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean {
            if (declineMerges) return false
            merges += sourceClusterId to targetClusterId
            val moved = files.remove(sourceClusterId) ?: return false
            files.getOrPut(targetClusterId) { mutableListOf() }.addAll(moved)
            clusters.removeAll { it.clusterId == sourceClusterId }
            clusters.replaceAll {
                if (it.clusterId == targetClusterId) {
                    it.copy(faceCount = it.faceCount + moved.size)
                } else {
                    it
                }
            }
            return true
        }

        override fun removeFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean {
            removals += clusterId to fileIds
            val kept = files[clusterId]?.let { list -> list - fileIds.toSet() } ?: return false
            files[clusterId] = kept.toMutableList()
            clusters.replaceAll {
                if (it.clusterId == clusterId) it.copy(faceCount = kept.size) else it
            }
            return true
        }
    }

    private class FakeStateStore(var state: PeopleState = PeopleState.EMPTY) : PeopleStateStore {
        val saves = mutableListOf<PeopleState>()

        override suspend fun load(): PeopleStateLoad = PeopleStateLoad(state, recoveredFromCorruptFile = false)

        override suspend fun save(state: PeopleState) {
            saves += state
            this.state = state
        }
    }

    private fun repository(
        clusters: MutableList<FaceCluster>,
        files: MutableMap<String, MutableList<Long>>,
        store: FakeStateStore = FakeStateStore(),
        engineAvailable: Boolean = true,
    ): Pair<PeopleRepository, FakeEngine> {
        val engine = FakeEngine(clusters, files, engineAvailable)
        return PeopleRepository(engine, store) to engine
    }

    // ---- the tests ----

    @Test
    fun `snapshot carries the live clusters plus the persisted names and hidden`() = runBlocking {
        val clusters = mutableListOf(
            FaceCluster("c1", 10, 5L),
            FaceCluster("c2", 4, 6L),
        )
        val store = FakeStateStore(PeopleState(names = mapOf("c1" to "Alice"), hidden = setOf("c2")))
        val (repo, _) = repository(clusters, mutableMapOf(), store)

        val snapshot = repo.snapshot()

        assertEquals(clusters, snapshot.clusters)
        assertEquals(mapOf("c1" to "Alice"), snapshot.names)
        assertEquals(setOf("c2"), snapshot.hidden)
        assertFalse(snapshot.recoveredFromCorruptFile)
        // Nothing was stale -> no save-back happened:
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun `snapshot prunes stale ids and persists the pruned copy back`() = runBlocking {
        val clusters = mutableListOf(FaceCluster("live", 3, 1L))
        // A re-clustering pass orphaned "dead" (name) and "gone" (hidden):
        val store = FakeStateStore(
            PeopleState(names = mapOf("live" to "Alice", "dead" to "Bob"), hidden = setOf("gone")),
        )
        val (repo, _) = repository(clusters, mutableMapOf(), store)

        val snapshot = repo.snapshot()

        assertEquals(mapOf("live" to "Alice"), snapshot.names)
        assertEquals(emptySet<String>(), snapshot.hidden)
        // The pruned state was saved back (dead ids never accumulate):
        assertEquals(listOf(PeopleState(names = mapOf("live" to "Alice"))), store.saves)
    }

    @Test
    fun `visible people - the ViewModel composition - is clusters minus hidden with names`() = runBlocking {
        val clusters = mutableListOf(
            FaceCluster("big", 12, 1L),
            FaceCluster("named", 9, null),
            FaceCluster("hidden1", 7, 2L),
        )
        val (repo, _) = repository(
            clusters,
            mutableMapOf(),
            // "hidden1" carries a name AND sits in the hidden set: the
            // composition must drop it (a hidden cluster's name must never
            // resurrect it in the visible list).
            FakeStateStore(
                PeopleState(
                    names = mapOf("big" to "Alice", "hidden1" to "Carol"),
                    hidden = setOf("hidden1"),
                ),
            ),
        )

        val snapshot = repo.snapshot()
        val visible = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = repo.engineAvailable,
            clusters = snapshot.clusters,
            hiddenIds = snapshot.hidden,
            names = snapshot.names,
        ) as PeopleContentState.Ready

        assertEquals(
            listOf(
                VisiblePerson("big", "Alice", 12, 1L),
                VisiblePerson("named", null, 9, null),
            ),
            visible.visible,
        )
    }

    @Test
    fun `rename persists through the state store - blank removes the entry`() = runBlocking {
        val (repo, engine) = repository(
            mutableListOf(FaceCluster("c1", 3, 1L)),
            mutableMapOf("c1" to mutableListOf(1L, 2L, 3L)),
        )

        runBlocking { repo.rename("c1", "  Alice  ") }
        assertEquals(mapOf("c1" to "Alice"), runBlocking { repo.snapshot().names })

        runBlocking { repo.rename("c1", "") }
        assertEquals(emptyMap<String, String>(), runBlocking { repo.snapshot().names })
        // The engine was never touched by a rename:
        assertTrue(engine.merges.isEmpty() && engine.removals.isEmpty())
    }

    @Test
    fun `merge calls the seam with the dialog's direction then the next snapshot reflects it`() {
        val clusters = mutableListOf(
            FaceCluster("src", 3, 1L),
            FaceCluster("tgt", 5, 2L),
        )
        val files = mutableMapOf(
            "src" to mutableListOf(10L, 11L, 12L),
            "tgt" to mutableListOf(20L, 21L, 22L, 23L, 24L),
        )
        val store = FakeStateStore(PeopleState(names = mapOf("src" to "Soon Gone")))
        val (repo, engine) = repository(clusters, files, store)

        val ok = runBlocking { repo.merge("src", "tgt") }

        assertTrue(ok)
        // Exactly one seam call, source-then-target (the dialog's encoding):
        assertEquals(listOf("src" to "tgt"), engine.merges)
        // The repository holds no cache: the next snapshot reads the merged
        // live table (source gone) AND prunes the orphaned source name:
        val snapshot = runBlocking { repo.snapshot() }
        assertEquals(listOf(FaceCluster("tgt", 8, 2L)), snapshot.clusters)
        assertEquals(emptyMap<String, String>(), snapshot.names)
        assertEquals(8, engine.clusterFileIds("tgt").size)
    }

    @Test
    fun `removePhotos calls the seam with exactly the given file ids`() = runBlocking {
        val clusters = mutableListOf(FaceCluster("c1", 4, 1L))
        val files = mutableMapOf("c1" to mutableListOf(1L, 2L, 3L, 4L))
        val (repo, engine) = repository(clusters, files)

        val ok = runBlocking { repo.removePhotos("c1", longArrayOf(2L, 4L)) }

        assertTrue(ok)
        // Exactly one seam call with exactly the given ids (LongArray is
        // reference-compared by Object.equals, so the ids are asserted
        // element-wise, not via the Pair's toString):
        assertEquals(1, engine.removals.size)
        assertEquals("c1", engine.removals[0].first)
        assertArrayEquals(longArrayOf(2L, 4L), engine.removals[0].second)
        assertEquals(listOf(1L, 3L), engine.clusterFileIds("c1"))
    }

    @Test
    fun `hide persists the hidden membership`() = runBlocking {
        val (repo, _) = repository(
            mutableListOf(FaceCluster("c1", 3, 1L)),
            mutableMapOf("c1" to mutableListOf(1L, 2L, 3L)),
        )

        runBlocking { repo.hide("c1") }

        val snapshot = runBlocking { repo.snapshot() }
        assertEquals(setOf("c1"), snapshot.hidden)
        // Hiding never removes the cluster from the live table:
        assertEquals(1, snapshot.clusters.size)
    }

    @Test
    fun `engineAvailable is the seam's own build-shape flag`() {
        val (repo, _) = repository(
            mutableListOf(),
            mutableMapOf(),
            engineAvailable = false,
        )
        assertFalse(repo.engineAvailable)
        val (repoOk, _) = repository(mutableListOf(), mutableMapOf(), engineAvailable = true)
        assertTrue(repoOk.engineAvailable)
    }

    // ---- the r26 overlay: simulated recluster passes ----

    @Test
    fun `a simulated recluster keeps a name through the overlay`() = runBlocking {
        val clusters = mutableListOf(FaceCluster("a1", 3, 10L))
        val files = mutableMapOf("a1" to mutableListOf(10L, 11L, 12L))
        val store = FakeStateStore()
        val (repo, engine) = repository(clusters, files, store)

        repo.rename("a1", "Alice")
        // The edit captured the membership fingerprint (the stable identity):
        assertEquals(mapOf("a1" to setOf(10L, 11L, 12L)), store.state.memberships)

        // The queue drains and RE-CLUSTERS: fresh random id, same photos.
        clusters.clear()
        clusters += FaceCluster("x9", 3, 10L)
        files.clear()
        files["x9"] = mutableListOf(10L, 11L, 12L)

        val snapshot = repo.snapshot()

        // The name survived on the person's new id:
        assertEquals(mapOf("x9" to "Alice"), snapshot.names)
        assertEquals(listOf(FaceCluster("x9", 3, 10L)), snapshot.clusters)
        // Keeping a name is a pure re-key: the engine was never mutated.
        assertTrue(engine.merges.isEmpty() && engine.removals.isEmpty())
        // The persisted state is re-keyed too (the next snapshot is a no-op):
        assertEquals(mapOf("x9" to "Alice"), store.state.names)
        assertEquals(mapOf("x9" to setOf(10L, 11L, 12L)), store.state.memberships)
    }

    @Test
    fun `a simulated recluster keeps a hidden person hidden`() = runBlocking {
        val clusters = mutableListOf(FaceCluster("h1", 2, 5L))
        val files = mutableMapOf("h1" to mutableListOf(5L, 6L))
        val (repo, engine) = repository(clusters, files)

        repo.hide("h1")

        clusters.clear()
        clusters += FaceCluster("z2", 2, 5L)
        files.clear()
        files["z2"] = mutableListOf(5L, 6L)

        val snapshot = repo.snapshot()

        assertEquals(setOf("z2"), snapshot.hidden)
        // Hidden stays hidden with no engine mutation:
        assertTrue(engine.merges.isEmpty() && engine.removals.isEmpty())
        // And the policy still maps the person into the hidden half:
        val hidden = PeopleContentPolicy.hiddenPersons(snapshot.clusters, snapshot.hidden, snapshot.names)
        assertEquals(listOf(VisiblePerson("z2", null, 2, 5L)), hidden)
    }

    @Test
    fun `a simulated recluster re-folds a merge the pass split apart`() = runBlocking {
        val clusters = mutableListOf(
            FaceCluster("src", 1, 10L),
            FaceCluster("tgt", 2, 20L),
        )
        val files = mutableMapOf(
            "src" to mutableListOf(10L),
            "tgt" to mutableListOf(20L, 21L),
        )
        val store = FakeStateStore()
        val (repo, engine) = repository(clusters, files, store)

        repo.rename("tgt", "Taylor")
        assertTrue(repo.merge("src", "tgt"))
        // The merge intent is on record with the post-merge membership:
        assertEquals(listOf(PeopleMerge("tgt", setOf(10L, 20L, 21L))), store.state.merges)

        // The next pass splits the merged person back into its halves:
        clusters.clear()
        clusters += listOf(FaceCluster("x9", 2, 20L), FaceCluster("y7", 1, 10L))
        files.clear()
        files["x9"] = mutableListOf(20L, 21L)
        files["y7"] = mutableListOf(10L)

        val snapshot = repo.snapshot()

        // The overlay re-folded the split: ONE cluster with all the faces...
        assertEquals(listOf(FaceCluster("x9", 3, 20L)), snapshot.clusters)
        // ...by exactly one engine re-merge (the recorded merge + the re-fold):
        assertEquals(listOf("src" to "tgt", "y7" to "x9"), engine.merges)
        // The name and the intent survived, re-keyed onto the fold's home:
        assertEquals(mapOf("x9" to "Taylor"), snapshot.names)
        assertEquals(listOf(PeopleMerge("x9", setOf(10L, 20L, 21L))), store.state.merges)
    }

    @Test
    fun `a simulated recluster re-applies a removal the pass re-derived`() = runBlocking {
        val clusters = mutableListOf(FaceCluster("p", 3, 1L))
        val files = mutableMapOf("p" to mutableListOf(1L, 2L, 3L))
        val store = FakeStateStore()
        val (repo, engine) = repository(clusters, files, store)

        repo.rename("p", "Pat")
        assertTrue(repo.removePhotos("p", longArrayOf(2L)))
        // The correction is on record, and the fingerprint is post-removal:
        assertEquals(mapOf("p" to setOf(2L)), store.state.removals)
        assertEquals(mapOf("p" to setOf(1L, 3L)), store.state.memberships)

        // The pass re-derived the removed face RIGHT BACK into the person:
        clusters.clear()
        clusters += FaceCluster("q", 3, 1L)
        files.clear()
        files["q"] = mutableListOf(1L, 2L, 3L)

        val snapshot = repo.snapshot()

        // The overlay re-removed the face (the user's correction stands):
        assertEquals(2, engine.removals.size)
        assertEquals("q", engine.removals[1].first)
        assertArrayEquals(longArrayOf(2L), engine.removals[1].second)
        assertEquals(listOf(FaceCluster("q", 2, 1L)), snapshot.clusters)
        assertEquals(mapOf("q" to "Pat"), snapshot.names)
        // ...and the corrected fingerprint is what persists:
        assertEquals(mapOf("q" to setOf(1L, 3L)), store.state.memberships)
    }

    @Test
    fun `unhide restores a hidden person without a queue reset`() = runBlocking {
        val clusters = mutableListOf(FaceCluster("c1", 3, 1L))
        val files = mutableMapOf("c1" to mutableListOf(1L, 2L, 3L))
        val (repo, engine) = repository(clusters, files)

        repo.hide("c1")
        repo.unhide("c1")

        val snapshot = repo.snapshot()

        assertEquals(emptySet<String>(), snapshot.hidden)
        assertEquals(listOf(FaceCluster("c1", 3, 1L)), snapshot.clusters)
        // Unhide is a pure state flip: the engine was never touched (the
        // r25 way back --- the queue-resetting r24 Reset --- is gone):
        assertTrue(engine.merges.isEmpty() && engine.removals.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), engine.clusterFileIds("c1"))
    }

    @Test
    fun `a hidden person rides the policy's hidden mapping - then unhides back into view`() = runBlocking {
        val clusters = mutableListOf(
            FaceCluster("c1", 3, 1L),
            FaceCluster("c2", 1, 2L),
        )
        val files = mutableMapOf(
            "c1" to mutableListOf(1L, 2L, 3L),
            "c2" to mutableListOf(9L),
        )
        val (repo, _) = repository(clusters, files)

        repo.rename("c1", "Carol")
        repo.hide("c1")

        val hiddenSnapshot = repo.snapshot()
        val hidden = PeopleContentPolicy.hiddenPersons(
            hiddenSnapshot.clusters,
            hiddenSnapshot.hidden,
            hiddenSnapshot.names,
        )
        assertEquals(listOf(VisiblePerson("c1", "Carol", 3, 1L)), hidden)

        repo.unhide("c1")
        val snapshot = repo.snapshot()
        val visible = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = repo.engineAvailable,
            clusters = snapshot.clusters,
            hiddenIds = snapshot.hidden,
            names = snapshot.names,
        ) as PeopleContentState.Ready

        // Back in view, with the name the hide never erased:
        assertEquals(
            listOf(VisiblePerson("c1", "Carol", 3, 1L), VisiblePerson("c2", null, 1, 2L)),
            visible.visible,
        )
        assertEquals(
            emptyList<VisiblePerson>(),
            PeopleContentPolicy.hiddenPersons(snapshot.clusters, snapshot.hidden, snapshot.names),
        )
    }

    @Test
    fun `a declined re-fold aborts the reconcile without saving - then self-heals`() = runBlocking {
        val clusters = mutableListOf(
            FaceCluster("src", 1, 10L),
            FaceCluster("tgt", 2, 20L),
        )
        val files = mutableMapOf(
            "src" to mutableListOf(10L),
            "tgt" to mutableListOf(20L, 21L),
        )
        val store = FakeStateStore()
        val (repo, engine) = repository(clusters, files, store)

        repo.rename("tgt", "Taylor")
        assertTrue(repo.merge("src", "tgt"))

        clusters.clear()
        clusters += listOf(FaceCluster("x9", 2, 20L), FaceCluster("y7", 1, 10L))
        files.clear()
        files["x9"] = mutableListOf(20L, 21L)
        files["y7"] = mutableListOf(10L)

        // The engine declines the re-fold: the reconcile must abort with
        // the OLD state intact (self-healing, never corrupting)...
        engine.declineMerges = true
        val aborted = repo.snapshot()
        assertEquals(listOf(FaceCluster("x9", 2, 20L), FaceCluster("y7", 1, 10L)), aborted.clusters)
        assertEquals(mapOf("tgt" to "Taylor"), store.state.names)

        // ...and the next read, with the engine healthy again, converges:
        engine.declineMerges = false
        val healed = repo.snapshot()
        assertEquals(listOf(FaceCluster("x9", 3, 20L)), healed.clusters)
        assertEquals(mapOf("x9" to "Taylor"), healed.names)
        assertEquals(listOf("src" to "tgt", "y7" to "x9"), engine.merges)
    }

    @Test
    fun `an edit made on a stale id after a recluster is re-keyed`() = runBlocking {
        val clusters = mutableListOf(FaceCluster("a1", 2, 10L))
        val files = mutableMapOf("a1" to mutableListOf(10L, 11L))
        val store = FakeStateStore()
        val (repo, _) = repository(clusters, files, store)

        repo.rename("a1", "Alice")

        // The recluster lands between the UI's snapshot and the rename
        // tap: the screen still holds the OLD id.
        clusters.clear()
        clusters += FaceCluster("x9", 2, 10L)
        files.clear()
        files["x9"] = mutableListOf(10L, 11L)

        repo.rename("a1", "Alicia")

        // The edit path reconciled first and re-keyed the rename:
        assertEquals(mapOf("x9" to "Alicia"), store.state.names)
    }

    @Test
    fun `merge records the intent and folds the source edits away`() = runBlocking {
        val clusters = mutableListOf(
            FaceCluster("src", 1, 10L),
            FaceCluster("tgt", 2, 20L),
        )
        val files = mutableMapOf(
            "src" to mutableListOf(10L),
            "tgt" to mutableListOf(20L, 21L),
        )
        val store = FakeStateStore()
        val (repo, _) = repository(clusters, files, store)

        repo.rename("src", "Doomed")
        assertTrue(repo.merge("src", "tgt"))

        // The target's identity wins (the r25 rule, now recorded): the
        // source's name is gone, the merge intent + post-merge membership
        // sit on the target.
        assertEquals(emptyMap<String, String>(), store.state.names)
        assertEquals(listOf(PeopleMerge("tgt", setOf(10L, 20L, 21L))), store.state.merges)
        assertEquals(mapOf("tgt" to setOf(10L, 20L, 21L)), store.state.memberships)
    }

    @Test
    fun `removePhotos records the removal intent with the post-removal fingerprint`() = runBlocking {
        val clusters = mutableListOf(FaceCluster("c1", 4, 1L))
        val files = mutableMapOf("c1" to mutableListOf(1L, 2L, 3L, 4L))
        val store = FakeStateStore()
        val (repo, _) = repository(clusters, files, store)

        assertTrue(repo.removePhotos("c1", longArrayOf(2L, 4L)))

        assertEquals(mapOf("c1" to setOf(2L, 4L)), store.state.removals)
        assertEquals(mapOf("c1" to setOf(1L, 3L)), store.state.memberships)
    }
}
