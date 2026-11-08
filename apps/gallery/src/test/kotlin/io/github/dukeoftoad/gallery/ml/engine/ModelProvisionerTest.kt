package io.github.dukeoftoad.gallery.ml.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Contract tests for the pinned model catalog + provisioning core (r21, audit
 * workstream 3). These run hermetically: the transport is faked and the
 * provisioner gets a tiny synthetic catalog, so no test hashes megabytes and
 * no test needs the INTERNET permission --- the r22 production HTTP
 * downloader has its own hermetic loopback suite (`HttpModelDownloaderTest`)
 * and the privacy suites pin its egress surface.
 */
class ModelProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------- manifest integrity

    @Test
    fun `manifest core assets match the pinned Ente catalog exactly`() {
        // Values transcribed from engine/crates/ente-ml/src/assets.rs at pin
        // 423b2d80d9ec89d45f5680cb1e1d777bac225f41 --- the audit forbids
        // silently swapping weights, so these literals ARE the contract.
        val core = ModelManifest.firstDelivery
        assertEquals(5, core.size)
        val face = ModelManifest.FACE_DETECTION
        assertEquals("yolov5s_face_640_640_static_b1.onnx", face.fileName)
        assertEquals(32_355_091L, face.sizeBytes)
        assertEquals("e047647409403d52696035ecd445792173e50d7fbdcccac97b958a585db9aa3d", face.sha256)
        val embed = ModelManifest.FACE_EMBEDDING
        assertEquals(5_278_803L, embed.sizeBytes)
        assertEquals("0763fc33f54e138476194da95987e133b3e976075a6b1d3e1b2caedb251b1a36", embed.sha256)
        val clipImage = ModelManifest.CLIP_IMAGE
        assertEquals(143_057_352L, clipImage.sizeBytes)
        assertEquals("205a430af825e501c5138e5bb9abea942482a7a4fd4a680e98e47cf0830dce7e", clipImage.sha256)
        val clipText = ModelManifest.CLIP_TEXT
        assertEquals(67_144_712L, clipText.sizeBytes)
        assertEquals("d92f33dfcff83077fc2e0d3414250710efbb51795dfd89767bdbefb5fdc47322", clipText.sha256)
        val vocab = ModelManifest.CLIP_VOCAB
        assertEquals(3_194_984L, vocab.sizeBytes)
        assertEquals("67603cfda2e032ad77b5f8808af37789d590db664b26df8705d2bf8b3c553fc8", vocab.sha256)
    }

    @Test
    fun `every manifest entry is well formed and unique`() {
        val names = ModelManifest.all.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
        ModelManifest.all.forEach {
            assertTrue("sha not well formed: ${it.fileName}", ModelVerifier.isWellFormedSha256(it.sha256))
            assertTrue("size not positive: ${it.fileName}", it.sizeBytes > 0)
            assertNotNull(ModelManifest.byFileName(it.fileName))
        }
        // The audit excludes the pet pack from the first delivery.
        assertTrue(ModelManifest.all.any { it.isPet })
        assertTrue(ModelManifest.firstDelivery.none { it.isPet })
    }

    // ----------------------------------------------------- verifier gates

    @Test
    fun `verifier accepts correct bytes and rejects size then hash mismatches`() {
        val payload = "0123456789".toByteArray()
        val realSha = sha256Of(payload)
        val spec = ModelManifest.Model("t.bin", ModelManifest.Capability.FACE, 10L, realSha)
        assertNull(ModelVerifier.verifyStream(ByteArrayInputStream(payload), spec))
        // Wrong size is caught before hashing:
        val sizeErr = ModelVerifier.verifyStream(ByteArrayInputStream("01234".toByteArray()), spec)
        assertTrue(sizeErr is ModelVerifier.VerificationError.SizeMismatch)
        // Right size, wrong content is a hash mismatch:
        val hashErr = ModelVerifier.verifyStream(
            ByteArrayInputStream("9876543210".toByteArray()),
            spec,
        )
        assertTrue(hashErr is ModelVerifier.VerificationError.HashMismatch)
        assertEquals(realSha, (hashErr as ModelVerifier.VerificationError.HashMismatch).expected)
    }

    // ------------------------------------------------ provisioning happy path

    private class FakeDownloader(private val payloads: MutableMap<String, ByteArray>) : ModelDownloader {
        val openCount = mutableMapOf<String, Int>()

        override fun open(spec: ModelManifest.Model): InputStream {
            openCount[spec.fileName] = (openCount[spec.fileName] ?: 0) + 1
            return ByteArrayInputStream(payloads.getValue(spec.fileName))
        }
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun specFor(fileName: String, capability: ModelManifest.Capability, payload: ByteArray) =
        ModelManifest.Model(fileName, capability, payload.size.toLong(), sha256Of(payload))

    @Test
    fun `provision installs multi-asset capability, receipt last, and is idempotent`() {
        val dir = tmp.newFolder("models")
        val p1 = ByteArray(1024) { it.toByte() }
        val p2 = "second asset".toByteArray()
        val spec1 = specFor("a.bin", ModelManifest.Capability.FACE, p1)
        val spec2 = specFor("b.bin", ModelManifest.Capability.FACE, p2)
        val payloads = mutableMapOf("a.bin" to p1, "b.bin" to p2)
        val downloader = FakeDownloader(payloads)
        val provisioner = ModelProvisioner(dir, downloader, catalog = listOf(spec1, spec2))

        assertEquals(
            ModelProvisioner.CapabilityState.NotProvisioned,
            provisioner.capabilityState(ModelManifest.Capability.FACE),
        )
        val progress = mutableListOf<String>()
        provisioner.provision(ModelManifest.Capability.FACE) { progress.add(it.fileName) }

        assertEquals(listOf("a.bin", "b.bin"), progress)
        val f1 = provisioner.installedFile(spec1)
        assertNotNull(f1)
        assertEquals(p1.size.toLong(), f1!!.length())
        val f2 = provisioner.installedFile(spec2)
        assertNotNull(f2)
        assertEquals(p2.size.toLong(), f2!!.length())
        assertEquals(
            ModelProvisioner.CapabilityState.Ready,
            provisioner.capabilityState(ModelManifest.Capability.FACE),
        )
        assertEquals(spec1.sha256, provisioner.installed().getValue("a.bin").sha256)
        // Idempotence: a second provision makes NO transport calls.
        provisioner.provision(ModelManifest.Capability.FACE)
        assertEquals(1, downloader.openCount.getValue("a.bin"))
        assertEquals(1, downloader.openCount.getValue("b.bin"))
    }

    @Test
    fun `partial install reads as Partial with the missing names`() {
        val dir = tmp.newFolder("models-partial")
        val p1 = ByteArray(64) { 1 }
        val spec1 = specFor("a.bin", ModelManifest.Capability.SEMANTIC, p1)
        val spec2 = specFor("b.bin", ModelManifest.Capability.SEMANTIC, "never delivered".toByteArray())
        val downloader = FakeDownloader(mutableMapOf("a.bin" to p1))
        // Install under a pin that knows only a.bin ...
        ModelProvisioner(dir, downloader, catalog = listOf(spec1))
            .provision(ModelManifest.Capability.SEMANTIC)
        // ... then the widened catalog sees b.bin missing.
        val state = ModelProvisioner(dir, downloader, catalog = listOf(spec1, spec2))
            .capabilityState(ModelManifest.Capability.SEMANTIC)
        assertTrue(state is ModelProvisioner.CapabilityState.Partial)
        assertEquals(listOf("b.bin"), (state as ModelProvisioner.CapabilityState.Partial).missing)
    }

    @Test
    fun `failed download leaves no temp, no receipt, and no install`() {
        val dir = tmp.newFolder("models-fail")
        val spec = specFor("a.bin", ModelManifest.Capability.FACE, "x".toByteArray())
        val failing = ModelDownloader { throw java.io.IOException("network down") }
        val provisioner = ModelProvisioner(dir, failing, catalog = listOf(spec))
        try {
            provisioner.provision(ModelManifest.Capability.FACE)
            throw AssertionError("expected DownloadFailure")
        } catch (expected: ModelProvisioner.ProvisionException.DownloadFailure) {
            assertTrue(expected.message!!.endsWith(spec.fileName))
        }
        assertEquals(emptyMap<String, ModelProvisioner.InstalledModel>(), provisioner.installed())
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `verification failure discards bytes and installs nothing`() {
        val dir = tmp.newFolder("models-hash")
        val spec = ModelManifest.Model("a.bin", ModelManifest.Capability.FACE, 4L, "f".repeat(64))
        val wrongBytes = ModelDownloader { ByteArrayInputStream("XXXX".toByteArray()) }
        val provisioner = ModelProvisioner(dir, wrongBytes, catalog = listOf(spec))
        try {
            provisioner.provision(ModelManifest.Capability.FACE)
            throw AssertionError("expected VerificationFailed")
        } catch (expected: ModelProvisioner.ProvisionException.VerificationFailed) {
            assertTrue(expected.error is ModelVerifier.VerificationError.HashMismatch)
        }
        assertNull(provisioner.installedFile(spec))
        // The .part temp was cleaned:
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `pin change reads as not installed and reprovision replaces the stale copy`() {
        val dir = tmp.newFolder("models-pin")
        val v1 = "v1-payload".toByteArray()
        val spec1 = specFor("vocab.txt", ModelManifest.Capability.SEMANTIC, v1)
        val payloads = mutableMapOf("vocab.txt" to v1)
        val downloader = FakeDownloader(payloads)
        val provisioner1 = ModelProvisioner(dir, downloader, catalog = listOf(spec1))
        provisioner1.provision(ModelManifest.Capability.SEMANTIC)
        assertNotNull(provisioner1.installedFile(spec1))

        // The pin moves (weights swap): same name, different hash and size.
        val v2 = "v2-payload-strictly-longer".toByteArray()
        val spec2 = specFor("vocab.txt", ModelManifest.Capability.SEMANTIC, v2)
        payloads["vocab.txt"] = v2
        // The OLD provisioner's pin no longer describes what is installed:
        assertNull(provisioner1.installedFile(spec2))
        // The app update ships the new manifest -> a provisioner with the new pin.
        val provisioner2 = ModelProvisioner(dir, downloader, catalog = listOf(spec2))
        provisioner2.provision(ModelManifest.Capability.SEMANTIC)
        val replaced = provisioner2.installedFile(spec2)
        assertNotNull(replaced)
        assertEquals(v2.size.toLong(), replaced!!.length())
        assertEquals(spec2.sha256, provisioner2.installed().getValue("vocab.txt").sha256)
        // Exactly one refetch happened across both pins.
        assertEquals(2, downloader.openCount.getValue("vocab.txt"))
    }

    @Test
    fun `invalidate removes file and receipt so the next provision refetches`() {
        val dir = tmp.newFolder("models-inval")
        val payload = ByteArray(512) { (it % 251).toByte() }
        val spec = specFor("a.bin", ModelManifest.Capability.FACE, payload)
        val downloader = FakeDownloader(mutableMapOf("a.bin" to payload))
        val provisioner = ModelProvisioner(dir, downloader, catalog = listOf(spec))
        provisioner.provision(ModelManifest.Capability.FACE)
        assertNotNull(provisioner.installedFile(spec))

        provisioner.invalidate("a.bin")
        assertNull(provisioner.installedFile(spec))
        assertFalse(File(dir, "a.bin").exists())
        provisioner.provision(ModelManifest.Capability.FACE)
        assertEquals(2, downloader.openCount.getValue("a.bin"))
    }

    // ------------------------------------------------- r60 progress events

    /**
     * The r60 event contract, end to end over a two-asset capability:
     * per asset Start -> (zero or more Bytes, monotonically growing, the
     * last one exact) -> Verifying -> Installed; the legacy per-asset
     * [ModelProvisioner.provision]'s onProgress callback still fires after
     * each install, in the same order.
     */
    @Test
    fun `progress events stream in order with monotonic bytes and exact totals`() {
        val dir = tmp.newFolder("models-events")
        val p1 = ByteArray(600) { it.toByte() }
        val p2 = "second".toByteArray()
        val spec1 = specFor("e1.bin", ModelManifest.Capability.FACE, p1)
        val spec2 = specFor("e2.bin", ModelManifest.Capability.FACE, p2)
        val provisioner = ModelProvisioner(
            dir,
            FakeDownloader(mutableMapOf("e1.bin" to p1, "e2.bin" to p2)),
            catalog = listOf(spec1, spec2),
        )
        val events = mutableListOf<ProvisionEvent>()
        val legacyOrder = mutableListOf<String>()
        provisioner.provision(
            ModelManifest.Capability.FACE,
            onProgress = { legacyOrder.add(it.fileName) },
            listener = ProvisionListener { events.add(it) },
        )

        val kinds = events.map { it::class.simpleName }
        assertEquals(
            listOf(
                "DownloadStart", "DownloadBytes", "Verifying", "Installed", // e1
                "DownloadStart", "DownloadBytes", "Verifying", "Installed", // e2
            ),
            kinds,
        )
        // Start/Installed carry the 1-based file index over the count.
        assertEquals(1, (events[0] as ProvisionEvent.DownloadStart).index)
        assertEquals(2, (events[0] as ProvisionEvent.DownloadStart).count)
        assertEquals(2, (events[4] as ProvisionEvent.DownloadStart).index)
        // Bytes are monotonic, pinned-total-denominated, and end exact.
        val e1Bytes = events.filterIsInstance<ProvisionEvent.DownloadBytes>().take(1)
        assertEquals(1, e1Bytes.size)
        assertEquals(p1.size.toLong(), e1Bytes[0].bytesSoFar)
        assertEquals(spec1.sizeBytes, e1Bytes[0].totalBytes)
        assertEquals(spec2.sizeBytes, (events[5] as ProvisionEvent.DownloadBytes).bytesSoFar)
        assertEquals(spec2.sizeBytes, (events[5] as ProvisionEvent.DownloadBytes).totalBytes)
        // The legacy r21 callback still fired, per asset, after its install.
        assertEquals(listOf("e1.bin", "e2.bin"), legacyOrder)
        // And the assets are really installed.
        assertNotNull(provisioner.installedFile(spec1))
        assertNotNull(provisioner.installedFile(spec2))
    }

    /** The 128 KiB emission budget: a >chunk payload emits MORE than once. */
    @Test
    fun `large transfer emits throttled byte events ending at the exact total`() {
        val dir = tmp.newFolder("models-throttle")
        val payload = ByteArray(300 * 1024) { (it % 255).toByte() }
        val spec = specFor("big.bin", ModelManifest.Capability.SEMANTIC, payload)
        val provisioner = ModelProvisioner(
            dir,
            FakeDownloader(mutableMapOf("big.bin" to payload)),
            catalog = listOf(spec),
        )
        val byteEvents = mutableListOf<ProvisionEvent.DownloadBytes>()
        provisioner.provision(
            ModelManifest.Capability.SEMANTIC,
            listener = ProvisionListener {
                if (it is ProvisionEvent.DownloadBytes) byteEvents.add(it)
            },
        )
        assertTrue("expected throttled emissions, got ${byteEvents.size}", byteEvents.size >= 2)
        assertEquals(
            byteEvents.map { it.bytesSoFar },
            byteEvents.map { it.bytesSoFar }.sorted(),
        ) // monotonic
        assertEquals(payload.size.toLong(), byteEvents.last().bytesSoFar)
        assertEquals(spec.sizeBytes, byteEvents.last().totalBytes)
    }

    /** Idempotent re-provision: already-installed assets emit NOTHING. */
    @Test
    fun `already-installed assets emit no progress events`() {
        val dir = tmp.newFolder("models-idle")
        val payload = "again".toByteArray()
        val spec = specFor("s.bin", ModelManifest.Capability.FACE, payload)
        val downloader = FakeDownloader(mutableMapOf("s.bin" to payload))
        val provisioner = ModelProvisioner(dir, downloader, catalog = listOf(spec))
        provisioner.provision(ModelManifest.Capability.FACE)
        val events = mutableListOf<ProvisionEvent>()
        provisioner.provision(
            ModelManifest.Capability.FACE,
            listener = ProvisionListener { events.add(it) },
        )
        assertTrue(events.isEmpty())
        // And no transport call either: the skip happens before the fetch.
        assertEquals(1, downloader.openCount.getValue("s.bin"))
    }

    /** A crashing UI listener must never fail the download itself. */
    @Test
    fun `a throwing listener cannot break provisioning`() {
        val dir = tmp.newFolder("models-throw")
        val payload = "resilient".toByteArray()
        val spec = specFor("r.bin", ModelManifest.Capability.FACE, payload)
        val provisioner = ModelProvisioner(
            dir,
            FakeDownloader(mutableMapOf("r.bin" to payload)),
            catalog = listOf(spec),
        )
        provisioner.provision(
            ModelManifest.Capability.FACE,
            listener = ProvisionListener { error("the UI sank") },
        )
        assertNotNull(provisioner.installedFile(spec))
    }
}
