package io.github.dukeoftoad.gallery.ui

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.buildshape.BuildShape
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ui.albums.AlbumDetailScreen
import io.github.dukeoftoad.gallery.ui.albums.AlbumsScreen
import io.github.dukeoftoad.gallery.ui.collage.CollageScreen
import io.github.dukeoftoad.gallery.ui.details.DetailsScreen
import io.github.dukeoftoad.gallery.ui.map.MapScreen
import io.github.dukeoftoad.gallery.ui.people.PeopleScreen
import io.github.dukeoftoad.gallery.ui.people.PersonDetailScreen
import io.github.dukeoftoad.gallery.ui.places.PlaceDetailScreen
import io.github.dukeoftoad.gallery.ui.places.PlacesScreen
import io.github.dukeoftoad.gallery.ui.picker.PickedPhotosScreen
import io.github.dukeoftoad.gallery.ui.picker.PickedPhotosViewModel
import io.github.dukeoftoad.gallery.ui.photos.PhotosScreen
import io.github.dukeoftoad.gallery.ui.search.SearchScreen
import io.github.dukeoftoad.gallery.ui.settings.AboutScreen
import io.github.dukeoftoad.gallery.ui.settings.ProgressScreen
import io.github.dukeoftoad.gallery.ui.settings.SettingsScreen
import io.github.dukeoftoad.gallery.ui.settings.SettingsViewModel
import io.github.dukeoftoad.gallery.ui.things.ThingsDetailScreen
import io.github.dukeoftoad.gallery.ui.theme.ToadGalleryTheme
import io.github.dukeoftoad.gallery.ui.trash.TrashScreen
import io.github.dukeoftoad.gallery.ui.vault.VaultScreen
import io.github.dukeoftoad.gallery.ui.vault.VaultViewModel
import io.github.dukeoftoad.gallery.ui.viewer.ViewerScreen
import io.github.dukeoftoad.gallery.ui.viewer.ViewerViewModel

/**
 * toadGALLERY product shell (roadmap Phase 2, ADR-0009; Phase 3 wiring #25/#29).
 *
 * Four top-level destinations: Photos, Albums, Search, Settings (inherited from the
 * superseded ADR-0005; carried forward in ADR-0009). The bottom navigation bar is
 * visible ONLY on top-level routes; sub-screens such as the album detail view
 * replace it with their own top app bar (Mi-Gallery-style sub-page UX).
 */
object Destinations {
    const val PHOTOS = "photos"
    const val ALBUMS = "albums"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /**
     * The progress sub-screen (F-1, follow-up 2026-11-04): the build-time
     * progress.json mirror --- the two independent bars (overall project
     * + the Ente port workstream) + their phase lists, reached from the
     * Settings entry. A static snapshot by design (the app never fetches
     * progress); sub-page UX like About (no bottom bar).
     */
    const val PROGRESS = "progress"

    /**
     * The Map tab (r16 round, Phase 4 still-open item "on-device map
     * view"): the offline world map over the catalogue's persisted EXIF
     * GPS. Top-level like the other tabs; zero network --- the outline is
     * a bundled asset (docs/phase-4/on-device-map.md).
     */
    const val MAP = "map"

    /** Route pattern for the album detail sub-screen (issue #29). */
    const val ALBUM_DETAIL = "album/{bucketId}"

    /**
     * The People browse sub-screen (r25 round, Workstream 6): the live
     * face clusters over the `peopleAlbumEnabled` consent (ADR-0010).
     * Not top-level on purpose (sub-page UX like album detail; the
     * bottom bar hides): reached from the Albums tab's People section
     * via its "See all" action.
     */
    const val PEOPLE = "people"

    /**
     * Route pattern for the Person detail sub-screen (r25, Workstream 6):
     * one face cluster's photos + the rename/merge/hide/remove actions.
     * The cluster id is a STRING nav argument (the engine's own row
     * identity), percent-encoded by [personDetail] exactly like the
     * Details route encodes its Uri --- an id containing route-reserved
     * characters must never splinter the pattern.
     */
    const val PERSON_DETAIL = "person/{clusterId}"

    /**
     * The Places browse sub-screen (r27, the organisational UX round):
     * the offline region buckets over the catalogue's GPS coordinates.
     * Not top-level (sub-page UX like People; the bottom bar hides):
     * reached from the Albums tab's Places section via its "See all"
     * action.
     */
    const val PLACES = "places"

    /**
     * Route pattern for the Place detail sub-screen (r27): one region
     * bucket's media grid. The region key is a STRING nav argument
     * ("r<row>c<column>", the pure PlacesRegionGrid identity) --- the
     * durable fallback for the one-shot PlaceMediaHandoff request.
     */
    const val PLACE_DETAIL = "place/{regionKey}"

    /**
     * Route pattern for the Things collection detail sub-screen (r39,
     * the ente-port exit round): one named semantic collection's
     * members. The collection id is a STRING nav argument (OUR OWN
     * stable slug identity, unlike the People feature's unstable
     * engine cluster ids) and is the WHOLE request --- the screen's
     * ViewModel re-derives the membership from the live CLIP index
     * through the repository, so no hand-off slot is needed.
     */
    const val THINGS_DETAIL = "things/{collectionId}"

    /**
     * The collage creator sub-screen (r27): the Photos tab's
     * multi-selection (2..9 photos, carried by the CollageHandoff ---
     * far too large for nav args) laid out by a template and exportable
     * as one new image. Argument-free like the viewer route.
     */
    const val COLLAGE = "collage"

    /**
     * Sub-screen for the system-Photo-Picker selection (issue #27). Not
     * top-level on purpose: the bottom bar hides here (sub-page UX like
     * album detail) and the route is reachable only from the Settings tab.
     */
    const val PICKED = "picked"

    /**
     * Full-screen media viewer (issue #50). Not top-level (the bottom bar
     * hides) and argument-free by design: the tap-time window lives in the
     * shell-scoped [ViewerViewModel], the same hand-off the picked-photos
     * set uses --- a list of content URIs is far too large for nav args.
     */
    const val VIEWER = "viewer"

    /**
     * Recycle bin sub-screen (issue #31). Not top-level (sub-page UX);
     * reached from the Settings entry. The trash store is MediaStore's
     * own `IS_TRASHED` rows (API 30+), so the screen needs no arguments.
     */
    const val TRASH = "trash"

    /**
     * Hidden vault sub-screen (issue #32, ADR-0017). Not top-level
     * (sub-page UX); reached from the Settings entry. The vault's lock
     * state lives in the shell-scoped `VaultViewModel` (the viewerViewModel
     * hand-off), so the Photos move-to-vault flow and this screen share one
     * auto-locked session --- nothing to pass through nav arguments.
     */
    const val VAULT = "vault"

    /**
     * About sub-screen (roadmap Phase 3: appearance/About slice). Not
     * top-level (sub-page UX); reached from the Settings entry. Identity,
     * privacy posture, credits and the vault diagnostics + ring log live
     * there; the probe runs against the SAME shell-scoped `VaultViewModel`
     * session the vault screen uses.
     */
    const val ABOUT = "about"

    /**
     * Metadata Details sub-screen (roadmap Phase 4: "Metadata Details view
     * with read"). Not top-level (sub-page UX); reached from the viewer's
     * info sheet. Arguments follow the existing NavType conventions: the
     * MediaStore `_id` as [NavType.LongType] (like album detail's
     * bucketId) plus the content Uri string, percent-encoded by
     * [details] --- the Uri is the ACCESS handle (ADR-0006), not identity,
     * but it is what reconstructs the identity when the item is not in
     * the catalogue yet.
     */
    const val DETAILS = "details/{mediaId}?uri={uri}"

    fun albumDetail(bucketId: Long): String = "album/$bucketId"

    /** Builds the Person detail route for [clusterId], encoding the id. */
    fun personDetail(clusterId: String): String =
        "person/${Uri.encode(clusterId)}"

    /** Builds the Place detail route for [regionKey], encoding the key. */
    fun placeDetail(regionKey: String): String =
        "place/${Uri.encode(regionKey)}"

    /** Builds the Things collection detail route for [collectionId], encoding the id. */
    fun thingsDetail(collectionId: String): String =
        "things/${Uri.encode(collectionId)}"

    /** Builds the details route for [mediaId], encoding the [uri] argument. */
    fun details(mediaId: Long, uri: Uri): String =
        "details/$mediaId?uri=${Uri.encode(uri.toString())}"

    /** Routes that show the bottom navigation bar. */
    val topLevel = setOf(PHOTOS, ALBUMS, SEARCH, SETTINGS, MAP)
}

private data class TopLevelDestination(
    val route: String,
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
)

private val bottomNavItems = listOf(
    TopLevelDestination(Destinations.PHOTOS, R.drawable.ic_nav_photos, R.string.nav_photos),
    TopLevelDestination(Destinations.ALBUMS, R.drawable.ic_nav_albums, R.string.nav_albums),
    TopLevelDestination(Destinations.SEARCH, R.drawable.ic_nav_search, R.string.nav_search),
    // r16: the Map tab rides the same drawable-icon mechanism as its
    // siblings (see ic_nav_map for the shape's provenance).
    TopLevelDestination(Destinations.MAP, R.drawable.ic_nav_map, R.string.nav_map),
    TopLevelDestination(Destinations.SETTINGS, R.drawable.ic_nav_settings, R.string.nav_settings),
)

@Composable
fun ToadGalleryApp() {
    ToadGalleryTheme {
        val navController = rememberNavController()
        // Issue #27: the picked-photos set is shared between the Settings tab
        // (picker entry point + live count) and the `picked` sub-screen, so it
        // lives in ONE activity-scoped ViewModel instead of nav arguments.
        val pickedViewModel: PickedPhotosViewModel = viewModel()
        // Issue #50: the viewer window uses the same shell-scoped hand-off.
        // Grid screens call viewerViewModel.open(window, index) and navigate;
        // ViewerScreen reads the same instance. viewModel() at the shell
        // level scopes to the ACTIVITY, so both sides see one object.
        val viewerViewModel: ViewerViewModel = viewModel()
        // Issue #32: the hidden vault's lock state is shared between the
        // Settings entry (live status), the Photos move-to-vault flow and
        // the `vault` sub-screen, so it lives in ONE activity-scoped
        // ViewModel like the picked set and the viewer window above.
        val vaultViewModel: VaultViewModel = viewModel()
        // Appearance settings (roadmap Phase 3): ONE shell-scoped instance
        // so a layout / photos-per-row / group-by change on the Settings
        // tab propagates to the Photos timeline and album detail in the
        // same session (SettingsScreen used to create its own ViewModel;
        // the shell hoists it now, the picked/viewer/vault hand-off
        // pattern).
        val settingsViewModel: SettingsViewModel = viewModel()
        val openViewer: (window: List<MediaItem>, index: Int) -> Unit = { window, index ->
            viewerViewModel.open(window, index)
            navController.navigate(Destinations.VIEWER)
        }
        // r27 (Slideshow + Stories): the viewer opened WITH its slideshow
        // mode armed --- the same shell-scoped hand-off [openViewer] uses,
        // plus the slideshow flag the Stories shelf and the grid/album
        // overflow actions set before navigating.
        val openSlideshow: (window: List<MediaItem>) -> Unit = { window ->
            viewerViewModel.open(window, 0)
            viewerViewModel.startSlideshow()
            navController.navigate(Destinations.VIEWER)
        }
        // Auto-lock (issue #32, ADR-0017): the unlocked vault session is
        // in-memory only and clears when the ACTIVITY stops --- the observer
        // lives here at the shell (the app-level ProcessLifecycleOwner would
        // be an extra dependency; the activity lifecycle is the honest v1
        // proxy for "the app went to the background").
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, vaultViewModel) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    vaultViewModel.onBackgrounded()
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route

        Scaffold(
            bottomBar = {
                // Sub-screens (album detail) hide the bottom bar: they carry their
                // own top app bar with an Up action instead (issue #29).
                if (currentRoute in Destinations.topLevel) {
                    NavigationBar {
                        bottomNavItems.forEach { destination ->
                            NavigationBarItem(
                                selected = currentRoute == destination.route,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        // Single top-level back stack: tapping the same tab
                                        // does not stack screens; Up returns to Photos.
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(destination.iconRes),
                                        contentDescription = null, // decorative; label carries meaning
                                    )
                                },
                                label = { Text(stringResource(destination.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Destinations.PHOTOS,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                composable(Destinations.PHOTOS) {
                    // Phase 3 (issue #25): real MediaStore timeline replaces the placeholder.
                    PhotosScreen(
                        onOpenViewer = openViewer,
                        vaultViewModel = vaultViewModel,
                        settingsViewModel = settingsViewModel,
                        // r27: the Stories shelf's playback hand-off, the
                        // header bar's slideshow launch, and the selection
                        // bar's collage launch.
                        onOpenStory = openSlideshow,
                        onStartSlideshow = openSlideshow,
                        onStartCollage = { navController.navigate(Destinations.COLLAGE) },
                    )
                }
                composable(Destinations.ALBUMS) {
                    // Phase 3 (issue #29): real folder grid replaces the placeholder.
                    // r25: the shell hands the People section's two navigations
                    // down --- "See all" opens the People sub-screen, a person
                    // card opens that person's detail (the album cards' pattern).
                    // r27: the Places section's pair --- "See all" opens the
                    // Places sub-screen, a region chip opens that region's grid.
                    // r39: the Things section's pair --- a collection chip opens
                    // that collection's detail (the id IS the request; no
                    // hand-off slot).
                    AlbumsScreen(
                        onOpenAlbum = { bucketId ->
                            navController.navigate(Destinations.albumDetail(bucketId))
                        },
                        onOpenPeople = { navController.navigate(Destinations.PEOPLE) },
                        onOpenPerson = { clusterId ->
                            navController.navigate(Destinations.personDetail(clusterId))
                        },
                        onOpenPlaces = { navController.navigate(Destinations.PLACES) },
                        onOpenPlace = { regionKey ->
                            navController.navigate(Destinations.placeDetail(regionKey))
                        },
                        onOpenThing = { collectionId ->
                            navController.navigate(Destinations.thingsDetail(collectionId))
                        },
                    )
                }
                composable(
                    route = Destinations.ALBUM_DETAIL,
                    arguments = listOf(navArgument("bucketId") { type = NavType.LongType }),
                ) { backStackEntry ->
                    val bucketId = backStackEntry.arguments?.getLong("bucketId")
                    if (bucketId != null && bucketId != 0L) {
                        AlbumDetailScreen(
                            bucketId = bucketId,
                            onBack = { navController.popBackStack() },
                            onOpenViewer = openViewer,
                            settingsViewModel = settingsViewModel,
                            // r27: the album surface's slideshow launch (the
                            // overflow action the round contract names).
                            onStartSlideshow = openSlideshow,
                        )
                    }
                }
                composable(Destinations.PLACES) {
                    // r27: the Places browse sub-screen over the offline
                    // region buckets. The consent state's "Open Settings"
                    // action routes to the Settings tab with the same
                    // single-top-level-stack discipline the People and Map
                    // screens use.
                    PlacesScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPlace = { regionKey ->
                            navController.navigate(Destinations.placeDetail(regionKey))
                        },
                        onOpenSettings = {
                            navController.navigate(Destinations.SETTINGS) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(
                    route = Destinations.PLACE_DETAIL,
                    arguments = listOf(navArgument("regionKey") { type = NavType.StringType }),
                ) { backStackEntry ->
                    // r27: one region's media grid. The ViewModel is
                    // screen-scoped (per-region state, the PersonDetail
                    // factory pattern); photo taps open the SAME
                    // shell-scoped viewer hand-off the grid screens use.
                    val regionKey = backStackEntry.arguments?.getString("regionKey")
                    if (!regionKey.isNullOrEmpty()) {
                        PlaceDetailScreen(
                            regionKey = regionKey,
                            onBack = { navController.popBackStack() },
                            onOpenViewer = openViewer,
                        )
                    }
                }
                composable(
                    route = Destinations.THINGS_DETAIL,
                    arguments = listOf(navArgument("collectionId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    // r39 (the ente-port exit round): one named semantic
                    // collection's ranked members. The ViewModel is
                    // screen-scoped (per-collection state, the PersonDetail
                    // factory pattern); photo taps open the SAME
                    // shell-scoped viewer hand-off the grid screens use.
                    // r46 build-shape gate: no SEMANTIC index in this build
                    // --- the honest unavailable card instead of a
                    // ThingsDetailScreen whose ViewModel cannot exist here.
                    if (!BuildShape.semanticIndex) {
                        BuildFeatureUnavailable()
                        return@composable
                    }
                    val collectionId = backStackEntry.arguments?.getString("collectionId")
                    if (!collectionId.isNullOrEmpty()) {
                        ThingsDetailScreen(
                            collectionId = collectionId,
                            onBack = { navController.popBackStack() },
                            onOpenViewer = openViewer,
                        )
                    }
                }
                composable(Destinations.COLLAGE) {
                    // r27: the collage creator over the selection the
                    // Photos tab stashed in the CollageHandoff (the
                    // viewer's argument-free hand-off pattern).
                    CollageScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Destinations.PEOPLE) {
                    // r25 (Workstream 6): the People browse sub-screen over the
                    // live face clusters. The consent-off state's "Open Settings"
                    // action routes to the Settings tab with the same
                    // single-top-level-stack discipline a bottom-bar tap uses
                    // (the Map tab's consent copy wired it first).
                    // r46 build-shape gate: no FACE index in this build --- the
                    // honest unavailable card instead of a PeopleScreen whose
                    // ViewModel cannot exist here (the Albums section that
                    // navigates here is hidden by the same gate).
                    if (!BuildShape.faceIndex) {
                        BuildFeatureUnavailable()
                        return@composable
                    }
                    PeopleScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPerson = { clusterId ->
                            navController.navigate(Destinations.personDetail(clusterId))
                        },
                        onOpenSettings = {
                            navController.navigate(Destinations.SETTINGS) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(
                    route = Destinations.PERSON_DETAIL,
                    arguments = listOf(navArgument("clusterId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    // r25 (Workstream 6): one cluster's photos + management ops.
                    // The ViewModel is screen-scoped (per-cluster state, the
                    // AlbumDetailScreen factory pattern); photo taps open the
                    // SAME shell-scoped viewer hand-off the grid screens use.
                    // r46 build-shape gate: the same FACE-index rule as the
                    // People route above.
                    if (!BuildShape.faceIndex) {
                        BuildFeatureUnavailable()
                        return@composable
                    }
                    val clusterId = backStackEntry.arguments?.getString("clusterId")
                    if (!clusterId.isNullOrEmpty()) {
                        PersonDetailScreen(
                            clusterId = clusterId,
                            onBack = { navController.popBackStack() },
                            onOpenViewer = openViewer,
                        )
                    }
                }
                composable(Destinations.SEARCH) {
                    // Phase 3 (issue #62): real name search replaces the
                    // placeholder; results open the shared viewer hand-off.
                    // r19a: the shell's settingsViewModel rides along so the
                    // results grid honors the photos-per-row / GRID layout
                    // setting (the Photos-tab wiring); the screen's own
                    // permission state rides the SAME shared machinery.
                    // r26 (Workstream 7): the Content mode's consent /
                    // models / not-indexed cards route to the Settings tab
                    // with the same single-top-level-stack discipline the
                    // People and Map consent copies wired first.
                    SearchScreen(
                        onOpenViewer = openViewer,
                        settingsViewModel = settingsViewModel,
                        onOpenSettings = {
                            navController.navigate(Destinations.SETTINGS) {
                                // Same single-top-level-stack discipline as a
                                // bottom-bar tap (see NavigationBarItem).
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(Destinations.MAP) {
                    // r16 (Phase 4 still-open item): the offline Map tab.
                    // The viewer opens through the SAME shell-scoped
                    // hand-off the grid screens use; the consent state's
                    // "turn it on" action routes to the Settings tab, where
                    // the r14 ACCESS_MEDIA_LOCATION row lives.
                    MapScreen(
                        onOpenViewer = openViewer,
                        onOpenSettings = {
                            navController.navigate(Destinations.SETTINGS) {
                                // Same single-top-level-stack discipline as a
                                // bottom-bar tap (see NavigationBarItem).
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(Destinations.SETTINGS) {
                    // Issue #27: the real Settings screen (Photo Picker toggle,
                    // pick entry point, fallback copy) replaces the placeholder;
                    // the appearance section and About entry ride the SHELL-scoped
                    // settingsViewModel so changes reach every grid live.
                    SettingsScreen(
                        pickedViewModel = pickedViewModel,
                        settingsViewModel = settingsViewModel,
                        onBrowsePicked = { navController.navigate(Destinations.PICKED) },
                        onOpenTrash = { navController.navigate(Destinations.TRASH) },
                        vaultViewModel = vaultViewModel,
                        onOpenVault = { navController.navigate(Destinations.VAULT) },
                        onOpenAbout = { navController.navigate(Destinations.ABOUT) },
                        onOpenProgress = { navController.navigate(Destinations.PROGRESS) },
                    )
                }
                composable(Destinations.PICKED) {
                    // Issue #27: sub-screen over the shared picked set; the
                    // shell hides the bottom bar (non-topLevel route). r19a:
                    // the shell's settingsViewModel rides along so the grid
                    // honors the photos-per-row / GRID layout setting.
                    PickedPhotosScreen(
                        pickedViewModel = pickedViewModel,
                        settingsViewModel = settingsViewModel,
                        onBack = { navController.popBackStack() },
                        // r50 ux fix: the picked grid opens the shared viewer
                        // (the same shell-scoped hand-off every other grid
                        // surface uses).
                        onOpenViewer = openViewer,
                    )
                }
                composable(Destinations.VIEWER) {
                    // Issue #50: full-screen viewer over the tap-time window;
                    // the SAME shell-scoped instance the grid screens wrote to.
                    // Phase 4: the info sheet gains a Full-details entry that
                    // opens the metadata Details sub-screen for THAT item.
                    ViewerScreen(
                        viewModel = viewerViewModel,
                        onBack = { navController.popBackStack() },
                        onOpenDetails = { item ->
                            navController.navigate(Destinations.details(item.id, item.uri))
                        },
                    )
                }
                composable(
                    route = Destinations.DETAILS,
                    arguments = listOf(
                        navArgument("mediaId") { type = NavType.LongType },
                        navArgument("uri") { type = NavType.StringType },
                    ),
                ) { backStackEntry ->
                    // Phase 4: the metadata Details sub-screen; its ViewModel
                    // is screen-scoped (per-item state, unlike the shell
                    // hand-offs above).
                    val mediaId = backStackEntry.arguments?.getLong("mediaId")
                    val uriText = backStackEntry.arguments?.getString("uri")
                    if (mediaId != null && mediaId != 0L && uriText != null) {
                        DetailsScreen(
                            mediaId = mediaId,
                            uri = Uri.parse(uriText),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
                composable(Destinations.TRASH) {
                    // Issue #31: recycle bin over MediaStore's IS_TRASHED rows.
                    // r19a: the shell's settingsViewModel rides along so the
                    // grid honors the photos-per-row / GRID layout setting.
                    TrashScreen(
                        onBack = { navController.popBackStack() },
                        settingsViewModel = settingsViewModel,
                    )
                }
                composable(Destinations.VAULT) {
                    // Issue #32: hidden vault over the SAME shell-scoped
                    // session the Photos move-to-vault flow used.
                    VaultScreen(
                        viewModel = vaultViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Destinations.ABOUT) {
                    // About sub-screen: identity, privacy, credits and the
                    // vault diagnostics probed through the SAME shell-scoped
                    // vault session (the vault screen's hand-off pattern).
                    AboutScreen(
                        vaultViewModel = vaultViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Destinations.PROGRESS) {
                    // Progress sub-screen (F-1): the build-time
                    // progress.json mirror --- the two independent bars +
                    // phase lists; a static snapshot (zero network).
                    ProgressScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * Reusable screen-state composable (roadmap Phase 2: empty / loading / denied /
 * unsupported / error system --- issue #13). Renders empty-state copy today; the
 * permission state becomes live in Phase 3 when media access lands.
 */
@Composable
fun StateScreen(
    @DrawableRes iconRes: Int,
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    @StringRes actionRes: Int? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null, // decorative
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        if (actionRes != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(stringResource(actionRes))
            }
        }
        // r50 ux fix: the "Phase 3 --- on-device timeline" milestone badge is
        // GONE --- an internal roadmap label rendered under every shared
        // state surface (empty/error/permission, all tabs) shipped a dev
        // note to users on surfaces that have nothing to do with Phase 3.
    }
}

/**
 * The r46 (APK-matrix round) build-shape fallback: the routed body of an
 * engine feature the installed flavor does not carry (the People /
 * Person-detail routes without the FACE index, the Things route without
 * the SEMANTIC index --- the Albums sections that navigate there are
 * hidden by the same [BuildShape] gates). A small honest centered card
 * --- the feature is not "broken", it simply is not part of this build
 * (the engine-family builds install separately, like the edit app) ---
 * instead of constructing a ViewModel whose engine cannot exist here.
 * The route entries themselves stay (nav-graph stability); only the
 * bodies are gated. The test tag pins the surface for the UI suites.
 */
@Composable
private fun BuildFeatureUnavailable() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("build_feature_unavailable"),
        ) {
            Text(
                text = stringResource(R.string.build_feature_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
            )
        }
    }
}
