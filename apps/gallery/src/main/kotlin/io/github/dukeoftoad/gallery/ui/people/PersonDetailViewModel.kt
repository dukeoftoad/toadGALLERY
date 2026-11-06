package io.github.dukeoftoad.gallery.ui.people

import android.app.Application
import androidx.annotation.AnyRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ml.index.EngineSessionGuard
import io.github.dukeoftoad.gallery.ml.people.PeopleContentPolicy
import io.github.dukeoftoad.gallery.ml.people.PeopleContentState
import io.github.dukeoftoad.gallery.ml.people.PeopleEngineAdapter
import io.github.dukeoftoad.gallery.ml.people.PeopleRepository
import io.github.dukeoftoad.gallery.ml.people.PeopleSnapshot
import io.github.dukeoftoad.gallery.ml.people.PeopleStateFile
import io.github.dukeoftoad.gallery.ml.people.PersonPhoto
import io.github.dukeoftoad.gallery.ml.people.VisiblePerson
import io.github.dukeoftoad.gallery.ml.people.PEOPLE_STATE_FILE_NAME
import io.github.dukeoftoad.gallery.ml.people.queryPersonPhotos
import io.github.dukeoftoad.gallery.ml.index.FACE_INDEX_DIR_NAME
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * State holder for the Person detail screen (r25, Workstream 6): one
 * cluster's photos plus the cluster-management operations.
 *
 * ## State model (honest, no dead ends)
 *  - [PersonDetailState.Loading] / [PersonDetailState.Error] mirror the
 *    sibling sub-screens (a failed read surfaces its own message, never
 *    a fake empty grid);
 *  - [PersonDetailState.Missing] is the live-table truth "this cluster
 *    no longer exists" --- merged away, hidden, emptied by a removal,
 *    or re-clustered out from under the screen (cluster ids are NOT
 *    stable across re-clustering passes; see `FaceCluster`). The screen
 *    pops back; faking a grid of stale photos would be the opposite of
 *    honest;
 *  - [PersonDetailState.Ready] carries the person (raw cluster + the
 *    user's name applied), the cluster's photos (MediaStore rows,
 *    newest-first, deduped) and the OTHER visible clusters as
 *    merge targets.
 *
 * ## Operations (all through [PeopleRepository], then refresh)
 *  - [rename] (blank input removes the name --- the state store's rule);
 *  - [mergeInto]: `merge(source = THIS cluster, target = chosen)` ---
 *    the direction the "Merge with…" dialog encodes; success ends in
 *    [PersonDetailState.Missing] (the source cluster is gone) and the
 *    screen pops back to the refreshed People list;
 *  - [hide]: adds the cluster to the hidden set, then Missing + pop;
 *  - [removeFromPerson]: `removeFacesFromCluster` over the current
 *    selection; the screen stays (the cluster usually survives with its
 *    remaining photos) and a snackbar carries the honest count, while a
 *    cluster emptied by the removal lands in Missing + pop.
 *
 * Operation failures surface as one-shot [PersonDetailEvent.ShowSnackbar]
 * events (resource ids + args only --- strings resolve against the
 * current configuration at display time, the `FileOpsEvent` contract),
 * never as silent no-ops.
 *
 * The [selection] is a PURE [PersonDetailSelection] machine; every load
 * re-syncs it to the fresh photo ids so the count bar can never claim
 * photos that are gone.
 */
class PersonDetailViewModel(
    app: Application,
    private val clusterId: String,
) : AndroidViewModel(app) {

    private val engineAdapter = PeopleEngineAdapter.fromApplication(app)
    private val faceIndexDir = File(app.filesDir, FACE_INDEX_DIR_NAME)
    private val repository = PeopleRepository(
        engine = engineAdapter,
        stateStore = PeopleStateFile(File(faceIndexDir, PEOPLE_STATE_FILE_NAME)),
        queryPhotos = { fileIds -> queryPersonPhotos(app.contentResolver, fileIds) },
    )

    private val _state = MutableStateFlow<PersonDetailState>(PersonDetailState.Loading)
    val state: StateFlow<PersonDetailState> = _state.asStateFlow()

    /** The grid's multi-select state (pure transitions, JVM-tested). */
    private val _selection = MutableStateFlow(PersonDetailSelection.INACTIVE)
    val selection: StateFlow<PersonDetailSelection> = _selection.asStateFlow()

    /**
     * One-shot UI events (snackbars), the `FileOpsEvent` shape: capacity
     * with DROP_OLDEST keeps `tryEmit` non-blocking and coalesces a
     * burst into the newest message.
     */
    private val _events = MutableSharedFlow<PersonDetailEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<PersonDetailEvent> = _events.asSharedFlow()

    /** Monotonic load generation: only the youngest load publishes. */
    private var loadGeneration = 0

    init {
        load()
    }

    /** Re-reads the cluster + photos + merge targets (no Loading flash over a working screen). */
    fun refresh() {
        load()
    }

    /** The Error state's retry action: re-enters Loading first, then loads. */
    fun retry() {
        _state.value = PersonDetailState.Loading
        load()
    }

    // ---- selection (pure state machine transitions) ----

    /** Long-press handler: enters selection mode with the pressed photo seeded. */
    fun onPhotoLongPressed(fileId: Long) {
        _selection.value = _selection.value.entered(fileId)
    }

    /** Tap handler: toggles inside the mode (plain taps outside it are the screen's viewer opens). */
    fun onPhotoTapped(fileId: Long) {
        _selection.value = _selection.value.toggled(fileId)
    }

    /** The selection bar's exit action. */
    fun exitSelection() {
        _selection.value = _selection.value.exited()
    }

    // ---- cluster management operations ----

    /**
     * Sets/updates/removes this person's name (blank input REMOVES it ---
     * back to "Unnamed"); persists through the repository, then refreshes.
     */
    fun rename(name: String) {
        performOperation { repository.rename(clusterId, name) }
    }

    /**
     * Merges THIS cluster into [targetClusterId] (source = this, target =
     * the chosen person --- the dialog's direction). On success the
     * refresh lands in [PersonDetailState.Missing] and the screen pops
     * back; the merge dialog itself was the confirmation, and the effect
     * (this person gone, the target's count grown) is immediately
     * visible in the People list behind.
     */
    fun mergeInto(targetClusterId: String) {
        performOperation { repository.merge(clusterId, targetClusterId) }
    }

    /**
     * Hides this person (the hidden set); the refresh lands in Missing
     * and the screen pops back. The r26 way back is the People screen's
     * HIDDEN SECTION (per-person Unhide --- no index reset, names and
     * every other edit intact; see [io.github.dukeoftoad.gallery.ml
     * .people.PeopleRepository.unhide]).
     */
    fun hide() {
        performOperation { repository.hide(clusterId) }
    }

    /**
     * Removes the currently SELECTED photos' faces from this cluster
     * (the "not this person" correction; the photos themselves are
     * untouched). The screen stays for a surviving cluster (snackbar
     * carries the removed-face count); an emptied cluster lands in
     * Missing and pops back.
     */
    fun removeFromPerson() {
        val fileIds = _selection.value.selectedFileIds
        if (fileIds.isEmpty()) return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { repository.removePhotos(clusterId, fileIds.toLongArray()) }
            }
            when {
                // The engine's own success flag: true -> the honest count.
                outcome.getOrDefault(false) ->
                    _events.tryEmit(
                        PersonDetailEvent.ShowSnackbar(
                            messageRes = R.plurals.person_removed_faces,
                            pluralCount = fileIds.size,
                        )
                    )
                // False with no exception: the engine declined the write.
                outcome.isSuccess ->
                    _events.tryEmit(
                        PersonDetailEvent.ShowSnackbar(
                            messageRes = R.string.people_action_failed_generic,
                        )
                    )
                // A thrown failure: the engine's own message, verbatim.
                else ->
                    outcome.exceptionOrNull()?.let { e ->
                        _events.tryEmit(
                            PersonDetailEvent.ShowSnackbar(
                                messageRes = R.string.people_action_failed,
                                formatArgs = listOf(e.message ?: e.javaClass.simpleName),
                            )
                        )
                    }
            }
            load()
        }
    }

    /**
     * One load: snapshot -> the cluster's own row (Missing when it is
     * gone), its photos, and the OTHER visible clusters as merge
     * targets. All inside an IO hop guarded by `runCatching` --- a
     * failure maps to [PersonDetailState.Error] with the failure's own
     * message (the `AlbumDetailViewModel.load` contract; the catch sees
     * only blocking engine/store throws, so cancellation of the hop
     * still propagates).
     */
    private fun load() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { loadReadyState() }
                    .getOrElse { e ->
                        PersonDetailState.Error(e.message ?: e.javaClass.simpleName)
                    }
            }
            if (generation == loadGeneration) {
                _state.value = fresh
                if (fresh is PersonDetailState.Ready) {
                    // The selection can only reference photos that still exist.
                    _selection.value = _selection.value
                        .syncedTo(fresh.photos.mapTo(HashSet()) { it.fileId })
                }
            }
        }
    }

    /**
     * The success path of [load], already on a worker thread. The person
     * itself comes from the RAW cluster (the screen was reached while
     * the cluster existed); the merge targets are the OTHER visible
     * clusters --- the same filter/name mapping the pure
     * [PeopleContentPolicy] applies to the People list.
     */
    private suspend fun loadReadyState(): PersonDetailState {
        val snapshot = repository.snapshot()
        val cluster = snapshot.clusters.firstOrNull { it.clusterId == clusterId }
            ?: return PersonDetailState.Missing
        // The name rule is PeopleContentPolicy's ("blank stored names fall
        // back to Unnamed"); the store never persists blanks, so this is
        // belt-and-braces over the same rule.
        val name = snapshot.names[clusterId]?.takeIf { it.isNotBlank() }
        val person = VisiblePerson(
            clusterId = cluster.clusterId,
            name = name,
            faceCount = cluster.faceCount,
            coverFileId = cluster.coverFileId,
        )
        val mergeTargets = visibleOthers(snapshot, selfId = clusterId)
        val photos = repository.photosOf(clusterId)
        return PersonDetailState.Ready(
            person = person,
            photos = photos,
            mergeTargets = mergeTargets,
        )
    }

    /**
     * The merge dialog's entries: every OTHER visible cluster (hidden
     * ones excluded --- merging into a person the user cannot see would
     * be a dead-end action), names applied, seam order preserved ---
     * exactly the pure policy's Ready(visible) minus this person.
     */
    private suspend fun visibleOthers(snapshot: PeopleSnapshot, selfId: String): List<VisiblePerson> {
        val visible = PeopleContentPolicy.decide(
            consentEnabled = true,
            engineAvailable = repository.engineAvailable,
            clusters = snapshot.clusters,
            hiddenIds = snapshot.hidden,
            names = snapshot.names,
        ) as? PeopleContentState.Ready
        return visible?.visible.orEmpty().filter { it.clusterId != selfId }
    }

    /**
     * The shared operation skeleton: run the repository op on IO guarded
     * by `runCatching` (failures -> the honest snackbar), then reload.
     * Used by [rename], [mergeInto] and [hide], whose outcomes land in
     * the normal state machine (Ready with the new name / Missing after
     * a merge or hide).
     */
    private fun performOperation(op: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { op() }
            }.onFailure { e ->
                _events.tryEmit(
                    PersonDetailEvent.ShowSnackbar(
                        messageRes = R.string.people_action_failed,
                        formatArgs = listOf(e.message ?: e.javaClass.simpleName),
                    )
                )
            }
            load()
        }
    }

    override fun onCleared() {
        // r50 (F-38): the adapter's close tears down the PROCESS-GLOBAL
        // native session --- skip it while an index chain is live (the
        // queue shares the session); the adapter re-opens lazily anyway.
        if (!EngineSessionGuard.anyIndexChainLive(getApplication())) {
            engineAdapter.close()
        }
        super.onCleared()
    }

    companion object {
        /** Minimal factory carrying the navigation argument (the AlbumDetailViewModel pattern). */
        fun factory(application: Application, clusterId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PersonDetailViewModel(application, clusterId) as T
            }
    }
}

/**
 * The Person detail screen's states (r25). [Missing] is the live-table
 * truth that the cluster no longer exists (merged away / hidden /
 * emptied / re-clustered) --- the screen pops back rather than faking
 * stale content. [Ready] carries the person, its photos (newest first)
 * and the other visible clusters as merge targets.
 */
sealed interface PersonDetailState {
    data object Loading : PersonDetailState

    /** The read failed; [message] is the failure's own text, surfaced verbatim. */
    data class Error(val message: String) : PersonDetailState

    /** The cluster no longer exists; the screen pops back. */
    data object Missing : PersonDetailState

    data class Ready(
        val person: VisiblePerson,
        val photos: List<PersonPhoto>,
        val mergeTargets: List<VisiblePerson>,
    ) : PersonDetailState
}

/**
 * One-shot Person-detail UI events (r25): snackbars in the
 * `FileOpsEvent` shape --- resource ids + format args only, so strings
 * resolve against the current configuration at display time. When
 * [pluralCount] is >= 0, [messageRes] is a plurals resource resolved
 * with the count (the `FileOpsEvent` convention).
 */
sealed interface PersonDetailEvent {
    data class ShowSnackbar(
        // @AnyRes, not @StringRes: the same field carries either a plain
        // R.string (formatArgs path) or an R.plurals (pluralCount path)
        // --- the FileOpsEvent.ShowSnackbar convention, whose @AnyRes is
        // what lets the screen's getQuantityString/getString dual resolve
        // without a lint ResourceType error.
        @AnyRes val messageRes: Int,
        val formatArgs: List<String> = emptyList(),
        val pluralCount: Int = -1,
    ) : PersonDetailEvent
}
