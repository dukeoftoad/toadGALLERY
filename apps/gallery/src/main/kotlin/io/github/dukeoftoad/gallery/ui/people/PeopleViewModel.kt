package io.github.dukeoftoad.gallery.ui.people

import android.app.Application
import android.content.Context
import androidx.annotation.AnyRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ml.people.PeopleContentPolicy
import io.github.dukeoftoad.gallery.ml.people.PeopleContentState
import io.github.dukeoftoad.gallery.ml.people.PeopleEngineAdapter
import io.github.dukeoftoad.gallery.ml.people.PeopleRepository
import io.github.dukeoftoad.gallery.ml.people.PeopleStateFile
import io.github.dukeoftoad.gallery.ml.people.VisiblePerson
import io.github.dukeoftoad.gallery.ml.people.PEOPLE_STATE_FILE_NAME
import io.github.dukeoftoad.gallery.ml.people.queryPersonPhotos
import io.github.dukeoftoad.gallery.ml.index.FACE_INDEX_DIR_NAME
import io.github.dukeoftoad.gallery.ui.settings.KEY_PEOPLE_ALBUM_ENABLED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * State holder for the People screen (r25, Workstream 6; the r26
 * follow-ups round adds the hidden section + unhide).
 *
 * A thin state machine over the PURE [PeopleContentPolicy] (the
 * [MapContentPolicy] discipline): [refresh] gathers the policy's inputs
 * --- the `peopleAlbumEnabled` consent read fresh from the house
 * `toad_settings` preferences (default FALSE, ADR-0010; read at REFRESH
 * time, not construction, so toggling it in Settings and coming back
 * flips the screen without a process restart), the engine seam's
 * build-shape availability, and one [PeopleRepository.snapshot] (live
 * clusters + the r26-reconciled names/hidden + the corrupt-file note)
 * --- and the policy alone decides which of the six honest states the
 * screen shows. The HIDDEN half ([PeopleUiState.hidden], the pure
 * [PeopleContentPolicy.hiddenPersons] mapping) feeds the screen's
 * hidden section, whose per-person unhide action is this ViewModel's
 * [unhide] --- the r26 way back from a hide, no index reset.
 *
 * The rename/merge/hide/remove operations live on
 * [PersonDetailViewModel] (the screen that renders their affordances);
 * this ViewModel owns the browse surface, the hidden section and
 * nothing else --- no dead API.
 *
 * ## Error surfacing (the sibling screens' contract)
 * Every repository call runs inside `runCatching { ... }.getOrElse { ... }`
 * in an IO hop (the `AlbumDetailViewModel.load` guard): an engine/store
 * failure --- session open failed, store unreadable --- maps to
 * [PeopleContentState.Error] carrying the failure's own message, never
 * an escape that kills the process and never a fake empty list. The
 * corrupt-file note from the state store rides [PeopleUiState.corruptNote]
 * so the screen can say the user's names were reset instead of silently
 * losing them. An UNHIDE failure (the one operation this screen owns)
 * is a one-shot [PeopleEvent.ShowSnackbar] --- never a silent no-op ---
 * and the refresh that follows keeps the honest current state on
 * screen.
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
     * One-shot UI events (the unhide failure snackbar), the
     * `FileOpsEvent` shape: capacity with DROP_OLDEST keeps `tryEmit`
     * non-blocking; resource ids + args only, so strings resolve
     * against the current configuration at display time.
     */
    private val _events = MutableSharedFlow<PeopleEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PeopleEvent> = _events.asSharedFlow()

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
                        hidden = PeopleContentPolicy.hiddenPersons(
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

    /**
     * The hidden section's per-person action (r26): restores a hidden
     * person WITHOUT a queue reset --- the repository only flips the
     * hidden-set membership (names and every other edit stay). A
     * failure is the honest one-shot snackbar (the failure's own
     * message), then the refresh keeps the current truth on screen.
     */
    fun unhide(clusterId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { repository.unhide(clusterId) }
            }.onFailure { e ->
                _events.tryEmit(
                    PeopleEvent.ShowSnackbar(
                        messageRes = R.string.people_action_failed,
                        formatArgs = listOf(e.message ?: e.javaClass.simpleName),
                    )
                )
            }
            refresh()
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
 * The People screen's whole UI state (r25 + the r26 hidden half): the
 * policy's content state, the HIDDEN persons its section renders
 * ([PeopleContentPolicy.hiddenPersons] --- empty outside the Ready
 * states by construction), and the honest corrupt-file footnote
 * ([corruptNote] --- true when the persisted names could not be parsed
 * and were RESET; the screen shows the note once instead of silently
 * losing the user's names).
 */
data class PeopleUiState(
    val content: PeopleContentState,
    val hidden: List<VisiblePerson> = emptyList(),
    val corruptNote: Boolean = false,
)

/**
 * One-shot People-screen UI events (r26): snackbars in the
 * `FileOpsEvent`/`PersonDetailEvent` shape --- resource ids + format
 * args only, so strings resolve against the current configuration at
 * display time.
 */
sealed interface PeopleEvent {
    data class ShowSnackbar(
        // @AnyRes, not @StringRes: the same field carries either a plain
        // R.string (formatArgs path) or an R.plurals (pluralCount path)
        // --- the FileOpsEvent.ShowSnackbar convention.
        @AnyRes val messageRes: Int,
        val formatArgs: List<String> = emptyList(),
        val pluralCount: Int = -1,
    ) : PeopleEvent
}
