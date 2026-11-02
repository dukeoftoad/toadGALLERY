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
import io.github.dukeoftoad.gallery.ml.index.IndexCoordinator
import io.github.dukeoftoad.gallery.ml.index.MlEngineIndexEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    /** Live r23 index-run progress, or null between runs. */
    val indexProgress: IndexCoordinator.IndexProgress? = null,
    /** The last r23 index+cluster outcome (or reset), or null. */
    val indexResult: IndexResult? = null,
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

    /**
     * The r23 index+cluster outcome (mirrors [SelfTestResult]'s honest
     * failure shapes; the OK case is the "N photos · M faces · K
     * clusters" summary).
     */
    sealed interface IndexResult {
        /** No `libtoad_ml_jni.so` in this build --- the honest answer. */
        object LibraryMissing : IndexResult

        /** Library present but no indexable JPEG exists on the device. */
        object NoJpegFound : IndexResult

        /** The run completed: photos fed, faces written, clusters found. */
        data class Ok(
            val photos: Int,
            val faces: Int,
            val clusters: Int,
            val elapsedMs: Long,
        ) : IndexResult

        /** The face-index database was deleted/reset. */
        object Deleted : IndexResult

        /** The run failed; [message] is the engine's own text. */
        data class Failed(val message: String) : IndexResult
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
 *  - [runIndexCluster] (r23): up to the 12 most recent on-device JPEGs,
 *    read through the same content-resolver path as the self-test, fed to
 *    the [IndexCoordinator] (detect embeddings → putFace per face →
 *    cluster at the vendored upstream 0.24 threshold) over the SAME
 *    [MlEngine] instance, with live per-photo progress; the face-index
 *    store lives at `filesDir/face-index/ml-index.sqlite` (app-private;
 *    no new permissions, no network) and is closed after every run;
 *  - [deleteFaceIndex] (r23): closes the store and deletes the whole
 *    `face-index` directory (the reset mirror of [deleteFaceModels]).
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

    /** The r23 index+cluster run over the 12 most recent on-device JPEGs. */
    fun runIndexCluster() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                val result = runIndexClusterBlocking()
                snapshot().copy(indexResult = result, indexProgress = null)
            }
        }
    }

    /** Closes the face-index store and deletes its database (r23 reset). */
    fun deleteFaceIndex() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) {
                engine.closeFaceIndex()
                faceIndexDir().deleteRecursively()
                snapshot().copy(indexResult = AiDogfoodState.IndexResult.Deleted)
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

    /**
     * The r23 index run body, already on a worker thread. Same honest
     * gates as the self-test (no library, no JPEG), then the
     * coordinator's pipeline with live progress pushed into the state.
     * The store is opened per run and closed in `finally` --- every run
     * starts from a known store state, and [deleteFaceIndex] (or an
     * engine release) can never race an open handle.
     */
    private suspend fun runIndexClusterBlocking(): AiDogfoodState.IndexResult {
        if (!engine.available) return AiDogfoodState.IndexResult.LibraryMissing
        val jpegs = source.queryTimeline()
            .filter { !it.isVideo && it.mimeType.equals("image/jpeg", ignoreCase = true) }
            .take(INDEX_PHOTO_LIMIT)
        val photos = jpegs.mapNotNull { item ->
            resolver.openInputStream(item.uri)?.use { stream -> stream.readBytes() }
                ?.let { bytes -> item.id to bytes }
        }
        if (photos.isEmpty()) return AiDogfoodState.IndexResult.NoJpegFound
        return try {
            if (!engine.openFaceIndex(faceIndexDir().absolutePath)) {
                return AiDogfoodState.IndexResult.Failed("face index store did not open")
            }
            val coordinator = IndexCoordinator(MlEngineIndexEngine(engine))
            val startedAt = SystemClock.elapsedRealtime()
            val summary = coordinator.indexAndCluster(photos) { progress ->
                _state.value = _state.value.copy(indexProgress = progress, busy = true)
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            AiDogfoodState.IndexResult.Ok(
                photos = summary.photosProcessed,
                faces = summary.facesIndexed,
                clusters = summary.clusterSummary.clusters,
                elapsedMs = elapsedMs,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The facade's failure shapes (IllegalStateException for state
            // machine/engine faults, IllegalArgumentException for a wrong
            // embedding dimension); the engine's own message is the honest
            // diagnostic, not a paraphrase.
            AiDogfoodState.IndexResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            engine.closeFaceIndex()
        }
    }

    /** The app-private face-index directory (`ml-index.sqlite` inside). */
    private fun faceIndexDir(): File =
        File(getApplication<ToadGalleryApplication>().filesDir, "face-index")

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

    private companion object {
        /** The r23 dogfood caps one run at the 12 most recent JPEGs. */
        const val INDEX_PHOTO_LIMIT = 12
    }
}
