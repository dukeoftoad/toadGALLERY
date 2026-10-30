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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.albums.AlbumDetailScreen
import io.github.dukeoftoad.gallery.ui.albums.AlbumsScreen
import io.github.dukeoftoad.gallery.ui.photos.PhotosScreen
import io.github.dukeoftoad.gallery.ui.theme.ToadGalleryTheme

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
                    PhotosScreen()
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
                        )
                    }
                }
                composable(Destinations.SEARCH) {
                    StateScreen(
                        iconRes = R.drawable.ic_nav_search,
                        titleRes = R.string.state_search_empty_title,
                        bodyRes = R.string.state_search_empty_body,
                    )
                }
                composable(Destinations.SETTINGS) {
                    StateScreen(
                        iconRes = R.drawable.ic_nav_settings,
                        titleRes = R.string.state_settings_empty_title,
                        bodyRes = R.string.state_settings_empty_body,
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
