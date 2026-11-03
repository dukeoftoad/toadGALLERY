package io.github.dukeoftoad.gallery.edit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dukeoftoad.gallery.edit.contract.EditContract
import io.github.dukeoftoad.gallery.edit.contract.EditRequestValidator
import io.github.dukeoftoad.gallery.edit.contract.MetadataPolicy
import io.github.dukeoftoad.gallery.edit.contract.RawExtras
import io.github.dukeoftoad.gallery.edit.contract.RequestValidation
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * toadGALLERY Edit --- the contract launch activity (r29 gate; r30 wires
 * the full v1 suite).
 *
 * Round trip, end to end:
 *  1. LAUNCH: the gallery sends the v1 contract extras with a read-only
 *     URI grant; validation runs through the SHARED
 *     [EditRequestValidator] (one truth, both sides), with the ClipData
 *     item as the documented [sourceUriFromExtra] fallback.
 *  2. SESSION (r30): the full v1 parameter set --- the ten adjustments,
 *     the 8-band HSL model, and the geometry (quarter turns, straighten,
 *     flips; crop + perspective ride the recipe/pipeline but their
 *     interactive overlays are a later round) --- with live preview
 *     through the ordered [EditPipeline] and undo/redo over the committed
 *     history ([EditSessionState]). If a sidecar recipe exists for the
 *     source (a previous export), the session OPENS with it: edits are
 *     re-editable across sessions ([SidecarStore], ADR-0011).
 *  3. EXPORT (Save copy): the format/quality dialog (JPEG/PNG/WebP; PNG
 *     lossless) -> full-resolution decode -> [EditPipeline] -> compress ->
 *     MediaStore INSERT of a NEW row (IS_PENDING on Q+; a failed write
 *     CANCELS the pending row --- no partial entry survives) -> the EXIF
 *     metadata policy -> RESULT_OK + the applied policy echoed -> the
 *     recipe saved to the sidecar store. The source URI is read-only by
 *     construction: never opened for write, never updated, never deleted.
 *  4. TERMINAL STATES: RESULT_OK / RESULT_CANCELED / RESULT_ERROR with
 *     the structured codes.
 *
 * Zero network: the module's build-time gate fails if INTERNET ever rides
 * into the merged manifest, and the activity opens no sockets itself.
 */
class EditActivity : ComponentActivity() {

    /** The validated request; non-null after onCreate validation passed. */
    internal lateinit var request: io.github.dukeoftoad.gallery.edit.contract.EditRequest
        private set

    /** The sidecar store; created once. */
    private lateinit var sidecars: SidecarStore

    private val exportScope = CoroutineScope(Dispatchers.IO + Job())

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
            // Spec section 2: EXTRA_SOURCE_URI is a Uri (Parcelable); the
            // ClipData item is the documented fallback.
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

        // ---- The recipe sidecar: re-editable sessions (ADR-0011). A
        // previous export's recipe seeds the session; a missing or corrupt
        // sidecar is just a fresh session (never a crash). ----
        sidecars = SidecarStore.inApp(this)
        val initial = sidecars.loadRecipe(request.sourceUri) ?: EditParameters()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    EditorContent(activity = this@EditActivity, initialParameters = initial)
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
    internal fun startExport(
        parameters: EditParameters,
        policy: MetadataPolicy,
        format: ExportPlan.ExportFormat,
        quality: Int,
    ) {
        exportScope.launch { runExport(parameters, policy, format, quality) }
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

    /** The source's mime type (for the follow-source export default). */
    internal fun sourceMime(sourceUri: String): String? = try {
        contentResolver.getType(Uri.parse(sourceUri))
    } catch (_: RuntimeException) {
        null
    }

    /**
     * The export pipeline. IO dispatcher; on ANY failure the pending row is
     * cancelled (Q+) and RESULT_ERROR answers --- never a partial entry.
     * On success the recipe is saved to the sidecar store (the re-editable
     * promise) before RESULT_OK.
     */
    private suspend fun runExport(
        parameters: EditParameters,
        policy: MetadataPolicy,
        format: ExportPlan.ExportFormat,
        quality: Int,
    ): Unit = withContext(Dispatchers.IO) {
        val api29Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val sourceUri = request.sourceUri
        var outputUri: Uri? = null
        try {
            // 1. Full-resolution decode.
            val source = decodeBitmap(sourceUri, Int.MAX_VALUE)
                ?: throw ExportException(
                    EditContract.ERROR_UNSUPPORTED,
                    "cannot decode the source at full resolution",
                )

            // 2. The ordered pipeline (adjust + geometry).
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val pipelined = EditPipeline.apply(pixels, width, height, parameters)
            val adjusted = Bitmap.createBitmap(
                pipelined.width, pipelined.height, Bitmap.Config.ARGB_8888,
            )
            adjusted.setPixels(
                pipelined.pixels, 0, pipelined.width, 0, 0, pipelined.width, pipelined.height,
            )

            // 3. Insert the NEW MediaStore row (IS_PENDING on Q+), with the
            // chosen format's mime and extension.
            val displayName = ExportPlan.displayNameFor(sourceDisplayName(sourceUri), format)
            val collection = if (api29Plus) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValuesShim()
            for ((key, value) in ExportPlan.insertValuesForFormat(displayName, format, api29Plus)) {
                when (value) {
                    is String -> values.put(key, value)
                    is Int -> values.put(key, value)
                }
            }
            val inserted = contentResolver.insert(collection, values.backing)
                ?: throw ExportException(EditContract.ERROR_IO, "MediaStore refused the insert")
            outputUri = inserted

            // 4. Compress into the pending row with the chosen format+quality.
            val compressFormat = when (format) {
                ExportPlan.ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
                ExportPlan.ExportFormat.PNG -> Bitmap.CompressFormat.PNG
                ExportPlan.ExportFormat.WEBP -> Bitmap.CompressFormat.WEBP
            }
            val effectiveQuality = ExportPlan.qualityFor(format, quality)
            contentResolver.openOutputStream(inserted)?.use { out ->
                if (!adjusted.compress(compressFormat, effectiveQuality, out)) {
                    throw ExportException(EditContract.ERROR_IO, "compression failed")
                }
                out.flush()
            } ?: throw ExportException(EditContract.ERROR_IO, "cannot open the output stream")

            // 5. Apply the EXIF metadata policy exactly (contract 4.4).
            applyMetadataPolicy(sourceUri, inserted, policy)

            // 6. Finalize (clear IS_PENDING).
            if (api29Plus) {
                val finalize = ContentValuesShim()
                for ((key, value) in ExportPlan.finalizeValues(api29Plus)) {
                    when (value) {
                        is String -> finalize.put(key, value)
                        is Int -> finalize.put(key, value)
                    }
                }
                contentResolver.update(inserted, finalize.backing, null, null)
            }

            // 7. Persist the recipe sidecar (the re-editable promise) BEFORE
            // reporting success --- a failure here still answers OK (the
            // export itself succeeded; the sidecar is best-effort).
            sidecars.save(sourceUri, EditRecipe.toJson(parameters, sourceUri))

            withContext(Dispatchers.Main) {
                finishWithSavedCopy(inserted, policy)
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
            for (tag in sourceExif.tagNames) {
                when (MetadataPolicyApplier.decide(policy, tag)) {
                    MetadataPolicyApplier.TagDecision.Copy ->
                        target.setAttribute(tag, sourceExif.getAttribute(tag))
                    MetadataPolicyApplier.TagDecision.Strip ->
                        target.setAttribute(tag, null)
                }
            }
            target.saveAttributes()
        } ?: throw ExportException(EditContract.ERROR_IO, "cannot reopen the copy for EXIF")
    }

    /** The internal failure type carrying a structured contract error code. */
    private class ExportException(val errorCode: String, message: String) : RuntimeException(message)

    /**
     * The typed ContentValues builder: [ExportPlan] speaks Map<String, Any>
     * (pure, JVM-testable); ContentValues has no Any overload --- this
     * shim branches on the two value types the plan emits (String, Int).
     */
    private class ContentValuesShim {
        val backing = android.content.ContentValues()
        fun put(key: String, value: String) { backing.put(key, value) }
        fun put(key: String, value: Int) { backing.put(key, value) }
    }
}

// ---------------------------------------------------------------------------
// Compose UI
// ---------------------------------------------------------------------------

/** Maximum preview dimension --- the session edits a bounded preview; export re-decodes full. */
private const val PREVIEW_MAX_DIMENSION: Int = 2048

/** One observable editor snapshot: parameters + the undo/redo affordance states. */
data class EditorUiState(
    val params: EditParameters,
    val canUndo: Boolean,
    val canRedo: Boolean,
)

/**
 * The session engine bridging the pure [EditSessionState] stack to Compose:
 * every commit/undo/redo returns a fresh [EditorUiState] the UI observes.
 */
private class EditSessionEngine(initial: EditParameters) {
    private val stack = EditSessionState(initial)

    val initial: EditorUiState = EditorUiState(stack.current, stack.canUndo, stack.canRedo)

    fun commit(params: EditParameters): EditorUiState {
        stack.commit(params)
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

@Composable
private fun EditorContent(
    activity: EditActivity,
    initialParameters: EditParameters,
) {
    val policy = activity.request.metadataPolicy
    val engine = remember { EditSessionEngine(initialParameters) }

    var uiState by remember { mutableStateOf(engine.initial) }
    val params = uiState.params

    var previewBase by remember { mutableStateOf<Bitmap?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExportAfterPermission by remember { mutableStateOf(false) }

    // ---- The export dialog state (r30): format + quality. ----
    var showExportDialog by remember { mutableStateOf(false) }
    var resolvedExport by remember {
        mutableStateOf<Pair<ExportPlan.ExportFormat, Int>?>(null)
    }

    // First composition: decode the bounded preview; a decode failure is
    // the ERROR_UNSUPPORTED terminal state.
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

    // Live preview through the ordered pipeline.
    LaunchedEffect(previewBase, params) {
        val base = previewBase ?: return@LaunchedEffect
        previewImage = withContext(Dispatchers.Default) {
            val width = base.width
            val height = base.height
            val pixels = IntArray(width * height)
            base.getPixels(pixels, 0, width, 0, 0, width, height)
            val pipelined = EditPipeline.apply(pixels, width, height, params)
            val out = Bitmap.createBitmap(
                pipelined.width, pipelined.height, Bitmap.Config.ARGB_8888,
            )
            out.setPixels(
                pipelined.pixels, 0, pipelined.width, 0, 0, pipelined.width, pipelined.height,
            )
            out.asImageBitmap()
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

    // The exporting flag drives the pipeline through the activity's
    // outliving scope.
    LaunchedEffect(exporting) {
        if (exporting) {
            val (format, quality) = resolvedExport
                ?: Pair(ExportPlan.ExportFormat.JPEG, ExportPlan.JPEG_QUALITY)
            activity.startExport(params, policy, format, quality)
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
            TextButton(
                onClick = { uiState = engine.undo() },
                enabled = uiState.canUndo && !exporting,
            ) {
                Text(stringResource(R.string.editor_undo))
            }
            TextButton(
                onClick = { uiState = engine.redo() },
                enabled = uiState.canRedo && !exporting,
            ) {
                Text(stringResource(R.string.editor_redo))
            }
            if (exporting) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = Color.White,
                )
            } else {
                Button(
                    onClick = { showExportDialog = true },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text(stringResource(R.string.editor_save_copy))
                }
            }
        }

        // ---- Preview ----
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
            } else {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // ---- Controls (the v1 suite exposed in r30; crop/straighten/
        // perspective/HSL overlays are the next round --- their math and
        // recipe persistence are live in the pipeline already). ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AdjustSlider(
                labelRes = R.string.editor_brightness,
                value = params.brightness,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(brightness = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_contrast,
                value = params.contrast,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(contrast = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_exposure,
                value = params.exposure,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(exposure = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_highlights,
                value = params.highlights,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(highlights = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_shadows,
                value = params.shadows,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(shadows = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_saturation,
                value = params.saturation,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(saturation = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_temperature,
                value = params.temperature,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(temperature = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_tint,
                value = params.tint,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(tint = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_vignette,
                value = params.vignette,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(vignette = v)) },
            )
            AdjustSlider(
                labelRes = R.string.editor_sharpness,
                value = params.sharpness,
                enabled = !exporting,
                onCommit = { v -> uiState = engine.commit(params.copy(sharpness = v)) },
            )

            // ---- Geometry row: rotate / flip H / flip V ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        uiState = engine.commit(
                            params.copy(quarterTurns = (params.quarterTurns + 1) % 4),
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
                TextButton(
                    onClick = { uiState = engine.commit(params.copy(flipH = !params.flipH)) },
                    enabled = !exporting,
                ) {
                    Text(stringResource(if (params.flipH) R.string.editor_flip_h_on else R.string.editor_flip_h))
                }
                TextButton(
                    onClick = { uiState = engine.commit(params.copy(flipV = !params.flipV)) },
                    enabled = !exporting,
                ) {
                    Text(stringResource(if (params.flipV) R.string.editor_flip_v_on else R.string.editor_flip_v))
                }
                Text(
                    text = stringResource(R.string.editor_policy_line, policyLabel(policy)),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // ---- The export dialog (r30): format + quality, then save. ----
    if (showExportDialog) {
        ExportDialog(
            sourceMime = activity.sourceMime(activity.request.sourceUri),
            onConfirm = { format, quality ->
                resolvedExport = format to quality
                showExportDialog = false
                requestSave()
            },
            onDismiss = { showExportDialog = false },
        )
    }
}

/** One labelled adjustment slider: drafts live, commits on release (one undo step per gesture). */
@Composable
private fun AdjustSlider(
    labelRes: Int,
    value: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf<Float?>(null) }
    val shown = draft?.toInt() ?: value
    Column {
        Text(
            text = stringResource(labelRes, shown),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = shown.toFloat(),
            onValueChange = { v -> draft = v },
            onValueChangeFinished = {
                draft?.let { d -> onCommit(d.toInt()) }
                draft = null
            },
            valueRange = -100f..100f,
            enabled = enabled,
        )
    }
}

/** The export format+quality dialog. */
@Composable
private fun ExportDialog(
    sourceMime: String?,
    onConfirm: (ExportPlan.ExportFormat, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultFormat = remember(sourceMime) { ExportPlan.defaultFormatFor(sourceMime) }
    // null = follow source; otherwise the explicit pick.
    var picked by remember { mutableStateOf<ExportPlan.ExportFormat?>(null) }
    var quality by remember { mutableStateOf(95f) }
    val effectiveFormat = picked ?: defaultFormat
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.editor_export_format), style = MaterialTheme.typography.labelLarge)
                FormatOption(
                    label = stringResource(R.string.editor_export_follow_source, defaultFormat.name),
                    selected = picked == null,
                    onSelect = { picked = null },
                )
                for (format in ExportPlan.ExportFormat.entries) {
                    FormatOption(
                        label = format.name,
                        selected = picked == format,
                        onSelect = { picked = format },
                    )
                }
                Text(
                    stringResource(R.string.editor_export_heic_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (effectiveFormat != ExportPlan.ExportFormat.PNG) {
                    Text(
                        stringResource(R.string.editor_export_quality, quality.roundToInt()),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(
                        value = quality,
                        onValueChange = { q -> quality = q },
                        valueRange = 50f..100f,
                    )
                } else {
                    Text(
                        stringResource(R.string.editor_export_png_lossless),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(effectiveFormat, quality.roundToInt()) },
            ) {
                Text(stringResource(R.string.editor_save_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_export_cancel))
            }
        },
    )
}

@Composable
private fun FormatOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun policyLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.PRESERVE -> stringResource(R.string.editor_policy_preserve)
    MetadataPolicy.STRIP_LOCATION -> stringResource(R.string.editor_policy_strip_location)
    MetadataPolicy.STRIP_ALL -> stringResource(R.string.editor_policy_strip_all)
}
