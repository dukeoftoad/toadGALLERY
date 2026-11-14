package com.toadgallery.editor

import java.security.MessageDigest

/** Facade over ONNX Runtime: commit-time inference only, off the UI thread, cancellable.
 *  Impl `EditAiOrtRuntime.kt` is NNAPI-first with CPU fallback; JVM tests use fakes. */

/** The honest boundary kinds (each maps to a dedicated message string). */
enum class EditAiDegradation {
    /** The bundled models are missing from this build (a version skew or a hand-pruned build). */
    MODELS_MISSING_FROM_BUILD,

    /** The native runtime failed to load or execute (a relaunch may recover it). */
    RUNTIME_BROKEN,

    /** The sky detection found no sky --- an honest refusal, not a degradation of the runtime. */
    NO_SKY_FOUND,

    /** The user cancelled the commit --- the honest abort, surfaced as "cancelled", never as an error. */
    CANCELLED,
}

/** The honest per-run progress + cancellation plumbing (the caller owns both). */
interface AiProgress {
    fun report(fraction: Int)
    fun isCancelled(): Boolean
}

/** The per-run result: the value, or the dedicated degradation. */
sealed interface AiRun<out T> {
    data class Ok<T>(val value: T) : AiRun<T>
    class Unavailable(val kind: EditAiDegradation) : AiRun<Nothing>
}

/**
 * Per-model graph facts and preprocessing conventions, cited from upstream
 * sources; each fact states its verification status.
 */
object EditAiModels {

    // ---- u2netp (erase segmentation + the sky saliency input) ----

    /** The u2netp working grid: the graph input is static [1,3,320,320] f32 (opset 11). */
    const val SALIENCY_SIZE: Int = 320
    const val SALIENCY_INPUT_NAME: String = "input.1"

    /**
     * The u2netp output slot: the LAST of the 7 outputs (six encoder scales plus
     * the fused d6 map), [1,1,320,320] f32, validated on-host.
     */
    const val SALIENCY_OUTPUT_INDEX: Int = 6

    /**
     * u2netp preprocessing per the upstream U-2-Net demo (ToTensorLab flag 0):
     * RGB in [0,1], then per-channel (v - mean)/std with the ImageNet constants;
     * postprocess `normPRED` (min-max to 0..1) then *255.
     */
    const val IMAGENET_MEAN_R: Float = 0.485f
    const val IMAGENET_MEAN_G: Float = 0.456f
    const val IMAGENET_MEAN_B: Float = 0.406f
    const val IMAGENET_STD_R: Float = 0.229f
    const val IMAGENET_STD_G: Float = 0.224f
    const val IMAGENET_STD_B: Float = 0.225f

    /** The saliency in-threshold: the normalised map at or above 0.5 reads as subject. */
    const val SALIENCY_THRESHOLD: Int = 128

    // ---- migan (erase inpainting) ----

    /** The migan working size: 512x512 (the committed graph validated on-host at 512). */
    const val INPAINT_SIZE: Int = 512
    const val INPAINT_IMAGE_NAME: String = "image"
    const val INPAINT_MASK_NAME: String = "mask"
    const val INPAINT_RESULT_NAME: String = "result"

    /**
     * migan per upstream MI-GAN: uint8 RGB `image`, uint8 `mask` with
     * 255 = KNOWN / 0 = to-inpaint; the graph does its own pre/postprocessing.
     * Best results come from small, incremental strokes.
     */
    const val INPAINT_KEEP: Int = 255
    const val INPAINT_HOLE: Int = 0

    // ---- dav2-small-int8 (bokeh depth) ----

    /** The dav2 working grid: 518 = 14 * 37; the graph input is dynamic [b,3,h,w] f32. */
    const val DEPTH_SIZE: Int = 518
    const val DEPTH_INPUT_NAME: String = "pixel_values"
    const val DEPTH_OUTPUT_NAME: String = "predicted_depth"

    /**
     * dav2 preprocessing: RGB /255 + the ImageNet mean/std at 518x518 (the repo's
     * default input size). Resize is NEAREST (deterministic floor mapping), not
     * the upstream bicubic --- the integer-determinism discipline.
     */
    const val DEPTH_MAP_SIZE: Int = 518

    // ---- yunet (face localization) ----

    /** The yunet working grid: the 2023mar export's static [1,3,640,640] f32. */
    const val FACES_SIZE: Int = 640
    const val FACES_INPUT_NAME: String = "input"

    /**
     * yunet input per the OpenCV Zoo demo: BGR, float [0,255] scale; the graph
     * applies its own Sigmoid to the cls/obj heads (verified on-host). The BGR
     * scaling is unverified against the FaceDetectorYN C++ source --- quality suspect.
     */
    const val FACES_CONF_THRESHOLD: Float = 0.6f
    const val FACES_NMS_THRESHOLD: Float = 0.3f

    /**
     * yunet decode (cls/obj/bbox/kps at strides 8/16/32): score = cls * obj (both
     * post-Sigmoid in the graph); anchors x = (col + dx) * stride, box centre +
     * extents, landmarks in demo order (right eye, left eye, nose tip, mouth
     * corners). Conf 0.6 (recall-friendlier than the demo's 0.9), NMS 0.3; anchor
     * arithmetic unverified against the C++ source.
     */
    const val FACES_STRIDE_8: Int = 8
    const val FACES_STRIDE_16: Int = 16
    const val FACES_STRIDE_32: Int = 32

    /** The model identity strings the artifact keys embed (the store's `modelIdentity`). */
    const val IDENTITY_SALIENCY: String = "u2netp/320"
    const val IDENTITY_INPAINT: String = "migan/512"
    const val IDENTITY_DEPTH: String = "dav2-small-int8/518"
    const val IDENTITY_FACES: String = "yunet/640"
}

/**
 * Every method takes the WORKING grid (fully-graded session pixels, ARGB) and
 * returns the derived output; impls call [EditAiPreprocess] and [EditAiDerive].
 */
interface EditAiInference {

    /**
     * u2netp: fused d6 saliency min-max normalised to 0..255 (upstream
     * `normPRED` * 255, floor), at [EditAiModels.SALIENCY_SIZE] square.
     */
    fun saliencyMap(working: IntArray, width: Int, height: Int, progress: AiProgress): AiRun<IntArray>

    /**
     * migan: [mask8] carries the upstream polarity (255 = keep, 0 = hole to inpaint);
     * the result is the opaque inpainted grid at the working resolution.
     */
    fun inpaintFill(
        working: IntArray,
        width: Int,
        height: Int,
        mask8: ByteArray,
        progress: AiProgress,
    ): AiRun<IntArray>

    /**
     * dav2: predicted depth min-max normalised to 0..255 (LARGER = CLOSER), at
     * [EditAiModels.DEPTH_SIZE] square.
     */
    fun depthMap(working: IntArray, width: Int, height: Int, progress: AiProgress): AiRun<IntArray>

    /**
     * yunet: decoded face boxes as output-canvas fractions (the 640-space decode
     * is scale-invariant), in score order after NMS.
     */
    fun faceBoxes(working: IntArray, width: Int, height: Int, progress: AiProgress): AiRun<List<FaceBoxes.Box>>
}

/**
 * Pure preprocessing helpers, integer-deterministic (impls call these so the JVM
 * tests pin the exact tensors). Every resize is NEAREST (floor mapping), not the
 * upstream bicubic.
 */
object EditAiPreprocess {

    /** Nearest resize of an ARGB grid to [targetW] x [targetH] (deterministic floor mapping). */
    fun resize(working: IntArray, width: Int, height: Int, targetW: Int, targetH: Int): IntArray {
        val out = IntArray(targetW * targetH)
        for (y in 0 until targetH) {
            val sy = (y * height / targetH).coerceIn(0, height - 1)
            for (x in 0 until targetW) {
                val sx = (x * width / targetW).coerceIn(0, width - 1)
                out[y * targetW + x] = working[sy * width + sx]
            }
        }
        return out
    }

    /** The u2netp tensor: CHW floats, RGB /255 + ImageNet mean/std (see [EditAiModels]). */
    fun saliencyTensor(grid: IntArray): FloatArray {
        val n = grid.size
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            val c = grid[i]
            val r = ((c shr 16) and 0xFF) / 255f
            val g = ((c shr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            out[i] = (r - EditAiModels.IMAGENET_MEAN_R) / EditAiModels.IMAGENET_STD_R
            out[n + i] = (g - EditAiModels.IMAGENET_MEAN_G) / EditAiModels.IMAGENET_STD_G
            out[2 * n + i] = (b - EditAiModels.IMAGENET_MEAN_B) / EditAiModels.IMAGENET_STD_B
        }
        return out
    }

    /** The dav2 tensor: CHW floats, RGB /255 + the ImageNet mean/std (the cited 518 convention). */
    fun depthTensor(grid: IntArray): FloatArray = saliencyTensor(grid)

    /**
     * The yunet tensor: CHW floats, BGR order at raw [0,255] scale (plane 0 = B,
     * 1 = G, 2 = R); see [EditAiModels].
     */
    fun facesTensor(grid: IntArray): FloatArray {
        val n = grid.size
        val out = FloatArray(3 * n)
        for (i in 0 until n) {
            val c = grid[i]
            out[i] = (c and 0xFF).toFloat() // B (plane 0)
            out[n + i] = ((c shr 8) and 0xFF).toFloat() // G (plane 1)
            out[2 * n + i] = ((c shr 16) and 0xFF).toFloat() // R (plane 2)
        }
        return out
    }

    /** The migan mask bytes: 255 = keep, 0 = the hole (the PAINTED region is the hole). */
    fun miganMask(hole: BooleanArray): ByteArray {
        val out = ByteArray(hole.size)
        for (i in hole.indices) {
            out[i] = if (hole[i]) EditAiModels.INPAINT_HOLE.toByte() else EditAiModels.INPAINT_KEEP.toByte()
        }
        return out
    }
}

/**
 * Pure derive + decode functions (artifact assembly); the commit coordinator
 * composes these over the inference facade, JVM tests pin them over hand maps.
 */
object EditAiDerive {

    /**
     * The erase refined mask: painted seed AND saliency subject (the saliency
     * arrives at 320 and maps by the fraction convention), as fraction RLE.
     */
    fun refinedEraseMask(
        seed: BooleanArray,
        workingWidth: Int,
        workingHeight: Int,
        saliencyMap255: IntArray,
    ): FractionRleMask {
        // Saliency: fraction-encoded at its own 320 grid, then reconstructed on the
        // working grid (exact at the working grid only, approximate elsewhere).
        val size = EditAiModels.SALIENCY_SIZE
        val saliencyBool = BooleanArray(size * size) { i ->
            saliencyMap255[i] >= EditAiModels.SALIENCY_THRESHOLD
        }
        val saliencyAtWorking = FractionRleMask.fromMaskAt(saliencyBool, size, size)
            .maskAt(workingWidth, workingHeight)
        val refined = BooleanArray(workingWidth * workingHeight)
        for (i in refined.indices) {
            refined[i] = seed[i] && saliencyAtWorking[i]
        }
        return FractionRleMask.fromMaskAt(refined, workingWidth, workingHeight)
    }

    /**
     * The sky mask: the region above the subject's TOP edge minus the subject ---
     * sky(x, y) = y < globalSubjectTop AND NOT subject(x, y). No detected subject
     * or an empty result refuses with [EditAiDegradation.NO_SKY_FOUND].
     */
    fun skyMask(
        workingWidth: Int,
        workingHeight: Int,
        saliencyMap255: IntArray,
    ): AiRun<FractionRleMask> {
        val size = EditAiModels.SALIENCY_SIZE
        val saliencyBool = BooleanArray(size * size) { i ->
            saliencyMap255[i] >= EditAiModels.SALIENCY_THRESHOLD
        }
        if (saliencyBool.none { it }) {
            return AiRun.Unavailable(EditAiDegradation.NO_SKY_FOUND)
        }
        val subject = FractionRleMask.fromMaskAt(saliencyBool, size, size)
            .maskAt(workingWidth, workingHeight)
        // The subject's topmost row over the whole grid.
        var globalTop = workingHeight
        for (y in 0 until workingHeight) {
            var rowHas = false
            for (x in 0 until workingWidth) {
                if (subject[y * workingWidth + x]) {
                    rowHas = true
                    break
                }
            }
            if (rowHas) {
                globalTop = y
                break
            }
        }
        if (globalTop >= workingHeight) {
            return AiRun.Unavailable(EditAiDegradation.NO_SKY_FOUND)
        }
        val sky = BooleanArray(workingWidth * workingHeight)
        for (y in 0 until globalTop) {
            val row = y * workingWidth
            for (x in 0 until workingWidth) {
                sky[row + x] = !subject[row + x]
            }
        }
        if (sky.none { it }) {
            return AiRun.Unavailable(EditAiDegradation.NO_SKY_FOUND)
        }
        return AiRun.Ok(FractionRleMask.fromMaskAt(sky, workingWidth, workingHeight))
    }

    /**
     * The erase fill patch: migan result cropped to the refined mask's bbox, with
     * placement fractions; bounded by the working resolution (a very large export
     * is softer than its surroundings).
     */
    fun fillPatch(
        refinedMask: FractionRleMask,
        inpainted: IntArray,
        workingWidth: Int,
        workingHeight: Int,
    ): FillPatch? {
        val mask = refinedMask.maskAt(workingWidth, workingHeight)
        var minX = -1
        var minY = -1
        var maxX = -1
        var maxY = -1
        for (y in 0 until workingHeight) {
            for (x in 0 until workingWidth) {
                if (!mask[y * workingWidth + x]) continue
                if (minX < 0 || x < minX) minX = x
                if (minY < 0 || y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (minX < 0) return null
        val px0 = minX
        val py0 = minY
        val px1 = maxX + 1
        val py1 = maxY + 1
        val pw = px1 - px0
        val ph = py1 - py0
        val patchPixels = IntArray(pw * ph)
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                patchPixels[y * pw + x] = inpainted[(py0 + y) * workingWidth + (px0 + x)] or 0xFF000000.toInt()
            }
        }
        return FillPatch(
            pixels = patchPixels,
            width = pw,
            height = ph,
            left = (px0 + 0.5) / workingWidth.toDouble(),
            top = (py0 + 0.5) / workingHeight.toDouble(),
            right = (px1 - 0.5) / workingWidth.toDouble(),
            bottom = (py1 - 0.5) / workingHeight.toDouble(),
        )
    }

    /** The bokeh depth grid: the 518 map min-max normalised to 0..255, then the bounded downsample. */
    fun depthGrid(depthMap255: IntArray): DepthGrid =
        DepthGrid.fromMap(depthMap255, EditAiModels.DEPTH_MAP_SIZE, EditAiModels.DEPTH_MAP_SIZE)

    /**
     * Yunet decode over the 9 array outputs (cls/obj/bbox at strides 8/16/32, all
     * [N]/[N, 4] flattened row-major, cls/obj post-Sigmoid): score = cls * obj;
     * anchor x = (col + dx) * stride, box centre + extents; NMS 0.3 after score
     * sort. Boxes return as fractions of the 640 canvas (scale-invariant).
     */
    fun decodeFaces(
        cls8: FloatArray, cls16: FloatArray, cls32: FloatArray,
        obj8: FloatArray, obj16: FloatArray, obj32: FloatArray,
        bbox8: FloatArray, bbox16: FloatArray, bbox32: FloatArray,
    ): List<FaceBoxes.Box> {
        val found = ArrayList<FaceBoxes.Box>()
        val scores = ArrayList<Float>()
        fun decodeLevel(cls: FloatArray, obj: FloatArray, bbox: FloatArray, stride: Int) {
            val size = EditAiModels.FACES_SIZE
            val cells = size / stride
            for (row in 0 until cells) {
                for (col in 0 until cells) {
                    val k = row * cells + col
                    val score = cls[k] * obj[k]
                    if (score < EditAiModels.FACES_CONF_THRESHOLD) continue
                    val cx = (col + bbox[4 * k]) * stride
                    val cy = (row + bbox[4 * k + 1]) * stride
                    val w = bbox[4 * k + 2] * stride
                    val h = bbox[4 * k + 3] * stride
                    if (w < 1f || h < 1f) continue
                    val x1 = cx - w / 2f
                    val y1 = cy - h / 2f
                    val x2 = cx + w / 2f
                    val y2 = cy + h / 2f
                    // Divide in double (float-then-double would round at float precision); clamped.
                    found.add(
                        FaceBoxes.Box(
                            left = (x1.toDouble() / size).coerceIn(0.0, 1.0),
                            top = (y1.toDouble() / size).coerceIn(0.0, 1.0),
                            right = (x2.toDouble() / size).coerceIn(0.0, 1.0),
                            bottom = (y2.toDouble() / size).coerceIn(0.0, 1.0),
                        ),
                    )
                    scores.add(score)
                }
            }
        }
        decodeLevel(cls8, obj8, bbox8, EditAiModels.FACES_STRIDE_8)
        decodeLevel(cls16, obj16, bbox16, EditAiModels.FACES_STRIDE_16)
        decodeLevel(cls32, obj32, bbox32, EditAiModels.FACES_STRIDE_32)
        // Score order (ties keep the decode order), then NMS: the higher-score box keeps the slot.
        val order = found.indices.sortedByDescending { scores[it] }
        fun iou(a: FaceBoxes.Box, b: FaceBoxes.Box): Float {
            val ix0 = maxOf(a.left, b.left)
            val iy0 = maxOf(a.top, b.top)
            val ix1 = minOf(a.right, b.right)
            val iy1 = minOf(a.bottom, b.bottom)
            if (ix1 <= ix0 || iy1 <= iy0) return 0f
            val inter = ((ix1 - ix0) * (iy1 - iy0)).toFloat()
            val areaA = ((a.right - a.left) * (a.bottom - a.top)).toFloat()
            val areaB = ((b.right - b.left) * (b.bottom - b.top)).toFloat()
            val union = areaA + areaB - inter
            return if (union <= 0f) 0f else inter / union
        }
        val kept = ArrayList<FaceBoxes.Box>()
        for (idx in order) {
            val candidate = found[idx]
            var suppressed = false
            for (keep in kept) {
                if (iou(candidate, keep) >= EditAiModels.FACES_NMS_THRESHOLD) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) kept.add(candidate)
        }
        // Kept boxes are non-empty fraction spans (decode clamps into the canvas, skips sub-pixel extents).
        return kept
    }
}

/**
 * The commit coordinator, called OFF the UI thread at commit time. Each function
 * runs the inference ONCE and derives the artifact --- one snapshot per commit.
 */
object EditAiCommit {

    /** The erase artifact: the refined mask + the inpaint fill patch for the painted seed. */
    fun erase(
        inference: EditAiInference,
        working: IntArray,
        width: Int,
        height: Int,
        seedPoints: List<AiEraseLayer.Point>,
        progress: AiProgress,
    ): AiRun<Pair<FractionRleMask, FillPatch?>> {
        if (progress.isCancelled()) return AiRun.Unavailable(EditAiDegradation.CANCELLED)
        // Seed mask via the even-odd RegionMask plumbing; erase points map onto region points.
        val seed = RegionMask.build(
            seedPoints.map { RegionLayer.Point(it.x, it.y) },
            width,
            height,
        )
        when (val saliency = inference.saliencyMap(working, width, height, progress)) {
            is AiRun.Unavailable -> return saliency
            is AiRun.Ok -> {
                if (progress.isCancelled()) return AiRun.Unavailable(EditAiDegradation.CANCELLED)
                val refined = EditAiDerive.refinedEraseMask(seed, width, height, saliency.value)
                val maskBool = refined.maskAt(width, height)
                if (maskBool.none { it }) {
                    // Refinement found no subject under the seed --- degrade to the
                    // SEED itself: the fill covers what the user painted.
                    val seedRle = FractionRleMask.fromMaskAt(seed, width, height)
                    return fillFor(inference, working, width, height, seedRle, seed, progress)
                }
                return fillFor(inference, working, width, height, refined, maskBool, progress)
            }
        }
    }

    private fun fillFor(
        inference: EditAiInference,
        working: IntArray,
        width: Int,
        height: Int,
        refined: FractionRleMask,
        hole: BooleanArray,
        progress: AiProgress,
    ): AiRun<Pair<FractionRleMask, FillPatch?>> {
        if (progress.isCancelled()) return AiRun.Unavailable(EditAiDegradation.CANCELLED)
        val mask8 = EditAiPreprocess.miganMask(hole)
        return when (val fill = inference.inpaintFill(working, width, height, mask8, progress)) {
            is AiRun.Unavailable -> fill
            is AiRun.Ok -> {
                val patch = EditAiDerive.fillPatch(refined, fill.value, width, height)
                AiRun.Ok(refined to patch)
            }
        }
    }

    /** The sky mask artifact (or the honest no-sky refusal). */
    fun sky(
        inference: EditAiInference,
        working: IntArray,
        width: Int,
        height: Int,
        progress: AiProgress,
    ): AiRun<FractionRleMask> {
        if (progress.isCancelled()) return AiRun.Unavailable(EditAiDegradation.CANCELLED)
        return when (val saliency = inference.saliencyMap(working, width, height, progress)) {
            is AiRun.Unavailable -> saliency
            is AiRun.Ok -> EditAiDerive.skyMask(width, height, saliency.value)
        }
    }

    /** The bokeh depth artifact. */
    fun depth(
        inference: EditAiInference,
        working: IntArray,
        width: Int,
        height: Int,
        progress: AiProgress,
    ): AiRun<DepthGrid> {
        if (progress.isCancelled()) return AiRun.Unavailable(EditAiDegradation.CANCELLED)
        return when (val map = inference.depthMap(working, width, height, progress)) {
            is AiRun.Unavailable -> map
            is AiRun.Ok -> AiRun.Ok(EditAiDerive.depthGrid(map.value))
        }
    }

    /** The face boxes artifact (only called under consent GRANTED). */
    fun faces(
        inference: EditAiInference,
        working: IntArray,
        width: Int,
        height: Int,
        progress: AiProgress,
    ): AiRun<FaceBoxes> {
        if (progress.isCancelled()) return AiRun.Unavailable(EditAiDegradation.CANCELLED)
        return when (val boxes = inference.faceBoxes(working, width, height, progress)) {
            is AiRun.Unavailable -> boxes
            is AiRun.Ok -> AiRun.Ok(FaceBoxes(boxes.value))
        }
    }

    /**
     * The `inputs` string for an artifact key: SHA-256 of the working pixels (+ any
     * extra, e.g. the erase seed points), so changed pixels derive a NEW key and
     * never reuse a stale artifact.
     */
    fun pixelInputs(working: IntArray, extra: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buffer)
        for (c in working) out.writeInt(c)
        out.flush()
        digest.update(buffer.toByteArray())
        if (extra.isNotEmpty()) {
            digest.update(extra.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
