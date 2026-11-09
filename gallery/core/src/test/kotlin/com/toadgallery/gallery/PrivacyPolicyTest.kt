package com.toadgallery.gallery

import com.toadgallery.gallery.ml.engine.HttpModelDownloader
import com.toadgallery.gallery.ml.engine.MODEL_HOST
import com.toadgallery.gallery.ml.engine.ModelManifest
import com.toadgallery.gallery.ml.engine.requireSafeModelFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Privacy policy tests (issue #20, Phase 2 exit criteria; extended for the
 * r22 ADR-0020 model-delivery round).
 *
 * These run on the JVM inside the ordinary `testDebugUnitTest` task --- no
 * emulator needed --- and encode the source-level privacy invariants of
 * the app. r22 CHANGED the posture honestly and on record: ADR-0020
 * amends ADR-0014 for model fetches only, so the manifest now DECLARES
 * the INTERNET permission for exactly one purpose --- downloading the
 * pinned AI model assets from the single allowlisted host --- and this
 * suite pins every edge of that grant:
 *
 *  - the permission set is exactly the pinned five (media + location
 *    consent + the ADR-0020 model-fetch INTERNET), with the ADR-0020
 *    justification cited in the manifest itself;
 *  - the allowlist file may carry ONLY the ADR-0020 entry (empty is the
 *    pre-entry transition state --- the build-time gate then fails
 *    assemble until the entry lands; anything else is a violation);
 *  - the downloader's URL surface: every one of the eleven pinned
 *    manifest entries constructs an `https://models.ente.com/<fileName>`
 *    URL and NOTHING else (no port, no query, no fragment);
 *  - [MODEL_HOST] is the pinned host and the only host literal in the
 *    `ml/` package (source scan);
 *  - the structural fileName guard rejects traversal and absolute-URL
 *    injection before any connection can exist.
 *
 * The companion instrumented suite (`PrivacyEgressTest`, androidTest)
 * proves the runtime half: INTERNET granted (the ADR-0020 grant landed)
 * and non-allowlisted targets refused by the guard without a connection.
 * Together they implement the enforcement model documented in
 * docs/phase-2/privacy-gate.md as amended by ADR-0020 §6.
 */
class PrivacyPolicyTest {

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    /**
     * r22 (ADR-0020 §1): the manifest DECLARES the model-fetch INTERNET
     * grant --- exactly once, with the ADR-0020 justification cited in the
     * manifest comment, and as part of a fully pinned permission set. The
     * set assertion is the r22 replacement of the pre-r22 "never declares
     * INTERNET" tripwire: no permission outside the five below --- and no
     * second network permission of any kind --- may ride into the merged
     * manifest from the source side.
     */
    @Test
    fun `source manifest declares the ADR-0020 model-fetch INTERNET permission`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        val internetPermission = Regex(
            """<(uses-permission|uses-permission-sdk-\d+)[^>]*android:name\s*=\s*"android\.permission\.INTERNET""""
        )
        assertEquals(
            "The source manifest must declare android.permission.INTERNET exactly ONCE --- the r22 " +
                "ADR-0020 §1 model-fetch grant, landed with the HttpModelDownloader round.",
            1,
            internetPermission.findAll(manifest).count(),
        )
        assertTrue(
            "The INTERNET declaration must cite ADR-0020 in the manifest itself: the grant is " +
                "model-fetch-only and the citation is the audit trail for why the permission exists.",
            manifest.contains("ADR-0020"),
        )
        // r24: tools:node="remove" markers are merger REMOVAL directives,
        // not grants --- they push permissions OUT of the merged manifest.
        // The declared-set pin below asserts GRANTS only; the removal set
        // is pinned exactly by its own dedicated r24 test below.
        val declared = Regex("""<(uses-permission|uses-permission-sdk-\d+)[^>]*>""")
            .findAll(manifest)
            .map { it.value }
            .filterNot { it.contains("tools:node=\"remove\"") }
            .flatMap { element ->
                Regex("""android:name\s*=\s*"(android\.permission\.[A-Z_]+)"""")
                    .findAll(element)
                    .map { it.groupValues[1] }
            }
            .toSet()
        assertEquals(
            "The source manifest's permission set is pinned: media read (Phase 3), location consent " +
                "(Phase 4) and the ADR-0020 model-fetch INTERNET --- nothing else, ever (r24 merger " +
                "removal markers are filtered out above and pinned by their own dedicated test).",
            setOf(
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.ACCESS_MEDIA_LOCATION",
                "android.permission.INTERNET",
            ),
            declared,
        )
    }

    /**
     * r24 (WorkManager): the merger REMOVAL MARKERS in the source manifest
     * are pinned exactly --- ACCESS_NETWORK_STATE and FOREGROUND_SERVICE,
     * the two of androidx.work's four manifest injections this app
     * declines (no network-constrained work, no foreground workers). The
     * accepted pair (WAKE_LOCK, RECEIVE_BOOT_COMPLETED) is merged FROM THE
     * LIBRARY and deliberately NOT declared in the source manifest --- a
     * source-side declaration of either would be a grant the pinned-set
     * test above rejects. Shrinking the removal set would silently widen
     * the merged manifest's network-capability surface; growing it would
     * silently break WorkManager's execution guarantees. Both directions
     * are ADR-level decisions recorded in docs/phase-2/privacy-gate.md.
     */
    @Test
    fun `r24 WorkManager permission removal markers are exactly the two declined injections`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        val removals = Regex("""<(uses-permission|uses-permission-sdk-\d+)[^>]*tools:node="remove"[^>]*>""")
            .findAll(manifest)
            .flatMap { element ->
                Regex("""android:name\s*=\s*"(android\.permission\.[A-Z_]+)"""")
                    .findAll(element.value)
                    .map { it.groupValues[1] }
            }
            .toSet()
        assertEquals(
            "The r24 removal markers must remove exactly androidx.work's ACCESS_NETWORK_STATE and " +
                "FOREGROUND_SERVICE injections --- see the manifest's r24 note and " +
                "docs/phase-2/privacy-gate.md.",
            setOf(
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE",
            ),
            removals,
        )
    }

    /**
     * Phase 3 (issue #25): the manifest must declare the granular READ_MEDIA_*
     * family on API 33+ and the legacy READ_EXTERNAL_STORAGE fallback for
     * pre-33. This is the *only* permission set the core app may hold besides
     * the androidx-injected self-scoped dynamic-receiver permission and the
     * r22 model-fetch INTERNET (asserted by its own test). Asserting
     * it explicitly proves Phase 3 actually landed and guards against a future
     * accidental permission pruning.
     */
    @Test
    fun `source manifest declares the Phase 3 media permission set`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_EXTERNAL_STORAGE",
        ).forEach { permission ->
            assertTrue(
                "Phase 3 manifest must declare '$permission' (issue #25); " +
                    "found the source manifest missing it.",
                manifest.contains(permission),
            )
        }
        assertTrue(
            "READ_EXTERNAL_STORAGE must be capped at maxSdkVersion=32 (legacy fallback only).",
            Regex(
                """<uses-permission[^>]*android:name\s*=\s*"android\.permission\.READ_EXTERNAL_STORAGE"[^>]*android:maxSdkVersion\s*=\s*"32""""
            ).containsMatchIn(manifest),
        )
    }

    /**
     * Phase 4 (metadata layer / audit decision #1): the EXIF-location
     * consent permission is declared deliberately. It is a runtime
     * permission that EXISTS only from API 29; there is no maxSdk form
     * for a minimum API range, so the source manifest declares it
     * unconditionally (pre-29 devices ignore the unknown name) and the
     * runtime request is gated in code (MediaLocationAccess.required()).
     * Deliberate addition to the pinned set: location consent is a
     * display gate only (ADR-0014) --- coordinates never leave the device.
     */
    @Test
    fun `source manifest declares ACCESS_MEDIA_LOCATION for the Phase 4 location flow`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "Phase 4 manifest must declare 'android.permission.ACCESS_MEDIA_LOCATION' " +
                "(EXIF location consent; see data/media/location and ExifReader's KDoc).",
            manifest.contains("android.permission.ACCESS_MEDIA_LOCATION"),
        )
    }

    /**
     * ADR-0020 §6: "the merged-manifest privacy gate gains an INTERNET
     * justification entry". The allowlist file (protocol:
     * internet-permission-allowlist.txt) may therefore carry exactly ONE
     * active entry --- `android.permission.INTERNET` --- authorized by
     * ADR-0020 §1 and bounded to the downloader's host constant by the
     * URL-surface tests below. An EMPTY file is only the r22 transition
     * state: the build-time gate then fails every assemble/bundle variant
     * whose merged manifest declares INTERNET, so the missing entry cannot
     * slip through a build. Any OTHER entry is a protocol violation.
     */
    @Test
    fun `INTERNET allowlist carries at most the ADR-0020 model-fetch entry`() {
        val allowlist = moduleFile("internet-permission-allowlist.txt")
        assertTrue(
            "apps/gallery/internet-permission-allowlist.txt must exist " +
                "(see the allowlist protocol in docs/phase-2/privacy-gate.md).",
            allowlist.exists(),
        )
        val activeEntries = allowlist.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(
            "Every active INTERNET-allowlist entry must be exactly 'android.permission.INTERNET' " +
                "--- the ADR-0020 §1 model-fetch grant (host-pinned by the URL-surface tests). No " +
                "other network authorization may ever be allowlisted.",
            activeEntries.all { it == "android.permission.INTERNET" },
        )
    }

    @Test
    fun `build-time privacy gate is wired into the build`() {
        val buildScript = moduleFile("build.gradle.kts").readText()
        // Regression tripwire: if the gate task or its merged-manifest wiring is
        // removed from the build script, this test fails the (binding) unit-test
        // CI step before the enforcement model silently disappears.
        assertTrue(
            "InternetPermissionGateTask is missing from apps/gallery/build.gradle.kts --- " +
                "the issue #20 build-time gate must stay wired in.",
            buildScript.contains("InternetPermissionGateTask"),
        )
        assertTrue(
            "The gate must consume SingleArtifact.MERGED_MANIFEST (the file that ships " +
                "in the APK), not just the source manifest.",
            buildScript.contains("SingleArtifact.MERGED_MANIFEST"),
        )
        assertTrue(
            "The gate must be anchored on the assemble/bundle lifecycle so the binding " +
                "CI build path (ADR-0013) always runs it.",
            buildScript.contains("assemble\$variantTitle"),
        )
    }

    /**
     * ADR-0020 §4/§6 (URL surface): for EVERY pinned manifest entry, the
     * production downloader's constructed URL is exactly
     * `https://models.ente.com/<fileName>` --- the pinned host, the pinned
     * scheme, no port, no query, no fragment, no second host. The URL is
     * built from parts over the guard-validated name, so this is the whole
     * fetch surface that can ever exist for the catalog.
     */
    @Test
    fun `model manifest URL surface is exactly the allowlisted host`() {
        val downloader = HttpModelDownloader()
        ModelManifest.all.forEach { spec ->
            val url = downloader.urlFor(spec)
            assertEquals("https://$MODEL_HOST/${spec.fileName}", url.toString())
            assertEquals("https", url.protocol)
            assertEquals(MODEL_HOST, url.host)
            assertEquals("/${spec.fileName}", url.path)
            assertEquals("no explicit port may appear in a model URL", -1, url.port)
            assertTrue("no query may appear in a model URL", url.query == null)
            assertTrue("no fragment may appear in a model URL", url.ref == null)
        }
    }

    /**
     * ADR-0020 §4/§6 (single host): [MODEL_HOST] must be exactly the
     * documented fallback-and-catalog host, and it must be the ONLY host
     * literal in the production `ml/` package --- a second host constant
     * would be a second egress surface. The scan reads every `.kt` file
     * under `src/main/kotlin/.../ml` and collects quoted strings shaped
     * like dot-joined hostnames; the pinned dotted FILE names (`.onnx`,
     * `.txt`) all carry underscores and cannot match; the receipt file
     * name does and is the one documented non-host literal.
     */
    @Test
    fun `MODEL_HOST is the pinned ADR-0020 host and the only host literal in the ml package`() {
        assertEquals(
            "ADR-0020 §4 pins the single allowlisted model host; changing it is an ADR-level " +
                "decision recorded in docs/integration-decisions.md, never a casual code edit.",
            "models.ente.com",
            MODEL_HOST,
        )
        val mlDir = moduleFile("src/main/kotlin/io/github/dukeoftoad/gallery/ml")
        val hostLike = Regex(
            "\"[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+\"",
        )
        // install.properties: the provisioner's receipt file name.
        // index-state.json: the r24 queue's state-file name (the atomic
        // bookkeeping file inside the app-private face-index directory).
        // people-state.json: the r25 People state file (Workstream 6 ---
        // the names/hidden bookkeeping in the SAME app-private directory,
        // `FaceIndexStateFile`'s discipline; the deliberate documented-set
        // addition this test prescribes for a new dotted NON-host literal).
        // clip-index-state.json: the r26 semantic queue's state file
        // (Workstream 7 --- the Magic Search queue's own bookkeeping in
        // the SAME directory, the identical deliberate addition).
        // things-state.json: the r39 Things collections' state file
        // (the ente-port exit round --- the named collections in the
        // SAME directory, the identical deliberate addition).
        // ml-index.sqlite: the r26 engine store the face/clip queues
        // write through the JNI bridge (the r51 C-3 backstop names it
        // for the store-existence check --- the SAME app-private
        // directory, the identical deliberate addition).
        val nonHostLiterals = setOf(
            "install.properties",
            "index-state.json",
            "people-state.json",
            "clip-index-state.json",
            "things-state.json",
            "ml-index.sqlite",
        )
        val found = mlDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> hostLike.findAll(file.readText()).map { m -> m.value.removeSurrounding("\"") } }
            .toSet()
        assertEquals(
            "The ml/ package must contain exactly ONE host literal: the MODEL_HOST allowlist " +
                "constant. Found: $found. (A genuinely new dotted NON-host literal must be added " +
                "to this test's documented set deliberately; a new HOST is an ADR-0020 revision.)",
            setOf(MODEL_HOST),
            found - nonHostLiterals,
        )
    }

    /**
     * ADR-0020 egress rules (structural guard): the manifest is trusted,
     * but the guard is the choke point every URL must pass, and it must
     * reject path traversal, absolute-URL injection, protocol-relative
     * URLs, query/fragment smuggling, percent-encoded paths, the empty
     * name and any character outside the catalog's file-name alphabet ---
     * all BEFORE a URL (let alone a connection) exists. The loopback-HTTP
     * behavior tests live in `HttpModelDownloaderTest`; this pins the
     * privacy-relevant guarantee itself.
     */
    @Test
    fun `model fileName guard rejects traversal and absolute-URL injection`() {
        val downloader = HttpModelDownloader()
        val hostile = mapOf(
            "a/b.onnx" to "path separator",
            "a\\b.onnx" to "backslash separator",
            "../a.onnx" to "parent traversal",
            "..\\a.onnx" to "parent traversal (backslash)",
            "https://evil.example.com/a.onnx" to "absolute-URL injection",
            "//evil.example.com/a.onnx" to "protocol-relative injection",
            "a.onnx?x=1" to "query smuggling",
            "a.onnx#f" to "fragment smuggling",
            "a%2Fb.onnx" to "percent-encoded separator",
            "" to "empty name",
            ".hidden.onnx" to "leading dot",
            "a b.onnx" to "character outside the name alphabet",
        )
        hostile.forEach { (fileName, why) ->
            try {
                downloader.urlFor(
                    ModelManifest.Model(fileName, ModelManifest.Capability.FACE, 1L, "0".repeat(64)),
                )
                fail("The guard accepted a $why file name: \"$fileName\"")
            } catch (expected: IOException) {
                assertTrue(
                    "The guard must name the refusal: \"${expected.message}\"",
                    expected.message!!.contains("refusing unsafe model file name"),
                )
            }
        }
        // And every PINNED catalog name passes the guard unchanged.
        ModelManifest.all.forEach { spec ->
            assertEquals(spec.fileName, requireSafeModelFileName(spec.fileName))
        }
    }
}
