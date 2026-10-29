package io.github.dukeoftoad.gallery.privacy

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ADR-0014 privacy suite --- layer 2d: source-level network-API ban scan.
 *
 * The manifest gates stop permissions; this stops the code that would want
 * them. Every file under the module's `src/main/kotlin` and `src/main/res`
 * is scanned for banned network-client tokens. If a future dependency or
 * "helpful" snippet drags in an HTTP client, the test run fails with the
 * offending file and token.
 *
 * Scope note (ADR-0014): Phase 8 (ADR-0010 §7) extends the privacy suite with
 * a RUNTIME zero-outbound-connections audit; this static scan is the cheap
 * first line of defense.
 */
class SourceNetworkApiBanTest {

    private fun sourceRoots(): List<File> {
        val module = PrivacyPolicy.moduleDir()
        val kotlin = File(module, "src/main/kotlin")
        val res = File(module, "src/main/res")
        val manifest = File(module, "src/main/AndroidManifest.xml")
        return listOfNotNull(
            kotlin.takeIf { it.isDirectory },
            res.takeIf { it.isDirectory },
            manifest.takeIf { it.isFile },
        )
    }

    @Test
    fun `source roots exist so the scan actually ran`() {
        val roots = sourceRoots()
        assertTrue("no source roots found under ${PrivacyPolicy.moduleDir()}", roots.isNotEmpty())
        assertTrue(
            "src/main/kotlin missing --- the scan would silently cover nothing",
            roots.any { it.isDirectory && it.name == "kotlin" },
        )
    }

    @Test
    fun `no banned network-client tokens anywhere in src-main`() {
        val banned = PrivacyPolicy.BANNED_SOURCE_TOKENS
        val offenders = mutableListOf<String>()

        sourceRoots().forEach { root ->
            root.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val text = file.readText()
                    banned.forEach { token ->
                        if (token in text) {
                            offenders += "${file.relativeTo(PrivacyPolicy.moduleDir())} -> '$token'"
                        }
                    }
                }
        }

        assertTrue(
            "ADR-0014 network-API ban violated:\n  " + offenders.joinToString("\n  "),
            offenders.isEmpty(),
        )
    }
}
