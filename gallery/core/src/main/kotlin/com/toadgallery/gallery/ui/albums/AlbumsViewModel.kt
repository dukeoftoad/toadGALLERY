package com.toadgallery.gallery.ui.albums

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.Album
import com.toadgallery.gallery.data.Favorites
import com.toadgallery.gallery.data.MediaStoreSource
import com.toadgallery.gallery.data.diag.DiagLog
import com.toadgallery.gallery.ml.engine.ModelManifest
import com.toadgallery.gallery.ml.engine.ModelProvisioner
import com.toadgallery.gallery.ml.index.faceIndexDirFor
import com.toadgallery.gallery.ml.people.PeopleContentPolicy
import com.toadgallery.gallery.ml.people.PeopleContentState
import com.toadgallery.gallery.ml.index.EngineSessionGuard
import com.toadgallery.gallery.ml.people.PeopleEngineAdapter
import com.toadgallery.gallery.ml.people.PeopleRepository
import com.toadgallery.gallery.ml.people.PeopleStateFile
import com.toadgallery.gallery.ml.people.PEOPLE_STATE_FILE_NAME
import com.toadgallery.gallery.ml.search.MagicSearchEngineAdapter
import com.toadgallery.gallery.ml.search.SemanticContentPolicy
import com.toadgallery.gallery.ml.search.SemanticContentState
import com.toadgallery.gallery.ml.things.ThingsCollection
import com.toadgallery.gallery.ml.things.ThingsRepository
import com.toadgallery.gallery.ml.things.ThingsStateFile
import com.toadgallery.gallery.ml.things.THINGS_STATE_FILE_NAME
import com.toadgallery.gallery.ToadGalleryApplication
import com.toadgallery.gallery.data.media.location.AndroidMediaLocationAccess
import com.toadgallery.gallery.data.media.location.MediaLocationDisplayRules
import com.toadgallery.gallery.data.places.PlacesContentPolicy
import com.toadgallery.gallery.data.places.PlacesContentState
import com.toadgallery.gallery.ui.settings.KEY_PEOPLE_ALBUM_ENABLED
import com.toadgallery.gallery.ui.settings.KEY_THINGS_ALBUM_ENABLED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * State holder for the Albums tab (Phase 3, issue #29; favorites pseudo-album
 * and live counts since #66).
 *
 * Mirrors the Photos tab's state model (issue #25) so both tabs feel
 * identical: Loading → PermissionNeeded → Ready, plus --- since r19 --- the
 * honest Error arm every sibling screen already had. `Ready` with an empty
 * list means the user granted access but has no media on the device;
 * Error is a failed read, never a disguised empty one.
 *
 * ## Favorites pseudo-album (issue #66)
 * `Ready` is pinned with a "Favorites" entry carrying
 * [Favorites.FAVORITES_BUCKET_ID] as its `bucketId` when at least one
 * favorited item exists (an empty favorites album renders no card --- there
 * is nothing to show until the first heart). The count stays live: on
 * API 30+ the same unified-collection `ContentObserver` the Photos timeline
 * watches (#38) funnels MediaStore changes into a debounced re-query, so
 * toggling a heart updates the card (and the pseudo-album's contents)
 * without re-entering the tab. Below R --- the same tiering as
 * `PhotosViewModel`/`AlbumDetailViewModel` --- no observer exists and the
 * list reflects the last query; favorites toggles are Q+ writes, and their
 * staleness on Q is the documented residual of that tier.
 *
 * ## People section (r25, Workstream 6)
 * [people] carries the SAME pure [PeopleContentPolicy] state the People
 * screen renders, refreshed by [refreshPeople] (init + every screen
 * re-entry, so a consent toggle in Settings lands on the next visit).
 * The section is read-only here --- names/merges/hides live on the Person
 * detail screen --- and the engine session only ever opens when consent
 * is ON and the engine is present (the consent-off and build-shape arms
 * are decided from the preference and the availability flag alone),
 * which keeps the common path session-free. A provider/engine failure
 * maps to the policy's honest Error state, never a fake empty row.
 *
 * ## Places section (r27, the organisational UX round)
 * [places] carries the SAME pure [PlacesContentPolicy] state the Places
 * screen renders, refreshed by [refreshPlaces] alongside the People
 * read on every tab re-entry. The read is the r16 map's exact supply
 * (the catalogue's geotagged rows + the r14 consent display gate over
 * [AndroidMediaLocationAccess]) --- no second query path, no reverse
 * geocoding; the section derives its region buckets in the composition
 * (the pure grid math), and a failure maps to the policy's honest
 * Error state.
 *
 * ## Things section (r39, the ente-port exit round)
 * [thingsSection] carries the SAME pure [SemanticContentPolicy] state
 * the Magic Search Content mode uses (ONE truth for the six semantic
 * states, shared), refreshed by [refreshThings] alongside the
 * People/Places reads on every tab re-entry; [thingsCollections]
 * carries the user's named collections (the state store's own order).
 * The section's two mutations ([createThing]/[removeThing]) persist
 * through the no-cache [ThingsRepository] and refresh; the membership
 * itself never lives here --- each collection's members are derived
 * on open by the detail screen (the derived-view doctrine).
 */
class AlbumsViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    private val _state = MutableStateFlow<AlbumsState>(AlbumsState.Loading)
    val state: StateFlow<AlbumsState> = _state.asStateFlow()

    /**
     * MediaStore change notifications, emitted by the observer registered
     * in `init` (API 30+). Capacity 1 with DROP_OLDEST coalesces storms
     * into one pending signal and keeps `tryEmit` non-blocking --- same
     * shape as `PhotosViewModel.timelineChanges` (#38) and
     * `AlbumDetailViewModel.bucketChanges` (#64).
     */
    private val albumsChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Registered in `init` (API 30+ only); unregistered in [onCleared]. */
    private var albumsObserver: ContentObserver? = null

    // ---- r25 (Workstream 6): the People section's state ----

    private val engineAdapter = PeopleEngineAdapter.fromApplication(app)

    /**
     * The People read path (read-only here; no photo query is needed for
     * the section, so the repository's default empty binding suffices).
     */
    private val peopleRepository = PeopleRepository(
        engine = engineAdapter,
        // r56: faceIndexDirFor mkdirs()s --- see FaceIndexWorker.
        stateStore = PeopleStateFile(
            File(faceIndexDirFor(app.filesDir), PEOPLE_STATE_FILE_NAME),
        ),
    )

    /** The consent source, read fresh on every [refreshPeople]. */
    private val prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _people = MutableStateFlow<PeopleContentState>(PeopleContentState.Loading)

    /** The People section's content state (the pure policy's output, r25). */
    val people: StateFlow<PeopleContentState> = _people.asStateFlow()

    // ---- r27 (the organisational UX round): the Places section's state ----

    /** The r14 consent source the Places read gates on (the map's machinery). */
    private val locationAccess = AndroidMediaLocationAccess(app)

    private val _places = MutableStateFlow<PlacesContentState>(PlacesContentState.Loading)

    /** The Places section's content state (the pure policy's output, r27). */
    val places: StateFlow<PlacesContentState> = _places.asStateFlow()

    // ---- r39 (the ente-port exit round): the Things section's state ----

    /** The semantic engine adapter the Things repository reads through. */
    private val thingsEngineAdapter = MagicSearchEngineAdapter.fromApplication(app)

    /** The Things read path (list + mutations; no media query --- the
     * section shows chips, the detail screen derives members). */
    private val thingsRepository = ThingsRepository(
        engine = thingsEngineAdapter,
        // r56: faceIndexDirFor mkdirs()s --- see FaceIndexWorker.
        store = ThingsStateFile(
            File(faceIndexDirFor(app.filesDir), THINGS_STATE_FILE_NAME),
        ),
    )

    private val _thingsSection = MutableStateFlow<SemanticContentState>(SemanticContentState.Loading)

    /** The Things section's content state (the SHARED semantic policy's output, r39). */
    val thingsSection: StateFlow<SemanticContentState> = _thingsSection.asStateFlow()

    private val _thingsCollections = MutableStateFlow<List<ThingsCollection>>(emptyList())

    /** The user's named collections, in creation order (the store's own order, r39). */
    val thingsCollections: StateFlow<List<ThingsCollection>> = _thingsCollections.asStateFlow()

    init {
        // API 30+ only, mirroring PhotosViewModel's gating and rationale:
        // the observer watches the unified Files collection (an R-gated
        // registration), and one re-query per debounced change storm keeps
        // the favorites count and the album list live.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            albumsObserver = source.registerTimelineObserver {
                albumsChanges.tryEmit(Unit)
            }
            viewModelScope.launch {
                albumsChanges
                    .debounce(RELOAD_DEBOUNCE_MS.milliseconds)
                    .collect { reload() }
            }
        }
        refreshPeople()
        refreshPlaces()
        refreshThings()
    }

    /**
     * One People read (r25): consent + availability + one repository
     * snapshot, mapped through the SAME pure policy the People screen
     * uses. Called from `init` and on every screen re-entry (the screen's
     * entry `LaunchedEffect`), which is what carries a Settings consent
     * toggle over without a shared ViewModel scope. Engine/store failures
     * map to the policy's honest Error state with the failure's own
     * message (the `reload` guard's contract).
     */
    fun refreshPeople() {
        viewModelScope.launch {
            _people.value = withContext(Dispatchers.IO) {
                runCatching {
                    val consent = prefs.getBoolean(KEY_PEOPLE_ALBUM_ENABLED, false)
                    // Consent-off and build-shape-missing are decided WITHOUT
                    // touching the engine (no session opens on the common path).
                    if (!consent) {
                        PeopleContentState.ConsentOff
                    } else if (!peopleRepository.engineAvailable) {
                        PeopleContentState.EngineMissing
                    } else {
                        val snapshot = peopleRepository.snapshot()
                        PeopleContentPolicy.decide(
                            consentEnabled = true,
                            engineAvailable = true,
                            clusters = snapshot.clusters,
                            hiddenIds = snapshot.hidden,
                            names = snapshot.names,
                        )
                    }
                }.getOrElse { e ->
                    PeopleContentState.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * One Places read (r27): the catalogue's geotagged rows + pending
     * count through the r14 display gate, mapped by the SAME pure
     * policy the Places screen uses (the map's supply, read once more
     * per tab re-entry). Called from `init` and every screen re-entry
     * alongside [refreshPeople], so a Settings consent change lands on
     * the next visit.
     *
     * Failure handling (the honest choice for a policy with no Error
     * arm): a failed read KEEPS the last good state (a hiccup must not
     * wipe a working section into a fake empty card), is logged to the
     * diagnostics ring, and leaves a first-load failure in Loading ---
     * which the section renders as nothing (the People section's
     * Loading precedent), never as a lying card.
     */
    fun refreshPlaces() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    val displayable = MediaLocationDisplayRules.Default
                        .shouldDisplayGps(locationAccess.state.value)
                    PlacesContentPolicy.decide(
                        gpsDisplayable = displayable,
                        rows = getApplication<ToadGalleryApplication>()
                            .mediaCatalogue.geotaggedRows(),
                        pendingUngrantedRows = getApplication<ToadGalleryApplication>()
                            .mediaCatalogue.pendingUngrantedLocations(),
                    )
                }.onFailure { e ->
                    DiagLog.w(TAG, "refreshPlaces failed: ${e.message}")
                }.getOrNull()
            }
            if (fresh != null) _places.value = fresh
        }
    }

    /**
     * One Things read (r39): consent + availability + the collections
     * list + the clip count, mapped through the SHARED pure semantic
     * policy (the same six states Magic Search's Content mode uses).
     * Called from `init` and on every screen re-entry alongside the
     * People/Places reads. The consent-off, build-shape and
     * models-not-provisioned arms are decided WITHOUT opening an engine
     * session (the clip-count read happens only when every earlier gate
     * passes); failures map to the policy's honest Error state.
     */
    fun refreshThings() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching {
                    val consent = prefs.getBoolean(KEY_THINGS_ALBUM_ENABLED, false)
                    when {
                        !consent -> SemanticContentState.ConsentOff
                        !thingsRepository.engineAvailable -> SemanticContentState.EngineMissing
                        else -> {
                            val load = thingsRepository.collections()
                            _thingsCollections.value = load.state.collections
                            // The r26 provisioner's own SEMANTIC capability
                            // read (the MagicSearchViewModel gate's twin).
                            val semanticReady = getApplication<ToadGalleryApplication>()
                                .modelProvisioner.capabilityState(ModelManifest.Capability.SEMANTIC) ==
                                ModelProvisioner.CapabilityState.Ready
                            val clipCount = if (semanticReady) {
                                thingsRepository.clipCount()
                            } else {
                                0L
                            }
                            SemanticContentPolicy.decide(
                                consentEnabled = true,
                                engineAvailable = true,
                                semanticModelsReady = semanticReady,
                                clipCount = clipCount,
                            )
                        }
                    }
                }.getOrElse { e ->
                    SemanticContentState.Error(e.message ?: e.javaClass.simpleName)
                }
            }
            _thingsSection.value = fresh
        }
    }

    /**
     * Creates a Things collection (the section's create action, r39):
     * persists through the repository, then refreshes the section. A
     * refused create (blank name) simply refreshes --- the dialog's own
     * confirm button already refuses blank input.
     */
    fun createThing(name: String, query: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    thingsRepository.create(name, query, System.currentTimeMillis())
                }
            }
            refreshThings()
        }
    }

    /**
     * Removes a Things collection (the section's per-chip remove action,
     * r39); refreshes the section afterwards.
     */
    fun removeThing(collectionId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { thingsRepository.remove(collectionId) }
            }
            refreshThings()
        }
    }

    /**
     * Called by the screen once the runtime permission grant has succeeded.
     * A repeated grant (tab re-entry) re-queries --- album lists are cheap
     * snapshots and re-entry is also the below-R refresh path.
     */
    fun onPermissionGranted() {
        _state.value = AlbumsState.Loading
        reload()
    }

    /** Called when the user denies the permission or revokes it later. */
    fun onPermissionDenied() {
        _state.value = AlbumsState.PermissionNeeded
    }

    /**
     * Re-runs the album queries after an [AlbumsState.Error] (the error
     * state's retry action). Re-enters [AlbumsState.Loading] first --- the
     * same contract as `TrashViewModel.refresh` --- so a slow retry never
     * leaves a stale error card on screen while the query is in flight.
     */
    fun retry() {
        _state.value = AlbumsState.Loading
        reload()
    }

    override fun onCleared() {
        albumsObserver?.let { source.unregisterTimelineObserver(it) }
        albumsObserver = null
        // r25/r39: release the People and Things sections' engine sessions
        // with the screen. r50 (F-38): ONLY when neither index chain is
        // live --- the process-global native session is shared with the
        // background queues, and an unconditional close killed them
        // mid-batch. Skipped closes leak nothing (lazy re-open).
        if (!EngineSessionGuard.anyIndexChainLive(getApplication())) {
            // r25: release the People section's engine session with the screen.
            engineAdapter.close()
            // r39: the Things section's semantic session too (the same
            // process-global engine, released by its own adapter).
            thingsEngineAdapter.close()
        }
        super.onCleared()
    }
    /**
     * One (re)load: the bucket list plus the favorites pseudo-album entry
     * pinned on top (issue #66). The favorites query returns empty below Q
     * (see `MediaStoreSource.queryFavorites`), which simply means no card;
     * the per-bucket list is unchanged by the favorites feature.
     *
     * r19: the queries run inside `runCatching { ... }.getOrElse { ... }`
     * (the guard `AlbumDetailViewModel.load` already uses) because a
     * provider hiccup --- permission revoked mid-session, a provider
     * SQLiteException, an IllegalArgumentException from a missing column
     * --- previously escaped `viewModelScope.launch` UNCAUGHT and killed
     * the process; the UI audit found the Albums tab the only screen with
     * that escape (Search/Trash map to their Error states, Photos to the
     * Paging LoadState.Error, AlbumDetail to runCatching). The catch sits
     * INSIDE the IO hop, so cancellation of the hop itself still
     * propagates --- `runCatching` sees only provider throws (the queries
     * are plain blocking calls with no suspension points of their own).
     */
    private fun reload() {
        viewModelScope.launch {
            val ready: AlbumsState = withContext(Dispatchers.IO) {
                runCatching { queryReadyState() }
                    .getOrElse { AlbumsState.Error }
            }
            _state.value = ready
        }
    }

    /**
     * The success path of [reload]: both provider queries plus the Ready
     * assembly over them --- main-thread-free by construction (pure data
     * classes and a `getString`), so it runs whole inside the IO hop.
     */
    private fun queryReadyState(): AlbumsState.Ready {
        val albums = source.queryAlbums()
        val favorites = source.queryFavorites()
        return if (favorites.isEmpty()) {
            AlbumsState.Ready(albums)
        } else {
            // queryFavorites is newest-first, so the first row is the
            // cover; the pseudo-album keeps newest-first contents (v1).
            val newest = favorites.first()
            AlbumsState.Ready(
                listOf(
                    Album(
                        bucketId = Favorites.FAVORITES_BUCKET_ID,
                        bucketName = getApplication<Application>()
                            .getString(R.string.albums_favorites_title),
                        itemCount = favorites.size,
                        coverUri = newest.uri,
                        latestDateSec = newest.dateAddedSec,
                    ),
                ) + albums,
            )
        }
    }

    companion object {
        /** Diagnostics tag for the r27 Places section's read (DiagLog's W1 convention). */
        private const val TAG = "ToadPlaces"

        /**
         * MediaStore change-storm debounce, in milliseconds (issue #66);
         * same value and rationale as the Photos timeline's (#38): one
         * re-query per storm suffices because the fresh query sees every
         * change at once.
         */
        private const val RELOAD_DEBOUNCE_MS = 500L

        /** The Settings house preferences file (issue #27; see SettingsViewModel). */
        private const val PREFS_FILE = "toad_settings"
    }
}

sealed interface AlbumsState {
    /** Initial state and re-loading state. */
    data object Loading : AlbumsState

    /** No media access; show the permission state with a Grant button. */
    data object PermissionNeeded : AlbumsState

    /**
     * The provider query failed (permission revoked mid-session, a
     * provider SQLiteException, a missing column); the screen shows the
     * error state with a retry action (r19). Distinct from `Ready` with an
     * empty list --- that one is a clean library, this one is a failed
     * read; the honest-states doctrine forbids masking one as the other.
     */
    data object Error : AlbumsState

    /**
     * Media access granted; `albums` may be empty (clean library, not an
     * error). Since issue #66 a non-empty favorites set pins the
     * pseudo-album entry first ([Favorites.FAVORITES_BUCKET_ID]).
     */
    data class Ready(val albums: List<Album>) : AlbumsState
}
