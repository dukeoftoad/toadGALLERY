package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The r41 privacy-suite extension (the contract's JVM half; the
 * ADR-0020 §6 `ml/` scan shape applied to the EDIT module): a host-literal
 * + network-class source scan over the editor's whole main tree (the r41
 * AI files joined the existing flat package, so the tree IS the scan
 * surface). No host literals, no network classes, ever --- the editor's
 * zero-network posture is UNCONDITIONAL (its merged-manifest INTERNET
 * gate has no allowlist at all).
 *
 * The instrumented egress probe is a physical-device gate (ADR-0016) ---
 * NOT this test; the device checklist rows stay human-only.
 */
class EditAiPrivacyScanTest {

    /**
     * Unit-test working dir is the module dir (apps/edit) under Gradle; the
     * local harness pins the WORKTREE ROOT as the cwd --- be tolerant of
     * both (the r41 harness's repo-root candidate included).
     */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            File(cwd, relative), // the Gradle module cwd (apps/edit)
            File(cwd, "apps/edit/$relative"), // the repo/worktree-root cwd (the local harness)
            File(cwd.parentFile, "apps/edit/$relative"), // any direct child of the root
        ).firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    private fun mainTree(): File = moduleFile("src/main/kotlin")

    /**
     * No host literals anywhere in the editor's Kotlin tree. The scan
     * collects quoted strings shaped like dot-joined hostnames, the
     * `PrivacyPolicyTest` shape. The documented NON-host set: the
     * committed FILE names (`.ttf`, `.cube`, `.onnx`) and the u2netp
     * graph's input name `input.1` --- dotted identifiers, never hosts;
     * nothing here can open a connection.
     */
    @Test
    fun `the edit tree carries no host literals`() {
        val tree = mainTree()
        val hostLike = Regex(
            "\"[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+\"",
        )
        // The committed asset file names (the fonts/looks/models pins), the
        // u2netp graph's input name, and the r42 video round's speed-label
        // EXAMPLE ("0.50x" --- the String.format "%.2fx" shape quoted once
        // in a KDoc; a UI label, never a host; nothing can open a
        // connection to it). A genuinely new dotted NON-host literal must
        // be added to this set deliberately; a new HOST is a contract
        // amendment first --- the editor has no allowlist at all.
        val nonHostLiterals = setOf(
            "FiraSans-Regular.ttf",
            "SpaceMono-Regular.ttf",
            "warm-film.cube",
            "cool-chrome.cube",
            "silver-mono.cube",
            "matte-fade.cube",
            "vivid-punch.cube",
            "erase-segmentation-u2netp.onnx",
            "erase-inpaint-migan.onnx",
            "bokeh-depth-dav2-small-int8.onnx",
            "retouch-faces-yunet.onnx",
            "input.1",
            "0.50x",
        )
        val found = tree.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> hostLike.findAll(file.readText()).map { m -> m.value.removeSurrounding("\"") } }
            .toSet()
        assertEquals(
            "The edit tree must contain ZERO host literals (the editor has no allowlist; a dotted " +
                "NON-host literal joins the documented set deliberately). Found: $found",
            emptySet<String>(),
            found - nonHostLiterals,
        )
    }

    /**
     * No network classes anywhere in the editor's Kotlin tree: no
     * java.net/javax.net usage, no HTTP client, no socket surface. The
     * models ship in the APK; there is no fetch path to guard because
     * there is no fetch path.
     */
    @Test
    fun `the edit tree carries no network classes`() {
        val tree = mainTree()
        val files = tree.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the tree must contain Kotlin sources", files.isNotEmpty())
        val forbidden = listOf(
            "java.net.",
            "javax.net.",
            "okhttp",
            "HttpClient",
            "libcurl",
        )
        for (file in files) {
            val text = file.readText()
            for (pattern in forbidden) {
                assertTrue(
                    "${file.name} must not reference the network class surface \"$pattern\"",
                    !text.contains(pattern),
                )
            }
        }
    }

    /**
     * The r41 AI seam stays behind its facade: the ORT implementation is
     * the ONLY file that may reference the runtime, and no file outside
     * `EditAiOrtRuntime.kt` mentions the runtime package. (The runtime is
     * a local-inference library --- it declares no INTERNET permission ---
     * but the boundary is pinned anyway so the seam stays auditable.)
     */
    @Test
    fun `the onnx runtime surface stays behind the facade`() {
        val tree = mainTree()
        val files = tree.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        for (file in files) {
            val text = file.readText()
            val mentionsOrt = text.contains("ai.onnxruntime") || text.contains("OrtSession")
            if (file.name == "EditAiOrtRuntime.kt") {
                assertTrue("the facade impl must reference the runtime", mentionsOrt)
            } else {
                assertTrue(
                    "${file.name} must not reference the ORT classes directly (the seam is the interface)",
                    !mentionsOrt,
                )
            }
        }
    }
}
