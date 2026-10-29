package io.github.dukeoftoad.gallery.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-0014 privacy suite --- layer 2c: CI workflow contract tests.
 *
 * ADR-0013 makes green CI a merge requirement, and ADR-0014 makes the privacy
 * gate + privacy test suite BLOCKING CI steps. These tests assert the workflow
 * still runs the gate and still treats the suite as binding, so the privacy
 * posture cannot silently regress by editing CI.
 */
class CiWorkflowPrivacyTest {

    private val workflow: File =
        File(PrivacyPolicy.repoRoot(), ".github/workflows/ci.yml")

    /** Extracts one step block (from its `name:` line to the next step's `name:` line). */
    private fun stepBlock(steps: List<String>, nameMarker: String): String? {
        val startIdx = steps.indexOfFirst { it.contains("name: $nameMarker") }
        if (startIdx < 0) return null
        val endIdx = steps.drop(startIdx + 1).indexOfFirst { it.trimStart().startsWith("- name:") }
        val stepLines = steps.subList(startIdx, if (endIdx < 0) steps.size else startIdx + 1 + endIdx)
        return stepLines.joinToString("\n")
    }

    private fun stepLines(): List<String> = workflow.readLines()

    @Test
    fun `workflow file exists`() {
        assertTrue("Missing CI workflow at ${workflow.absolutePath}", workflow.isFile)
    }

    @Test
    fun `CI runs the privacy gate for both variants as a blocking step`() {
        val text = workflow.readText()
        assertTrue(
            "CI must run :apps:gallery:privacyGateDebug (ADR-0014)",
            text.contains(":apps:gallery:privacyGateDebug"),
        )
        assertTrue(
            "CI must run :apps:gallery:privacyGateRelease (ADR-0014)",
            text.contains(":apps:gallery:privacyGateRelease"),
        )
        val gateStep = stepBlock(stepLines(), "Privacy gate")
        assertTrue("privacy gate step not found in ci.yml", gateStep != null)
        assertFalse(
            "the privacy gate step must be BLOCKING (no continue-on-error) --- ADR-0014/ADR-0013",
            gateStep!!.contains("continue-on-error"),
        )
    }

    @Test
    fun `CI runs the unit tests as a blocking step`() {
        val testStep = stepBlock(stepLines(), "Run unit tests")
        assertTrue("unit test step not found in ci.yml", testStep != null)
        assertTrue(
            "CI must run testDebugUnitTest",
            testStep!!.contains("testDebugUnitTest"),
        )
        assertFalse(
            "unit tests (incl. the ADR-0014 privacy suite) must be BLOCKING --- remove continue-on-error",
            testStep.contains("continue-on-error"),
        )
    }

    @Test
    fun `CI uploads the privacy gate reports for auditability`() {
        val text = workflow.readText()
        assertTrue(
            "CI should upload privacy gate reports (apps/gallery/build/privacy/)",
            text.contains("privacy-gate") || text.contains("build/privacy"),
        )
    }
}
