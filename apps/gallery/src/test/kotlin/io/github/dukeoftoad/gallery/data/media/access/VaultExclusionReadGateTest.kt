package io.github.dukeoftoad.gallery.data.media.access

import io.github.dukeoftoad.gallery.data.media.identity.EligiblePublicMedia
import io.github.dukeoftoad.gallery.data.media.identity.MediaEligibility
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import io.github.dukeoftoad.gallery.ml.index.FaceIndexCandidate
import io.github.dukeoftoad.gallery.ml.index.FaceIndexQueuePlanner
import io.github.dukeoftoad.gallery.ml.index.FaceIndexQueueProcessor
import io.github.dukeoftoad.gallery.ml.index.FaceIndexStateStore
import io.github.dukeoftoad.gallery.ml.index.IndexCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GATE 2 of the r28 three-gate vault-exclusion proof (Workstream 8,
 * Phase 5 hardening): between ENQUEUE (gate 1,
 * `FaceIndexQueueVaultExclusionTest`) and PERSISTENCE (gate 3,
 * `PeopleVaultExclusionPersistenceTest`) sits the audit's READ
 * BOUNDARY --- the moment original media bytes could start flowing ---
 * and vault media must be excluded BEFORE THE FIRST BYTE.
 *
 * The boundary's pieces (Phase 4 contract change 5; no new production
 * code --- this suite pins the existing contract):
 *
 *  - **the type-level gate**: `MediaAccess.open` accepts ONLY an
 *    [EligiblePublicMedia], and the ONLY way to obtain one is
 *    `MediaEligibility.asEligible`, which returns `null` for
 *    [MediaEligibility.State.VAULT_EXCLUDED]. A vault item therefore
 *    cannot even be HANDED to the read boundary --- there is no
 *    overload for a bare identity, a raw Uri or a string;
 *  - **the read-time permission re-check**: the opener re-verifies
 *    media permission through [MediaPermissionGate] right before it
 *    mints a handle (`ContentResolverMediaAccess.open` checks the gate
 *    BEFORE constructing its lazy handle) --- pinned here by contract
 *    fakes AND by a source-order tripwire on the production file;
 *  - **the byte cap**: a well-behaved [MediaReadHandle] refuses ---
 *    whole, never truncated --- any payload above the caller's cap, so
 *    a pathological blob costs nothing;
 *  - **the queue's consumption of the boundary**: when a queued
 *    photo's read comes back `null` (vault move, revoked grant,
 *    vanished row), the r24 processor counts it unreadable and
 *    persists NOTHING for it: no detection call, no face rows, no
 *    state record.
 *
 * See `docs/phase-5/hardening.md` for the three-gate map.
 */
class VaultExclusionReadGateTest {

    private fun identity(id: Long = 42, uri: String = "content://media/external/images/$id"): MediaIdentity =
        MediaIdentity(volumeName = "external_primary", mediaStoreId = id, canonicalUri = uri)

    /**
     * A [MediaAccess] implementing the boundary's documented contract
     * (the shape `ContentResolverMediaAccess` follows): gate first,
     * then the handle. Records every opened identity so the tests can
     * prove a vault item never reached the OPEN call at all.
     */
    private class ContractAccess(private val gate: MediaPermissionGate) : MediaAccess {
        val opened = mutableListOf<Long>()

        override suspend fun open(media: EligiblePublicMedia): MediaReadHandle? {
            if (!gate.stillHasAccess(media)) return null // gate 2: refused BEFORE a handle exists
            opened += media.identity.mediaStoreId
            return BoundedHandle(media.identity.mediaStoreId.toString().toByteArray())
        }
    }

    /** A [MediaPermissionGate] the tests script. */
    private class ScriptedGate(var allow: Boolean) : MediaPermissionGate {
        override suspend fun stillHasAccess(media: EligiblePublicMedia): Boolean = allow
    }

    /** A handle honoring the cap contract: refuse whole, never truncate. */
    private class BoundedHandle(private val payload: ByteArray) : MediaReadHandle {
        var reads = 0

        override suspend fun readBytes(maxBytes: Long): ByteArray? {
            reads++
            return if (payload.size.toLong() > maxBytes) null else payload.copyOf()
        }
    }

    // The type-level gate --------------------------------------------------------

    /**
     * VAULT_EXCLUDED media can never mint the wrapper the read
     * boundary requires: `asEligible` returns null, so there is no
     * value to pass to ANY `MediaAccess.open` implementation. This is
     * the audit's vault-leakage gate made runtime-provable (the
     * compile-level claim lives in `MediaAccess`'s parameter type ---
     * there is deliberately no other overload).
     */
    @Test
    fun `vault-excluded media cannot mint the wrapper the read boundary requires`() {
        val vaultItem: MediaIdentity = identity(id = 7)

        assertNull(
            "VAULT_EXCLUDED must never produce an EligiblePublicMedia --- vault bytes are " +
                "unreadable through the boundary by construction.",
            MediaEligibility.VAULT_EXCLUDED.asEligible(vaultItem),
        )
        // And the boundary's only public entry point type-checks against
        // exactly that wrapper --- no identity-shaped overload exists.
        // (The JVM signature carries the suspend Continuation as the
        // LAST parameter; the media parameter is first.)
        // JVM bridge/synthetic duplicates collapse: the assertion is about
        // the OVERLOAD SHAPES the boundary exposes, not reflection artifacts.
        val openOverloads = MediaAccess::class.java.methods
            .filter { it.name == "open" && !it.isSynthetic }
            .map { it.parameterTypes.first().simpleName }
            .distinct()
        assertEquals(
            "MediaAccess must expose exactly ONE open() overload, taking EligiblePublicMedia --- " +
                "a second overload (raw identity / Uri / string) would be a hole in the vault gate. " +
                "Found overloads: $openOverloads",
            listOf("EligiblePublicMedia"),
            openOverloads,
        )
    }

    // The read-time permission re-check -------------------------------------------

    /**
     * Gate 2's re-check: a grant that was valid at enqueue time and is
     * GONE at read time must yield no handle and no bytes. The opener
     * consults [MediaPermissionGate] before minting a handle --- the
     * contract `ContentResolverMediaAccess` implements and this fake
     * mirrors.
     */
    @Test
    fun `a refusing permission gate yields no handle and no bytes`() = runBlocking {
        val gate = ScriptedGate(allow = false)
        val access = ContractAccess(gate)
        val eligible = MediaEligibility.PUBLIC_INDEXABLE.asEligible(identity(id = 9))
            ?: error("PUBLIC_INDEXABLE must always mint a wrapper")

        assertNull("a refused gate must refuse the open", access.open(eligible))
        assertTrue("no identity may reach the handle when the gate refuses", access.opened.isEmpty())
    }

    /**
     * Source-order tripwire on the production opener: the permission
     * gate must be consulted BEFORE the resolver handle is constructed
     * in `ContentResolverMediaAccess.open` --- the audit's "gate 2 of 3
     * (enqueue / OPEN / pre-persist)" ordering. A refactor that swaps
     * the two lines would still behave correctly TODAY (the handle is
     * lazy), but the check-before-construction order is the documented
     * contract and this keeps it from drifting silently.
     */
    @Test
    fun `the production opener checks the permission gate before constructing a handle`() {
        val source = moduleFile("src/main/kotlin/io/github/dukeoftoad/gallery/data/media/access/ContentResolverMediaAccess.kt")
            .readText()
        val gateCall = source.indexOf("permissionGate.stillHasAccess(media)")
        val handleConstruction = source.indexOf("ResolverReadHandle(")
        assertTrue(
            "ContentResolverMediaAccess must consult MediaPermissionGate before constructing its " +
                "read handle (the gate-2-of-3 ordering); could not find the gate call.",
            gateCall >= 0,
        )
        assertTrue(
            "ContentResolverMediaAccess must construct ResolverReadHandle inside open(); the " +
                "source-order tripwire needs both anchors.",
            handleConstruction >= 0,
        )
        assertTrue(
            "The permission gate (position $gateCall) must be consulted BEFORE the handle is " +
                "constructed (position $handleConstruction) --- gate 2 of the audit's three-gate rule.",
            gateCall < handleConstruction,
        )
    }

    // The byte cap ----------------------------------------------------------------

    /**
     * The cap is a refusal, never a truncation: a payload one byte
     * above the cap yields `null` (NOT a prefix), and a payload exactly
     * AT the cap flows whole. A vault-adjacent pathological blob (say a
     * mislabeled multi-gigabyte container) therefore costs the pipeline
     * nothing --- no partial bytes that could masquerade as an image.
     */
    @Test
    fun `the byte cap refuses whole and never truncates`() = runBlocking {
        val payload = ByteArray(1_000) { (it % 251).toByte() }

        assertNull("one byte over the cap must refuse", BoundedHandle(payload).readBytes(maxBytes = 999))
        assertEquals(
            "exactly at the cap must flow whole",
            1_000,
            BoundedHandle(payload).readBytes(maxBytes = 1_000)!!.size,
        )
        assertEquals(
            "the default cap is the documented 64 MiB memory guard",
            64L * 1024 * 1024,
            MediaReadHandle.DEFAULT_MAX_BYTES,
        )
    }

    // The queue's consumption of the boundary --------------------------------------

    /** The r24 recording-engine fake (same shape as the queue suite's). */
    private class RecordingEngine : IndexCoordinator.FaceIndexEngine {
        val detectCalls = mutableListOf<String>()
        val putCalls = mutableListOf<Pair<Long, FloatArray>>()

        override fun detectFaceEmbeddings(jpeg: ByteArray): List<MlEngine.FaceEmbedding> {
            detectCalls.add(String(jpeg))
            return emptyList()
        }

        override fun putFace(fileId: Long, embedding: FloatArray): Long {
            putCalls.add(fileId to embedding)
            return putCalls.size.toLong()
        }

        override fun faceCount(): Long = 0L

        override fun clusterFaces(distanceThreshold: Double) = MlEngine.ClusterSummary(0, 0, emptyList())

        override fun indexedFaceFileIds(): List<Long> = emptyList()

        override fun deleteFacesForFiles(fileIds: LongArray): Boolean = true
    }

    private fun stateStore() = object : FaceIndexStateStore {
        var saved: List<io.github.dukeoftoad.gallery.ml.index.FaceIndexedState>? = null
        override suspend fun load(): List<io.github.dukeoftoad.gallery.ml.index.FaceIndexedState> = emptyList()
        override suspend fun save(states: List<io.github.dukeoftoad.gallery.ml.index.FaceIndexedState>) {
            saved = states
        }
    }

    /**
     * The composition the gate exists for: the queue reads plan
     * candidates through the access boundary, and a read that comes
     * back `null` --- vault-moved row, revoked grant, vanished file ---
     * persists NOTHING. Photo 1 is public and readable; photo 2's
     * eligibility is VAULT_EXCLUDED (moved to the vault between plan
     * and read): no wrapper can be minted for it, the boundary is
     * never even opened with it, and the engine never sees a byte of
     * it.
     */
    @Test
    fun `a vault-excluded photo reads nothing and persists nothing through the boundary`() = runBlocking {
        val gate = ScriptedGate(allow = true)
        val access = ContractAccess(gate)
        val engine = RecordingEngine()
        val store = stateStore()

        fun candidate(key: String, fileId: Long) = FaceIndexCandidate(
            localMediaKey = key,
            fileId = fileId,
            revision = io.github.dukeoftoad.gallery.data.media.revision.MediaRevision(
                sizeBytes = 100L,
                dateModifiedMillis = 1_000L,
            ),
            uri = "content://media/external/images/$fileId",
            dateAddedMillis = 5_000L,
        )

        // The queue's read seam, composed over the access boundary the
        // way a production reader would be: eligibility first (the
        // type-level gate), then open, then the bounded read.
        suspend fun readThroughBoundary(fileId: Long): ByteArray? {
            val eligible = MediaEligibility.PUBLIC_INDEXABLE.asEligible(identity(fileId))
                // A vault-moved item cannot mint a wrapper: asEligible
                // returns null and the read is over before it began.
                ?: return null
            return access.open(eligible)?.readBytes()
        }

        val plan = FaceIndexQueuePlanner.plan(
            candidates = listOf(candidate("external_primary:1", 1), candidate("external_primary:2", 2)),
            states = emptyList(),
            storeIndexedFileIds = emptySet(),
        )
        val outcome = FaceIndexQueueProcessor(engine, Dispatchers.Unconfined).processBatch(
            plan = plan,
            stateStore = store,
            readBytes = { candidate ->
                if (candidate.fileId == 2L) {
                    // Photo 2 moved to the vault: its eligibility is
                    // VAULT_EXCLUDED --- the wrapper cannot be minted.
                    MediaEligibility.VAULT_EXCLUDED.asEligible(identity(2))?.let {
                        error("VAULT_EXCLUDED must never mint a wrapper")
                    }
                    null
                } else {
                    readThroughBoundary(candidate.fileId)
                }
            },
            nowMillis = { 11_000L },
        )

        assertEquals(1, outcome.unreadableThisRun)
        assertEquals(1, outcome.indexedThisRun)
        // Only photo 1 crossed the boundary (opened) and reached the engine:
        assertEquals(listOf(1L), access.opened)
        // Photo 1's bytes through the boundary are its file id (the fake
        // handle's payload), so exactly one detection saw exactly that:
        assertEquals(listOf("1"), engine.detectCalls)
        assertTrue("no face rows may be persisted for the vault-excluded photo", engine.putCalls.isEmpty())
        assertEquals(
            "no queue state record may be persisted for the vault-excluded photo",
            listOf("external_primary:1"),
            store.saved!!.map { it.localMediaKey },
        )
    }

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }
}
