package com.toadgallery.gallery.ui.albums

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.toadgallery.gallery.R
import com.toadgallery.gallery.buildshape.BuildShape
import com.toadgallery.gallery.data.Album
import com.toadgallery.gallery.data.Favorites
import com.toadgallery.gallery.data.filters.DerivedCollections
import com.toadgallery.gallery.data.filters.PhotosFilterHandoff
import com.toadgallery.gallery.data.places.PlaceRegion
import com.toadgallery.gallery.data.places.PlaceMediaHandoff
import com.toadgallery.gallery.data.places.PlacesContentState
import com.toadgallery.gallery.data.places.PlacesRegionGrid
import com.toadgallery.gallery.data.places.PlacesRegionLabels
import com.toadgallery.gallery.ml.people.PeopleContentState
import com.toadgallery.gallery.ml.search.SemanticContentState
import com.toadgallery.gallery.ml.things.ThingsCollection
import com.toadgallery.gallery.ui.LocalToadToast
import com.toadgallery.gallery.ui.StateScreen
import com.toadgallery.gallery.ui.media.ErrorState
import com.toadgallery.gallery.ui.media.LoadingState
import com.toadgallery.gallery.ui.media.MediaPermissionState
import com.toadgallery.gallery.ui.media.rememberMediaPermissionRequester
import com.toadgallery.gallery.ui.people.personCoverUri
import com.toadgallery.gallery.ui.photos.collectionLabelRes
import com.toadgallery.gallery.ui.overlays.HiddenVaultPinDialog
import com.toadgallery.gallery.ui.screens.albums.ToadAlbumsHeader
import com.toadgallery.gallery.ui.screens.albums.ToadSectionEyebrow
import com.toadgallery.gallery.ui.screens.albums.ToadSmartCollectionCard
import com.toadgallery.gallery.ui.screens.albums.ToadUtilitiesCard
import kotlinx.coroutines.launch

/**
 * Albums tab (Phase 3, issue #29).
 *
 * Same state model as Photos (Loading / PermissionNeeded / Ready), plus
 * since r19 the honest Error arm, rendered through the shared screen-state
 * components (issue #13):
 *  - Loading -> [LoadingState] tagged `albums_state_loading`
 *  - PermissionNeeded -> [MediaPermissionState] (shared denial tag
 *    `state_permission_denied`, Settings deep-link built in)
 *  - Error -> the shared [ErrorState] tagged `albums_state_error` (r19):
 *    a provider failure --- permission revoked mid-session, a provider
 *    SQLiteException, a missing column --- renders the honest error screen
 *    with a retry action that re-queries. These are the shared component's
 *    FIRST live call sites: issue #13 defined it with none, and the UI
 *    audit tracked it as dead code (the Photos tab migrates later per
 *    docs/architecture/screen-states.md §5).
 *  - Ready + empty -> the frozen `StateScreen` empty layout, wrapped in a Box
 *    tagged `albums_state_empty` (StateScreen itself is frozen and carries no
 *    tag parameter; the wrapper supplies the test surface, issue #38.7)
 *
 * `Ready` renders a two-column grid of folder cards: cover thumbnail (newest
 * item in the bucket, URI-first per ADR-0006), folder name, item count.
 * Tapping a card navigates to the album detail view (`AlbumDetailScreen`),
 * which reuses the shared `MediaGrid`.
 *
 * Since issue #66 a non-empty favorites set pins a distinct "Favorites"
 * pseudo-album card FIRST ([FavoritesAlbumCard]): newest favorite as the
 * cover, a primary heart chip as the distinguishing mark, and the live
 * count the same plurals use. Tapping it routes the SAME album-detail
 * navigation with [Favorites.FAVORITES_BUCKET_ID] --- the detail ViewModel
 * resolves the sentinel to the favorites query.
 *
 * r76.4 mirrors the prototype's Albums tab exactly, top to bottom in ONE
 * scroll surface (the LazyVerticalGrid):
 *  1. the header row (the ExtraBold "Albums" wordmark + the New Album
 *     pill, [ToadAlbumsHeader]) above the grid;
 *  2. "On-Device Smart Collections" ([SmartCollectionsSection]): ONE
 *     eyebrow with the "Private AI" note, then a 2-column grid of compact
 *     cards (48dp rounded cover, bold name, muted count line) --- People
 *     (r25), Places (r27), Things (r39) and the derived Collections
 *     (r17: Photos / Videos / GIFs / Favorites) merged into the
 *     prototype's one visual language. Every feature keeps its own state
 *     policy + build-shape gate; the non-Ready arms render the honest
 *     "why there are no cards" row (the prototype's disabled cards).
 *     A collection preset hands the filter to the Photos timeline through
 *     the one-shot [PhotosFilterHandoff] + snackbar; a person/place/thing
 *     card routes to its own detail as before. The Things create dialog
 *     ([ThingsCreateDialog]) is the grid's SIBLING (a dialog is not grid
 *     content), opened from the empty-things card;
 *  3. "Device Folders" ([AlbumCard] / [FavoritesAlbumCard]): the 2-column
 *     folder cards (112dp cropped cover, count pill bottom-end, name +
 *     chevron row);
 *  4. "Utilities & Privacy" ([ToadUtilitiesCard]) as the LAST in-flow
 *     full-span item --- the r76 first cut PINNED it under the grid (a
 *     fixed bottom bar the prototype does not have); it now scrolls with
 *     everything else. The vault row opens the PIN dialog at the screen
 *     root; the recycle row routes to the trash screen.
 */
@Composable
fun AlbumsScreen(
    onOpenAlbum: (bucketId: Long) -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPerson: (clusterId: String) -> Unit,
    /** r27: the Places section's "See all" action into the places sub-screen. */
    onOpenPlaces: () -> Unit,
    /** r27: a region chip's tap --- that region's media grid. */
    onOpenPlace: (regionKey: String) -> Unit,
    /** r39: a Things collection chip's tap --- that collection's detail. */
    onOpenThing: (collectionId: String) -> Unit,
    /** r76: the Utilities & Privacy card's two rows (vault + recycle bin). */
    onOpenVault: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    /** r76: the vault row's live summary (from the shell-scoped vault state). */
    vaultSummary: String = "PIN & biometric locked storage",
    /** r76: hands a typed PIN to the shell vault session's async unlock. */
    onAttemptVaultUnlock: (CharArray) -> Unit = {},
    /** r76: triggers the vault's biometric prompt (null = unavailable). */
    onVaultBiometric: (() -> Unit)? = null,
    /** r76: whether the vault biometric trigger is offered. */
    vaultBiometricAvailable: Boolean = false,
    /** r76: the recycle row's live summary (from the trash store). */
    recycleSummary: String = "Deleted items",
    viewModel: AlbumsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val peopleState by viewModel.people.collectAsState()
    // r27: the Places section's state, refreshed beside the People read.
    val placesState by viewModel.places.collectAsState()
    // r39: the Things section's state + collections, refreshed on the same
    // entry cadence.
    val thingsState by viewModel.thingsSection.collectAsState()
    val thingsCollections by viewModel.thingsCollections.collectAsState()

    val requester = rememberMediaPermissionRequester(
        onResult = { granted ->
            if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
        }
    )

    // r17: the Collections confirmation snackbar --- screen-owned, like the
    // file-ops snackbars on the Photos tab (the hand-off itself is a plain
    // process-wide object; no ViewModel state needed for one message).
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val onOpenCollection: (DerivedCollections.DerivedCollection) -> Unit = { collection ->
        // One-shot request: the Photos tab's entry composition consumes it
        // and applies it through the same onFilterChange the chips use.
        PhotosFilterHandoff.request(collection.filter)
        collectionLabelRes(collection.id)?.let { labelRes ->
            scope.launch {
                snackbarHostState.showSnackbar(
                    message = context.getString(
                        R.string.albums_collection_applied,
                        context.getString(labelRes),
                    ),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        requester.launch()
        // r25: the People section re-reads consent + clusters on every tab
        // (re-)entry --- the toggle in Settings lands here on the next visit.
        viewModel.refreshPeople()
        // r27: the Places section re-reads consent + rows on the same
        // cadence (the same refresh discipline, one entry effect).
        viewModel.refreshPlaces()
        // r39: the Things section re-reads consent + collections on the
        // same cadence.
        viewModel.refreshThings()
    }

    // r76: the vault PIN dialog's open flag --- hoisted to the screen's
    // root Box scope so the scrim covers the WHOLE screen. The r76 first
    // cut nested the scrim Box inside the Ready branch's Column, where
    // fillMaxSize measured the column's remaining height (a scrim that
    // only dimmed the content below the header and a dialog pushed off
    // the bottom on small screens).
    var showVaultPin by rememberSaveable { mutableStateOf(false) }
    // r76 polish: system back dismisses the PIN dialog (the scrim's own
    // escape hatch) instead of leaving the app with the modal still up.
    BackHandler(enabled = showVaultPin) { showVaultPin = false }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            is AlbumsState.Loading -> LoadingState(tag = "albums_state_loading")
            is AlbumsState.PermissionNeeded -> MediaPermissionState(onGrant = requester::launch)
            is AlbumsState.Error -> ErrorState(
                titleRes = R.string.state_albums_error_title,
                bodyRes = R.string.state_albums_error_body,
                actionRes = R.string.state_action_retry,
                onRetry = viewModel::retry,
                tag = "albums_state_error",
            )
            is AlbumsState.Ready -> {
                if (s.albums.isEmpty()) {
                    // StateScreen is frozen without a tag parameter, so the stable
                    // per-screen tag rides on a wrapper Box (issue #38.7).
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("albums_state_empty"),
                    ) {
                        StateScreen(
                            iconRes = R.drawable.ic_nav_albums,
                            titleRes = R.string.state_albums_empty_title,
                            bodyRes = R.string.state_albums_empty_body,
                        )
                    }
                } else {
                    // r76.4: the prototype's structure --- ONE scroll
                    // surface (the grid) carrying every section in order:
                    // header, Smart Collections, Device Folders, then the
                    // Utilities & Privacy card IN FLOW as the last item.
                    // The r76 first cut PINNED the utilities card under
                    // the grid (a fixed bottom bar the prototype does not
                    // have --- the field report's flaw); it now scrolls
                    // with everything else.
                    Column(modifier = Modifier.fillMaxSize()) {
                        // r76: the redesign header (the ExtraBold title +
                        // the New Album pill). The honest v1 action: a
                        // toast pointing at the Things section's create
                        // flow --- the app's one user-created collection
                        // surface today (MediaStore buckets are
                        // device-owned, so "new album" means a named smart
                        // collection).
                        val toast = LocalToadToast.current
                        ToadAlbumsHeader(
                            onNewAlbum = {
                                toast.show("Create a collection under Things")
                            },
                        )
                        AlbumGrid(
                            albums = s.albums,
                            peopleState = peopleState,
                            placesState = placesState,
                            thingsState = thingsState,
                            thingsCollections = thingsCollections,
                            onOpenAlbum = onOpenAlbum,
                            onOpenCollection = onOpenCollection,
                            onOpenPeople = onOpenPeople,
                            onOpenPerson = onOpenPerson,
                            onRetryPeople = viewModel::refreshPeople,
                            onOpenPlaces = onOpenPlaces,
                            onOpenPlace = onOpenPlace,
                            onCreateThing = viewModel::createThing,
                            onRemoveThing = viewModel::removeThing,
                            onOpenThing = onOpenThing,
                            onRetryThings = viewModel::refreshThings,
                            utilities = {
                                // r76.4: the Utilities & Privacy card,
                                // rendered as the grid's LAST full-span
                                // item (in flow, not pinned). The vault
                                // row opens the PIN dialog (the dialog
                                // itself lives at the screen's ROOT Box
                                // scope below).
                                ToadUtilitiesCard(
                                    vaultSummary = vaultSummary,
                                    onOpenVault = { showVaultPin = true },
                                    recycleSummary = recycleSummary,
                                    onOpenRecycleBin = onOpenTrash,
                                )
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        // r76 polish: the PIN dialog + scrim moved from the Ready branch's
        // COLUMN to the root Box --- the old placement made the scrim's
        // fillMaxSize measure the column's remaining height (a partial
        // dim + a dialog clipped off small screens). The unlock contract
        // stays the ASYNC vault session's: the dialog hands the PIN to
        // [onAttemptVaultUnlock] (the shell's PBKDF2-backed verify ---
        // never synchronous) and closes; the VaultScreen the navigation
        // lands on narrates a wrong PIN through its own messaging.
        if (showVaultPin) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xBF000000)),
                contentAlignment = Alignment.Center,
            ) {
                HiddenVaultPinDialog(
                    onDismiss = { showVaultPin = false },
                    onPinEntered = { pin ->
                        onAttemptVaultUnlock(pin)
                        onOpenVault()
                        true
                    },
                    // r77.5 (the field report's dead fingerprint trigger):
                    // mirror the PIN flow --- close the dialog, land on the
                    // vault screen, THEN raise the request. The shell's
                    // collector launches the device prompt over the vault
                    // screen, so a successful fingerprint lands INSIDE the
                    // unlocked vault (and a cancel leaves the locked screen's
                    // PIN pad right there) instead of unlocking invisibly
                    // behind the albums grid.
                    onBiometric = {
                        showVaultPin = false
                        onOpenVault()
                        onVaultBiometric?.invoke()
                    },
                    biometricAvailable = vaultBiometricAvailable,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun AlbumGrid(
    albums: List<Album>,
    peopleState: PeopleContentState,
    placesState: PlacesContentState,
    thingsState: SemanticContentState,
    thingsCollections: List<ThingsCollection>,
    onOpenAlbum: (bucketId: Long) -> Unit,
    onOpenCollection: (DerivedCollections.DerivedCollection) -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPerson: (clusterId: String) -> Unit,
    onRetryPeople: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenPlace: (regionKey: String) -> Unit,
    onCreateThing: (name: String, query: String) -> Unit,
    onRemoveThing: (collectionId: String) -> Unit,
    onOpenThing: (collectionId: String) -> Unit,
    onRetryThings: () -> Unit,
    utilities: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // r76.4: the Things create dialog's open flag --- hoisted HERE (the
    // grid's sibling), because a dialog is not grid content: rendering it
    // inside a LazyGrid item tied its window to the item's lifecycle
    // (scrolling away dismissed it). The empty-things card requests the
    // dialog through [SmartCollectionsSection]'s onRequestCreateThing.
    var showCreateThing by remember { mutableStateOf(false) }
    // r76.4 fix (CI #14): [SmartCollectionsSection] is a PLAIN
    // LazyGridScope extension --- its body is NOT a composable context,
    // so the regions derivation's `remember` moved HERE into composition
    // (the old PlacesSection's own discipline; bucket() is pure math over
    // the Ready rows).
    val placesRegions = remember(placesState) {
        if (placesState is PlacesContentState.Ready) {
            PlacesRegionGrid.bucket(placesState.rows)
        } else {
            emptyList()
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // r50 ux fix: the tab had NO title --- Photos opens under a titled
        // top bar and Settings under a headline, but Albums jumped straight
        // into the People/Places cards and thumbnails, unidentified on a
        // fresh install. r76.1: the redesign header (ToadAlbumsHeader) now
        // carries the visible "Albums" wordmark, so the grid's first item
        // reverts to the PhotosScreen anchor idiom --- a zero-height
        // transparent Text keeping the heading semantics + the test surface
        // without painting the title TWICE on one screen (the field
        // report's duplicate heading).
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(R.string.nav_albums),
                color = Color.Transparent,
                fontSize = 1.sp,
                modifier = Modifier
                    .height(0.dp)
                    .semantics { heading() }
                    .testTag("albums_title"),
            )
        }
        // ---- r76.4 SECTION 1: On-Device Smart Collections (the
        //      prototype's 2-column compact-card grid). People, Places,
        //      Things and the derived Collections all render as the SAME
        //      compact cell shape the prototype's smart collections use
        //      (48dp rounded cover, bold name, muted count line), one
        //      section, one visual language. The sections keep their
        //      honest state arms and build-shape gates --- the prototype's
        //      "Disabled in AI" card is exactly this section's non-Ready
        //      arms. ----
        SmartCollectionsSection(
            peopleState = peopleState,
            placesState = placesState,
            placesRegions = placesRegions,
            thingsState = thingsState,
            thingsCollections = thingsCollections,
            onOpenPeople = onOpenPeople,
            onOpenPerson = onOpenPerson,
            onRetryPeople = onRetryPeople,
            onOpenPlaces = onOpenPlaces,
            onOpenPlace = onOpenPlace,
            onCreateThing = onCreateThing,
            onRemoveThing = onRemoveThing,
            onOpenThing = onOpenThing,
            onRetryThings = onRetryThings,
            onOpenCollection = onOpenCollection,
            onRequestCreateThing = { showCreateThing = true },
        )
        // ---- r76.4 SECTION 2: Device Folders (the prototype's eyebrow
        //      + the 112dp-cover folder cards). ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            ToadSectionEyebrow(
                label = stringResource(R.string.albums_device_folders),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(
            items = albums,
            key = { it.bucketId },
            span = { if (Favorites.isFavoritesBucket(it.bucketId)) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        ) { album ->
            if (Favorites.isFavoritesBucket(album.bucketId)) {
                FavoritesAlbumCard(
                    album = album,
                    onClick = { onOpenAlbum(album.bucketId) },
                )
            } else {
                AlbumCard(
                    album = album,
                    onClick = { onOpenAlbum(album.bucketId) },
                )
            }
        }
        // ---- r76.4 SECTION 3: Utilities & Privacy, IN FLOW as the grid's
        //      LAST full-span item (the prototype's bottom card --- never
        //      a pinned bottom bar). ----
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(modifier = Modifier.padding(top = 8.dp)) {
                utilities()
            }
        }
    }
    // r76.4: the Things create dialog --- the grid's SIBLING, not an
    // item (see the hoisted flag above). The old ThingsSection's dialog
    // verbatim: name + query fields, a disabled confirm on a blank name
    // (the repository refuses it), cancel.
    if (showCreateThing) {
        ThingsCreateDialog(
            onCreate = onCreateThing,
            onDismiss = { showCreateThing = false },
        )
    }
}

/**
 * One device-folder card, r76.1 restyled to the prototype's exact chrome:
 * a bordered `rounded-3xl` surface card; the cover fills a FIXED 112dp
 * box (`ContentScale.Crop` --- the first cut painted the image at intrinsic
 * size inside an aspect-ratio box, so tall screenshots floated small in a
 * mostly-empty card and decode failures left a giant blank tile); the item
 * count rides the cover's bottom-end as the prototype's black/65 pill; the
 * name + trailing chevron row sits under the cover. The r47 error plate
 * stays (a corrupt cover still renders the tonal plate, now inside the
 * fixed box instead of an empty aspect slab).
 */
@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
            ) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = null, // decorative; the label below carries meaning
                    contentScale = ContentScale.Crop,
                    // r47 ux fix: the decode-failure plate (MediaThumb's r19a idiom):
                    // a corrupt cover used to render as fully blank pixels ---
                    // indistinguishable from loading --- under the folder's name.
                    error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.matchParentSize(),
                )
                Text(
                    text = album.itemCount.let { count ->
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.album_item_count, count, count
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = album.bucketName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, // decorative; the whole card is the target
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/**
 * The favorites pseudo-album card (issue #66): the SAME chrome as
 * [AlbumCard] (r76.1: bordered rounded card, 112dp cropped cover, count
 * pill on the cover, name + chevron row) so the grid stays uniform, but
 * DISTINCTLY marked --- a solid primary circle with a white heart pinned
 * over the cover's top-end corner (the count pill owns the bottom-end).
 * The cover is the newest favorite; the card only exists while at least
 * one favorite does (see [AlbumsViewModel]), so the cover is guaranteed
 * non-null.
 *
 * Tapping routes the album-detail navigation with the sentinel bucket id
 * [Favorites.FAVORITES_BUCKET_ID], which the detail ViewModel resolves to
 * the `IS_FAVORITE = 1` query. The heart glyph is the CORE-set
 * `Icons.Filled.Favorite` --- the same vector the viewer toggle and the
 * grid overlay use.
 */
@Composable
private fun FavoritesAlbumCard(
    album: Album,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .testTag("albums_favorites_card"),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
            ) {
                AsyncImage(
                    model = album.coverUri,
                    contentDescription = null, // decorative; the label below carries meaning
                    contentScale = ContentScale.Crop,
                    // r47 ux fix: the decode-failure plate, same as [AlbumCard].
                    error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.matchParentSize(),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Favorite,
                        contentDescription = null, // decorative; the card label carries meaning
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = album.itemCount.let { count ->
                        androidx.compose.ui.res.pluralStringResource(
                            R.plurals.album_item_count, count, count
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = album.bucketName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, // decorative; the whole card is the target
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}


/**
 * r76.4: the prototype's "On-Device Smart Collections" section --- ONE
 * eyebrow ("ON-DEVICE SMART COLLECTIONS" + the shield "Private AI" note
 * in the primary) over a 2-column grid of compact cards: People, Places,
 * Things and the derived Collections (Photos / Videos / GIFs /
 * Favorites), every cell the SAME shape (the prototype's smart
 * collection card: 48dp rounded cover, bold 12sp name, muted 11sp
 * count line, 24dp bordered bgSurface card).
 *
 * The section is the four r17/r25/r27/r39 sections MERGED into the
 * prototype's format --- same states, same build-shape gates, same
 * honest arms, new chrome. Each feature keeps its own state policy:
 *  - People: consent off / engine missing / not indexed / error+retry /
 *    all-hidden each render ONE disabled-shape card ("Disabled in AI"
 *    in the prototype's language); Ready renders the clusters as cards
 *    (cover = the cluster's cover thumbnail; count = the face count).
 *  - Places: the same discipline over regions (count = region count).
 *  - Things: the same discipline over the user's named collections ---
 *    plus the section keeps its create entry (the header pill's sibling
 *    action) and per-collection remove in the Things detail screen.
 *  - Collections: the derived presets (Photos / Videos / GIFs /
 *    Favorites) as the prototype's first four cells --- tonal glyph
 *    covers (they are filter presets, not stored content; no fake
 *    thumbnails), count = the timeline's live count is NOT known here,
 *    so the subtitle stays the preset's honest description.
 */
private fun LazyGridScope.SmartCollectionsSection(
    peopleState: PeopleContentState,
    placesState: PlacesContentState,
    /** r76.4 fix (CI #14): pre-derived in [AlbumGrid] (a composable context). */
    placesRegions: List<PlaceRegion>,
    thingsState: SemanticContentState,
    thingsCollections: List<ThingsCollection>,
    onOpenPeople: () -> Unit,
    onOpenPerson: (clusterId: String) -> Unit,
    onRetryPeople: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenPlace: (regionKey: String) -> Unit,
    onCreateThing: (name: String, query: String) -> Unit,
    onRemoveThing: (collectionId: String) -> Unit,
    onOpenThing: (collectionId: String) -> Unit,
    onRetryThings: () -> Unit,
    onOpenCollection: (DerivedCollections.DerivedCollection) -> Unit,
    /** r76.4: opens the Things create dialog (the state lives in [AlbumGrid]). */
    onRequestCreateThing: () -> Unit,
) {
    val fullSpan: (LazyGridItemSpanScope.() -> GridItemSpan) = {
        GridItemSpan(maxLineSpan)
    }
    item(span = fullSpan) {
        Column {
            ToadSectionEyebrow(
                label = stringResource(R.string.albums_smart_collections),
                trailing = stringResource(R.string.albums_smart_collections_private_ai),
                trailingColor = MaterialTheme.colorScheme.primary,
            )
        }
    }
    // ---- People (r25, build-shape gated) ----
    if (BuildShape.faceIndex) {
        when (peopleState) {
            PeopleContentState.Loading -> Unit
            is PeopleContentState.Ready -> {
                if (peopleState.visible.isNotEmpty()) {
                    items(
                        count = peopleState.visible.size,
                        key = { "people-" + peopleState.visible[it].clusterId },
                    ) { index ->
                        val person = peopleState.visible[index]
                        ToadSmartCollectionCard(
                            name = person.name
                                ?: stringResource(R.string.people_unnamed),
                            summary = pluralStringResource(
                                R.plurals.people_face_count,
                                person.faceCount,
                                person.faceCount,
                            ),
                            cover = com.toadgallery.gallery.ui.people.personCoverUri(
                                person.coverFileId
                            ),
                            onClick = { onOpenPerson(person.clusterId) },
                            modifier = Modifier.testTag("albums_smart_people"),
                        )
                    }
                    item(span = fullSpan) {
                        SmartCollectionsSeeAllRow(
                            label = stringResource(R.string.albums_people_see_all),
                            onClick = onOpenPeople,
                            testTag = "albums_people_see_all",
                        )
                    }
                } else {
                    item(span = fullSpan) {
                        SmartCollectionsHonestCard(
                            text = stringResource(R.string.albums_people_all_hidden),
                            actionLabel = stringResource(R.string.albums_people_see_all),
                            onAction = onOpenPeople,
                        )
                    }
                }
            }
            else -> item(span = fullSpan) {
                SmartCollectionsHonestCard(
                    text = stringResource(peopleState.honestRes()),
                    actionLabel = stringResource(R.string.state_action_retry)
                        .takeIf { peopleState is PeopleContentState.Error },
                    onAction = onRetryPeople.takeIf { peopleState is PeopleContentState.Error },
                )
            }
        }
    }
    // ---- Places (r27) ----
    when (placesState) {
        PlacesContentState.Loading -> Unit
        is PlacesContentState.Ready -> {
            // r76.4 fix (CI #14): regions pre-derived in [AlbumGrid]
            // (this extension's body is not a composable context).
            val regions = placesRegions
            if (regions.isNotEmpty()) {
                items(
                    count = minOf(regions.size, SMART_COLLECTIONS_MAX_REGIONS),
                    key = { "place-" + regions[it].regionKey },
                ) { index ->
                    val region = regions[index]
                    ToadSmartCollectionCard(
                        name = PlacesRegionLabels.regionLabel(region),
                        summary = pluralStringResource(
                            R.plurals.places_region_count,
                            region.count,
                            region.count,
                        ),
                        cover = placesState.rows.getOrNull(region.coverIndex)?.canonicalUri,
                        onClick = {
                            PlaceMediaHandoff.request(region.regionKey)
                            onOpenPlace(region.regionKey)
                        },
                        modifier = Modifier.testTag("albums_smart_places"),
                    )
                }
                item(span = fullSpan) {
                    SmartCollectionsSeeAllRow(
                        label = stringResource(R.string.albums_places_see_all),
                        onClick = onOpenPlaces,
                        testTag = "albums_places_see_all",
                    )
                }
            } else {
                item(span = fullSpan) {
                    SmartCollectionsHonestCard(
                        text = stringResource(R.string.albums_places_empty),
                    )
                }
            }
        }
        PlacesContentState.ConsentOff -> item(span = fullSpan) {
            SmartCollectionsHonestCard(
                text = stringResource(R.string.albums_places_consent_off),
            )
        }
        is PlacesContentState.Empty -> item(span = fullSpan) {
            SmartCollectionsHonestCard(
                text = stringResource(R.string.albums_places_empty),
            )
        }
    }
    // ---- Things (r39, build-shape gated) ----
    if (BuildShape.semanticIndex) {
        when (thingsState) {
            SemanticContentState.Loading -> Unit
            SemanticContentState.Ready -> {
                if (thingsCollections.isEmpty()) {
                    item(span = fullSpan) {
                        SmartCollectionsHonestCard(
                            text = stringResource(R.string.albums_things_none),
                            actionLabel = stringResource(R.string.albums_things_create),
                            onAction = onRequestCreateThing,
                        )
                    }
                } else {
                    items(
                        count = thingsCollections.size,
                        key = { "thing-" + thingsCollections[it].collectionId },
                    ) { index ->
                        val collection = thingsCollections[index]
                        ToadSmartCollectionCard(
                            name = collection.name,
                            summary = collection.query,
                            cover = null,
                            onClick = { onOpenThing(collection.collectionId) },
                            modifier = Modifier.testTag("albums_smart_things"),
                        )
                    }
                }
            }
            else -> item(span = fullSpan) {
                SmartCollectionsHonestCard(
                    text = stringResource(thingsState.honestRes()),
                    actionLabel = stringResource(R.string.state_action_retry)
                        .takeIf { thingsState is SemanticContentState.Error },
                    onAction = onRetryThings.takeIf { thingsState is SemanticContentState.Error },
                )
            }
        }
    }
    // ---- Collections (r17, the derived presets) ----
    val visibleCollections = DerivedCollections.visibleOn(Build.VERSION.SDK_INT)
    items(
        count = visibleCollections.size,
        key = { "collection-" + visibleCollections[it].id },
    ) { index ->
        val collection = visibleCollections[index]
        collectionLabelRes(collection.id)?.let { labelRes ->
            ToadSmartCollectionCard(
                name = stringResource(labelRes),
                summary = collectionSubtitle(collection.id),
                cover = null,
                onClick = { onOpenCollection(collection) },
                modifier = Modifier.testTag(
                    "albums_collection_card_" + collection.id
                ),
            )
        }
    }
}

/** The max region cards before the "See all" row takes over (r76.4). */
private const val SMART_COLLECTIONS_MAX_REGIONS = 4

/** The section's "See all" action row (the prototype's header actions). */
@Composable
private fun SmartCollectionsSeeAllRow(
    label: String,
    onClick: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.testTag(testTag),
        ) {
            Text(label)
        }
    }
}

/**
 * One honest smart-collection row (r76.4): the prototype's disabled-card
 * language --- one line of truth about why a collection has no cards,
 * with its optional recovery action, in a tonal full-width card.
 */
@Composable
private fun SmartCollectionsHonestCard(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("albums_smart_honest"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * The Things create dialog (r39, re-hosted r76.4): name + query fields,
 * a confirm disabled on a blank name (the repository refuses it), cancel.
 * The one-tap decision is screen-local state; the mutations live in the
 * ViewModel and re-derive from the live store.
 */
@Composable
private fun ThingsCreateDialog(
    onCreate: (name: String, query: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.things_create_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.things_create_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.things_name_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.things_query_label)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onDismiss()
                    onCreate(name, query)
                },
                // A blank name is refused by the repository; a disabled
                // confirm beats a lying one.
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.things_create_dialog_confirm))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.file_ops_action_cancel))
            }
        },
    )
}

/** The People section state's honest one-line copy (r76.4). */
private fun PeopleContentState.honestRes(): Int = when (this) {
    PeopleContentState.ConsentOff -> R.string.albums_people_consent_off
    PeopleContentState.EngineMissing -> R.string.albums_people_engine_missing
    PeopleContentState.NotIndexed -> R.string.albums_people_not_indexed
    is PeopleContentState.Error -> R.string.albums_people_error
    else -> R.string.albums_people_not_indexed
}

/** The Things section state's honest one-line copy (r76.4). */
private fun SemanticContentState.honestRes(): Int = when (this) {
    SemanticContentState.ConsentOff -> R.string.albums_things_consent_off
    SemanticContentState.EngineMissing -> R.string.albums_things_engine_missing
    SemanticContentState.ModelsNotProvisioned -> R.string.albums_things_models_missing
    SemanticContentState.NotIndexed -> R.string.albums_things_not_indexed
    is SemanticContentState.Error -> R.string.albums_things_error
    else -> R.string.albums_things_not_indexed
}

/** The derived collection's honest subtitle (r76.4; a preset, no counts). */
private fun collectionSubtitle(id: String): String = when (id) {
    DerivedCollections.ID_PHOTOS -> "All photos"
    DerivedCollections.ID_VIDEOS -> "All videos"
    DerivedCollections.ID_GIFS -> "Animated GIFs"
    DerivedCollections.ID_FAVORITES -> "Your favorites"
    else -> ""
}
