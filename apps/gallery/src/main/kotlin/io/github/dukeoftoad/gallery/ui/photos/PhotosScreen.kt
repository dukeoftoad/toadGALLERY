package io.github.dukeoftoad.gallery.ui.photos

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.StateScreen

/**
 * Photos tab (Phase 3 first slice, issue #25).
 *
 * Three runtime states derived from the user's permission decision:
 *  - PermissionNeeded: the offline-friendly rationale + Grant button
 *  - Loading: a single centered spinner while the MediaStore cursor walks
 *  - Ready: a 3-column LazyVerticalGrid of square thumbnails via Coil
 *
 * The grid is intentionally minimal: square aspect, no overlay, no paging.
 * Each later Phase 3 slice (aspect-aware staggered grid, video duration
 * overlay, paging, viewer entry) layers on top of this skeleton.
 *
 * Privacy: every image is a local content URI (ADR-0006). Coil is wired
 * without its network artifact, so even if a URI somehow resolved to a
 * remote URL the loader could not fetch it.
 */
@Composable
fun PhotosScreen(
    viewModel: PhotosViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()

    // The media permissions depend on API level: granular READ_MEDIA_* on 33+,
    // legacy READ_EXTERNAL_STORAGE below. The launcher accepts the union; the
    // OS only prompts for the ones the app actually needs at the device's API.
    val mediaPermissions: Array<String> = rememberBuildSpecificPermissions()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.any { it }
        if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
    }

    // First composition: kick off the permission request immediately. The user
    // sees the rationale state only on denial or after re-entry from settings.
    LaunchedEffect(Unit) {
        permissionLauncher.launch(mediaPermissions)
    }

    when (val s = state) {
        is PhotosState.Loading -> LoadingState()
        is PhotosState.PermissionNeeded -> PermissionState(onGrant = {
            permissionLauncher.launch(mediaPermissions)
        })
        is PhotosState.Ready -> {
            if (s.items.isEmpty()) {
                StateScreen(
                    iconRes = R.drawable.ic_nav_photos,
                    titleRes = R.string.state_photos_empty_title,
                    bodyRes = R.string.state_photos_empty_body,
                )
            } else {
                PhotoGrid(items = s.items)
            }
        }
    }
}

@Composable
private fun rememberBuildSpecificPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PermissionState(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_nav_photos),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = stringResource(R.string.state_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = stringResource(R.string.state_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        TextButton(
            onClick = onGrant,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text(stringResource(R.string.state_action_grant))
        }
        Text(
            text = stringResource(R.string.state_shell_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}

@Composable
private fun PhotoGrid(items: List<io.github.dukeoftoad.gallery.data.MediaItem>) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            items = items,
            key = { item -> "${item.uri}" },
        ) { item ->
            AsyncImage(
                model = item.uri,
                contentDescription = null, // decorative; the viewer will describe
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            )
        }
    }
}
