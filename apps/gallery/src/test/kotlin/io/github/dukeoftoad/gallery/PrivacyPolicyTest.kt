package io.github.dukeoftoad.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Privacy policy tests (issue #20, Phase 2 exit criteria).
 *
 * These run on the JVM inside the ordinary `testDebugUnitTest` task --- no
 * emulator needed --- and encode the source-level privacy invariants of the
 * app: the source manifest never declares INTERNET, the allowlist ships empty,
 * and the build-time merged-manifest gate stays wired into the build.
 *
 * The companion instrumented suite (`PrivacyEgressTest`, androidTest) proves
 * the runtime half: permission denied + socket egress blocked by the OS.
 * Together they implement the enforcement model documented in
 * docs/phase-2/privacy-gate.md.
 */
class PrivacyPolicyTest {

    /** Unit-test working dir is the module dir (apps/gallery); be tolerant either way. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(File(cwd, relative), File(cwd.parentFile, "apps/gallery/$relative"))
            .firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    @Test
    fun `source manifest declares no INTERNET permission`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        val internetPermission = Regex(
            """<(uses-permission|uses-permission-sdk-\d+)[^>]*android:name\s*=\s*"android\.permission\.INTERNET""""
        )
        assertFalse(
            "The source AndroidManifest must never declare android.permission.INTERNET " +
                "(offline by construction; docs/phase-2/privacy-gate.md).",
            internetPermission.containsMatchIn(manifest),
        )
    }

    /**
     * Phase 3 (issue #25): the manifest must declare the granular READ_MEDIA_*
     * family on API 33+ and the legacy READ_EXTERNAL_STORAGE fallback for
     * pre-33. This is the *only* permission set the core app may hold besides
     * the androidx-injected self-scoped dynamic-receiver permission. Asserting
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

    @Test
    fun `INTERNET allowlist is empty by default`() {
        val allowlist = moduleFile("internet-permission-allowlist.txt")
        assertTrue(
            "apps/gallery/internet-permission-allowlist.txt must exist and ship empty " +
                "(see the allowlist protocol in docs/phase-2/privacy-gate.md).",
            allowlist.exists(),
        )
        val activeEntries = allowlist.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertEquals(
            "The INTERNET allowlist must have zero active entries; adding one requires an " +
                "authorizing ADR plus a tracking issue (protocol: internet-permission-allowlist.txt).",
            emptyList<String>(), activeEntries,
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
}
