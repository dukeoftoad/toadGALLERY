package io.github.dukeoftoad.gallery.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.ui.albums.AlbumDetailScreen
import io.github.dukeoftoad.gallery.ui.albums.AlbumsScreen
import io.github.dukeoftoad.gallery.ui.picker.PickedPhotosScreen
import io.github.dukeoftoad.gallery.ui.picker.PickedPhotosViewModel
import io.github.dukeoftoad.gallery.ui.photos.PhotosScreen
import io.github.dukeoftoad.gallery.ui.search.SearchScreen
import io.github.dukeoftoad.gallery.ui.settings.AboutScreen
import io.github.dukeoftoad.gallery.ui.settings.SettingsScreen
import io.github.dukeoftoad.gallery.ui.settings.SettingsViewModel
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

    /** Route pattern for the album detail sub-screen (issue #29). */
    const val ALBUM_DETAIL = "album/{bucketId}"

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

    fun albumDetail(bucketId: Long): String = "album/$bucketId"

    /** Routes that show the bottom navigation bar. */
    val topLevel = setOf(PHOTOS, ALBUMS, SEARCH, SETTINGS)
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
                    )
                }
                composable(Destinations.ALBUMS) {
                    // Phase 3 (issue #29): real folder grid replaces the placeholder.
                    AlbumsScreen(
                        onOpenAlbum = { bucketId ->
                            navController.navigate(Destinations.albumDetail(bucketId))
                        }
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
                        )
                    }
                }
                composable(Destinations.SEARCH) {
                    // Phase 3 (issue #62): real name search replaces the
                    // placeholder; results open the shared viewer hand-off.
                    SearchScreen(onOpenViewer = openViewer)
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
                    )
                }
                composable(Destinations.PICKED) {
                    // Issue #27: sub-screen over the shared picked set; the
                    // shell hides the bottom bar (non-topLevel route).
                    PickedPhotosScreen(
                        pickedViewModel = pickedViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Destinations.VIEWER) {
                    // Issue #50: full-screen viewer over the tap-time window;
                    // the SAME shell-scoped instance the grid screens wrote to.
                    ViewerScreen(
                        viewModel = viewerViewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Destinations.TRASH) {
                    // Issue #31: recycle bin over MediaStore's IS_TRASHED rows.
                    TrashScreen(
                        onBack = { navController.popBackStack() },
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
        Text(
            text = stringResource(R.string.state_shell_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
