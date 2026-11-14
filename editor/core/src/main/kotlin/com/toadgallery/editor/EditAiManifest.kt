package com.toadgallery.editor

/**
 * The AI model manifest: the four ONNX graphs ship IN THE APK under `assets/models/`, pinned
 * by JVM literals --- name + byte size + SHA-256 --- with no downloader (the editor's
 * zero-network posture). Verification split: on-device, PRESENCE + SIZE are checked before
 * every inference; the full SHA-256 digest is a release/dogfood gate, not a per-launch cost.
 * The JVM test (`EditAiManifestTest`) pins all three literals against the committed files at
 * build time, so a missing or tampered asset is a broken build, not bad data. Per-model graph
 * facts live in `EditAiEngine.kt`; provenance/licensing in the docs.
 */
object EditAiManifest {

    /** One committed model file: the assets-relative path and the pins. */
    data class ModelFile(
        val committedName: String,
        val bytes: Long,
        val sha256: String,
    ) {
        init {
            require(committedName.endsWith(".onnx")) { "the committed name must be an .onnx file name" }
            require(bytes > 0) { "the byte size must be positive" }
            require(sha256.length == 64) { "the sha256 must be 64 hex chars" }
        }

        /** The assets-relative path (`models/<committedName>`). */
        val assetPath: String get() = "models/$committedName"
    }

    /**
     * The erase segmentation + the sky saliency input (u2netp, Apache-2.0; xuebinqin/U-2-Net,
     * ONNX conversion BritishWerewolf/U-2-Netp). One graph serves both features (sky carries
     * 0 dedicated bytes --- it shares this graph).
     */
    val ERASE_SEGMENTATION = ModelFile(
        committedName = "erase-segmentation-u2netp.onnx",
        bytes = 4_574_861L,
        sha256 = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8",
    )

    /** The magic-erase inpainting fill (MI-GAN, MIT; Picsart-AI-Research/MI-GAN). */
    val ERASE_INPAINT = ModelFile(
        committedName = "erase-inpaint-migan.onnx",
        bytes = 29_546_882L,
        sha256 = "593eba0b7e04730f1b61c0a3cbca68d97d8d6a7ff5c6a44a7b9d7fcd880fc5ae",
    )

    /** The bokeh depth estimation (Depth-Anything-V2-Small int8, Apache-2.0). */
    val BOKEH_DEPTH = ModelFile(
        committedName = "bokeh-depth-dav2-small-int8.onnx",
        bytes = 27_258_801L,
        sha256 = "01aa7a23de3f4a0ee1a2bb9997e6918104c85a9f95dea46d27b9b3fb0c6b9001",
    )

    /** The face-retouch localization (YuNet 2023mar, Apache-2.0; opencv/opencv_zoo). */
    val RETOUCH_FACES = ModelFile(
        committedName = "retouch-faces-yunet.onnx",
        bytes = 232_589L,
        sha256 = "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4",
    )

    /** The registry in the feature order (erase pair, bokeh, retouch). */
    val ORDERED: List<ModelFile> = listOf(ERASE_SEGMENTATION, ERASE_INPAINT, BOKEH_DEPTH, RETOUCH_FACES)

    /**
     * The whole-set byte budget, pinned as a literal (61,613,133 bytes; erase 34,121,743 /
     * bokeh 27,258,801 / retouch 232,589 / sky 0 --- the skies are project-authored parametric
     * code, not bitmaps). `EditAiManifestTest` asserts sum == literal, so the pinned total
     * can never drift from the four sizes below.
     */
    const val TOTAL_COMMITTED_BYTES: Long = 61_613_133L

    /**
     * The presence + size check the device runs before any inference (the digest is a
     * release gate, not a per-launch cost). Forgiving by design: a missing or wrong-sized
     * file answers false for THAT model, and the caller degrades that feature --- never
     * a crash.
     */
    fun presentAndSized(open: (String) -> java.io.InputStream?, file: ModelFile): Boolean {
        return try {
            val stream = open(file.assetPath) ?: return false
            stream.use { input ->
                var counted = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    counted += read
                    if (counted > file.bytes) return false
                }
                counted == file.bytes
            }
        } catch (_: RuntimeException) {
            false
        } catch (_: java.io.IOException) {
            false
        }
    }
}
