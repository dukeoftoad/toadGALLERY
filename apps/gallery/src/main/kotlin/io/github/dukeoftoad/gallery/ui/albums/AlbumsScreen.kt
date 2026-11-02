package io.github.dukeoftoad.gallery.ui.albums

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.Album
import io.github.dukeoftoad.gallery.data.Favorites
import io.github.dukeoftoad.gallery.data.filters.DerivedCollections
import io.github.dukeoftoad.gallery.data.filters.PhotosFilterHandoff
import io.github.dukeoftoad.gallery.ml.people.PeopleContentState
import io.github.dukeoftoad.gallery.ui.StateScreen
import io.github.dukeoftoad.gallery.ui.media.ErrorState
import io.github.dukeoftoad.gallery.ui.media.LoadingState
import io.github.dukeoftoad.gallery.ui.media.MediaPermissionState
import io.github.dukeoftoad.gallery.ui.media.rememberMediaPermissionRequester
import io.github.dukeoftoad.gallery.ui.people.PersonCard
import io.github.dukeoftoad.gallery.ui.photos.collectionLabelRes
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
 * Since r17 the non-empty Ready state also carries a "Collections"
 * section ([CollectionsSection]) pinned as the grid's first, full-span
 * item: the DERIVED collections of `DerivedCollections` --- Photos /
 * Videos / GIFs / Favorites --- as tappable cards. A collection is nothing
 * but a named `TimelineFilter` preset from the SAME pure engine the Photos
 * chips use (zero persistence; user-defined saved collections are roadmap
 * Phase 5 --- see `DerivedCollections`'s KDoc). The shell owns every tab
 * switch and its file is frozen for the parallel rounds, so a card cannot
 * navigate to Photos itself: tapping hands the preset to the Photos
 * timeline through the one-shot [PhotosFilterHandoff] and confirms with a
 * snackbar ("...open the Photos tab to view"); the Photos screen consumes
 * the request on its next entry. The favorites card is Q+ only, matching
 * the favorites pseudo-album's own tiering.
 *
 * Since r25 (Workstream 6) a "People" section ([PeopleSection]) sits
 * ABOVE the Collections section as the grid's first full-span item: the
 * same pure [io.github.dukeoftoad.gallery.ml.people.PeopleContentPolicy]
 * state the People screen renders, as ONE honest card per non-Ready arm
 * (consent off / engine missing in this build / not indexed yet / all
 * hidden / read error with retry) or a horizontal `LazyRow` of the shared
 * [PersonCard]s plus a "See all" action into the `people` sub-screen.
 * Consent is re-read on every tab re-entry (`AlbumsViewModel.refreshPeople`),
 * so the Settings toggle lands here on the next visit.
 */
@Composable
fun AlbumsScreen(
    onOpenAlbum: (bucketId: Long) -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPerson: (clusterId: String) -> Unit,
    viewModel: AlbumsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val peopleState by viewModel.people.collectAsState()

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
    }

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
                    AlbumGrid(
                        albums = s.albums,
                        peopleState = peopleState,
                        onOpenAlbum = onOpenAlbum,
                        onOpenCollection = onOpenCollection,
                        onOpenPeople = onOpenPeople,
                        onOpenPerson = onOpenPerson,
                        onRetryPeople = viewModel::refreshPeople,
                    )
                }
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
    onOpenAlbum: (bucketId: Long) -> Unit,
    onOpenCollection: (DerivedCollections.DerivedCollection) -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPerson: (clusterId: String) -> Unit,
    onRetryPeople: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The People section (r25) spans the grid's full width as its FIRST
        // item, above the Collections section and the albums: the people
        // row scrolls with everything else (one scroll surface).
        item(span = { GridItemSpan(maxLineSpan) }) {
            PeopleSection(
                state = peopleState,
                onOpenPeople = onOpenPeople,
                onOpenPerson = onOpenPerson,
                onRetryPeople = onRetryPeople,
            )
        }
        // The Collections section (r17) spans the grid's full width, so it
        // scrolls with the albums (one scroll surface) and needs no extra
        // layout scaffolding.
        item(span = { GridItemSpan(maxLineSpan) }) {
            CollectionsSection(onOpenCollection = onOpenCollection)
        }
        items(
            items = albums,
            key = { it.bucketId },
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
    }
}

/**
 * The People section (r25, Workstream 6): ONE honest row above the
 * Collections section, decided by the SAME pure
 * [io.github.dukeoftoad.gallery.ml.people.PeopleContentPolicy] output
 * the People screen renders:
 *  - Loading -> nothing at all (the albums grid is the content; a
 *    transient sub-second section flash would be noise, not honesty);
 *  - ConsentOff / EngineMissing / NotIndexed / Ready-with-everyone-hidden
 *    -> one honest card saying exactly why there is no row of people;
 *  - Error -> the honest card with a retry action
 *    ([AlbumsViewModel.refreshPeople]);
 *  - Ready -> the header + a horizontal `LazyRow` of the SHARED
 *    [PersonCard]s (identical chrome to the People screen) plus a
 *    "See all" action into the `people` sub-screen; a card tap opens
 *    that person's detail (the album cards' pattern).
 */
@Composable
private fun PeopleSection(
    state: PeopleContentState,
    onOpenPeople: () -> Unit,
    onOpenPerson: (clusterId: String) -> Unit,
    onRetryPeople: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (state) {
            PeopleContentState.Loading -> Unit
            PeopleContentState.ConsentOff -> PeopleInfoCard(
                text = stringResource(R.string.albums_people_consent_off),
            )
            PeopleContentState.EngineMissing -> PeopleInfoCard(
                text = stringResource(R.string.albums_people_engine_missing),
            )
            PeopleContentState.NotIndexed -> PeopleInfoCard(
                text = stringResource(R.string.albums_people_not_indexed),
            )
            is PeopleContentState.Error -> PeopleInfoCard(
                text = stringResource(R.string.albums_people_error),
                actionLabel = stringResource(R.string.state_action_retry),
                onAction = onRetryPeople,
            )
            is PeopleContentState.Ready -> {
                if (state.visible.isEmpty()) {
                    PeopleInfoCard(
                        text = stringResource(R.string.albums_people_all_hidden),
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.albums_people_header),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onOpenPeople,
                            modifier = Modifier.testTag("albums_people_see_all"),
                        ) {
                            Text(stringResource(R.string.albums_people_see_all))
                        }
                    }
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag("albums_people_row"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Count-based items: the file already imports the GRID
                        // items extension for the album cards below, and the
                        // LazyList list-overload shares its name (the same
                        // trick the Collections row above uses).
                        items(state.visible.size) { index ->
                            val person = state.visible[index]
                            PersonCard(
                                person = person,
                                onClick = { onOpenPerson(person.clusterId) },
                                modifier = Modifier.width(104.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One honest People-section card (r25): the tonal rounded card the
 * [CollectionCard] idiom uses, carrying ONE line of truth about why
 * there is no row of people (plus an optional action).
 */
@Composable
private fun PeopleInfoCard(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (actionLabel != null) {
                TextButton(onClick = { onAction?.invoke() }) {
                    Text(actionLabel)
                }
            }
        }
    }
}

/**
 * The Collections section (r17): the derived collections as a header + a
 * horizontally scrolling row of tappable cards. The entries come from
 * `DerivedCollections.visibleOn` (pure, JVM-tested): everything today,
 * minus Favorites below Q (the `is_favorite` provider column is Q+, so
 * the card would promise a view that can never fill --- the same tiering
 * as the favorites pseudo-album card).
 */
@Composable
private fun CollectionsSection(
    onOpenCollection: (DerivedCollections.DerivedCollection) -> Unit,
) {
    val visible = DerivedCollections.visibleOn(Build.VERSION.SDK_INT)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.albums_collections_header),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.albums_collections_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("albums_collections"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Count-based items: the file already imports the GRID items
            // extension (`lazy.grid.items`) for the album cards below, and
            // the LazyList list-overload shares its name.
            items(visible.size) { index ->
                val collection = visible[index]
                collectionLabelRes(collection.id)?.let { labelRes ->
                    CollectionCard(
                        label = stringResource(labelRes),
                        onClick = { onOpenCollection(collection) },
                        modifier = Modifier.testTag("albums_collection_card_${collection.id}"),
                    )
                }
            }
        }
    }
}

/**
 * One derived-collection card (r17): a tonal, rounded label card --- the
 * collection has no cover of its own (it is a filter preset, not stored
 * content), and pretending otherwise with some other item's thumbnail
 * would be the fake-persistence look the section explicitly avoids.
 */
@Composable
private fun CollectionCard(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = album.coverUri,
            contentDescription = null, // decorative; the label below carries meaning
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        )
        Text(
            text = album.bucketName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
        Text(
            text = album.itemCount.let { count ->
                androidx.compose.ui.res.pluralStringResource(
                    R.plurals.album_item_count, count, count
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * The favorites pseudo-album card (issue #66): the SAME column geometry as
 * [AlbumCard] (square clipped cover, name, live count plurals) so the grid
 * stays uniform, but DISTINCTLY marked --- a solid primary circle with a
 * white heart pinned over the cover's bottom-end corner. The cover is the
 * newest favorite; the card only exists while at least one favorite does
 * (see [AlbumsViewModel]), so the cover is guaranteed non-null.
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("albums_favorites_card"),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            AsyncImage(
                model = album.coverUri,
                contentDescription = null, // decorative; the label below carries meaning
                modifier = Modifier.matchParentSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
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
        }
        Text(
            text = album.bucketName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp),
        )
        Text(
            text = album.itemCount.let { count ->
                androidx.compose.ui.res.pluralStringResource(
                    R.plurals.album_item_count, count, count
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
        )
    }
}
