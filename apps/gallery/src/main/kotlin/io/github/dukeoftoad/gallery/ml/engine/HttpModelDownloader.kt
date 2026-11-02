package io.github.dukeoftoad.gallery.ml.engine

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The ONE allowlisted model-download host (ADR-0020 §4: the downloader
 * "will fetch from a single pinned host"; `models.ente.com` is "the
 * documented fallback, and the only host the vendored upstream catalog
 * itself references"). Every fetch the app can ever perform is a URL
 * under this host; `PrivacyPolicyTest` pins the value and source-scans
 * the `ml/` package to keep it the only host literal, and the merged
 * manifest holds the INTERNET permission for exactly this grant
 * (ADR-0020 §1 amends ADR-0014 for model fetches only).
 */
internal const val MODEL_HOST = "models.ente.com"

/**
 * The r22 HTTP transport for [ModelProvisioner] (ADR-0020): plain
 * [HttpURLConnection], ZERO new dependencies (ADR-0014 minimalism --- no
 * OkHttp/Retrofit enters the tree for one pinned GET surface).
 *
 * Egress discipline (all of it structural, all of it unit-tested):
 *  - single host: URLs are built as `https://[MODEL_HOST]/<fileName>`
 *    from parts, never from a concatenated string, and the file name is
 *    guarded by [requireSafeModelFileName] BEFORE any connection can be
 *    opened ([urlFor]) --- path traversal and absolute-URL injection are
 *    rejected without a single socket;
 *  - redirects are refused: [HttpURLConnection.setInstanceFollowRedirects]
 *    is false and any 3xx fails closed (verified against the pinned host:
 *    all five core assets answer HTTP 200 directly, so a 3xx can only be
 *    a mis-aimed or tampered endpoint, never a legitimate fetch);
 *  - a present `Content-Length` that disagrees with the pinned
 *    [ModelManifest.Model.sizeBytes] fails fast BEFORE the stream is
 *    returned --- the download never starts on the wrong-sized body
 *    (the streaming SHA-256 in [ModelVerifier] remains the real gate);
 *  - the returned [InputStream] is the connection's own stream: reads
 *    are driven by the provisioner, nothing is buffered whole
 *    (ADR-0020 §3; the CLIP image encoder alone is ~143 MB).
 *
 * No retry logic on purpose: a failed fetch leaves no state behind
 * (verify-then-stage-then-commit), and re-running the provision IS the
 * retry (ADR-0020 §5 per-asset resumability).
 *
 * Threading: [open] performs blocking network IO --- callers must run it
 * off the main thread (the Settings dogfood section uses Dispatchers.IO;
 * the Phase 5 index coordinator will own its own bounded dispatcher).
 */
class HttpModelDownloader(private val root: URL = DEFAULT_ROOT) : ModelDownloader {

    /**
     * Builds and validates the fetch URL for [spec]: the structural name
     * guard first (nothing can connect before it passes), then the name
     * resolved against the pinned [root]. Internal so the JVM privacy
     * suite can pin the URL surface (host, scheme, path) for every
     * manifest entry and the injection matrix.
     */
    internal fun urlFor(spec: ModelManifest.Model): URL {
        val name = requireSafeModelFileName(spec.fileName)
        return URL(root, name)
    }

    override fun open(spec: ModelManifest.Model): InputStream {
        val url = urlFor(spec) // guard BEFORE any connection attempt
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false // 3xx = fail closed
            val status = try {
                connection.responseCode
            } catch (e: IOException) {
                throw IOException("$MODEL_HOST/${spec.fileName}: ${e.message}", e)
            }
            if (status / 100 == 3) {
                fail(spec, "HTTP $status redirect refused (the pinned host serves 200-direct; ADR-0020 fail-closed)")
            }
            if (status != HttpURLConnection.HTTP_OK) {
                fail(spec, "HTTP $status")
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength != -1L && declaredLength != spec.sizeBytes) {
                fail(spec, "content-length $declaredLength != pinned ${spec.sizeBytes} bytes")
            }
            return try {
                connection.inputStream
            } catch (e: IOException) {
                throw IOException("$MODEL_HOST/${spec.fileName}: ${e.message}", e)
            }
        } catch (e: IOException) {
            // Every failure path above already carries host + file + reason;
            // this only releases the socket. On success the CALLER owns the
            // stream and closing it releases the connection.
            connection.disconnect()
            throw e
        }
    }

    private fun fail(spec: ModelManifest.Model, reason: String): Nothing =
        throw IOException("$MODEL_HOST/${spec.fileName}: $reason")

    internal companion object {
        /** Fail fast on an unresponsive host instead of hanging the caller. */
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** Per-read cap; a stalled transfer aborts and the re-run is the retry. */
        private const val READ_TIMEOUT_MS = 60_000

        /**
         * The production URL root every fetch resolves against:
         * `https://models.ente.com/`. A constructor default (not a string
         * concatenation) so the test seam --- [root] --- can point the
         * hermetic JVM suite at a loopback server while the production
         * instance is always constructed argument-less (see
         * `ToadGalleryApplication.modelProvisioner`; `PrivacyPolicyTest`
         * pins that the default root IS the allowlisted host).
         */
        val DEFAULT_ROOT: URL = URL("https", MODEL_HOST, "/")
    }
}

/**
 * Structural guard on a manifest file name (ADR-0020 egress rules): the
 * manifest is trusted (its literals are pinned by `ModelProvisionerTest`
 * against the Rust catalog), but the guard is cheap defense in depth and
 * the single choke point every URL must pass. Rejects --- with a distinct
 * reason each --- any name containing a path separator (`/` or `\`), a
 * parent segment (`..`), a scheme separator (`://`), a query (`?`) or a
 * fragment (`#`), plus the empty name, a leading dot, and any character
 * outside the pinned catalog's file-name alphabet (`A`-`Z`, `a`-`z`,
 * `0`-`9`, `.`, `_`, `-`). Returns the name unchanged on success.
 *
 * Throwing [IOException] (fail-closed) rather than IllegalArgumentException
 * keeps the provisioner's uniform "download failed for <file>" wrapping.
 */
internal fun requireSafeModelFileName(fileName: String): String {
    fun reject(reason: String): Nothing =
        throw IOException("refusing unsafe model file name \"$fileName\": $reason")
    if (fileName.isEmpty()) reject("empty")
    if (fileName.startsWith(".")) reject("leading dot")
    listOf(
        "/" to "path separator",
        "\\" to "path separator",
        ".." to "parent traversal",
        "://" to "absolute-URL injection",
        "?" to "query",
        "#" to "fragment",
    ).forEach { (token, why) ->
        if (fileName.contains(token)) reject(why)
    }
    if (!fileName.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }) {
        reject("character outside the catalog name alphabet")
    }
    return fileName
}
