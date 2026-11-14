package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Zero-network posture scan over the editor's main Kotlin tree: no host literals, no network
 * classes, ever (no allowlist at all). The physical-device egress probe is NOT this test.
 */
class EditAiPrivacyScanTest {

    /** Tolerates both the Gradle module-dir and the repo-root working directories. */
    private fun moduleFile(relative: String): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            File(cwd, relative), // the Gradle module cwd (apps/edit)
            File(cwd, "apps/edit/$relative"), // the repo/worktree-root cwd
            File(cwd.parentFile, "apps/edit/$relative"), // any direct child of the root
        ).firstOrNull { it.exists() }
            ?: error("Cannot locate '$relative' from ${cwd.absolutePath}")
    }

    private fun mainTree(): File = moduleFile("src/main/kotlin")

    /**
     * No host literals in the tree: quoted dot-joined hostnames are collected and must all be
     * documented NON-hosts (committed asset file names, the u2netp input name `input.1`).
     */
    @Test
    fun `the edit tree carries no host literals`() {
        val tree = mainTree()
        val hostLike = Regex(
            "\"[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)+\"",
        )
        // Documented NON-hosts: asset file names, the u2netp input name, the "0.50x" label (UI, never a host).
        // A new dotted NON-host joins deliberately; a new HOST is a contract amendment (no allowlist exists).
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
     * No network classes in the tree: no java.net/javax.net, no HTTP client, no socket surface.
     * The models ship in the APK; there is no fetch path at all.
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
     * The AI seam stays behind its facade: only `EditAiOrtRuntime.kt` may reference the runtime
     * package (local inference, no INTERNET permission, but the boundary is pinned for auditability).
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
