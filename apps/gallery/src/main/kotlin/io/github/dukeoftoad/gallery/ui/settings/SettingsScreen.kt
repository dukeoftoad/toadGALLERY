package io.github.dukeoftoad.gallery.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.DataSizeFormats
import io.github.dukeoftoad.gallery.ui.picker.PickedPhotosViewModel
import io.github.dukeoftoad.gallery.ui.trash.TrashUiState
import io.github.dukeoftoad.gallery.ui.trash.TrashViewModel
import io.github.dukeoftoad.gallery.ui.vault.VaultUiState
import io.github.dukeoftoad.gallery.ui.vault.VaultViewModel

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
 *
 * Recycle bin entry (issue #31): shows the live item count + total size
 * from a screen-scoped [TrashViewModel] (refreshed on entry; the count can
 * lag until the next entry --- a snapshot, not a live observer) and opens
 * the `trash` sub-screen via [onOpenTrash]. Below API 30 the entry renders
 * as inert copy: no MediaStore trash protocol exists there (the Photos
 * delete flow stays permanent, see `FileOpsViewModel.requestDelete`).
 *
 * Hidden vault entry (issue #32, ADR-0017): mirrors the recycle-bin row and
 * opens the `vault` sub-screen via [onOpenVault]. The status line comes
 * from the SHELL-scoped [vaultViewModel] (the shared session, not a
 * screen-scoped holder): "not set up" / "Locked" / item count + size. The
 * count appears only while the vault is UNLOCKED --- a locked vault leaks
 * no size or count here, by design. Below Android 10 (API 29) there is no
 * consent protocol for the move/delete legs, so the entry renders as inert
 * copy (the same shape as the recycle bin below R).
 */
@Composable
fun SettingsScreen(
    pickedViewModel: PickedPhotosViewModel,
    onBrowsePicked: () -> Unit,
    onOpenTrash: () -> Unit,
    vaultViewModel: VaultViewModel,
    onOpenVault: () -> Unit,
) {
    val settingsViewModel: SettingsViewModel = viewModel()
    val usePhotoPicker by settingsViewModel.usePhotoPicker.collectAsState()
    val pickedItems by pickedViewModel.picked.collectAsState()
    val trashViewModel: TrashViewModel = viewModel()
    val trashState by trashViewModel.state.collectAsState()
    LaunchedEffect(Unit) { trashViewModel.refresh() }
    val vaultState by vaultViewModel.state.collectAsState()

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

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_recycle_bin_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Count + size from the entry-time snapshot; the Trash screen
            // reloads on entry, so this row catches up next time it shows.
            val ready = trashState as? TrashUiState.Ready
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable(onClick = onOpenTrash)
                    .testTag("settings_recycle_bin_entry")
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_recycle_bin_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (ready != null) {
                        Text(
                            text = pluralStringResource(
                                R.plurals.trash_header_items,
                                ready.items.size,
                                ready.items.size,
                            ) + "  ·  " + DataSizeFormats.formatBytes(ready.totalBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.settings_recycle_bin_pre_r_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_vault_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable(onClick = onOpenVault)
                    .testTag("settings_vault_entry")
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_vault),
                    contentDescription = null, // decorative; the row title carries meaning
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_vault_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The status line never leaks counts while locked (see
                    // the screen KDoc); Resolving is a sub-second first frame.
                    val vaultSubtitle = when (val vault = vaultState) {
                        VaultUiState.NotConfigured ->
                            stringResource(R.string.vault_settings_not_configured)
                        VaultUiState.Locked ->
                            stringResource(R.string.vault_settings_locked)
                        is VaultUiState.Unlocked -> pluralStringResource(
                            R.plurals.vault_header_items,
                            vault.entries.size,
                            vault.entries.size,
                        ) + "  ·  " + DataSizeFormats.formatBytes(
                            vault.entries.sumOf { it.sizeBytes },
                        )
                        else -> null
                    }
                    if (vaultSubtitle != null) {
                        Text(
                            text = vaultSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.settings_vault_pre_q_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
