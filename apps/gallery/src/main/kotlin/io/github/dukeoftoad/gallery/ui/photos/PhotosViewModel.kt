package io.github.dukeoftoad.gallery.ui.photos

import android.app.Application
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.dukeoftoad.gallery.ToadGalleryApplication
import io.github.dukeoftoad.gallery.data.Album
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.MediaStoreSource
import io.github.dukeoftoad.gallery.data.TimelineSort
import io.github.dukeoftoad.gallery.data.WindowPagingSource
import io.github.dukeoftoad.gallery.data.catalogue.CatalogueRecord
import io.github.dukeoftoad.gallery.data.catalogue.MediaCatalogue
import io.github.dukeoftoad.gallery.data.catalogue.TimelineDirection
import io.github.dukeoftoad.gallery.data.catalogue.room.RoomMediaCatalogue
import io.github.dukeoftoad.gallery.data.filters.TimelineFilter
import io.github.dukeoftoad.gallery.data.filters.TimelineFilterCatalogue
import io.github.dukeoftoad.gallery.data.filters.TimelineFilterFields
import io.github.dukeoftoad.gallery.data.filters.matches
import io.github.dukeoftoad.gallery.data.media.identity.MediaIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * State holder for the Photos timeline (Phase 3, issue #25; paging since #28;
 * review follow-ups #38; catalogue-backed ordering since r13; smart-filter
 * chips since r17).
 *
 * Three states, derived from the user's permission decision:
 *  - `Loading`        : first frame, before the first permission check resolves
 *  - `PermissionNeeded`: no media access yet (initial denial, or revoked later)
 *  - `Ready`          : media access granted; carries the *paging stream*
 *                      (`Flow<PagingData<MediaItem>>`), not a materialized list.
 *
 * ## The r13 source switch: catalogue-backed timeline, MediaStore fallback
 * One generation's windows are served by exactly ONE backend, chosen when the
 * generation is built ([timelineSource]): the media catalogue
 * ([TimelineSource.CATALOGUE]) as soon as it holds at least one live record,
 * else today's MediaStore path unchanged
 * ([TimelineSource.MEDIA_STORE_FALLBACK]). The catalogue windows are ordered
 * by the EFFECTIVE instant --- the resolved capture date the scan pipeline's
 * index pass read out of the item's own metadata, else `dateAdded` --- which
 * is the r13 behavior change: the timeline's SORT and GROUP keys follow when
 * a photo was TAKEN, not when the device happened to import it. The fallback
 * is strictly the first-cold-start window: an EMPTY catalogue means "the
 * first scan has not populated yet", never a permanent state on a granted
 * library (the app-start scan reconciles MediaStore into the catalogue, and
 * every later change flows through it) --- the moment the first records
 * land, the catalogue's `changes()` flow flips the timeline onto the
 * catalogue path (see the invalidation section below).
 *
 * ## Eventual consistency (the price of catalogue-backed ordering)
 * On [TimelineSource.CATALOGUE] the timeline is EVENTUALLY consistent with
 * MediaStore: a fresh camera shot appears only after the scan pipeline
 * reconciles it into the catalogue (typically seconds), NOT the moment the
 * observer sees the provider write. That is deliberate, and it is the price
 * of catalogue-backed ordering: the ordering key itself --- the resolved
 * capture date --- is produced by the scan (the indexer must open the item's
 * bytes to read EXIF/XMP/video headers), so a window read before
 * reconciliation could not be ordered correctly anyway. The MediaStore
 * `ContentObserver` is therefore gated OFF on the catalogue path (an early
 * invalidate would only re-read the same un-reconciled windows); the
 * catalogue's own `changes()` flow is THE invalidation source there.
 *
 * ## Paging pipeline (issue #28)
 * [onPermissionGranted] builds ONE `Pager` per grant and publishes its flow
 * inside `Ready`, `cachedIn(viewModelScope)`. Building the `Pager` is cheap
 * (no query runs at construction); the actual loads fire when the screen
 * collects the stream. The flow instance is stable for as long as the
 * state stays `Ready` --- `collectAsLazyPagingItems` in the screen must not
 * restart on recomposition, which it would if a fresh `Pager` were built per
 * frame. `cachedIn` additionally survives tab switches, and since the issue
 * #38 idempotence fix that is the whole re-entry story: [onPermissionGranted]
 * early-returns while the state is already [PhotosState.Ready], so
 * re-entering Photos re-collects the cached stream instead of rebuilding the
 * `Pager` and re-querying from offset 0.
 *
 * The `PagingSource` is the generic [WindowPagingSource] over positional
 * offsets (window contract in its KDoc):
 *  - CATALOGUE (any API level; Room has no platform gate): one
 *    [MediaCatalogue.timelineWindow] read per page, ordered by the effective
 *    instant with the local-media-key tiebreak (the catalogue's SQL owns
 *    that total order), mapped to [MediaItem] by the pure
 *    [catalogueTimelineItem] mapper below.
 *  - MEDIA_STORE_FALLBACK, API 30+ : native MediaStore windows via
 *    [MediaStoreSource.queryTimelineWindow] --- one Bundle-args query per
 *    page over the unified Files collection (`date_added DESC, _id DESC`),
 *    so first paint waits for 60 items, not for the whole library.
 *  - MEDIA_STORE_FALLBACK, below 30 : the full `queryTimeline()` snapshot,
 *    memoized once per PagingSource generation and sliced in-memory
 *    (`drop`/`take`). Same window math and same state shell; only the memory
 *    profile stays the pre-paging one (windowed Bundle queries are not
 *    reliable there).
 *
 * ## Sorting (issue #66)
 * [sort] is per-session ViewModel state (persistence is deliberately out
 * of scope for #66). Changing it through [onSortChange] does NOT mutate any
 * live generation --- a generation's windows must all tile ONE total order,
 * so the ViewModel builds a fresh `Pager` (fresh `PagingSource`, fresh
 * `cachedIn` stream) and swaps it into a new [PhotosState.Ready]. The
 * screen's `collectAsLazyPagingItems` re-collects the new flow, restarts
 * from offset 0, and the paging factory hands the new [currentSource] to
 * the SAME observer-invalidation pipeline --- a sort change is simply the
 * next generation, exactly like an observer-driven invalidation ending one.
 * Since r13 the direction maps one-to-one onto the catalogue's
 * [TimelineDirection] ([toCatalogueDirection]) when the generation runs on
 * the catalogue backend.
 *
 * ## Smart-filter chips (r17) + the folder chip (r19b)
 * [filter] is per-session ViewModel state (same persistence scope as
 * [sort]: deliberately NOT persisted), written through [onFilterChange]
 * and applied to BOTH data paths by the SAME generation mechanics a sort
 * change uses --- a filter change builds a fresh `Pager` generation that
 * captures the filter, so every window of the generation tiles ONE
 * filtered total order:
 *  - CATALOGUE: [RoomMediaCatalogue.filteredTimelineWindow] applies the
 *    pure engine's WHERE projection (mimeType/isFavorite/bucketId/
 *    dateAddedMillis --- the columns `CatalogueItemEntity` persists; the
 *    bucket pair since the r19 Room v5) before the LIMIT/OFFSET, keeping
 *    windows positional over the FILTERED order;
 *  - MEDIA_STORE_FALLBACK, API 30+: the engine's MediaStore selection
 *    projection AND-joins into `queryTimelineWindow`'s base selection
 *    (fragment via `QUERY_ARG_SQL_SELECTION`, value-dimension args via
 *    `QUERY_ARG_SQL_SELECTION_ARGS`); every engine dimension serves there,
 *    so no surfaced filter ever leaves this leg;
 *  - MEDIA_STORE_FALLBACK, snapshot leg (below 30, or a non-Room catalogue
 *    binding with an active filter it cannot serve): the full
 *    `queryTimeline()` snapshot is filtered IN MEMORY with the engine's
 *    predicate before the drop/take slicing, which keeps the window math
 *    exact because the snapshot is fully materialized first. (The one
 *    dimension memory cannot evaluate --- a bucket, no bucket field on
 *    `MediaItem` --- fails CLOSED there: a visibly empty timeline, never a
 *    silently unfiltered one. Since r19b the surfaced folder chip CAN
 *    reach this leg on a pre-30 device; the nothing-matched state keeps
 *    the chips row rendered, so the way back from that honest dead end
 *    is one tap, not a dead UI.)
 *
 * The backend ladder itself ([timelineSource]) is filter-aware: since the
 * r19 Room v5 bucket columns the catalogue projection serves every
 * dimension the engine defines ([TimelineFilterCatalogue.canServe]), so
 * only a non-Room catalogue binding (test wiring, which has no
 * filtered-window method) keeps a generation on the MediaStore fallback,
 * which serves every filter the UI can surface. The grid, the empty state
 * and the item count therefore stay honest on every path: nothing is
 * filtered client-side after paging.
 *
 * ## The folder chip + picker (r19b)
 * The folder dimension ([TimelineFilter.bucketId]) is surfaced as the
 * chips row's LEADING chip, which opens a picker sheet rather than
 * toggling in place --- its value space is the device's bucket list, not
 * a fixed enum. The sheet lists the folders the move picker's own query
 * returns (`MediaStoreSource.queryAlbums`, re-queried on every open),
 * and the ViewModel keeps two extra pieces of per-session state for it:
 *  - [selectedFolderName]: the active bucket's display name (the pure
 *    filter carries only the id --- names collide across volumes, ids do
 *    not); [onFilterChange] drops it whenever the bucket dimension
 *    changes, and only [onFolderSelected] re-fills it;
 *  - [folders]: the picker sheet's list state, which distinguishes a
 *    FAILED query (the sheet renders its honest error + retry) from a
 *    loaded list --- never a silent empty list.
 *
 * ## Dataset drift -> generational invalidation (issue #38, finding 1)
 * LIMIT/OFFSET windows are positional: if the dataset changes between two
 * windows of the SAME generation (camera shot, download, delete), the
 * offsets of later windows no longer tile the new dataset --- a later
 * window can re-emit an item the grid already shows, and the
 * `uri.toString()` LazyGrid key would collide (foundation lazy grids throw
 * on duplicate keys). The ViewModel therefore NEVER appends deltas; change
 * notifications are debounced into generation invalidations, and each
 * backend has exactly one source:
 *  - MEDIA_STORE_FALLBACK: the MediaStore `ContentObserver` (registered in
 *    `init` via [MediaStoreSource.registerTimelineObserver], API 30+ only)
 *    funnels into [timelineChanges]; a debounced collector calls
 *    `currentSource.invalidate()` --- today's pipeline unchanged.
 *  - CATALOGUE: [MediaCatalogue.changes] (collected in `init`, debounced
 *    the same way) --- each emission either invalidates the live source
 *    (same backend: anchored refresh via `getRefreshKey`) or, when the
 *    change flipped the backend choice (the first scan populated the
 *    catalogue), builds a whole fresh generation, because only a fresh
 *    `Pager` can swap the window loader its factory captured.
 * Invalidating ends the generation and makes the `Pager` build a fresh
 * source whose refresh re-anchors via `getRefreshKey` (snapped to the
 * window lattice), so every window of the new generation is computed
 * against one consistent dataset.
 *
 * The observer is registered on API 30+ only, matching the windowed
 * protocol it serves; below 30 one memoized in-memory snapshot serves a
 * whole fallback generation, so windows cannot drift apart mid-generation
 * (those devices keep the documented snapshot behavior; their staleness
 * trade-off is the ADR-0015 open question). The catalogue path has no such
 * gate --- Room serves windows on every API level.
 *
 * ## Why `initialLoadSize == pageSize`
 * `initialLoadSize` defaults to `pageSize * 3`; a larger first window would
 * misalign the second window's `prevKey` into the middle of the first, so a
 * prepend (fast upward scroll) would re-emit rows the grid already shows as
 * duplicates. Both are pinned to 60. Placeholders are disabled: the windowed
 * protocol has no cheap total count (it would cost one `COUNT(*)` per page)
 * and nothing in the UI consumes placeholder slots.
 *
 * Privacy: the stream carries local content URIs only (ADR-0006); the two
 * Paging artifacts are pure-JVM androidx libraries with no network surface,
 * and both invalidation sources are local --- the MediaStore observer
 * watches the local provider, the catalogue is an on-device Room database
 * --- so the offline guarantee and the INTERNET gate are unaffected
 * (ADR-0014).
 */
class PhotosViewModel(app: Application) : AndroidViewModel(app) {

    private val source = MediaStoreSource(app)

    /**
     * The process-wide media catalogue (r13): the persistent
     * "what did the media world look like at the last authoritative scan"
     * cache the scan pipeline writes and this timeline reads. One shared
     * instance (held by [ToadGalleryApplication]) is REQUIRED, not a
     * convenience: Room invalidation only reaches observers of the same
     * database instance, so a second catalogue over the same file would
     * silently miss every `changes()` emission.
     */
    private val catalogue: MediaCatalogue =
        getApplication<ToadGalleryApplication>().mediaCatalogue

    /**
     * The catalogue binding as the concrete Room implementation (r17): the
     * filtered timeline windows live on [RoomMediaCatalogue]
     * ([RoomMediaCatalogue.filteredTimelineWindow]) because the
     * [MediaCatalogue] interface and its in-memory reference twin are
     * frozen for the parallel r15-r18 rounds. The production shell ALWAYS
     * binds Room here (`ToadGalleryApplication.mediaCatalogue`), so this
     * cast succeeds in every real process; the `as?` keeps a non-Room
     * test wiring honest instead of crashing, and the backend ladder
     * ([timelineSource]) then routes active filters onto the MediaStore
     * fallback, which serves every filter the chips can surface.
     */
    private val roomCatalogue: RoomMediaCatalogue? = catalogue as? RoomMediaCatalogue

    private val _state = MutableStateFlow<PhotosState>(PhotosState.Loading)
    val state: StateFlow<PhotosState> = _state.asStateFlow()

    /**
     * The timeline's sort direction (issue #66): per-session ViewModel state,
     * newest-first by default. Read by the screen's sort menu; written only
     * through [onSortChange], which rebuilds the paging pipeline.
     */
    private val _sort = MutableStateFlow(TimelineSort.NEWEST_FIRST)
    val sort: StateFlow<TimelineSort> = _sort.asStateFlow()

    /**
     * The timeline's smart-filter state (r17): per-session ViewModel state
     * (survives config changes with the ViewModel, deliberately NOT
     * persisted across process death), read by the screen's chips row and
     * written only through [onFilterChange], which rebuilds the paging
     * pipeline. A fresh [TimelineFilter] is the unfiltered timeline --- the
     * pre-r17 behavior, exactly.
     */
    private val _filter = MutableStateFlow(TimelineFilter())
    val filter: StateFlow<TimelineFilter> = _filter.asStateFlow()

    /**
     * The display name of the filter's selected folder (r19b): the label
     * the chips row's folder chip carries while the bucket dimension is
     * active. The pure [TimelineFilter] stores only the bucket ID --- ids
     * are the filter dimension, names collide across volumes --- so the
     * name rides here as per-session ViewModel state (same survival scope
     * as the filter itself), kept honest by [onFilterChange]: any filter
     * change that moves or clears the bucket drops the name, and only
     * [onFolderSelected], which knows the picked name, re-fills it.
     */
    private val _selectedFolderName = MutableStateFlow<String?>(null)
    val selectedFolderName: StateFlow<String?> = _selectedFolderName.asStateFlow()

    /**
     * The folder-picker sheet's list state (r19b), written by
     * [refreshFolders]. Distinguishing a FAILED query from a loaded-but-
     * empty list is the state's whole point: the sheet renders the honest
     * error with its own retry for the former, never a silent empty list.
     * Loading is also the pre-first-open value --- the sheet only composes
     * while open, so a stale list never renders.
     */
    private val _folders = MutableStateFlow(PhotosFoldersState.Loading)
    val folders: StateFlow<PhotosFoldersState> = _folders.asStateFlow()

    /**
     * The [WindowPagingSource] of the CURRENT Pager generation (issue #38
     * finding 1). The paging factory --- invoked from the collection scope
     * that `cachedIn(viewModelScope)` binds to, i.e. the main dispatcher ---
     * refreshes this field on every generation, and the debounced change
     * collectors below read it to invalidate. Both accesses run on the
     * main dispatcher, so no further synchronization is needed.
     */
    private var currentSource: WindowPagingSource<MediaItem>? = null

    /**
     * The backend the CURRENT generation's factory captured (r13): written
     * by [timelineWindowPagingSource] on the main dispatcher (see
     * [currentSource]'s synchronization note), read by the debounced change
     * collectors to route each notification --- MediaStore observer signals
     * only drive the fallback; catalogue `changes()` emissions drive the
     * catalogue path (and any backend flip).
     */
    private var currentBackend: TimelineSource? = null

    /**
     * The in-flight generation build, if any (r13): building a generation
     * now resolves the backend choice first ([MediaCatalogue.liveItemCount]
     * is suspend), so the build is a coroutine. Tracking its [Job] keeps
     * [onPermissionGranted] idempotent across the async window (issue #38)
     * and lets a newer request (sort change, backend flip) cancel a
     * superseded build instead of racing it to publish.
     */
    private var generationJob: Job? = null

    /**
     * MediaStore change notifications, emitted by the observer registered in
     * `init`. Capacity 1 with DROP_OLDEST coalesces storms into one pending
     * signal (`ContentObserver` fires per changed row during device-wide
     * media scans) and `tryEmit` can never block or fail.
     */
    private val timelineChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Registered in `init` (API 30+ only); unregistered in [onCleared]. */
    private var timelineObserver: ContentObserver? = null

    init {
        // API 30+ only: the drift risk this observer mitigates is specific
        // to the windowed protocol, where each window is a fresh positional
        // query against a dataset that may have changed between windows.
        // Below 30 the memoized snapshot serves the whole generation and
        // cannot drift mid-generation; paying a full re-walk per change
        // storm there would only churn. The `Build.VERSION.SDK_INT` check
        // is the lexically lint-visible guard for the `@RequiresApi(R)`
        // call (same pattern as [timelineWindowPagingSource]).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            timelineObserver = source.registerTimelineObserver {
                // Called on the main looper (the observer's Handler); tryEmit
                // is non-suspending, so this stays cheap under storms.
                timelineChanges.tryEmit(Unit)
            }
            viewModelScope.launch {
                // Debounce the storms: invalidating per row would thrash
                // Pager generations (each one tears down windows and
                // re-queries). One invalidate per storm is enough --- it is
                // idempotent, and the fresh generation sees every change.
                // Gated to the fallback backend (r13): on CATALOGUE the
                // timeline is eventually consistent by contract, and a
                // MediaStore-driven invalidate would only re-read windows
                // the scan has not reconciled yet --- the catalogue's own
                // changes() flow is the invalidation source there.
                timelineChanges
                    .debounce(INVALIDATION_DEBOUNCE_MS.milliseconds)
                    .collect {
                        if (currentBackend == TimelineSource.MEDIA_STORE_FALLBACK) {
                            currentSource?.invalidate()
                        }
                    }
            }
        }
        viewModelScope.launch {
            // The catalogue's change signal (r13): debounced with the same
            // discipline as the MediaStore observer --- the reconciler
            // emits per scan batch, and invalidating per batch during a
            // bulk first scan would thrash generations exactly like a
            // device-wide media scan storm. One invalidate per storm
            // suffices; the fresh generation re-reads everything at once.
            catalogue.changes()
                .debounce(INVALIDATION_DEBOUNCE_MS.milliseconds)
                .collect { onCatalogueChanged() }
        }
    }

    override fun onCleared() {
        timelineObserver?.let { source.unregisterTimelineObserver(it) }
        timelineObserver = null
        super.onCleared()
    }

    /**
     * Called by the screen once the runtime permission grant has succeeded.
     * Publishes a fresh [PhotosState.Ready] wrapping a new `Pager` (one per
     * grant). Idempotent (issue #38, finding 3): while the state is already
     * [PhotosState.Ready] this returns early --- re-entering the Photos tab
     * must re-collect the existing `cachedIn` stream, not tear the current
     * generation down and re-query from offset 0 --- and since r13 the
     * idempotence also covers the async build window (the backend choice is
     * a suspend Room read, so a second grant while [generationJob] is still
     * resolving must not start a second build). From here on, load
     * progress / end-of-data / failures surface through the paging
     * `LoadState` that the screen observes inside `Ready` --- the shell
     * states stay permission-driven only.
     */
    fun onPermissionGranted() {
        if (_state.value is PhotosState.Ready) return
        if (generationJob?.isActive == true) return
        _state.value = PhotosState.Loading
        startGeneration()
    }

    /**
     * Called when the user denies the permission or revokes it later.
     * Since the r19b permission-race fix (audit B6) this also tears down
     * any in-flight generation build and forgets the live generation's
     * source/backend: a build launched while access was briefly granted
     * must not complete INTO a stale [PhotosState.Ready] over the denial
     * (the grid would render un-grantable thumbnails behind the permission
     * state), and the debounced change collectors must find nothing live
     * to invalidate. The next grant rebuilds fresh from scratch
     * ([onPermissionGranted]).
     */
    fun onPermissionDenied() {
        generationJob?.cancel()
        currentSource = null
        currentBackend = null
        _state.value = PhotosState.PermissionNeeded
    }

    /**
     * Changes the timeline's sort direction (issue #66). No-op for the
     * current value; otherwise the ready timeline is REPLACED by a fresh
     * `Pager` generation carrying the new order (see the class KDoc's
     * sorting section for why a live generation is never re-sorted
     * mid-flight). While the shell is not `Ready` (permission pending) the
     * value is only stored --- the next grant builds with it.
     */
    fun onSortChange(newSort: TimelineSort) {
        if (_sort.value == newSort) return
        _sort.value = newSort
        if (_state.value is PhotosState.Ready) {
            startGeneration()
        }
    }

    /**
     * Changes the timeline's smart filter (r17). No-op for the current
     * value; otherwise the ready timeline is REPLACED by a fresh `Pager`
     * generation capturing the new filter (see the class KDoc's
     * smart-filter section for why a live generation is never re-filtered
     * mid-flight --- the same total-order argument as a sort change). While
     * the shell is not `Ready` (permission pending) the value is only
     * stored --- the next grant builds with it. The screen also feeds the
     * Collections hand-off ([PhotosFilterHandoff]) through here, and every
     * folder-dimension write ([onFolderSelected] / [onFolderCleared])
     * lands here too, keeping ONE filter pipeline.
     */
    fun onFilterChange(newFilter: TimelineFilter) {
        val oldFilter = _filter.value
        if (oldFilter == newFilter) return
        _filter.value = newFilter
        // Folder-name sync (r19b): the folder chip's active label needs
        // the selected bucket's display name, which the pure filter
        // cannot carry. Drop the cached name whenever the bucket
        // dimension changes --- every filter writer flows through here
        // (the type/favorites toggles, the All chip, the Clear action,
        // the Collections hand-off), and only [onFolderSelected] knows a
        // newly picked name, so it re-fills the name after this call. A
        // dropped name renders the honest generic label; a kept-stale
        // one would label the WRONG folder.
        if (newFilter.bucketId != oldFilter.bucketId) {
            _selectedFolderName.value = null
        }
        if (_state.value is PhotosState.Ready) {
            startGeneration()
        }
    }

    /**
     * Re-queries the device's media folders for the picker sheet (r19b):
     * the SAME [MediaStoreSource.queryAlbums] query the file-ops move
     * picker lists (bucket ids + display names + live item counts,
     * images and videos merged per bucket), run on this ViewModel's own
     * source. Called every time the sheet opens, and by the sheet's
     * retry --- buckets and counts change as ops land. Mirrors
     * [FileOpsViewModel.refreshAlbums]'s guarded shape (the runCatching
     * sits INSIDE the IO hop, so cancellation of the hop itself still
     * propagates) but maps a failure onto [PhotosFoldersState.Error]
     * instead of a snackbar + empty list: the folder sheet renders the
     * honest failure itself, with its own retry action.
     */
    fun refreshFolders() {
        _folders.value = PhotosFoldersState.Loading
        viewModelScope.launch {
            val queried = withContext(Dispatchers.IO) {
                runCatching { source.queryAlbums() }.getOrNull()
            }
            _folders.value = if (queried != null) {
                PhotosFoldersState.Ready(queried)
            } else {
                PhotosFoldersState.Error
            }
        }
    }

    /**
     * Applies the picker sheet's picked folder (r19b): writes the bucket
     * dimension through [onFilterChange] --- the same fresh-generation
     * mechanics every filter change uses, composing AND with the
     * type/favorites dimensions on BOTH backends --- then re-fills
     * [selectedFolderName], which [onFilterChange]'s bucket-change sync
     * just dropped. Re-picking the already-selected folder changes
     * nothing observable (the filter write no-ops; the name re-set is
     * idempotent).
     */
    fun onFolderSelected(album: Album) {
        onFilterChange(_filter.value.copy(bucketId = album.bucketId))
        _selectedFolderName.value = album.bucketName
    }

    /**
     * Clears the folder dimension only (r19b): the picker sheet's "All
     * folders" row --- deliberately distinct from the chips row's All
     * chip and Clear action, which reset EVERY dimension. The type and
     * favorites dimensions pass through untouched; no-op when no folder
     * is selected.
     */
    fun onFolderCleared() {
        if (_filter.value.bucketId == null) return
        onFilterChange(_filter.value.copy(bucketId = null))
    }

    /**
     * One debounced catalogue change landed (r13): the timeline must
     * re-read, and HOW depends on whether the change also flipped the
     * backend choice. Since the r19b permission-race fix (audit B6) the
     * method reacts only to a READY timeline:
     *
     *  - FLIP (first scan populated the catalogue; the theoretical
     *    reverse, a wiped library emptying it): only a fresh generation
     *    can switch backends --- the live Pager's factory captured its
     *    backend when it was built.
     *  - SAME backend: a plain [WindowPagingSource.invalidate] --- the
     *    anchored-refresh path the MediaStore observer has always
     *    driven --- because the order key did not change, only the
     *    content under it (new shots, resolved capture dates,
     *    tombstones).
     *  - PermissionNeeded: nothing is live ([onPermissionDenied] also
     *    cancelled any in-flight build); the next grant resolves the
     *    backend against the settled catalogue anyway.
     *  - Loading: the system permission dialog is up, or a just-granted
     *    build is still resolving its backend. The pre-r19b code let the
     *    first case build a generation behind the dialog (the B6 race:
     *    an un-grantable Ready grid rendered behind the dialog, then a
     *    grant that early-returns "already Ready" leaves broken cells);
     *    awaiting the grant is strictly better --- the grant path builds
     *    fresh against the settled catalogue. The second case's residual
     *    edge (the change consumed while the granted build is mid-flight)
     *    is a millisecond window whose worst case is the pre-r13 fallback
     *    ordering until the NEXT catalogue change flips it --- correct
     *    content, superseded ordering, self-healing.
     */
    private suspend fun onCatalogueChanged() {
        if (_state.value !is PhotosState.Ready) return
        if (currentTimelineSource() != currentBackend) {
            startGeneration()
        } else {
            currentSource?.invalidate()
        }
    }

    /**
     * Launches (or relaunches) the one generation-build coroutine
     * ([publishNextGeneration]), cancelling any superseded in-flight build
     * first. Main-thread only, like every generation transition.
     */
    private fun startGeneration() {
        generationJob?.cancel()
        generationJob = viewModelScope.launch { publishNextGeneration() }
    }

    /**
     * Resolves the backend for the next generation and publishes it as a
     * fresh [PhotosState.Ready]. The sort AND the filter are read at
     * PUBLISH time, not launch time: a rapid sort/filter toggle can
     * supersede this coroutine while it awaits [MediaCatalogue
     * .liveItemCount], and publishing the superseded values would leave
     * the grid disagreeing with the menu/chips; the superseding build
     * publishes the fresh values regardless.
     */
    private suspend fun publishNextGeneration() {
        val backend = currentTimelineSource()
        _state.value = PhotosState.Ready(
            buildPagingData(sort = _sort.value, filter = _filter.value, backend = backend),
        )
    }

    /**
     * The backend choice for a generation about to be built: the catalogue
     * drives iff it is populated AND (since r17) the binding can serve the
     * active filter. One suspend Room read ([MediaCatalogue
     * .liveItemCount]) on [Dispatchers.IO]; the pure decision itself is
     * [timelineSource] (JVM-tested).
     */
    private suspend fun currentTimelineSource(): TimelineSource {
        val filter = _filter.value
        val catalogueServesFilter = !filter.isActive ||
            (roomCatalogue != null && TimelineFilterCatalogue.canServe(filter))
        return timelineSource(
            cataloguePopulated = withContext(Dispatchers.IO) { catalogue.liveItemCount() } > 0,
            catalogueServesFilter = catalogueServesFilter,
        )
    }

    /**
     * One `Pager` + `cachedIn(viewModelScope)` stream for one generation
     * (one permission grant, one sort change, one filter change, or one
     * backend flip). The paging source factory captures the generation's
     * [TimelineSort], [TimelineFilter] AND [TimelineSource]; every window
     * of the generation therefore tiles the same total order, over the
     * same filtered slice, from the same backend.
     */
    private fun buildPagingData(
        sort: TimelineSort,
        filter: TimelineFilter,
        backend: TimelineSource,
    ): Flow<PagingData<MediaItem>> =
        Pager(
            config = PagingConfig(
                pageSize = TIMELINE_PAGE_SIZE,
                // == pageSize on purpose; see the class KDoc for why a bigger
                // initial window would duplicate rows on prepend.
                initialLoadSize = TIMELINE_PAGE_SIZE,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = { timelineWindowPagingSource(sort, filter, backend) },
        ).flow.cachedIn(viewModelScope)

    /**
     * One [WindowPagingSource] per Pager generation, backed by [backend],
     * ordered by the generation's [sort] (issue #66; the backend split
     * since r13) and narrowed to the generation's [filter] (r17) INSIDE
     * every window query --- positional windows must filter in-query or
     * offsets stop tiling, which is the same total-order argument that
     * forbids re-sorting a live generation. The explicit
     * `Build.VERSION.SDK_INT` check (not `supportsWindowedQueries()`) is
     * what NewApi lint recognizes as the guard for the `@RequiresApi(30)`
     * call --- note it gates only the FALLBACK branch; the catalogue
     * branch is plain Room and serves every API level. Every generation
     * also becomes [currentSource] (and its backend [currentBackend]), so
     * the debounced change collectors invalidate exactly the live source
     * through the right channel.
     */
    private fun timelineWindowPagingSource(
        sort: TimelineSort,
        filter: TimelineFilter,
        backend: TimelineSource,
    ): WindowPagingSource<MediaItem> {
        val newSource = when (backend) {
            TimelineSource.CATALOGUE -> WindowPagingSource { offset, limit ->
                withContext(Dispatchers.IO) {
                    // The catalogue owns the total order here (effective
                    // instant, local-media-key tiebreak --- its SQL is the
                    // ordering contract); this side only maps rows. The
                    // mapping is the pure [catalogueTimelineItem] core with
                    // `Uri.parse` at this thin seam.
                    //
                    // r17: an INACTIVE filter keeps the pre-r17 interface
                    // read byte-identical; an ACTIVE one takes the filtered
                    // twin on the Room binding. The backend ladder
                    // ([currentTimelineSource]) only routes here for an
                    // active filter when the Room binding exists AND the
                    // engine's canServe gate passes, so the fail-closed
                    // `?: emptyList()` below is unreachable in practice ---
                    // and deliberately visible-empty (never silently
                    // unfiltered) if a future caller violates the ladder.
                    val records =
                        if (!filter.isActive) {
                            catalogue.timelineWindow(
                                offset,
                                limit,
                                sort.toCatalogueDirection(),
                            )
                        } else {
                            roomCatalogue?.filteredTimelineWindow(
                                offset,
                                limit,
                                sort.toCatalogueDirection(),
                                where = TimelineFilterCatalogue.where(filter),
                            ) ?: emptyList()
                        }
                    records.map { it.asTimelineItem().asMediaItem() }
                }
            }

            TimelineSource.MEDIA_STORE_FALLBACK ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Native windows: one Bundle-args MediaStore query per
                    // page, ordered by the generation's sort (the provider
                    // applies the total order; the _id tiebreak keeps
                    // windows aligned) and filtered by the generation's
                    // filter inside the same query (selection fragment +
                    // bound args, both built by the pure engine inside
                    // `queryTimelineWindow`). Exactly the pre-r13 path when
                    // the filter is inactive.
                    WindowPagingSource { offset, limit ->
                        withContext(Dispatchers.IO) {
                            source.queryTimelineWindow(offset, limit, sort, filter)
                        }
                    }
                } else {
                    // Pre-30 fallback: one full snapshot per generation,
                    // memoized on first load in the generation's order
                    // (TimelineSort applies the same date+_id total order
                    // in memory), FILTERED in memory by the engine's
                    // predicate BEFORE the memoization (r17) --- so the
                    // drop/take slicing below stays exact over the
                    // FILTERED list --- then sliced with the same window
                    // contract. Exactly the pre-r13 path when the filter
                    // is inactive (the empty filter's predicate passes
                    // every item).
                    var snapshot: List<MediaItem>? = null
                    WindowPagingSource { offset, limit ->
                        withContext(Dispatchers.IO) {
                            (snapshot ?: sort
                                .sortItems(
                                    source.queryTimeline().let { items ->
                                        if (filter.isActive) {
                                            items.filter { item ->
                                                filter.matches(item.asFilterFields())
                                            }
                                        } else {
                                            items
                                        }
                                    },
                                    { it.dateAddedSec },
                                    { it.id },
                                )
                                .also { snapshot = it })
                                .drop(offset)
                                .take(limit)
                        }
                    }
                }
        }
        currentSource = newSource
        currentBackend = backend
        return newSource
    }

    private companion object {
        /** Timeline page size; doubles as the initial load size (see KDoc). */
        const val TIMELINE_PAGE_SIZE = 60

        /**
         * Change-storm debounce, in milliseconds (issue #38). Both
         * invalidation sources fire in storms --- the MediaStore
         * ContentObserver per changed row (device-wide scans notify
         * hundreds of times), the catalogue's changes() per scan batch
         * (a bulk first scan reconciles in many batches) --- and
         * invalidating per emission would thrash Pager generations, while
         * one invalidate per storm suffices because the fresh generation
         * re-queries everything at once.
         */
        const val INVALIDATION_DEBOUNCE_MS = 500L
    }
}

/**
 * The window backend one timeline generation reads (r13).
 *
 * The catalogue is the steady state on a granted library; the fallback is
 * the first-cold-start window only (see [timelineSource]).
 */
internal enum class TimelineSource {
    /**
     * Room catalogue windows ([MediaCatalogue.timelineWindow]), ordered by
     * the EFFECTIVE instant (resolved capture date, else `dateAdded`; the
     * catalogue's SQL owns that total order). Available on every API level.
     */
    CATALOGUE,

    /**
     * Today's MediaStore path, unchanged: native Bundle-args windows on
     * API 30+ (`date_added DESC, _id DESC`), the memoized full-snapshot
     * slice below 30.
     */
    MEDIA_STORE_FALLBACK,
}

/**
 * The pure backend choice for one timeline generation (r13, JVM-tested;
 * filter-aware since r17): the catalogue drives iff it holds at least one
 * live record AND the binding can serve the generation's active filter
 * ([catalogueServesFilter] --- an inactive filter is always serveable, so
 * the default keeps the pre-r17 decision exactly).
 *
 * An EMPTY catalogue means "the first scan has not populated yet" --- the
 * first cold start on a fresh install (or after the app's data was
 * cleared) --- and NEVER a permanent state on a granted library: the scan
 * pipeline's app-start pass reconciles MediaStore into the catalogue, and
 * every later change arrives through its observers. Until then the
 * timeline keeps the MediaStore path, which needs no scan and orders by
 * `date_added`; the first catalogue change flips the generation onto the
 * catalogue path (see `onCatalogueChanged`).
 *
 * A filter the catalogue CANNOT serve (a non-Room catalogue binding,
 * which has no filtered-window method --- test wiring only; since the
 * r19 Room v5 bucket columns the projection serves every dimension the
 * engine defines) also keeps the generation on the MediaStore fallback,
 * which serves every engine dimension; the Android side composes the
 * boolean from the binding and the pure engine's
 * `TimelineFilterCatalogue.canServe` gate, keeping this decision itself
 * JVM-pure.
 */
internal fun timelineSource(
    cataloguePopulated: Boolean,
    catalogueServesFilter: Boolean = true,
): TimelineSource = when {
    !cataloguePopulated -> TimelineSource.MEDIA_STORE_FALLBACK
    !catalogueServesFilter -> TimelineSource.MEDIA_STORE_FALLBACK
    else -> TimelineSource.CATALOGUE
}

/**
 * Maps the timeline's [TimelineSort] onto the catalogue's
 * [TimelineDirection] (r13): one-to-one, both directions. The catalogue
 * backend consumes this; the MediaStore fallback keeps [TimelineSort]
 * itself.
 */
internal fun TimelineSort.toCatalogueDirection(): TimelineDirection = when (this) {
    TimelineSort.NEWEST_FIRST -> TimelineDirection.NEWEST_FIRST
    TimelineSort.OLDEST_FIRST -> TimelineDirection.OLDEST_FIRST
}

/**
 * The android-free half of the `CatalogueRecord -> MediaItem` mapping
 * (r13): every field the timeline grid needs, with the content URI still
 * a STRING. Constructed only by [catalogueTimelineItem] (pure, JVM-tested);
 * the thin android side ([asMediaItem]) lives at the window-loader seam in
 * [PhotosViewModel], which is where `Uri.parse` belongs.
 */
internal data class CatalogueTimelineItem(
    /** MediaStore `_id` parsed from the local media key; `0` if malformed. */
    val id: Long,
    /** The record's canonical URI, still unparsed; the access handle. */
    val uriString: String,
    val mimeType: String,
    /** `dateAddedMillis / 1000`, truncating toward zero like SQLite. */
    val dateAddedSec: Long,
    /** The resolved capture instant verbatim (nullable, possibly negative). */
    val captureDateMillis: Long?,
    /** `mimeType.startsWith("video/")` --- the same rule the queries use. */
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val isFavorite: Boolean,
)

/**
 * The pure record-to-timeline mapping (r13, JVM-tested per ADR-0016):
 * takes the catalogue row's fields as plain strings/longs/booleans --- no
 * `android.*`, no `CatalogueRecord` dependency --- so the mapping contract
 * is pinned on the plain JVM while the catalogue record's own shape stays
 * free to evolve.
 *
 *  - [id] comes from the LOCAL MEDIA KEY (the stable identity), parsed by
 *    [MediaIdentity.fromKeyString] --- the total, null-on-malformed
 *    parser. A malformed key degrades to `id = 0` rather than crashing the
 *    window (real keys are written by [MediaIdentity.toKeyString] in the
 *    scan pipeline, so this is belt-and-braces); the id is NOT identity on
 *    this path (the URI string is the grid's key), only the analogue of
 *    the `_id` the MediaStore rows carry.
 *  - [dateAddedSec] is `dateAddedMillis / 1000`, truncating toward zero
 *    (Kotlin and SQLite integer division agree), so pre-1970 `dateAdded`
 *    values --- impossible from MediaStore, harmless if ever stored ---
 *    still round-trip consistently.
 *  - `captureDateMillis` and the grid metadata pass through verbatim; the
 *    record's `displayName` has no [MediaItem] counterpart and is
 *    deliberately dropped.
 */
internal fun catalogueTimelineItem(
    localMediaKey: String,
    canonicalUri: String,
    mimeType: String,
    dateAddedMillis: Long,
    resolvedCaptureDateMillis: Long?,
    width: Int,
    height: Int,
    durationMs: Long,
    isFavorite: Boolean,
): CatalogueTimelineItem = CatalogueTimelineItem(
    id = MediaIdentity.fromKeyString(localMediaKey)?.mediaStoreId ?: 0L,
    uriString = canonicalUri,
    mimeType = mimeType,
    dateAddedSec = dateAddedMillis / 1000,
    captureDateMillis = resolvedCaptureDateMillis,
    isVideo = mimeType.startsWith("video/"),
    width = width,
    height = height,
    durationMs = durationMs,
    isFavorite = isFavorite,
)

/** The record -> pure-item leg of the catalogue mapping (field copy). */
private fun CatalogueRecord.asTimelineItem(): CatalogueTimelineItem =
    catalogueTimelineItem(
        localMediaKey = localMediaKey,
        canonicalUri = canonicalUri,
        mimeType = mimeType,
        dateAddedMillis = dateAddedMillis,
        resolvedCaptureDateMillis = resolvedCaptureDateMillis,
        width = width,
        height = height,
        durationMs = durationMs,
        isFavorite = isFavorite,
    )

/** The pure-item -> [MediaItem] leg: the one android touch (Uri.parse). */
private fun CatalogueTimelineItem.asMediaItem(): MediaItem = MediaItem(
    id = id,
    uri = Uri.parse(uriString),
    mimeType = mimeType,
    dateAddedSec = dateAddedSec,
    isVideo = isVideo,
    width = width,
    height = height,
    durationMs = durationMs,
    isFavorite = isFavorite,
    captureDateMillis = captureDateMillis,
)

/**
 * The `MediaItem` -> engine-tuple leg (r17): maps an android-bearing item
 * onto the android-free [TimelineFilterFields] the pure predicate
 * ([io.github.dukeoftoad.gallery.data.filters.matches]) evaluates. The
 * one place the snapshot fallback translates a row for the filter engine;
 * the tuple carries every field the engine can read (no bucket ---
 * [MediaItem] has none, which is why the bucket dimension fails closed
 * in memory; see the engine's KDoc).
 */
private fun MediaItem.asFilterFields(): TimelineFilterFields =
    TimelineFilterFields(
        mimeType = mimeType,
        isVideo = isVideo,
        isFavorite = isFavorite,
        dateAddedSec = dateAddedSec,
    )

sealed interface PhotosState {
    /** Initial state: first frame before the first permission check resolves. */
    data object Loading : PhotosState

    /** No media access; show the permission state with a Grant button. */
    data object PermissionNeeded : PhotosState

    /**
     * Media access granted; carries the paging stream (issue #28). The
     * stream may legitimately yield zero items (clean library, not an
     * error) or fail a refresh (surfaced as a retryable `LoadState.Error`
     * by the screen) --- both are load-state concerns, not shell concerns.
     * The stream instance is kept alive across tab switches by
     * `cachedIn` + the idempotent grant path (issue #38): re-entering
     * Photos re-collects this same flow.
     */
    data class Ready(val pagingData: Flow<PagingData<MediaItem>>) : PhotosState
}

/**
 * The folder-picker sheet's list state (r19b), written by
 * [PhotosViewModel.refreshFolders] and rendered by the chips file's
 * picker sheet. One sealed state instead of a nullable list because the
 * sheet must tell a FAILED query from a loaded-but-empty list apart: a
 * failure renders the honest error copy with a retry, an empty list the
 * honest empty line --- never a silent empty list that reads as "the
 * device has no folders" when the provider was merely unreadable.
 */
sealed interface PhotosFoldersState {
    /**
     * A (re)query is in flight --- also the value before the sheet ever
     * opens, which never renders: the sheet composes only while open,
     * and opening triggers the query.
     */
    data object Loading : PhotosFoldersState

    /** The queried folders; an empty list is a REAL result, not a failure. */
    data class Ready(val folders: List<Album>) : PhotosFoldersState

    /**
     * The provider query failed; retry re-runs
     * [PhotosViewModel.refreshFolders].
     */
    data object Error : PhotosFoldersState
}
