package io.github.dukeoftoad.gallery.ml.people

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 3 of the r28 three-gate vault-exclusion proof (Workstream 8,
 * Phase 5 hardening): the PRE-PERSISTENCE half --- the People surfaces
 * (the r25 product layer over the face index) must never persist,
 * derive or display state for photos that left the public library, and
 * the face-derived data that IS persisted must stay on the device.
 *
 * After gates 1 (`FaceIndexQueueVaultExclusionTest`: a vault move
 * removes the photo from the queue and orphan-deletes its face rows)
 * and 2 (`VaultExclusionReadGateTest`: vault bytes cannot cross the
 * read boundary), the residual exposure this gate covers:
 *
 *  - **stale face rows in the display path**: between a vault move and
 *    the next queue run, the store may still hold rows for a moved
 *    photo. [PeopleRepository.photosOf] resolves a cluster's photos
 *    through the MediaStore binding, and a vault-moved photo has NO
 *    MediaStore row --- it contributes no photo to the person's grid
 *    (its face rows exist, its thumbnail does not; the repository's
 *    own KDoc). This suite pins the pipe: exactly the cluster's
 *    DISTINCT file ids reach the resolver, and a resolver that
 *    resolves nothing (every photo moved to the vault) yields an
 *    empty grid;
 *  - **stale user state**: a cluster emptied by vault moves disappears
 *    at the next recluster; the names/hidden bookkeeping keyed by its
 *    id must not survive the next snapshot (the prune-on-load
 *    contract), because persisting state for a person the user can
 *    never see again is dead data at best;
 *  - **hide is a display gate, not a store mutation**: hiding a person
 *    withholds the cluster from every People surface WITHOUT touching
 *    the engine's rows (un-hiding is a future surface; destroying face
 *    data on hide would make it unimplementable);
 *  - **the persisted face-derived data stays on the device**: the
 *    face-index directory (the ML store, the queue's bookkeeping AND
 *    `people-state.json`) is excluded from cloud backup and device
 *    transfer in BOTH rule files --- an r24 exclusion this suite pins
 *    (BackupRulesTest pins the vault directory; the face-index rows
 *    are the People feature's data-at-rest).
 *
 * See `docs/phase-5/hardening.md` for the three-gate map.
 */
class PeopleVaultExclusionPersistenceTest {

    // ---- the fakes (the PeopleRepositoryTest shapes) ----

    private class FakeEngine(
        private val clusters: MutableList<FaceCluster>,
        private val files: MutableMap<String, List<Long>>,
        override val engineAvailable: Boolean = true,
    ) : PeopleEngineSeam {
        val mutations = mutableListOf<String>()

        override fun faceClusters(): List<FaceCluster> = clusters.toList()

        override fun clusterFileIds(clusterId: String): List<Long> =
            files[clusterId] ?: emptyList()

        override fun mergeFaceClusters(sourceClusterId: String, targetClusterId: String): Boolean {
            mutations += "merge"
            return true
        }

        override fun removeFacesFromCluster(clusterId: String, fileIds: LongArray): Boolean {
            mutations += "remove"
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

    // ---- the photosOf pipe (the display path's vault exclusion) ----

    /**
     * A cluster whose photos ALL moved to the vault resolves to NO
     * photos: the repository hands the cluster's file ids to the
     * MediaStore binding, and the binding (the production
     * `PeopleMediaQuery`) returns only rows that still exist --- a
     * vault-moved photo has none. The pin here is the JVM-testable
     * half: exactly the DISTINCT file ids reach the resolver, and an
     * all-vault resolution yields the empty grid, never a crash and
     * never a stale photo.
     */
    @Test
    fun `a cluster whose photos all moved to the vault resolves to no photos`() = runBlocking {
        val engine = FakeEngine(
            clusters = mutableListOf(FaceCluster("c1", 3, 1L)),
            files = mutableMapOf("c1" to listOf(1L, 2L, 3L)),
        )
        val resolvedIds = mutableListOf<List<Long>>()
        val repo = PeopleRepository(
            engine = engine,
            stateStore = FakeStateStore(),
            queryPhotos = { fileIds ->
                resolvedIds += fileIds
                // Every photo vault-moved: MediaStore resolves none of
                // the ids (their rows are gone --- the sealed blobs live
                // in the app-private vault directory).
                emptyList()
            },
        )

        val photos = repo.photosOf("c1")

        assertTrue("an all-vault cluster must render an empty grid, never stale photos", photos.isEmpty())
        assertEquals(listOf(listOf(1L, 2L, 3L)), resolvedIds)
    }

    /**
     * One photo can contribute SEVERAL faces to one cluster; the
     * repository dedupes before resolving, so the resolver (and the
     * grid) sees each photo once --- and the vault-exclusion above
     * therefore drops the photo exactly once.
     */
    @Test
    fun `photosOf hands the cluster's file ids to the resolver exactly once, deduped`() = runBlocking {
        val engine = FakeEngine(
            clusters = mutableListOf(FaceCluster("c1", 5, 1L)),
            files = mutableMapOf("c1" to listOf(1L, 1L, 2L, 3L, 2L)), // two faces on photos 1 and 2
        )
        val resolvedIds = mutableListOf<List<Long>>()
        val repo = PeopleRepository(
            engine = engine,
            stateStore = FakeStateStore(),
            queryPhotos = { fileIds ->
                resolvedIds += fileIds
                emptyList()
            },
        )

        repo.photosOf("c1")

        assertEquals(
            "the resolver must see each photo id exactly once (distinct), in the seam's order",
            listOf(listOf(1L, 2L, 3L)),
            resolvedIds,
        )
    }

    // ---- stale user state (the prune-on-load contract, vault flavor) ----

    /**
     * A person whose every photo moved to the vault disappears at the
     * next recluster (the queue orphan-deleted the rows; the clustering
     * pass has nothing to group). The user's NAME for that person is
     * stale by then, and the next snapshot must PRUNE it and persist
     * the pruned state back --- the repository never keeps bookkeeping
     * for a person the user can never see again.
     */
    @Test
    fun `a cluster emptied by vault moves prunes its name on the next snapshot`() = runBlocking {
        val store = FakeStateStore(
            PeopleState(
                names = mapOf("c1" to "Alice", "c2" to "Bob"), // c2's photos all vault-moved
                hidden = setOf("c3"), // c3 still live, just hidden
            ),
        )
        // After the vault moves + the next recluster only c1 and c3 exist:
        val engine = FakeEngine(
            clusters = mutableListOf(FaceCluster("c1", 10, 1L), FaceCluster("c3", 2, 7L)),
            files = mutableMapOf("c1" to listOf(1L), "c3" to listOf(7L)),
        )
        val repo = PeopleRepository(engine, store)

        val snapshot = repo.snapshot()

        assertEquals(
            "the vault-emptied person's name must not survive the snapshot",
            mapOf("c1" to "Alice"),
            snapshot.names,
        )
        assertEquals(setOf("c3"), snapshot.hidden) // live-but-hidden stays (hide is a choice, not deletion)
        assertEquals(
            "the pruned state must be persisted back (dead ids never accumulate) " +
                "--- post-r26 the plan also refreshes the surviving edits' membership fingerprints",
            listOf(
                PeopleState(
                    names = mapOf("c1" to "Alice"),
                    hidden = setOf("c3"),
                    memberships = mapOf("c1" to setOf(1L), "c3" to setOf(7L)),
                ),
            ),
            store.saves,
        )
    }

    /**
     * Hide is a DISPLAY gate, not a store mutation: hiding a person
     * persists only the hidden membership in `people-state.json` ---
     * the engine's cluster rows stay untouched (the future unhide
     * surface needs them; destroying face data on hide would make it
     * unimplementable). The exclusion from the visible set happens in
     * the policy mapping, pre-persistence of any derived display state.
     * Post-r26 the persisted state also carries the hidden id's
     * membership fingerprint (the overlay's survival anchor) --- the
     * engine's cluster rows STILL stay untouched.
     */
    @Test
    fun `hide withholds a person from every surface without touching the store`() = runBlocking {
        val engine = FakeEngine(
            clusters = mutableListOf(FaceCluster("c1", 10, 1L), FaceCluster("c2", 4, 2L)),
            files = mutableMapOf("c1" to listOf(1L), "c2" to listOf(2L)),
        )
        val store = FakeStateStore()
        val repo = PeopleRepository(engine, store)

        repo.hide("c2")

        // Persisted: the hidden membership only (plus the r26 fingerprint
        // that lets a hidden flag survive a recluster id re-roll)...
        assertEquals(
            listOf(PeopleState(hidden = setOf("c2"), memberships = mapOf("c2" to setOf(2L)))),
            store.saves,
        )
        // ...and the engine was never asked to mutate anything:
        assertTrue("hide must not mutate the face store", engine.mutations.isEmpty())

        // The visible set (what the ViewModels feed PeopleContentPolicy)
        // excludes the hidden person BEFORE anything is derived from it:
        val snapshot = repo.snapshot()
        val visible = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = true,
            clusters = snapshot.clusters,
            hiddenIds = snapshot.hidden,
            names = snapshot.names,
        )
        assertTrue(visible is PeopleContentState.Ready)
        assertEquals(
            listOf("c1"),
            (visible as PeopleContentState.Ready).visible.map { it.clusterId },
        )
    }

    // ---- the persisted face-derived data stays on the device ----

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    /**
     * The People feature's persisted data --- the ML store's embeddings
     * and cluster assignments, the queue's bookkeeping and
     * `people-state.json` --- lives in the face-index directory, and
     * that directory is excluded from pre-API-31 cloud backup (the
     * r24 exclusion `BackupRulesTest` does not pin; the vault rows
     * there are the vault's own). Face-derived data never leaves the
     * device, backup included.
     */
    @Test
    fun `the face-index directory is excluded from pre-API-31 cloud backup`() {
        val rules = moduleFile("src/main/res/xml/backup_rules.xml").readText()
        assertTrue(
            "backup_rules.xml must exclude domain=\"file\" path=\"face-index\": the People state " +
                "(people-state.json), the ML store and the queue bookkeeping are face-derived data " +
                "and must not leave the device via cloud backup.",
            Regex(
                """<full-backup-content>.*<exclude\s+domain\s*=\s*"file"\s+path\s*=\s*"face-index"\s*/>.*</full-backup-content>""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(rules),
        )
    }

    /**
     * The API-31+ twin: the face-index directory is excluded from BOTH
     * cloud backup AND device-to-device transfer --- the r24 round's
     * on-principle exclusion (nothing in it is KeyStore-bound, but
     * face-derived biometric-adjacent data does not leave the device,
     * either surface).
     */
    @Test
    fun `the face-index directory is excluded from API 31 plus backup and device transfer`() {
        val rules = moduleFile("src/main/res/xml/data_extraction_rules.xml").readText()
        listOf("cloud-backup", "device-transfer").forEach { scope ->
            assertTrue(
                "data_extraction_rules.xml must exclude the face-index directory under <$scope>.",
                Regex("""<$scope>(?s:.)*?<exclude\s+domain\s*=\s*"file"\s+path\s*=\s*"face-index"\s*/>""")
                    .containsMatchIn(rules),
            )
        }
    }
}
