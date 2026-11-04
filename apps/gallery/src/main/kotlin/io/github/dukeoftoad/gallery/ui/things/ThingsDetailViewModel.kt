package io.github.dukeoftoad.gallery.ui.things

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ml.index.FACE_INDEX_DIR_NAME
import io.github.dukeoftoad.gallery.ml.search.MagicSearchEngineAdapter
import io.github.dukeoftoad.gallery.ml.search.queryMagicSearchMedia
import io.github.dukeoftoad.gallery.ml.things.ThingsCollection
import io.github.dukeoftoad.gallery.ml.things.ThingsMemberOutcome
import io.github.dukeoftoad.gallery.ml.things.ThingsRepository
import io.github.dukeoftoad.gallery.ml.things.ThingsStateFile
import io.github.dukeoftoad.gallery.ml.things.THINGS_STATE_FILE_NAME
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen states of the Things collection detail (r39, the ente-port
 * exit round): the [io.github.dukeoftoad.gallery.ui.people
 * .PersonDetailState] shape, Things-flavored --- `Ready` carries the
 * collection (its own name for the title) plus its members in the CLIP
 * ranking's RELEVANCE order; `Missing` is the deleted-collection
 * transition that pops the screen back (never fake stale content);
 * `Empty` is a collection whose query matched nothing within the
 * membership cut (an honest state with its own copy, NOT an error).
 */
sealed interface ThingsDetailState {

    /** Initial load in flight. */
    data object Loading : ThingsDetailState

    /**
     * The collection no longer exists (deleted on the Albums tab while
     * the detail was open, or a stale route): a one-frame transition on
     * the way to the screen's `onBack()`.
     */
    data object Missing : ThingsDetailState

    /**
     * The read failed; [message] is the engine's/store's own text,
     * surfaced verbatim. The retry action re-runs the load.
     */
    data class Error(val message: String) : ThingsDetailState

    /**
     * The collection exists but its membership is empty (nothing
     * within the distance cut, or matches whose photos no longer
     * resolve) --- the honest empty state with its own copy and the
     * edit-query affordance.
     */
    data class Empty(val collection: ThingsCollection) : ThingsDetailState

    /** The collection and its ranked members. */
    data class Ready(val collection: ThingsCollection, val items: List<MediaItem>) : ThingsDetailState
}

/**
 * Screen-scoped state holder for the Things collection detail (r39,
 * the `PersonDetailViewModel` pattern): owns ONE collection's
 * membership read + the rename/requery/delete mutations, over the
 * no-cache [ThingsRepository] (every refresh re-derives the membership
 * from the live CLIP index --- the derived-view doctrine).
 *
 * The mutations persist through the repository and REFRESH; a delete
 * lands in [ThingsDetailState.Missing] and the screen pops back (the
 * PersonDetail hide/merge contract). Monotonic load generation keeps
 * only the youngest read publishing (stale loads from a rapid
 * rename-then-refresh never race a newer one).
 */
class ThingsDetailViewModel(
    app: Application,
    private val collectionId: String,
) : AndroidViewModel(app) {

    private val engineAdapter = MagicSearchEngineAdapter.fromApplication(app)

    private val repository = ThingsRepository(
        engine = engineAdapter,
        store = ThingsStateFile(
            File(File(app.filesDir, FACE_INDEX_DIR_NAME), THINGS_STATE_FILE_NAME),
        ),
        queryMedia = { fileIds -> queryMagicSearchMedia(app.contentResolver, fileIds) },
    )

    private val _state = MutableStateFlow<ThingsDetailState>(ThingsDetailState.Loading)
    val state: StateFlow<ThingsDetailState> = _state.asStateFlow()

    /** Monotonic load generation: only the youngest load publishes. */
    private var loadGeneration = 0

    init {
        load()
    }

    /** Re-reads the collection + membership (no Loading flash over a working screen). */
    fun refresh() {
        load()
    }

    /** The Error state's retry action: re-enters Loading first, then loads. */
    fun retry() {
        _state.value = ThingsDetailState.Loading
        load()
    }

    /**
     * Renames the collection (a blank name is refused by the
     * repository --- no state change, the dialog stays the user's next
     * move); persists, then refreshes.
     */
    fun rename(name: String) {
        mutate { repository.rename(collectionId, name) }
    }

    /**
     * Sets the membership query (a blank query resets to the
     * collection's name --- the "Dogs over dogs" default); persists,
     * then refreshes.
     */
    fun requery(query: String) {
        mutate { repository.requery(collectionId, query) }
    }

    /**
     * Deletes the collection; the refresh lands in
     * [ThingsDetailState.Missing] and the screen pops back (the
     * delete dialog itself was the confirmation).
     */
    fun delete() {
        mutate { repository.remove(collectionId) }
    }

    private fun mutate(operation: suspend () -> Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { operation() }.getOrElse { false }
            }
            load()
        }
    }

    private fun load() {
        val generation = ++loadGeneration
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { readState() }.getOrElse { e ->
                    ThingsDetailState.Error(e.message ?: e.javaClass.simpleName)
                }
            }
            if (generation == loadGeneration) _state.value = fresh
        }
    }

    private suspend fun readState(): ThingsDetailState {
        val collection = repository.collection(collectionId)
            ?: return ThingsDetailState.Missing
        return when (val outcome = repository.members(collectionId)) {
            is ThingsMemberOutcome.Members -> ThingsDetailState.Ready(collection, outcome.items)
            ThingsMemberOutcome.Empty -> ThingsDetailState.Empty(collection)
            is ThingsMemberOutcome.EngineFailure ->
                ThingsDetailState.Error(outcome.message)
            ThingsMemberOutcome.UnknownCollection -> ThingsDetailState.Missing
        }
    }

    companion object {
        /**
         * The screen-scoped factory (the PersonDetailViewModel pattern):
         * the Application is process-scoped, the collection id is the
         * route's durable argument.
         */
        fun factory(application: Application, collectionId: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ThingsDetailViewModel::class.java)) {
                        return ThingsDetailViewModel(application, collectionId) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel: $modelClass")
                }
            }
    }
}
