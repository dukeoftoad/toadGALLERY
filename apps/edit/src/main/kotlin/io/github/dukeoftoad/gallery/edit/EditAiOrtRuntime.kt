package io.github.dukeoftoad.gallery.edit

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * The r41 ONNX Runtime implementation of the inference seam (the
 * prebuilt-AAR answer to the contract's open item 1:
 * `com.microsoft.onnxruntime:onnxruntime-android` --- no editor NDK
 * build surface is added). This file is ANDROID-BOUND (the AAR lives
 * on the device classpath), so it stays OUT of the local pure harness
 * (`verify-r41.sh`); its gates are the lexer check + CI. The JVM tests
 * drive the seam over fakes (`EditAiEngineTest`) through the SAME
 * [EditAiInference] interface this class implements.
 *
 * Execution-provider policy (ADR-0010 §3): NNAPI-FIRST with CPU
 * fallback. The honest note: the int8 depth graph's
 * DynamicQuantizeLinear/MatMulInteger ops largely resolve to the CPU
 * execution provider, so NNAPI is requested for every session and the
 * fallback is EXPECTED for the depth model --- the request stands, the
 * honest fallback is recorded (the round doc).
 *
 * Every failure is a DEDICATED degradation, never a crash and never a
 * generic inference error: a model that fails the presence+size check
 * answers [EditAiDegradation.MODELS_MISSING_FROM_BUILD]; a session that
 * fails to load or a run that throws answers
 * [EditAiDegradation.RUNTIME_BROKEN] --- the affected feature says so,
 * the rest of the editor keeps working (the LibraryMissing precedent).
 */
class OrtEditAiInference(
    private val openAsset: (String) -> InputStream?,
) : EditAiInference {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /** Lazily built sessions per committed model; null = that model's degradation. */
    private var saliencySession: OrtSession? = null
    private var saliencyChecked = false
    private var inpaintSession: OrtSession? = null
    private var inpaintChecked = false
    private var depthSession: OrtSession? = null
    private var depthChecked = false
    private var facesSession: OrtSession? = null
    private var facesChecked = false

    // ---- the session cache (presence + size first; the digest is a release gate) ----

    private fun saliencySessionOrNull(): OrtSession? {
        if (saliencyChecked) return saliencySession
        saliencyChecked = true
        if (!EditAiManifest.presentAndSized(openAsset, EditAiManifest.ERASE_SEGMENTATION)) return null
        saliencySession = openSession(EditAiManifest.ERASE_SEGMENTATION)
        return saliencySession
    }

    private fun inpaintSessionOrNull(): OrtSession? {
        if (inpaintChecked) return inpaintSession
        inpaintChecked = true
        if (!EditAiManifest.presentAndSized(openAsset, EditAiManifest.ERASE_INPAINT)) return null
        inpaintSession = openSession(EditAiManifest.ERASE_INPAINT)
        return inpaintSession
    }

    private fun depthSessionOrNull(): OrtSession? {
        if (depthChecked) return depthSession
        depthChecked = true
        if (!EditAiManifest.presentAndSized(openAsset, EditAiManifest.BOKEH_DEPTH)) return null
        depthSession = openSession(EditAiManifest.BOKEH_DEPTH)
        return depthSession
    }

    private fun facesSessionOrNull(): OrtSession? {
        if (facesChecked) return facesSession
        facesChecked = true
        if (!EditAiManifest.presentAndSized(openAsset, EditAiManifest.RETOUCH_FACES)) return null
        facesSession = openSession(EditAiManifest.RETOUCH_FACES)
        return facesSession
    }

    private fun openSession(model: EditAiManifest.ModelFile): OrtSession? {
        return try {
            val bytes = openAsset(model.assetPath)?.use { it.readBytes() } ?: return null
            if (bytes.size.toLong() != model.bytes) return null
            val session = createSessionNnapiFirst(bytes)
            session
        } catch (_: RuntimeException) {
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    /** NNAPI-first: an NNAPI failure falls back to the CPU EP (the request stands). */
    private fun createSessionNnapiFirst(bytes: ByteArray): OrtSession? {
        return try {
            val options = OrtSession.SessionOptions()
            try {
                options.addNnapi()
                env.createSession(bytes, options)
            } finally {
                options.close()
            }
        } catch (_: RuntimeException) {
            // The honest fallback: the CPU EP is the default provider.
            val options = OrtSession.SessionOptions()
            try {
                env.createSession(bytes, options)
            } finally {
                options.close()
            }
        }
    }

    // ---- the model runs ----

    override fun saliencyMap(
        working: IntArray,
        width: Int,
        height: Int,
        progress: AiProgress,
    ): AiRun<IntArray> {
        val session = saliencySessionOrNull()
            ?: return AiRun.Unavailable(EditAiDegradation.MODELS_MISSING_FROM_BUILD)
        progress.report(10)
        return try {
            val size = EditAiModels.SALIENCY_SIZE
            val resized = EditAiPreprocess.resize(working, width, height, size, size)
            val tensorData = EditAiPreprocess.saliencyTensor(resized)
            val shape = longArrayOf(1L, 3L, size.toLong(), size.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape).use { input ->
                session.run(mapOf(EditAiModels.SALIENCY_INPUT_NAME to input)).use { result ->
                    progress.report(70)
                    // Result is Iterable<Map.Entry<String, OnnxValue>> (NOT a Map:
                    // the ORT 1.31.1 Java surface); collect the output names,
                    // sort, take the LAST as the fused d6 map (the graph's
                    // numeric output names sort in graph order).
                    val outputs = ArrayList<String>()
                    for (entry in result) outputs.add(entry.key)
                    outputs.sort()
                    if (outputs.size <= EditAiModels.SALIENCY_OUTPUT_INDEX) {
                        return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    }
                    val fused = result.get(outputs[EditAiModels.SALIENCY_OUTPUT_INDEX]).orElse(null)
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val flat = flattenFloats(fused.getValue()) ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    if (flat.size < size * size) {
                        return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    }
                    val out = AiRun.Ok(normaliseTo255(flat, size * size))
                    progress.report(100)
                    out
                }
            }
        } catch (_: RuntimeException) {
            AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
        }
    }

    override fun inpaintFill(
        working: IntArray,
        width: Int,
        height: Int,
        mask8: ByteArray,
        progress: AiProgress,
    ): AiRun<IntArray> {
        val session = inpaintSessionOrNull()
            ?: return AiRun.Unavailable(EditAiDegradation.MODELS_MISSING_FROM_BUILD)
        progress.report(10)
        return try {
            // The committed pipeline graph is DYNAMIC (the upstream's
            // promise: it crops around the mask and resizes internally),
            // so the working grid is fed at its own dims; the on-host
            // validation covered the 512x512 case --- other sizes ride the
            // dynamic promise and degrade honestly if the provider refuses.
            if (width < 1 || height < 1 || working.size != width * height) {
                return AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
            }
            if (mask8.size != width * height) {
                return AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
            }
            // image: uint8 CHW at [0,255], RGB planes (the cited convention:
            // uint8 RGB image; 255=keep, 0=hole for the mask plane).
            val plane = width * height
            val imageBytes = ByteArray(3 * plane)
            for (i in 0 until plane) {
                val c = working[i]
                imageBytes[i] = ((c shr 16) and 0xFF).toByte() // R plane
                imageBytes[plane + i] = ((c shr 8) and 0xFF).toByte() // G plane
                imageBytes[2 * plane + i] = (c and 0xFF).toByte() // B plane
            }
            val imageShape = longArrayOf(1L, 3L, height.toLong(), width.toLong())
            val maskShape = longArrayOf(1L, 1L, height.toLong(), width.toLong())
            OnnxTensor.createTensor(env, ByteBuffer.wrap(imageBytes), imageShape).use { image ->
                OnnxTensor.createTensor(env, ByteBuffer.wrap(mask8), maskShape).use { mask ->
                    session.run(
                        mapOf(
                            EditAiModels.INPAINT_IMAGE_NAME to image,
                            EditAiModels.INPAINT_MASK_NAME to mask,
                        ),
                    ).use { result ->
                        progress.report(70)
                        val outTensor = result.get(EditAiModels.INPAINT_RESULT_NAME).orElse(null)
                            ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                        val ints = flattenInts(outTensor.getValue())
                            ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                        if (ints.size < 3 * plane) {
                            return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                        }
                        // The result comes back planar RGB at the working dims.
                        val out = IntArray(plane)
                        for (i in 0 until plane) {
                            val r = ints[i].coerceIn(0, 255)
                            val g = ints[plane + i].coerceIn(0, 255)
                            val b = ints[2 * plane + i].coerceIn(0, 255)
                            out[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                        }
                        progress.report(100)
                        AiRun.Ok(out)
                    }
                }
            }
        } catch (_: RuntimeException) {
            AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
        }
    }

    override fun depthMap(
        working: IntArray,
        width: Int,
        height: Int,
        progress: AiProgress,
    ): AiRun<IntArray> {
        val session = depthSessionOrNull()
            ?: return AiRun.Unavailable(EditAiDegradation.MODELS_MISSING_FROM_BUILD)
        progress.report(10)
        return try {
            val size = EditAiModels.DEPTH_SIZE
            val resized = EditAiPreprocess.resize(working, width, height, size, size)
            val tensorData = EditAiPreprocess.depthTensor(resized)
            val shape = longArrayOf(1L, 3L, size.toLong(), size.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape).use { input ->
                session.run(mapOf(EditAiModels.DEPTH_INPUT_NAME to input)).use { result ->
                    progress.report(70)
                    val outTensor = result.get(EditAiModels.DEPTH_OUTPUT_NAME).orElse(null)
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val flat = flattenFloats(outTensor.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    if (flat.size < size * size) {
                        return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    }
                    val out = AiRun.Ok(normaliseTo255(flat, size * size))
                    progress.report(100)
                    out
                }
            }
        } catch (_: RuntimeException) {
            AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
        }
    }

    override fun faceBoxes(
        working: IntArray,
        width: Int,
        height: Int,
        progress: AiProgress,
    ): AiRun<List<FaceBoxes.Box>> {
        val session = facesSessionOrNull()
            ?: return AiRun.Unavailable(EditAiDegradation.MODELS_MISSING_FROM_BUILD)
        progress.report(10)
        return try {
            val size = EditAiModels.FACES_SIZE
            val resized = EditAiPreprocess.resize(working, width, height, size, size)
            val tensorData = EditAiPreprocess.facesTensor(resized)
            val shape = longArrayOf(1L, 3L, size.toLong(), size.toLong())
            OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), shape).use { input ->
                session.run(mapOf(EditAiModels.FACES_INPUT_NAME to input)).use { result ->
                    progress.report(70)
                    val cls8 = flattenFloats(result.get("cls_8").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val cls16 = flattenFloats(result.get("cls_16").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val cls32 = flattenFloats(result.get("cls_32").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val obj8 = flattenFloats(result.get("obj_8").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val obj16 = flattenFloats(result.get("obj_16").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val obj32 = flattenFloats(result.get("obj_32").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val bbox8 = flattenFloats(result.get("bbox_8").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val bbox16 = flattenFloats(result.get("bbox_16").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val bbox32 = flattenFloats(result.get("bbox_32").orElse(null)?.getValue())
                        ?: return@use AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
                    val boxes = EditAiDerive.decodeFaces(
                        cls8, cls16, cls32, obj8, obj16, obj32, bbox8, bbox16, bbox32,
                    )
                    progress.report(100)
                    AiRun.Ok(boxes)
                }
            }
        } catch (_: RuntimeException) {
            AiRun.Unavailable(EditAiDegradation.RUNTIME_BROKEN)
        }
    }

    // ---- the defensive output walkers ----

    /** Recursively flattens an ORT float output (nested arrays) to a FloatArray. */
    private fun flattenFloats(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val parts = value.map { flattenFloats(it) ?: return null }
            if (parts.isEmpty()) {
                FloatArray(0)
            } else {
                val total = parts.sumOf { it.size }
                val out = FloatArray(total)
                var at = 0
                for (p in parts) {
                    p.copyInto(out, at)
                    at += p.size
                }
                out
            }
        }
        is Number -> floatArrayOf(value.toFloat())
        else -> null
    }

    /** Recursively flattens an ORT uint8/int output to an IntArray (0..255 values). */
    private fun flattenInts(value: Any?): IntArray? = when (value) {
        is IntArray -> value
        is ByteArray -> IntArray(value.size) { value[it].toInt() and 0xFF }
        is Array<*> -> {
            val parts = value.map { flattenInts(it) ?: return null }
            if (parts.isEmpty()) {
                IntArray(0)
            } else {
                val total = parts.sumOf { it.size }
                val out = IntArray(total)
                var at = 0
                for (p in parts) {
                    p.copyInto(out, at)
                    at += p.size
                }
                out
            }
        }
        is Number -> intArrayOf(value.toInt())
        else -> null
    }

    /**
     * The upstream min-max normalisation to 0..255 ints (u2netp's
     * `normPRED` * 255 floor; the depth map's min-max), over a map whose
     * length is exactly [expected].
     */
    private fun normaliseTo255(flat: FloatArray, expected: Int): IntArray {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (v in flat) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val span = (max - min)
        val out = IntArray(expected)
        for (i in 0 until expected) {
            val v = flat[i]
            val norm = if (span <= 0f) 0f else (v - min) / span
            out[i] = (norm * 255f).toInt().coerceIn(0, 255)
        }
        return out
    }
}
