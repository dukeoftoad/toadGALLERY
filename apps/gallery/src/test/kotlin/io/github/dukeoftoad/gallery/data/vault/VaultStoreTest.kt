package io.github.dukeoftoad.gallery.data.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * JVM tests for [VaultStore] (issue #32, ADR-0017).
 *
 * The store is pure JVM by design (`java.io` + the [VaultCrypto] seam), so
 * the EXACT production on-disk layout --- sealed PIN record, sealed index,
 * id-named sealed blobs, crash-safety orderings, the integrity sweep, the
 * store's mutual exclusion and the sweep's grace window --- runs under
 * `testDebugUnitTest` against a temp directory and a locally generated
 * AES-256 key (no AndroidKeyStore, no emulator; ADR-0016). The layout the
 * tests reach into (`pin.bin`, `index.bin`, `blobs/<id>.bin`) is the
 * documented contract from the [VaultStore] KDoc.
 *
 * PBKDF2 budget: every [VaultStore.setup]/[VaultStore.verifyPin] call costs
 * one 210k-round derivation (~0.1-0.3 s JVM), so the suite derives about a
 * dozen times total instead of once per assertion.
 *
 * Concurrency rig (the unlock-vs-move race round): [ParkedInputStream]
 * holds a [VaultStore.store] mid-copy INSIDE its serialized section while
 * another thread races [VaultStore.sweepOrphans] or a second `store`
 * against it --- every transition a latch, every await bounded, zero
 * sleeps; the assertions hold under every schedule of the fixed code and
 * fail against the pre-fix unsynchronized store.
 *
 * Privacy (ADR-0010/ADR-0014): everything here stays inside the temp
 * directory; the app holds no INTERNET permission, so no vault byte can
 * ever leave a device.
 */
class VaultStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** The vault's media key in tests: locally generated, standing in for AndroidKeyStore. */
    private val key: SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(VaultCrypto.KEY_BITS) }
        .generateKey()

    /** A second, unrelated key: what a wiped/re-provisioned Keystore looks like. */
    private val otherKey: SecretKey = KeyGenerator
        .getInstance("AES")
        .apply { init(VaultCrypto.KEY_BITS) }
        .generateKey()

    // ---- Fixtures ------------------------------------------------------

    /** A store plus its root directory, so tests can reach the documented files. */
    private class Fixture(val root: File, val store: VaultStore) {
        val blobDir: File get() = File(root, "blobs")
        fun blobOf(id: Long): File = File(blobDir, "$id.bin")
    }

    private fun newFixture(): Fixture {
        val root = tmp.newFolder()
        return Fixture(root, VaultStore(root = root, keys = { key }))
    }

    private fun configuredFixture(): Fixture =
        newFixture().apply { store.setup(PIN.toCharArray()) }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    // ---- Setup + PIN ---------------------------------------------------

    @Test
    fun `a fresh store is not configured and its sweep is a no-op`() {
        val fixture = newFixture()
        assertFalse(fixture.store.isConfigured())
        assertEquals(0, fixture.store.sweepOrphans())
    }

    @Test
    fun `setup persists the PIN record and a sealed empty index`() {
        val fixture = configuredFixture()
        assertTrue(fixture.store.isConfigured())
        assertTrue(fixture.store.entries().isEmpty())
    }

    @Test
    fun `verifyPin accepts the setup pin and rejects a wrong one`() {
        val fixture = configuredFixture()
        assertTrue(fixture.store.verifyPin(PIN.toCharArray()))
        assertFalse(fixture.store.verifyPin("0000".toCharArray()))
    }

    @Test
    fun `double setup is rejected and the original pin still rules`() {
        val fixture = configuredFixture()
        // The documented behavior (VaultStore.setup KDoc): re-running setup
        // must never silently swap the PIN under an existing vault.
        assertThrows(IllegalStateException::class.java) {
            fixture.store.setup("9999".toCharArray())
        }
        assertTrue(fixture.store.verifyPin(PIN.toCharArray()))
        assertFalse(fixture.store.verifyPin("9999".toCharArray()))
    }

    /**
     * Failure injection for setup's second write (issue follow-up to #32):
     * a non-empty directory parked at `index.bin` makes the index commit
     * rename fail. Setup must roll back to a fully unconfigured store ---
     * no pin, no index, no staged `.tmp` residue --- and a retry after the
     * obstruction is removed must succeed.
     */
    @Test
    fun `a failed index commit leaves nothing configured and setup can be retried`() {
        val fixture = newFixture()
        val blocker = File(fixture.root, "index.bin").apply {
            mkdirs()
            File(this, "occupied").writeBytes(byteArrayOf(1))
        }
        assertThrows(VaultStoreException::class.java) {
            fixture.store.setup(PIN.toCharArray())
        }
        assertFalse(fixture.store.isConfigured())
        assertFalse(File(fixture.root, "pin.bin").exists())
        assertTrue(blocker.isDirectory) // untouched by the rollback
        assertEquals(
            "no staged residue survives the rollback",
            emptyList<String>(),
            fixture.root.listFiles()!!.map { it.name }.filter { it.endsWith(".tmp") },
        )
        // Retry after the obstruction is gone: the store is usable.
        blocker.deleteRecursively()
        fixture.store.setup(PIN.toCharArray())
        assertTrue(fixture.store.isConfigured())
        assertTrue(fixture.store.entries().isEmpty())
    }

    /** Same injection against the PIN commit (the second rename): full rollback again. */
    @Test
    fun `a failed pin commit rolls back the already-committed index`() {
        val fixture = newFixture()
        val blocker = File(fixture.root, "pin.bin").apply {
            mkdirs()
            File(this, "occupied").writeBytes(byteArrayOf(1))
        }
        assertThrows(VaultStoreException::class.java) {
            fixture.store.setup(PIN.toCharArray())
        }
        assertFalse(fixture.store.isConfigured())
        assertFalse(
            "the index committed before the pin failure must be rolled back",
            File(fixture.root, "index.bin").exists(),
        )
        assertTrue(blocker.isDirectory)
        // Retry after the obstruction is gone: the store is usable.
        blocker.deleteRecursively()
        fixture.store.setup(PIN.toCharArray())
        assertTrue(fixture.store.isConfigured())
    }

    /** `isConfigured` requires BOTH files; a pin-only residue must not lie. */
    @Test
    fun `isConfigured is false when the index is missing even though the pin record exists`() {
        val fixture = configuredFixture()
        assertTrue(File(fixture.root, "index.bin").delete())
        assertFalse(fixture.store.isConfigured())
    }

    /**
     * A one-file residue from a pre-transactional crash self-heals: setup
     * removes the stale remnant and proceeds fresh instead of wedging the
     * store between "configured" and "loadable".
     */
    @Test
    fun `a broken pin-without-index residue self-heals on setup`() {
        val fixture = newFixture()
        // Simulate the old bug's on-disk state: pin present, index lost.
        File(fixture.root, "pin.bin").writeBytes(randomBytes(64))
        fixture.store.setup(PIN.toCharArray())
        assertTrue(fixture.store.isConfigured())
        assertTrue(fixture.store.verifyPin(PIN.toCharArray()))
        assertTrue(fixture.store.entries().isEmpty())
    }

    // ---- Store / list / open -------------------------------------------

    @Test
    fun `store lists the entry with metadata intact and open streams back the exact bytes`() {
        val fixture = configuredFixture()
        val payload = randomBytes(300_000)
        val entry = fixture.store.store(
            displayName = DISPLAY_NAME,
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = ADDED_AT,
            bytes = ByteArrayInputStream(payload),
            // Deliberately wrong: the store records the ACTUAL streamed size.
            length = 999_999L,
        )
        val entries = fixture.store.entries()
        assertEquals(listOf(entry), entries)
        assertEquals(DISPLAY_NAME, entries.single().displayName)
        assertEquals("image/jpeg", entries.single().mimeType)
        assertEquals(payload.size.toLong(), entries.single().sizeBytes)
        assertEquals(ADDED_AT, entries.single().addedAtMillis)
        assertFalse(entries.single().isVideo)
        val opened = fixture.store.open(entry).use { it.readBytes() }
        assertTrue("open() must stream back the exact plaintext", payload.contentEquals(opened))
    }

    @Test
    fun `an id freed by delete may be reused without collision`() {
        val fixture = configuredFixture()
        val first = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(4)),
            length = 4L,
        )
        fixture.store.delete(first)
        val second = fixture.store.store(
            displayName = "b.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 2L,
            bytes = ByteArrayInputStream(ByteArray(4)),
            length = 4L,
        )
        // The documented allocation rule (VaultIndex.nextId): one past the
        // CURRENT maximum, so a deleted tail id is re-allocated. That is
        // collision-free --- delete() removed the blob first, and any crash
        // orphan left behind is reclaimed by the sweep or safely overwritten.
        assertEquals(first.id, second.id)
        val opened = fixture.store.open(second).use { it.readBytes() }
        assertEquals(4, opened.size)
    }

    @Test
    fun `file names and raw bytes never leak the display name`() {
        val fixture = configuredFixture()
        val secretName = "very-private-leaf.jpg"
        fixture.store.store(
            displayName = secretName,
            mimeType = "image/png",
            isVideo = true,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(16)),
            length = 16L,
        )
        // Blob files are named by stable id, never by content (ADR-0017).
        assertEquals(listOf("1.bin"), fixture.blobDir.list()!!.toList())
        // The index is sealed: its raw bytes must not contain the name.
        val rawIndex = File(fixture.root, "index.bin").readBytes()
        assertFalse(String(rawIndex, Charsets.ISO_8859_1).contains(secretName))
    }

    // ---- Tamper / truncation -------------------------------------------

    @Test
    fun `a tampered blob fails authentication while reading`() {
        val fixture = configuredFixture()
        val payload = randomBytes(64 * 1024)
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        val blob = fixture.blobOf(entry.id)
        val sealed = blob.readBytes()
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 1).toByte()
        blob.writeBytes(sealed)
        assertThrows(Exception::class.java) {
            fixture.store.open(entry).use { it.readBytes() }
        }
    }

    @Test
    fun `a truncated blob fails authentication while reading`() {
        val fixture = configuredFixture()
        val payload = randomBytes(64 * 1024)
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        val blob = fixture.blobOf(entry.id)
        val sealed = blob.readBytes()
        // Half the blob: the IV survives, the GCM tag does not.
        blob.writeBytes(sealed.copyOfRange(0, sealed.size / 2))
        assertThrows(Exception::class.java) {
            fixture.store.open(entry).use { it.readBytes() }
        }
    }

    // ---- Crash safety + sweep ------------------------------------------

    @Test
    fun `a failed seal leaves no index entry and no blob`() {
        val fixture = configuredFixture()
        assertThrows(IOException::class.java) {
            fixture.store.store(
                displayName = "a.jpg",
                mimeType = "image/jpeg",
                isVideo = false,
                addedAtMillis = 1L,
                bytes = ExplodingInputStream(),
                length = 100L,
            )
        }
        assertTrue(fixture.store.entries().isEmpty())
        val blobs = fixture.blobDir.list()
        assertTrue(blobs != null && blobs.isEmpty())
    }

    @Test
    fun `an orphan blob with no index entry is swept away`() {
        val fixture = configuredFixture()
        val payload = randomBytes(1024)
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        // The crash shape from ADR-0017: a sealed blob whose index write
        // never landed --- AGED here past the sweep's grace window, so this
        // test pins the reclaim itself (the grace test below pins the
        // deferral a fresh orphan gets).
        val orphan = File(fixture.blobDir, "42.bin").apply {
            writeBytes(ByteArray(32))
            agePastSweepGrace()
        }
        assertEquals(1, fixture.store.sweepOrphans())
        assertFalse(orphan.exists())
        // The indexed blob survives untouched.
        assertTrue(fixture.blobOf(entry.id).isFile)
        assertEquals(listOf(entry), fixture.store.entries())
    }

    @Test
    fun `the sweep also reclaims stale tmp files`() {
        val fixture = configuredFixture()
        // "Stale" is the sweep's honest bar: a `.tmp` fresh enough to sit
        // inside the grace window is deferred exactly like a fresh blob
        // (an in-flight atomicWrite must not be yanked mid-write).
        val stale = File(fixture.root, "index.bin.tmp").apply {
            writeBytes(ByteArray(8))
            agePastSweepGrace()
        }
        assertEquals(1, fixture.store.sweepOrphans())
        assertFalse(stale.exists())
    }

    /**
     * The sweep's grace window (see [VaultStore.sweepOrphans]): a FRESH
     * orphan --- the crash-mid-move residue, or a blob written by any
     * writer this store's monitor cannot serialize (a second instance, a
     * future unlocked path) --- must be DEFERRED, never deleted; the same
     * file becomes reclaimable once it ages past the window. Deferred is
     * not kept: the second sweep reclaims it.
     */
    @Test
    fun `a fresh orphan is deferred by the sweep grace and reclaimed once aged`() {
        val fixture = configuredFixture()
        val payload = randomBytes(1024)
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        val orphan = File(fixture.blobDir, "42.bin").apply { writeBytes(ByteArray(32)) }
        val freshTmp = File(fixture.root, "pin.bin.tmp").apply { writeBytes(ByteArray(4)) }
        // Fresh (mtime = now): deferred on both fronts --- blob and tmp.
        // Deleting young files is exactly the unlock-vs-move data loss the
        // grace window exists to close.
        assertEquals(0, fixture.store.sweepOrphans())
        assertTrue(orphan.isFile)
        assertTrue(freshTmp.isFile)
        assertTrue(fixture.blobOf(entry.id).isFile)
        // Aged past the window: the next sweep reclaims both.
        orphan.agePastSweepGrace()
        freshTmp.agePastSweepGrace()
        assertEquals(2, fixture.store.sweepOrphans())
        assertFalse(orphan.exists())
        assertFalse(freshTmp.exists())
        // The indexed blob survives both sweeps untouched.
        assertTrue(fixture.blobOf(entry.id).isFile)
        assertEquals(listOf(entry), fixture.store.entries())
    }

    // ---- Concurrency (the P0 unlock-vs-move race) ----------------------

    /**
     * THE audit finding: a [VaultStore.sweepOrphans] that runs while a
     * [VaultStore.store] is parked mid-copy must not delete the in-flight
     * blob. The blob is deliberately AGED past the sweep's grace window
     * before the sweeper starts, so ONLY the store's serialization --- not
     * the grace --- can save it: against the pre-fix unsynchronized store
     * the sweep reads the OLD index mid-move, judges the aged blob an
     * orphan, deletes it, and the move then commits an index entry
     * pointing at nothing while the caller deletes the original
     * MediaStore row --- permanent loss.
     *
     * Deterministic by construction: every transition is a latch (no
     * sleeps), and the parked mover holds the store monitor from the
     * moment [ParkedInputStream] opens its park latch until the test
     * resumes it, so the sweeper can only run strictly BEFORE the store's
     * index read (nothing fresh to delete) or strictly AFTER its index
     * commit (the blob is known).
     */
    @Test
    fun `a sweep concurrent with a parked store cannot delete the in-flight blob`() {
        val fixture = configuredFixture()
        val payload = randomBytes(64)
        val midCopy = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val moveDone = CountDownLatch(1)
        val sweepEntered = CountDownLatch(1)
        val sweepDone = CountDownLatch(1)
        val swept = AtomicInteger(-1)
        val moveFailure = AtomicReference<Throwable?>(null)
        val mover = Thread {
            try {
                fixture.store.store(
                    displayName = "a.jpg",
                    mimeType = "image/jpeg",
                    isVideo = false,
                    addedAtMillis = 1L,
                    bytes = ParkedInputStream(payload, midCopy, resume),
                    length = payload.size.toLong(),
                )
            } catch (t: Throwable) {
                moveFailure.set(t)
            } finally {
                moveDone.countDown()
            }
        }.apply { isDaemon = true }
        try {
            mover.start()
            // The mover now sits mid-copy INSIDE the store's serialized
            // section: the blob file is open on disk (its leading IV
            // written) while the index still knows nothing about it --- the
            // exact shape the pre-fix sweep judged "orphan".
            assertTrue(midCopy.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            // Age the in-flight blob past the grace window: the lock, not
            // the grace, must be what saves it (KDoc above).
            fixture.blobDir.listFiles()!!.single().agePastSweepGrace()
            val sweeper = Thread {
                sweepEntered.countDown()
                swept.set(fixture.store.sweepOrphans())
                sweepDone.countDown()
            }.apply { isDaemon = true }
            sweeper.start()
            // The sweeper is at the monitor's door while the mover is parked
            // inside; letting the mover finish releases exactly one of the
            // two legal orders (never the mid-move one).
            assertTrue(sweepEntered.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            resume.countDown()
            assertTrue(moveDone.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(sweepDone.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            // Even a failed await must never strand the parked mover.
            resume.countDown()
        }
        assertNull(moveFailure.get())
        assertEquals(0, swept.get())
        // The blob survived the concurrent sweep and the entry committed.
        val entry = fixture.store.entries().single()
        assertTrue(fixture.blobOf(entry.id).isFile)
        val opened = fixture.store.open(entry).use { it.readBytes() }
        assertTrue(
            "the move's blob must stream back its exact plaintext",
            payload.contentEquals(opened),
        )
    }

    /**
     * The MOVE-vs-MOVE interleave of the same mutual-exclusion story (see
     * [VaultStore]'s KDoc): unsynchronized, two concurrent stores read the
     * same index, computed the same [VaultIndex.nextId], and the second
     * store overwrote the first's blob while its index write dropped the
     * first's entry. Serialized, the second store runs strictly after the
     * first's index commit: distinct ids, both blobs intact, both entries
     * listed. Same latch rig as the sweep test above.
     */
    @Test
    fun `two concurrent stores serialize and both entries land intact`() {
        val fixture = configuredFixture()
        val first = randomBytes(64)
        val second = randomBytes(48)
        val midCopyA = CountDownLatch(1)
        val resumeA = CountDownLatch(1)
        val midCopyB = CountDownLatch(1)
        val resumeB = CountDownLatch(1)
        val doneA = CountDownLatch(1)
        val doneB = CountDownLatch(1)
        val failureA = AtomicReference<Throwable?>(null)
        val failureB = AtomicReference<Throwable?>(null)
        val moverA = Thread {
            try {
                fixture.store.store(
                    displayName = "a.jpg",
                    mimeType = "image/jpeg",
                    isVideo = false,
                    addedAtMillis = 1L,
                    bytes = ParkedInputStream(first, midCopyA, resumeA),
                    length = first.size.toLong(),
                )
            } catch (t: Throwable) {
                failureA.set(t)
            } finally {
                doneA.countDown()
            }
        }.apply { isDaemon = true }
        val moverB = Thread {
            try {
                fixture.store.store(
                    displayName = "b.jpg",
                    mimeType = "image/jpeg",
                    isVideo = false,
                    addedAtMillis = 2L,
                    bytes = ParkedInputStream(second, midCopyB, resumeB),
                    length = second.size.toLong(),
                )
            } catch (t: Throwable) {
                failureB.set(t)
            } finally {
                doneB.countDown()
            }
        }.apply { isDaemon = true }
        try {
            moverA.start()
            // A is parked mid-copy holding the monitor; B then queues on it.
            assertTrue(midCopyA.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            moverB.start()
            resumeA.countDown()
            assertTrue(doneA.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            // B owns the monitor now; it parks mid-copy in its turn.
            assertTrue(midCopyB.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            resumeB.countDown()
            assertTrue(doneB.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            resumeA.countDown()
            resumeB.countDown()
        }
        assertNull(failureA.get())
        assertNull(failureB.get())
        // Insertion order is guaranteed (A's whole store precedes B's read),
        // the ids are distinct, and each blob round-trips its own payload.
        val entries = fixture.store.entries()
        assertEquals(2, entries.size)
        assertEquals(listOf("a.jpg", "b.jpg"), entries.map { it.displayName })
        assertTrue(
            "serialized stores must allocate distinct ids",
            entries[0].id != entries[1].id,
        )
        val firstOpened = fixture.store.open(entries[0]).use { it.readBytes() }
        assertTrue(first.contentEquals(firstOpened))
        val secondOpened = fixture.store.open(entries[1]).use { it.readBytes() }
        assertTrue(second.contentEquals(secondOpened))
    }

    // ---- Delete / restoreOut -------------------------------------------

    @Test
    fun `delete removes the index entry and the blob file`() {
        val fixture = configuredFixture()
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(8)),
            length = 8L,
        )
        fixture.store.delete(entry)
        assertTrue(fixture.store.entries().isEmpty())
        assertFalse(fixture.blobOf(entry.id).exists())
        // Deleting an unknown id is a loud failure, never a silent no-op.
        assertThrows(VaultStoreException::class.java) { fixture.store.delete(entry) }
    }

    @Test
    fun `restoreOut streams the plaintext and deliberately keeps the entry`() {
        val fixture = configuredFixture()
        val payload = "restore me".toByteArray()
        val entry = fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        val sink = ByteArrayOutputStream()
        fixture.store.restoreOut(entry, sink)
        assertTrue(payload.contentEquals(sink.toByteArray()))
        // ADR-0017 restore order: the caller deletes via delete() only after
        // the plaintext landed durably --- restoreOut must not pre-delete.
        assertEquals(listOf(entry), fixture.store.entries())
        assertTrue(fixture.blobOf(entry.id).isFile)
    }

    // ---- Wipe / wrong key ----------------------------------------------

    @Test
    fun `wipe destroys the pin record, the index and every blob`() {
        val fixture = configuredFixture()
        fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(8)),
            length = 8L,
        )
        fixture.store.wipe()
        assertFalse(fixture.store.isConfigured())
        assertFalse(fixture.root.exists())
        assertThrows(VaultStoreException::class.java) { fixture.store.entries() }
    }

    @Test
    fun `a store under a different key cannot read the sealed state`() {
        val fixture = configuredFixture()
        fixture.store.store(
            displayName = "a.jpg",
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = 1L,
            bytes = ByteArrayInputStream(ByteArray(8)),
            length = 8L,
        )
        // What a lost AndroidKeyStore key looks like (ADR-0017: factory
        // reset makes the vault permanently unopenable): the files are all
        // still there, but every read fails authentication.
        val stranger = VaultStore(root = fixture.root, keys = { otherKey })
        assertThrows(VaultStoreException::class.java) { stranger.entries() }
        assertThrows(VaultStoreException::class.java) { stranger.verifyPin(PIN.toCharArray()) }
    }

    // ---- Diagnostics (W1) ----------------------------------------------

    /**
     * The About > Diagnostics probe (W1) narrates the disk truth at every
     * stage: a fresh store, a configured one, one holding a blob, and the
     * pre-#73 broken-residue shape. The blob size matches the documented
     * sealed layout (IV || ciphertext == plaintext || GCM tag), and the
     * `.probe` writability file never lingers behind the probe.
     */
    @Test
    fun `describeState narrates the disk truth from fresh to residue`() {
        val fixture = newFixture()
        // Fresh: nothing sealed yet, root present and writable.
        fixture.store.describeState().let { fresh ->
            assertTrue(fresh.rootExists)
            assertFalse(fresh.pinPresent)
            assertFalse(fresh.indexPresent)
            assertFalse(fresh.biometricBindingPresent)
            assertEquals(0, fresh.blobCount)
            assertEquals(0L, fresh.blobBytes)
            assertTrue(fresh.rootWritable)
        }
        // Configured: the pin+index pair, zero blobs.
        fixture.store.setup(PIN.toCharArray())
        fixture.store.describeState().let { configured ->
            assertTrue(configured.rootExists)
            assertTrue(configured.pinPresent)
            assertTrue(configured.indexPresent)
            assertFalse(configured.biometricBindingPresent)
            assertEquals(0, configured.blobCount)
            assertEquals(0L, configured.blobBytes)
            assertTrue(configured.rootWritable)
        }
        // One stored blob: raw count and the documented sealed size
        // (IV(12) || ciphertext == plaintext || GCM tag(16)).
        val payload = randomBytes(2048)
        fixture.store.store(
            displayName = DISPLAY_NAME,
            mimeType = "image/jpeg",
            isVideo = false,
            addedAtMillis = ADDED_AT,
            bytes = ByteArrayInputStream(payload),
            length = payload.size.toLong(),
        )
        val withBlob = fixture.store.describeState()
        assertEquals(1, withBlob.blobCount)
        assertEquals(
            (payload.size + VaultCrypto.IV_BYTES + VaultCrypto.GCM_TAG_BITS / 8).toLong(),
            withBlob.blobBytes,
        )
        // The broken residue shape (external index loss): pin present,
        // index gone --- exactly the state isConfigured refuses to lie about.
        assertTrue(File(fixture.root, "index.bin").delete())
        val residue = fixture.store.describeState()
        assertTrue(residue.pinPresent)
        assertFalse(residue.indexPresent)
        // The writability probe cleans up after itself.
        assertFalse(File(fixture.root, ".probe").exists())
    }

    // ---- Helpers -------------------------------------------------------

    /**
     * Stamps this file's mtime 24 h into the past --- far beyond the
     * sweep's documented 10-minute grace window (see
     * [VaultStore.sweepOrphans]), so the helper never couples to the exact
     * constant. The grace runs on the file clock, and tests age files
     * through the same JVM-pure `File` API the gate reads.
     */
    private fun File.agePastSweepGrace() {
        assertTrue(setLastModified(System.currentTimeMillis() - AGED_MTIME_MILLIS))
    }

    /** Emits a few bytes, then dies: the shape of a provider stream failing mid-copy. */
    private class ExplodingInputStream(private val goodBytes: Int = 10) : InputStream() {
        private var served = 0

        override fun read(): Int =
            if (served < goodBytes) {
                served++
                0x41
            } else {
                throw IOException("provider stream died mid-copy")
            }
    }

    /**
     * Serves [payload] byte by byte (through the stock array-form loop, like
     * [ExplodingInputStream]), then --- exactly once, at the first read
     * past the payload --- opens [park] and blocks on [resume] before
     * serving EOF: the deterministic shape of a provider stream stalling
     * mid-copy (a large video over a slow provider), letting a test hold a
     * [VaultStore.store] INSIDE its serialized section while another
     * thread races a mutation against it. Latches only --- no sleeps, and
     * every await is bounded by [LATCH_TIMEOUT_SECONDS] so a wedged test
     * fails loudly instead of hanging the runner.
     */
    private class ParkedInputStream(
        private val payload: ByteArray,
        private val park: CountDownLatch,
        private val resume: CountDownLatch,
    ) : InputStream() {
        private var served = 0
        private var parkedOnce = false

        override fun read(): Int {
            if (served < payload.size) {
                return payload[served++].toInt() and 0xFF
            }
            if (!parkedOnce) {
                parkedOnce = true
                park.countDown()
                check(resume.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "parked mid-copy read was never resumed"
                }
            }
            return -1
        }
    }

    private companion object {
        const val PIN = "8642"
        const val DISPLAY_NAME = "IMG_20261030_101010.jpg"
        const val ADDED_AT = 1_768_000_000_000L

        /**
         * Generous but bounded latch budget for the concurrency rig: every
         * `await` is an assertion (a wedged thread fails the test), never an
         * unbounded hang.
         */
        const val LATCH_TIMEOUT_SECONDS = 10L

        /**
         * The `agePastSweepGrace` stamp: 24 h, far beyond the sweep's
         * documented 10-minute window (see [agePastSweepGrace]).
         */
        const val AGED_MTIME_MILLIS = 24L * 60L * 60L * 1000L
    }
}
