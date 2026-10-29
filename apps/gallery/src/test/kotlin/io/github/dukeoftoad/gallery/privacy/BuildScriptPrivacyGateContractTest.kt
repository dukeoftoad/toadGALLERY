package io.github.dukeoftoad.gallery.privacy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-0014 privacy suite --- layer 2b: build-script contract tests.
 *
 * The Gradle gate is the enforcement point for the merged manifest. These
 * tests are tripwires: if someone edits `apps/gallery/build.gradle.kts` and
 * weakens or removes the gate (deny-list, allow-list wiring, assemble/check
 * binding), the unit-test suite fails immediately --- not just at the next
 * manifest audit.
 */
class BuildScriptPrivacyGateContractTest {

    private val buildScript: File =
        File(PrivacyPolicy.moduleDir(), "build.gradle.kts")

    private fun scriptText(): String = buildScript.readText()

    @Test
    fun `build script defines the privacy gate deny-list with the network family`() {
        val text = scriptText()
        assertTrue("privacyDenyList missing from ${buildScript.name}", text.contains("val privacyDenyList"))
        PrivacyPolicy.NETWORK_DENY_LIST.forEach { permission ->
            assertTrue(
                "deny-list in ${buildScript.name} no longer denies '$permission'",
                text.contains("\"$permission\""),
            )
        }
    }

    @Test
    fun `build script registers privacyGate tasks for every variant`() {
        val text = scriptText()
        assertTrue(
            "privacyGate<Variant> task registration missing (privacyGate\$capitalized)",
            text.contains("tasks.register<PrivacyGateTask>(\"privacyGate\$capitalized\")"),
        )
        assertTrue(
            "gate no longer reads the MERGED manifest via the AGP artifacts API",
            text.contains("SingleArtifact.MERGED_MANIFEST"),
        )
    }

    @Test
    fun `gate is wired into assemble and check so packaging always audits`() {
        val text = scriptText()
        assertTrue(
            "assemble<Variant> must depend on the gate (build-time enforcement)",
            text.contains("it.name == \"assemble\$capitalized\""),
        )
        assertTrue(
            "check must depend on the gate (aggregate verification)",
            text.contains("it.name == \"check\""),
        )
    }

    @Test
    fun `per-variant allow-list is policy-declared, not implicit`() {
        val text = scriptText()
        assertTrue(
            "expectedPermissionsByVariant map missing --- permissions would become implicit",
            text.contains("val expectedPermissionsByVariant"),
        )
        // Phase 2: both variants declare an EMPTY allow-list explicitly.
        assertTrue(
            "debug variant must declare its allow-list explicitly (empty for Phase 2)",
            Regex("\"debug\"\\s+to\\s+emptySet<String>()").containsMatchIn(text),
        )
        assertTrue(
            "release variant must declare its allow-list explicitly (empty for Phase 2)",
            Regex("\"release\"\\s+to\\s+emptySet<String>()").containsMatchIn(text),
        )
    }

    @Test
    fun `self-scoped dynamic-receiver allowance is explicit and reported, never silent`() {
        val text = scriptText()
        assertTrue(
            "the DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION self-scoped allowance must be " +
                "derived from the variant applicationId and recorded in the gate report",
            text.contains("DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") &&
                text.contains("variant.applicationId.get()") &&
                text.contains("selfScopedPermissions"),
        )
    }
}
