package io.github.dukeoftoad.gallery.ui.people

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.ml.people.PeopleContentPolicy
import io.github.dukeoftoad.gallery.ml.people.PeopleContentState
import io.github.dukeoftoad.gallery.ml.people.PeopleEngineAdapter
import io.github.dukeoftoad.gallery.ml.people.PeopleRepository
import io.github.dukeoftoad.gallery.ml.people.PeopleStateFile
import io.github.dukeoftoad.gallery.ml.people.PEOPLE_STATE_FILE_NAME
import io.github.dukeoftoad.gallery.ml.people.queryPersonPhotos
import io.github.dukeoftoad.gallery.ml.index.FACE_INDEX_DIR_NAME
import io.github.dukeoftoad.gallery.ui.settings.KEY_PEOPLE_ALBUM_ENABLED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * State holder for the People screen (r25, Workstream 6).
 *
 * A thin state machine over the PURE [PeopleContentPolicy] (the
 * [MapContentPolicy] discipline): [refresh] gathers the policy's inputs
 * --- the `peopleAlbumEnabled` consent read fresh from the house
 * `toad_settings` preferences (default FALSE, ADR-0010; read at REFRESH
 * time, not construction, so toggling it in Settings and coming back
 * flips the screen without a process restart), the engine seam's
 * build-shape availability, and one [PeopleRepository.snapshot] (live
 * clusters + pruned names/hidden + the corrupt-file note) --- and the
 * policy alone decides which of the six honest states the screen shows.
 *
 * The rename/merge/hide/remove operations live on
 * [PersonDetailViewModel] (the screen that renders their affordances);
 * this ViewModel owns the browse surface and nothing else --- no dead
 * API.
 *
 * ## Error surfacing (the sibling screens' contract)
 * Every repository call runs inside `runCatching { ... }.getOrElse { ... }`
 * in an IO hop (the `AlbumDetailViewModel.load` guard): an engine/store
 * failure --- session open failed, store unreadable --- maps to
 * [PeopleContentState.Error] carrying the failure's own message, never
 * an escape that kills the process and never a fake empty list. The
 * corrupt-file note from the state store rides [PeopleUiState.corruptNote]
 * so the screen can say the user's names were reset instead of silently
 * losing them.
 *
 * The repository owns NO cache (live-table semantics), so "refresh" is
 * literally another snapshot; the generation guard below keeps only the
 * YOUNGEST result publishable (a slow older read can never overwrite a
 * fresher one with stale clusters).
 */
class PeopleViewModel(app: Application) : AndroidViewModel(app) {

    private val engineAdapter = PeopleEngineAdapter.fromApplication(app)
    private val faceIndexDir = File(app.filesDir, FACE_INDEX_DIR_NAME)
    private val repository = PeopleRepository(
        engine = engineAdapter,
        stateStore = PeopleStateFile(File(faceIndexDir, PEOPLE_STATE_FILE_NAME)),
        queryPhotos = { fileIds -> queryPersonPhotos(app.contentResolver, fileIds) },
    )
    private val prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PeopleUiState(PeopleContentState.Loading))
    val state: StateFlow<PeopleUiState> = _state.asStateFlow()

    /**
     * Monotonic refresh generation: only the YOUNGEST [refresh] may
     * publish (main-dispatcher launches, so the counter needs no
     * synchronization --- the same argument `AlbumDetailViewModel` makes).
     */
    private var refreshGeneration = 0

    init {
        refresh()
    }

    /**
     * One full read: consent + availability + snapshot, mapped through
     * the pure policy. Also the Error state's retry action (a failed read
     * re-runs the whole thing; the Loading state is re-entered first so a
     * slow retry never leaves a stale error on screen while it is in
     * flight --- the `AlbumDetailViewModel.retry` contract).
     */
    fun refresh() {
        val generation = ++refreshGeneration
        _state.value = PeopleUiState(PeopleContentState.Loading)
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    val consent = prefs.getBoolean(KEY_PEOPLE_ALBUM_ENABLED, false)
                    val snapshot = repository.snapshot()
                    PeopleUiState(
                        content = PeopleContentPolicy.decide(
                            consentEnabled = consent,
                            engineAvailable = repository.engineAvailable,
                            clusters = snapshot.clusters,
                            hiddenIds = snapshot.hidden,
                            names = snapshot.names,
                        ),
                        corruptNote = snapshot.recoveredFromCorruptFile,
                    )
                }.getOrElse { e ->
                    PeopleUiState(
                        content = PeopleContentState.Error(
                            e.message ?: e.javaClass.simpleName,
                        ),
                    )
                }
            }
            if (generation == refreshGeneration) {
                _state.value = fresh
            }
        }
    }

    override fun onCleared() {
        // The adapter's engine session is process-global native state ---
        // release it with the screen, exactly like FaceIndexWorker's
        // finally does per run.
        engineAdapter.close()
        super.onCleared()
    }

    private companion object {
        /** The Settings house preferences file (issue #27; see SettingsViewModel). */
        const val PREFS_FILE = "toad_settings"
    }
}

/**
 * The People screen's whole UI state (r25): the policy's content state
 * plus the honest corrupt-file footnote ([corruptNote] --- true when the
 * persisted names could not be parsed and were RESET; the screen shows
 * the note once instead of silently losing the user's names).
 */
data class PeopleUiState(
    val content: PeopleContentState,
    val corruptNote: Boolean = false,
)
