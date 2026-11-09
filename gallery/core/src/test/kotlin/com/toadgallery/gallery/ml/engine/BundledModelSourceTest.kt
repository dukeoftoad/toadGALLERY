package com.toadgallery.gallery.ml.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM tests for the r28 offline model-pack seam (Workstream 8,
 * `BundledModelAssets` + [BundledFirstModelDownloader]): the
 * ADR-0020-sanctioned second delivery path, in which a CI build
 * bundles the pinned model bytes into `assets/models/` and a device
 * provisioned from it never opens a socket.
 *
 * What is pinned (hermetic: a fake asset map + a recording network
 * transport; no Android `AssetManager` on the JVM --- the production
 * asset binding in `ToadGalleryApplication` is the usual
 * Android-adjacent, untested-by-house-rule wiring):
 *
 *  - **bundled-first**: an asset the bundle carries is served from the
 *    bundle, and the network transport is never consulted;
 *  - **network fallback**: a missing asset degrades to the ordinary
 *    r22 HTTP fetch, byte for byte the same call;
 *  - **guard-before-asset**: a hostile manifest entry is refused by
 *    the SAME structural guard the HTTP path uses, BEFORE the asset
 *    seam is even consulted (an unsafe name can no more reach an
 *    arbitrary asset path than an arbitrary URL);
 *  - **verification is source-independent**: the provisioner's
 *    verify-then-stage-then-commit discipline applies unchanged to
 *    bundled bytes --- a pack whose hash does not match the CURRENT
 *    pin installs NOTHING (the offline path cannot smuggle stale or
 *    tampered weights past the pin);
 *  - **the full offline round trip**: provision from the bundle ->
 *    receipt-verified install -> `CapabilityState.Ready` -> the
 *    network transport stayed completely idle.
 */
class BundledModelSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fake bundle: file name -> bytes, recording lookups. */
    private class FakeBundle(private val assets: Map<String, ByteArray>) : BundledModelAssets {
        val lookups = mutableListOf<String>()

        override fun open(fileName: String): InputStream? {
            lookups += fileName
            return assets[fileName]?.let(::ByteArrayInputStream)
        }
    }

    /** A recording network transport that must stay idle in offline paths. */
    private class RecordingNetwork : ModelDownloader {
        val opens = mutableListOf<String>()
        var payload: ByteArray = "network-bytes".toByteArray()

        override fun open(spec: ModelManifest.Model): InputStream {
            opens += spec.fileName
            return ByteArrayInputStream(payload)
        }
    }

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun specFor(fileName: String, payload: ByteArray, sha: String = sha256Of(payload)) =
        ModelManifest.Model(fileName, ModelManifest.Capability.FACE, payload.size.toLong(), sha)

    @Test
    fun `an asset the bundle carries is served from the bundle, never the network`() {
        val payload = "bundled-face-bytes".toByteArray()
        val bundle = FakeBundle(mapOf("t.onnx" to payload))
        val network = RecordingNetwork()
        val downloader = BundledFirstModelDownloader(bundle, network)

        val received = downloader.open(specFor("t.onnx", payload)).use { it.readBytes() }

        assertArrayEquals(payload, received)
        assertEquals("the asset seam saw exactly the safe name", listOf("t.onnx"), bundle.lookups)
        assertTrue("the network transport must stay completely idle when the bundle has the asset", network.opens.isEmpty())
    }

    @Test
    fun `a missing asset degrades to the ordinary network fetch`() {
        val bundle = FakeBundle(emptyMap()) // a normal build: no bundled pack at all
        val network = RecordingNetwork()
        val downloader = BundledFirstModelDownloader(bundle, network)

        val received = downloader.open(specFor("t.onnx", ByteArray(0))).use { it.readBytes() }

        assertArrayEquals(network.payload, received)
        assertEquals("exactly one network open for the missing asset", listOf("t.onnx"), network.opens)
        assertEquals("the asset seam was consulted first", listOf("t.onnx"), bundle.lookups)
    }

    @Test
    fun `a hostile manifest entry is refused before the asset seam is consulted`() {
        val bundle = FakeBundle(mapOf("a/b.onnx" to ByteArray(1))) // even if something matches
        val network = RecordingNetwork()
        val downloader = BundledFirstModelDownloader(bundle, network)

        listOf(
            "a/b.onnx" to "path separator",
            "../escape.onnx" to "parent traversal",
            "https://evil.example.com/a.onnx" to "absolute-URL injection",
            "a.onnx?x=1" to "query smuggling",
        ).forEach { (fileName, why) ->
            try {
                downloader.open(specFor(fileName, ByteArray(1)))
                fail("the bundled path accepted a $why file name: \"$fileName\"")
            } catch (expected: IOException) {
                assertTrue(
                    "the refusal must be the shared structural guard: \"${expected.message}\"",
                    expected.message!!.contains("refusing unsafe model file name"),
                )
            }
        }
        assertTrue("the asset seam must never see a hostile name", bundle.lookups.isEmpty())
        assertTrue("the network must never see a hostile name", network.opens.isEmpty())
    }

    @Test
    fun `the full offline round trip provisions receipt-verified with the network idle`() {
        val dir = tmp.newFolder("models")
        val detection = ByteArray(2_048) { (it % 251).toByte() }
        val embedding = ByteArray(512) { (it % 127).toByte() }
        val specs = listOf(
            specFor("detect.onnx", detection),
            specFor("embed.onnx", embedding),
        )
        val bundle = FakeBundle(mapOf("detect.onnx" to detection, "embed.onnx" to embedding))
        val network = RecordingNetwork()
        val provisioner = ModelProvisioner(
            storeDir = dir,
            downloader = BundledFirstModelDownloader(bundle, network),
            catalog = specs,
        )

        assertEquals(ModelProvisioner.CapabilityState.NotProvisioned, provisioner.capabilityState(ModelManifest.Capability.FACE))

        val installed = mutableListOf<String>()
        provisioner.provision(ModelManifest.Capability.FACE) { installed += it.fileName }

        assertEquals(ModelProvisioner.CapabilityState.Ready, provisioner.capabilityState(ModelManifest.Capability.FACE))
        assertEquals(setOf("detect.onnx", "embed.onnx"), installed.toSet())
        assertArrayEquals(detection, File(dir, "detect.onnx").readBytes())
        assertArrayEquals(embedding, File(dir, "embed.onnx").readBytes())
        assertTrue("the offline provision never touched the network", network.opens.isEmpty())
    }

    @Test
    fun `a bundled pack that fails the pin's verification installs nothing`() {
        val dir = tmp.newFolder("models")
        // The bundle carries TAMPERED bytes for the pinned spec --- same
        // SIZE, different content, so the failure gate is the SHA-256
        // (the pin's real discipline), not the cheap size pre-check:
        val pinned = ByteArray(2_048) { (it * 3).toByte() }
        val tampered = ByteArray(2_048) { (it * 5).toByte() }
        val spec = specFor("t.onnx", pinned)
        val bundle = FakeBundle(mapOf("t.onnx" to tampered))
        val network = RecordingNetwork()
        val provisioner = ModelProvisioner(
            storeDir = dir,
            downloader = BundledFirstModelDownloader(bundle, network),
            catalog = listOf(spec),
        )

        try {
            provisioner.provision(ModelManifest.Capability.FACE)
            fail("a tampered bundled pack must fail verification, not install")
        } catch (expected: ModelProvisioner.ProvisionException.VerificationFailed) {
            assertTrue(
                "the refusal must name the file: \"${expected.message}\"",
                expected.message!!.contains("t.onnx"),
            )
        }

        // Nothing installed, no orphan staged, no receipt entry --- and
        // no silent network fallback either (the failure is the pack's,
        // and re-running the provision is the retry, the same contract
        // as the network path):
        assertEquals(ModelProvisioner.CapabilityState.NotProvisioned, provisioner.capabilityState(ModelManifest.Capability.FACE))
        assertTrue("no staged .part may survive the failed install", dir.listFiles()?.none { it.name.endsWith(".part") } ?: true)
        assertTrue("no receipt may be written for a failed install", dir.listFiles()?.none { it.name == "install.properties" } ?: true)
        assertTrue("no final file may land for a failed install", dir.listFiles()?.none { it.name == "t.onnx" } ?: true)
    }

    @Test
    fun `a partial bundle falls back per asset`() {
        // A pack that bundles only the detector (the honest partial
        // shape a future per-capability pack could take): the bundled
        // asset comes from the bundle, the missing one from the wire.
        val detection = ByteArray(64) { it.toByte() }
        val embedding = ByteArray(32) { (it * 7).toByte() }
        val specs = listOf(
            specFor("detect.onnx", detection),
            specFor("embed.onnx", embedding),
        )
        val bundle = FakeBundle(mapOf("detect.onnx" to detection))
        val network = RecordingNetwork().apply { payload = embedding }
        val provisioner = ModelProvisioner(
            storeDir = tmp.newFolder("models-partial"),
            downloader = BundledFirstModelDownloader(bundle, network),
            catalog = specs,
        )

        provisioner.provision(ModelManifest.Capability.FACE)

        assertEquals(ModelProvisioner.CapabilityState.Ready, provisioner.capabilityState(ModelManifest.Capability.FACE))
        assertEquals("only the missing asset hit the wire", listOf("embed.onnx"), network.opens)
    }
}
