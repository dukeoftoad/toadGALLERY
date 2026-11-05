package io.github.dukeoftoad.gallery.edit

import java.security.MessageDigest

/**
 * The r41 inference seam (Phase 10 sub-scope A; the design contract
 * `docs/phase-10/edit-v3-ai.md`, decisions 2 + 10): a FACADE over ONNX
 * Runtime. Inference runs at COMMIT time only --- off the UI thread,
 * cancellable, with honest progress --- never in the render loop; what
 * it produces is a derived artifact persisted in `EditAiStore.kt`.
 *
 * The ORT classes are android-bound (the prebuilt
 * `com.microsoft.onnxruntime:onnxruntime-android` AAR), so the runtime
 * lives BEHIND this interface: the JVM tests drive the seam over FAKES
 * (`EditAiEngineTest`); the real implementation is `EditAiOrtRuntime.kt`
 * (NNAPI-first with CPU fallback; the honest note: the int8 depth
 * graph's DynamicQuantizeLinear/MatMulInteger ops largely resolve to
 * the CPU execution provider, so NNAPI is requested and the fallback is
 * expected there).
 *
 * Every degradation is a DEDICATED message naming the recovery (the
 * engine's FACE_MODELS_NOT_INSTALLED precedent): a missing or broken
 * runtime or model answers its own boundary message --- never a generic
 * inference failure, never a crash. The message strings live in
 * `strings_r41.xml`; the kinds are pinned here.
 */

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
 * The per-model graph facts and preprocessing conventions --- FETCHED
 * AND CITED, never invented. Each fact names its upstream source; the
 * verification status of each is stated honestly.
 */
object EditAiModels {

    // ---- u2netp (erase segmentation + the sky saliency input) ----

    /** The u2netp working grid: the graph input is static [1,3,320,320] f32 (opset 11). */
    const val SALIENCY_SIZE: Int = 320
    const val SALIENCY_INPUT_NAME: String = "input.1"

    /**
     * The u2netp output slot: the LAST of the 7 outputs --- the fused
     * d6 map [1,1,320,320] f32 (the on-host graph validation; the 7
     * outputs are the six encoder scales plus the fused map).
     */
    const val SALIENCY_OUTPUT_INDEX: Int = 6

    /**
     * The u2netp preprocessing, cited from the upstream demo
     * (xuebinqin/U-2-Net, `data_loader.py` `ToTensorLab` flag 0, "with
     * rgb color", fetched 2026-11-05): RGB in [0,1] (image/255), then
     * per-channel (v - mean)/std with the ImageNet constants. The
     * postprocess is `u2net_test.py`'s `normPRED` (min-max to 0..1)
     * then *255 --- fetched from the same repo.
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
     * The migan mask polarity + input convention, cited from the
     * upstream repo (Picsart-AI-Research/MI-GAN, README "For
     * Developers", fetched 2026-11-05): the ONNX pipeline expects
     * `image` as a uint8 RGB image and `mask` as a uint8 grayscale mask
     * where 255 denotes the KNOWN region and 0 the MASKED (to-inpaint)
     * region; the graph performs its own internal preprocessing
     * (uint8->float32, crop around the mask, resize to 512, normalise)
     * and postprocessing (resize back, blend, float32->uint8). The
     * upstream README also warns the best results come from small,
     * incremental strokes on large areas --- the honest product copy
     * carries that bound.
     */
    const val INPAINT_KEEP: Int = 255
    const val INPAINT_HOLE: Int = 0

    // ---- dav2-small-int8 (bokeh depth) ----

    /** The dav2 working grid: 518 = 14 * 37; the graph input is dynamic [b,3,h,w] f32. */
    const val DEPTH_SIZE: Int = 518
    const val DEPTH_INPUT_NAME: String = "pixel_values"
    const val DEPTH_OUTPUT_NAME: String = "predicted_depth"

    /**
     * The dav2 preprocessing: RGB /255 + the ImageNet mean/std (the
     * same constants the u2netp demo carries), at 518x518 --- the
     * 518 default is cited from the official repo's README
     * (DepthAnything/Depth-Anything-V2, "--input-size", "By default, we
     * use input size 518 for model inference", fetched 2026-11-05). The
     * exact preprocessor_config.json fetch was rate-limited at round
     * time, so the per-file processor is recorded from the model card +
     * the repo README, unverified against the processor source; the
     * constants are pinned here either way. The resize is NEAREST
     * (deterministic floor mapping), not the upstream bicubic --- the
     * r37 integer-determinism discipline; recorded as the round's
     * decision, quality judged on the device rows.
     */
    const val DEPTH_MAP_SIZE: Int = 518

    // ---- yunet (face localization) ----

    /** The yunet working grid: the 2023mar export's static [1,3,640,640] f32. */
    const val FACES_SIZE: Int = 640
    const val FACES_INPUT_NAME: String = "input"

    /**
     * The yunet input convention, cited from the OpenCV Zoo demo
     * (opencv/opencv_zoo `models/face_detection_yunet/README.md` +
     * `demo.py` + `yunet.py`, fetched 2026-11-05): the demo feeds BGR
     * images through cv.FaceDetectorYN, which resizes to the input
     * shape; the graph applies its own Sigmoid to the cls and obj heads
     * (verified by the on-host graph dump --- the outputs are
     * Sigmoid nodes). The per-tensor scaling (BGR order, float [0,255])
     * is recorded from the model card + demo code, UNVERIFIED against
     * the FaceDetectorYN C++ source (unreachable at round time); it is
     * the device rows' first quality suspect.
     */
    const val FACES_CONF_THRESHOLD: Float = 0.6f
    const val FACES_NMS_THRESHOLD: Float = 0.3f

    /**
     * The yunet decode (cls/obj/bbox/kps at strides 8/16/32): score =
     * cls * obj (both post-Sigmoid in the graph); the anchor decode is
     * the canonical YuNet arithmetic --- x = (col + dx) * stride, the
     * box as centre + extents, the landmarks in the demo's order (right
     * eye, left eye, nose tip, right mouth corner, left mouth corner).
     * The conf threshold is the wrapper class's 0.6 (yunet.py's
     * default, cited; the demo.py default is 0.9 --- the round takes
     * the recall-friendlier one for retouch); NMS 0.3 (the demo's).
     * The anchor arithmetic itself is recorded from the model card +
     * the canonical libfacedetection convention, unverified against the
     * C++ source --- flagged for the device rows.
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
 * The inference facade. Every method takes the WORKING grid (the
 * session's fully-graded pixels, ARGB) and returns the model output in
 * the derived form; the implementations call the pure preprocessing
 * helpers in [EditAiPreprocess] and the pure decoders in [EditAiDerive].
 */
interface EditAiInference {

    /**
     * u2netp over the working grid: the fused d6 saliency, min-max
     * normalised to 0..255 ints (upstream `normPRED` * 255, floor), at
     * [EditAiModels.SALIENCY_SIZE] square.
     */
    fun saliencyMap(working: IntArray, width: Int, height: Int, progress: AiProgress): AiRun<IntArray>

    /**
     * migan over the working grid: [mask8] carries the upstream polarity
     * (255 = keep, 0 = the hole to inpaint); the result is the
     * inpainted grid at the working resolution, opaque.
     */
    fun inpaintFill(
        working: IntArray,
        width: Int,
        height: Int,
        mask8: ByteArray,
        progress: AiProgress,
    ): AiRun<IntArray>

    /**
     * dav2 over the working grid: the predicted depth, min-max
     * normalised to 0..255 ints (LARGER = CLOSER), at
     * [EditAiModels.DEPTH_SIZE] square.
     */
    fun depthMap(working: IntArray, width: Int, height: Int, progress: AiProgress): AiRun<IntArray>

    /**
     * yunet over the working grid: the decoded face boxes, fractions of
     * the output canvas (the 640-space decode is scale-invariant), in
     * score order after NMS.
     */
    fun faceBoxes(working: IntArray, width: Int, height: Int, progress: AiProgress): AiRun<List<FaceBoxes.Box>>
}

/**
 * The pure preprocessing helpers (integer-deterministic; the
 * implementations call these so the JVM tests pin the exact tensors).
 * Every resize is NEAREST (floor mapping) --- the r37 discipline, not
 * the upstream bicubic; recorded as the round's decision.
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

    /**
     * The u2netp tensor: CHW floats, RGB /255 + the ImageNet mean/std
     * (the upstream ToTensorLab flag-0 convention, cited in
     * [EditAiModels]).
     */
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
     * The yunet tensor: CHW floats in BGR channel order at RAW [0,255]
     * scale (the cited demo convention; the honest note lives in
     * [EditAiModels]) --- plane 0 is B, plane 1 is G, plane 2 is R.
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

    /**
     * The migan mask tensor bytes: 255 = keep, 0 = the hole (the cited
     * upstream polarity --- the PAINTED region is the hole).
     */
    fun miganMask(hole: BooleanArray): ByteArray {
        val out = ByteArray(hole.size)
        for (i in hole.indices) {
            out[i] = if (hole[i]) EditAiModels.INPAINT_HOLE.toByte() else EditAiModels.INPAINT_KEEP.toByte()
        }
        return out
    }
}

/**
 * The pure derive + decode functions (the artifact assembly). The
 * commit coordinator below composes these over the inference facade;
 * the JVM tests pin them over hand maps.
 */
object EditAiDerive {

    /**
     * The erase refined mask: the painted seed AND the saliency subject
     * (both reconstructed on the working grid --- the saliency mask
     * arrives at 320 and maps by the fraction convention), encoded as
     * the fraction RLE at the working grid.
     */
    fun refinedEraseMask(
        seed: BooleanArray,
        workingWidth: Int,
        workingHeight: Int,
        saliencyMap255: IntArray,
    ): FractionRleMask {
        // The saliency mask at its own 320 grid, fraction-encoded, then
        // reconstructed on the working grid (the exact-at-working-grid
        // rule; approximate elsewhere, the r37 honesty).
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
     * The sky mask from the saliency map (the round's decision): the
     * region above the subject's TOP edge, minus the subject itself ---
     * sky(x, y) = y < globalSubjectTop AND NOT subject(x, y). A photo
     * with no detected subject (an empty subject mask) refuses honestly
     * ([EditAiDegradation.NO_SKY_FOUND]); so does an empty result.
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
     * The erase fill patch: the migan result cropped to the refined
     * mask's bounding box, with the bbox's placement fractions. The
     * patch is bounded by the session's working resolution --- the
     * contract's honest limitation (a very large export is softer than
     * its surroundings there).
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
     * The yunet decode over the 9 array outputs (cls/obj/bbox at strides
     * 8/16/32, all [N] and [N, 4] shapes flattened row-major, cls/obj
     * post-Sigmoid in the graph): score = cls * obj; the anchor decode
     * x = (col + dx) * stride with the box as centre + extents; NMS at
     * the demo's 0.3 after the score sort. The boxes return as
     * fractions of the 640 canvas (scale-invariant).
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
                    // Double division (float division then toDouble would
                    // round at float precision); clamped into the canvas.
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
        // Score order (ties keep the decode order), then NMS at the demo's
        // threshold --- the earlier (higher-score) box keeps the slot.
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
        // Every kept box is a non-empty fraction span by construction (the
        // decode clamps into the canvas and skips sub-pixel extents).
        return kept
    }
}

/**
 * The commit coordinator: the compose point the activity calls OFF the
 * UI thread at commit time. Each function runs the inference ONCE, then
 * derives the artifact; the activity writes it to the store and commits
 * the recipe layer --- one snapshot per commit.
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
        // The seed mask through the EXISTING even-odd RegionMask plumbing
        // (the contract's decision 5): the erase points are the family's
        // own fraction type, mapped onto the region family's point shape.
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
                    // The seed survived, the refinement found no subject under
                    // it --- the honest degrade is the SEED itself: the fill
                    // covers what the user painted (the honest bound stated
                    // in the round doc; on-device quality is bounded).
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
     * The `inputs` string for an artifact key: the SHA-256 of the
     * working pixels (+ any extra, e.g. the seed points for erase) --- a
     * changed adjustment changes the inference input, so a re-apply
     * derives a NEW key and never reuses the stale artifact.
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
