package io.github.dukeoftoad.gallery.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.picker.PickedPhotosViewModel

/**
 * The Settings tab (issue #27: first real content on this screen).
 *
 * Scaffold-less scrollable column per the v1 design: a title and the one
 * live preference --- the "Use system Photo Picker" switch (default OFF;
 * persisted by [SettingsViewModel]). The description stays honest about
 * the privacy posture: the picker path browses ONLY user-selected media
 * via user-granted content URIs, requires no library-wide permission at
 * all, and --- like the rest of the app --- has no network access.
 *
 * API gating (issue #27):
 *  - API 33+ with the toggle ON: a "Pick photos to browse" button launches
 *    `ActivityResultContracts.PickMultipleVisualMedia` (the
 *    `ACTION_PICK_IMAGES` system Photo Picker), plus a live count and a
 *    browse entry for the current session's picked set.
 *  - Pre-33 with the toggle ON: explanatory fallback copy --- the system
 *    picker needs Android 13+ --- while the normal `READ_MEDIA_*` /
 *    `READ_EXTERNAL_STORAGE` flow on the Photos/Albums tabs keeps working
 *    untouched (graceful fallback: no permission is requested here).
 *
 * Wiring: `pickedViewModel` is the activity-scoped state holder the shell
 * hoists (shared with the `picked` sub-screen, which keeps the picked set
 * alive across Settings re-entries); [onBrowsePicked] navigates there and
 * is invoked BOTH right after a successful pick and from the browse row.
 * Test tags (`settings_toggle_photo_picker`, `settings_pick_entry`) land
 * with the future Settings UI tests.
 */
@Composable
fun SettingsScreen(
    pickedViewModel: PickedPhotosViewModel,
    onBrowsePicked: () -> Unit,
) {
    val settingsViewModel: SettingsViewModel = viewModel()
    val usePhotoPicker by settingsViewModel.usePhotoPicker.collectAsState()
    val pickedItems by pickedViewModel.picked.collectAsState()

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            pickedViewModel.onPicked(uris)
            onBrowsePicked()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_section_access),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_use_photo_picker),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = usePhotoPicker,
                onCheckedChange = { settingsViewModel.setUsePhotoPicker(it) },
                modifier = Modifier
                    .padding(start = 16.dp)
                    .testTag("settings_toggle_photo_picker"),
            )
        }
        Text(
            text = stringResource(R.string.settings_use_photo_picker_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (usePhotoPicker) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(
                    onClick = {
                        pickerLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            )
                        )
                    },
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .testTag("settings_pick_entry"),
                ) {
                    Text(stringResource(R.string.settings_pick_photos))
                }
                if (pickedItems.isNotEmpty()) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.settings_picked_count,
                            pickedItems.size,
                            pickedItems.size,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    TextButton(
                        onClick = onBrowsePicked,
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.settings_browse_picked))
                    }
                }
            } else {
                // Graceful fallback (issue #27): no picker on pre-33, the
                // current permission-based flow keeps carrying the app.
                Text(
                    text = stringResource(R.string.settings_photo_picker_pre33_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}
