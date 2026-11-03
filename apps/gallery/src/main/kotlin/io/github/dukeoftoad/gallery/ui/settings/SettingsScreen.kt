package io.github.dukeoftoad.gallery.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.DataSizeFormats
import io.github.dukeoftoad.gallery.data.media.location.MediaLocationState
import io.github.dukeoftoad.gallery.ml.engine.ModelProvisioner
import io.github.dukeoftoad.gallery.ml.index.ClipIndexWorkState
import io.github.dukeoftoad.gallery.ml.index.FaceIndexWorkState
import io.github.dukeoftoad.gallery.ml.index.KEY_CLIP_OUT_FILL_INDEXED
import io.github.dukeoftoad.gallery.ml.index.KEY_CLIP_OUT_FILL_OUTCOME
import io.github.dukeoftoad.gallery.ml.index.KEY_CLIP_OUT_FILL_RESUMED
import io.github.dukeoftoad.gallery.ml.index.KEY_CLIP_OUT_FILL_ROWS
import io.github.dukeoftoad.gallery.ml.index.KEY_CLIP_OUT_FILL_SKIPPED
import io.github.dukeoftoad.gallery.ml.index.KEY_CLIP_OUT_INDEXED
import io.github.dukeoftoad.gallery.ml.index.KEY_CLIP_OUT_SKIPPED_UP_TO_DATE
import io.github.dukeoftoad.gallery.ml.index.KEY_CLIP_OUT_TOTAL_CLIPS
import io.github.dukeoftoad.gallery.ml.index.KEY_OUT_CLUSTERS
import io.github.dukeoftoad.gallery.ml.index.KEY_OUT_FACES
import io.github.dukeoftoad.gallery.ml.index.KEY_OUT_INDEXED
import io.github.dukeoftoad.gallery.ml.index.KEY_OUT_SKIPPED_UP_TO_DATE
import io.github.dukeoftoad.gallery.ml.index.KEY_OUT_TOTAL_FACES
import io.github.dukeoftoad.gallery.ui.picker.PickedPhotosViewModel
import io.github.dukeoftoad.gallery.ui.trash.TrashUiState
import io.github.dukeoftoad.gallery.ui.trash.TrashViewModel
import io.github.dukeoftoad.gallery.ui.vault.VaultUiState
import io.github.dukeoftoad.gallery.ui.vault.VaultViewModel

/**
 * The Settings tab (issue #27: first real content; appearance + About since
 * the roadmap Phase 3 appearance slice).
 *
 * Scaffold-less scrollable column per the v1 design. Sections, top to
 * bottom:
 *
 * ## Appearance (Ente-inspired)
 * The shell-scoped [SettingsViewModel] --- passed IN from `ToadGalleryApp`
 * since this slice (it used to create its own `viewModel()` here) so a
 * layout / photos-per-row / group-by change propagates live to the Photos
 * timeline and album detail in the same session --- exposes the collected
 * [Appearance] the section edits:
 *  - Layout: two side-by-side selectable cards (`settings_layout_grid` /
 *    `settings_layout_collage`), each carrying a small Compose-drawn
 *    preview (2x2 squares vs staggered rectangles), its label, a one-line
 *    body and a leading check when selected. COLLAGE is checked by default
 *    per the [Appearance] defaults (the app's render since issues #34/#29).
 *  - Photos per row: a label plus minus/plus [IconButton]s
 *    (`settings_columns_minus` / `settings_columns_value` /
 *    `settings_columns_plus`), bounded by [MIN_COLUMNS]/[MAX_COLUMNS] and
 *    clamped again ViewModel-side.
 *  - Group by: three single-select FilterChips (`settings_group_by_*`).
 *    DAY/MONTH layer full-line date headers into the Photos timeline (see
 *    `TimelineRows`/`MediaPagingGrid`).
 *
 * ## Media access (issue #27)
 * The one live preference --- the "Use system Photo Picker" switch (default
 * OFF; persisted by [SettingsViewModel]). The description stays honest
 * about the privacy posture: the picker path browses ONLY user-selected
 * media via user-granted content URIs, requires no library-wide permission
 * at all, and --- like the rest of the app --- has no network access.
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
 *
 * About entry (appearance slice): the same row shape as the recycle bin,
 * opening the `about` sub-screen via [onOpenAbout] --- identity, privacy
 * posture, credits and vault diagnostics live there.
 *
 * AI engine section (r22 dogfood, ADR-0020): the maintainer's window into
 * the model-delivery path --- FACE capability status + per-asset presence
 * from the process-wide provisioner (ToadGalleryApplication), the real
 * allowlisted-host download, deletion, and one JNI inference self-test
 * over the first JPEG on the device. A developer section, honestly
 * labeled. Since r25 (Workstream 6) the section also carries the
 * USER-facing "People albums" consent row --- the ADR-0010 opt-in gate
 * (default off) for the People UI that consumes the engine's clusters:
 * one Switch + on-device-processing copy, persisted by the shell-scoped
 * [SettingsViewModel] under the `peopleAlbumEnabled` key. See
 * `AiEngineViewModel` for the dogfood rows.
 */
@Composable
fun SettingsScreen(
    pickedViewModel: PickedPhotosViewModel,
    settingsViewModel: SettingsViewModel,
    onBrowsePicked: () -> Unit,
    onOpenTrash: () -> Unit,
    vaultViewModel: VaultViewModel,
    onOpenVault: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val usePhotoPicker by settingsViewModel.usePhotoPicker.collectAsState()
    val appearance by settingsViewModel.appearance.collectAsState()
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

    // Phase 4 location consent: the runtime answer lands straight in the
    // shell-scoped state holder (no re-check needed --- the result IS the
    // OS truth; refresh() covers the system-settings detour).
    val locationState by settingsViewModel.mediaLocation.state.collectAsState()
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        settingsViewModel.mediaLocation.onPermissionResult(granted)
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

        // ---- Appearance (Ente-inspired; first section, the screenshot's order) ----
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_section_appearance),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.settings_layout_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LayoutCard(
                title = stringResource(R.string.settings_layout_grid),
                body = stringResource(R.string.settings_layout_grid_body),
                selected = appearance.layout == LayoutStyle.GRID,
                onSelect = { settingsViewModel.setLayout(LayoutStyle.GRID) },
                uniformPreview = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_layout_grid"),
            )
            LayoutCard(
                title = stringResource(R.string.settings_layout_collage),
                body = stringResource(R.string.settings_layout_collage_body),
                selected = appearance.layout == LayoutStyle.COLLAGE,
                onSelect = { settingsViewModel.setLayout(LayoutStyle.COLLAGE) },
                uniformPreview = false,
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_layout_collage"),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_photos_per_row),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { settingsViewModel.setColumns(appearance.columns - 1) },
                enabled = appearance.columns > MIN_COLUMNS,
                modifier = Modifier.testTag("settings_columns_minus"),
            ) {
                Icon(
                    imageVector = RemoveIcon,
                    contentDescription = stringResource(R.string.settings_columns_decrease),
                )
            }
            Text(
                text = appearance.columns.toString(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .testTag("settings_columns_value"),
            )
            IconButton(
                onClick = { settingsViewModel.setColumns(appearance.columns + 1) },
                enabled = appearance.columns < MAX_COLUMNS,
                modifier = Modifier.testTag("settings_columns_plus"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.settings_columns_increase),
                )
            }
        }
        Text(
            text = stringResource(R.string.settings_group_by),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GroupBy.entries.forEach { option ->
                FilterChip(
                    selected = appearance.groupBy == option,
                    onClick = { settingsViewModel.setGroupBy(option) },
                    label = { Text(stringResource(option.labelRes)) },
                    modifier = Modifier.testTag(
                        when (option) {
                            GroupBy.NONE -> "settings_group_by_none"
                            GroupBy.DAY -> "settings_group_by_day"
                            GroupBy.MONTH -> "settings_group_by_month"
                        }
                    ),
                )
            }
        }

        // ---- Media access (issue #27) ----
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

        // ---- Location data (Phase 4: ACCESS_MEDIA_LOCATION consent) ----
        // Renders the shell-scoped state holder; on API 29+ the row asks
        // the OS (the grant is a DISPLAY gate for GPS coordinates only ---
        // ADR-0014: coordinates never leave the device). Below 29 the
        // permission does not exist and nothing was ever redactable, so
        // the entry renders as inert copy (same shape as the pre-R
        // recycle-bin entry).
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_location_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.settings_location_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val locationStatus = when (locationState) {
                MediaLocationState.GRANTED ->
                    stringResource(R.string.settings_location_state_granted)
                MediaLocationState.DENIED ->
                    stringResource(R.string.settings_location_state_denied)
                else -> stringResource(R.string.settings_location_state_unknown)
            }
            Text(
                text = locationStatus,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (locationState == MediaLocationState.UNKNOWN ||
                locationState == MediaLocationState.DENIED
            ) {
                Button(
                    onClick = {
                        locationPermissionLauncher.launch(
                            Manifest.permission.ACCESS_MEDIA_LOCATION,
                        )
                    },
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("settings_location_request"),
                ) {
                    Text(stringResource(R.string.settings_location_request))
                }
            }
        } else {
            Text(
                text = stringResource(R.string.settings_location_pre_q_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ---- Recycle bin (issue #31) ----
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

        // ---- Hidden vault (issue #32, ADR-0017) ----
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

        // ---- AI engine (r22 dogfood, ADR-0020) ----
        // The maintainer's window into the model-delivery path: FACE
        // provisioning over the REAL allowlisted-host downloader, deletion,
        // and one JNI inference self-test. Screen-scoped state holder (the
        // recycle-bin section's `viewModel()` pattern); every presence row
        // is the provisioner's own disk-backed derivation, never cached
        // wishful thinking. Actions run on Dispatchers.IO inside the VM;
        // the buttons sit disabled while one is in flight.
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_ai_section),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("settings_ai_section"),
        )
        Text(
            text = stringResource(R.string.settings_ai_section_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        // ---- People albums consent (r25, Workstream 6; ADR-0010) ----
        // The opt-in gate for the People UI: default OFF, one Switch, copy
        // that states the on-device posture. The row rides the SHELL-scoped
        // settingsViewModel (write-through persistence under the
        // `peopleAlbumEnabled` key); the People/Albums surfaces read the
        // preference at their own entry refresh, so a toggle here lands
        // there on the next visit.
        val peopleAlbumsEnabled by settingsViewModel.peopleAlbumsEnabled.collectAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_people_albums),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = peopleAlbumsEnabled,
                onCheckedChange = { settingsViewModel.setPeopleAlbumsEnabled(it) },
                modifier = Modifier
                    .padding(start = 16.dp)
                    .testTag("settings_toggle_people_albums"),
            )
        }
        Text(
            text = stringResource(R.string.settings_people_albums_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        // ---- Magic search consent (r26, Workstream 7; ADR-0010) ----
        // The opt-in gate for the Search tab's Content mode: the SAME row
        // shape as the People consent one row above (default OFF, one
        // Switch, on-device copy, write-through under the
        // `magicSearchEnabled` key); the Search tab's Content-mode
        // ViewModel reads the preference at query time, so a toggle here
        // lands there on the next query.
        val magicSearchEnabled by settingsViewModel.magicSearchEnabled.collectAsState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_magic_search),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = magicSearchEnabled,
                onCheckedChange = { settingsViewModel.setMagicSearchEnabled(it) },
                modifier = Modifier
                    .padding(start = 16.dp)
                    .testTag("settings_toggle_magic_search"),
            )
        }
        Text(
            text = stringResource(R.string.settings_magic_search_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        val aiViewModel: AiEngineViewModel = viewModel()
        val aiState by aiViewModel.state.collectAsState()
        LaunchedEffect(Unit) { aiViewModel.refresh() }
        Text(
            text = stringResource(
                if (aiState.engineLibraryPresent) {
                    R.string.settings_ai_library_present
                } else {
                    R.string.settings_ai_library_missing
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(
                when (aiState.faceCapability) {
                    ModelProvisioner.CapabilityState.NotProvisioned -> R.string.settings_ai_state_none
                    is ModelProvisioner.CapabilityState.Partial -> R.string.settings_ai_state_partial
                    ModelProvisioner.CapabilityState.Ready -> R.string.settings_ai_state_ready
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        aiState.assets.forEach { asset ->
            Text(
                text = asset.fileName + " — " + stringResource(
                    if (asset.installed) {
                        R.string.settings_ai_asset_installed
                    } else {
                        R.string.settings_ai_asset_missing
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Button(
            onClick = aiViewModel::downloadFaceModels,
            enabled = !aiState.busy,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag("settings_ai_download"),
        ) {
            Text(stringResource(R.string.settings_ai_download))
        }
        Button(
            onClick = aiViewModel::deleteFaceModels,
            enabled = !aiState.busy,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_ai_delete"),
        ) {
            Text(stringResource(R.string.settings_ai_delete))
        }
        Button(
            onClick = aiViewModel::runSelfTest,
            enabled = !aiState.busy,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_ai_selftest"),
        ) {
            Text(stringResource(R.string.settings_ai_selftest))
        }
        when (val note = aiState.note) {
            AiDogfoodState.Note.Downloaded -> Text(
                text = stringResource(R.string.settings_ai_download_done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is AiDogfoodState.Note.DownloadFailed -> Text(
                text = stringResource(R.string.settings_ai_download_failed, note.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            AiDogfoodState.Note.Deleted -> Text(
                text = stringResource(R.string.settings_ai_deleted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            AiDogfoodState.Note.SemanticDownloaded -> Text(
                text = stringResource(R.string.settings_ai_semantic_download_done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is AiDogfoodState.Note.SemanticDownloadFailed -> Text(
                text = stringResource(R.string.settings_ai_semantic_download_failed, note.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            AiDogfoodState.Note.SemanticDeleted -> Text(
                text = stringResource(R.string.settings_ai_semantic_deleted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            null -> Unit
        }
        when (val result = aiState.selfTest) {
            AiDogfoodState.SelfTestResult.LibraryMissing -> Text(
                text = stringResource(R.string.settings_ai_selftest_library_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            AiDogfoodState.SelfTestResult.NoJpegFound -> Text(
                text = stringResource(R.string.settings_ai_selftest_no_jpeg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is AiDogfoodState.SelfTestResult.Ok -> Text(
                text = pluralStringResource(
                    R.plurals.settings_ai_selftest_faces,
                    result.faces,
                    result.fileName,
                    result.faces,
                    result.elapsedMs,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is AiDogfoodState.SelfTestResult.Failed -> Text(
                text = stringResource(R.string.settings_ai_selftest_failed, result.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            null -> Unit
        }

        // ---- Semantic models (r26, Workstream 7) ----
        // The SEMANTIC pack's card: the r22 FACE download card's shape
        // over the CLIP image/text/vocab assets --- per-asset presence
        // rows from the provisioner's own disk-backed derivation, the
        // capability state line, Download + Delete, and outcome notes.
        // The size is the manifest pin's own total (never a
        // hand-maintained copy), formatted with the house byte formatter.
        Text(
            text = stringResource(R.string.settings_ai_semantic_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(
                when (aiState.semanticCapability) {
                    ModelProvisioner.CapabilityState.NotProvisioned -> R.string.settings_ai_semantic_state_none
                    is ModelProvisioner.CapabilityState.Partial -> R.string.settings_ai_semantic_state_partial
                    ModelProvisioner.CapabilityState.Ready -> R.string.settings_ai_semantic_state_ready
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        aiState.semanticAssets.forEach { asset ->
            Text(
                text = asset.fileName + " — " + stringResource(
                    if (asset.installed) {
                        R.string.settings_ai_asset_installed
                    } else {
                        R.string.settings_ai_asset_missing
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Button(
            onClick = aiViewModel::downloadSemanticModels,
            enabled = !aiState.busy,
            modifier = Modifier
                .padding(top = 16.dp)
                .testTag("settings_ai_semantic_download"),
        ) {
            Text(
                stringResource(
                    R.string.settings_ai_semantic_download,
                    aiViewModel.semanticModelsSize,
                )
            )
        }
        Button(
            onClick = aiViewModel::deleteSemanticModels,
            enabled = !aiState.busy,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_ai_semantic_delete"),
        ) {
            Text(stringResource(R.string.settings_ai_semantic_delete))
        }
        when (val note = aiState.note) {
            AiDogfoodState.Note.SemanticDownloaded -> Text(
                text = stringResource(R.string.settings_ai_semantic_download_done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is AiDogfoodState.Note.SemanticDownloadFailed -> Text(
                text = stringResource(R.string.settings_ai_semantic_download_failed, note.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            AiDogfoodState.Note.SemanticDeleted -> Text(
                text = stringResource(R.string.settings_ai_semantic_deleted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            else -> Unit // the FACE notes render in their own block above
        }

        // ---- Background face indexing (r24 dogfood) ----
        // The r24 slice replaces the r23 12-photo manual run with the
        // WorkManager-backed queue: revision-aware planning, bounded
        // batches, retry/resume across process death, live progress from
        // the queue state, and the threshold tuning control the r23
        // round flagged for this round. Reset cancels + erases the whole
        // app-private face-index directory (ML store AND queue state).
        Text(
            text = stringResource(R.string.settings_ai_queue_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        val queueState by aiViewModel.queueState.collectAsState()
        val queueThreshold by aiViewModel.threshold.collectAsState()
        Text(
            text = stringResource(
                R.string.settings_ai_queue_threshold,
                String.format(java.util.Locale.ROOT, "%.2f", queueThreshold),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_ai_queue_threshold"),
        )
        Slider(
            value = queueThreshold.toFloat(),
            onValueChange = { aiViewModel.setThreshold(it.toDouble()) },
            valueRange = AI_THRESHOLD_MIN.toFloat()..AI_THRESHOLD_MAX.toFloat(),
            steps = AI_THRESHOLD_STEPS,
            modifier = Modifier
                .padding(top = 4.dp)
                .testTag("settings_ai_queue_threshold_slider"),
        )
        Button(
            onClick = aiViewModel::startBackgroundIndex,
            enabled = aiState.engineLibraryPresent,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_ai_index_run"),
        ) {
            Text(stringResource(R.string.settings_ai_queue_start))
        }
        when (queueState) {
            is FaceIndexWorkState.Enqueued, is FaceIndexWorkState.Running -> Button(
                onClick = aiViewModel::cancelBackgroundIndex,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("settings_ai_queue_cancel"),
            ) {
                Text(stringResource(R.string.settings_ai_queue_cancel))
            }
            else -> Unit
        }
        Button(
            onClick = aiViewModel::resetFaceIndex,
            enabled = !aiState.busy,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_ai_index_delete"),
        ) {
            Text(stringResource(R.string.settings_ai_queue_reset))
        }
        when (val queue = queueState) {
            is FaceIndexWorkState.Running -> Text(
                text = stringResource(
                    R.string.settings_ai_queue_progress,
                    queue.processedThisRun,
                    queue.batchSize,
                    queue.queueDepth,
                    queue.facesWrittenThisRun,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is FaceIndexWorkState.Enqueued -> Text(
                text = stringResource(R.string.settings_ai_queue_enqueued),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is FaceIndexWorkState.Succeeded -> {
                val clusters = queue.output.getInt(KEY_OUT_CLUSTERS, -1)
                Text(
                    // Two honest terminal shapes: the draining run that
                    // clustered (clusters >= 0) and the no-op drain that
                    // did not (empty plan --- no wasted recluster pass).
                    text = if (clusters >= 0) {
                        stringResource(
                            R.string.settings_ai_queue_done,
                            queue.output.getInt(KEY_OUT_INDEXED, 0),
                            queue.output.getInt(KEY_OUT_FACES, 0),
                            queue.output.getInt(KEY_OUT_SKIPPED_UP_TO_DATE, 0),
                            clusters,
                            queue.output.getLong(KEY_OUT_TOTAL_FACES, 0L),
                        )
                    } else {
                        stringResource(
                            R.string.settings_ai_queue_done_no_cluster,
                            queue.output.getInt(KEY_OUT_INDEXED, 0),
                            queue.output.getInt(KEY_OUT_FACES, 0),
                            queue.output.getInt(KEY_OUT_SKIPPED_UP_TO_DATE, 0),
                            queue.output.getLong(KEY_OUT_TOTAL_FACES, 0L),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            is FaceIndexWorkState.Failed -> Text(
                text = stringResource(R.string.settings_ai_queue_failed, queue.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            FaceIndexWorkState.Cancelled -> Text(
                text = stringResource(R.string.settings_ai_queue_cancelled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            FaceIndexWorkState.Idle -> Unit
        }

        // ---- Background semantic indexing (r26, Workstream 7) ----
        // The semantic queue's card: the r24 face queue card's shape,
        // sibling edition --- its own unique WorkManager chain, live
        // per-batch progress, terminal outcome lines (including the
        // once-per-drain reconciliation pass's report), start / cancel /
        // reset. The reset's honest scope: the QUEUE's bookkeeping only
        // (`clip-index-state.json`); the clip ROWS ride the r24 Reset
        // face index, whose directory delete erases the whole
        // `ml-index.sqlite` both queues share.
        Text(
            text = stringResource(R.string.settings_ai_semantic_queue_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        val clipQueueState by aiViewModel.clipQueueState.collectAsState()
        Button(
            onClick = aiViewModel::startSemanticIndex,
            enabled = aiState.engineLibraryPresent,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_ai_semantic_index_run"),
        ) {
            Text(stringResource(R.string.settings_ai_semantic_queue_start))
        }
        when (clipQueueState) {
            is ClipIndexWorkState.Enqueued, is ClipIndexWorkState.Running -> Button(
                onClick = aiViewModel::cancelSemanticIndex,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("settings_ai_semantic_queue_cancel"),
            ) {
                Text(stringResource(R.string.settings_ai_semantic_queue_cancel))
            }
            else -> Unit
        }
        Button(
            onClick = aiViewModel::resetSemanticIndex,
            enabled = !aiState.busy,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag("settings_ai_semantic_index_delete"),
        ) {
            Text(stringResource(R.string.settings_ai_semantic_queue_reset))
        }
        when (val clip = clipQueueState) {
            is ClipIndexWorkState.Running -> Text(
                text = stringResource(
                    R.string.settings_ai_semantic_queue_progress,
                    clip.processedThisRun,
                    clip.batchSize,
                    clip.queueDepth,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is ClipIndexWorkState.Enqueued -> Text(
                text = stringResource(R.string.settings_ai_semantic_queue_enqueued),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            is ClipIndexWorkState.Succeeded -> {
                // Two honest terminal shapes, like the r24 card's: the
                // draining run that ran the reconciliation pass (a fill
                // outcome string present) and the no-op drain that did not
                // (empty plan --- no wasted fill pass).
                val fillOutcome = clip.output.getString(KEY_CLIP_OUT_FILL_OUTCOME)
                Text(
                    text = stringResource(
                        R.string.settings_ai_semantic_queue_done,
                        clip.output.getInt(KEY_CLIP_OUT_INDEXED, 0),
                        clip.output.getInt(KEY_CLIP_OUT_SKIPPED_UP_TO_DATE, 0),
                        clip.output.getLong(KEY_CLIP_OUT_TOTAL_CLIPS, 0L),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (fillOutcome != null) {
                    // The engine's own reconciliation report, surfaced
                    // verbatim (outcome + counters + the resumed flag).
                    Text(
                        text = stringResource(
                            R.string.settings_ai_semantic_fill,
                            fillOutcome,
                            clip.output.getInt(KEY_CLIP_OUT_FILL_ROWS, 0),
                            clip.output.getInt(KEY_CLIP_OUT_FILL_INDEXED, 0),
                            clip.output.getInt(KEY_CLIP_OUT_FILL_SKIPPED, 0),
                            if (clip.output.getBoolean(KEY_CLIP_OUT_FILL_RESUMED, false)) {
                                stringResource(R.string.settings_ai_semantic_fill_resumed_yes)
                            } else {
                                stringResource(R.string.settings_ai_semantic_fill_resumed_no)
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            is ClipIndexWorkState.Failed -> Text(
                text = stringResource(R.string.settings_ai_semantic_queue_failed, clip.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            ClipIndexWorkState.Cancelled -> Text(
                text = stringResource(R.string.settings_ai_semantic_queue_cancelled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            ClipIndexWorkState.Idle -> Unit
        }

        // ---- About (appearance slice) ----
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.settings_about_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        // The same row shape as the recycle-bin entry (no subtitle: there
        // is no live state to narrate --- the About page carries it all).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable(onClick = onOpenAbout)
                .testTag("settings_about_entry")
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_about_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One selectable Ente-style layout card: rounded border (the theme's
 * primary when selected, a quiet outline otherwise), a small Compose-drawn
 * grid/collage preview, the label with a leading check icon (transparent
 * when unselected, so the label never shifts on selection), and a one-line
 * body. The caller supplies [Modifier.weight] so the pair splits the width
 * (each card lands at roughly 120.dp on a phone).
 */
@Composable
private fun LayoutCard(
    title: String,
    body: String,
    selected: Boolean,
    onSelect: () -> Unit,
    uniformPreview: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
    ) {
        LayoutPreview(uniform = uniformPreview)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null, // decorative; the card border + title carry the state
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Transparent
                },
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(18.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * The layout card's preview glyph: a 2x2 of uniform squares for the GRID
 * layout vs two staggered columns of different-height rectangles for
 * COLLAGE (the same footprint, so the cards stay visually balanced).
 * Compose-drawn on purpose --- no fitting drawable exists in the res set,
 * and the shapes say more than any icon would.
 */
@Composable
private fun LayoutPreview(uniform: Boolean) {
    val swatch = MaterialTheme.colorScheme.onSurfaceVariant
    if (uniform) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
                Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(14.dp, 10.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
                Box(Modifier.size(14.dp, 18.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(14.dp, 18.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
                Box(Modifier.size(14.dp, 10.dp).clip(RoundedCornerShape(3.dp)).background(swatch))
            }
        }
    }
}

/**
 * The stepper's minus glyph: the canonical Material "remove" vector (the
 * exact 14x2 bar of the extended icon set), built inline because the app
 * deliberately ships only `material-icons-core` --- whose 49-glyph set
 * carries [Icons.Filled.Add] but not "Remove" --- and this slice may not
 * add the multi-megabyte `material-icons-extended` artifact for one bar.
 * Same 24dp viewport and stroke weight as `Icons.Filled.Add`, so the
 * minus/plus pair renders as one family.
 */
// The r24 threshold slider's tuning window (mirrors AiEngineViewModel's
// MIN/MAX constants; the ViewModel clamps writes into the same range).
private const val AI_THRESHOLD_MIN = 0.10
private const val AI_THRESHOLD_MAX = 0.40
private const val AI_THRESHOLD_STEPS = 29

private val RemoveIcon: ImageVector = ImageVector.Builder(
    name = "Remove",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19f, 13f)
        horizontalLineTo(5f)
        verticalLineTo(11f)
        horizontalLineTo(19f)
        verticalLineTo(13f)
        close()
    }
}.build()

/** The localized label of one group-by option (UI-layer resource mapping). */
private val GroupBy.labelRes: Int
    get() = when (this) {
        GroupBy.NONE -> R.string.settings_group_by_none
        GroupBy.DAY -> R.string.settings_group_by_day
        GroupBy.MONTH -> R.string.settings_group_by_month
    }
