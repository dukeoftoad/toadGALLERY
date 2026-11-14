package com.toadgallery.editor

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toadgallery.editor.contract.EditContract
import com.toadgallery.editor.contract.EditRequestValidator
import com.toadgallery.editor.contract.MetadataPolicy
import com.toadgallery.editor.contract.RawExtras
import com.toadgallery.editor.contract.RequestValidation
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * toadGALLERY Edit --- the contract launch activity (spec
 * `docs/ARCHITECTURE.md`). Round trip: validate the launch
 * extras (invalid calls answer before any UI); restore the ADR-0011 sidecar
 * session (non-destructive, survives process death); edit as ONE
 * [EditRecipe] snapshot stack over a bounded decode; Save copy re-decodes
 * full resolution, runs [EditPipeline], compresses, INSERTs a NEW MediaStore
 * row (IS_PENDING on Q+, cancelled on failure), applies the EXIF policy and
 * clears the sidecar. The source URI is read-only by construction; zero
 * network; the export scope outlives the composition so a mid-export close
 * still answers a terminal state.
 */
class EditActivity : ComponentActivity() {

    private val exportScope = CoroutineScope(Dispatchers.IO + Job())

    /** The validated contract request; non-null after onCreate validation. */
    internal lateinit var request: com.toadgallery.editor.contract.EditRequest
        private set

    /** The ADR-0011 sidecar store (app-private, created lazily). */
    internal lateinit var sidecarStore: EditRecipeStore
        private set

    /**
     * The bundled `.cube` looks, parsed once and shared by the preview and
     * every export (the same [CubeLut] instances, so the look cannot
     * differ). Parsed lazily off the main thread by the first reader; a
     * broken asset is absent (the conformance test pins the committed set).
     */
    internal val bundledLooks: Map<String, CubeLut> by lazy {
        BundledLuts.loadAll { path -> assets.open(path) }
    }

    /**
     * SAF-picked `.cube` looks copied verbatim into app-private storage;
     * ids derived and collision-suffixed so they never shadow the bundled set.
     */
    internal val importedLutStore: ImportedLutStore by lazy {
        ImportedLutStore(File(filesDir, "imported-luts"))
    }

    /**
     * SAF-picked sticker PNG/WebP files copied verbatim into app-private
     * storage; ids collision-suffixed so they never shadow the bundled set.
     */
    internal val importedStickerStore: ImportedStickerStore by lazy {
        ImportedStickerStore(File(filesDir, "imported-stickers"))
    }

    /**
     * Imported-sticker bitmaps, decoded once per registry load and shared by
     * the preview and every export; a payload that fails to decode is absent.
     */
    internal val importedStickerBitmaps: MutableMap<String, Bitmap> =
        java.util.Collections.synchronizedMap(java.util.LinkedHashMap())

    /** Re-decodes every imported sticker (per import/removal, off the main thread); broken files are absent. */
    internal fun reloadImportedStickers(): Map<String, Bitmap> {
        val reloaded = java.util.LinkedHashMap<String, Bitmap>()
        for (sticker in importedStickerStore.list()) {
            val file = importedStickerStore.fileFor(sticker.id) ?: continue
            try {
                BitmapFactory.decodeFile(file.path)?.let { reloaded[sticker.id] = it }
            } catch (_: OutOfMemoryError) {
            } catch (_: RuntimeException) {
            }
        }
        importedStickerBitmaps.clear()
        importedStickerBitmaps.putAll(reloaded)
        return java.util.LinkedHashMap(reloaded)
    }

    /**
     * The render payload for a sticker id: the bundled vector, else the
     * imported bitmap, else null --- an unresolvable id renders nothing,
     * never a crash. One resolver shared by the preview and the export.
     */
    internal fun stickerResolver(id: String): StickerCompositor.Resolved? =
        BundledStickers.shape(id)?.let { StickerCompositor.Resolved.Vector(it) }
            ?: importedStickerBitmaps[id]?.let { StickerCompositor.Resolved.Bitmap(it) }

    /**
     * SAF-picked frame PNG/WebP files copied verbatim into app-private
     * storage; ids collision-suffixed so they never shadow the bundled set.
     */
    internal val importedFrameStore: ImportedFrameStore by lazy {
        ImportedFrameStore(File(filesDir, "imported-frames"))
    }

    /**
     * Imported-frame bitmaps, decoded once per registry load and shared by
     * the preview and every export; a payload that fails to decode is absent.
     */
    internal val importedFrameBitmaps: MutableMap<String, Bitmap> =
        java.util.Collections.synchronizedMap(java.util.LinkedHashMap())

    /** Re-decodes every imported frame (per import); broken files are absent. */
    internal fun reloadImportedFrames(): Map<String, Bitmap> {
        val reloaded = java.util.LinkedHashMap<String, Bitmap>()
        for (frame in importedFrameStore.list()) {
            val file = importedFrameStore.fileFor(frame.id) ?: continue
            try {
                BitmapFactory.decodeFile(file.path)?.let { reloaded[frame.id] = it }
            } catch (_: OutOfMemoryError) {
            } catch (_: RuntimeException) {
            }
        }
        importedFrameBitmaps.clear()
        importedFrameBitmaps.putAll(reloaded)
        return java.util.LinkedHashMap(reloaded)
    }

    // ---- AI seam: derived-artifact stores, inference, stage-input resolution ----

    /**
     * The derived-artifact store: `edit-ai/` in app-private storage beside
     * the sidecar directory --- the masks/fills/depth/face-box artifacts the
     * AI commits persist; every render replays over them, no model ever runs
     * at render time. Swept with the sidecar when the edit resolves.
     */
    internal val aiStore: EditAiStore by lazy {
        EditAiStore(File(filesDir, "edit-ai"), request.sourceUri)
    }

    /**
     * SAF-picked sky PNG/WebP files copied verbatim into app-private storage
     * (magic-before-write, size cap); ids collision-suffixed so they never
     * shadow the bundled parametric set.
     */
    internal val importedSkyStore: ImportedSkyStore by lazy {
        ImportedSkyStore(File(filesDir, "imported-skies"))
    }

    /**
     * Imported-sky bitmaps, decoded once per registry load and shared by the
     * preview and every export; a payload that fails to decode is absent.
     */
    internal val importedSkyBitmaps: MutableMap<String, Bitmap> =
        java.util.Collections.synchronizedMap(java.util.LinkedHashMap())

    /** Re-decodes every imported sky (per import); broken files are absent. */
    internal fun reloadImportedSkies(): Map<String, Bitmap> {
        val reloaded = java.util.LinkedHashMap<String, Bitmap>()
        for (sky in importedSkyStore.list()) {
            val file = importedSkyStore.fileFor(sky.id) ?: continue
            try {
                BitmapFactory.decodeFile(file.path)?.let { reloaded[sky.id] = it }
            } catch (_: OutOfMemoryError) {
            } catch (_: RuntimeException) {
            }
        }
        importedSkyBitmaps.clear()
        importedSkyBitmaps.putAll(reloaded)
        return java.util.LinkedHashMap(reloaded)
    }

    /** The on-device model store: app-private files, download-on-demand (see EditAiModelStore). */
    internal val aiModelStore: EditAiModelStore by lazy { EditAiModelStore(this) }

    /**
     * The inference seam: the ORT facade, NNAPI-first with CPU fallback.
     * Lazy --- the runtime loads on the first AI commit, never at launch; a
     * missing/broken runtime degrades the affected feature, never a crash.
     */
    internal val aiInference: EditAiInference by lazy {
        OrtEditAiInference { path -> aiModelStore.openRelative(path) }
    }

    /** The face-retouch consent store (ADR-0010 section 5; default-off). */
    internal val retouchConsentStore: RetouchConsentStore by lazy {
        RetouchConsentStore(File(filesDir, "edit-settings"))
    }

    /**
     * The caller-resolved AI artifact bundle for a recipe: the store's
     * artifacts for the recipe's keys, the resolved sky source (bundled
     * parametric code or imported bitmap), and the retouch consent gate
     * (the stage refuses under consent-off even with a restored sidecar).
     * ONE resolver shared by the preview and every export; a missing
     * artifact degrades that stage to nothing, never a crash.
     */
    internal fun resolveAiStageInputs(recipe: EditRecipe): EditAiStageInputs {
        val eraseMasks = HashMap<String, FractionRleMask>()
        val eraseFills = HashMap<String, FillPatch>()
        recipe.aiErase?.layers?.forEach { layer ->
            val ref = layer.artifactRef
            if (ref.isEmpty()) return@forEach
            aiStore.readEraseArtifact(ref)?.let { (mask, fill) ->
                eraseMasks[layer.id] = mask
                eraseFills[layer.id] = fill
            }
        }
        val skyMask = recipe.sky?.maskRef?.takeIf { it.isNotEmpty() }?.let { aiStore.readSkyMask(it) }
        val skySource = recipe.sky?.let { sel ->
            if (BundledSkies.byId(sel.skyId) != null) {
                SkySource.Parametric(sel.skyId)
            } else {
                importedSkyBitmaps[sel.skyId]?.let { bmp ->
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    SkySource.Pixels(px, bmp.width, bmp.height)
                }
            }
        }
        val depth = recipe.bokeh?.depthRef?.takeIf { it.isNotEmpty() }?.let { aiStore.readDepth(it) }
        val boxes = recipe.retouch?.boxesRef?.takeIf { it.isNotEmpty() }?.let { aiStore.readBoxes(it) }
        return EditAiStageInputs(
            eraseMasks = eraseMasks,
            eraseFills = eraseFills,
            skyMask = skyMask,
            skySource = skySource,
            depth = depth,
            faceBoxes = boxes,
            retouchConsentGranted = retouchConsentStore.load().stageMayRun,
        )
    }

    /**
     * The bundled Typefaces, created once per session and shared by the
     * preview and every export; a broken asset is absent (the conformance
     * test pins the committed bytes).
     */
    internal val bundledFonts: Map<String, Typeface> by lazy {
        BundledFonts.ORDERED.mapNotNull { entry ->
            try {
                Typeface.createFromAsset(assets, BundledFonts.assetPath(entry))
                    ?.let { entry.id to it }
            } catch (_: RuntimeException) {
                null
            }
        }.toMap()
    }

    /**
     * The render face for a layer fontId: the bundled face, else the DEFAULT
     * face --- never a crash, never a dropped layer.
     */
    internal fun fontFor(fontId: String): Typeface? =
        bundledFonts[fontId] ?: bundledFonts[BundledFonts.DEFAULT_ID]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Dark SystemBarStyle: light icons on the always-black canvas under
        // edge-to-edge; the layout inset is paid at the root Column below.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        // ---- Contract validation (pre-UI: invalid calls answer immediately) ----
        val sourceFromClip = intent?.clipData?.let { clip ->
            if (clip.itemCount > 0) clip.getItemAt(0).uri?.toString() else null
        }
        val raw = RawExtras(
            contractVersion = if (intent?.hasExtra(EditContract.EXTRA_CONTRACT_VERSION) == true) {
                intent.getIntExtra(EditContract.EXTRA_CONTRACT_VERSION, Int.MIN_VALUE)
            } else {
                null
            },
            sourceUri = sourceUriFromExtra(intent) ?: sourceFromClip,
            operationHint = intent?.getStringExtra(EditContract.EXTRA_OPERATION_HINT),
            exportMode = intent?.getStringExtra(EditContract.EXTRA_EXPORT_MODE),
            metadataPolicy = intent?.getStringExtra(EditContract.EXTRA_METADATA_POLICY),
        )
        when (val validation = EditRequestValidator.validate(
            raw = raw,
            acceptedVersionMin = EditContract.CONTRACT_VERSION_MIN,
            acceptedVersionMax = EditContract.CONTRACT_VERSION,
        )) {
            is RequestValidation.Invalid -> {
                finishWithError(validation.invalid.errorCode, validation.invalid.reason)
                return
            }
            is RequestValidation.Valid -> request = validation.request
        }

        // ---- Decode feasibility (fail fast on unsupported formats) ----
        val bounds = decodeBounds(request.sourceUri)
        if (bounds == null || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            finishWithError(
                EditContract.ERROR_UNSUPPORTED,
                "cannot decode ${request.sourceUri} on this device",
            )
            return
        }

        // ---- The sidecar store + the restored session ----
        sidecarStore = EditRecipeStore(File(filesDir, "edit-sidecars"))
        val restored = sidecarStore.load(request.sourceUri) ?: EditRecipe()

        setContent {
            // The family theme (fixed dark) instead of bare MaterialTheme,
            // whose baseline purple leaks through every accent role.
            EditorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(LilypadTokens.POND.toInt())) {
                    EditorContent(activity = this@EditActivity, initialRecipe = restored)
                }
            }
        }
    }

    // ---- Result plumbing (the three terminal states) ----

    /** The launch's source URI as a Parcelable Uri (spec section 2), API-33-compat read. */
    private fun sourceUriFromExtra(intent: Intent?): String? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EditContract.EXTRA_SOURCE_URI, Uri::class.java)?.toString()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(EditContract.EXTRA_SOURCE_URI)?.toString()
        }
    }

    /** RESULT_ERROR + structured code, then finish. */
    internal fun finishWithError(errorCode: String, message: String) {
        val result = Intent().apply {
            putExtra(EditContract.EXTRA_ERROR_CODE, errorCode)
            putExtra(EditContract.EXTRA_ERROR_MESSAGE, message)
        }
        setResult(EditContract.RESULT_ERROR, result)
        finish()
    }

    /** RESULT_OK with the saved-copy URI + the applied policy, then finish. */
    internal fun finishWithSavedCopy(outputUri: Uri, appliedPolicy: MetadataPolicy) {
        val result = Intent().apply {
            putExtra(EditContract.EXTRA_OUTPUT_URI, outputUri.toString())
            putExtra(EditContract.EXTRA_METADATA_POLICY_APPLIED, appliedPolicy.wireName)
        }
        setResult(EditContract.RESULT_OK, result)
        finish()
    }

    internal fun finishCanceled() {
        setResult(EditContract.RESULT_CANCELED)
        finish()
    }

    /** Kicks the export pipeline on the outliving scope. */
    internal fun startExport(export: ExportRequest) {
        exportScope.launch { runExport(export) }
    }

    // ---- Decode helpers ----

    internal fun decodeBounds(sourceUri: String): BitmapFactory.Options? = try {
        contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also {
                BitmapFactory.decodeStream(input, null, it)
            }
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** Bitmap decode with a bounded sample pass; null on any failure. */
    internal fun decodeBitmap(sourceUri: String, maxDimension: Int): Bitmap? = try {
        val uri = Uri.parse(sourceUri)
        contentResolver.openInputStream(uri)?.use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDimension ||
                bounds.outHeight / (sample * 2) >= maxDimension
            ) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use { second ->
                BitmapFactory.decodeStream(second, null, options)
            }
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /** The source's display name, for the saved-copy naming. */
    internal fun sourceDisplayName(sourceUri: String): String? = try {
        contentResolver.query(
            Uri.parse(sourceUri),
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * The device's allocatable bytes, or null when the platform answer is
     * unavailable --- an unknown value must never block an export (MediaStore
     * enforces regardless).
     */
    internal fun queryAllocatableBytes(): Long? = try {
        val storageManager = getSystemService(StorageManager::class.java) ?: return null
        storageManager.getAllocatableBytes(StorageManager.UUID_DEFAULT)
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    /**
     * The export pipeline. IO dispatcher; on ANY failure the pending row is
     * cancelled (Q+) and RESULT_ERROR answers --- never a partial entry.
     * On success the sidecar is CLEARED (the edit resolved into the copy).
     */
    private suspend fun runExport(export: ExportRequest): Unit = withContext(Dispatchers.IO) {
        val api29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val sourceUri = request.sourceUri
        var outputUri: Uri? = null
        val format = export.format
        try {

            // 1. The cheap bounds pass: the decode guard refuses a source the
            //    pipeline could not hold --- ERROR_UNSUPPORTED before any work.
            val bounds = decodeBounds(sourceUri)
            if (bounds != null &&
                ExportPreflight.decodeGuard(
                    bounds.outWidth,
                    bounds.outHeight,
                    ExportPreflight.MAX_EXPORT_PIXELS,
                ) == ExportPreflight.Decision.INSUFFICIENT
            ) {
                throw ExportException(
                    EditContract.ERROR_UNSUPPORTED,
                    "the image is too large to edit on this device",
                )
            }

            // 2. Storage preflight: refuse an insert the device cannot hold
            //    (advisory --- unknown availability proceeds; MediaStore still
            //    enforces). ERROR_IO keeps the v1 contract surface untouched.
            val allocatable = queryAllocatableBytes()
            if (allocatable != null) {
                val assumedPixels =
                    if (bounds != null && bounds.outWidth > 0 && bounds.outHeight > 0) {
                        bounds.outWidth.toLong() * bounds.outHeight.toLong()
                    } else {
                        ExportPreflight.ASSUMED_PIXELS
                    }
                val estimated = ExportPreflight.estimateBytes(assumedPixels, format, export.quality)
                if (ExportPreflight.decide(estimated, allocatable) ==
                    ExportPreflight.Decision.INSUFFICIENT
                ) {
                    throw ExportException(
                        EditContract.ERROR_IO,
                        "not enough free storage for the exported copy",
                    )
                }
            }

            // 3. Full-resolution decode.
            val source = decodeBitmap(sourceUri, Int.MAX_VALUE)
                ?: throw ExportException(
                    EditContract.ERROR_UNSUPPORTED,
                    "cannot decode the source at full resolution",
                )

            // 4. The ordered pipeline (geometry first, then colour/detail);
            //    the look resolves through the SAME lazy cache the preview used.
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val look = export.recipe.lut?.let { bundledLooks[it.lutId] }
            // The AI stages replay over the SAME resolved artifact bundle the
            // preview used --- no model runs during export; the fill patch is
            // bounded by the session's working resolution, so an erased region
            // on a very large export is softer than its surroundings.
            val ai = resolveAiStageInputs(export.recipe)
            // The keystone quad was authored on the PREVIEW grid; the export
            // decodes full resolution, so the same warp retargets via the
            // resolution-independent percent pairs (LilypadKeystoneMath pins
            // the rule). A 0 preview grid or an identity quad rides verbatim.
            val exportRecipe = if (
                export.recipe.perspective != PerspectiveQuad.IDENTITY &&
                export.previewWidth > 0 && export.previewHeight > 0
            ) {
                export.recipe.copy(
                    perspective = LilypadKeystoneMath.scaledForExport(
                        export.recipe.perspective,
                        export.previewWidth,
                        export.previewHeight,
                        width,
                        height,
                    ),
                )
            } else {
                export.recipe
            }
            val pipelined = EditPipeline.apply(pixels, width, height, exportRecipe, look, ai)
            val adjusted = Bitmap.createBitmap(
                pipelined.width, pipelined.height, Bitmap.Config.ARGB_8888,
            )
            adjusted.setPixels(
                pipelined.pixels, 0, pipelined.width, 0, 0, pipelined.width, pipelined.height,
            )

            // 4.5 The compositing passes AFTER the whole pixel pipeline, over
            //     the FULL output canvas at origin 0 --- the same compositors
            //     the preview uses, so preview ≡ export by construction.
            //     Family order: stickers (scene objects) BELOW strokes
            //     (annotation) BELOW text (captions on top).
            export.recipe.stickerLayers?.let { stickerSpec ->
                if (stickerSpec.layers.isNotEmpty()) {
                    StickerCompositor.draw(
                        adjusted,
                        stickerSpec.layers,
                        { stickerId -> stickerResolver(stickerId) },
                        0f,
                        0f,
                        pipelined.width.toFloat(),
                        pipelined.height.toFloat(),
                    )
                }
            }
            export.recipe.drawLayers?.let { drawSpec ->
                if (drawSpec.layers.isNotEmpty()) {
                    DrawCompositor.draw(
                        adjusted,
                        drawSpec.layers,
                        0f,
                        0f,
                        pipelined.width.toFloat(),
                        pipelined.height.toFloat(),
                    )
                }
            }
            export.recipe.textLayers?.let { textSpec ->
                if (textSpec.layers.isNotEmpty()) {
                    TextCompositor.draw(
                        adjusted,
                        textSpec.layers,
                        { fontId -> fontFor(fontId) },
                        0f,
                        0f,
                        pipelined.width.toFloat(),
                        pipelined.height.toFloat(),
                    )
                }
            }
            // The frame LAST: the mounting covers everything.
            export.recipe.frame?.let { frameSel ->
                FrameCompositor.draw(
                    adjusted,
                    frameSel,
                    { frameId ->
                        importedFrameBitmaps[frameId]?.let { FrameCompositor.Resolved.Bitmap(it) }
                    },
                    0f,
                    0f,
                    pipelined.width.toFloat(),
                    pipelined.height.toFloat(),
                )
            }

            // 5. Insert the NEW MediaStore row (IS_PENDING on Q+).
            val displayName = ExportPlan.displayNameFor(sourceDisplayName(sourceUri), format)
            val collection = if (api29Plus) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues()
            ExportPlan.insertValues(displayName, format, api29Plus).forEach { (key, value) ->
                when (value) {
                    is String -> values.put(key, value)
                    is Int -> values.put(key, value)
                }
            }
            val inserted = contentResolver.insert(collection, values)
                ?: throw ExportException(EditContract.ERROR_IO, "MediaStore refused the insert")
            outputUri = inserted

            // 6. Compress into the pending row (quality: lossy formats only).
            val compressFormat = when (format) {
                ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                ExportFormat.WEBP -> Bitmap.CompressFormat.WEBP
            }
            val effectiveQuality = if (format == ExportFormat.PNG) 100 else export.quality
            contentResolver.openOutputStream(inserted)?.use { out ->
                if (!adjusted.compress(compressFormat, effectiveQuality, out)) {
                    throw ExportException(EditContract.ERROR_IO, "compression failed")
                }
                out.flush()
            } ?: throw ExportException(EditContract.ERROR_IO, "cannot open the output stream")

            // 7. Apply the EXIF metadata policy exactly (contract 4.4).
            applyMetadataPolicy(sourceUri, inserted, export.metadataPolicy)

            // 8. Finalize (clear IS_PENDING).
            if (api29Plus) {
                val finalize = ContentValues()
                ExportPlan.finalizeValues(api29Plus).forEach { (key, value) ->
                    finalize.put(key, value as Int)
                }
                contentResolver.update(inserted, finalize, null, null)
            }

            // 9. The sidecar's edit resolved into the copy: clear it BEFORE
            //    reporting success (the export itself succeeded; a leftover
            //    sidecar self-heals on next load). The derived-artifact store
            //    sweeps together with the sidecar (append-only until the edit
            //    resolves; this is the only delete).
            try {
                sidecarStore.clear(sourceUri)
                aiStore.sweep()
            } catch (_: RuntimeException) {
                // Best-effort; never mask the export's success.
            }

            withContext(Dispatchers.Main) {
                finishWithSavedCopy(inserted, export.metadataPolicy)
            }
        } catch (e: ExportException) {
            cancelPendingRow(outputUri, api29Plus)
            withContext(Dispatchers.Main) { finishWithError(e.errorCode, e.message ?: "") }
        } catch (_: SecurityException) {
            cancelPendingRow(outputUri, api29Plus)
            withContext(Dispatchers.Main) {
                finishWithError(EditContract.ERROR_PERMISSIONS, "a MediaStore access was refused")
            }
        } catch (_: IOException) {
            cancelPendingRow(outputUri, api29Plus)
            withContext(Dispatchers.Main) {
                finishWithError(EditContract.ERROR_IO, "an I/O failure occurred during export")
            }
        } catch (_: OutOfMemoryError) {
            cancelPendingRow(outputUri, api29Plus)
            withContext(Dispatchers.Main) {
                finishWithError(EditContract.ERROR_IO, "the image is too large for this device's memory")
            }
        } catch (e: RuntimeException) {
            cancelPendingRow(outputUri, api29Plus)
            withContext(Dispatchers.Main) {
                finishWithError(EditContract.ERROR_INTERNAL, e.message ?: "unexpected export failure")
            }
        }
    }

    /** Cancels a pending row on Q+ so no partial entry survives a failed write. */
    private fun cancelPendingRow(outputUri: Uri?, api29Plus: Boolean) {
        if (outputUri != null && api29Plus &&
            ExportPlan.failureRecovery(api29Plus) == ExportPlan.Recovery.CANCEL_PENDING_ROW
        ) {
            try {
                contentResolver.delete(outputUri, null, null)
            } catch (_: RuntimeException) {
                // Best-effort: never mask the export's own error.
            }
        }
    }

    /**
     * Copies the source EXIF onto the saved copy per the policy: PRESERVE
     * copies every tag, STRIP_LOCATION nulls the GPS tags, STRIP_ALL writes
     * nothing at all.
     */
    private fun applyMetadataPolicy(
        sourceUri: String,
        outputUri: Uri,
        policy: MetadataPolicy,
    ) {
        if (policy == MetadataPolicy.STRIP_ALL) return
        val sourceExif = contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
            androidx.exifinterface.media.ExifInterface(input)
        } ?: return
        contentResolver.openFileDescriptor(outputUri, "rw")?.use { fd ->
            val target = androidx.exifinterface.media.ExifInterface(fd.fileDescriptor)
            // androidx.exifinterface exposes no public tag enumeration, so
            // the copy walks MetadataPolicyApplier.COPYABLE_TAGS and skips
            // tags the source does not carry.
            for (tag in MetadataPolicyApplier.COPYABLE_TAGS) {
                val value = sourceExif.getAttribute(tag) ?: continue
                when (MetadataPolicyApplier.decide(policy, tag)) {
                    MetadataPolicyApplier.TagDecision.Copy ->
                        target.setAttribute(tag, value)
                    MetadataPolicyApplier.TagDecision.Strip ->
                        target.setAttribute(tag, null)
                }
            }
            target.saveAttributes()
        } ?: throw ExportException(EditContract.ERROR_IO, "cannot reopen the copy for EXIF")
    }

    /** The internal failure type carrying a structured contract error code. */
    private class ExportException(val errorCode: String, message: String) : RuntimeException(message)
}

// ---- Compose UI ----

/** Maximum preview dimension --- the session edits a bounded preview; export re-decodes full. */
private const val PREVIEW_MAX_DIMENSION: Int = 2048

/** One observable editor snapshot: the recipe + the undo/redo affordance states. */
data class EditorUiState(
    val recipe: EditRecipe,
    val canUndo: Boolean,
    val canRedo: Boolean,
)

/**
 * The export sheet's state: format, quality, and the per-export metadata
 * policy chosen by the user. A `null` [metadataPolicy] means "no override"
 * --- the launch request's policy rides as-is (the privacy floor: the sheet
 * may strip more, never less).
 */
data class ExportChoice(
    val format: ExportFormat = ExportPlan.DEFAULT_FORMAT,
    val quality: Int = ExportPlan.DEFAULT_QUALITY,
    val metadataPolicy: MetadataPolicy? = null,
)

/**
 * The session engine bridging the pure [EditSessionState] stack to Compose:
 * every commit/undo/redo returns a fresh [EditorUiState] the UI observes.
 * [depth]/[pointerIndex]/[entryAt] are the ring's read-only view for the
 * history strip; [jumpTo] walks to a tapped index by repeated undo/redo
 * only --- the sanctioned moves, so the strip can never corrupt the trail.
 */
private class EditSessionEngine(initial: EditRecipe) {
    private val stack = EditSessionState(initial)

    val initial: EditorUiState = EditorUiState(stack.current, stack.canUndo, stack.canRedo)

    /** The number of snapshots the ring holds (the strip's chip count). */
    val depth: Int get() = stack.depth

    /** The pointer's position in the ring (the strip's current chip). */
    val pointerIndex: Int get() = stack.pointerIndex

    /** The snapshot at [index], or null outside the ring (the strip's label source). */
    fun entryAt(index: Int): EditRecipe? = stack.entryAt(index)

    fun commit(recipe: EditRecipe): EditorUiState {
        stack.commit(recipe)
        return EditorUiState(stack.current, stack.canUndo, stack.canRedo)
    }

    fun undo(): EditorUiState {
        stack.undo()
        return EditorUiState(stack.current, stack.canUndo, stack.canRedo)
    }

    fun redo(): EditorUiState {
        stack.redo()
        return EditorUiState(stack.current, stack.canUndo, stack.canRedo)
    }

    /**
     * Moves the pointer to [index] by repeated undo/redo (each an O(1) ring
     * move); an index outside the ring clamps at its ends. The caller sweeps
     * any pending gesture draft first.
     */
    fun jumpTo(index: Int): EditorUiState {
        while (stack.pointerIndex > index) stack.undo()
        while (stack.pointerIndex < index) stack.redo()
        return EditorUiState(stack.current, stack.canUndo, stack.canRedo)
    }
}

/**
 * One slider's binding: identity key, current value, next-state builder.
 * The a11y name is the semantics contentDescription; the label string still
 * carries the live value for the visible text and the stateDescription.
 */
internal data class SliderSpec(
    val key: String,
    val label: Int,
    val a11yNameRes: Int,
    val get: (EditRecipe) -> Int,
    val set: (EditRecipe, Int) -> EditRecipe,
)

/** The tone + colour + detail sliders (the full adjustment suite minus HSL bands). */
internal val ADJUST_SLIDERS: List<SliderSpec> = listOf(
    SliderSpec("exposure", R.string.editor_exposure, R.string.editor_a11y_exposure, { it.exposure }, { r, v -> r.copy(exposure = v) }),
    SliderSpec("brightness", R.string.editor_brightness, R.string.editor_a11y_brightness, { it.brightness }, { r, v -> r.copy(brightness = v) }),
    SliderSpec("contrast", R.string.editor_contrast, R.string.editor_a11y_contrast, { it.contrast }, { r, v -> r.copy(contrast = v) }),
    // The far-end tone pair rides right after shadows --- the Lightroom order.
    SliderSpec("highlights", R.string.editor_highlights, R.string.editor_a11y_highlights, { it.highlights }, { r, v -> r.copy(highlights = v) }),
    SliderSpec("shadows", R.string.editor_shadows, R.string.editor_a11y_shadows, { it.shadows }, { r, v -> r.copy(shadows = v) }),
    SliderSpec("whites", R.string.editor_whites, R.string.editor_a11y_whites, { it.whites }, { r, v -> r.copy(whites = v) }),
    SliderSpec("blacks", R.string.editor_blacks, R.string.editor_a11y_blacks, { it.blacks }, { r, v -> r.copy(blacks = v) }),
    SliderSpec("saturation", R.string.editor_saturation, R.string.editor_a11y_saturation, { it.saturation }, { r, v -> r.copy(saturation = v) }),
    // Vibrance: the luma-weighted saturation, right after its flat sibling.
    SliderSpec("vibrance", R.string.editor_vibrance, R.string.editor_a11y_vibrance, { it.vibrance }, { r, v -> r.copy(vibrance = v) }),
    SliderSpec("temperature", R.string.editor_temperature, R.string.editor_a11y_temperature, { it.temperature }, { r, v -> r.copy(temperature = v) }),
    SliderSpec("tint", R.string.editor_tint, R.string.editor_a11y_tint, { it.tint }, { r, v -> r.copy(tint = v) }),
    SliderSpec("vignette", R.string.editor_vignette, R.string.editor_a11y_vignette, { it.vignette }, { r, v -> r.copy(vignette = v) }),
    SliderSpec("sharpness", R.string.editor_sharpness, R.string.editor_a11y_sharpness, { it.sharpness }, { r, v -> r.copy(sharpness = v) }),
)

/** The eight HSL bands with their display names (fixed order). */
internal val HSL_BANDS: List<Pair<String, (HslBands) -> HslBandAdjust>> = listOf(
    "red" to { it.red },
    "orange" to { it.orange },
    "yellow" to { it.yellow },
    "green" to { it.green },
    "aqua" to { it.aqua },
    "blue" to { it.blue },
    "purple" to { it.purple },
    "magenta" to { it.magenta },
)

private fun HslBands.withBand(name: String, band: HslBandAdjust): HslBands = when (name) {
    "red" -> copy(red = band)
    "orange" -> copy(orange = band)
    "yellow" -> copy(yellow = band)
    "green" -> copy(green = band)
    "aqua" -> copy(aqua = band)
    "blue" -> copy(blue = band)
    "purple" -> copy(purple = band)
    else -> copy(magenta = band)
}

// The PhotoScreen enum, the tool header and the tab bar live in `PhotoChrome.kt`.

@OptIn(ExperimentalMaterial3Api::class) // FilterChip in the export sheet
@Composable
private fun EditorContent(activity: EditActivity, initialRecipe: EditRecipe) {
    val policy = activity.request.metadataPolicy
    val engine = remember { EditSessionEngine(initialRecipe) }

    var uiState by remember { mutableStateOf(engine.initial) }
    var selectedBand by remember { mutableStateOf(HSL_BANDS.first().first) }
    var exportChoice by remember { mutableStateOf<ExportChoice?>(null) }
    // The copy-paste edits clipboard: ONE recipe slot, remembered at the
    // chassis so it survives tool switches and photo handoffs but dies with
    // the session (no cross-session storage for a grade).
    var copiedGrade by remember { mutableStateOf<EditRecipe?>(null) }
    var previewBase by remember { mutableStateOf<Bitmap?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    // The Adjust panel's live tonal histogram, recomputed by the preview
    // render from the SAME pipelined pixels the canvas shows --- it follows
    // a drag tick for tick and can never disagree with the preview.
    var previewHistogram by remember { mutableStateOf<EditorHistogram?>(null) }
    // The pro scopes' chassis state: the scope mode (a view preference) and
    // the zebra overlay toggle. The zebra bitmap reads the same pipelined
    // pixels as the histogram, recomputed inside the render pass while the
    // overlay is on.
    var scopeMode by remember { mutableStateOf(PhotoScopeMode.Histogram) }
    var zebraActive by remember { mutableStateOf(false) }
    var previewZebra by remember { mutableStateOf<ImageBitmap?>(null) }
    // The curve deck's chassis state: the Full-state host flag, the edited
    // channel (a view preference, commits nothing), and the LIVE whole-curve
    // draft --- every drag tick writes it, ONE recipe commit on release.
    var curveDeckOpen by remember { mutableStateOf(false) }
    var curveChannel by remember { mutableStateOf(CurveChannel.Master) }
    var curveDraft by remember { mutableStateOf<ToneCurves?>(null) }
    // The corner drag's live quad draft: every clamped drag tick writes it,
    // release commits ONE recipe.
    var cornerDraft by remember { mutableStateOf<PerspectiveQuad?>(null) }
    // The history strip's chassis state: the open flag (a view preference,
    // gated by LilypadFlags.historyStripEnabled) and the REVISION the label
    // model recomputes on --- EditorUiState can compare EQUAL across a
    // pointer move, so the strip keys on this counter; the ring's three
    // mutation funnels (commit, header undo/redo, strip jump) each bump it.
    var historyOpen by remember { mutableStateOf(false) }
    var historyTick by remember { mutableStateOf(0) }
    var previewCropRect by remember { mutableStateOf<PixelRect?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExportAfterPermission by remember { mutableStateOf(false) }
    // The ONE live-drag draft (key -> value); committed on release.
    var draft by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // The parsed look registry: the bundled set MERGED with the user-imported
    // store (disjoint ids by construction); the version counter re-runs it
    // after an import or a removal.
    var looks by remember { mutableStateOf<Map<String, CubeLut>>(emptyMap()) }
    var looksVersion by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    // Text state: the selected layer, the TextField's LOCAL draft
    // (per-keystroke commits would flood the undo stack) and the position
    // drag's live draft. All three live-preview through effectiveRecipe()
    // and commit once on done/release/target-switch/export.
    var selectedTextLayerId by remember { mutableStateOf<String?>(null) }
    var textDraft by remember { mutableStateOf<Pair<String, String>?>(null) }
    var posDraft by remember { mutableStateOf<Triple<String, Double, Double>?>(null) }
    // Sticker state: the selected layer and the position drag's live draft;
    // the imported-bitmap snapshot the preview re-renders on; the import
    // rejection line. ONE selected layer at a time across the families.
    var selectedStickerLayerId by remember { mutableStateOf<String?>(null) }
    var stickerPosDraft by remember { mutableStateOf<Triple<String, Double, Double>?>(null) }
    var importedStickers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var stickersVersion by remember { mutableStateOf(0) }
    var stickerImportError by remember { mutableStateOf<String?>(null) }
    // Drawing state: the paint mode (ON = painting; a selected layer =
    // editing, one selection at a time across all families), the in-progress
    // gesture's live point list, and the brush settings for the NEXT stroke
    // (applied at commit).
    var selectedDrawLayerId by remember { mutableStateOf<String?>(null) }
    var drawMode by remember { mutableStateOf(false) }
    var paintDraft by remember { mutableStateOf<List<DrawLayer.Point>?>(null) }
    var brushColor by remember { mutableStateOf(0xFFFFFFFFL) }
    var brushWidth by remember { mutableStateOf(3) }
    var brushOpacity by remember { mutableStateOf(100) }
    var brushSmoothing by remember { mutableStateOf(50) }
    // Region state: the region paint mode (mutually exclusive with Draw
    // mode, enforced at both toggles), the in-progress gesture's live point
    // list, and the next region's kind + strength.
    var selectedRegionLayerId by remember { mutableStateOf<String?>(null) }
    var regionMode by remember { mutableStateOf(false) }
    var regionPaintDraft by remember { mutableStateOf<List<RegionLayer.Point>?>(null) }
    var regionKind by remember { mutableStateOf(RegionKind.MOSAIC) }
    var regionStrength by remember { mutableStateOf(12) }
    // Frame state: the imported-bitmap snapshot, the version counter and the
    // import rejection line. The frame selection lives in the recipe (a
    // single selection, like the look).
    var importedFrames by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var framesVersion by remember { mutableStateOf(0) }
    var frameImportError by remember { mutableStateOf<String?>(null) }
    // AI state: the Erase paint mode (one paint mode at a time across
    // Draw/Region/Erase, enforced at all three toggles) + its live point
    // list; the commit progress (aiBusy, the fraction, the cancel flag the
    // inference seam polls); the degradation message; the imported-sky
    // registry + version counter + import rejection line; the retouch
    // consent state (default-off) + first-use dialog + settings sheet flag.
    var eraseMode by remember { mutableStateOf(false) }
    var erasePaintDraft by remember { mutableStateOf<List<AiEraseLayer.Point>?>(null) }
    var selectedEraseLayerId by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiProgressPct by remember { mutableStateOf(0) }
    var aiCancelRequested by remember { mutableStateOf(false) }
    var aiNotice by remember { mutableStateOf<Int?>(null) }
    // The on-demand model gate: the models are not bundled in the APK; each AI
    // commit first ensures its models are on the device, asking for ONE explicit
    // consent per batch to download from the pinned release URLs (the editor's
    // only network use). The gate is a fresh CompletableDeferred per prompt.
    var aiDownloadPrompt by remember { mutableStateOf<List<EditAiManifest.ModelFile>?>(null) }
    var aiDownloadPct by remember { mutableStateOf(-1) }
    var aiDownloadTotalMb by remember { mutableStateOf(1) }
    val aiDownloadGate = remember { mutableStateOf(CompletableDeferred<Boolean>()) }
    var importedSkies by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var skiesVersion by remember { mutableStateOf(0) }
    var skyImportError by remember { mutableStateOf<String?>(null) }
    var retouchConsent by remember { mutableStateOf(RetouchConsent.NOT_GRANTED) }
    var retouchConsentDialog by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    // The chassis navigation state: the main canvas screen vs the ten tool screens.
    var screen by remember { mutableStateOf(PhotoScreen.Main) }
    // The recipe as it was when the CURRENT tool screen was entered --- the
    // tool Reset's boundary (reverting is ONE undoable commit against it).
    var entryRecipe by remember { mutableStateOf<EditRecipe?>(null) }

    val committed = uiState.recipe

    /** The recipe the preview renders: the committed one with any live draft applied. */
    fun effectiveRecipe(): EditRecipe {
        var effective = committed
        val d = draft
        if (d != null) {
            val spec = ADJUST_SLIDERS.firstOrNull { it.key == d.first }
            effective = when {
                spec != null -> spec.set(effective, d.second)
                // The straighten drag previews live, like the adjustment sliders.
                d.first == "straighten" -> effective.copy(straightenDeg = d.second.toDouble())
                // The keystone drags preview live: the drafted knob rides on
                // top of the other knob's committed read-back, so what the
                // drag shows is what the release lands; a draft with no
                // decoded base drops.
                d.first == KEYSTONE_VERTICAL || d.first == KEYSTONE_HORIZONTAL -> {
                    val base = previewBase
                    if (base != null) {
                        val vertical = if (d.first == KEYSTONE_VERTICAL) {
                            d.second
                        } else {
                            LilypadKeystoneMath.verticalPctOf(effective.perspective, base.width)
                        }
                        val horizontal = if (d.first == KEYSTONE_HORIZONTAL) {
                            d.second
                        } else {
                            LilypadKeystoneMath.horizontalPctOf(effective.perspective, base.height)
                        }
                        effective.copy(
                            perspective = LilypadKeystoneMath.quadFor(
                                verticalPct = vertical,
                                horizontalPct = horizontal,
                                width = base.width,
                                height = base.height,
                            ),
                        )
                    } else {
                        effective
                    }
                }
                // The intensity drag previews live, like the adjustment sliders.
                d.first == "lut-intensity" -> {
                    val selection = effective.lut
                    if (selection != null) {
                        effective.copy(lut = selection.copy(intensity = d.second))
                    } else {
                        effective
                    }
                }
                else -> effective
            }
        }
        // The HSL band drags preview live too --- the chassis draft merged,
        // one commit on release (key shape "hsl-<field>@<band>").
        val hslSlider = d?.takeIf { it.first.startsWith("hsl-") }
            ?.let { (key, value) ->
                val parts = key.removePrefix("hsl-").split("@", limit = 2)
                if (parts.size == 2) Triple(parts[0], parts[1], value) else null
            }
        if (hslSlider != null) {
            val bands = effective.hslBands
            val current = HSL_BANDS.firstOrNull { it.first == hslSlider.second }?.second(bands)
            if (current != null) {
                val updated = when (hslSlider.first) {
                    "hue" -> current.copy(hue = hslSlider.third)
                    "sat" -> current.copy(saturation = hslSlider.third)
                    else -> current.copy(luminance = hslSlider.third)
                }
                effective = effective.copy(
                    hslBands = bands.withBand(hslSlider.second, updated),
                )
            }
        }
        // The text LIVE state: per-layer slider drafts ("text-size@<id>",
        // "text-opacity@<id>", "text-rotation@<id>"), the TextField draft
        // and the position drag all apply over the committed layers; each
        // commits exactly once.
        val textSlider = d?.takeIf { it.first.startsWith("text-") }
            ?.let { (key, value) ->
                val parts = key.removePrefix("text-").split("@", limit = 2)
                if (parts.size == 2) Triple(parts[0], parts[1], value) else null
            }
        val td = textDraft
        val pd = posDraft
        if ((textSlider != null || td != null || pd != null) && effective.textLayers != null) {
            effective = effective.copy(
                textLayers = TextLayers(
                    effective.textLayers!!.layers.map { layer ->
                        var l = layer
                        if (textSlider != null && textSlider.second == layer.id) {
                            l = when (textSlider.first) {
                                "size" -> l.copy(sizePct = textSlider.third)
                                "opacity" -> l.copy(opacity = textSlider.third)
                                else -> l.copy(rotationDeg = textSlider.third)
                            }
                        }
                        if (td != null && td.first == layer.id) {
                            l = l.copy(text = td.second.take(TextLayer.MAX_TEXT_LENGTH))
                        }
                        if (pd != null && pd.first == layer.id) {
                            l = l.copy(centerX = pd.second, centerY = pd.third)
                        }
                        l
                    },
                ),
            )
        }
        // The sticker LIVE state: per-layer slider drafts ("sticker-size@<id>",
        // "sticker-opacity@<id>", "sticker-rotation@<id>") and the position
        // drag over the committed layers; each commits exactly once.
        val stickerSlider = d?.takeIf { it.first.startsWith("sticker-") }
            ?.let { (key, value) ->
                val parts = key.removePrefix("sticker-").split("@", limit = 2)
                if (parts.size == 2) Triple(parts[0], parts[1], value) else null
            }
        val spd = stickerPosDraft
        if ((stickerSlider != null || spd != null) && effective.stickerLayers != null) {
            effective = effective.copy(
                stickerLayers = StickerLayers(
                    effective.stickerLayers!!.layers.map { layer ->
                        var l = layer
                        if (stickerSlider != null && stickerSlider.second == layer.id) {
                            l = when (stickerSlider.first) {
                                "size" -> l.copy(sizePct = stickerSlider.third)
                                "opacity" -> l.copy(opacity = stickerSlider.third)
                                else -> l.copy(rotationDeg = stickerSlider.third)
                            }
                        }
                        if (spd != null && spd.first == layer.id) {
                            l = l.copy(centerX = spd.second, centerY = spd.third)
                        }
                        l
                    },
                ),
            )
        }
        // The stroke LIVE state: per-layer slider drafts ("draw-width@<id>",
        // "draw-opacity@<id>", "draw-smoothing@<id>").
        val drawSlider = d?.takeIf { it.first.startsWith("draw-") }
            ?.let { (key, value) ->
                val parts = key.removePrefix("draw-").split("@", limit = 2)
                if (parts.size == 2) Triple(parts[0], parts[1], value) else null
            }
        if (drawSlider != null && effective.drawLayers != null) {
            effective = effective.copy(
                drawLayers = DrawLayers(
                    effective.drawLayers!!.layers.map { layer ->
                        var l = layer
                        if (drawSlider.second == layer.id) {
                            l = when (drawSlider.first) {
                                "width" -> l.copy(widthPct = drawSlider.third)
                                "opacity" -> l.copy(opacity = drawSlider.third)
                                else -> l.copy(smoothingPct = drawSlider.third)
                            }
                        }
                        l
                    },
                ),
            )
        }
        // The in-progress paint gesture: a TRANSIENT draft layer (id "draft",
        // never a session id) under the brush's current settings, riding
        // ABOVE the committed strokes (list order, top last), so the preview
        // shows the stroke exactly as its commit will render it. (The local
        // is `paintPts`: `pd` is already the posDraft binding in this scope.)
        val paintPts = paintDraft
        if (paintPts != null && paintPts.isNotEmpty()) {
            effective = effective.copy(
                drawLayers = DrawLayers(
                    (effective.drawLayers?.layers ?: emptyList()) + DrawLayer(
                        id = "draft",
                        colorArgb = brushColor,
                        widthPct = brushWidth,
                        opacity = brushOpacity,
                        smoothingPct = brushSmoothing,
                        points = paintPts,
                    ),
                ),
            )
        }
        // The region LIVE state: the strength slider draft
        // ("region-strength@<id>") and the in-progress paint gesture as a
        // TRANSIENT draft region --- the pipeline's region stage reads it
        // through the same apply() call, so the effect previews AT PAINT TIME.
        val regionSlider = d?.takeIf { it.first.startsWith("region-strength@") }
            ?.let { (key, value) ->
                val id = key.removePrefix("region-strength@")
                if (id.isNotEmpty()) id to value else null
            }
        if (regionSlider != null && effective.mosaicRegions != null) {
            effective = effective.copy(
                mosaicRegions = RegionLayers(
                    effective.mosaicRegions!!.layers.map { layer ->
                        if (regionSlider.first == layer.id) {
                            layer.copy(strengthPct = regionSlider.second)
                        } else {
                            layer
                        }
                    },
                ),
            )
        }
        val regionPts = regionPaintDraft
        if (regionPts != null && regionPts.size >= 3) {
            effective = effective.copy(
                mosaicRegions = RegionLayers(
                    (effective.mosaicRegions?.layers ?: emptyList()) + RegionLayer(
                        id = "draft",
                        kind = regionKind,
                        strengthPct = regionStrength,
                        points = regionPts,
                    ),
                ),
            )
        }
        // The frame width's live draft (a single selection, no per-layer id).
        if (d != null && d.first == "frame-width" && effective.frame != null) {
            effective = effective.copy(frame = effective.frame!!.copy(widthPct = d.second))
        }
        // The AI single-selection sliders' live drafts (one selection per
        // family, no per-layer id in the key; each commits once on release).
        if (d != null && d.first == "sky-opacity" && effective.sky != null) {
            effective = effective.copy(sky = effective.sky!!.copy(opacityPct = d.second.coerceIn(0, 100)))
        }
        if (d != null && d.first == "bokeh-strength" && effective.bokeh != null) {
            effective = effective.copy(bokeh = effective.bokeh!!.copy(blurStrengthPct = d.second.coerceIn(1, 100)))
        }
        if (d != null && d.first == "bokeh-focal" && effective.bokeh != null) {
            effective = effective.copy(bokeh = effective.bokeh!!.copy(focalDepthNorm = d.second.coerceIn(0, 100) / 100.0))
        }
        if (d != null && effective.retouch != null && d.first.startsWith("retouch-")) {
            val sel = effective.retouch!!
            effective = effective.copy(
                retouch = when (d.first) {
                    "retouch-smoothing" -> sel.copy(smoothingPct = d.second.coerceIn(0, 100))
                    "retouch-eyes" -> sel.copy(eyeBrightenPct = d.second.coerceIn(0, 100))
                    else -> sel.copy(faceSlimPct = d.second.coerceIn(0, 100))
                },
            )
        }
        // The curve LIVE state: the deck's point gestures draft the whole
        // ToneCurves; ONE commit on release.
        curveDraft?.let { effective = effective.copy(toneCurves = it) }
        // The corner drag's LIVE state: the overlay's clamped ticks draft
        // the whole quad; ONE commit on release.
        cornerDraft?.let { effective = effective.copy(perspective = it) }
        return effective
    }

    fun commit(recipe: EditRecipe) {
        uiState = engine.commit(recipe)
        historyTick++
        // The ADR-0011 promise: every committed change persists the sidecar.
        try {
            activity.sidecarStore.save(activity.request.sourceUri, recipe)
        } catch (_: RuntimeException) {
            // Best-effort persistence; the session stays in memory.
        }
    }

    // ---- Text helpers (read the LIVE delegated states, so calls from
    // long-lived closures never commit against a stale recipe) ----

    /** Commits an edit to ONE layer, by id, over the CURRENT recipe. */
    fun commitLayerById(id: String, edit: (TextLayer) -> TextLayer) {
        val current = uiState.recipe
        val tl = current.textLayers ?: return
        val idx = tl.layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        commit(
            current.copy(
                textLayers = TextLayers(
                    tl.layers.mapIndexed { i, l -> if (i == idx) edit(l) else l },
                ),
            ),
        )
    }

    /** Commits an edit to the SELECTED layer (no-op when nothing is selected). */
    fun commitSelectedLayer(edit: (TextLayer) -> TextLayer) {
        selectedTextLayerId?.let { commitLayerById(it, edit) }
    }

    /** Sweeps the TextField's pending draft into one snapshot (IME Done, target switch, add/remove, export). */
    fun commitTextDraftIfPending() {
        val d = textDraft ?: return
        textDraft = null
        commitLayerById(d.first) { it.copy(text = d.second.take(TextLayer.MAX_TEXT_LENGTH)) }
    }

    /** Sweeps the position drag's live draft into one snapshot (drag end/cancel, export). */
    fun commitPosDraftIfPending() {
        val d = posDraft ?: return
        posDraft = null
        commitLayerById(d.first) { it.copy(centerX = d.second, centerY = d.third) }
    }

    // ---- Sticker helpers (the text helpers' conventions) ----

    /** Commits an edit to ONE sticker layer, by id, over the CURRENT recipe. */
    fun commitStickerLayerById(id: String, edit: (StickerLayer) -> StickerLayer) {
        val current = uiState.recipe
        val sl = current.stickerLayers ?: return
        val idx = sl.layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        commit(
            current.copy(
                stickerLayers = StickerLayers(
                    sl.layers.mapIndexed { i, l -> if (i == idx) edit(l) else l },
                ),
            ),
        )
    }

    /** Commits an edit to the SELECTED sticker layer (no-op when nothing is selected). */
    fun commitSelectedStickerLayer(edit: (StickerLayer) -> StickerLayer) {
        selectedStickerLayerId?.let { commitStickerLayerById(it, edit) }
    }

    /** Sweeps the sticker position drag's live draft into one snapshot (drag end/cancel, export). */
    fun commitStickerPosDraftIfPending() {
        val d = stickerPosDraft ?: return
        stickerPosDraft = null
        commitStickerLayerById(d.first) { it.copy(centerX = d.second, centerY = d.third) }
    }

    // ---- Drawing helpers ----

    /** Commits an edit to ONE stroke layer, by id, over the CURRENT recipe. */
    fun commitDrawLayerById(id: String, edit: (DrawLayer) -> DrawLayer) {
        val current = uiState.recipe
        val dl = current.drawLayers ?: return
        val idx = dl.layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        commit(
            current.copy(
                drawLayers = DrawLayers(
                    dl.layers.mapIndexed { i, l -> if (i == idx) edit(l) else l },
                ),
            ),
        )
    }

    /** Commits an edit to the SELECTED stroke layer (no-op when nothing is selected). */
    fun commitSelectedDrawLayer(edit: (DrawLayer) -> DrawLayer) {
        selectedDrawLayerId?.let { commitDrawLayerById(it, edit) }
    }

    /**
     * Sweeps the in-progress paint gesture into one snapshot: thin the live
     * point list to the model cap, then commit ONE layer with the brush's
     * current settings (called on drag end/cancel, mode exit, undo/redo,
     * export). Does NOT select the new stroke: the user keeps painting.
     */
    fun commitPaintDraftIfPending() {
        val pd = paintDraft ?: return
        paintDraft = null
        if (pd.isEmpty()) return
        val current = uiState.recipe
        val existing = current.drawLayers?.layers ?: emptyList()
        val newLayer = DrawLayer(
            id = DrawLayer.newId(existing.map { it.id }),
            colorArgb = brushColor,
            widthPct = brushWidth,
            opacity = brushOpacity,
            smoothingPct = brushSmoothing,
            points = DrawLayer.thin(pd, DrawLayer.MAX_POINTS),
        )
        commit(current.copy(drawLayers = DrawLayers(existing + newLayer)))
    }

    // ---- Region helpers ----

    /** Commits an edit to ONE region layer, by id, over the CURRENT recipe. */
    fun commitRegionLayerById(id: String, edit: (RegionLayer) -> RegionLayer) {
        val current = uiState.recipe
        val rl = current.mosaicRegions ?: return
        val idx = rl.layers.indexOfFirst { it.id == id }
        if (idx < 0) return
        commit(
            current.copy(
                mosaicRegions = RegionLayers(
                    rl.layers.mapIndexed { i, l -> if (i == idx) edit(l) else l },
                ),
            ),
        )
    }

    /** Commits an edit to the SELECTED region (no-op when nothing is selected). */
    fun commitSelectedRegionLayer(edit: (RegionLayer) -> RegionLayer) {
        selectedRegionLayerId?.let { commitRegionLayerById(it, edit) }
    }

    /**
     * Sweeps the in-progress region gesture into one snapshot: thin the live
     * point list, then commit ONE region IF it still has >= 3 points (a
     * region needs a painted AREA). Does NOT select the new region: the user
     * keeps painting.
     */
    fun commitRegionPaintDraftIfPending() {
        val pending = regionPaintDraft ?: return
        regionPaintDraft = null
        if (pending.size < 3) return
        val current = uiState.recipe
        val existing = current.mosaicRegions?.layers ?: emptyList()
        val newLayer = RegionLayer(
            id = RegionLayer.newId(existing.map { it.id }),
            kind = regionKind,
            strengthPct = regionStrength,
            points = RegionLayer.thin(pending, RegionLayer.MAX_POINTS),
        )
        commit(current.copy(mosaicRegions = RegionLayers(existing + newLayer)))
    }

    // ---- AI helpers (inference runs at commit time) ----

    /** The dedicated degradation message for a boundary kind + feature. */
    fun aiDegradationRes(kind: EditAiDegradation, modelsMissingRes: Int): Int = when (kind) {
        EditAiDegradation.MODELS_MISSING_FROM_BUILD -> modelsMissingRes
        EditAiDegradation.RUNTIME_BROKEN -> R.string.editor_ai_runtime_broken
        EditAiDegradation.NO_SKY_FOUND -> R.string.editor_ai_no_sky
        EditAiDegradation.CANCELLED -> R.string.editor_ai_cancelled
    }

    /**
     * The inference seam's progress + cancellation plumbing: the fraction
     * feeds the UI line, the cancel flag is polled inside the model runs ---
     * a cancel answers CANCELLED, never an error.
     */
    val aiProgress = object : AiProgress {
        override fun report(fraction: Int) {
            aiProgressPct = fraction.coerceIn(0, 100)
        }

        override fun isCancelled(): Boolean = aiCancelRequested
    }

    /**
     * The WORKING GRID the models see: the preview's own fully-graded pixels
     * (the same resolver and pipeline the render uses, so the inference
     * input can never disagree with the preview). Artifact keys embed this
     * grid's hash, so a changed adjustment derives a NEW key (append-only).
     */
    suspend fun aiWorkingGrid(): Triple<IntArray, Int, Int>? = withContext(Dispatchers.Default) {
        val base = previewBase ?: return@withContext null
        val effective = effectiveRecipe()
        val display = effective.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0))
        val look = effective.lut?.let { looks[it.lutId] }
        val w = base.width
        val h = base.height
        val px = IntArray(w * h)
        base.getPixels(px, 0, w, 0, 0, w, h)
        val aiNow = activity.resolveAiStageInputs(display)
        val pipelined = EditPipeline.apply(px, w, h, display, look, aiNow)
        Triple(pipelined.pixels, pipelined.width, pipelined.height)
    }

    // The composable's own scope, declared BEFORE its first use below
    // (Kotlin locals are visible only below their declaration point).
    val uiScope = rememberCoroutineScope()

    /**
     * The on-demand model gate: answers true only when every listed model is on the
     * device, downloading the missing ones after an explicit user confirmation.
     * Refusal, cancellation, or a failed download answers false and leaves the
     * notice behind.
     */
    suspend fun ensureAiModels(models: List<EditAiManifest.ModelFile>): Boolean {
        val missing = models.filter { !activity.aiModelStore.present(it) }
        if (missing.isEmpty()) return true
        aiDownloadTotalMb =
            ((missing.sumOf { it.bytes } + (1024L * 1024L) - 1) / (1024L * 1024L)).toInt()
        aiDownloadGate.value = CompletableDeferred()
        aiDownloadPrompt = missing
        val confirmed = aiDownloadGate.value.await()
        aiDownloadPrompt = null
        if (!confirmed) return false
        aiCancelRequested = false
        aiDownloadPct = 0
        val totalBytes = missing.sumOf { it.bytes }.coerceAtLeast(1L)
        var received = 0L
        return try {
            for (model in missing) {
                activity.aiModelStore.download(model) { chunk ->
                    if (aiCancelRequested) throw IOException("the model download was cancelled")
                    received += chunk
                    aiDownloadPct = ((received * 100L) / totalBytes).toInt().coerceIn(0, 99)
                }
            }
            aiDownloadPct = -1
            missing.all { activity.aiModelStore.present(it) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            aiDownloadPct = -1
            aiNotice =
                if (aiCancelRequested) R.string.editor_ai_cancelled else R.string.editor_ai_download_failed
            aiCancelRequested = false
            false
        }
    }

    /**
     * ONE runner for the four AI commits: off the UI thread, cancellable
     * through [aiProgress], one snapshot when the artifact lands. Refuses
     * while a commit is already running or an export is in flight.
     */
    fun runAiCommit(work: suspend () -> Unit) {
        if (aiBusy || exporting) return
        aiBusy = true
        aiCancelRequested = false
        aiNotice = null
        aiProgressPct = 0
        uiScope.launch {
            try {
                work()
            } finally {
                aiBusy = false
            }
        }
    }

    /**
     * Sweeps the in-progress erase gesture into its COMMIT: run segmentation
     * + inpainting once, persist the mask + fill pair as ONE artifact, and
     * commit the erase layer referencing it. A tap under three samples drops.
     * The undo snapshot lands when the artifact does --- inference IS the
     * commit.
     */
    fun commitErasePaintDraftIfPending() {
        val pending = erasePaintDraft ?: return
        erasePaintDraft = null
        if (pending.size < 3 || aiBusy || exporting) return
        val thinned = AiEraseLayer.thin(pending, AiEraseLayer.MAX_POINTS)
        runAiCommit {
            if (!ensureAiModels(listOf(EditAiManifest.ERASE_SEGMENTATION, EditAiManifest.ERASE_INPAINT))) {
                return@runAiCommit
            }
            val grid = aiWorkingGrid()
            if (grid == null) {
                aiNotice = R.string.editor_ai_runtime_broken
                return@runAiCommit
            }
            val (working, width, height) = grid
            val seed = thinned.joinToString(",") { "${it.x},${it.y}" }
            when (val result = EditAiCommit.erase(activity.aiInference, working, width, height, thinned, aiProgress)) {
                is AiRun.Unavailable ->
                    aiNotice = aiDegradationRes(result.kind, R.string.editor_ai_erase_models_missing)
                is AiRun.Ok -> {
                    val (mask, patch) = result.value
                    if (patch == null) {
                        aiNotice = R.string.editor_ai_erase_too_small
                        return@runAiCommit
                    }
                    val current = uiState.recipe
                    val existing = current.aiErase?.layers ?: emptyList()
                    val id = AiEraseLayer.newId(existing.map { it.id })
                    val key = activity.aiStore.keyFor(
                        id,
                        EditAiCommit.pixelInputs(working, seed),
                        EditAiModels.IDENTITY_SALIENCY,
                    )
                    if (!activity.aiStore.writeEraseArtifact(key, mask, patch)) {
                        aiNotice = R.string.editor_ai_store_failed
                        return@runAiCommit
                    }
                    commit(
                        current.copy(
                            aiErase = AiEraseLayers(
                                existing + AiEraseLayer(id = id, points = thinned, artifactRef = key),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** Runs the sky detection against the CURRENT working grid and persists the mask artifact. */
    suspend fun detectSkyMaskKey(): String? {
        if (!ensureAiModels(listOf(EditAiManifest.ERASE_SEGMENTATION))) return null
        val grid = aiWorkingGrid() ?: run {
            aiNotice = R.string.editor_ai_runtime_broken
            return null
        }
        val (working, width, height) = grid
        return when (val result = EditAiCommit.sky(activity.aiInference, working, width, height, aiProgress)) {
            is AiRun.Unavailable -> {
                aiNotice = aiDegradationRes(result.kind, R.string.editor_ai_sky_models_missing)
                null
            }
            is AiRun.Ok -> {
                val key = activity.aiStore.keyFor(
                    "sky",
                    EditAiCommit.pixelInputs(working, ""),
                    EditAiModels.IDENTITY_SALIENCY,
                )
                if (activity.aiStore.writeSkyMask(key, result.value)) key else null
            }
        }
    }

    /** Ensures a resolvable sky mask exists: the committed one, else a fresh detection. */
    suspend fun ensureSkyMaskKey(): String? {
        val committedKey = committed.sky?.maskRef?.takeIf { it.isNotEmpty() }
        if (committedKey != null && activity.aiStore.readSkyMask(committedKey) != null) {
            return committedKey
        }
        return detectSkyMaskKey()
    }

    /**
     * Selects a sky: instant when a resolvable mask artifact exists, else
     * the detection runs first; [freshDetection] forces the re-apply (a
     * changed adjustment derives a NEW artifact key; the previous one stays
     * for the undo stack).
     */
    fun selectSky(skyId: String, freshDetection: Boolean = false) {
        if (aiBusy || exporting) return
        fun commitSky(maskKey: String) {
            commit(
                committed.copy(
                    sky = SkySelection(
                        maskRef = maskKey,
                        skyId = skyId,
                        opacityPct = committed.sky?.opacityPct ?: SkySelection.DEFAULT_OPACITY_PCT,
                    ),
                ),
            )
        }
        if (!freshDetection) {
            val existingKey = committed.sky?.maskRef?.takeIf { it.isNotEmpty() }
            if (existingKey != null && activity.aiStore.readSkyMask(existingKey) != null) {
                commitSky(existingKey)
                return
            }
        }
        runAiCommit {
            val key = if (freshDetection) detectSkyMaskKey() else ensureSkyMaskKey()
            if (key != null) commitSky(key)
        }
    }

    /** Runs the depth estimation once and commits the bokeh selection over the artifact. */
    fun runBokehEstimate() {
        runAiCommit {
            if (!ensureAiModels(listOf(EditAiManifest.BOKEH_DEPTH))) return@runAiCommit
            val grid = aiWorkingGrid()
            if (grid == null) {
                aiNotice = R.string.editor_ai_runtime_broken
                return@runAiCommit
            }
            val (working, width, height) = grid
            when (val result = EditAiCommit.depth(activity.aiInference, working, width, height, aiProgress)) {
                is AiRun.Unavailable ->
                    aiNotice = aiDegradationRes(result.kind, R.string.editor_ai_bokeh_models_missing)
                is AiRun.Ok -> {
                    val key = activity.aiStore.keyFor(
                        "bokeh",
                        EditAiCommit.pixelInputs(working, ""),
                        EditAiModels.IDENTITY_DEPTH,
                    )
                    if (!activity.aiStore.writeDepth(key, result.value)) {
                        aiNotice = R.string.editor_ai_store_failed
                        return@runAiCommit
                    }
                    commit(
                        committed.copy(
                            bokeh = BokehSelection(
                                depthRef = key,
                                blurStrengthPct = committed.bokeh?.blurStrengthPct
                                    ?: BokehSelection.DEFAULT_BLUR_STRENGTH_PCT,
                                focalDepthNorm = committed.bokeh?.focalDepthNorm
                                    ?: BokehSelection.DEFAULT_FOCAL_DEPTH_NORM,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /** Runs the face localization once (only ever under consent GRANTED) and commits the selection. */
    fun runRetouchDetect() {
        if (retouchConsent != RetouchConsent.GRANTED) return
        runAiCommit {
            if (!ensureAiModels(listOf(EditAiManifest.RETOUCH_FACES))) return@runAiCommit
            val grid = aiWorkingGrid()
            if (grid == null) {
                aiNotice = R.string.editor_ai_runtime_broken
                return@runAiCommit
            }
            val (working, width, height) = grid
            when (val result = EditAiCommit.faces(activity.aiInference, working, width, height, aiProgress)) {
                is AiRun.Unavailable ->
                    aiNotice = aiDegradationRes(result.kind, R.string.editor_ai_retouch_models_missing)
                is AiRun.Ok -> {
                    val key = activity.aiStore.keyFor(
                        "retouch",
                        EditAiCommit.pixelInputs(working, ""),
                        EditAiModels.IDENTITY_FACES,
                    )
                    if (!activity.aiStore.writeBoxes(key, result.value)) {
                        aiNotice = R.string.editor_ai_store_failed
                        return@runAiCommit
                    }
                    commit(
                        committed.copy(
                            retouch = RetouchSelection(
                                boxesRef = key,
                                smoothingPct = committed.retouch?.smoothingPct ?: 0,
                                eyeBrightenPct = committed.retouch?.eyeBrightenPct ?: 0,
                                faceSlimPct = committed.retouch?.faceSlimPct ?: 0,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Sweeps the curve drag's live draft into one snapshot (drag end/cancel,
     * tool exit, export --- a draft is never silently lost). A curve back at
     * the diagonal leaves NO residue; a gesture that changed nothing pushes
     * NO history entry.
     */
    fun commitCurveDraftIfPending() {
        val cd = curveDraft ?: return
        curveDraft = null
        val normalized = LilypadCurveDeckMath.normalize(cd)
        val current = uiState.recipe
        if (normalized != current.toneCurves) {
            commit(current.copy(toneCurves = normalized))
        }
    }

    /**
     * Sweeps the corner drag's live draft into one snapshot (the safety net
     * for tool exit, export and undo/redo; the overlay's commit covers drag
     * end). A quad back at the identity leaves NO residue; a no-op gesture
     * pushes NO history entry.
     */
    fun commitCornerDraftIfPending() {
        val qd = cornerDraft ?: return
        cornerDraft = null
        val current = uiState.recipe
        if (qd != current.perspective) {
            commit(current.copy(perspective = qd))
        }
    }

    /**
     * The ONE sweep point for every pending draft across the families: each
     * pending draft commits as its own snapshot.
     */
    fun sweepPendingDrafts() {
        commitTextDraftIfPending()
        commitPosDraftIfPending()
        commitStickerPosDraftIfPending()
        commitPaintDraftIfPending()
        commitRegionPaintDraftIfPending()
        commitCurveDraftIfPending()
        commitCornerDraftIfPending()
        // The erase gesture sweeps into its own ASYNC commit; it refuses
        // while another AI commit or an export runs.
        commitErasePaintDraftIfPending()
    }


    // First composition: decode the bounded preview off the main thread.
    LaunchedEffect(Unit) {
        val decoded = withContext(Dispatchers.IO) {
            activity.decodeBitmap(activity.request.sourceUri, PREVIEW_MAX_DIMENSION)
        }
        if (decoded == null) {
            activity.finishWithError(
                EditContract.ERROR_UNSUPPORTED,
                "cannot decode ${activity.request.sourceUri}",
            )
        } else {
            previewBase = decoded
        }
    }

    // Live preview: re-run the ordered pipeline (without the crop --- it
    // draws as the overlay rect) whenever the effective recipe or the loaded
    // look set changes. The scene layers composite over the same crop region
    // the export will produce, through the SAME compositors --- stickers
    // below strokes below text, the frame last.
    LaunchedEffect(
        previewBase, committed, draft, textDraft, posDraft, stickerPosDraft,
        paintDraft, brushColor, brushWidth, brushOpacity, brushSmoothing,
        regionPaintDraft, regionKind, regionStrength,
        looks, importedStickers, importedFrames, importedSkies, retouchConsent,
        zebraActive, curveDraft,
    ) {
        val base = previewBase ?: return@LaunchedEffect
        val effective = effectiveRecipe()
        val displayRecipe = effective.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0))
        val look = effective.lut?.let { looks[it.lutId] }
        previewZebra = null
        previewImage = withContext(Dispatchers.Default) {
            val width = base.width
            val height = base.height
            val plan = EditGeometryPlan.plan(effective, width, height)
            previewCropRect = plan.cropPixelRect
            if (displayRecipe.isIdentity) {
                // The identity short-circuit still owes the Adjust panel its
                // histogram (and the zebra its clipping state): one read of
                // the untouched decode.
                val raw = IntArray(width * height)
                base.getPixels(raw, 0, width, 0, 0, width, height)
                previewHistogram = EditorHistogram.fromPixels(raw)
                if (zebraActive) {
                    previewZebra = buildZebraOverlayImage(raw, width, height)
                }
                return@withContext base.asImageBitmap()
            }
            val pixels = IntArray(width * height)
            base.getPixels(pixels, 0, width, 0, 0, width, height)
            // The AI stages replay over the SAME resolved artifact bundle the
            // export uses --- no model ever runs during a render.
            val ai = activity.resolveAiStageInputs(displayRecipe)
            val pipelined = EditPipeline.apply(pixels, width, height, displayRecipe, look, ai)
            // The tone-honest histogram source: the pipeline output BEFORE
            // the scene layers composite (a sticker or caption is not a tonal
            // state), the same uncropped grid the AI seam and zebra read.
            previewHistogram = EditorHistogram.fromPixels(pipelined.pixels)
            if (zebraActive) {
                previewZebra = buildZebraOverlayImage(pipelined.pixels, pipelined.width, pipelined.height)
            }
            val out = Bitmap.createBitmap(pipelined.width, pipelined.height, Bitmap.Config.ARGB_8888)
            out.setPixels(pipelined.pixels, 0, pipelined.width, 0, 0, pipelined.width, pipelined.height)
            val crop = plan.cropPixelRect
            // Stickers are scene objects: they composite BELOW the text pass.
            val stickerSpec = effective.stickerLayers
            if (stickerSpec != null && stickerSpec.layers.isNotEmpty()) {
                StickerCompositor.draw(
                    out,
                    stickerSpec.layers,
                    { stickerId -> activity.stickerResolver(stickerId) },
                    crop.x0.toFloat(),
                    crop.y0.toFloat(),
                    crop.width.toFloat(),
                    crop.height.toFloat(),
                )
            }
            // Strokes sit between the stickers and the text.
            val drawSpec = effective.drawLayers
            if (drawSpec != null && drawSpec.layers.isNotEmpty()) {
                DrawCompositor.draw(
                    out,
                    drawSpec.layers,
                    crop.x0.toFloat(),
                    crop.y0.toFloat(),
                    crop.width.toFloat(),
                    crop.height.toFloat(),
                )
            }
            val textSpec = effective.textLayers
            if (textSpec != null && textSpec.layers.isNotEmpty()) {
                TextCompositor.draw(
                    out,
                    textSpec.layers,
                    { fontId -> activity.fontFor(fontId) },
                    crop.x0.toFloat(),
                    crop.y0.toFloat(),
                    crop.width.toFloat(),
                    crop.height.toFloat(),
                )
            }
            // The frame LAST: the mounting covers the edges of everything.
            effective.frame?.let { frameSel ->
                FrameCompositor.draw(
                    out,
                    frameSel,
                    { frameId ->
                        importedFrames[frameId]?.let { FrameCompositor.Resolved.Bitmap(it) }
                    },
                    crop.x0.toFloat(),
                    crop.y0.toFloat(),
                    crop.width.toFloat(),
                    crop.height.toFloat(),
                )
            }
            out.asImageBitmap()
        }
    }

    // The merged look registry: bundled first, imported appended (disjoint
    // keys by the store's collision rule); re-run per version counter bump.
    LaunchedEffect(looksVersion) {
        looks = withContext(Dispatchers.IO) {
            activity.bundledLooks + activity.importedLutStore.loadAll()
        }
    }

    // The imported-sticker registry: re-decoded per import/removal; preview
    // and export resolve through the SAME shared instances.
    LaunchedEffect(stickersVersion) {
        importedStickers = withContext(Dispatchers.IO) {
            activity.reloadImportedStickers()
        }
    }

    // The imported-frame registry (the sticker machinery, frame-shaped).
    LaunchedEffect(framesVersion) {
        importedFrames = withContext(Dispatchers.IO) {
            activity.reloadImportedFrames()
        }
    }

    // The imported-sky registry (the frame machinery, sky-shaped).
    LaunchedEffect(skiesVersion) {
        importedSkies = withContext(Dispatchers.IO) {
            activity.reloadImportedSkies()
        }
    }

    // The retouch consent state, loaded once per composition from the
    // per-install store (default-off).
    LaunchedEffect(Unit) {
        retouchConsent = withContext(Dispatchers.IO) {
            activity.retouchConsentStore.load()
        }
    }

    // Pre-Q: the legacy write grant is requested at save time only.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingExportAfterPermission) {
            pendingExportAfterPermission = false
            exporting = true
        } else if (!granted) {
            pendingExportAfterPermission = false
            activity.finishWithError(
                EditContract.ERROR_PERMISSIONS,
                "the storage write grant was declined",
            )
        }
    }

    // The look import flow: SAF-pick a .cube, read it off-main, hand the
    // BYTES to the store (parse-before-write); an import selects the new
    // look at the current intensity, a rejection surfaces the reason.
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                importError = null
                val result = withContext(Dispatchers.IO) {
                    try {
                        val bytes = activity.contentResolver
                            .openInputStream(uri)
                            ?.use { it.readBytes() }
                        if (bytes == null) {
                            null
                        } else {
                            activity.importedLutStore.import(
                                bytes,
                                activity.sourceDisplayName(uri.toString()),
                            )
                        }
                    } catch (_: IOException) {
                        null
                    }
                }
                when (result) {
                    is ImportedLutStore.ImportResult.Imported -> {
                        looksVersion++
                        commit(
                            committed.copy(
                                lut = LutSelection(
                                    lutId = result.lut.id,
                                    intensity = committed.lut?.intensity ?: 100,
                                ),
                            ),
                        )
                    }
                    is ImportedLutStore.ImportResult.Rejected -> importError = result.reason
                    null -> importError = "the file could not be read"
                }
            }
        }
    }

    // The sticker import flow: SAF-pick a PNG/WebP, read it off-main, hand
    // the BYTES to the store (magic-before-write); the imported chip
    // appears, a rejection surfaces the reason.
    val stickerImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                stickerImportError = null
                val result = withContext(Dispatchers.IO) {
                    try {
                        val bytes = activity.contentResolver
                            .openInputStream(uri)
                            ?.use { it.readBytes() }
                        if (bytes == null) {
                            null
                        } else {
                            activity.importedStickerStore.import(
                                bytes,
                                activity.sourceDisplayName(uri.toString()),
                            )
                        }
                    } catch (_: IOException) {
                        null
                    }
                }
                when (result) {
                    is ImportedStickerStore.ImportResult.Imported -> stickersVersion++
                    is ImportedStickerStore.ImportResult.Rejected -> stickerImportError = result.reason
                    null -> stickerImportError = "the file could not be read"
                }
            }
        }
    }

    // The frame import flow (the sticker import flow, frame-shaped).
    val frameImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                frameImportError = null
                val result = withContext(Dispatchers.IO) {
                    try {
                        val bytes = activity.contentResolver
                            .openInputStream(uri)
                            ?.use { it.readBytes() }
                        if (bytes == null) {
                            null
                        } else {
                            activity.importedFrameStore.import(
                                bytes,
                                activity.sourceDisplayName(uri.toString()),
                            )
                        }
                    } catch (_: IOException) {
                        null
                    }
                }
                when (result) {
                    is ImportedFrameStore.ImportResult.Imported -> framesVersion++
                    is ImportedFrameStore.ImportResult.Rejected -> frameImportError = result.reason
                    null -> frameImportError = "the file could not be read"
                }
            }
        }
    }

    // The sky import flow (the frame import flow, sky-shaped).
    val skyImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            uiScope.launch {
                skyImportError = null
                val result = withContext(Dispatchers.IO) {
                    try {
                        val bytes = activity.contentResolver
                            .openInputStream(uri)
                            ?.use { it.readBytes() }
                        if (bytes == null) {
                            null
                        } else {
                            activity.importedSkyStore.import(
                                bytes,
                                activity.sourceDisplayName(uri.toString()),
                            )
                        }
                    } catch (_: IOException) {
                        null
                    }
                }
                when (result) {
                    is ImportedSkyStore.ImportResult.Imported -> skiesVersion++
                    is ImportedSkyStore.ImportResult.Rejected -> skyImportError = result.reason
                    null -> skyImportError = "the file could not be read"
                }
            }
        }
    }

    LaunchedEffect(exporting) {
        if (exporting) {
            val choice = exportChoice ?: ExportChoice()
            activity.startExport(
                ExportRequest(
                    recipe = committed,
                    format = choice.format,
                    quality = choice.quality,
                    // The per-export choice, resolved against the request's
                    // privacy floor (contract 4.4: never strip less than asked).
                    metadataPolicy = ExportPolicyChoice.effectivePolicy(
                        policy,
                        choice.metadataPolicy,
                    ),
                    // The grid the keystone quad was authored on (the export
                    // retargets the warp to the full decode via
                    // LilypadKeystoneMath.scaledForExport).
                    previewWidth = previewBase?.width ?: 0,
                    previewHeight = previewBase?.height ?: 0,
                ),
            )
        }
    }

    fun requestSave() {
        if (exporting) return
        // Sweep any pending drafts first --- the export reads the COMMITTED
        // recipe, and a draft must never be silently lost.
        sweepPendingDrafts()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingExportAfterPermission = true
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            exporting = true
        }
    }

    // ---- The chassis navigation ----
    // Entering a tool snapshots the recipe (the tool Reset's boundary);
    // leaving one sweeps any pending gesture draft and drops the paint
    // modes --- painting belongs to its tool's screen.
    fun enterTool(target: PhotoScreen) {
        if (target == PhotoScreen.Main || target == screen) return
        sweepPendingDrafts()
        if (screen != PhotoScreen.Main) {
            drawMode = false
            regionMode = false
            eraseMode = false
        }
        entryRecipe = committed
        screen = target
    }

    fun exitToMain() {
        sweepPendingDrafts()
        drawMode = false
        regionMode = false
        eraseMode = false
        screen = PhotoScreen.Main
    }

    // System back: a tool screen pops to the main chassis; the main screen
    // keeps the default back = the activity's cancel path.
    BackHandler(enabled = screen != PhotoScreen.Main) { exitToMain() }

    // Layout contract: the header is the first child of the fillMaxSize
    // root, and this root-level systemBarsPadding is the only inset payment
    // in the edit app (pinned by EditHeaderPinningTest).
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(LilypadTokens.POND.toInt()))
            // Pay the system-bar insets once, at the root, so the chrome
            // never sits under the status/navigation bars (edge-to-edge is
            // enforced at targetSdk 36+).
            .systemBarsPadding(),
    ) {
        // ---- Top bar ----
        // The MAIN screen keeps the photo editor's own chrome (close /
        // title / settings / undo / redo / history / save); a tool screen
        // swaps in the tool-header shape.
        if (screen == PhotoScreen.Main) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { activity.finishCanceled() }) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.editor_close),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Text(
                    text = stringResource(R.string.editor_app_name),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.titleMedium,
                    // The title is the heading anchor (a11y).
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The settings entry: a one-row sheet (the retouch kill-switch
                // lives there, ADR-0010 section 5).
                val settingsLabel = stringResource(R.string.editor_settings)
                IconButton(
                    onClick = { settingsOpen = !settingsOpen },
                    enabled = !exporting,
                    modifier = Modifier.semantics { contentDescription = settingsLabel },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                val undoLabel = stringResource(R.string.editor_undo)
                val redoLabel = stringResource(R.string.editor_redo)
                // State-dependent a11y: the label announces what the tap does.
                val historyA11y = stringResource(
                    if (historyOpen) {
                        R.string.editor_r68_history_hide
                    } else {
                        R.string.editor_r68_history_show
                    },
                )
                val undoEnabled = uiState.canUndo && !exporting
                val redoEnabled = uiState.canRedo && !exporting
                IconButton(
                    onClick = {
                        sweepPendingDrafts()
                        uiState = engine.undo()
                        historyTick++
                    },
                    enabled = undoEnabled,
                    modifier = Modifier.semantics { contentDescription = undoLabel },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pt_undo),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground
                            .copy(alpha = if (undoEnabled) 1f else 0.38f),
                    )
                }
                IconButton(
                    onClick = {
                        sweepPendingDrafts()
                        uiState = engine.redo()
                        historyTick++
                    },
                    enabled = redoEnabled,
                    modifier = Modifier.semantics { contentDescription = redoLabel },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pt_redo),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onBackground
                            .copy(alpha = if (redoEnabled) 1f else 0.38f),
                    )
                }
                // The history strip's header entry: Main screen only, behind
                // the LilypadFlags revert seam.
                if (LilypadFlags.historyStripEnabled) {
                    IconButton(
                        onClick = { historyOpen = !historyOpen },
                        enabled = !exporting,
                        modifier = Modifier.semantics { contentDescription = historyA11y },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pt_history),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Button(
                        onClick = {
                            exportChoice = if (exportChoice == null) ExportChoice() else null
                        },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .height(40.dp),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(
                                if (exportChoice == null) {
                                    R.string.editor_save_copy
                                } else {
                                    R.string.editor_cancel_export
                                },
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        } else {
            PhotoToolHeader(
                title = stringResource(screen.labelRes),
                backA11y = stringResource(R.string.editor_v2_back),
                confirmA11y = stringResource(R.string.editor_v2_confirm),
                onBack = { exitToMain() },
                onConfirm = { exitToMain() },
                // The session Reset, in the header row: one undoable commit
                // against the entry snapshot.
                resetA11y = stringResource(R.string.editor_v2_reset),
                onReset = { entryRecipe?.let { commit(it) } },
                resetEnabled = !exporting && entryRecipe != null && committed != entryRecipe,
                // The copy-paste edits pair, riding the Adjust tool. Copy
                // lifts the committed grade into the session clipboard; paste
                // lands it as ONE undoable commit (disabled when empty or
                // during export).
                copyA11y = if (screen == PhotoScreen.Adjust) {
                    stringResource(R.string.editor_copy_edits)
                } else {
                    null
                },
                onCopy = { copiedGrade = committed },
                copyEnabled = !exporting && screen == PhotoScreen.Adjust &&
                    EditTransfer.hasGrade(committed),
                pasteA11y = if (screen == PhotoScreen.Adjust) {
                    stringResource(R.string.editor_paste_edits)
                } else {
                    null
                },
                onPaste = {
                    copiedGrade?.let { source ->
                        commit(EditTransfer.pasteGrade(source, committed))
                    }
                },
                pasteEnabled = !exporting && screen == PhotoScreen.Adjust && copiedGrade != null,
            )
        }

        // The box carries the canvas gestures: drag near the SELECTED text
        // or sticker layer's center to reposition (live-drafted, one commit
        // on release; the sticker wins --- one selection across the
        // families). With a paint mode ON it PAINTS instead (Draw, Region,
        // Erase --- mutually exclusive, enforced at the toggles). Keyed on
        // the selections and modes; every value read is a LIVE delegated
        // state. The press-and-hold compare shows the untouched decode under
        // the finger, gated to the Main chassis with no paint mode and no
        // selection (those gestures own the canvas).
        val compareEligible = screen == PhotoScreen.Main && !drawMode && !regionMode && !eraseMode &&
            selectedTextLayerId == null && selectedStickerLayerId == null && !exporting
        var showOriginal by remember { mutableStateOf(false) }
        // The compare renders the SAME compose type as the pipeline preview:
        // previewBase wrapped once per decode (a view, not a copy).
        val originalImage = remember(previewBase) { previewBase?.asImageBitmap() }

        // The MAIN canvas zooms: pinch 1x (fit) to 4x, pan while zoomed,
        // double-tap to fit (math in CanvasZoomMath, JVM-tested). This pose
        // is session-local UI and RESETS on every tool entry --- the tool
        // canvases own their own gestures and unzoomed coordinate space.
        var canvasScale by remember { mutableFloatStateOf(1f) }
        var canvasOffset by remember { mutableStateOf(Offset.Zero) }
        var canvasSize by remember { mutableStateOf(IntSize.Zero) }
        fun resetCanvasZoom() {
            canvasScale = 1f
            canvasOffset = Offset.Zero
        }
        LaunchedEffect(screen) {
            if (screen != PhotoScreen.Main) resetCanvasZoom()
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The zoomed preview must never paint over the chrome ---
                // the canvas clips its own bounds (visible only past 1x zoom).
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .pointerInput(
                    selectedTextLayerId, selectedStickerLayerId,
                    selectedDrawLayerId, selectedRegionLayerId,
                    drawMode, regionMode, eraseMode,
                ) {
                    val grabRadiusPx = 48.dp.toPx()

                    // Screen position -> output-canvas fractions (the same
                    // FIT mapping the crop overlay draws).
                    fun mapToFraction(position: Offset): Pair<Double, Double>? {
                        val image = previewImage ?: return null
                        val rect = previewCropRect ?: return null
                        if (image.width < 1 || image.height < 1 ||
                            rect.width < 1 || rect.height < 1
                        ) return null
                        val scale = minOf(
                            size.width.toFloat() / image.width,
                            size.height.toFloat() / image.height,
                        )
                        if (scale <= 0f) return null
                        val offX = (size.width - image.width * scale) / 2f
                        val offY = (size.height - image.height * scale) / 2f
                        val imgX = (position.x - offX) / scale
                        val imgY = (position.y - offY) / scale
                        val fx = ((imgX - rect.x0) / rect.width).coerceIn(0f, 1f).toDouble()
                        val fy = ((imgY - rect.y0) / rect.height).coerceIn(0f, 1f).toDouble()
                        return fx to fy
                    }

                    // The selected TEXT layer's center in screen coordinates.
                    fun textLayerCenterScreen(): Offset? {
                        val image = previewImage ?: return null
                        val rect = previewCropRect ?: return null
                        val layerId = selectedTextLayerId ?: return null
                        val layer = uiState.recipe.textLayers?.layers
                            ?.firstOrNull { it.id == layerId } ?: return null
                        if (image.width < 1 || image.height < 1 ||
                            rect.width < 1 || rect.height < 1
                        ) return null
                        val scale = minOf(
                            size.width.toFloat() / image.width,
                            size.height.toFloat() / image.height,
                        )
                        val offX = (size.width - image.width * scale) / 2f
                        val offY = (size.height - image.height * scale) / 2f
                        val cxImg = (rect.x0 + layer.centerX * rect.width).toFloat()
                        val cyImg = (rect.y0 + layer.centerY * rect.height).toFloat()
                        return Offset(offX + cxImg * scale, offY + cyImg * scale)
                    }

                    // The selected STICKER layer's center in screen coordinates.
                    fun stickerLayerCenterScreen(): Offset? {
                        val image = previewImage ?: return null
                        val rect = previewCropRect ?: return null
                        val layerId = selectedStickerLayerId ?: return null
                        val layer = uiState.recipe.stickerLayers?.layers
                            ?.firstOrNull { it.id == layerId } ?: return null
                        if (image.width < 1 || image.height < 1 ||
                            rect.width < 1 || rect.height < 1
                        ) return null
                        val scale = minOf(
                            size.width.toFloat() / image.width,
                            size.height.toFloat() / image.height,
                        )
                        val offX = (size.width - image.width * scale) / 2f
                        val offY = (size.height - image.height * scale) / 2f
                        val cxImg = (rect.x0 + layer.centerX * rect.width).toFloat()
                        val cyImg = (rect.y0 + layer.centerY * rect.height).toFloat()
                        return Offset(offX + cxImg * scale, offY + cyImg * scale)
                    }

                    // Seeds the draft for whichever family owns the selection
                    // (the sticker wins --- one selection across the families).
                    fun seedDraftAt(fx: Double, fy: Double) {
                        val stickerId = selectedStickerLayerId
                        if (stickerId != null) {
                            stickerPosDraft = Triple(stickerId, fx, fy)
                        } else {
                            selectedTextLayerId?.let { id -> posDraft = Triple(id, fx, fy) }
                        }
                    }

                    var dragging = false
                    var painting = false
                    var paintingRegion = false
                    var paintingErase = false
                    detectDragGestures(
                        onDragStart = { start ->
                            painting = drawMode || regionMode || eraseMode
                            paintingRegion = painting && regionMode
                            paintingErase = painting && eraseMode
                            if (painting) {
                                mapToFraction(start)?.let { (fx, fy) ->
                                    if (paintingRegion) {
                                        regionPaintDraft = listOf(RegionLayer.Point(fx, fy))
                                    } else if (paintingErase) {
                                        erasePaintDraft = listOf(AiEraseLayer.Point(fx, fy))
                                    } else {
                                        paintDraft = listOf(DrawLayer.Point(fx, fy))
                                    }
                                }
                            } else {
                                val center = stickerLayerCenterScreen() ?: textLayerCenterScreen()
                                dragging = center != null &&
                                    (start - center).getDistance() <= grabRadiusPx
                                if (dragging) {
                                    mapToFraction(start)?.let { (fx, fy) -> seedDraftAt(fx, fy) }
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            if (painting) {
                                mapToFraction(change.position)?.let { (fx, fy) ->
                                    if (paintingRegion) {
                                        regionPaintDraft =
                                            (regionPaintDraft ?: emptyList()) + RegionLayer.Point(fx, fy)
                                    } else if (paintingErase) {
                                        erasePaintDraft =
                                            (erasePaintDraft ?: emptyList()) + AiEraseLayer.Point(fx, fy)
                                    } else {
                                        paintDraft = (paintDraft ?: emptyList()) + DrawLayer.Point(fx, fy)
                                    }
                                }
                                change.consume()
                            } else if (dragging) {
                                mapToFraction(change.position)?.let { (fx, fy) ->
                                    seedDraftAt(fx, fy)
                                }
                                change.consume()
                            }
                        },
                        onDragEnd = {
                            if (paintingRegion) commitRegionPaintDraftIfPending()
                            if (paintingErase) commitErasePaintDraftIfPending()
                            if (painting && !paintingRegion && !paintingErase) commitPaintDraftIfPending()
                            if (dragging) sweepPendingDrafts()
                            dragging = false
                            painting = false
                            paintingRegion = false
                            paintingErase = false
                        },
                        onDragCancel = {
                            if (paintingRegion) commitRegionPaintDraftIfPending()
                            if (paintingErase) commitErasePaintDraftIfPending()
                            if (painting && !paintingRegion && !paintingErase) commitPaintDraftIfPending()
                            if (dragging) sweepPendingDrafts()
                            dragging = false
                            painting = false
                            paintingRegion = false
                            paintingErase = false
                        },
                    )
                }
                // The compare gesture: a second, independent pointer observer
                // alongside the drag handler (drag owns movement; the compare
                // only watches press/release).
                .pointerInput(compareEligible) {
                    if (compareEligible) {
                        detectTapGestures(
                            onPress = {
                                showOriginal = true
                                try {
                                    awaitRelease()
                                } finally {
                                    showOriginal = false
                                }
                            },
                        )
                    }
                }
                // The two-pointer transform (pinch zoom + pan): a CUSTOM loop,
                // not detectTransformGestures, because the generic detector
                // consumes one-finger drags too, which would cancel the
                // compare press-hold above. This loop only reacts --- and only
                // consumes --- while TWO or more pointers are down.
                .pointerInput(screen == PhotoScreen.Main, exporting) {
                    if (screen == PhotoScreen.Main && !exporting) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val zoomChange = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid()
                                    if (zoomChange != 1f || pan != Offset.Zero) {
                                        val out = CanvasZoomMath.step(
                                            scale = canvasScale,
                                            offsetX = canvasOffset.x,
                                            offsetY = canvasOffset.y,
                                            zoomChange = zoomChange,
                                            panX = pan.x,
                                            panY = pan.y,
                                            centroidX = centroid.x,
                                            centroidY = centroid.y,
                                            widthPx = canvasSize.width.toFloat(),
                                            heightPx = canvasSize.height.toFloat(),
                                        )
                                        canvasScale = out[0]
                                        canvasOffset = Offset(out[1], out[2])
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                }
                // Double-tap-to-fit, AFTER the compare's detector so the
                // compare observes every event unconsumed first.
                .pointerInput(screen == PhotoScreen.Main, exporting) {
                    if (screen == PhotoScreen.Main && !exporting) {
                        detectTapGestures(
                            onDoubleTap = { resetCanvasZoom() },
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val image = if (showOriginal) originalImage else previewImage
            if (image != null) {
                // The pose layer: the preview AND its overlays transform
                // TOGETHER, so the zoomed canvas stays self-consistent. The
                // compare chrome below sits OUTSIDE this layer.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = canvasScale
                            scaleY = canvasScale
                            translationX = canvasOffset.x
                            translationY = canvasOffset.y
                        },
                ) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.editor_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                // The clipping zebra: over the preview (never over the
                // compare's original), under the crop dim; no pointer input,
                // so the canvas's gestures stay live.
                if (!showOriginal) {
                    ZebraOverlay(zebra = previewZebra)
                }
                val crop = previewCropRect
                if (crop != null && (crop.x0 > 0 || crop.y0 > 0 ||
                        crop.x1 < image.width || crop.y1 < image.height)
                ) {
                    // The crop overlay: dim everything outside the rect,
                    // drawn in image pixel coordinates scaled to the FIT box
                    // (approximate but honest: the rect's ratio is the point).
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scale = minOf(
                            size.width / image.width,
                            size.height / image.height,
                        )
                        val drawW = image.width * scale
                        val drawH = image.height * scale
                        val offX = (size.width - drawW) / 2f
                        val offY = (size.height - drawH) / 2f
                        val left = offX + crop.x0 * scale
                        val top = offY + crop.y0 * scale
                        val w = (crop.x1 - crop.x0) * scale
                        val h = (crop.y1 - crop.y0) * scale
                        drawRect(
                            color = Color.Black.copy(alpha = 0.55f),
                            topLeft = Offset.Zero,
                            size = size,
                        )
                        drawRect(
                            color = Color.White,
                            topLeft = Offset(left, top),
                            size = Size(w, h),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
                // The perspective corner overlay: the displaced quad's
                // outline and four drag handles over the PRE-WARP source fit
                // box, on the Geometry tool screen only, behind the revert
                // seam. INSIDE the pose layer but OUTSIDE the crop dim
                // (canvas content): the handles stay reachable at any crop,
                // on the source grid the keystone rows commit on.
                if (LilypadFlags.cornerDragEnabled && screen == PhotoScreen.Crop && !showOriginal) {
                    val base = previewBase
                    CornerOverlay(
                        quad = cornerDraft ?: effectiveRecipe().perspective,
                        sourceWidth = base?.width ?: 0,
                        sourceHeight = base?.height ?: 0,
                        exporting = exporting,
                        onDraftChange = { cornerDraft = it },
                        onCommit = { released ->
                            cornerDraft = null
                            val current = uiState.recipe
                            if (released != current.perspective) {
                                commit(current.copy(perspective = released))
                            }
                        },
                    )
                }
                } // pose layer (the graphicsLayer Box)
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            // The compare badge: while the original shows, a pill names what
            // the user is looking at (the canvas never silently swaps content).
            if (showOriginal) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.editor_r56_compare_badge),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            // The floating compare chip: the press-and-hold gesture made
            // discoverable. Holding it drives the SAME showOriginal flag the
            // canvas gesture does; hidden exactly when the gesture is
            // (compareEligible), so the affordance and the gesture agree.
            if (compareEligible) {
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .heightIn(min = 40.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                showOriginal = true
                                waitForUpOrCancellation()
                                showOriginal = false
                            }
                        },
                ) {
                    Text(
                        text = stringResource(R.string.editor_r63b_hold_to_compare),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // ---- The history strip ----
        // Bottom-anchored on the Main chassis only, behind the revert seam.
        // The label model recomputes on the history revision (see the state
        // block: uiState alone cannot drive it); the jump sweeps any pending
        // gesture first, then walks the ring's own moves.
        if (LilypadFlags.historyStripEnabled && historyOpen && screen == PhotoScreen.Main) {
            val historyChanges = remember(historyTick) {
                List(engine.depth) { i ->
                    val previous = engine.entryAt(i - 1)
                    val next = engine.entryAt(i)
                    if (i == 0 || previous == null || next == null) {
                        null // the launch entry has no predecessor
                    } else {
                        LilypadHistoryNames.changeBetween(previous, next)
                    }
                }
            }
            HistoryStrip(
                changes = historyChanges,
                currentIndex = engine.pointerIndex,
                looks = looks,
                enabled = !exporting,
                onJump = { index ->
                    sweepPendingDrafts()
                    uiState = engine.jumpTo(index)
                    historyTick++
                },
            )
        }

        // ---- The settings sheet ----
        // The retouch kill-switch (ADR-0010 section 5): a permanent disable
        // hides the entry AND the stage refuses even with a restored sidecar
        // (the layer stays verbatim). A real bottom sheet, so the canvas no
        // longer shrinks under the rows while it is open.
        if (settingsOpen && !exporting) {
            ModalBottomSheet(
                onDismissRequest = { settingsOpen = false },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.editor_settings),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.editor_settings_retouch_title),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (retouchConsent == RetouchConsent.PERMANENTLY_DISABLED) {
                        Text(
                            text = stringResource(R.string.editor_settings_retouch_disabled),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        TextButton(
                            onClick = {
                                val next = RetouchConsentMachine.onPermanentlyDisable(retouchConsent)
                                if (activity.retouchConsentStore.save(next)) {
                                    retouchConsent = next
                                }
                            },
                            enabled = !exporting && !aiBusy,
                        ) {
                            Text(stringResource(R.string.editor_settings_retouch_disable))
                        }
                    }
                }
            }
        }

        // ---- The export sheet (format + quality) ----
        // A real bottom sheet: the rows overlay the canvas instead of
        // squeezing it; same content, same policy floor, same requestSave().
        val choice = exportChoice
        if (choice != null && !exporting) {
            ModalBottomSheet(
                onDismissRequest = { exportChoice = null },
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        ExportFormat.entries.forEach { format ->
                            FilterChip(
                                selected = choice.format == format,
                                onClick = { exportChoice = choice.copy(format = format) },
                                enabled = !exporting,
                                label = { Text(format.name) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    if (choice.format != ExportFormat.PNG) {
                        Text(
                            text = stringResource(R.string.editor_quality, choice.quality),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val qualityName = stringResource(R.string.editor_a11y_quality)
                        val qualityState = stringResource(R.string.editor_quality, choice.quality)
                        LabeledSlider(
                            name = qualityName,
                            label = qualityState,
                            valueText = choice.quality.toString(),
                            value = choice.quality.toFloat(),
                            valueRange = 1f..100f,
                            enabled = !exporting,
                            onChange = { exportChoice = choice.copy(quality = it) },
                            resetValue = ExportPlan.DEFAULT_QUALITY,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.editor_png_lossless),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    // The per-export metadata-policy choice: the launch
                    // request's policy is the privacy floor --- options that
                    // would strip LESS are disabled (contract 4.4).
                    Text(
                        text = stringResource(R.string.editor_section_metadata),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        MetadataPolicy.entries.forEach { option ->
                            FilterChip(
                                selected = (choice.metadataPolicy ?: policy) == option,
                                onClick = { exportChoice = choice.copy(metadataPolicy = option) },
                                enabled = !exporting &&
                                    !ExportPolicyChoice.isBlockedByFloor(policy, option),
                                label = { Text(policyLabel(option)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    if (policy != MetadataPolicy.PRESERVE) {
                        Text(
                            text = stringResource(
                                R.string.editor_metadata_floor,
                                policyLabel(policy),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = stringResource(R.string.editor_metadata_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Button(onClick = { requestSave() }, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.editor_save_copy))
                    }
                }
            }
        }

        // ---- The AI job machinery ----
        // A RUNNING job stays visible and cancellable from EVERY screen.
        // Every AI commit runs ONCE, off the UI thread; the models never run
        // during a render or an export. Compact on-device models, bounded
        // results (ADR-0010).
        if (aiBusy && aiDownloadPct < 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.editor_ai_progress, aiProgressPct),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                TextButton(
                    onClick = { aiCancelRequested = true },
                    enabled = !exporting,
                ) {
                    Text(stringResource(R.string.editor_ai_cancel))
                }
            }
        }
        if (aiDownloadPct >= 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.editor_ai_download_progress, aiDownloadPct),
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                TextButton(
                    onClick = { aiCancelRequested = true },
                    enabled = !exporting,
                ) {
                    Text(stringResource(R.string.editor_ai_cancel))
                }
            }
        }
        aiDownloadPrompt?.let {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.editor_ai_download_text, aiDownloadTotalMb),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Row {
                    TextButton(onClick = { aiDownloadGate.value.complete(true) }) {
                        Text(stringResource(R.string.editor_ai_download_yes))
                    }
                    TextButton(onClick = { aiDownloadGate.value.complete(false) }) {
                        Text(stringResource(R.string.editor_ai_no))
                    }
                }
            }
        }
        aiNotice?.let { noticeRes ->
            Text(
                text = stringResource(noticeRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // ---- Controls: the Lilypad Deck (the three-state sheet) ----
        // The panels ride the Deck --- Collapsed (the 64dp peek), Half (the
        // 45% working surface), Full (78%) --- with the sheet's own scroll
        // and spring motion; LilypadFlags.decksEnabled=false restores the
        // fixed 300dp window. The panels are untouched re-hosts: the
        // commit-on-release, draft and cross-family-exclusivity contracts
        // hold. ONE preview above, never recomposed away.
        var deckState by remember { mutableStateOf(LilypadDeckState.Half) }
        // The working default per screen: tools open on Half; the Main
        // canvas collapses to the peek. A screen change also CLOSES the
        // curve deck --- the Full state's host belongs to the Adjust board
        // that opened it.
        LaunchedEffect(screen) {
            deckState = if (screen == PhotoScreen.Main) {
                LilypadDeckState.Collapsed
            } else {
                LilypadDeckState.Half
            }
            if (curveDeckOpen) curveDeckOpen = false
        }
        /**
         * The curve deck's open/close pair: opening sweeps any pending curve
         * gesture, expands the deck to Full and swaps in the curve editor;
         * closing sweeps and restores the tool board. The Full state IS the
         * curve deck's host --- nothing else references it.
         */
        fun setCurveDeckOpen(open: Boolean) {
            if (open == curveDeckOpen) return
            commitCurveDraftIfPending()
            curveDeckOpen = open
            deckState = if (open) LilypadDeckState.Full else LilypadDeckState.Half
        }
        val panels: @Composable ColumnScope.() -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // The curve deck: the Full state's reserved content. While
                // open on the Adjust board it REPLACES the board (the scope
                // row's Curve chip is the entry, the deck's Done the exit);
                // every other screen keeps the tool panels.
                if (LilypadFlags.curveDeckEnabled && curveDeckOpen && screen == PhotoScreen.Adjust) {
                    CurveDeck(
                        committed = committed,
                        curveDraft = curveDraft,
                        onCurveDraftChange = { curveDraft = it },
                        onFinish = { commitCurveDraftIfPending() },
                        channel = curveChannel,
                        onChannelChange = { curveChannel = it },
                        histogram = previewHistogram,
                        exporting = exporting,
                        onCollapse = { setCurveDeckOpen(false) },
                    )
                } else {
                    PhotoPanelHost(
                        tool = screen,
                        committed = committed,
                        draft = draft,
                        onDraftChange = { draft = it },
                        exporting = exporting,
                        previewBase = previewBase,
                        previewHistogram = previewHistogram,
                        commit = { commit(it) },
                        activity = activity,
                        uiScope = uiScope,
                        looks = looks,
                        onLooksVersionChange = { looksVersion++ },
                        importError = importError,
                        importLauncher = importLauncher,
                        sweepPendingDrafts = { sweepPendingDrafts() },
                        commitSelectedLayer = { commitSelectedLayer(it) },
                        commitTextDraftIfPending = { commitTextDraftIfPending() },
                        textDraft = textDraft,
                        onTextDraftChange = { textDraft = it },
                        selectedTextLayerId = selectedTextLayerId,
                        onSelectTextLayerChange = { selectedTextLayerId = it },
                        selectedStickerLayerId = selectedStickerLayerId,
                        onSelectStickerLayerChange = { selectedStickerLayerId = it },
                        selectedRegionLayerId = selectedRegionLayerId,
                        onSelectRegionLayerChange = { selectedRegionLayerId = it },
                        commitSelectedStickerLayer = { commitSelectedStickerLayer(it) },
                        importedStickers = importedStickers,
                        stickerImportError = stickerImportError,
                        stickerImportLauncher = stickerImportLauncher,
                        commitSelectedDrawLayer = { commitSelectedDrawLayer(it) },
                        drawMode = drawMode,
                        onDrawModeChange = { drawMode = it },
                        regionMode = regionMode,
                        onRegionModeChange = { regionMode = it },
                        eraseMode = eraseMode,
                        onEraseModeChange = { eraseMode = it },
                        brushColor = brushColor,
                        onBrushColorChange = { brushColor = it },
                        brushWidth = brushWidth,
                        onBrushWidthChange = { brushWidth = it },
                        brushOpacity = brushOpacity,
                        onBrushOpacityChange = { brushOpacity = it },
                        brushSmoothing = brushSmoothing,
                        onBrushSmoothingChange = { brushSmoothing = it },
                        selectedDrawLayerId = selectedDrawLayerId,
                        onSelectDrawLayerChange = { selectedDrawLayerId = it },
                        commitSelectedRegionLayer = { commitSelectedRegionLayer(it) },
                        regionKind = regionKind,
                        onRegionKindChange = { regionKind = it },
                        regionStrength = regionStrength,
                        onRegionStrengthChange = { regionStrength = it },
                        importedFrames = importedFrames,
                        frameImportError = frameImportError,
                        frameImportLauncher = frameImportLauncher,
                        selectedEraseLayerId = selectedEraseLayerId,
                        onSelectEraseLayerChange = { selectedEraseLayerId = it },
                        aiBusy = aiBusy,
                        importedSkies = importedSkies,
                        skyImportError = skyImportError,
                        skyImportLauncher = skyImportLauncher,
                        retouchConsent = retouchConsent,
                        onRetouchConsentChange = { retouchConsent = it },
                        retouchConsentDialog = retouchConsentDialog,
                        onRetouchConsentDialogChange = { retouchConsentDialog = it },
                        runBokehEstimate = { runBokehEstimate() },
                        runRetouchDetect = { runRetouchDetect() },
                        selectSky = { id, fresh -> selectSky(id, fresh) },
                        selectedBand = selectedBand,
                        onSelectedBandChange = { selectedBand = it },
                        scopeMode = scopeMode,
                        onScopeModeChange = { scopeMode = it },
                        zebraActive = zebraActive,
                        onZebraActiveChange = { zebraActive = it },
                        curveDeckOpen = curveDeckOpen,
                        onCurveDeckOpenChange = { setCurveDeckOpen(it) },
                    )
                }
            }
            // The Main-screen honesty chip: the effective policy copy,
            // tappable to open the export sheet (same one the Save-copy CTA
            // opens).
            if (screen == PhotoScreen.Main) {
                Surface(
                    onClick = {
                        exportChoice = if (exportChoice == null) ExportChoice() else null
                    },
                    enabled = !exporting,
                    shape = RoundedCornerShape(percent = 50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pt_shield),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.editor_policy_line,
                                policyLabel(
                                    ExportPolicyChoice.effectivePolicy(
                                        policy,
                                        exportChoice?.metadataPolicy,
                                    ),
                                ),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        if (LilypadFlags.decksEnabled) {
            // The Lilypad Deck hosts the panels; a handle move OFF Full
            // closes the curve deck with it --- the Full state and the curve
            // surface are one thing.
            LilypadDeck(
                state = deckState,
                onStateChange = { next ->
                    deckState = next
                    if (next != LilypadDeckState.Full && curveDeckOpen) {
                        commitCurveDraftIfPending()
                        curveDeckOpen = false
                    }
                },
            ) {
                panels()
            }
        } else {
            // The fixed 300dp scroll-column fallback (the flag's off rail).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                panels()
            }
        }

        // Keep the tool rail docked below the canvas on every screen so
        // switching tools never requires returning to the main canvas.
        PhotoTabBar(
            current = screen,
            enabled = !exporting,
            onSelect = { enterTool(it) },
        )
    }
}

// PhotoToolHeader and PhotoTabBar live in `PhotoChrome.kt` (same package, internal).


@Composable
private fun policyLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.PRESERVE -> stringResource(R.string.editor_policy_preserve)
    MetadataPolicy.STRIP_LOCATION -> stringResource(R.string.editor_policy_strip_location)
    MetadataPolicy.STRIP_ALL -> stringResource(R.string.editor_policy_strip_all)
}
