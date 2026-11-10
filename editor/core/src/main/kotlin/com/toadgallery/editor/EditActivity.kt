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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * toadGALLERY Edit --- the contract launch activity (r29 Issue #14 gate;
 * r30 the Phase 6 v1 pipeline). Spec `docs/architecture/editor-contract.md`.
 *
 * Round trip (contract-exact):
 *  1. LAUNCH: the gallery's v1 extras validated through the SHARED
 *     [EditRequestValidator]; the source URI rides the Parcelable extra or
 *     the ClipData item; the read-only grant scopes everything the editor
 *     may touch.
 *  2. RESTORE: the ADR-0011 sidecar for this source (if any) restores the
 *     previous session --- the non-destructive promise survives process
 *     death and cancel.
 *  3. MODIFY: the whole session is ONE [EditRecipe] snapshot stack
 *     ([EditSessionState]); the live preview runs the ordered
 *     [EditPipeline] over a bounded decode; every stage's zero state is
 *     skipped exactly. The sidecar is saved on every commit.
 *  4. EXPORT (Save copy): full-resolution decode -> [EditPipeline] ->
 *     compress (JPEG quality / PNG lossless / WebP; the honest set
 *     encoder supports it, honest JPEG fallback otherwise) -> MediaStore
 *     INSERT of a NEW row (IS_PENDING on Q+, cancelled on failure --- no
 *     partial entry) -> EXIF metadata policy applied exactly ->
 *     sidecar cleared (the edit resolved) -> RESULT_OK with
 *     EXTRA_OUTPUT_URI + the applied policy echoed. The source URI is
 *     read-only by construction: never opened for write, never updated,
 *     never deleted.
 *  5. TERMINAL STATES: RESULT_OK / RESULT_CANCELED (back) / RESULT_ERROR
 *     with the structured codes --- validation failures answer before any
 *     UI exists.
 *
 * Zero network: the module's build-time gate fails if INTERNET ever rides
 * into the merged manifest; the activity opens no sockets itself. The
 * export scope OUTLIVES the composition so a mid-export close still
 * answers a terminal state or cancels its pending row.
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
     * The bundled `.cube` looks (r33), parsed ONCE and shared by the
     * preview and every export --- the same [CubeLut] instances, so the
     * look cannot differ between what the user previews and what the
     * pipeline exports. Lazily evaluated off the main thread by the
     * first reader (the composition's loader or an export); a broken
     * asset is simply absent (the conformance test pins the committed
     * set, so an absence means a broken build, not bad data).
     */
    internal val bundledLooks: Map<String, CubeLut> by lazy {
        BundledLuts.loadAll { path -> assets.open(path) }
    }

    /**
     * The user-imported `.cube` store (r33 extension): SAF-picked looks
     * copied verbatim into app-private storage, ids derived and
     * collision-suffixed so they can never shadow the bundled set.
     */
    internal val importedLutStore: ImportedLutStore by lazy {
        ImportedLutStore(File(filesDir, "imported-luts"))
    }

    /**
     * The user-imported sticker store (r35): SAF-picked PNG/WebP files
     * copied verbatim into app-private storage, ids derived and
     * collision-suffixed so they can never shadow the bundled set (the
     * ImportedLutStore pattern, image-shaped).
     */
    internal val importedStickerStore: ImportedStickerStore by lazy {
        ImportedStickerStore(File(filesDir, "imported-stickers"))
    }

    /**
     * The imported-sticker bitmaps (r35), decoded once per registry load
     * and shared by the preview and every export --- the same instances,
     * so a rendered sticker cannot differ between them (the bundledFonts
     * pattern; a payload that fails to decode is absent, never thrown).
     */
    internal val importedStickerBitmaps: MutableMap<String, Bitmap> =
        java.util.Collections.synchronizedMap(java.util.LinkedHashMap())

    /**
     * Re-decodes every imported sticker (per import/removal; called off
     * the main thread by the composition's loader). Broken files are
     * absent from the result, never thrown.
     */
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
     * The render resolution for a sticker id (the tolerance contract):
     * the bundled vector, else the imported bitmap, else null --- the
     * compositor renders nothing for an unresolvable id, never a crash.
     * One resolver shared by the preview and the export.
     */
    internal fun stickerResolver(id: String): StickerCompositor.Resolved? =
        BundledStickers.shape(id)?.let { StickerCompositor.Resolved.Vector(it) }
            ?: importedStickerBitmaps[id]?.let { StickerCompositor.Resolved.Bitmap(it) }

    /**
     * The user-imported frame store (r38): the SAF-picked PNG/WebP files
     * copied verbatim into app-private storage, ids derived and
     * collision-suffixed so they can never shadow the bundled set (the
     * sticker store's contract, frame-shaped).
     */
    internal val importedFrameStore: ImportedFrameStore by lazy {
        ImportedFrameStore(File(filesDir, "imported-frames"))
    }

    /**
     * The imported-frame bitmaps (r38), decoded once per registry load and
     * shared by the preview and every export (the sticker bitmaps'
     * pattern; a payload that fails to decode is absent, never thrown).
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

    // ---- r41: the Edit v3 AI seam (Phase 10 sub-scope A) ----

    /**
     * The derived-artifact store (r41): `edit-ai/` in the app-private
     * storage, BESIDE the sidecar directory --- the artifacts the four
     * inference commits persist (masks, fills, depth grids, face boxes);
     * every render replays over them, no model ever runs at render time.
     * Swept together with the sidecar when the edit resolves.
     */
    internal val aiStore: EditAiStore by lazy {
        EditAiStore(File(filesDir, "edit-ai"), request.sourceUri)
    }

    /**
     * The user-imported sky store (r41): the ImportedFrameStore contract
     * VERBATIM, sky-shaped --- PNG/WebP magic-before-write, verbatim
     * bytes, the size cap, collision-suffixed ids that can never shadow
     * the bundled parametric set.
     */
    internal val importedSkyStore: ImportedSkyStore by lazy {
        ImportedSkyStore(File(filesDir, "imported-skies"))
    }

    /**
     * The imported-sky bitmaps (r41), decoded once per registry load and
     * shared by the preview and every export (the frame bitmaps' pattern;
     * a payload that fails to decode is absent, never thrown).
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

    /**
     * The inference seam (r41): the ORT facade, NNAPI-first with CPU
     * fallback. Lazy --- the runtime loads when the first AI commit runs,
     * never at launch; a missing/broken runtime degrades the affected
     * feature with its dedicated message, never a crash.
     */
    internal val aiInference: EditAiInference by lazy {
        OrtEditAiInference { path ->
            try {
                assets.open(path)
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    /** The face-retouch consent store (r41, ADR-0010 section 5; default-off). */
    internal val retouchConsentStore: RetouchConsentStore by lazy {
        RetouchConsentStore(File(filesDir, "edit-settings"))
    }

    /**
     * The caller-RESOLVED r41 artifact bundle for a recipe: the store's
     * artifacts for the recipe's keys, the resolved sky source (the
     * bundled parametric code, or an imported bitmap), and the retouch
     * consent gate (the stage REFUSES under consent-off even with a
     * restored sidecar --- the layer stays verbatim, the stage skips).
     * ONE resolver shared by the preview and every export, so they agree
     * by construction. A missing artifact degrades that stage to nothing
     * --- the frame/sticker tolerance contract, never a crash.
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
     * The bundled Typefaces (r34), created ONCE per session and shared by
     * the preview and every export --- the same instances, so a rendered
     * face cannot differ between them. A broken asset is simply absent
     * (BundledFontsTest pins the committed bytes by sha256, so an absence
     * means a broken build, not bad data).
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
     * The render face for a layer fontId (the tolerance contract):
     * the bundled face, else the DEFAULT face, else null --- the platform
     * face in the compositor. Never a crash, never a dropped layer.
     */
    internal fun fontFor(fontId: String): Typeface? =
        bundledFonts[fontId] ?: bundledFonts[BundledFonts.DEFAULT_ID]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // r47 ux fix: targetSdk 36 enforces edge-to-edge on API 35+, so the
        // chrome drew under the system bars AND the status-bar icons took
        // the auto (theme-following) style --- dark icons on the editor's
        // always-black canvas, i.e. invisible in light mode. The dark
        // SystemBarStyle forces light icons; the layout inset follows below
        // (root Column systemBarsPadding).
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
            // r47 ux fix: the family theme (toad-green, fixed dark) instead of
            // the bare MaterialTheme whose baseline purple leaked through
            // every accent role (the maintainer's device screenshot).
            EditorTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
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
     * The device's allocatable bytes (the r31 storage preflight), or null
     * when the platform answer is unavailable --- an unknown value must
     * never block an export (MediaStore enforces regardless).
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

            // 1. The cheap bounds pass: the large-file decode guard refuses
            //    a source the pipeline could not hold anyway (r31) --- the
            //    honest failure is ERROR_UNSUPPORTED before any work.
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

            // 2. The storage preflight: refuse to start an insert the device
            //    cannot hold (advisory --- unknown availability proceeds;
            //    MediaStore still enforces). The error rides ERROR_IO so the
            //    frozen v1 contract surface stays untouched.
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

            // 4. The ordered pipeline (geometry first, then colour/detail).
            //    The r33 look stage resolves through the SAME lazy cache
            //    the preview used --- one parse, one shared instance.
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val look = export.recipe.lut?.let { bundledLooks[it.lutId] }
            // r41: the AI stages replay over the SAME resolved artifact
            // bundle the preview used (one resolver, one store) --- no
            // model ever runs during export; the fill patch is bounded by
            // the session's working resolution, so an erased region on a
            // very large export is softer than its surroundings (the
            // contract's stated honest limitation).
            val ai = resolveAiStageInputs(export.recipe)
            val pipelined = EditPipeline.apply(pixels, width, height, export.recipe, look, ai)
            val adjusted = Bitmap.createBitmap(
                pipelined.width, pipelined.height, Bitmap.Config.ARGB_8888,
            )
            adjusted.setPixels(
                pipelined.pixels, 0, pipelined.width, 0, 0, pipelined.width, pipelined.height,
            )

            // 4.5 The compositing passes AFTER the whole pixel pipeline
            //     (geometry -> adjustments -> LUT -> vignette -> sharpen),
            //     over the FULL output canvas at origin 0 --- the same
            //     compositors the preview uses, so preview ≡ export by
            //     construction (no second renderer). The family order:
            //     stickers (r35, scene objects) BELOW strokes (r36,
            //     annotation) BELOW text (r34, captions on top).
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
            // r38: the frame LAST, the mounting over everything.
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
            // reporting success (a failure here answers OK --- the export
            // itself succeeded; a leftover sidecar self-heals on next load).
            // r41: the derived-artifact store sweeps TOGETHER with the
            // sidecar (append-only until the edit resolves; this is the
            // only delete).
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
     * nothing at all. androidx.exifinterface on a read-write file
     * descriptor (minSdk 26 >= 24, always live).
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
            // androidx.exifinterface exposes NO public tag enumeration (the
            // gallery's r15 EditableExifCodec established the explicit-list
            // precedent), so the copy walks MetadataPolicyApplier's
            // COPYABLE_TAGS and skips the tags the source does not carry.
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

// ---------------------------------------------------------------------------
// Compose UI
// ---------------------------------------------------------------------------

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
 * --- the launch request's policy rides as-is (r31: the request's policy is
 * the privacy floor; the sheet may strip more, never less).
 */
data class ExportChoice(
    val format: ExportFormat = ExportPlan.DEFAULT_FORMAT,
    val quality: Int = ExportPlan.DEFAULT_QUALITY,
    val metadataPolicy: MetadataPolicy? = null,
)

/**
 * The session engine bridging the pure [EditSessionState] stack to Compose:
 * every commit/undo/redo returns a fresh [EditorUiState] the UI observes.
 */
private class EditSessionEngine(initial: EditRecipe) {
    private val stack = EditSessionState(initial)

    val initial: EditorUiState = EditorUiState(stack.current, stack.canUndo, stack.canRedo)

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
}

/**
 * One slider's binding: identity key, current value, next-state builder.
 * The a11y name (r32) is the semantics contentDescription; the label string
 * still carries the live value for the visible text AND the
 * stateDescription.
 */
internal data class SliderSpec(
    val key: String,
    val label: Int,
    val a11yNameRes: Int,
    val get: (EditRecipe) -> Int,
    val set: (EditRecipe, Int) -> EditRecipe,
)

/** The tone + colour + detail sliders (the full r30 adjustment suite minus HSL bands). */
internal val ADJUST_SLIDERS: List<SliderSpec> = listOf(
    SliderSpec("exposure", R.string.editor_exposure, R.string.editor_a11y_exposure, { it.exposure }, { r, v -> r.copy(exposure = v) }),
    SliderSpec("brightness", R.string.editor_brightness, R.string.editor_a11y_brightness, { it.brightness }, { r, v -> r.copy(brightness = v) }),
    SliderSpec("contrast", R.string.editor_contrast, R.string.editor_a11y_contrast, { it.contrast }, { r, v -> r.copy(contrast = v) }),
    SliderSpec("saturation", R.string.editor_saturation, R.string.editor_a11y_saturation, { it.saturation }, { r, v -> r.copy(saturation = v) }),
    SliderSpec("temperature", R.string.editor_temperature, R.string.editor_a11y_temperature, { it.temperature }, { r, v -> r.copy(temperature = v) }),
    SliderSpec("tint", R.string.editor_tint, R.string.editor_a11y_tint, { it.tint }, { r, v -> r.copy(tint = v) }),
    SliderSpec("highlights", R.string.editor_highlights, R.string.editor_a11y_highlights, { it.highlights }, { r, v -> r.copy(highlights = v) }),
    SliderSpec("shadows", R.string.editor_shadows, R.string.editor_a11y_shadows, { it.shadows }, { r, v -> r.copy(shadows = v) }),
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

// The r56 move: the PhotoScreen enum, the tool header and the tab bar now
// live in `PhotoChrome.kt` (composable in isolation --- the edit screenshot
// harness renders them); this file keeps the chassis logic.
//
// r63c: the value-tools session-Reset footer retired --- Reset moved INTO
// the tool header row (PhotoChrome.kt), one chrome shape for all ten tools.

@OptIn(ExperimentalMaterial3Api::class) // FilterChip in the export sheet
@Composable
private fun EditorContent(activity: EditActivity, initialRecipe: EditRecipe) {
    val policy = activity.request.metadataPolicy
    val engine = remember { EditSessionEngine(initialRecipe) }

    var uiState by remember { mutableStateOf(engine.initial) }
    var selectedBand by remember { mutableStateOf(HSL_BANDS.first().first) }
    var exportChoice by remember { mutableStateOf<ExportChoice?>(null) }
    var previewBase by remember { mutableStateOf<Bitmap?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewCropRect by remember { mutableStateOf<PixelRect?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExportAfterPermission by remember { mutableStateOf(false) }
    // The ONE live-drag draft (key -> value); committed on release.
    var draft by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // The parsed look registry (r33): the bundled set MERGED with the
    // user-imported store (disjoint ids by construction). Empty until
    // the IO load lands; the version counter re-runs it after an import
    // or a removal.
    var looks by remember { mutableStateOf<Map<String, CubeLut>>(emptyMap()) }
    var looksVersion by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf<String?>(null) }
    // r34 text state: the selected layer, the TextField's LOCAL draft
    // (per-keystroke commits would flood the undo stack --- the per-commit
    // granularity is the round contract) and the position drag's live
    // draft. All three live-preview through effectiveRecipe() and commit
    // once on done/release/target-switch/export.
    var selectedTextLayerId by remember { mutableStateOf<String?>(null) }
    var textDraft by remember { mutableStateOf<Pair<String, String>?>(null) }
    var posDraft by remember { mutableStateOf<Triple<String, Double, Double>?>(null) }
    // r35 sticker state: the selected layer and the position drag's live
    // draft (the r34 pattern); the imported-bitmap snapshot the preview
    // re-renders on; the import rejection line. ONE selected layer at a
    // time across the families (the round contract: the drag target and
    // the per-layer controls follow the single selection).
    var selectedStickerLayerId by remember { mutableStateOf<String?>(null) }
    var stickerPosDraft by remember { mutableStateOf<Triple<String, Double, Double>?>(null) }
    var importedStickers by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var stickersVersion by remember { mutableStateOf(0) }
    var stickerImportError by remember { mutableStateOf<String?>(null) }
    // r36 drawing state: the paint mode (ON = painting; a selected layer
    // = editing --- the two states are exclusive, and ONE selection at a
    // time across ALL the families), the in-progress gesture's live point
    // list (a transient draft layer under the brush's current settings),
    // and the brush itself (the NEXT stroke's colour/width/opacity/
    // smoothing; plain state, applied at commit --- the per-LAYER edits
    // ride the draft slot like the other families).
    var selectedDrawLayerId by remember { mutableStateOf<String?>(null) }
    var drawMode by remember { mutableStateOf(false) }
    var paintDraft by remember { mutableStateOf<List<DrawLayer.Point>?>(null) }
    var brushColor by remember { mutableStateOf(0xFFFFFFFFL) }
    var brushWidth by remember { mutableStateOf(3) }
    var brushOpacity by remember { mutableStateOf(100) }
    var brushSmoothing by remember { mutableStateOf(50) }
    // r37 region state: the region paint mode (mutually exclusive with
    // Draw mode --- one paint mode at a time, enforced at both toggles),
    // the in-progress gesture's live point list, and the next region's
    // kind + strength (plain state in paint mode; the live-draft pattern
    // for a selected region).
    var selectedRegionLayerId by remember { mutableStateOf<String?>(null) }
    var regionMode by remember { mutableStateOf(false) }
    var regionPaintDraft by remember { mutableStateOf<List<RegionLayer.Point>?>(null) }
    var regionKind by remember { mutableStateOf(RegionKind.MOSAIC) }
    var regionStrength by remember { mutableStateOf(12) }
    // r38 frame state: the imported-bitmap snapshot + the version counter
    // + the import rejection line. The frame selection itself lives in
    // the recipe (a single selection, like the look); the width slider
    // drafts through the generic draft slot (key "frame-width").
    var importedFrames by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var framesVersion by remember { mutableStateOf(0) }
    var frameImportError by remember { mutableStateOf<String?>(null) }
    // r41 AI state: the Erase paint mode (the exclusive duality's THIRD
    // member --- one paint mode at a time across Draw/Region/Erase,
    // enforced at all three toggles) + its live point list; the commit
    // machinery's honest progress (aiBusy + the fraction + the cancel
    // flag the inference seam polls); the dedicated degradation message
    // (a string resource, shown until the next attempt); the imported-sky
    // registry snapshot + its version counter + the import rejection
    // line; the retouch consent state (default-off, ADR-0010 section 5)
    // + the first-use dialog + the settings sheet's open flag.
    var eraseMode by remember { mutableStateOf(false) }
    var erasePaintDraft by remember { mutableStateOf<List<AiEraseLayer.Point>?>(null) }
    var selectedEraseLayerId by remember { mutableStateOf<String?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiProgressPct by remember { mutableStateOf(0) }
    var aiCancelRequested by remember { mutableStateOf(false) }
    var aiNotice by remember { mutableStateOf<Int?>(null) }
    var importedSkies by remember { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var skiesVersion by remember { mutableStateOf(0) }
    var skyImportError by remember { mutableStateOf<String?>(null) }
    var retouchConsent by remember { mutableStateOf(RetouchConsent.NOT_GRANTED) }
    var retouchConsentDialog by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    // r54: the chassis navigation state (the video editor's screen enum,
    // photo-adapted): the main canvas screen vs the ten tool screens.
    var screen by remember { mutableStateOf(PhotoScreen.Main) }
    // r54: the recipe as it was when the CURRENT tool screen was entered
    // --- the tool Reset's honest boundary (reverting a tool session is
    // ONE undoable commit against this snapshot; the r63c header Reset
    // reads it).
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
                // r32: the straighten drag previews live too (it previously
                // waited for release, burning a per-tick re-render for nothing).
                d.first == "straighten" -> effective.copy(straightenDeg = d.second.toDouble())
                // r33: the intensity drag previews live, exactly like the
                // adjustment sliders (the look commits on release).
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
        // r34: the text LIVE state --- per-layer slider drafts (keys
        // "text-size@<id>" / "text-opacity@<id>" / "text-rotation@<id>"),
        // the TextField draft and the position drag all apply over the
        // committed layers; each commits exactly once (the per-commit
        // granularity the round contract pins).
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
        // r35: the sticker LIVE state --- per-layer slider drafts (keys
        // "sticker-size@<id>" / "sticker-opacity@<id>" /
        // "sticker-rotation@<id>") and the position drag, over the
        // committed layers; each commits exactly once (the per-commit
        // granularity the round contract pins).
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
        // r36: the stroke LIVE state, part 1 --- the per-layer slider
        // drafts (keys "draw-width@<id>" / "draw-opacity@<id>" /
        // "draw-smoothing@<id>"), the live-draft pattern verbatim.
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
        // r36, part 2 --- the in-progress paint gesture: a TRANSIENT draft
        // layer (id "draft", never a session id) under the brush's current
        // settings, so the preview shows the stroke exactly as its commit
        // will render it. The draft layer rides ABOVE the committed
        // strokes (list order, top last) --- the live-paint position.
        // (Distinct local name: `pd` is already the r34 posDraft binding
        // in this same scope --- the 41839665527 lesson.)
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
        // r37: the region LIVE state --- the strength slider draft (key
        // "region-strength@<id>") for a selected region, and the
        // in-progress paint gesture as a TRANSIENT draft region (the
        // pipeline's region stage reads it through the same apply() call,
        // so the preview shows the effect AT PAINT TIME).
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
        // r38: the frame width's live draft (the intensity slider's shape:
        // a single selection, no per-layer id in the key).
        if (d != null && d.first == "frame-width" && effective.frame != null) {
            effective = effective.copy(frame = effective.frame!!.copy(widthPct = d.second))
        }
        // r41: the AI single-selection sliders' live drafts (the frame
        // width's shape verbatim --- one selection per family, no per-layer
        // id in the key; each commits once on release).
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
        return effective
    }

    fun commit(recipe: EditRecipe) {
        uiState = engine.commit(recipe)
        // The ADR-0011 promise: every committed change persists the sidecar.
        try {
            activity.sidecarStore.save(activity.request.sourceUri, recipe)
        } catch (_: RuntimeException) {
            // Best-effort persistence; the session stays in memory.
        }
    }

    // ---- r34 text helpers (all read the LIVE delegated states, so calls
    // from long-lived closures --- the drag gesture, chips --- can never
    // commit against a stale recipe) ----

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

    // ---- r35 sticker helpers (all read the LIVE delegated states, so
    // calls from long-lived closures --- the drag gesture, chips --- can
    // never commit against a stale recipe; the r34 conventions) ----

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

    // ---- r36 drawing helpers (the family conventions) ----

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
     * Sweeps the in-progress paint gesture into one snapshot: thin the
     * live point list to the model cap, then commit ONE layer carrying
     * the brush's current settings (drag end/cancel, mode exit, undo/
     * redo, export --- a draft is never silently lost). Does NOT select
     * the new stroke: the user keeps painting.
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

    // ---- r37 region helpers (the family conventions) ----

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
     * Sweeps the in-progress region gesture into one snapshot: thin the
     * live point list, then commit ONE region IF it still has >= 3 points
     * (a region needs a painted AREA; a tap or a two-sample flick drops
     * honestly --- the model refuses less). Does NOT select the new
     * region: the user keeps painting.
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

    // ---- r41 AI helpers (the inference-at-COMMIT machinery) ----

    /** The dedicated degradation message for a boundary kind + feature. */
    fun aiDegradationRes(kind: EditAiDegradation, modelsMissingRes: Int): Int = when (kind) {
        EditAiDegradation.MODELS_MISSING_FROM_BUILD -> modelsMissingRes
        EditAiDegradation.RUNTIME_BROKEN -> R.string.editor_ai_runtime_broken
        EditAiDegradation.NO_SKY_FOUND -> R.string.editor_ai_no_sky
        EditAiDegradation.CANCELLED -> R.string.editor_ai_cancelled
    }

    /**
     * The inference seam's honest progress + cancellation plumbing: the
     * fraction feeds the UI line, the cancel flag is polled inside the
     * model runs --- a cancel answers CANCELLED, never an error.
     */
    val aiProgress = object : AiProgress {
        override fun report(fraction: Int) {
            aiProgressPct = fraction.coerceIn(0, 100)
        }

        override fun isCancelled(): Boolean = aiCancelRequested
    }

    /**
     * The WORKING GRID the models see: the preview's own fully-graded
     * pixels (the pipeline output the user is looking at, at preview
     * resolution) --- the same resolver and pipeline the render uses, so
     * the inference input can never disagree with the preview. The
     * artifact keys embed this grid's hash, so a changed adjustment
     * derives a NEW key (the append-only rule).
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

    // The composable's own scope, declared BEFORE the first use: the AI
    // commit runner below launches on it (the r41 CI catch --- Kotlin
    // locals are visible only below their declaration point).
    val uiScope = rememberCoroutineScope()

    /**
     * ONE runner for the four AI commits: off the UI thread (the coroutine
     * scope is Main; the work blocks hop to Default), cancellable through
     * [aiProgress], one snapshot when the artifact lands. Refuses while a
     * commit is already running or an export is in flight.
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
     * Sweeps the in-progress erase gesture into its COMMIT: thin the live
     * point list, then run the segmentation + inpainting ONCE (off the UI
     * thread, cancellable, honest progress), persist the refined mask +
     * fill pair as ONE artifact, and commit the erase layer referencing
     * it. A tap under three samples drops honestly (the region commit
     * rule). The undo snapshot lands when the artifact does --- inference
     * IS the commit.
     */
    fun commitErasePaintDraftIfPending() {
        val pending = erasePaintDraft ?: return
        erasePaintDraft = null
        if (pending.size < 3 || aiBusy || exporting) return
        val thinned = AiEraseLayer.thin(pending, AiEraseLayer.MAX_POINTS)
        runAiCommit {
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
     * the detection runs first (once, cancellable); [freshDetection]
     * forces the re-apply (a changed adjustment derives a NEW artifact
     * key; the previous one stays for the undo stack).
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
     * The ONE sweep point for every pending draft across the families
     * (r36): each pending draft commits as its own snapshot, exactly as
     * the r34 handlers did inline --- one helper so later rounds extend
     * HERE instead of at every call site.
     */
    fun sweepPendingDrafts() {
        commitTextDraftIfPending()
        commitPosDraftIfPending()
        commitStickerPosDraftIfPending()
        commitPaintDraftIfPending()
        commitRegionPaintDraftIfPending()
        // r41: the erase gesture sweeps into its own ASYNC commit (the
        // inference IS the commit); it refuses while another AI commit or
        // an export runs --- the honest drop, same as a gesture released
        // exactly at an export boundary.
        commitErasePaintDraftIfPending()
    }


    // First composition: decode the bounded preview + parse the bundled
    // looks (both off the main thread; the looks feed the chip row and
    // the preview's filter stage --- the export shares the same cache).
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

    // Live preview: re-run the ordered pipeline (without the crop --- the
    // crop draws as the overlay rect) whenever the effective recipe or
    // the loaded look set changes. The r34 text pass composites over the
    // same CROP REGION the overlay draws (the output canvas the export
    // will produce), through the SAME TextCompositor --- one code path.
    // r35: the sticker pass composites over the same region, BELOW the
    // text pass. r36: the stroke pass sits between them; the effect also
    // re-runs on the brush values (the draft layer reads them live) and
    // on the imported-bitmap registry.
    LaunchedEffect(
        previewBase, committed, draft, textDraft, posDraft, stickerPosDraft,
        paintDraft, brushColor, brushWidth, brushOpacity, brushSmoothing,
        regionPaintDraft, regionKind, regionStrength,
        looks, importedStickers, importedFrames, importedSkies, retouchConsent,
    ) {
        val base = previewBase ?: return@LaunchedEffect
        val effective = effectiveRecipe()
        val displayRecipe = effective.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0))
        val look = effective.lut?.let { looks[it.lutId] }
        previewImage = withContext(Dispatchers.Default) {
            val width = base.width
            val height = base.height
            val plan = EditGeometryPlan.plan(effective, width, height)
            previewCropRect = plan.cropPixelRect
            if (displayRecipe.isIdentity) {
                return@withContext base.asImageBitmap()
            }
            val pixels = IntArray(width * height)
            base.getPixels(pixels, 0, width, 0, 0, width, height)
            // r41: the AI stages replay over the SAME resolved artifact
            // bundle the export uses (one resolver, one store) --- no
            // model ever runs during a render; re-keyed on the imported
            // skies and the consent state so a kill-switch or an import
            // re-renders immediately.
            val ai = activity.resolveAiStageInputs(displayRecipe)
            val pipelined = EditPipeline.apply(pixels, width, height, displayRecipe, look, ai)
            val out = Bitmap.createBitmap(pipelined.width, pipelined.height, Bitmap.Config.ARGB_8888)
            out.setPixels(pipelined.pixels, 0, pipelined.width, 0, 0, pipelined.width, pipelined.height)
            val crop = plan.cropPixelRect
            // r35: stickers are scene objects --- they composite BELOW the
            // text pass, through the same crop-region output canvas.
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
            // r36: strokes sit between the stickers and the text ---
            // annotation paints over the scene objects, captions float
            // above the annotation.
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
            // r38: the frame LAST --- the mounting covers the edges of
            // everything (a caption spilling into the border goes under
            // it, the physical metaphor).
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

    // The merged look registry: bundled first, imported appended (the
    // store's collision rule guarantees disjoint keys). Re-keyed on the
    // version counter so an import/removal re-runs it.
    LaunchedEffect(looksVersion) {
        looks = withContext(Dispatchers.IO) {
            activity.bundledLooks + activity.importedLutStore.loadAll()
        }
    }

    // The imported-sticker registry (r35): re-decoded per import/removal
    // (the version counter re-runs it); the preview and the export
    // resolve through the SAME shared instances (the activity's map).
    LaunchedEffect(stickersVersion) {
        importedStickers = withContext(Dispatchers.IO) {
            activity.reloadImportedStickers()
        }
    }

    // The imported-frame registry (r38): the sticker machinery, frame-shaped.
    LaunchedEffect(framesVersion) {
        importedFrames = withContext(Dispatchers.IO) {
            activity.reloadImportedFrames()
        }
    }

    // The imported-sky registry (r41): the frame machinery, sky-shaped.
    LaunchedEffect(skiesVersion) {
        importedSkies = withContext(Dispatchers.IO) {
            activity.reloadImportedSkies()
        }
    }

    // The retouch consent state (r41): loaded once per composition from
    // the per-install store (default-off; the dialog and the settings
    // kill-switch write through it).
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

    // The r33 import flow: SAF-pick a .cube, read it off-main, hand the
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

    // The r35 sticker import flow: SAF-pick a PNG/WebP, read it off-main,
    // hand the BYTES to the store (magic-before-write). An import lands
    // in the registry (its chip appears; the user taps it to add a
    // layer), a rejection surfaces the reason.
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

    // The r38 frame import flow: the sticker import flow, frame-shaped.
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

    // The r41 sky import flow: the frame import flow, sky-shaped (the
    // ImportedFrameStore contract verbatim through ImportedSkyStore).
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
                ),
            )
        }
    }

    fun requestSave() {
        if (exporting) return
        // r34/r35/r36: sweep any pending drafts into snapshots first ---
        // the export reads the COMMITTED recipe, and a draft must never be
        // silently lost at the moment of export.
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

    // ---- r54: the chassis navigation (the video editor's screen
    // contract, photo-adapted) ----
    // Entering a tool snapshots the recipe (the tool Reset's honest
    // boundary); leaving one sweeps any pending gesture draft and drops
    // the paint modes --- painting belongs to its tool's screen now,
    // where the scroll era kept the modes alive across sections (the one
    // deliberate behavior change; the modes were unreachable from other
    // sections' controls anyway).
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

    // The system-back contract: a tool screen pops to the main chassis
    // (the gallery app's three selection screens set the in-repo
    // precedent); the main screen keeps the default back = the activity's
    // cancel path.
    BackHandler(enabled = screen != PhotoScreen.Main) { exitToMain() }

    // F-71 (the r63a audit's P1, ledger item): on the r62 device screenshot
    // this header rendered ~630px below the top edge with pure black above
    // it, yet the code composes it as the FIRST child of the ONLY root
    // container. The r63a root-cause hunt exonerates this tree, with line
    // evidence: (a) the root fills the window --- fillMaxSize here under the
    // fillMaxSize Surface in onCreate's setContent (EditActivity.kt:465),
    // default Arrangement.Top, no contentAlignment; (b) insets are consumed
    // EXACTLY ONCE --- this systemBarsPadding is the only inset modifier in
    // the whole edit app (no statusBars/displayCutout/ime/safeDrawing
    // padding exists), there is no Scaffold, and no manifest or theme
    // declares fitsSystemWindows/translucent bars/cutout modes that could
    // double-pay at the decor; (c) nothing is composed above the header ---
    // the first child below is the header branch itself (no spacers, no
    // wrapper Box, no early-return layout, the one verticalScroll lives in
    // the export sheet's own window); (d) the unified APK launches THIS
    // activity class (apps/gallery/src/unified/AndroidManifest.xml registers
    // com.toadgallery.editor.EditActivity; the gallery's
    // EditorLaunch resolves exactly it), so there is no alternate entry; and
    // (e) the VIDEO editor (VideoEditActivity: the identical root pattern,
    // same device, same audit) rendered top-anchored --- a window-mode cause
    // would have hit both. Verdict: the offset is a device-side window state
    // (a mid-launch window transition or a compat/windowed placement
    // captured by the screenshot), not producible by this composition tree;
    // EditHeaderPinningTest pins the header as the top-most composed element
    // on the JVM harness (the repro attempt, negative). The layout contract
    // stands: header pinned to the top of a fillMaxSize root, insets paid
    // exactly once.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // r47 ux fix: pay the system-bar insets once, at the root, so
            // the top bar and the control stack never sit under the
            // status/navigation bars (edge-to-edge is enforced at
            // targetSdk 36+); the black background still reaches the bars.
            .systemBarsPadding(),
    ) {
        // ---- Top bar: the r54 chassis header, r63b §4 icon chrome ----
        // The MAIN screen keeps the photo editor's own chrome (close /
        // title / settings / undo / redo / save) --- the video header is a
        // back / title / export pill, but the photo surface's undo-redo
        // and the r41 settings entry are contract chrome that stays one
        // tap from the canvas. A tool screen swaps in the video shape.
        //
        // r63b §4: 56dp row, 8dp horizontal padding; the three r54
        // TextButtons retire into 48dp-touch IconButtons with 24dp glyphs
        // (undo/redo disabled at 38% alpha), the title gains the one-line
        // ellipsis, and the CTA pill holds the §4 shape (40dp tall, 20dp
        // radius, 20dp horizontal padding, labelLarge, maxLines = 1 --- the
        // r63a P3 pin). The icons free the ~140dp that used to wrap P3.
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
                    // r40 a11y: the editor's title is the heading anchor (the
                    // gallery screens' r40 heading convention, editor side).
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    // §4: one line --- longer locales ellipsize, never wrap.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // r41: the editor's FIRST settings entry --- a one-row sheet
                // (the retouch kill-switch lives there; ADR-0010 section 5).
                // §4: the gear icon, not a text button.
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
                val undoEnabled = uiState.canUndo && !exporting
                val redoEnabled = uiState.canRedo && !exporting
                IconButton(
                    onClick = {
                        sweepPendingDrafts()
                        uiState = engine.undo()
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
                            // r63a P3: the CTA pill never wraps --- longer
                            // locales ellipsize instead of breaking the
                            // primary control into two lines.
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // §4: labelLarge (14sp) --- also Button's
                            // default, pinned explicitly against drift.
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
                // r63c: the session Reset, promoted into the header row
                // (the old value-tools footer row retired) --- one chrome
                // shape for all ten tools; same ONE-undoable-commit revert
                // against the entry snapshot, disabled while the snapshot
                // IS the recipe and during the export freeze.
                resetA11y = stringResource(R.string.editor_v2_reset),
                onReset = { entryRecipe?.let { commit(it) } },
                resetEnabled = !exporting && entryRecipe != null && committed != entryRecipe,
            )
        }

        // r34: the box also carries the text-layer DRAG gesture (drag near
        // the SELECTED layer's center to reposition it; live-drafted, one
        // commit on release). r35: the same gesture targets the SELECTED
        // STICKER layer first (one selection across the families). r36:
        // with Draw mode ON, the gesture PAINTS instead. r37: a SECOND
        // paint mode --- Region mode paints the region path (the effect
        // previews through the pipeline stage at paint time); the two
        // paint modes are mutually exclusive, enforced at their toggles.
        // Keyed on the selections AND both modes: the handler restarts
        // when any of them changes, and every value it reads is a LIVE
        // delegated state so long-lived closures never see stale data.
        // r56: the press-and-hold BEFORE/AFTER compare --- the untouched
        // decode (previewBase) under the finger while held, the pipelined
        // preview on release. The original is ALREADY in memory for the
        // render loop, so the compare costs nothing. Gated to the Main
        // chassis with no paint mode and no layer selection (those gestures
        // own the canvas); everywhere else the modifier is inert.
        val compareEligible = screen == PhotoScreen.Main && !drawMode && !regionMode && !eraseMode &&
            selectedTextLayerId == null && selectedStickerLayerId == null && !exporting
        var showOriginal by remember { mutableStateOf(false) }
        // The compare renders the SAME compose type the pipeline preview uses:
        // previewBase is the platform decode, wrapped once per decode (the
        // wrapper is a view of the same pixel buffer, not a copy).
        val originalImage = remember(previewBase) { previewBase?.asImageBitmap() }

        // F-72 (r65, the r63 proposal's deferred canvas craft): the MAIN
        // canvas zooms --- pinch to scale between 1x (fit) and 4x (the
        // proposal's "clamped 1x-4x"), pan while zoomed (two fingers),
        // double-tap to fit. The math lives in CanvasZoomMath (JVM-tested);
        // this state is session-local UI (the compose-side pose the
        // graphicsLayer replays) and RESETS on every tool entry --- the ten
        // tool canvases own their own gestures and their unzoomed coordinate
        // space, exactly Ente's split (the main canvas zooms, sub-editors
        // own their gestures).
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
                // F-72: the zoomed preview must never paint over the chrome
                // (the header above, the tab bar below) --- the canvas clips
                // its own bounds (the image was Fit-fit inside anyway; the
                // clip only becomes visible once the zoom exceeds 1x).
                .clipToBounds()
                .onSizeChanged { canvasSize = it }
                .pointerInput(
                    selectedTextLayerId, selectedStickerLayerId,
                    selectedDrawLayerId, selectedRegionLayerId,
                    drawMode, regionMode, eraseMode,
                ) {
                    val grabRadiusPx = 48.dp.toPx()

                    // Screen position -> output-canvas fractions (the same
                    // FIT mapping the crop overlay draws; the crop rect is
                    // the output canvas the export will produce).
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

                    // Seeds the right draft for whichever family owns the
                    // selection (the sticker wins --- one selection across
                    // the families is the round contract).
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
                // r56: the compare gesture --- a second, INDEPENDENT pointer
                // observer alongside the drag handler above (drag still owns
                // movement; the compare only watches press/release, and only
                // where the drag handler is inert anyway).
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
                // F-72 gesture 1: the two-pointer transform (pinch zoom + pan
                // while zoomed). A CUSTOM loop, not detectTransformGestures,
                // on purpose: the generic detector consumes ONE-finger drags
                // too, which would cancel the compare press-hold above the
                // moment a held finger moved (waitForUpOrCancellation treats
                // a consumed position change as a cancellation). This loop
                // only reacts --- and only consumes --- while TWO or more
                // pointers are down, so the compare hold's single-finger
                // event stream stays byte-identical to the pre-F-72 shape:
                // the zoom can never eat the compare's contract.
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
                // F-72 gesture 2: double-tap-to-fit. Sits AFTER the compare's
                // detector in the chain, so the compare observes every event
                // unconsumed first (its press-hold semantics are untouched);
                // the reset is a no-op at the fit pose, and while the pose is
                // zoomed the brief first-tap compare flash IS the honest
                // original-vs-edited look at the moment of reset.
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
                // F-72: the pose layer --- the preview AND its overlays (the
                // crop dim rect) transform TOGETHER, so the zoomed canvas
                // stays self-consistent. The chrome overlays (the compare
                // chip/badge below) sit OUTSIDE this layer: they are canvas
                // chrome, not canvas content.
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
                val crop = previewCropRect
                if (crop != null && (crop.x0 > 0 || crop.y0 > 0 ||
                        crop.x1 < image.width || crop.y1 < image.height)
                ) {
                    // The crop overlay: dim everything outside the rect.
                    // Drawn in image pixel coordinates scaled to the FIT
                    // box --- approximate but honest (the rect's ratio is
                    // what the user needs to see).
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
                } // F-72 pose layer (the graphicsLayer Box)
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            // r56: the compare badge --- while the original shows, a small
            // pill names what the user is looking at (honesty: the canvas
            // must never silently swap content).
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
            // r63b §4: the floating compare chip --- the r56 press-and-hold
            // gesture made discoverable. Bottom-leading overlay, 8dp from
            // the edges, surfaceVariant @ 72% alpha behind a 1dp
            // outlineVariant border, full-radius pill, 12sp label, 40dp min
            // height. Holding it drives the SAME showOriginal flag the
            // canvas gesture does (press = the untouched decode, release =
            // the pipelined preview); the r56 top badge still names the
            // state while held. Hidden exactly when the gesture is (a paint
            // mode, a layer selection, an export --- compareEligible), so
            // the affordance and the gesture can never disagree.
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

        // ---- The r41 settings sheet (the editor's FIRST, one row) ----
        // The retouch kill-switch lives here (ADR-0010 section 5): a
        // permanent disable hides the entry AND the stage refuses even
        // with a restored sidecar (the layer stays verbatim, the stage
        // skips --- pinned in RetouchConsentTest + AiRenderTest).
        // r56: a real bottom sheet (the gallery ViewerScreen's M3 pattern)
        // --- the sheet renders in its own window, so the canvas no longer
        // shrinks under the rows while it is open. Same content, same
        // consent machine.
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
        // r56: a real bottom sheet (the settings sheet's rationale) --- the
        // format/quality/policy rows overlay the canvas instead of squeezing
        // it; same content, same policy floor, same requestSave().
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

                    // ---- The per-export metadata-policy choice (r31) ----
                    // The launch request's policy is the privacy floor: options
                    // that would strip LESS than the request are disabled (the
                    // editor respects the requested policy exactly, contract 4.4).
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

        // ---- The r41 AI job machinery, hoisted by r54 out of the controls ----
        // A RUNNING job stays visible and cancellable from EVERY screen
        // (the video chassis hoisted its still-export progress block for
        // exactly this reason). Every AI commit runs ONCE, off the UI
        // thread, when the user applies or re-applies a feature; the
        // models never run during a render or an export. The quality note
        // is honest per ADR-0010: compact on-device models, bounded
        // results.
        if (aiBusy) {
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
        aiNotice?.let { noticeRes ->
            Text(
                text = stringResource(noticeRes),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        // ---- Controls: the r54 per-tool panels ----
        // The long-scroll sectioned IA retires: each tool's block now
        // lives behind its tab, re-hosted VERBATIM (the r30-r41 behavior
        // contracts --- commit-on-release, the draft machinery, the
        // cross-family layer exclusivity --- are untouched; only the
        // container changed). The panel shares the canvas above: ONE
        // preview, never recomposed away (the video chassis's rule).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PhotoPanelHost(
                        tool = screen,
                        committed = committed,
                        draft = draft,
                        onDraftChange = { draft = it },
                        exporting = exporting,
                        previewBase = previewBase,
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
                )
            }
            // ---- The always-visible honesty chip (the r54 chassis's Main
            // home: the export decision surface; tool screens don't repeat
            // it) ----
            // r63b §4: the r54 text line becomes a tappable pill --- the
            // same effective-policy copy, now legible AND actionable: a
            // surfaceVariant pill with the shield glyph in the accent and a
            // 10dp margin, tapping it opens the export-policy sheet (the
            // same sheet the Save-copy CTA opens; the privacy floor and the
            // policy choice machinery are untouched).
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

        // Keep the tool rail docked below the canvas on every screen so
        // switching tools never requires returning to the main canvas.
        PhotoTabBar(
            current = screen,
            enabled = !exporting,
            onSelect = { enterTool(it) },
        )
    }
}

// The r56 move: PhotoToolHeader and PhotoTabBar live in `PhotoChrome.kt`
// (same package, internal --- the call sites in this file are unchanged).

// The r56 move: HslBandSlider lives in `PhotoControls.kt` (the r56 control
// kit --- same signature, live value pill added).


@Composable
private fun policyLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.PRESERVE -> stringResource(R.string.editor_policy_preserve)
    MetadataPolicy.STRIP_LOCATION -> stringResource(R.string.editor_policy_strip_location)
    MetadataPolicy.STRIP_ALL -> stringResource(R.string.editor_policy_strip_all)
}
