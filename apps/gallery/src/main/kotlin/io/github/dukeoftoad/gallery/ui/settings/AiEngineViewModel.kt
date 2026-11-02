package io.github.dukeoftoad.gallery.ui.settings

import android.app.Application
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Whether `libtoad_ml_jni.so` is present in THIS build (per-process fixed). */
    val engineLibraryPresent: Boolean = false,
    /** The last download/delete outcome, or null. */
    val note: Note? = null,
    /** The last self-test outcome, or null. */
    val selfTest: SelfTestResult? = null,
) {

    /** One FACE asset's presence row. */
    data class AssetStatus(val fileName: String, val installed: Boolean)

    /** Last download/delete action outcome. */
    sealed interface Note {
        /** Every FACE asset is receipt-verified installed. */
        object Downloaded : Note

        /** The provisioning run threw; [reason] is the failure message. */
        data class DownloadFailed(val reason: String) : Note

        /** invalidateCapability(FACE) ran; rows re-derived from disk. */
        object Deleted : Note
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
 *    guarantee, dogfooded).
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

    /** Presence truth from the provisioner's own derivation (disk-backed). */
    private fun snapshot(): AiDogfoodState {
        val faceAssets = ModelManifest.forCapability(ModelManifest.Capability.FACE)
        return AiDogfoodState(
            faceCapability = provisioner.capabilityState(ModelManifest.Capability.FACE),
            assets = faceAssets.map {
                AiDogfoodState.AssetStatus(it.fileName, provisioner.installedFile(it) != null)
            },
            engineLibraryPresent = engine.available,
        )
    }
}
