package io.github.dukeoftoad.gallery.edit

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            MaterialTheme {
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
            val pipelined = EditPipeline.apply(pixels, width, height, export.recipe, look)
            val adjusted = Bitmap.createBitmap(
                pipelined.width, pipelined.height, Bitmap.Config.ARGB_8888,
            )
            adjusted.setPixels(
                pipelined.pixels, 0, pipelined.width, 0, 0, pipelined.width, pipelined.height,
            )

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
            try {
                sidecarStore.clear(sourceUri)
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

    val committed = uiState.recipe

    /** The recipe the preview renders: the committed one with any live draft applied. */
    fun effectiveRecipe(): EditRecipe {
        val d = draft ?: return committed
        val spec = ADJUST_SLIDERS.firstOrNull { it.key == d.first }
        return when {
            spec != null -> spec.set(committed, d.second)
            // r32: the straighten drag previews live too (it previously
            // waited for release, burning a per-tick re-render for nothing).
            d.first == "straighten" -> committed.copy(straightenDeg = d.second.toDouble())
            // r33: the intensity drag previews live, exactly like the
            // adjustment sliders (the look commits on release).
            d.first == "lut-intensity" -> {
                val selection = committed.lut
                if (selection != null) {
                    committed.copy(lut = selection.copy(intensity = d.second))
                } else {
                    committed
                }
            }
            else -> committed
        }
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
    // the loaded look set changes.
    LaunchedEffect(previewBase, committed, draft, looks) {
        val base = previewBase ?: return@LaunchedEffect
        val effective = effectiveRecipe()
        val displayRecipe = effective.copy(crop = CropRect(0.0, 0.0, 1.0, 1.0))
        val look = effective.lut?.let { looks[it.lutId] }
        previewImage = withContext(Dispatchers.Default) {
            val width = base.width
            val height = base.height
            val pixels = IntArray(width * height)
            base.getPixels(pixels, 0, width, 0, 0, width, height)
            val pipelined = EditPipeline.apply(pixels, width, height, displayRecipe, look)
            val out = Bitmap.createBitmap(pipelined.width, pipelined.height, Bitmap.Config.ARGB_8888)
            out.setPixels(pipelined.pixels, 0, pipelined.width, 0, 0, pipelined.width, pipelined.height)
            // The crop overlay rect rides the POST-geometry frame of the
            // same recipe (plan computed over the pipeline's own dims).
            previewCropRect = EditGeometryPlan.plan(effective, width, height).cropPixelRect
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
    val uiScope = rememberCoroutineScope()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ---- Top bar: close / undo / redo / save copy ----
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
                    tint = Color.White,
                )
            }
            Text(
                text = stringResource(R.string.editor_app_name),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { uiState = engine.undo() }, enabled = uiState.canUndo && !exporting) {
                Text(stringResource(R.string.editor_undo))
            }
            TextButton(onClick = { uiState = engine.redo() }, enabled = uiState.canRedo && !exporting) {
                Text(stringResource(R.string.editor_redo))
            }
            if (exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = Color.White,
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

        // ---- Preview (with the crop overlay rect) ----
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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

        // ---- The export sheet (format + quality) ----
        val choice = exportChoice
        if (choice != null && !exporting) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xEE101010))
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
                        color = Color.White,
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
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                // ---- The per-export metadata-policy choice (r31) ----
                // The launch request's policy is the privacy floor: options
                // that would strip LESS than the request are disabled (the
                // editor respects the requested policy exactly, contract 4.4).
                Text(
                    text = stringResource(R.string.editor_section_metadata),
                    color = Color.White,
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
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = stringResource(R.string.editor_metadata_note),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
                Button(onClick = { requestSave() }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.editor_save_copy))
                }
            }
        }

        // ---- Controls ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ---- Geometry row ----
            Text(
                text = stringResource(R.string.editor_section_geometry),
                color = Color.White,
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
                        tint = Color.White,
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
                color = Color.White,
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

            // ---- The adjustment suite ----
            Text(
                text = stringResource(R.string.editor_section_adjust),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            for (spec in ADJUST_SLIDERS) {
                val committedValue = spec.get(committed)
                val live = if (draft?.first == spec.key) draft!!.second else committedValue
                val labelText = stringResource(spec.label, live)
                val a11yName = stringResource(spec.a11yNameRes)
                Text(
                    text = labelText,
                    color = Color.White,
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

            // ---- Filters (r33: the bundled .cube looks at an intensity) ----
            // One None chip + one chip per look that PARSED; the preview and
            // the export resolve the selection through the same shared cache,
            // so what the user sees is what the pipeline exports. A selection
            // this build cannot resolve (a version-skewed sidecar) selects no
            // chip --- the tolerance contract, shown honestly.
            Text(
                text = stringResource(R.string.editor_section_filters),
                color = Color.White,
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
                    color = Color.White.copy(alpha = 0.7f),
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
                    color = Color.White,
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

            // ---- Per-channel HSL: band selector + three sliders ----
            Text(
                text = stringResource(R.string.editor_section_hsl),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                HSL_BANDS.forEach { (name, _) ->
                    FilterChip(
                        selected = selectedBand == name,
                        onClick = { selectedBand = name },
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

            // ---- The always-visible honesty line ----
            // Reflects the policy this export would apply: the pending
            // per-export choice resolved against the request's floor.
            Text(
                text = stringResource(
                    R.string.editor_policy_line,
                    policyLabel(
                        ExportPolicyChoice.effectivePolicy(policy, exportChoice?.metadataPolicy),
                    ),
                ),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Start,
            )
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
        color = Color.White,
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

@Composable
private fun policyLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.PRESERVE -> stringResource(R.string.editor_policy_preserve)
    MetadataPolicy.STRIP_LOCATION -> stringResource(R.string.editor_policy_strip_location)
    MetadataPolicy.STRIP_ALL -> stringResource(R.string.editor_policy_strip_all)
}
