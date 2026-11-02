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
 */
class PeopleRepositoryTest {

    // ---- the fakes ----

    private class FakeEngine(
        private val clusters: MutableList<FaceCluster>,
        /** file ids per cluster id; merged sources fold into targets. */
        private val files: MutableMap<String, MutableList<Long>>,
        override val engineAvailable: Boolean = true,
    ) : PeopleEngineSeam {

        val merges = mutableListOf<Pair<String, String>>()
        val removals = mutableListOf<Pair<String, LongArray>>()

        override fun faceClusters(): List<FaceCluster> = clusters.toList()

        override fun clusterFileIds(clusterId: String): List<Long> =
            files[clusterId]?.toList() ?: emptyList()

        override fun mergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean {
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
}
