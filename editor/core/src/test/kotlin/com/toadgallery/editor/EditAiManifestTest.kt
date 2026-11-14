package com.toadgallery.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Model-manifest catalog test: pins the registry order, the store-path convention, the
 * download-URL shape, and the byte-budget sum (the SHA-256 digests are verified against
 * the release assets at download time on device, not against committed files --- the
 * models are no longer bundled in the repo).
 */
class EditAiManifestTest {

    @Test
    fun `the registry pins the four slots in the feature order`() {
        assertEquals(
            listOf(
                "erase-segmentation-u2netp.onnx",
                "erase-inpaint-migan.onnx",
                "bokeh-depth-dav2-small-int8.onnx",
                "retouch-faces-yunet.onnx",
            ),
            EditAiManifest.ORDERED.map { it.fileName },
        )
        // The store paths ride the models-relative convention.
        assertEquals("models/erase-segmentation-u2netp.onnx", EditAiManifest.ERASE_SEGMENTATION.storePath)
    }

    @Test
    fun `every model entry carries a well-formed sha256 and download url`() {
        for (file in EditAiManifest.ORDERED) {
            assertEquals(file.storePath, "models/${file.fileName}")
            assertEquals(
                "download url pin for ${file.fileName}",
                "${EditAiManifest.DOWNLOAD_BASE}/${file.fileName}",
                file.downloadUrl,
            )
            assertEquals("the digest must be 64 lowercase hex chars", 64, file.sha256.length)
            file.sha256.forEach { c ->
                assertEquals("the digest must be lowercase hex", true, c in '0'..'9' || c in 'a'..'f')
            }
        }
    }

    @Test
    fun `the whole-set budget literal equals the sum of the parts`() {
        var sum = 0L
        for (file in EditAiManifest.ORDERED) sum += file.bytes
        assertEquals(EditAiManifest.TOTAL_DOWNLOAD_BYTES, sum)
        assertEquals(61_613_133L, EditAiManifest.TOTAL_DOWNLOAD_BYTES)
    }
}
