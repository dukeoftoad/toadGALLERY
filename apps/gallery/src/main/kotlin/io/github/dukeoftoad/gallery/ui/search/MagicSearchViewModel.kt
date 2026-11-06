package io.github.dukeoftoad.gallery.ui.search

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.data.DataSizeFormats
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ml.engine.ModelManifest
import io.github.dukeoftoad.gallery.ml.engine.ModelProvisioner
import io.github.dukeoftoad.gallery.ml.index.EngineSessionGuard
import io.github.dukeoftoad.gallery.ml.search.MagicSearchEngineAdapter
import io.github.dukeoftoad.gallery.ml.search.MagicSearchQuery
import io.github.dukeoftoad.gallery.ml.search.MagicSearchRepository
import io.github.dukeoftoad.gallery.ml.search.MagicSearchResult
import io.github.dukeoftoad.gallery.ml.search.SemanticContentPolicy
import io.github.dukeoftoad.gallery.ml.search.SemanticContentState
import io.github.dukeoftoad.gallery.ml.search.queryMagicSearchMedia
import io.github.dukeoftoad.gallery.ui.settings.KEY_MAGIC_SEARCH_ENABLED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Screen states of the Search tab's CONTENT mode (r26, Workstream 7 ---
 * Magic Search): the r19a [SearchUiState] shape, extended with the pure
 * [SemanticContentPolicy]'s four gating arms so every "why can't I
 * search by content" answer is an honest, named state instead of an
 * error loop.
 *
 * `Idle` covers BOTH "nothing typed yet" and "short/over-long query" ---
 * the screen disambiguates with the live query text (the pure gate:
 * [MagicSearchQuery.isSearchable]) so the state machine stays
 * one-dimensional, exactly the name-search contract. The policy arms
 * ([ConsentOff], [EngineMissing], [ModelsNotProvisioned],
 * [NotIndexed]) render as honest cards that each point at their own
 * recovery surface (the Settings toggle, the build shape, the model
 * download, the semantic index start).
 */
sealed interface MagicSearchUiState {

    /** No results to show: empty field, or a query outside the gate's bounds. */
    data object Idle : MagicSearchUiState

    /**
     * No media read permission (the r19a shared surface): the pipeline
     * refuses to query until the screen reports a grant --- the same
     * gate and the same shared Grant + Settings affordances the name
     * pipeline renders.
     */
    data object PermissionNeeded : MagicSearchUiState

    /** The `magicSearchEnabled` consent is OFF (ADR-0010; default false). */
    data object ConsentOff : MagicSearchUiState

    /** No engine library in this build --- the build-shape state. */
    data object EngineMissing : MagicSearchUiState

    /**
     * The SEMANTIC model pack is not fully installed (the r39 shape: the
     * FACE pack is no longer a Magic Search gate).
     */
    data object ModelsNotProvisioned : MagicSearchUiState

    /** All gates pass but the CLIP index is empty (queue never ran / draining). */
    data object NotIndexed : MagicSearchUiState

    /** A query is in flight (post-debounce, gates passed). */
    data object Searching : MagicSearchUiState

    /** The query ran and matched nothing. */
    data object NoResults : MagicSearchUiState

    /** The query failed; [message] is the engine's own text, verbatim. */
    data class Error(val message: String) : MagicSearchUiState

    /** The query's ranked matches, RELEVANCE order (the vecdb's own). */
    data class Results(val items: List<MediaItem>) : MagicSearchUiState
}

/**
 * State holder for the Search tab's Content mode (r26, Workstream 7 ---
 * Magic Search): the sibling of [SearchViewModel], owning the SAME
 * input-model shape (raw query text in, 300ms debounce, screen-driven
 * permission gate) and one addition --- the ACTIVE gate the screen
 * drives when the "Search by" mode row switches between Name and
 * Content. An inactive Content pipeline settles to [MagicSearchUiState.Idle]
 * and does no work; the name pipeline is likewise idle in Content mode,
 * so exactly one query pipeline runs at any moment.
 *
 * ## Input model
 * [onQueryChange] receives every keystroke (the screen pushes the SAME
 * text into both ViewModels --- each pipeline gates itself, so the
 * inactive one never queries); [onActiveChanged] flips this pipeline's
 * engagement and re-runs the live query through the same retry bump
 * [retry] uses. `init` combines query + retry counter, DEBOUNCES 300ms
 * (the name pipeline's discipline --- per-keystroke engine round-trips
 * would burn inference), and `collectLatest` cancels a query still in
 * flight when a newer (debounced) request lands.
 *
 * ## Gating (Error dominates; consent before capability)
 * Every request first consults the pure [MagicSearchQuery.isSearchable]
 * gate, then gathers the pure [SemanticContentPolicy]'s inputs in
 * DOMINANCE ORDER on Dispatchers.IO: the `magicSearchEnabled` consent
 * (read fresh from the house preferences at query time --- default
 * FALSE, ADR-0010), the engine seam's build-shape availability, the
 * provisioner's SEMANTIC capability state (r39: the FACE capability is
 * no longer a gate --- the semantic session installs without it), and
 * --- only when every earlier gate passes, so no pointless engine
 * session opens --- the store's clip count. The policy alone decides
 * the state; a failed input read maps to [MagicSearchUiState.Error]
 * with the failure's own message (the `PeopleViewModel` run-and-catch
 * contract, never an escape that kills the process).
 *
 * ## Execution
 * [MagicSearchRepository.search] runs on Dispatchers.IO inside
 * viewModelScope: [MagicSearchResult.Matches] publishes
 * [MagicSearchUiState.Results], [MagicSearchResult.NoMatches] publishes
 * [MagicSearchUiState.NoResults], and
 * [MagicSearchResult.EngineFailure] publishes
 * [MagicSearchUiState.Error] with the engine's own text (never
 * paraphrased away). The repository owns no cache; a "refresh" is
 * literally another query.
 *
 * Privacy (ADR-0010 / ADR-0020): the query text is embedded ON-DEVICE
 * by the CLIP text encoder; no search term, embedding or thumbnail
 * ever leaves the device (the only network the feature touches is the
 * ADR-0020 model download in Settings, which serves the model pack,
 * never queries).
 */
class MagicSearchViewModel(app: Application) : AndroidViewModel(app) {

    private val engineAdapter = MagicSearchEngineAdapter.fromApplication(app)
    private val repository = MagicSearchRepository(
        engine = engineAdapter,
        queryMedia = { fileIds -> queryMagicSearchMedia(app.contentResolver, fileIds) },
    )
    private val provisioner = getApplication<ToadGalleryApplication>().modelProvisioner
    private val prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /**
     * The honest size label for the models-not-provisioned card: the
     * SEMANTIC pack's total, derived from the manifest pin (never a
     * hand-maintained copy) and formatted with the house byte formatter.
     */
    val semanticModelsSize: String = DataSizeFormats.formatBytes(
        ModelManifest.forCapability(ModelManifest.Capability.SEMANTIC).sumOf { it.sizeBytes },
    )

    private val _state = MutableStateFlow<MagicSearchUiState>(MagicSearchUiState.Idle)
    val state: StateFlow<MagicSearchUiState> = _state.asStateFlow()

    /** The live TextField text (raw, untrimmed; shared with the name pipeline). */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * Retry counter: a StateFlow (not SharedFlow) so `combine` has an
     * initial value and emits from the first frame; `retry()` and the
     * activation flip increment it, which re-runs the pipeline for the
     * current query. All inputs are StateFlows, so no emission is ever
     * lost to buffering.
     */
    private val retries = MutableStateFlow(0)

    /**
     * Whether the Content mode is currently selected (the screen's
     * "Search by" row drives it). Main-thread-only like the StateFlows,
     * so a plain var is the honest, lock-free shape.
     */
    private var active = false

    /**
     * The screen-reported media read permission (the r19a discipline):
     * written by [onPermissionGranted]/[onPermissionDenied] and read by
     * the pipeline gate below.
     */
    private var permissionGranted = false

    init {
        viewModelScope.launch {
            combine(_query, retries) { query, _ -> query }
                .debounce(DEBOUNCE_MS.milliseconds)
                .collectLatest { rawQuery ->
                    if (!active) {
                        _state.value = MagicSearchUiState.Idle
                        return@collectLatest
                    }
                    if (!permissionGranted) {
                        _state.value = MagicSearchUiState.PermissionNeeded
                        return@collectLatest
                    }
                    if (!MagicSearchQuery.isSearchable(rawQuery)) {
                        _state.value = MagicSearchUiState.Idle
                        return@collectLatest
                    }
                    _state.value = MagicSearchUiState.Searching
                    _state.value = try {
                        withContext(Dispatchers.IO) { runQuery(rawQuery) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: SecurityException) {
                        // The grant vanished mid-session (the name
                        // pipeline's honest fold): the permission state,
                        // not a generic error the Retry button could not
                        // fix.
                        permissionGranted = false
                        MagicSearchUiState.PermissionNeeded
                    } catch (e: Exception) {
                        MagicSearchUiState.Error(e.message ?: e.javaClass.simpleName)
                    }
                }
        }
    }

    /**
     * The query body, already on a worker thread: policy gates in
     * dominance order, then the repository. A failed INPUT read folds to
     * the policy's own Error state inside [gateState] (run-and-catch,
     * the `PeopleViewModel` contract --- never an escape that kills the
     * process); the repository's own typed outcomes map one-to-one.
     */
    private suspend fun runQuery(rawQuery: String): MagicSearchUiState =
        when (val gate = gateState()) {
            // The pipeline's gate read is never a held state (gateState
            // decides synchronously per request); the arm exists because
            // the when is exhaustive over the shared policy's states.
            SemanticContentState.Loading -> MagicSearchUiState.Idle
            SemanticContentState.ConsentOff -> MagicSearchUiState.ConsentOff
            SemanticContentState.EngineMissing -> MagicSearchUiState.EngineMissing
            SemanticContentState.ModelsNotProvisioned -> MagicSearchUiState.ModelsNotProvisioned
            SemanticContentState.NotIndexed -> MagicSearchUiState.NotIndexed
            is SemanticContentState.Error -> MagicSearchUiState.Error(gate.message)
            SemanticContentState.Ready ->
                when (val result = repository.search(rawQuery)) {
                    is MagicSearchResult.Matches -> MagicSearchUiState.Results(result.items)
                    MagicSearchResult.NoMatches -> MagicSearchUiState.NoResults
                    is MagicSearchResult.EngineFailure ->
                        MagicSearchUiState.Error(result.message)
                }
        }

    /**
     * The pure policy's inputs, gathered in dominance order: the
     * clip-count read happens ONLY when consent, engine presence and
     * the SEMANTIC pack already pass, so a consented-off or
     * unprovisioned device never opens an engine session just to learn
     * it cannot query. A failure here (e.g. the session cannot open on
     * a provisioned device) folds to the policy's Error state with the
     * failure's own message.
     */
    private suspend fun gateState(): SemanticContentState = try {
        val consent = prefs.getBoolean(KEY_MAGIC_SEARCH_ENABLED, false)
        val engineAvailable = repository.engineAvailable
        val semanticReady = provisioner.capabilityState(ModelManifest.Capability.SEMANTIC) ==
            ModelProvisioner.CapabilityState.Ready
        val clipCount = if (consent && engineAvailable && semanticReady) {
            repository.clipCount()
        } else {
            0L
        }
        SemanticContentPolicy.decide(
            consentEnabled = consent,
            engineAvailable = engineAvailable,
            semanticModelsReady = semanticReady,
            clipCount = clipCount,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SemanticContentState.Error(e.message ?: e.javaClass.simpleName)
    }

    /** Called by the screen on every keystroke (both pipelines share the text). */
    fun onQueryChange(value: String) {
        _query.value = value
    }

    /**
     * Called by the screen when the "Search by" mode row changes: an
     * inactive pipeline settles to Idle and never queries; a newly
     * active one re-runs the live query through the SAME retry bump
     * [retry] uses (the debounced collector re-decides --- Idle
     * invitation when nothing searchable is typed, the query otherwise).
     */
    fun onActiveChanged(nowActive: Boolean) {
        if (active == nowActive) return
        active = nowActive
        if (!nowActive) {
            _state.value = MagicSearchUiState.Idle
        }
        retries.value += 1
    }

    /** Re-runs the current query after an [MagicSearchUiState.Error]. */
    fun retry() {
        retries.value += 1
    }

    /**
     * The screen reports the media read permission granted: the gate
     * opens and the current query re-runs. The early-return keeps tab
     * re-entries cheap (the launcher returns immediately when access is
     * already granted, so every re-entry would otherwise re-run the live
     * query for nothing).
     */
    fun onPermissionGranted() {
        if (permissionGranted) return
        permissionGranted = true
        retries.value += 1
    }

    /**
     * The screen reports the permission denied or revoked later: the
     * gate closes and the state flips to the honest grant-me surface,
     * discarding any shown results (they are unreadable without the
     * grant anyway).
     */
    fun onPermissionDenied() {
        permissionGranted = false
        if (active) {
            _state.value = MagicSearchUiState.PermissionNeeded
        }
    }

    override fun onCleared() {
        // The adapter's engine session is process-global native state ---
        // release it with the screen. r50 (F-38): ONLY when neither index
        // chain is live; a background queue shares that same native
        // session, and the old unconditional close killed it mid-batch.
        // A skipped close leaks nothing --- the adapter re-opens lazily.
        if (!EngineSessionGuard.anyIndexChainLive(getApplication())) {
            engineAdapter.close()
        }
        super.onCleared()
    }

    private companion object {
        /** The Settings house preferences file (issue #27; see SettingsViewModel). */
        const val PREFS_FILE = "toad_settings"

        /**
         * Debounce between input changes and the engine round-trip ---
         * the name pipeline's 300ms window, shared verbatim so the two
         * modes feel alike.
         */
        const val DEBOUNCE_MS = 300L
    }
}
