package io.github.dukeoftoad.gallery.ui.media

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable

/**
 * Shared media-permission request helper (Phase 3, issues #25 and #29).
 *
 * The media permissions depend on API level: granular `READ_MEDIA_IMAGES` +
 * `READ_MEDIA_VIDEO` on Android 13+ (`TIRAMISU`), legacy
 * `READ_EXTERNAL_STORAGE` below. Both the Photos and Albums screens need the
 * same request, so the API-level dispatch and the result-reduction
 * ("was ANY of the requested permissions granted?") live here once.
 *
 * The launcher returns immediately without showing UI when the permissions
 * are already granted, which keeps tab re-entry cheap.
 */
class MediaPermissionRequester internal constructor(
    /** The exact permission set for this device's API level. */
    val permissions: Array<String>,
    private val launchRequest: (Array<String>) -> Unit,
) {
    fun launch() = launchRequest(permissions)
}

/**
 * Remembers (and re-creates on configuration change) the media-permission
 * requester. `onResult` receives true when ANY requested permission was
 * granted, false when all were denied.
 */
@Composable
fun rememberMediaPermissionRequester(
    onResult: (Boolean) -> Unit,
): MediaPermissionRequester {
    val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        onResult(result.values.any { it })
    }
    return MediaPermissionRequester(
        permissions = permissions,
        launchRequest = { launcher.launch(it) },
    )
}
