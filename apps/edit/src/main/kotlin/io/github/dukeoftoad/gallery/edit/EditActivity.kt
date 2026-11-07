package io.github.dukeoftoad.gallery.edit

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.dukeoftoad.gallery.edit.contract.EditContract
import io.github.dukeoftoad.gallery.edit.contract.EditRequestValidator
import io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy
import io.github.dukeoftoad.gallery.edit.contract.RawExtras
import io.github.dukeoftoad.gallery.edit.contract.RequestValidation
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
    internal lateinit var request: io.github.dukeoftoad.gallery.edit.contract.EditRequest
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
private data class SliderSpec(
    val key: String,
    val label: Int,
    val a11yNameRes: Int,
    val get: (EditRecipe) -> Int,
    val set: (EditRecipe, Int) -> EditRecipe,
)

/** The tone + colour + detail sliders (the full r30 adjustment suite minus HSL bands). */
private val ADJUST_SLIDERS: List<SliderSpec> = listOf(
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
private val HSL_BANDS: List<Pair<String, (HslBands) -> HslBandAdjust>> = listOf(
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

/**
 * The r54 photo surface's screens: the main chassis plus the ten tool
 * tabs (the video editor's EditorScreen adapted). Labels reuse the
 * section-header strings wherever one exists; the Adjust tab reuses the
 * video surface's slider icon --- one app, one icon vocabulary.
 */
private enum class PhotoScreen(val labelRes: Int, val iconRes: Int) {
    /** The main chassis; never a tab (the tab bar shows the ten tools only). */
    Main(R.string.editor_app_name, 0),
    Adjust(R.string.editor_section_adjust, R.drawable.ic_vt_adjust),
    Color(R.string.editor_pt_tab_color, R.drawable.ic_pt_color),
    Looks(R.string.editor_section_filters, R.drawable.ic_pt_looks),
    Frames(R.string.editor_section_frames, R.drawable.ic_pt_frames),
    Text(R.string.editor_section_text, R.drawable.ic_pt_text),
    Stickers(R.string.editor_section_stickers, R.drawable.ic_pt_sticker),
    Draw(R.string.editor_section_drawing, R.drawable.ic_pt_draw),
    Regions(R.string.editor_section_regions, R.drawable.ic_pt_region),
    Ai(R.string.editor_pt_tab_ai, R.drawable.ic_pt_ai),
    Crop(R.string.editor_section_geometry, R.drawable.ic_pt_crop),
}

/**
 * The recipe-field editors --- the tools whose whole surface is a set of
 * recipe values (vs the layer tools' add/select/delete flows and the AI
 * tools' jobs). These get the session Reset footer: reverting the tool
 * session is ONE undoable commit against the entry snapshot.
 */
private val PHOTO_VALUE_TOOLS: Set<PhotoScreen> = setOf(
    PhotoScreen.Adjust,
    PhotoScreen.Color,
    PhotoScreen.Looks,
    PhotoScreen.Frames,
    PhotoScreen.Crop,
)

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
    // --- the Reset footer's honest boundary (reverting a tool session is
    // ONE undoable commit against this snapshot).
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
            // The crop overlay rect rides the POST-geometry frame of the
            // same recipe (plan computed over the pipeline's own dims).
            val plan = EditGeometryPlan.plan(effective, width, height)
            previewCropRect = plan.cropPixelRect
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
    // Entering a tool snapshots the recipe (the Reset footer's honest
    // boundary); leaving one sweeps any pending gesture draft and drops
    // the paint modes --- painting belongs to its tool's screen now,
    // where the scroll era kept the modes alive across sections (the one
    // deliberate behavior change; the modes were unreachable from other
    // sections' controls anyway).
    fun enterTool(target: PhotoScreen) {
        if (target == PhotoScreen.Main) return
        sweepPendingDrafts()
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
        // ---- Top bar: the r54 chassis header ----
        // The MAIN screen keeps the photo editor's own chrome (close /
        // title / settings / undo / redo / save) --- the video header is a
        // back / title / export pill, but the photo surface's undo-redo
        // and the r41 settings entry are contract chrome that stays one
        // tap from the canvas. A tool screen swaps in the video shape.
        if (screen == PhotoScreen.Main) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
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
                )
                // r41: the editor's FIRST settings entry --- a one-row sheet
                // (the retouch kill-switch lives there; ADR-0010 section 5).
                TextButton(
                    onClick = { settingsOpen = !settingsOpen },
                    enabled = !exporting,
                ) {
                    Text(stringResource(R.string.editor_settings))
                }
                TextButton(
                    onClick = {
                        sweepPendingDrafts()
                        uiState = engine.undo()
                    },
                    enabled = uiState.canUndo && !exporting,
                ) {
                    Text(stringResource(R.string.editor_undo))
                }
                TextButton(
                    onClick = {
                        sweepPendingDrafts()
                        uiState = engine.redo()
                    },
                    enabled = uiState.canRedo && !exporting,
                ) {
                    Text(stringResource(R.string.editor_redo))
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
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        Text(
                            stringResource(
                                if (exportChoice == null) {
                                    R.string.editor_save_copy
                                } else {
                                    R.string.editor_cancel_export
                                },
                            ),
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
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                },
            contentAlignment = Alignment.Center,
        ) {
            val image = previewImage
            if (image != null) {
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
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // ---- The r41 settings sheet (the editor's FIRST, one row) ----
        // The retouch kill-switch lives here (ADR-0010 section 5): a
        // permanent disable hides the entry AND the stage refuses even
        // with a restored sidecar (the layer stays verbatim, the stage
        // skips --- pinned in RetouchConsentTest + AiRenderTest).
        if (settingsOpen && !exporting) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xEE17131F))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

        // ---- The export sheet (format + quality) ----
        val choice = exportChoice
        if (choice != null && !exporting) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xEE17131F))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                    Slider(
                        value = choice.quality.toFloat(),
                        onValueChange = { exportChoice = choice.copy(quality = it.toInt()) },
                        valueRange = 1f..100f,
                        modifier = Modifier.semantics {
                            contentDescription = qualityName
                            stateDescription = qualityState
                        },
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
            when (screen) {
                PhotoScreen.Crop -> {
                    // ---- Geometry row ----
                    Text(
                        text = stringResource(R.string.editor_section_geometry),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        IconButton(
                            onClick = {
                                commit(
                                    committed.copy(
                                        quarterTurns = (committed.quarterTurns + 1) % 4,
                                    ),
                                )
                            },
                            enabled = !exporting,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.editor_rotate_left),
                                tint = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        // r32 a11y: both flip toggles are FilterChips. A text
                        // label inside an IconButton overflows the 48dp target, and
                        // a clickable Text was a sub-48dp touch target; a selected
                        // chip gives the proper target, the state highlight AND the
                        // selected/not-selected announcement next to the on/off text.
                        FilterChip(
                            selected = committed.horizontalFlip,
                            onClick = {
                                commit(committed.copy(horizontalFlip = !committed.horizontalFlip))
                            },
                            enabled = !exporting,
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.editor_flip_horizontal_toggle,
                                        if (committed.horizontalFlip) {
                                            stringResource(R.string.editor_on)
                                        } else {
                                            stringResource(R.string.editor_off)
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        FilterChip(
                            selected = committed.verticalFlip,
                            onClick = {
                                commit(committed.copy(verticalFlip = !committed.verticalFlip))
                            },
                            enabled = !exporting,
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.editor_flip_vertical_toggle,
                                        if (committed.verticalFlip) {
                                            stringResource(R.string.editor_on)
                                        } else {
                                            stringResource(R.string.editor_off)
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    // r32: the straighten slider follows the drag live (the label,
                    // the thumb, the preview) exactly like the adjustment sliders;
                    // it previously held the committed value until release. The
                    // value stays a Double so a fractional sidecar straighten (e.g.
                    // 7.5) displays exactly, not truncated.
                    val straightenLive = draft?.takeIf { it.first == "straighten" }?.second?.toDouble()
                        ?: committed.straightenDeg
                    val straightenName = stringResource(R.string.editor_a11y_straighten)
                    val straightenState = stringResource(R.string.editor_straighten, straightenLive.toInt())
                    Text(
                        text = straightenState,
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = straightenLive.toFloat(),
                        onValueChange = { draft = Pair("straighten", it.toInt()) },
                        onValueChangeFinished = {
                            draft?.let { d ->
                                if (d.first == "straighten") {
                                    commit(committed.copy(straightenDeg = d.second.toDouble()))
                                }
                            }
                            draft = null
                        },
                        valueRange = -15f..15f,
                        enabled = !exporting,
                        modifier = Modifier.semantics {
                            contentDescription = straightenName
                            stateDescription = straightenState
                        },
                    )

                    // ---- Crop aspect presets (centered fits over the post-geometry frame) ----
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(
                            null to CropAspect.FREE, // the ratios are locale-neutral; the word is not (r32)
                            "1:1" to CropAspect.SQUARE,
                            "4:3" to CropAspect.RATIO_4_3,
                            "3:4" to CropAspect.RATIO_3_4,
                            "16:9" to CropAspect.RATIO_16_9,
                            "9:16" to CropAspect.RATIO_9_16,
                        ).forEach { (label, aspect) ->
                            AssistChip(
                                onClick = {
                                    if (aspect == CropAspect.FREE) {
                                        commit(committed.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0)))
                                    } else {
                                        // The preset fits over the post-geometry
                                        // frame (the same plan the overlay draws).
                                        val plan = EditGeometryPlan.plan(
                                            committed.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0)),
                                            previewBase?.width ?: 1,
                                            previewBase?.height ?: 1,
                                        )
                                        val fitted = aspect.fit(plan.cropPixelRect, plan.postGeometryWidth, plan.postGeometryHeight)
                                        // r32: the pixel-rect -> normalized conversion
                                        // is the tested pure inverse (CropRect.fromPixelRect),
                                        // not an inline division that could drift.
                                        commit(
                                            committed.copy(
                                                crop = CropRect.fromPixelRect(
                                                    fitted,
                                                    plan.postGeometryWidth,
                                                    plan.postGeometryHeight,
                                                ),
                                            ),
                                        )
                                    }
                                },
                                label = {
                                    Text(label ?: stringResource(R.string.editor_crop_free))
                                },
                                modifier = Modifier.padding(end = 8.dp),
                                enabled = !exporting,
                            )
                        }
                    }
                }
                PhotoScreen.Adjust -> {

                    // ---- The adjustment suite ----
                    Text(
                        text = stringResource(R.string.editor_section_adjust),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    for (spec in ADJUST_SLIDERS) {
                        val committedValue = spec.get(committed)
                        val live = if (draft?.first == spec.key) draft!!.second else committedValue
                        val labelText = stringResource(spec.label, live)
                        val a11yName = stringResource(spec.a11yNameRes)
                        Text(
                            text = labelText,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = live.toFloat(),
                            onValueChange = { value -> draft = Pair(spec.key, value.toInt()) },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == spec.key) {
                                        commit(spec.set(committed, d.second))
                                    }
                                }
                                draft = null
                            },
                            valueRange = -100f..100f,
                            enabled = !exporting,
                            // r32 a11y: every slider announces its NAME and its live
                            // VALUE, not just the default percent-of-range.
                            modifier = Modifier.semantics {
                                contentDescription = a11yName
                                stateDescription = labelText
                            },
                        )
                    }
                }
                PhotoScreen.Looks -> {

                    // ---- Filters (r33: the bundled .cube looks at an intensity) ----
                    // One None chip + one chip per look that PARSED; the preview and
                    // the export resolve the selection through the same shared cache,
                    // so what the user sees is what the pipeline exports. A selection
                    // this build cannot resolve (a version-skewed sidecar) selects no
                    // chip --- the tolerance contract, shown honestly.
                    Text(
                        text = stringResource(R.string.editor_section_filters),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    val selection = committed.lut
                    val bundledIds = remember { BundledLuts.ORDERED.map { it.id }.toSet() }
                    val importedIds = looks.keys.filter { it !in bundledIds }.sorted()
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = selection == null,
                            onClick = { commit(committed.copy(lut = null)) },
                            enabled = !exporting,
                            label = { Text(stringResource(R.string.editor_filter_none)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        BundledLuts.ORDERED
                            .filter { looks.containsKey(it.id) }
                            .forEach { entry ->
                                FilterChip(
                                    selected = selection?.lutId == entry.id,
                                    onClick = {
                                        // A new look keeps the current intensity
                                        // (continuity while auditioning looks).
                                        commit(
                                            committed.copy(
                                                lut = LutSelection(
                                                    lutId = entry.id,
                                                    intensity = selection?.intensity ?: 100,
                                                ),
                                            ),
                                        )
                                    },
                                    enabled = !exporting,
                                    label = { Text(stringResource(lookNameRes(entry.id))) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        // The user-imported looks (r33 extension), id-sorted; the
                        // store's collision rule makes the subtraction exact.
                        importedIds.forEach { id ->
                            val look = looks.getValue(id)
                            FilterChip(
                                selected = selection?.lutId == id,
                                onClick = {
                                    commit(
                                        committed.copy(
                                            lut = LutSelection(
                                                lutId = id,
                                                intensity = selection?.intensity ?: 100,
                                            ),
                                        ),
                                    )
                                },
                                enabled = !exporting,
                                label = { Text(look.title.ifBlank { id }) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // The import action: SAF-pick a .cube (the zero-network
                        // editor reads local documents only).
                        AssistChip(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            enabled = !exporting,
                            label = { Text(stringResource(R.string.editor_filter_import)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    importError?.let { reason ->
                        Text(
                            text = stringResource(R.string.editor_filter_import_failed, reason),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (selection != null && looks.containsKey(selection.lutId)) {
                        // The intensity slider, live-dragged like every other
                        // slider (r32 pattern): the draft previews, release commits.
                        val live =
                            if (draft?.first == "lut-intensity") draft!!.second else selection.intensity
                        val intensityLabel = stringResource(R.string.editor_filter_intensity, live)
                        val intensityName = stringResource(R.string.editor_a11y_filter_intensity)
                        Text(
                            text = intensityLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = live.toFloat(),
                            onValueChange = { draft = Pair("lut-intensity", it.toInt()) },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "lut-intensity" && committed.lut != null) {
                                        commit(committed.copy(lut = committed.lut!!.copy(intensity = d.second)))
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = intensityName
                                stateDescription = intensityLabel
                            },
                        )
                        // An IMPORTED look can be removed (bundled looks are part
                        // of the app); removal clears the selection and reloads
                        // the registry. A sidecar still naming the removed look
                        // degrades to no-look by the tolerance contract.
                        if (selection.lutId !in bundledIds) {
                            TextButton(
                                onClick = {
                                    val removedId = selection.lutId
                                    uiScope.launch {
                                        withContext(Dispatchers.IO) {
                                            activity.importedLutStore.delete(removedId)
                                        }
                                        looksVersion++
                                        commit(committed.copy(lut = null))
                                    }
                                },
                                enabled = !exporting,
                            ) {
                                Text(stringResource(R.string.editor_filter_remove))
                            }
                        }
                    }
                }
                PhotoScreen.Text -> {

                    // ---- Text layers (r34: the OFL-font text tool) ----
                    // The contract's UI shape (text-tool.md decision 5): an Add
                    // chip -> the layer list -> per-layer controls (text field,
                    // font chip row, size/opacity/rotation sliders with the r32
                    // semantics conventions, color swatches). Layer chips show
                    // their text (the fallback labels empty layers); repositioning
                    // is the drag gesture on the preview (documented nit: position
                    // has no slider, so it is not TalkBack-reachable in v1).
                    Text(
                        text = stringResource(R.string.editor_section_text),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        AssistChip(
                            onClick = {
                                sweepPendingDrafts()
                                val existing = committed.textLayers?.layers ?: emptyList()
                                val newLayer = TextLayer(
                                    id = TextLayer.newId(existing.map { it.id }),
                                    text = "",
                                    fontId = BundledFonts.DEFAULT_ID,
                                )
                                commit(
                                    committed.copy(textLayers = TextLayers(existing + newLayer)),
                                )
                                selectedTextLayerId = newLayer.id
                                // r35: the new layer becomes THE selection --- one
                                // selected layer at a time across the families.
                                selectedStickerLayerId = null
                                selectedRegionLayerId = null
                            },
                            enabled = !exporting,
                            label = { Text(stringResource(R.string.editor_text_add)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        committed.textLayers?.layers?.forEachIndexed { index, layer ->
                            FilterChip(
                                selected = selectedTextLayerId == layer.id,
                                onClick = {
                                    if (selectedTextLayerId != layer.id) {
                                        sweepPendingDrafts()
                                        selectedTextLayerId = layer.id
                                        // r35/r37: one selection across the families.
                                        selectedStickerLayerId = null
                                        selectedRegionLayerId = null
                                    }
                                },
                                enabled = !exporting,
                                label = {
                                    val fallback = stringResource(R.string.editor_text_layer, index + 1)
                                    val label = layer.text.ifBlank { fallback }
                                    Text(if (label.length > 16) label.take(15) + "…" else label)
                                },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    val selectedTextLayer = committed.textLayers?.layers
                        ?.firstOrNull { it.id == selectedTextLayerId }
                    if (selectedTextLayer != null) {
                        val layerId = selectedTextLayer.id
                        // -- The text field: drafts locally (per-keystroke commits
                        //    would flood the undo stack); commits on IME Done, on
                        //    target switch, on add/remove and before an export.
                        val fieldText = textDraft?.takeIf { it.first == layerId }?.second
                            ?: selectedTextLayer.text
                        val textContentName = stringResource(R.string.editor_a11y_text_content)
                        val fieldHint = stringResource(R.string.editor_text_field_hint)
                        OutlinedTextField(
                            value = fieldText,
                            onValueChange = { value ->
                                textDraft = layerId to value.take(TextLayer.MAX_TEXT_LENGTH)
                            },
                            enabled = !exporting,
                            singleLine = true,
                            placeholder = { Text(fieldHint, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                capitalization = KeyboardCapitalization.Sentences,
                            ),
                            keyboardActions = KeyboardActions(onDone = { commitTextDraftIfPending() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = textContentName },
                        )
                        // -- The font chips: the bundled four; an unresolvable
                        //    fontId selects NO chip (the tolerance contract, shown
                        //    honestly exactly like an unknown look id).
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            BundledFonts.ORDERED.forEach { entry ->
                                FilterChip(
                                    selected = selectedTextLayer.fontId == entry.id,
                                    onClick = {
                                        commitSelectedLayer { it.copy(fontId = entry.id) }
                                    },
                                    enabled = !exporting,
                                    label = { Text(entry.displayName) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                        // -- size / opacity / rotation: the live-draft pattern.
                        val sizeLive = draft?.takeIf { it.first == "text-size@$layerId" }?.second
                            ?: selectedTextLayer.sizePct
                        val sizeLabel = stringResource(R.string.editor_text_size, sizeLive)
                        val sizeName = stringResource(R.string.editor_a11y_text_size)
                        Text(
                            text = sizeLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = sizeLive.toFloat(),
                            onValueChange = { draft = "text-size@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "text-size@$layerId") {
                                        commitSelectedLayer { it.copy(sizePct = d.second.coerceIn(0, 300)) }
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..300f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = sizeName
                                stateDescription = sizeLabel
                            },
                        )
                        val opacityLive = draft?.takeIf { it.first == "text-opacity@$layerId" }?.second
                            ?: selectedTextLayer.opacity
                        val opacityLabel = stringResource(R.string.editor_text_opacity, opacityLive)
                        val opacityName = stringResource(R.string.editor_a11y_text_opacity)
                        Text(
                            text = opacityLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = opacityLive.toFloat(),
                            onValueChange = { draft = "text-opacity@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "text-opacity@$layerId") {
                                        commitSelectedLayer { it.copy(opacity = d.second.coerceIn(0, 100)) }
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = opacityName
                                stateDescription = opacityLabel
                            },
                        )
                        val rotationLive = draft?.takeIf { it.first == "text-rotation@$layerId" }?.second
                            ?: selectedTextLayer.rotationDeg
                        val rotationLabel = stringResource(R.string.editor_text_rotation, rotationLive)
                        val rotationName = stringResource(R.string.editor_a11y_text_rotation)
                        Text(
                            text = rotationLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = rotationLive.toFloat(),
                            onValueChange = { draft = "text-rotation@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "text-rotation@$layerId") {
                                        commitSelectedLayer {
                                            it.copy(rotationDeg = d.second.coerceIn(-180, 180))
                                        }
                                    }
                                }
                                draft = null
                            },
                            valueRange = -180f..180f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = rotationName
                                stateDescription = rotationLabel
                            },
                        )
                        // -- The color swatches: a fixed named palette, a11y-labelled
                        //    (TalkBack announces each control; selection is radio
                        //    semantics --- one color at a time).
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            TEXT_COLORS.forEach { (colorArgb, nameRes) ->
                                ColorSwatch(
                                    colorArgb = colorArgb,
                                    selected = selectedTextLayer.colorArgb == colorArgb,
                                    name = stringResource(nameRes),
                                    enabled = !exporting,
                                    onPick = {
                                        commitSelectedLayer { it.copy(colorArgb = colorArgb) }
                                    },
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                sweepPendingDrafts()
                                val remaining = (committed.textLayers?.layers ?: emptyList())
                                    .filterNot { it.id == layerId }
                                commit(
                                    committed.copy(
                                        textLayers = if (remaining.isEmpty()) {
                                            null
                                        } else {
                                            TextLayers(remaining)
                                        },
                                    ),
                                )
                                selectedTextLayerId = remaining.lastOrNull()?.id
                            },
                            enabled = !exporting,
                        ) {
                            Text(stringResource(R.string.editor_text_remove))
                        }
                    }
                }
                PhotoScreen.Stickers -> {

                    // ---- Stickers (r35: the sticker tool) ----
                    // The contract's UI shape (stickers.md decision 6): the
                    // registry chip row (bundled eight, imported id-sorted, the
                    // SAF import chip for image/png + image/webp) --- a chip tap
                    // ADDS a layer at canvas centre; registry chips are ADD
                    // actions, not selections (the layer chips below carry the
                    // selection). One selected layer at a time across the
                    // families; the drag gesture and the per-layer controls
                    // follow the single selection.
                    Text(
                        text = stringResource(R.string.editor_section_stickers),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        BundledStickers.ORDERED.forEach { entry ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    sweepPendingDrafts()
                                    val existing = committed.stickerLayers?.layers ?: emptyList()
                                    val newLayer = StickerLayer(
                                        id = StickerLayer.newId(existing.map { it.id }),
                                        stickerId = entry.id,
                                    )
                                    commit(
                                        committed.copy(stickerLayers = StickerLayers(existing + newLayer)),
                                    )
                                    selectedStickerLayerId = newLayer.id
                                    selectedTextLayerId = null
                                    selectedRegionLayerId = null
                                },
                                enabled = !exporting,
                                label = { Text(entry.displayName) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // The user-imported stickers (r35), id-sorted; the store's
                        // collision rule makes the keys disjoint from the bundled set.
                        importedStickers.keys.sorted().forEach { id ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    sweepPendingDrafts()
                                    val existing = committed.stickerLayers?.layers ?: emptyList()
                                    val newLayer = StickerLayer(
                                        id = StickerLayer.newId(existing.map { it.id }),
                                        stickerId = id,
                                    )
                                    commit(
                                        committed.copy(stickerLayers = StickerLayers(existing + newLayer)),
                                    )
                                    selectedStickerLayerId = newLayer.id
                                    selectedTextLayerId = null
                                    selectedRegionLayerId = null
                                },
                                enabled = !exporting,
                                label = { Text(id) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // The import action: SAF-pick a PNG/WebP (the zero-network
                        // editor reads local documents only).
                        AssistChip(
                            onClick = { stickerImportLauncher.launch(arrayOf("image/png", "image/webp")) },
                            enabled = !exporting,
                            label = { Text(stringResource(R.string.editor_sticker_import)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    stickerImportError?.let { reason ->
                        Text(
                            text = stringResource(R.string.editor_sticker_import_failed, reason),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    // The layer chips: their sticker's display name (the raw id for
                    // an import or an unresolvable id --- the tolerance contract,
                    // shown honestly exactly like an unknown look id).
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        committed.stickerLayers?.layers?.forEach { layer ->
                            FilterChip(
                                selected = selectedStickerLayerId == layer.id,
                                onClick = {
                                    if (selectedStickerLayerId != layer.id) {
                                        sweepPendingDrafts()
                                        selectedStickerLayerId = layer.id
                                        selectedTextLayerId = null
                                        selectedRegionLayerId = null
                                    }
                                },
                                enabled = !exporting,
                                label = {
                                    val label = BundledStickers.byId(layer.stickerId)?.displayName
                                        ?: layer.stickerId
                                    Text(if (label.length > 16) label.take(15) + "…" else label)
                                },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    val selectedStickerLayer = committed.stickerLayers?.layers
                        ?.firstOrNull { it.id == selectedStickerLayerId }
                    if (selectedStickerLayer != null) {
                        val layerId = selectedStickerLayer.id
                        // -- size / opacity / rotation: the live-draft pattern
                        //    (the r34 conventions; the keys carry the sticker-
                        //    prefix so effectiveRecipe() routes them).
                        val sizeLive = draft?.takeIf { it.first == "sticker-size@$layerId" }?.second
                            ?: selectedStickerLayer.sizePct
                        val sizeLabel = stringResource(R.string.editor_sticker_size, sizeLive)
                        val sizeName = stringResource(R.string.editor_a11y_sticker_size)
                        Text(
                            text = sizeLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = sizeLive.toFloat(),
                            onValueChange = { draft = "sticker-size@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "sticker-size@$layerId") {
                                        commitSelectedStickerLayer { it.copy(sizePct = d.second.coerceIn(0, 300)) }
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..300f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = sizeName
                                stateDescription = sizeLabel
                            },
                        )
                        val opacityLive = draft?.takeIf { it.first == "sticker-opacity@$layerId" }?.second
                            ?: selectedStickerLayer.opacity
                        val opacityLabel = stringResource(R.string.editor_sticker_opacity, opacityLive)
                        val opacityName = stringResource(R.string.editor_a11y_sticker_opacity)
                        Text(
                            text = opacityLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = opacityLive.toFloat(),
                            onValueChange = { draft = "sticker-opacity@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "sticker-opacity@$layerId") {
                                        commitSelectedStickerLayer { it.copy(opacity = d.second.coerceIn(0, 100)) }
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = opacityName
                                stateDescription = opacityLabel
                            },
                        )
                        val rotationLive = draft?.takeIf { it.first == "sticker-rotation@$layerId" }?.second
                            ?: selectedStickerLayer.rotationDeg
                        val rotationLabel = stringResource(R.string.editor_sticker_rotation, rotationLive)
                        val rotationName = stringResource(R.string.editor_a11y_sticker_rotation)
                        Text(
                            text = rotationLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = rotationLive.toFloat(),
                            onValueChange = { draft = "sticker-rotation@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "sticker-rotation@$layerId") {
                                        commitSelectedStickerLayer {
                                            it.copy(rotationDeg = d.second.coerceIn(-180, 180))
                                        }
                                    }
                                }
                                draft = null
                            },
                            valueRange = -180f..180f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = rotationName
                                stateDescription = rotationLabel
                            },
                        )
                        TextButton(
                            onClick = {
                                sweepPendingDrafts()
                                val remaining = (committed.stickerLayers?.layers ?: emptyList())
                                    .filterNot { it.id == layerId }
                                commit(
                                    committed.copy(
                                        stickerLayers = if (remaining.isEmpty()) {
                                            null
                                        } else {
                                            StickerLayers(remaining)
                                        },
                                    ),
                                )
                                selectedStickerLayerId = remaining.lastOrNull()?.id
                            },
                            enabled = !exporting,
                        ) {
                            Text(stringResource(R.string.editor_sticker_remove))
                        }
                    }
                }
                PhotoScreen.Draw -> {

                    // ---- Drawing (r36: the freehand tool) ----
                    // The contract's UI shape (drawing-tool.md decision 6): the
                    // Draw-mode chip + the stroke layer chips, then the controls
                    // under the duality --- Draw mode ON tunes the NEXT stroke
                    // (the brush, plain state; the live gesture previews through
                    // a transient draft layer); a selected layer re-binds the SAME
                    // controls to it through the live-draft pattern. The two states
                    // are exclusive; ONE selection at a time across ALL the
                    // families (the r35 contract extended).
                    Text(
                        text = stringResource(R.string.editor_section_drawing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = drawMode,
                            onClick = {
                                sweepPendingDrafts()
                                drawMode = !drawMode
                                if (drawMode) {
                                    // Entering paint mode drops every selection ---
                                    // painting owns the gesture; the user keeps
                                    // painting across strokes. The r37 twin grew to
                                    // THREE paint modes in r41 (Draw/Region/Erase):
                                    // mutually exclusive, enforced at every toggle.
                                    regionMode = false
                                    eraseMode = false
                                    selectedTextLayerId = null
                                    selectedStickerLayerId = null
                                    selectedDrawLayerId = null
                                    selectedRegionLayerId = null
                                }
                            },
                            enabled = !exporting,
                            label = { Text(stringResource(R.string.editor_draw_mode)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        committed.drawLayers?.layers?.forEachIndexed { index, layer ->
                            FilterChip(
                                selected = selectedDrawLayerId == layer.id,
                                onClick = {
                                    if (selectedDrawLayerId != layer.id) {
                                        sweepPendingDrafts()
                                        selectedDrawLayerId = layer.id
                                        drawMode = false // the duality is exclusive
                                        regionMode = false // and so are the paint modes
                                        eraseMode = false // all three of them (r41)
                                        selectedTextLayerId = null
                                        selectedStickerLayerId = null
                                        selectedRegionLayerId = null
                                    }
                                },
                                enabled = !exporting,
                                label = { Text(stringResource(R.string.editor_draw_layer, index + 1)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    val selectedDrawLayer = committed.drawLayers?.layers
                        ?.firstOrNull { it.id == selectedDrawLayerId }
                    if (drawMode) {
                        // -- The brush (tunes the NEXT stroke): the swatches and
                        //    sliders are plain state, applied at commit; the live
                        //    gesture previews them through the draft layer.
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            TEXT_COLORS.forEach { (colorArgb, nameRes) ->
                                ColorSwatch(
                                    colorArgb = colorArgb,
                                    selected = brushColor == colorArgb,
                                    name = stringResource(nameRes),
                                    enabled = !exporting,
                                    onPick = { brushColor = colorArgb },
                                )
                            }
                        }
                        val widthName = stringResource(R.string.editor_a11y_draw_width)
                        val widthLabel = stringResource(R.string.editor_draw_width, brushWidth)
                        Text(
                            text = widthLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = brushWidth.toFloat(),
                            onValueChange = { brushWidth = it.toInt().coerceIn(1, 100) },
                            valueRange = 1f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = widthName
                                stateDescription = widthLabel
                            },
                        )
                        val opacityName = stringResource(R.string.editor_a11y_draw_opacity)
                        val opacityLabel = stringResource(R.string.editor_draw_opacity, brushOpacity)
                        Text(
                            text = opacityLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = brushOpacity.toFloat(),
                            onValueChange = { brushOpacity = it.toInt().coerceIn(0, 100) },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = opacityName
                                stateDescription = opacityLabel
                            },
                        )
                        val smoothingName = stringResource(R.string.editor_a11y_draw_smoothing)
                        val smoothingLabel = stringResource(R.string.editor_draw_smoothing, brushSmoothing)
                        Text(
                            text = smoothingLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = brushSmoothing.toFloat(),
                            onValueChange = { brushSmoothing = it.toInt().coerceIn(0, 100) },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = smoothingName
                                stateDescription = smoothingLabel
                            },
                        )
                    } else if (selectedDrawLayer != null) {
                        val layerId = selectedDrawLayer.id
                        // -- Per-layer re-edit (the live-draft pattern; the keys
                        //    carry the draw- prefix so effectiveRecipe() routes
                        //    them). The swatches and sliders re-bind to the layer.
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            TEXT_COLORS.forEach { (colorArgb, nameRes) ->
                                ColorSwatch(
                                    colorArgb = colorArgb,
                                    selected = selectedDrawLayer.colorArgb == colorArgb,
                                    name = stringResource(nameRes),
                                    enabled = !exporting,
                                    onPick = {
                                        commitSelectedDrawLayer { it.copy(colorArgb = colorArgb) }
                                    },
                                )
                            }
                        }
                        val widthLive = draft?.takeIf { it.first == "draw-width@$layerId" }?.second
                            ?: selectedDrawLayer.widthPct
                        val widthName = stringResource(R.string.editor_a11y_draw_width)
                        val widthLabel = stringResource(R.string.editor_draw_width, widthLive)
                        Text(
                            text = widthLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = widthLive.toFloat(),
                            onValueChange = { draft = "draw-width@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "draw-width@$layerId") {
                                        commitSelectedDrawLayer { it.copy(widthPct = d.second.coerceIn(1, 100)) }
                                    }
                                }
                                draft = null
                            },
                            valueRange = 1f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = widthName
                                stateDescription = widthLabel
                            },
                        )
                        val opacityLive = draft?.takeIf { it.first == "draw-opacity@$layerId" }?.second
                            ?: selectedDrawLayer.opacity
                        val opacityName = stringResource(R.string.editor_a11y_draw_opacity)
                        val opacityLabel = stringResource(R.string.editor_draw_opacity, opacityLive)
                        Text(
                            text = opacityLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = opacityLive.toFloat(),
                            onValueChange = { draft = "draw-opacity@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "draw-opacity@$layerId") {
                                        commitSelectedDrawLayer { it.copy(opacity = d.second.coerceIn(0, 100)) }
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = opacityName
                                stateDescription = opacityLabel
                            },
                        )
                        val smoothingLive = draft?.takeIf { it.first == "draw-smoothing@$layerId" }?.second
                            ?: selectedDrawLayer.smoothingPct
                        val smoothingName = stringResource(R.string.editor_a11y_draw_smoothing)
                        val smoothingLabel = stringResource(R.string.editor_draw_smoothing, smoothingLive)
                        Text(
                            text = smoothingLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = smoothingLive.toFloat(),
                            onValueChange = { draft = "draw-smoothing@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "draw-smoothing@$layerId") {
                                        commitSelectedDrawLayer {
                                            it.copy(smoothingPct = d.second.coerceIn(0, 100))
                                        }
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = smoothingName
                                stateDescription = smoothingLabel
                            },
                        )
                        TextButton(
                            onClick = {
                                sweepPendingDrafts()
                                val remaining = (committed.drawLayers?.layers ?: emptyList())
                                    .filterNot { it.id == layerId }
                                commit(
                                    committed.copy(
                                        drawLayers = if (remaining.isEmpty()) {
                                            null
                                        } else {
                                            DrawLayers(remaining)
                                        },
                                    ),
                                )
                                selectedDrawLayerId = remaining.lastOrNull()?.id
                            },
                            enabled = !exporting,
                        ) {
                            Text(stringResource(R.string.editor_draw_remove))
                        }
                    }
                }
                PhotoScreen.Regions -> {

                    // ---- Regions (r37: the mosaic/blur pixel stage) ----
                    // The r37 UI section, landed here as the recorded debt fix (the
                    // r37 insertion script wrote the gradle file but never the
                    // activity --- the section existed only in a Python variable;
                    // the merged r37 carried the model, the recipe, the pipeline
                    // stage and the gesture scaffolding without this surface, and
                    // regionMode was unreachable. The correction rides r38; the
                    // r37 delivery record gains the note).
                    //
                    // The contract's UI shape (mosaic-regions.md decision 6): the
                    // Region-mode chip (mutually exclusive with Draw mode --- one
                    // paint mode at a time, enforced at both toggles), the region
                    // layer chips, and the controls under the duality: paint mode
                    // tunes the NEXT region (kind chips + strength slider, plain
                    // state, previewed live through the transient draft region);
                    // a selected region re-binds the same controls through the
                    // live-draft pattern. The effect previews AT PAINT TIME: the
                    // draft region rides the effective recipe through the same
                    // pipeline stage the export runs.
                    Text(
                        text = stringResource(R.string.editor_section_regions),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = regionMode,
                            onClick = {
                                sweepPendingDrafts()
                                regionMode = !regionMode
                                if (regionMode) {
                                    // Entering region paint mode drops every
                                    // selection and exits the other paint modes;
                                    // the user keeps painting across regions.
                                    drawMode = false
                                    eraseMode = false
                                    selectedTextLayerId = null
                                    selectedStickerLayerId = null
                                    selectedDrawLayerId = null
                                    selectedRegionLayerId = null
                                }
                            },
                            enabled = !exporting,
                            label = { Text(stringResource(R.string.editor_region_mode)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        committed.mosaicRegions?.layers?.forEachIndexed { index, layer ->
                            FilterChip(
                                selected = selectedRegionLayerId == layer.id,
                                onClick = {
                                    if (selectedRegionLayerId != layer.id) {
                                        sweepPendingDrafts()
                                        selectedRegionLayerId = layer.id
                                        regionMode = false
                                        drawMode = false
                                        eraseMode = false
                                        selectedTextLayerId = null
                                        selectedStickerLayerId = null
                                        selectedDrawLayerId = null
                                    }
                                },
                                enabled = !exporting,
                                label = { Text(stringResource(R.string.editor_region_layer, index + 1)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    val selectedRegionLayer = committed.mosaicRegions?.layers
                        ?.firstOrNull { it.id == selectedRegionLayerId }
                    if (regionMode) {
                        // -- The next region's kind + strength (plain state; the
                        //    live gesture previews them through the draft region).
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            FilterChip(
                                selected = regionKind == RegionKind.MOSAIC,
                                onClick = { regionKind = RegionKind.MOSAIC },
                                enabled = !exporting,
                                label = { Text(stringResource(R.string.editor_region_mosaic)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            FilterChip(
                                selected = regionKind == RegionKind.LINEAR_BLUR,
                                onClick = { regionKind = RegionKind.LINEAR_BLUR },
                                enabled = !exporting,
                                label = { Text(stringResource(R.string.editor_region_blur)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        val strengthName = stringResource(R.string.editor_a11y_region_strength)
                        val strengthLabel = stringResource(R.string.editor_region_strength, regionStrength)
                        Text(
                            text = strengthLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = regionStrength.toFloat(),
                            onValueChange = { regionStrength = it.toInt().coerceIn(1, 100) },
                            valueRange = 1f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = strengthName
                                stateDescription = strengthLabel
                            },
                        )
                    } else if (selectedRegionLayer != null) {
                        val layerId = selectedRegionLayer.id
                        // -- Per-region re-edit (the live-draft pattern; chips
                        //    commit immediately, the strength slider drafts).
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            FilterChip(
                                selected = selectedRegionLayer.kind == RegionKind.MOSAIC,
                                onClick = {
                                    commitSelectedRegionLayer { it.copy(kind = RegionKind.MOSAIC) }
                                },
                                enabled = !exporting,
                                label = { Text(stringResource(R.string.editor_region_mosaic)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            FilterChip(
                                selected = selectedRegionLayer.kind == RegionKind.LINEAR_BLUR,
                                onClick = {
                                    commitSelectedRegionLayer { it.copy(kind = RegionKind.LINEAR_BLUR) }
                                },
                                enabled = !exporting,
                                label = { Text(stringResource(R.string.editor_region_blur)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        val strengthLive = draft?.takeIf { it.first == "region-strength@$layerId" }?.second
                            ?: selectedRegionLayer.strengthPct
                        val strengthName = stringResource(R.string.editor_a11y_region_strength)
                        val strengthLabel = stringResource(R.string.editor_region_strength, strengthLive)
                        Text(
                            text = strengthLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = strengthLive.toFloat(),
                            onValueChange = { draft = "region-strength@$layerId" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "region-strength@$layerId") {
                                        commitSelectedRegionLayer { it.copy(strengthPct = d.second.coerceIn(1, 100)) }
                                    }
                                }
                                draft = null
                            },
                            valueRange = 1f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = strengthName
                                stateDescription = strengthLabel
                            },
                        )
                        TextButton(
                            onClick = {
                                sweepPendingDrafts()
                                val remaining = (committed.mosaicRegions?.layers ?: emptyList())
                                    .filterNot { it.id == layerId }
                                commit(
                                    committed.copy(
                                        mosaicRegions = if (remaining.isEmpty()) {
                                            null
                                        } else {
                                            RegionLayers(remaining)
                                        },
                                    ),
                                )
                                selectedRegionLayerId = remaining.lastOrNull()?.id
                            },
                            enabled = !exporting,
                        ) {
                            Text(stringResource(R.string.editor_region_remove))
                        }
                    }
                }
                PhotoScreen.Frames -> {

                    // ---- Frames (r38: the single mounting; the closing round) ----
                    // The contract's UI shape (frames.md decision 6): the look
                    // row's conventions --- the None chip, the bundled five, the
                    // imported chips, the SAF import chip; the width slider +
                    // colour swatches for a PARAMETRIC selection (the live-draft
                    // pattern, single-selection keys like the intensity slider);
                    // the honest no-controls note for an imported one (the bitmap
                    // is the frame). No paint mode, no gesture: a frame needs no
                    // dragging.
                    Text(
                        text = stringResource(R.string.editor_section_frames),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = committed.frame == null,
                            onClick = { sweepPendingDrafts(); commit(committed.copy(frame = null)) },
                            enabled = !exporting,
                            label = { Text(stringResource(R.string.editor_frame_none)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        BundledFrames.ORDERED.forEach { entry ->
                            FilterChip(
                                selected = committed.frame?.frameId == entry.id,
                                onClick = {
                                    sweepPendingDrafts()
                                    // A new frame keeps the current width and
                                    // colour (continuity while auditioning, the
                                    // look row's rule).
                                    commit(
                                        committed.copy(
                                            frame = FrameSelection(
                                                frameId = entry.id,
                                                widthPct = committed.frame?.widthPct ?: FrameSelection.DEFAULT_WIDTH_PCT,
                                                colorArgb = committed.frame?.colorArgb ?: FrameSelection.DEFAULT_COLOR,
                                            ),
                                        ),
                                    )
                                },
                                enabled = !exporting,
                                label = { Text(entry.displayName) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        // The user-imported frames, id-sorted; the store's collision
                        // rule makes the keys disjoint from the bundled set.
                        importedFrames.keys.sorted().forEach { id ->
                            FilterChip(
                                selected = committed.frame?.frameId == id,
                                onClick = {
                                    sweepPendingDrafts()
                                    commit(
                                        committed.copy(
                                            frame = FrameSelection(
                                                frameId = id,
                                                widthPct = committed.frame?.widthPct ?: FrameSelection.DEFAULT_WIDTH_PCT,
                                                colorArgb = committed.frame?.colorArgb ?: FrameSelection.DEFAULT_COLOR,
                                            ),
                                        ),
                                    )
                                },
                                enabled = !exporting,
                                label = { Text(id) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        AssistChip(
                            onClick = { frameImportLauncher.launch(arrayOf("image/png", "image/webp")) },
                            enabled = !exporting,
                            label = { Text(stringResource(R.string.editor_frame_import)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    frameImportError?.let { reason ->
                        Text(
                            text = stringResource(R.string.editor_frame_import_failed, reason),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    val frameSelection = committed.frame
                    if (frameSelection != null) {
                        if (BundledFrames.byId(frameSelection.frameId) != null) {
                            // -- The parametric controls: width (the single-selection
                            //    draft key) + the swatches (immediate commits).
                            val widthLive = draft?.takeIf { it.first == "frame-width" }?.second
                                ?: frameSelection.widthPct
                            val widthName = stringResource(R.string.editor_a11y_frame_width)
                            val widthLabel = stringResource(R.string.editor_frame_width, widthLive)
                            Text(
                                text = widthLabel,
                                color = MaterialTheme.colorScheme.onBackground,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Slider(
                                value = widthLive.toFloat(),
                                onValueChange = { draft = "frame-width" to it.toInt() },
                                onValueChangeFinished = {
                                    draft?.let { d ->
                                        if (d.first == "frame-width" && committed.frame != null) {
                                            commit(
                                                committed.copy(
                                                    frame = committed.frame!!.copy(widthPct = d.second.coerceIn(1, 100)),
                                                ),
                                            )
                                        }
                                    }
                                    draft = null
                                },
                                valueRange = 1f..100f,
                                enabled = !exporting,
                                modifier = Modifier.semantics {
                                    contentDescription = widthName
                                    stateDescription = widthLabel
                                },
                            )
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                TEXT_COLORS.forEach { (colorArgb, nameRes) ->
                                    ColorSwatch(
                                        colorArgb = colorArgb,
                                        selected = frameSelection.colorArgb == colorArgb,
                                        name = stringResource(nameRes),
                                        enabled = !exporting,
                                        onPick = {
                                            commit(
                                                committed.copy(
                                                    frame = frameSelection.copy(colorArgb = colorArgb),
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        } else if (importedFrames.containsKey(frameSelection.frameId)) {
                            // -- An imported frame: the bitmap is the frame; the
                            //    width/colour controls do not apply, and the UI
                            //    says so instead of showing dead controls.
                            Text(
                                text = stringResource(R.string.editor_frame_imported_note),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        // An unresolvable id (a version skew, a deleted import)
                        // selects NO chip --- the tolerance contract, shown
                        // honestly exactly like an unknown look id.
                    }
                }
                PhotoScreen.Ai -> {
                    // ---- Magic erase: the third paint mode ----
                    // The r37 region section's shape: the mode chip (ONE paint
                    // mode at a time across Draw/Region/Erase, enforced at all
                    // three toggles), the layer chips, and the controls under
                    // the mode. The erase gesture paints the seed; the inference
                    // (segmentation + inpainting) runs ONCE at gesture end ---
                    // off the UI thread, cancellable, honest progress --- and the
                    // refined mask + fill persist as ONE derived artifact.
                    Text(
                        text = stringResource(R.string.editor_section_ai_erase),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = eraseMode,
                            onClick = {
                                sweepPendingDrafts()
                                eraseMode = !eraseMode
                                if (eraseMode) {
                                    // The duality's rule, third member: entering
                                    // Erase paint mode exits the other two and
                                    // drops every selection.
                                    drawMode = false
                                    regionMode = false
                                    selectedTextLayerId = null
                                    selectedStickerLayerId = null
                                    selectedDrawLayerId = null
                                    selectedRegionLayerId = null
                                }
                            },
                            enabled = !exporting && !aiBusy,
                            label = { Text(stringResource(R.string.editor_ai_erase_mode)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        committed.aiErase?.layers?.forEachIndexed { index, layer ->
                            FilterChip(
                                selected = selectedEraseLayerId == layer.id,
                                onClick = {
                                    if (selectedEraseLayerId != layer.id) {
                                        sweepPendingDrafts()
                                        selectedEraseLayerId = layer.id
                                        drawMode = false
                                        regionMode = false
                                        eraseMode = false
                                        selectedTextLayerId = null
                                        selectedStickerLayerId = null
                                        selectedDrawLayerId = null
                                        selectedRegionLayerId = null
                                    }
                                },
                                enabled = !exporting && !aiBusy,
                                label = { Text(stringResource(R.string.editor_ai_erase_layer, index + 1)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    if (eraseMode) {
                        // The honest bounds note (ADR-0010: on-device quality is
                        // bounded; the strings say so without apologising).
                        Text(
                            text = stringResource(R.string.editor_ai_erase_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        val selectedEraseLayer = committed.aiErase?.layers
                            ?.firstOrNull { it.id == selectedEraseLayerId }
                        if (selectedEraseLayer != null) {
                            val layerId = selectedEraseLayer.id
                            if (selectedEraseLayer.artifactRef.isNotEmpty() &&
                                !activity.aiStore.contains(
                                    EditAiStore.ArtifactKind.MASK,
                                    selectedEraseLayer.artifactRef,
                                )
                            ) {
                                // The honest degraded note: the layer's artifact is
                                // gone, it renders nothing (never dead controls).
                                Text(
                                    text = stringResource(R.string.editor_ai_degraded_layer),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            TextButton(
                                onClick = {
                                    sweepPendingDrafts()
                                    val remaining = (committed.aiErase?.layers ?: emptyList())
                                        .filterNot { it.id == layerId }
                                    commit(
                                        committed.copy(
                                            aiErase = if (remaining.isEmpty()) {
                                                null
                                            } else {
                                                AiEraseLayers(remaining)
                                            },
                                        ),
                                    )
                                    selectedEraseLayerId = remaining.lastOrNull()?.id
                                },
                                enabled = !exporting,
                            ) {
                                Text(stringResource(R.string.editor_ai_erase_remove))
                            }
                        }
                    }

                    // ---- Sky replacement: detect once, then the registry ----
                    // The frames section's shape: None, the bundled five
                    // (PROJECT-AUTHORED parametric skies --- no third-party sky
                    // bitmaps ship in this APK), the imports (the
                    // ImportedFrameStore contract verbatim), the import chip. A
                    // sky chip that finds no resolvable mask runs the detection
                    // first (once, cancellable); a re-detect is a deliberate
                    // re-apply. The opacity slider re-renders from the stored
                    // mask --- no re-inference.
                    Text(
                        text = stringResource(R.string.editor_section_ai_sky),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = committed.sky == null,
                            onClick = { sweepPendingDrafts(); commit(committed.copy(sky = null)) },
                            enabled = !exporting && !aiBusy,
                            label = { Text(stringResource(R.string.editor_ai_sky_none)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        BundledSkies.ORDERED.forEach { entry ->
                            FilterChip(
                                selected = committed.sky?.skyId == entry.id,
                                onClick = {
                                    sweepPendingDrafts()
                                    selectSky(entry.id)
                                },
                                enabled = !exporting && !aiBusy,
                                label = { Text(entry.displayName) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        importedSkies.keys.sorted().forEach { id ->
                            FilterChip(
                                selected = committed.sky?.skyId == id,
                                onClick = {
                                    sweepPendingDrafts()
                                    selectSky(id)
                                },
                                enabled = !exporting && !aiBusy,
                                label = { Text(id) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                        AssistChip(
                            onClick = { skyImportLauncher.launch(arrayOf("image/png", "image/webp")) },
                            enabled = !exporting && !aiBusy,
                            label = { Text(stringResource(R.string.editor_ai_sky_import)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    skyImportError?.let { reason ->
                        Text(
                            text = stringResource(R.string.editor_ai_sky_import_failed, reason),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    val skySelection = committed.sky
                    if (skySelection != null) {
                        val skyOpacityLive = draft?.takeIf { it.first == "sky-opacity" }?.second
                            ?: skySelection.opacityPct
                        val skyOpacityName = stringResource(R.string.editor_a11y_ai_sky_opacity)
                        val skyOpacityLabel = stringResource(R.string.editor_ai_sky_opacity, skyOpacityLive)
                        Text(
                            text = skyOpacityLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = skyOpacityLive.toFloat(),
                            onValueChange = { draft = "sky-opacity" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "sky-opacity" && committed.sky != null) {
                                        commit(
                                            committed.copy(
                                                sky = committed.sky!!.copy(opacityPct = d.second.coerceIn(0, 100)),
                                            ),
                                        )
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = skyOpacityName
                                stateDescription = skyOpacityLabel
                            },
                        )
                        if (BundledSkies.byId(skySelection.skyId) == null &&
                            importedSkies.containsKey(skySelection.skyId)
                        ) {
                            Text(
                                text = stringResource(R.string.editor_ai_sky_note),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (skySelection.maskRef.isNotEmpty() &&
                            !activity.aiStore.contains(EditAiStore.ArtifactKind.MASK, skySelection.maskRef)
                        ) {
                            Text(
                                text = stringResource(R.string.editor_ai_degraded_layer),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        // A deliberate re-apply: re-detect the sky against the
                        // CURRENT working grid (a changed adjustment means a new
                        // artifact key; the old one stays for the undo stack).
                        TextButton(
                            onClick = { sweepPendingDrafts(); selectSky(skySelection.skyId, freshDetection = true) },
                            enabled = !exporting && !aiBusy,
                        ) {
                            Text(stringResource(R.string.editor_ai_sky_redetect))
                        }
                    }

                    // ---- Bokeh: estimate the depth once, then pure re-renders ----
                    Text(
                        text = stringResource(R.string.editor_section_ai_bokeh),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(
                            selected = committed.bokeh == null,
                            onClick = { sweepPendingDrafts(); commit(committed.copy(bokeh = null)) },
                            enabled = !exporting && !aiBusy,
                            label = { Text(stringResource(R.string.editor_ai_bokeh_none)) },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        AssistChip(
                            onClick = { sweepPendingDrafts(); runBokehEstimate() },
                            enabled = !exporting && !aiBusy,
                            label = {
                                Text(
                                    stringResource(
                                        if (committed.bokeh == null) {
                                            R.string.editor_ai_bokeh_estimate
                                        } else {
                                            R.string.editor_ai_bokeh_reestimate
                                        },
                                    ),
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    val bokehSelection = committed.bokeh
                    if (bokehSelection != null) {
                        val strengthLive = draft?.takeIf { it.first == "bokeh-strength" }?.second
                            ?: bokehSelection.blurStrengthPct
                        val strengthName = stringResource(R.string.editor_a11y_ai_bokeh_strength)
                        val strengthLabel = stringResource(R.string.editor_ai_bokeh_strength, strengthLive)
                        Text(
                            text = strengthLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = strengthLive.toFloat(),
                            onValueChange = { draft = "bokeh-strength" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "bokeh-strength" && committed.bokeh != null) {
                                        commit(
                                            committed.copy(
                                                bokeh = committed.bokeh!!.copy(
                                                    blurStrengthPct = d.second.coerceIn(1, 100),
                                                ),
                                            ),
                                        )
                                    }
                                }
                                draft = null
                            },
                            valueRange = 1f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = strengthName
                                stateDescription = strengthLabel
                            },
                        )
                        val focalLive = draft?.takeIf { it.first == "bokeh-focal" }?.second
                            ?: (bokehSelection.focalDepthNorm * 100).toInt()
                        val focalName = stringResource(R.string.editor_a11y_ai_bokeh_focal)
                        val focalLabel = stringResource(R.string.editor_ai_bokeh_focal, focalLive)
                        Text(
                            text = focalLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Slider(
                            value = focalLive.toFloat(),
                            onValueChange = { draft = "bokeh-focal" to it.toInt() },
                            onValueChangeFinished = {
                                draft?.let { d ->
                                    if (d.first == "bokeh-focal" && committed.bokeh != null) {
                                        commit(
                                            committed.copy(
                                                bokeh = committed.bokeh!!.copy(
                                                    focalDepthNorm = d.second.coerceIn(0, 100) / 100.0,
                                                ),
                                            ),
                                        )
                                    }
                                }
                                draft = null
                            },
                            valueRange = 0f..100f,
                            enabled = !exporting,
                            modifier = Modifier.semantics {
                                contentDescription = focalName
                                stateDescription = focalLabel
                            },
                        )
                        if (bokehSelection.depthRef.isNotEmpty() &&
                            !activity.aiStore.contains(EditAiStore.ArtifactKind.DEPTH, bokehSelection.depthRef)
                        ) {
                            Text(
                                text = stringResource(R.string.editor_ai_degraded_layer),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }

                    // ---- Face retouch: OPT-IN (ADR-0010 section 5) ----
                    // Hidden entirely under the permanent kill-switch; before a
                    // grant the only entry is the enable chip that opens the
                    // first-use consent dialog (what it does / on-device only /
                    // no face data leaves the device). Under consent-off the
                    // STAGE refuses even with a restored sidecar --- the layer
                    // stays verbatim in the recipe, the stage skips.
                    if (retouchConsent != RetouchConsent.PERMANENTLY_DISABLED) {
                        Text(
                            text = stringResource(R.string.editor_section_ai_retouch),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (retouchConsent == RetouchConsent.GRANTED) {
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                FilterChip(
                                    selected = committed.retouch == null,
                                    onClick = { sweepPendingDrafts(); commit(committed.copy(retouch = null)) },
                                    enabled = !exporting && !aiBusy,
                                    label = { Text(stringResource(R.string.editor_ai_retouch_none)) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                AssistChip(
                                    onClick = { sweepPendingDrafts(); runRetouchDetect() },
                                    enabled = !exporting && !aiBusy,
                                    label = { Text(stringResource(R.string.editor_ai_retouch_detect)) },
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            val retouchSelection = committed.retouch
                            if (retouchSelection != null) {
                                val smoothingLive = draft?.takeIf { it.first == "retouch-smoothing" }?.second
                                    ?: retouchSelection.smoothingPct
                                val smoothingName = stringResource(R.string.editor_a11y_ai_retouch_smoothing)
                                val smoothingLabel = stringResource(
                                    R.string.editor_ai_retouch_smoothing,
                                    smoothingLive,
                                )
                                Text(
                                    text = smoothingLabel,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Slider(
                                    value = smoothingLive.toFloat(),
                                    onValueChange = { draft = "retouch-smoothing" to it.toInt() },
                                    onValueChangeFinished = {
                                        draft?.let { d ->
                                            if (d.first == "retouch-smoothing" && committed.retouch != null) {
                                                commit(
                                                    committed.copy(
                                                        retouch = committed.retouch!!.copy(
                                                            smoothingPct = d.second.coerceIn(0, 100),
                                                        ),
                                                    ),
                                                )
                                            }
                                        }
                                        draft = null
                                    },
                                    valueRange = 0f..100f,
                                    enabled = !exporting,
                                    modifier = Modifier.semantics {
                                        contentDescription = smoothingName
                                        stateDescription = smoothingLabel
                                    },
                                )
                                val eyesLive = draft?.takeIf { it.first == "retouch-eyes" }?.second
                                    ?: retouchSelection.eyeBrightenPct
                                val eyesName = stringResource(R.string.editor_a11y_ai_retouch_eyes)
                                val eyesLabel = stringResource(R.string.editor_ai_retouch_eyes, eyesLive)
                                Text(
                                    text = eyesLabel,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Slider(
                                    value = eyesLive.toFloat(),
                                    onValueChange = { draft = "retouch-eyes" to it.toInt() },
                                    onValueChangeFinished = {
                                        draft?.let { d ->
                                            if (d.first == "retouch-eyes" && committed.retouch != null) {
                                                commit(
                                                    committed.copy(
                                                        retouch = committed.retouch!!.copy(
                                                            eyeBrightenPct = d.second.coerceIn(0, 100),
                                                        ),
                                                    ),
                                                )
                                            }
                                        }
                                        draft = null
                                    },
                                    valueRange = 0f..100f,
                                    enabled = !exporting,
                                    modifier = Modifier.semantics {
                                        contentDescription = eyesName
                                        stateDescription = eyesLabel
                                    },
                                )
                                val slimLive = draft?.takeIf { it.first == "retouch-slim" }?.second
                                    ?: retouchSelection.faceSlimPct
                                val slimName = stringResource(R.string.editor_a11y_ai_retouch_slim)
                                val slimLabel = stringResource(R.string.editor_ai_retouch_slim, slimLive)
                                Text(
                                    text = slimLabel,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Slider(
                                    value = slimLive.toFloat(),
                                    onValueChange = { draft = "retouch-slim" to it.toInt() },
                                    onValueChangeFinished = {
                                        draft?.let { d ->
                                            if (d.first == "retouch-slim" && committed.retouch != null) {
                                                commit(
                                                    committed.copy(
                                                        retouch = committed.retouch!!.copy(
                                                            faceSlimPct = d.second.coerceIn(0, 100),
                                                        ),
                                                    ),
                                                )
                                            }
                                        }
                                        draft = null
                                    },
                                    valueRange = 0f..100f,
                                    enabled = !exporting,
                                    modifier = Modifier.semantics {
                                        contentDescription = slimName
                                        stateDescription = slimLabel
                                    },
                                )
                                if (retouchSelection.boxesRef.isNotEmpty() &&
                                    !activity.aiStore.contains(
                                        EditAiStore.ArtifactKind.BOXES,
                                        retouchSelection.boxesRef,
                                    )
                                ) {
                                    Text(
                                        text = stringResource(R.string.editor_ai_degraded_layer),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        } else {
                            // Default-off: the single opt-in entry. A deny keeps
                            // it (the next use re-asks); the never is the
                            // Settings kill-switch.
                            AssistChip(
                                onClick = { retouchConsentDialog = true },
                                enabled = !exporting,
                                label = { Text(stringResource(R.string.editor_ai_retouch_enable)) },
                            )
                        }

                        // The first-use consent dialog (ADR-0010 section 5): the
                        // text states what the model does, that it runs on-device
                        // only, and that no face data leaves the device. A grant
                        // persists per install; a deny re-asks on the next use.
                        if (retouchConsentDialog && retouchConsent == RetouchConsent.NOT_GRANTED) {
                            AlertDialog(
                                onDismissRequest = { retouchConsentDialog = false },
                                title = { Text(stringResource(R.string.editor_ai_retouch_consent_title)) },
                                text = { Text(stringResource(R.string.editor_ai_retouch_consent_body)) },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            val next = RetouchConsentMachine.onGrant(retouchConsent)
                                            if (activity.retouchConsentStore.save(next)) {
                                                retouchConsent = next
                                            }
                                            retouchConsentDialog = false
                                        },
                                    ) {
                                        Text(stringResource(R.string.editor_ai_retouch_consent_grant))
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            // A deny stays NOT_GRANTED (the next
                                            // use re-asks); nothing persists.
                                            retouchConsent = RetouchConsentMachine.onDeny(retouchConsent)
                                            retouchConsentDialog = false
                                        },
                                    ) {
                                        Text(stringResource(R.string.editor_ai_retouch_consent_deny))
                                    }
                                },
                            )
                        }
                    }
                }
                PhotoScreen.Color -> {

                    // ---- Per-channel HSL: band selector + three sliders ----
                    Text(
                        text = stringResource(R.string.editor_section_hsl),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        HSL_BANDS.forEach { (name, _) ->
                            FilterChip(
                                selected = selectedBand == name,
                                onClick = { selectedBand = name },
                                // r47 ux fix: the band chips stayed live during an
                                // export while every other control disables --- one
                                // consistent frozen state.
                                enabled = !exporting,
                                label = { Text(stringResource(bandNameRes(name))) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    val band = HSL_BANDS.first { it.first == selectedBand }.second(committed.hslBands)
                    val bandLabel = stringResource(bandNameRes(selectedBand))
                    HslBandSlider(
                        name = stringResource(
                            R.string.editor_a11y_band_control,
                            bandLabel,
                            stringResource(R.string.editor_a11y_hue),
                        ),
                        label = stringResource(R.string.editor_hsl_hue, band.hue),
                        value = band.hue.toFloat(),
                        range = -180f..180f,
                        enabled = !exporting,
                        onCommit = { v ->
                            commit(
                                committed.copy(
                                    hslBands = committed.hslBands.withBand(
                                        selectedBand,
                                        band.copy(hue = v),
                                    ),
                                ),
                            )
                        },
                    )
                    HslBandSlider(
                        name = stringResource(
                            R.string.editor_a11y_band_control,
                            bandLabel,
                            stringResource(R.string.editor_a11y_saturation),
                        ),
                        label = stringResource(R.string.editor_hsl_saturation, band.saturation),
                        value = band.saturation.toFloat(),
                        range = -100f..100f,
                        enabled = !exporting,
                        onCommit = { v ->
                            commit(
                                committed.copy(
                                    hslBands = committed.hslBands.withBand(
                                        selectedBand,
                                        band.copy(saturation = v),
                                    ),
                                ),
                            )
                        },
                    )
                    HslBandSlider(
                        name = stringResource(
                            R.string.editor_a11y_band_control,
                            bandLabel,
                            stringResource(R.string.editor_a11y_luminance),
                        ),
                        label = stringResource(R.string.editor_hsl_luminance, band.luminance),
                        value = band.luminance.toFloat(),
                        range = -100f..100f,
                        enabled = !exporting,
                        onCommit = { v ->
                            commit(
                                committed.copy(
                                    hslBands = committed.hslBands.withBand(
                                        selectedBand,
                                        band.copy(luminance = v),
                                    ),
                                ),
                            )
                        },
                    )
                }
                PhotoScreen.Main -> Unit
            }
            // ---- The value tools' session Reset (the video footer's
            // Reset, adapted honestly): the photo tools commit LIVE on
            // release, so there is no draft to Apply and deliberately no
            // dead Apply button --- Reset reverts the tool session as ONE
            // undoable commit against the entry snapshot. ----
            if (screen in PHOTO_VALUE_TOOLS) {
                val entry = entryRecipe
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = { entry?.let { commit(it) } },
                        enabled = !exporting && entry != null && committed != entry,
                    ) {
                        Text(stringResource(R.string.editor_v2_reset))
                    }
                }
            }

            // ---- The always-visible honesty line (the r54 chassis's Main
            // home: the export decision surface; tool screens don't repeat
            // it) ----
            if (screen == PhotoScreen.Main) {
                Text(
                    text = stringResource(
                        R.string.editor_policy_line,
                        policyLabel(
                            ExportPolicyChoice.effectivePolicy(policy, exportChoice?.metadataPolicy),
                        ),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Start,
                )
            }
        }

        // ---- The r54 main-screen tab bar (full-bleed: outside the
        // controls' padding, docked below the canvas column) ----
        if (screen == PhotoScreen.Main) {
            PhotoTabBar(
                current = screen,
                enabled = !exporting,
                onSelect = { enterTool(it) },
            )
        }
    }
}

/**
 * The r54 tool-screen header: back on the left, the tool's title (the
 * heading anchor), and the confirm check on the right --- the video
 * chassis's header shape on the photo surface's r53 theme roles. Both
 * actions sweep-and-exit honestly: the photo tools commit LIVE on
 * release, so there is no draft to apply or discard --- the check is the
 * chassis's signature, not a fake apply.
 */
@Composable
private fun PhotoToolHeader(
    title: String,
    backA11y: String,
    confirmA11y: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.semantics { contentDescription = backA11y },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        IconButton(
            onClick = onConfirm,
            modifier = Modifier.semantics { contentDescription = confirmA11y },
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The r54 bottom tab bar: the ten tools, horizontally scrollable (the
 * video bar's six fit a row; ten do not), active = the accent + the
 * underline, inactive = the secondary tint --- the video chassis's bar
 * construction on the photo surface's theme roles. The Main screen is
 * never a tab; it is the chassis the tabs navigate FROM.
 */
@Composable
private fun PhotoTabBar(
    current: PhotoScreen,
    enabled: Boolean,
    onSelect: (PhotoScreen) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState()),
    ) {
        for (tab in PhotoScreen.entries) {
            if (tab == PhotoScreen.Main) continue
            val selected = tab == current
            val tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(
                modifier = Modifier
                    .widthIn(min = 64.dp)
                    .clickable(enabled = enabled) { onSelect(tab) }
                    .padding(top = 8.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    painter = painterResource(tab.iconRes),
                    contentDescription = null,
                    tint = tint,
                )
                Text(
                    text = stringResource(tab.labelRes),
                    color = tint,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun HslBandSlider(
    name: String,
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var dragValue by remember(label) { mutableStateOf<Float?>(null) }
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = dragValue ?: value,
        onValueChange = { dragValue = it },
        onValueChangeFinished = {
            dragValue?.let { onCommit(it.toInt()) }
            dragValue = null
        },
        valueRange = range,
        enabled = enabled,
        // r32 a11y: the band control announces "BAND: CONTROL" as its name
        // and the formatted label (with the value) as its state.
        modifier = Modifier.semantics {
            contentDescription = name
            stateDescription = label
        },
    )
}

private fun bandNameRes(name: String): Int = when (name) {
    "red" -> R.string.editor_band_red
    "orange" -> R.string.editor_band_orange
    "yellow" -> R.string.editor_band_yellow
    "green" -> R.string.editor_band_green
    "aqua" -> R.string.editor_band_aqua
    "blue" -> R.string.editor_band_blue
    "purple" -> R.string.editor_band_purple
    else -> R.string.editor_band_magenta
}

/** The bundled look's display name (r33); ids are the BundledLuts registry ids. */
private fun lookNameRes(id: String): Int = when (id) {
    "warm-film" -> R.string.editor_filter_warm_film
    "cool-chrome" -> R.string.editor_filter_cool_chrome
    "silver-mono" -> R.string.editor_filter_silver_mono
    "matte-fade" -> R.string.editor_filter_matte_fade
    "vivid-punch" -> R.string.editor_filter_vivid_punch
    else -> R.string.editor_filter_none
}

/**
 * The r34 swatch palette: fixed, named, TalkBack-labelled (one of N radio
 * semantics). ARGB Longs; the layer keeps the exact value it picked.
 */
private val TEXT_COLORS: List<Pair<Long, Int>> = listOf(
    0xFFFFFFFFL to R.string.editor_color_white,
    0xFF111111L to R.string.editor_color_black,
    0xFFF44336L to R.string.editor_color_red,
    0xFFFF9800L to R.string.editor_color_orange,
    0xFFFDD835L to R.string.editor_color_yellow,
    0xFF43A047L to R.string.editor_color_green,
    0xFF00ACC1L to R.string.editor_color_cyan,
    0xFF1E88E5L to R.string.editor_color_blue,
)

/** One color swatch: a bordered circle with radio selection semantics. */
@Composable
private fun ColorSwatch(
    colorArgb: Long,
    selected: Boolean,
    name: String,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    // A local with a distinct name: inside the semantics {} lambda the
    // receiver's own `selected` property shadows this parameter, so an
    // unqualified `selected = selected` reads as self-assignment.
    val isSelected = selected
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            // r47 ux fix: 36dp was under the 48dp touch-target floor
            // (design-tokens.md section 2.5, WCAG 2.5.5); the swatch circle
            // grows to meet it.
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(colorArgb.toInt()))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            // toggleable is not on this foundation build's API surface
            // (unresolved at the r34 head's first compile); clickable +
            // explicit semantics carries the same a11y contract --- the
            // repo-wide clickable convention, role + selected stated here.
            .clickable(
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { onPick() },
            )
            .semantics {
                this.selected = isSelected
                contentDescription = name
            },
    )
}

@Composable
private fun policyLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.PRESERVE -> stringResource(R.string.editor_policy_preserve)
    MetadataPolicy.STRIP_LOCATION -> stringResource(R.string.editor_policy_strip_location)
    MetadataPolicy.STRIP_ALL -> stringResource(R.string.editor_policy_strip_all)
}
