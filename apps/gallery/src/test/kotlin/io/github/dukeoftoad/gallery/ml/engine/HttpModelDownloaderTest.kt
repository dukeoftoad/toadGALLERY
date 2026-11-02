package io.github.dukeoftoad.gallery.ml.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real-HTTP contract tests for [HttpModelDownloader] (r22, ADR-0020),
 * hermetic on the JVM: a JDK [HttpServer] on 127.0.0.1 answers every
 * request, so no test needs the INTERNET permission, DNS, or the real
 * allowlisted host.
 *
 * The loopback seam is [HttpModelDownloader]'s `root` constructor
 * parameter: the PRODUCTION instance is always constructed argument-less
 * (root = `https://models.ente.com/`, pinned by `PrivacyPolicyTest`'s
 * URL-surface and host-literal tests); these tests inject
 * `http://127.0.0.1:<port>/` to exercise the transport's actual wire
 * behavior --- status handling, the redirect refusal, the content-length
 * fail-fast, streaming, and the pre-connection ordering of the fileName
 * guard (a live server that records every request proves a rejected name
 * never produced one).
 */
class HttpModelDownloaderTest {

    /** A manifest spec for the tests; the pin's hash is the provisioner's concern, not the transport's. */
    private fun spec(fileName: String, sizeBytes: Long) =
        ModelManifest.Model(fileName, ModelManifest.Capability.FACE, sizeBytes, "0".repeat(64))

    /**
     * One scripted loopback endpoint: records every request
     * (method → path), then answers the one canned response.
     * [contentLengthHeader] null means "no Content-Length" (chunked);
     * [extraHeaders] ride along before the status line (e.g. Location).
     */
    private class LoopbackServer(
        private val status: Int,
        private val body: ByteArray,
        private val contentLengthHeader: Long? = body.size.toLong(),
        private val extraHeaders: Map<String, String> = emptyMap(),
    ) : AutoCloseable {

        val requests = CopyOnWriteArrayList<Pair<String, String>>()

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange: HttpExchange ->
                requests += exchange.requestMethod to exchange.requestURI.path
                extraHeaders.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
                when {
                    status == 200 && contentLengthHeader == null ->
                        // responseLength == 0: chunked, no Content-Length header.
                        exchange.sendResponseHeaders(status, 0)
                    status == 200 ->
                        // The header may deliberately DISAGREE with the body
                        // (the wrong-content-length case): declare first,
                        // then write what we have; the client's fail-fast
                        // aborts the transfer, which the handler survives.
                        exchange.sendResponseHeaders(status, contentLengthHeader!!)
                    else -> exchange.sendResponseHeaders(status, -1) // no body (302/404/…)
                }
                if (status == 200) {
                    try {
                        exchange.responseBody.write(body)
                    } catch (_: IOException) {
                        // The downloader failing fast closed the socket
                        // mid-write; the assertion happens client-side.
                    }
                }
                exchange.close()
            }
            server.start()
        }

        /** The test seam root: `http://127.0.0.1:<port>/`. */
        val root: URL
            get() = URL("http", "127.0.0.1", server.address.port, "/")

        override fun close() {
            server.stop(0)
        }
    }

    @Test
    fun `open streams the pinned bytes for a 200 with exact content length`() {
        val body = ByteArray(64 * 1024) { (it % 251).toByte() } // spans many read() rounds
        LoopbackServer(200, body).use { server ->
            val stream = HttpModelDownloader(server.root).open(spec("t.bin", body.size.toLong()))
            val received = stream.use { it.readBytes() }
            assertArrayEquals("the transport must hand through the raw stream unbuffered", body, received)
            assertEquals(listOf("GET" to "/t.bin"), server.requests)
        }
    }

    @Test
    fun `open fails fast when the declared content length disagrees with the pin`() {
        val body = ByteArray(1_024)
        LoopbackServer(200, body, contentLengthHeader = body.size + 1L).use { server ->
            try {
                HttpModelDownloader(server.root).open(spec("t.bin", body.size.toLong()))
                fail("a wrong content-length must fail the open before any stream is handed out")
            } catch (expected: IOException) {
                assertTrue("the refusal must name the file and the reason: \"${expected.message}\"",
                    expected.message!!.contains("t.bin") && expected.message!!.contains("content-length"))
            }
        }
    }

    @Test
    fun `open fails closed on a redirect and never follows it`() {
        // The Location points back at the SAME loopback server on a
        // different path: if the transport ever followed redirects, the
        // request list would grow beyond the one original GET.
        LoopbackServer(302, ByteArray(0), extraHeaders = mapOf("Location" to "/evil-redirect.onnx")).use { server ->
            try {
                HttpModelDownloader(server.root).open(spec("t.bin", 1L))
                fail("a 3xx must fail closed (the pinned host serves 200-direct; ADR-0020)")
            } catch (expected: IOException) {
                assertTrue(
                    "the refusal must say redirect: \"${expected.message}\"",
                    expected.message!!.contains("redirect"),
                )
            }
            assertEquals("the redirect target must never be requested", listOf("GET" to "/t.bin"), server.requests)
        }
    }

    @Test
    fun `open fails on non-200 statuses with the status in the message`() {
        LoopbackServer(404, ByteArray(0)).use { server ->
            try {
                HttpModelDownloader(server.root).open(spec("t.bin", 1L))
                fail("a 404 must fail the open")
            } catch (expected: IOException) {
                assertTrue("the refusal must carry the status: \"${expected.message}\"",
                    expected.message!!.contains("HTTP 404"))
            }
        }
    }

    @Test
    fun `a missing content length is accepted and streamed (the verifier owns the size gate)`() {
        val body = ByteArray(2_048) { (it * 31).toByte() }
        LoopbackServer(200, body, contentLengthHeader = null).use { server ->
            val received = HttpModelDownloader(server.root)
                .open(spec("t.bin", body.size.toLong()))
                .use { it.readBytes() }
            assertArrayEquals(body, received)
        }
    }

    @Test
    fun `hostile file names are rejected before any connection exists`() {
        LoopbackServer(200, ByteArray(16)).use { server ->
            val downloader = HttpModelDownloader(server.root)
            listOf(
                "a/b.onnx", // path separator
                "..\\evil.onnx", // backslash traversal
                "https://evil.example.com/a.onnx", // absolute-URL injection
                "a.onnx?x=1", // query
                "a.onnx#f", // fragment
                "", // empty
            ).forEach { fileName ->
                try {
                    downloader.open(spec(fileName, 16L))
                    fail("the guard accepted \"$fileName\"")
                } catch (expected: IOException) {
                    assertTrue(
                        "the refusal must be the structural guard: \"${expected.message}\"",
                        expected.message!!.contains("refusing unsafe model file name"),
                    )
                }
            }
            assertEquals(
                "a rejected name must never produce a request (guard precedes the connection)",
                emptyList<Pair<String, String>>(),
                server.requests,
            )
        }
    }

    @Test
    fun `the production root is the allowlisted host over https`() {
        // The seam's default: what every argument-less construction (the
        // only production wiring, see ToadGalleryApplication) resolves
        // against. PrivacyPolicyTest pins the full URL surface per entry.
        assertEquals("https://models.ente.com/", HttpModelDownloader.DEFAULT_ROOT.toString())
        assertEquals("https", HttpModelDownloader.DEFAULT_ROOT.protocol)
        assertEquals(MODEL_HOST, HttpModelDownloader.DEFAULT_ROOT.host)
    }
}
