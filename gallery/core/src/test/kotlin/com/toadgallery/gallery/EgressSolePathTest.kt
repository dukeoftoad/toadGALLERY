package com.toadgallery.gallery

import com.toadgallery.gallery.ml.engine.HttpModelDownloader
import com.toadgallery.gallery.ml.engine.MODEL_HOST
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r28 (Workstream 8, Phase 5 hardening) WHOLE-REPO egress proof:
 * `https://models.ente.com/` is the app's SOLE network path.
 *
 * `PrivacyPolicyTest` (Phase 2 / r22) pins the model-delivery surface
 * itself --- the manifest permission set, the allowlist protocol, the
 * downloader's URL construction per manifest entry, and the `ml/`
 * package's host-literal scan. This suite complements it with the
 * NEGATIVE space around that grant, scanned across EVERY source and
 * configuration surface that could open a network path:
 *
 *  - **no other URL literal**: the ONLY quoted `scheme://` string
 *    literal in the entire production Kotlin tree is the About screen's
 *    repository link, which is handed to the user's browser through an
 *    `ACTION_VIEW` intent (the app itself never connects; see its
 *    KDoc) --- every other URL in the tree is KDoc prose, which the
 *    quoted-literal scan ignores by construction;
 *  - **one transport file**: `java.net` may be imported ONLY by
 *    [HttpModelDownloader] --- the single, allowlisted, fail-closed
 *    transport of ADR-0020;
 *  - **no network client libraries**: okhttp/retrofit/volley/java
 *    `HttpClient` appear nowhere in production code;
 *  - **no raw sockets**: `Socket`/`DatagramSocket`/`InetAddress` appear
 *    nowhere in production code, so no path bypasses the transport;
 *  - **no cleartext escape hatch**: the manifest declares neither
 *    `usesCleartextTraffic` nor a `networkSecurityConfig` (the targetSdk
 *    default already forbids cleartext; pinning the ABSENCE keeps a
 *    future "quick fix" from silently reopening it);
 *  - **the allowlist is exactly the one ADR-0020 entry** (the r22
 *    transition state --- an empty file --- is over: the entry landed
 *    with issue #94 and must never quietly disappear or grow);
 *  - **the Gradle INTERNET gates stay wired**: the merged-manifest gate
 *    task, its allowlist input, and its anchors on assemble/bundle/check
 *    all remain in the build script, for BOTH variants
 *    (`gateDebugInternetPermission` / `gateReleaseInternetPermission`
 *    come from the same `onVariants` registration the test pins);
 *  - **the transport's fail-closed posture at source level**: redirects
 *    disabled (`instanceFollowRedirects = false`) and the production
 *    root pinned to `https://` + [MODEL_HOST] --- the behavioural
 *    redirect-refusal matrix is `HttpModelDownloaderTest` /
 *    `HttpModelDownloaderRedirectTest`'s job; this pins that the
 *    posture cannot be edited away without failing the suite.
 *
 * Together with `PrivacyPolicyTest` (the grant's shape) and the
 * downloader suites (the transport's behaviour), these tests implement
 * the r28 exit-gate item "egress tests proving models.ente.com is the
 * sole network path" --- see `docs/phase-5/hardening.md`.
 */
class EgressSolePathTest {

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    private fun mainKotlinDir(): File =
        moduleFile("src/main/kotlin")

    /**
     * Every `.kt` file under `src/main/kotlin`, walked the same way
     * `PrivacyPolicyTest`'s ml-package scan walks its subtree.
     */
    private fun mainKotlinFiles(): List<File> =
        mainKotlinDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /**
     * r28: the ONLY quoted `scheme://` string literal in the whole
     * production Kotlin tree is the About screen's repository URL,
     * opened through an `ACTION_VIEW` intent that hands the URL to the
     * user's browser (the app process never connects --- no INTERNET
     * use, no socket). KDoc citation URLs are NOT quoted literals, so
     * the scan ignores them by construction; a URL smuggled into a
     * string constant anywhere in the app would show up here. The
     * pattern requires a non-empty SCHEME before `://` so the name
     * guard's bare `"://"` check token in `HttpModelDownloader.kt` is
     * not a false positive.
     */
    @Test
    fun `the only quoted url literal in production code is the About ACTION_VIEW repository link`() {
        val urlLiteral = Regex("\"[A-Za-z][A-Za-z0-9+.-]*://[^\"\\n]*\"")
        val found = mainKotlinFiles()
            .flatMap { file ->
                urlLiteral.findAll(file.readText()).map { file.relativeTo(mainKotlinDir()).path to it.value }
            }
            .toMap()
        assertEquals(
            "Exactly ONE quoted URL literal may exist in production Kotlin: the About screen's " +
                "repository link (ACTION_VIEW --- the browser connects, never the app). Anything " +
                "else is an untested network surface; route fetches through HttpModelDownloader " +
                "(ADR-0020). Found: $found",
            mapOf(
                "io/github/dukeoftoad/gallery/ui/settings/AboutScreen.kt" to "\"https://github.com/dukeoftoad/toadGALLERY\"",
            ),
            found,
        )
    }

    /**
     * r28: `java.net` types (URL, HttpURLConnection, ...) may be
     * imported ONLY by the one allowlisted transport. A second file
     * importing `java.net` is a second network-capable surface by
     * definition, whatever it does with the types.
     */
    @Test
    fun `java dot net is imported only by the allowlisted model transport`() {
        val importers = mainKotlinFiles()
            .filter { Regex("""^import\s+java\.net\.""", RegexOption.MULTILINE).containsMatchIn(it.readText()) }
            .map { it.relativeTo(mainKotlinDir()).path }
        assertEquals(
            "java.net types are the network surface: only HttpModelDownloader.kt --- the ADR-0020 " +
                "single-host, fail-closed transport --- may import them. Found importers: $importers",
            listOf("io/github/dukeoftoad/gallery/ml/engine/HttpModelDownloader.kt"),
            importers,
        )
    }

    /**
     * r28: no network client library may appear anywhere in production
     * code --- not as an import, not as a fully-qualified reference.
     * The r22 ADR-0020 decision was plain `HttpURLConnection` with zero
     * new dependencies; a client library would carry its own
     * interceptors/config surfaces that this suite cannot scan.
     */
    @Test
    fun `no network client library appears in production code`() {
        val banned = listOf(
            "okhttp3.", "okhttp.", "retrofit2.", "com.android.volley.",
            "java.net.http.HttpClient",
        )
        val offenders = mainKotlinFiles().flatMap { file ->
            val text = file.readText()
            banned.filter { text.contains(it) }.map { "${file.relativeTo(mainKotlinDir()).path}: $it" }
        }
        assertEquals(
            "Network client libraries are banned in production code (ADR-0014 minimalism; ADR-0020's " +
                "one transport is plain HttpURLConnection). Found: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * r28: no raw socket APIs in production code. A `Socket`,
     * `DatagramSocket` or `InetAddress` reference would be a network
     * path that bypasses the transport's URL guard entirely (the guard
     * refuses unsafe targets BEFORE any connection exists; a raw socket
     * would have no such choke point).
     */
    @Test
    fun `no raw socket or dns api appears in production code`() {
        val banned = listOf(
            "java.net.Socket", "java.net.DatagramSocket", "java.net.InetAddress",
            "java.net.ServerSocket",
        )
        val offenders = mainKotlinFiles().flatMap { file ->
            val text = file.readText()
            banned.filter { text.contains(it) }.map { "${file.relativeTo(mainKotlinDir()).path}: $it" }
        }
        assertEquals(
            "Raw socket/DNS APIs are banned in production code: every network byte must flow through " +
                "HttpModelDownloader's guarded URL construction (ADR-0020 §4). Found: $offenders",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * r28: the manifest carries NO cleartext escape hatch --- neither
     * `android:usesCleartextTraffic` nor a `networkSecurityConfig`.
     * The production root is https-only (pinned below), and the default
     * platform posture for targetSdk 28+ already blocks cleartext; this
     * pins that no future edit quietly reopens http for "one quick
     * test" (a cleartext grant would apply process-wide, and the
     * ADR-0020 host serves 200-direct over https --- there is no
     * legitimate cleartext path at all).
     */
    @Test
    fun `the manifest declares no cleartext traffic or network security config`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertEquals(
            "The manifest must not declare usesCleartextTraffic: the ADR-0020 model host is https-only " +
                "and a process-wide cleartext grant would be an egress regression.",
            false,
            manifest.contains("usesCleartextTraffic"),
        )
        assertEquals(
            "The manifest must not declare a networkSecurityConfig: there is exactly one network " +
                "path (HttpModelDownloader to models.ente.com) and its discipline is enforced by the " +
                "privacy suites, not by a config file that could add a second domain trust anchor.",
            false,
            manifest.contains("networkSecurityConfig"),
        )
    }

    /**
     * r28: the INTERNET allowlist carries EXACTLY the one ADR-0020
     * entry --- the r22 "empty file" transition state is history (the
     * entry landed with issue #94 and the merged-manifest gate would
     * fail every assemble without it, since the source manifest
     * declares the grant). The file disappearing or growing an entry
     * is an ADR-level change this tripwire surfaces immediately.
     * (`PrivacyPolicyTest` keeps the "at most / only INTERNET entries"
     * shape test; this pins the exact post-r22 content.)
     */
    @Test
    fun `the INTERNET allowlist is exactly the single ADR-0020 entry`() {
        val activeEntries = moduleFile("internet-permission-allowlist.txt").readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(
            "apps/gallery/internet-permission-allowlist.txt must carry exactly ONE active entry: the " +
                "ADR-0020 / issue #94 model-fetch INTERNET grant. Found: $activeEntries",
            listOf("android.permission.INTERNET"),
            activeEntries,
        )
    }

    /**
     * r28: the build-time INTERNET gates stay wired for BOTH variants
     * --- `gateDebugInternetPermission` and `gateReleaseInternetPermission`
     * are generated from the same `onVariants` registration, anchored on
     * `assemble`/`bundle` (the binding CI path per ADR-0013) and on
     * `check`. This extends `PrivacyPolicyTest`'s gate-wiring tripwire
     * with the allowlist INPUT wiring and the check/bundle anchors: a
     * build script edit that drops any one of them would let a merged
     * manifest INTERNET permission ship unaudited in that variant.
     */
    @Test
    fun `the build-time INTERNET gates stay wired with their allowlist input and anchors`() {
        val buildScript = moduleFile("build.gradle.kts").readText()
        // The per-variant task registration (generates gateDebugInternetPermission
        // and gateReleaseInternetPermission for the debug/release variants):
        assertTrue(
            "The gate task registration (gate\${variantTitle}InternetPermission) must stay in the " +
                "build script --- it is what generates gateDebugInternetPermission and " +
                "gateReleaseInternetPermission.",
            buildScript.contains("gate\${variantTitle}InternetPermission"),
        )
        // The gate consumes the allowlist file (the protocol's input):
        assertTrue(
            "The gate must consume internet-permission-allowlist.txt as its authorization input.",
            buildScript.contains("internet-permission-allowlist.txt"),
        )
        // Anchors: assemble (binding CI path), bundle, and check:
        assertTrue(
            "The gate must anchor on assemble\$variantTitle so the binding CI build path (ADR-0013) " +
                "always runs it.",
            buildScript.contains("assemble\$variantTitle"),
        )
        assertTrue(
            "The gate must anchor on bundle\$variantTitle so AAB builds are gated too.",
            buildScript.contains("bundle\$variantTitle"),
        )
        assertTrue(
            "The gate must anchor on check so ./gradlew check cannot skip it.",
            buildScript.contains("tasks.matching { it.name == \"check\" }"),
        )
    }

    /**
     * r28 (source-level posture pin): the transport itself must keep
     * its two fail-closed properties that the behavioural suites exercise
     * --- redirects disabled at the connection level, and the
     * production root pinned to `https://` on [MODEL_HOST]. These
     * source pins are the belt to `HttpModelDownloaderHardeningTest`'s
     * suspenders: a refactor that drops the
     * `instanceFollowRedirects = false` line would still fail the
     * behavioural redirect matrix, but the source pin names the line
     * that must exist.
     */
    @Test
    fun `the transport source keeps redirects disabled and the https production root`() {
        val source = moduleFile("src/main/kotlin/io/github/dukeoftoad/gallery/ml/engine/HttpModelDownloader.kt")
            .readText()
        assertTrue(
            "HttpModelDownloader must disable connection-level redirects " +
                "(instanceFollowRedirects = false): ADR-0020's fail-closed redirect rule.",
            source.contains("instanceFollowRedirects = false"),
        )
        // And the runtime objects agree with the source pin:
        assertEquals("https", HttpModelDownloader.DEFAULT_ROOT.protocol)
        assertEquals(MODEL_HOST, HttpModelDownloader.DEFAULT_ROOT.host)
        assertEquals("https://models.ente.com/", HttpModelDownloader.DEFAULT_ROOT.toString())
    }
}
