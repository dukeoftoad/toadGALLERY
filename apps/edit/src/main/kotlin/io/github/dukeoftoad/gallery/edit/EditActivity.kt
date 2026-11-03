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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * toadGALLERY Edit --- the contract launch activity (Issue #14 gate
 * prototype; spec `docs/architecture/editor-contract.md`).
 *
 * Round trip proved end to end:
 *  1. LAUNCH: the gallery sends the v1 contract extras with a read-only
 *     URI grant; this activity validates them through the SHARED
 *     [EditRequestValidator] (one truth, both sides), falling back to the
 *     ClipData item for the source URI per the spec.
 *  2. MODIFY: a real edit session --- live brightness + contrast LUT over
 *     the decoded preview pixels, left rotation, undo/redo over the
 *     committed parameter history ([EditSessionState], [EditSessionEngine]).
 *     Nothing is written anywhere during the session (contract 4.1/4.2).
 *  3. EXPORT (Save copy): full-resolution decode -> adjust -> compress
 *     JPEG q95 -> MediaStore INSERT of a NEW row (IS_PENDING on Q+, cleared
 *     after the bytes land; a failed write CANCELS the pending row so no
 *     partial entry survives) -> EXIF policy applied with
 *     androidx.exifinterface -> RESULT_OK with EXTRA_OUTPUT_URI and the
 *     applied policy echoed. The source URI is read-only by construction:
 *     it is never opened for write, never updated, never deleted.
 *  4. TERMINAL STATES: RESULT_OK / RESULT_CANCELED (back, with or without
 *     edits) / RESULT_ERROR with the structured codes --- the pre-compose
 *     validation failures answer before any UI exists.
 *
 * Zero network: the module's build-time gate fails if INTERNET ever rides
 * into the merged manifest, and the activity opens no sockets itself.
 *
 * Threading note: the export pipeline runs in an activity-scoped coroutine
 * scope that OUTLIVES the composition --- a user closing the activity
 * mid-export does not strand a half-written row; the pipeline always
 * answers a terminal state or cancels its pending row, and the gallery
 * refreshes through MediaStore observation either way (the contract's
 * "no rescan" property).
 */
class EditActivity : ComponentActivity() {

    private val exportScope = CoroutineScope(Dispatchers.IO + Job())

    /** The validated request; non-null after onCreate validation passed. */
    internal lateinit var request: io.github.dukeoftoad.gallery.edit.contract.EditRequest
        private set

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

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    EditorContent(activity = this@EditActivity)
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
    internal fun startExport(parameters: EditParameters, policy: MetadataPolicy) {
        exportScope.launch { runExport(parameters, policy) }
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
     * The export pipeline. IO dispatcher; on ANY failure the pending row is
     * cancelled (Q+) and RESULT_ERROR answers --- never a partial entry.
     */
    private suspend fun runExport(
        parameters: EditParameters,
        policy: MetadataPolicy,
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

            // 2. Adjust (LUT) + rotate.
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            val lut = PixelAdjuster.buildLut(parameters.brightness, parameters.contrast)
            PixelAdjuster.applyTo(pixels, lut)
            val rotated = PixelAdjuster.rotateQuarterTurns(pixels, width, height, parameters.quarterTurns)
            val adjusted = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            adjusted.setPixels(rotated.pixels, 0, rotated.width, 0, 0, rotated.width, rotated.height)

            // 3. Insert the NEW MediaStore row (IS_PENDING on Q+).
            val displayName = ExportPlan.displayNameFor(sourceDisplayName(sourceUri))
            val collection = if (api29Plus) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                ExportPlan.insertValues(displayName, api29Plus).forEach { (key, value) ->
                    put(key, value)
                }
            }
            val inserted = contentResolver.insert(collection, values)
                ?: throw ExportException(EditContract.ERROR_IO, "MediaStore refused the insert")
            outputUri = inserted

            // 4. Compress the JPEG into the pending row.
            contentResolver.openOutputStream(inserted)?.use { out ->
                if (!adjusted.compress(Bitmap.CompressFormat.JPEG, ExportPlan.JPEG_QUALITY, out)) {
                    throw ExportException(EditContract.ERROR_IO, "JPEG compression failed")
                }
                out.flush()
            } ?: throw ExportException(EditContract.ERROR_IO, "cannot open the output stream")

            // 5. Apply the EXIF metadata policy exactly (contract 4.4).
            applyMetadataPolicy(sourceUri, inserted, policy)

            // 6. Finalize (clear IS_PENDING) and answer OK.
            if (api29Plus) {
                val finalize = ContentValues().apply {
                    ExportPlan.finalizeValues(api29Plus).forEach { (key, value) -> put(key, value) }
                }
                contentResolver.update(inserted, finalize, null, null)
            }
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
                // Best-effort: the pending row is app-owned; never mask the
                // export's own error with a cleanup failure.
            }
        }
    }

    /**
     * Copies the source EXIF onto the saved copy per the policy: PRESERVE
     * copies every tag, STRIP_LOCATION nulls the GPS tags, STRIP_ALL writes
     * nothing at all. Runs through androidx.exifinterface (ADR-0003), on a
     * read-write file descriptor (API 24+; the minSdk is 26 so the path is
     * always live).
     */
    private fun applyMetadataPolicy(
        sourceUri: String,
        outputUri: Uri,
        policy: MetadataPolicy,
    ) {
        if (policy == MetadataPolicy.STRIP_ALL) return // nothing rides onto the copy
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
private fun EditorContent(activity: EditActivity) {
    val policy = activity.request.metadataPolicy
    val engine = remember { EditSessionEngine(EditParameters()) }

    var uiState by remember { mutableStateOf(engine.initial) }
    // Slider drafts: live preview while dragging; committed to the undo
    // stack on release (onValueChangeFinished) --- one undo step per gesture.
    var brightnessDraft by remember { mutableStateOf<Float?>(null) }
    var contrastDraft by remember { mutableStateOf<Float?>(null) }

    val params = uiState.params
    val effectiveBrightness = brightnessDraft?.toInt() ?: params.brightness
    val effectiveContrast = contrastDraft?.toInt() ?: params.contrast

    var previewBase by remember { mutableStateOf<Bitmap?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExportAfterPermission by remember { mutableStateOf(false) }

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

    // Live adjustment: re-applies whenever the base preview, the committed
    // parameters, or a drag draft changes.
    LaunchedEffect(previewBase, params, effectiveBrightness, effectiveContrast) {
        val base = previewBase ?: return@LaunchedEffect
        previewImage = withContext(Dispatchers.Default) {
            val width = base.width
            val height = base.height
            val pixels = IntArray(width * height)
            base.getPixels(pixels, 0, width, 0, 0, width, height)
            PixelAdjuster.applyTo(
                pixels,
                PixelAdjuster.buildLut(effectiveBrightness, effectiveContrast),
            )
            val rotated = PixelAdjuster.rotateQuarterTurns(pixels, width, height, params.quarterTurns)
            val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
            out.setPixels(rotated.pixels, 0, rotated.width, 0, 0, rotated.width, rotated.height)
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

    // The exporting flag drives the pipeline through the activity's
    // outliving scope.
    LaunchedEffect(exporting) {
        if (exporting) {
            activity.startExport(params, policy)
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
                    onClick = { requestSave() },
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

        // ---- Controls ----
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_brightness, effectiveBrightness),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = effectiveBrightness.toFloat(),
                onValueChange = { value -> brightnessDraft = value },
                onValueChangeFinished = {
                    brightnessDraft?.let { draft ->
                        uiState = engine.commit(params.copy(brightness = draft.toInt()))
                    }
                    brightnessDraft = null
                },
                valueRange = PixelAdjuster.MIN.toFloat()..PixelAdjuster.MAX.toFloat(),
                enabled = !exporting,
            )
            Text(
                text = stringResource(R.string.editor_contrast, effectiveContrast),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = effectiveContrast.toFloat(),
                onValueChange = { value -> contrastDraft = value },
                onValueChangeFinished = {
                    contrastDraft?.let { draft ->
                        uiState = engine.commit(params.copy(contrast = draft.toInt()))
                    }
                    contrastDraft = null
                },
                valueRange = PixelAdjuster.MIN.toFloat()..PixelAdjuster.MAX.toFloat(),
                enabled = !exporting,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        uiState = engine.commit(params.copy(quarterTurns = params.quarterTurns + 1))
                    },
                    enabled = !exporting,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.editor_rotate_left),
                        tint = Color.White,
                    )
                }
                Text(
                    text = stringResource(R.string.editor_policy_line, policyLabel(policy)),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun policyLabel(policy: MetadataPolicy): String = when (policy) {
    MetadataPolicy.PRESERVE -> stringResource(R.string.editor_policy_preserve)
    MetadataPolicy.STRIP_LOCATION -> stringResource(R.string.editor_policy_strip_location)
    MetadataPolicy.STRIP_ALL -> stringResource(R.string.editor_policy_strip_all)
}
