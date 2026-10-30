package io.github.dukeoftoad.gallery.ui.media

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.dukeoftoad.gallery.R

/**
 * Stable semantics tag carried by [MediaPermissionState]'s root by default
 * (screen-state system: issue #13; instrumented-test surface: issue #38.7).
 * Call sites share it so a test can find the denial state on any screen; the
 * [MediaPermissionState] `tag` parameter overrides it when a per-screen variant
 * is ever needed.
 */
const val PERMISSION_DENIED_TAG = "state_permission_denied"

/**
 * The permission-denied screen state shared by the Photos and Albums tabs
 * (screen-state system: issue #13; Phase 3 wiring issues #25/#29).
 *
 * Frozen contract (docs/architecture/screen-states.md §3):
 *  - **What access is needed:** photo & video *read* access (the Phase 3
 *    media permission set), explained by `state_permission_title` /
 *    `state_permission_body` — which also state the honest privacy posture:
 *    media stays on the device, the app has no network access at all.
 *  - **Re-request path:** [onGrant] relaunches the shared media-permission
 *    request (`rememberMediaPermissionRequester`). The launcher returns
 *    immediately, without showing UI, when access is already granted — so the
 *    button also serves as the re-check after returning from Settings.
 *  - **Settings deep-link (internal):** a second action, "Open Settings",
 *    deep-links to this app's page in system Settings
 *    (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`). No call-site wiring is
 *    required. Why it exists: after two OS denials Android silently denies
 *    further requests — the re-request button becomes a dead end, and the
 *    app-details Settings page is the only remaining path.
 *  - **Test surface:** the root carries [PERMISSION_DENIED_TAG] unless the
 *    optional [tag] overrides it (issue #38.7: tests assert state, not copy).
 *
 * Compatibility: existing call sites that only pass `onGrant` (e.g.
 * `MediaPermissionState(onGrant = requester::launch)` in PhotosScreen) keep
 * compiling — [tag] defaults to null.
 */
@Composable
fun MediaPermissionState(
    onGrant: () -> Unit,
    tag: String? = null,
) {
    // LocalContext inside an activity-hosted composition is the activity
    // itself, so startActivity needs no NEW_TASK flag and no extra launcher.
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .testTag(tag ?: PERMISSION_DENIED_TAG),
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
        TextButton(
            onClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    )
                )
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.state_action_open_settings))
        }
        Text(
            text = stringResource(R.string.state_permission_settings_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.state_shell_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
