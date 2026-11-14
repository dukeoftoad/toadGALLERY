package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Model-manifest pin test: asserts name + bytes + SHA-256 of the committed ONNX files against
 * the JVM literals in [EditAiManifest] (fails at test time; the device checks presence+size only).
 */
class EditAiManifestTest {

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

    private fun assetsDir(): File = moduleFile("src/main/assets/models")

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `the registry pins the four r41 slots in the feature order`() {
        assertEquals(
            listOf(
                "erase-segmentation-u2netp.onnx",
                "erase-inpaint-migan.onnx",
                "bokeh-depth-dav2-small-int8.onnx",
                "retouch-faces-yunet.onnx",
            ),
            EditAiManifest.ORDERED.map { it.committedName },
        )
        // The asset paths ride the assets-relative convention.
        assertEquals("models/erase-segmentation-u2netp.onnx", EditAiManifest.ERASE_SEGMENTATION.assetPath)
    }

    @Test
    fun `every committed model file matches its name, byte size and sha256 literal`() {
        for (file in EditAiManifest.ORDERED) {
            val onDisk = File(assetsDir(), file.committedName)
            assertTrue("the committed file ${file.committedName} must exist", onDisk.isFile)
            assertEquals(
                "byte size pin for ${file.committedName}",
                file.bytes,
                onDisk.length(),
            )
            assertEquals(
                "sha256 pin for ${file.committedName} (the provenance table's digest)",
                file.sha256,
                sha256(onDisk),
            )
        }
    }

    @Test
    fun `the whole-set budget literal equals the sum of the parts`() {
        var sum = 0L
        for (file in EditAiManifest.ORDERED) sum += file.bytes
        assertEquals(EditAiManifest.TOTAL_COMMITTED_BYTES, sum)
        // The literal must stay the TRUE sum of the four pinned sizes (the earlier stated total
        // 61,612,633 carried a 500-byte arithmetic slip).
        assertEquals(61_613_133L, EditAiManifest.TOTAL_COMMITTED_BYTES)
    }

    @Test
    fun `the presence and size check accepts the committed files and refuses wrong sizes`() {
        for (file in EditAiManifest.ORDERED) {
            val onDisk = File(assetsDir(), file.committedName)
            assertTrue(
                "presence+size must accept the committed ${file.committedName}",
                EditAiManifest.presentAndSized(
                    { path -> FileInputStream(File(assetsDir(), path.removePrefix("models/"))) },
                    file,
                ),
            )
        }
        // A wrong-sized stand-in (one byte short) must be refused --- the per-launch check is honest.
        // The opener serves the STAND-IN for the retouch path, so the check measures the wrong bytes.
        val onDisk = File(assetsDir(), EditAiManifest.RETOUCH_FACES.committedName)
        val bytes = onDisk.readBytes()
        val short = File(assetsDir(), "short.onnx")
        short.writeBytes(bytes.copyOf(bytes.size - 1))
        try {
            assertTrue(
                !EditAiManifest.presentAndSized(
                    { path ->
                        if (path == EditAiManifest.RETOUCH_FACES.assetPath) {
                            java.io.FileInputStream(short)
                        } else {
                            FileInputStream(File(assetsDir(), path.removePrefix("models/")))
                        }
                    },
                    EditAiManifest.RETOUCH_FACES,
                ),
            )
        } finally {
            short.delete()
        }
    }
}
