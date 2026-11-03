package io.github.dukeoftoad.gallery.ui.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.data.MediaStoreSource
import io.github.dukeoftoad.gallery.ml.engine.MlEngine
import io.github.dukeoftoad.gallery.ml.engine.ModelManifest
import io.github.dukeoftoad.gallery.ml.engine.ModelProvisioner
import io.github.dukeoftoad.gallery.ml.index.ClipIndexScheduler
import io.github.dukeoftoad.gallery.ml.index.ClipIndexWorkState
import io.github.dukeoftoad.gallery.ml.index.FACE_INDEX_DIR_NAME
import io.github.dukeoftoad.gallery.ml.index.CLIP_INDEX_STATE_FILE_NAME
import io.github.dukeoftoad.gallery.ml.index.FaceIndexScheduler
import io.github.dukeoftoad.gallery.ml.index.FaceIndexWorkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The r22 AI-engine dogfood section's state (see [AiEngineViewModel]).
 *
 * Presence is re-derived from disk on every [AiEngineViewModel.refresh]
 * (receipt + file checks, the provisioner's own derivation) so the rows
 * never drift from what is actually installed --- including the pin-change
 * and partial-failure shapes the provisioner defines.
 */
data class AiDogfoodState(
    /** One action (download / delete / self-test) is in flight. */
    val busy: Boolean = false,
    val faceCapability: ModelProvisioner.CapabilityState =
        ModelProvisioner.CapabilityState.NotProvisioned,
    /** FACE assets in manifest order, each with its installed/missing truth. */
    val assets: List<AssetStatus> = emptyList(),
    /** The r26 SEMANTIC capability's provisioner state (Workstream 7). */
    val semanticCapability: ModelProvisioner.CapabilityState =
        ModelProvisioner.CapabilityState.NotProvisioned,
    /** SEMANTIC assets (CLIP image/text/vocab) in manifest order (r26). */
    val semanticAssets: List<AssetStatus> = emptyList(),
    /** Whether `libtoad_ml_jni.so` is present in THIS build (per-process fixed). */
    val engineLibraryPresent: Boolean = false,
    /** The last download/delete outcome, or null. */
    val note: Note? = null,
    /** The last self-test outcome, or null. */
    val selfTest: SelfTestResult? = null,
) {

    /** One model asset's presence row. */
    data class AssetStatus(val fileName: String, val installed: Boolean)

    /** Last download/delete action outcome. */
    sealed interface Note {
        /** Every FACE asset is receipt-verified installed. */
        object Downloaded : Note

        /** The provisioning run threw; [reason] is the failure message. */
        data class DownloadFailed(val reason: String) : Note

        /** invalidateCapability(FACE) ran; rows re-derived from disk. */
        object Deleted : Note

        /** Every SEMANTIC asset is receipt-verified installed (r26). */
        object SemanticDownloaded : Note

        /** The SEMANTIC provisioning run threw (r26). */
        data class SemanticDownloadFailed(val reason: String) : Note

        /** invalidateCapability(SEMANTIC) ran (r26). */
        object SemanticDeleted : Note
    }

    /** The engine self-test outcome (one JPEG, one inference, timed). */
    sealed interface SelfTestResult {
        /** No `libtoad_ml_jni.so` in this build --- the honest answer. */
        object LibraryMissing : SelfTestResult

        /** Library present but no JPEG exists on the device to test with. */
        object NoJpegFound : SelfTestResult

        /** "<file>: N faces in X ms --- engine OK". */
        data class Ok(val fileName: String, val faces: Int, val elapsedMs: Long) : SelfTestResult

        /** Init or inference failed; [message] is the engine's own text. */
        data class Failed(val message: String) : SelfTestResult
    }
}

/**
 * Screen-scoped state holder for the Settings "AI engine (r22 dogfood)"
 * section --- the maintainer's window into the ADR-0020 model-delivery
 * path on a real device. Screen-scoped (the `viewModel()` pattern the
 * recycle-bin section uses), because nothing else consumes this state
 * until the Phase 5 People/Magic-Search wiring lands; the process-wide
 * [ModelProvisioner] comes from [ToadGalleryApplication.modelProvisioner]
 * like every other app-level dependency (no DI framework, house pattern).
 *
 * Actions (all off the main thread; the provisioner and the engine are
 * blocking):
 *  - [downloadFaceModels]: `provision(FACE)` over the REAL
 *    [io.github.dukeoftoad.gallery.ml.engine.HttpModelDownloader] --- the
 *    single allowlisted host --- with the provisioner's per-asset progress
 *    callback re-deriving presence as each asset lands (live per-asset
 *    updates; byte-level progress is deliberately NOT added to keep r21's
 *    provisioner contract and its tests untouched);
 *  - [deleteFaceModels]: `invalidateCapability(FACE)`;
 *  - [runSelfTest]: the first eligible JPEG from the existing media
 *    listing ([MediaStoreSource.queryTimeline], newest first), one
 *    `nativeInit(modelsDir)` + `nativeDetectFacesJpeg` round-trip through
 *    [MlEngine], timed with the monotonic clock, session released after
 *    (the next self-test re-initializes --- the contract's re-init
 *    guarantee, dogfooded);
 *  - [startBackgroundIndex] (r24): hands the whole library to the
 *    WorkManager-backed background queue ([FaceIndexScheduler] ->
 *    `FaceIndexWorker`): revision-aware planning + bounded batches +
 *    retry/resume across process death, live progress collected from the
 *    queue state ([queueState]) --- the r23 12-photo dogfood slice this
 *    card replaces (the r22 model buttons and self-test above are
 *    untouched);
 *  - [cancelBackgroundIndex] (r24): cancels the chain;
 *  - [resetFaceIndex] (r24): cancels, closes the store and deletes the
 *    whole `face-index` directory --- ML store AND queue bookkeeping
 *    together, so neither can outlive the other (the reset mirror of
 *    [deleteFaceModels]);
 *  - [setThreshold] (r24): the clustering distance threshold the queue
 *    clusters at (upstream's recommended 0.24 by default, tuned through
 *    the card's slider, persisted in the `toad_settings` preferences);
 *  - [downloadSemanticModels]/[deleteSemanticModels] (r26, Workstream 7):
 *    the SAME provision/invalidate flow over the SEMANTIC capability
 *    (`provision(SEMANTIC)` / `invalidateCapability(SEMANTIC)`), driving
 *    the CLIP image/text/vocab assets the Magic Search round needs;
 *  - [startSemanticIndex]/[cancelSemanticIndex]/[resetSemanticIndex]
 *    (r26): the SEMANTIC background queue's lifecycle over
 *    [ClipIndexScheduler] (the r24 face queue's card, sibling edition:
 *    live progress, terminal outcome lines, and a reset whose scope the
 *    card copy states honestly --- the queue's OWN bookkeeping only;
 *    the store-level full reset remains the r24 Reset face index, which
 *    deletes the whole `ml-index.sqlite` directory both queues share).
 */
class AiEngineViewModel(app: Application) : AndroidViewModel(app) {

    private val provisioner = getApplication<ToadGalleryApplication>().modelProvisioner
    private val engine = MlEngine()
    private val source = MediaStoreSource(app)
    private val resolver = app.contentResolver
    // Construction only reads the in-memory library flag; the disk-derived
    // rows arrive with the first refresh() (the section's LaunchedEffect)
    // a frame later --- no IO on the main thread at ViewModel creation.
    private val _state = MutableStateFlow(AiDogfoodState(engineLibraryPresent = engine.available))
    val state: StateFlow<AiDogfoodState> = _state.asStateFlow()

    /** Re-derives presence from disk (entry refresh; preserves an in-flight action). */
    fun refresh() {
        viewModelScope.launch {
            val busy = _state.value.busy
            _state.value = withContext(Dispatchers.IO) { snapshot().copy(busy = busy) }
        }
    }

    /** Provisions the FACE capability from the allowlisted host (ADR-0020). */
    fun downloadFaceModels() {
        if (_state.value.busy) return
        // Claim single-flight synchronously (both taps land on the main
        // thread): a second action can never interleave with a running
        // provisioning pass over the same temp files.
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                val note = try {
                    // The per-asset callback (r21 contract) fires after each
                    // verified install; re-deriving the rows there gives the
                    // section live per-asset updates while the run continues.
                    provisioner.provision(ModelManifest.Capability.FACE) {
                        _state.value = snapshot().copy(busy = true)
                    }
                    AiDogfoodState.Note.Downloaded
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // ProvisionException carries "download failed for <file>" /
                    // "verification failed for <file>: ..."; anything else
                    // (e.g. the model dir cannot be created) is narrated too.
                    AiDogfoodState.Note.DownloadFailed(e.message ?: e.javaClass.simpleName)
                }
                snapshot().copy(note = note)
            }
        }
    }

    /** Removes every installed FACE asset (file + receipt entry). */
    fun deleteFaceModels() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                provisioner.invalidateCapability(ModelManifest.Capability.FACE)
                snapshot().copy(note = AiDogfoodState.Note.Deleted)
            }
        }
    }

    // ---- r26: the SEMANTIC model pack + the semantic background queue ----

    /**
     * The honest size label for the SEMANTIC download button and the
     * Magic Search cards: the pack's total, derived from the manifest
     * pin (never a hand-maintained copy) and formatted with the house
     * byte formatter.
     */
    val semanticModelsSize: String = io.github.dukeoftoad.gallery.data.DataSizeFormats.formatBytes(
        ModelManifest.forCapability(ModelManifest.Capability.SEMANTIC).sumOf { it.sizeBytes },
    )

    /** Provisions the SEMANTIC capability from the allowlisted host (ADR-0020; r26). */
    fun downloadSemanticModels() {
        if (_state.value.busy) return
        // Same synchronous single-flight claim as the FACE download: a
        // second action can never interleave with a provisioning pass
        // over the same temp files.
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                val note = try {
                    provisioner.provision(ModelManifest.Capability.SEMANTIC) {
                        _state.value = snapshot().copy(busy = true)
                    }
                    AiDogfoodState.Note.SemanticDownloaded
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AiDogfoodState.Note.SemanticDownloadFailed(
                        e.message ?: e.javaClass.simpleName,
                    )
                }
                snapshot().copy(note = note)
            }
        }
    }

    /** Removes every installed SEMANTIC asset (file + receipt entry; r26). */
    fun deleteSemanticModels() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                provisioner.invalidateCapability(ModelManifest.Capability.SEMANTIC)
                snapshot().copy(note = AiDogfoodState.Note.SemanticDeleted)
            }
        }
    }

    /**
     * The r26 semantic queue's live state, collected from WorkManager for
     * as long as this ViewModel lives (the r24 [queueState] wiring,
     * sibling edition --- its own unique work name, so the face queue's
     * card is untouched).
     */
    val clipQueueState: StateFlow<ClipIndexWorkState> =
        ClipIndexScheduler.observe(getApplication())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ClipIndexWorkState.Idle)

    /** Starts the semantic background queue (r26). */
    fun startSemanticIndex() {
        ClipIndexScheduler.enqueue(getApplication())
    }

    /** Cancels the semantic background queue chain (r26). */
    fun cancelSemanticIndex() {
        ClipIndexScheduler.cancel(getApplication())
    }

    /**
     * The r26 semantic reset: cancel the chain, close any open store
     * THIS process holds, then delete the queue's OWN bookkeeping
     * (`clip-index-state.json`). HONEST SCOPE (the card copy states it):
     * the CLIP ROWS in `ml-index.sqlite` do NOT go --- `MlEngine` exposes
     * no delete-all-clips in the r26 contract, and the store-level full
     * reset already exists as the r24 "Reset face index", whose recursive
     * directory delete erases the whole `ml-index.sqlite` both queues
     * share (semantic reset rides that shape). Deleting the state file
     * alone is still the honest queue reset: the next run re-plans from
     * nothing, and every photo re-indexes delete-first (a `putClip`
     * upsert, so no rows duplicate).
     */
    fun resetSemanticIndex() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                ClipIndexScheduler.cancel(getApplication())
                engine.closeFaceIndex()
                File(faceIndexDir(), CLIP_INDEX_STATE_FILE_NAME).delete()
                snapshot().copy(note = null)
            }
        }
    }

    /** Runs one real face detection over the first eligible JPEG. */
    fun runSelfTest() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                val result = runSelfTestBlocking()
                snapshot().copy(selfTest = result)
            }
        }
    }

    // ---- r24: the background queue state + the clustering threshold ----

    /**
     * The background queue's live state, collected from WorkManager for
     * as long as this ViewModel lives (WhileSubscribed: the collection
     * follows the card's visibility, not the process).
     */
    val queueState: StateFlow<FaceIndexWorkState> =
        FaceIndexScheduler.observe(getApplication())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FaceIndexWorkState.Idle)

    /**
     * The clustering distance threshold the queue runs at: upstream's
     * recommended 0.24 by default, persisted in the `toad_settings`
     * preferences (`face_cluster_threshold`) so the tuned value survives
     * process death with the queue itself.
     */
    private val _threshold = MutableStateFlow(readThreshold())
    val threshold: StateFlow<Double> = _threshold.asStateFlow()

    /** Starts the background queue at the current [threshold] (r24). */
    fun startBackgroundIndex() {
        FaceIndexScheduler.enqueue(getApplication(), threshold.value)
    }

    /** Cancels the background queue chain (r24). */
    fun cancelBackgroundIndex() {
        FaceIndexScheduler.cancel(getApplication())
    }

    /**
     * Persists [value] as the queue's clustering threshold (clamped to the
     * card's tuning window) and feeds it to the NEXT run --- a running
     * chain keeps the threshold it was enqueued with.
     */
    fun setThreshold(value: Double) {
        val clamped = clampThreshold(value)
        prefs().edit().putString(KEY_THRESHOLD, String.format(Locale.ROOT, "%.2f", clamped)).apply()
        _threshold.value = clamped
    }

    /**
     * The r24 reset: cancel the chain, close any open store THIS process
     * holds, then delete the whole `face-index` directory --- the ML
     * store's SQLite file AND the queue's `index-state.json` go together,
     * so the queue's bookkeeping can never describe rows that no longer
     * exist.
     */
    fun resetFaceIndex() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                FaceIndexScheduler.cancel(getApplication())
                engine.closeFaceIndex()
                faceIndexDir().deleteRecursively()
                snapshot().copy(note = null)
            }
        }
    }

    private fun prefs() = getApplication<Application>().getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** The persisted threshold, forgiving-parse to the upstream default. */
    private fun readThreshold(): Double =
        clampThreshold(prefs().getString(KEY_THRESHOLD, null)?.toDoubleOrNull())

    private fun clampThreshold(raw: Double?): Double =
        (raw ?: DEFAULT_THRESHOLD).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

    /**
     * The self-test body, already on a worker thread. Fails honestly at
     * every gate (no library, no JPEG, init failure, engine exception)
     * instead of pretending an engine result.
     */
    private fun runSelfTestBlocking(): AiDogfoodState.SelfTestResult {
        if (!engine.available) return AiDogfoodState.SelfTestResult.LibraryMissing
        val jpeg = source.queryTimeline()
            .firstOrNull { !it.isVideo && it.mimeType.equals("image/jpeg", ignoreCase = true) }
            ?: return AiDogfoodState.SelfTestResult.NoJpegFound
        val displayName = displayNameOf(jpeg.uri) ?: "JPEG ${jpeg.id}"
        return try {
            val bytes = resolver.openInputStream(jpeg.uri)?.use { it.readBytes() }
                ?: return AiDogfoodState.SelfTestResult.Failed("cannot open $displayName")
            when (val init = engine.initialize(provisioner.modelsDir.absolutePath)) {
                is MlEngine.State.Ready -> Unit
                is MlEngine.State.Error ->
                    return AiDogfoodState.SelfTestResult.Failed("engine init failed: ${init.message}")
                else ->
                    return AiDogfoodState.SelfTestResult.Failed("engine init: $init")
            }
            val startedAt = SystemClock.elapsedRealtime()
            val faces = engine.detectFacesJpeg(bytes)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            AiDogfoodState.SelfTestResult.Ok(displayName, faces.size, elapsedMs)
        } catch (e: IllegalStateException) {
            // The engine's own failure shape (Rust panics surface here);
            // its message is the honest diagnostic, not a paraphrase.
            AiDogfoodState.SelfTestResult.Failed(e.message ?: "engine error")
        } finally {
            engine.release() // frees the session; the next run re-initializes
        }
    }

    /** DISPLAY_NAME for a content [Uri], or null when unresolvable. */
    private fun displayNameOf(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /** The app-private face-index directory (`ml-index.sqlite` inside). */
    private fun faceIndexDir(): File =
        File(getApplication<ToadGalleryApplication>().filesDir, FACE_INDEX_DIR_NAME)

    /** Presence truth from the provisioner's own derivation (disk-backed). */
    private fun snapshot(): AiDogfoodState {
        val faceAssets = ModelManifest.forCapability(ModelManifest.Capability.FACE)
        val semanticAssets = ModelManifest.forCapability(ModelManifest.Capability.SEMANTIC)
        return AiDogfoodState(
            faceCapability = provisioner.capabilityState(ModelManifest.Capability.FACE),
            assets = faceAssets.map {
                AiDogfoodState.AssetStatus(it.fileName, provisioner.installedFile(it) != null)
            },
            semanticCapability = provisioner.capabilityState(ModelManifest.Capability.SEMANTIC),
            semanticAssets = semanticAssets.map {
                AiDogfoodState.AssetStatus(it.fileName, provisioner.installedFile(it) != null)
            },
            engineLibraryPresent = engine.available,
        )
    }

    private companion object {
        /** The SettingsViewModel house preferences file (`toad_settings`). */
        const val PREFS_FILE = "toad_settings"

        /** The threshold's preferences key (r24). */
        const val KEY_THRESHOLD = "face_cluster_threshold"

        /** Upstream's recommended clustering distance (the untuned default). */
        const val DEFAULT_THRESHOLD = 0.24

        /** The card's tuning window around the upstream recommendation. */
        const val MIN_THRESHOLD = 0.10
        const val MAX_THRESHOLD = 0.40

        /** [queueState]'s WhileSubscribed stop timeout (house milliseconds). */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
