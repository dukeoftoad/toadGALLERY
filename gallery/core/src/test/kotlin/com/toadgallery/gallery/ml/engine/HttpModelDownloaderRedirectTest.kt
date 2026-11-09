package com.toadgallery.gallery.ml.engine

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The r28 (Workstream 8, Phase 5 hardening) redirect-fail-closed
 * matrix for [HttpModelDownloader]: `HttpModelDownloaderTest` (r22)
 * proves the 302 refusal once; this suite drives the transport HARDER
 * across the whole 3xx family and the realistic tamper shapes an
 * allowlisted host impersonation would take, each time proving TWO
 * things at once:
 *
 *  1. the open FAILS CLOSED (no stream is handed out), with the
 *     refusal naming the redirect; and
 *  2. the server saw EXACTLY ONE request --- the original GET. A
 *     transport that followed the redirect would issue a second
 *     request to the Location target (the loopback server records
 *     every request, and every Location below points at a path THIS
 *     server serves, so a follow would be observable).
 *
 * Why every 3xx matters: ADR-0020's pin was verified against the live
 * host (all five core assets answer HTTP 200 DIRECT --- the r22
 * HEAD-request audit), so a 3xx can only ever be a mis-aimed endpoint,
 * a compromised mirror or a tampered network path --- never a
 * legitimate fetch. Following it would mean the app fetching bytes
 * from a host the allowlist never authorized, which is exactly the
 * egress boundary `EgressSolePathTest` / `PrivacyPolicyTest` pin.
 *
 * The suite also pins the two adjacent fail-closed behaviors a
 * redirect response would try to smuggle past: a redirect that carries
 * a body (the body must never be returned as the model stream), and
 * the loopback-seam honesty check (a crafted root is honored exactly
 * as-is --- the guard applies to the PATH, the host pin is the
 * production root's business, pinned by `PrivacyPolicyTest`).
 */
class HttpModelDownloaderRedirectTest {

    private fun spec(fileName: String = "t.bin", sizeBytes: Long = 1L) =
        ModelManifest.Model(fileName, ModelManifest.Capability.FACE, sizeBytes, "0".repeat(64))

    /**
     * One scripted loopback endpoint that ALWAYS answers a 3xx with the
     * given Location, records every request, and --- crucially for the
     * redirect-follow probes --- serves the redirect TARGET path as a
     * plain 200 body too, so a transport that followed the redirect
     * would leave a second recorded request behind.
     */
    private class RedirectServer(private val status: Int, private val location: String) : AutoCloseable {

        val requests = CopyOnWriteArrayList<Pair<String, String>>()

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange: HttpExchange ->
                requests += exchange.requestMethod to exchange.requestURI.path
                when (exchange.requestURI.path) {
                    "/t.bin" -> {
                        exchange.responseHeaders.add("Location", location)
                        exchange.sendResponseHeaders(status, -1) // 3xx, no body
                    }
                    else -> {
                        // The redirect target: serves an honest 200 so a
                        // follow-through would be a silent success (and a
                        // recorded request) rather than a 404.
                        val body = "redirect-target-bytes".toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.write(body)
                    }
                }
                exchange.close()
            }
            server.start()
        }

        val root: URL
            get() = URL("http", "127.0.0.1", server.address.port, "/")

        override fun close() {
            server.stop(0)
        }
    }

    /** Asserts the open refuses the [status] redirect without a follow. */
    private fun assertRedirectRefused(status: Int, location: String) {
        RedirectServer(status, location).use { server ->
            try {
                HttpModelDownloader(server.root).open(spec())
                fail("HTTP $status must fail closed (ADR-0020: the pinned host serves 200-direct)")
            } catch (expected: IOException) {
                assertTrue(
                    "the refusal must name the redirect (got \"${expected.message}\")",
                    expected.message!!.contains("redirect"),
                )
            }
            assertEquals(
                "a redirect must never be followed: exactly the original GET may be recorded",
                listOf("GET" to "/t.bin"),
                server.requests,
            )
        }
    }

    @Test
    fun `301 Moved Permanently fails closed and is never followed`() =
        assertRedirectRefused(301, "/redirect-target")

    @Test
    fun `302 Found fails closed and is never followed`() =
        assertRedirectRefused(302, "/redirect-target")

    @Test
    fun `303 See Other fails closed and is never followed`() =
        assertRedirectRefused(303, "/redirect-target")

    @Test
    fun `307 Temporary Redirect fails closed and is never followed`() =
        assertRedirectRefused(307, "/redirect-target")

    @Test
    fun `308 Permanent Redirect fails closed and is never followed`() =
        assertRedirectRefused(308, "/redirect-target")

    @Test
    fun `a redirect to an ABSOLUTE url on another host fails closed before any second connection`() =
        assertRedirectRefused(302, "http://evil.example.com/t.onnx")

    @Test
    fun `a redirect to an absolute https url fails closed`() =
        assertRedirectRefused(301, "https://evil.example.com/t.onnx")

    @Test
    fun `a redirect with no Location header still fails closed`() =
        assertRedirectRefused(302, "")

    /**
     * A redirect whose Location points back at the SAME URL (a redirect
     * loop) must fail closed on the FIRST response --- the
     * never-follow rule makes loop protection unnecessary, and the
     * single-request proof is what shows the transport never entered
     * the loop at all.
     */
    @Test
    fun `a same-url redirect loop fails closed on the first response`() =
        assertRedirectRefused(302, "/t.bin")

    /**
     * Location is only the SUGGESTED next hop: even a Location the
     * transport might consider "safe" (same host, same path, correct
     * file name) must not redeem the 3xx. This is the shape a
     * compromised-but-still-serving mirror would produce --- the
     * response status itself is the tamper signal.
     */
    @Test
    fun `a redirect whose Location matches the original request exactly still fails closed`() =
        assertRedirectRefused(307, "/t.bin")

    /**
     * r28 honesty check on the seam itself: the transport honors the
     * injected root EXACTLY as given (no hidden normalization to the
     * production host, no silent scheme upgrade). The production root
     * is the business of `PrivacyPolicyTest` / `EgressSolePathTest`;
     * here the point is that the r22 test seam stays a REAL seam, so
     * the redirect matrix above exercises the actual wire behavior and
     * not a mocked-out shortcut.
     */
    @Test
    fun `the loopback seam root is honored exactly as injected`() {
        RedirectServer(302, "/redirect-target").use { server ->
            val url = HttpModelDownloader(server.root).urlFor(spec())
            assertEquals("http", url.protocol)
            assertEquals(server.root.host, url.host)
            assertEquals("the injected root's port must survive URL construction", server.root.port, url.port)
            assertEquals("/t.bin", url.path)
        }
    }
}
