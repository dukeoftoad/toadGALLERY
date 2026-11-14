package com.toadgallery.gallery.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadgallery.gallery.BuildConfig
import com.toadgallery.gallery.R
import com.toadgallery.gallery.buildshape.BuildShape
import com.toadgallery.gallery.data.DataSizeFormats
import com.toadgallery.gallery.data.media.location.MediaLocationState
import com.toadgallery.gallery.data.vault.VaultBiometricReadiness
import com.toadgallery.gallery.ml.engine.ModelProvisioner
import com.toadgallery.gallery.ml.index.ClipIndexWorkState
import com.toadgallery.gallery.ml.index.FaceIndexWorkState
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_FILL_INDEXED
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_FILL_OUTCOME
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_FILL_RESUMED
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_FILL_ROWS
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_FILL_SKIPPED
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_INDEXED
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_SKIPPED_UNREADABLE
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_SKIPPED_UP_TO_DATE
import com.toadgallery.gallery.ml.index.KEY_CLIP_OUT_TOTAL_CLIPS
import com.toadgallery.gallery.ml.index.KEY_OUT_CLUSTERS
import com.toadgallery.gallery.ml.index.KEY_OUT_FACES
import com.toadgallery.gallery.ml.index.KEY_OUT_INDEXED
import com.toadgallery.gallery.ml.index.KEY_OUT_SKIPPED_UNREADABLE
import com.toadgallery.gallery.ml.index.KEY_OUT_SKIPPED_UP_TO_DATE
import com.toadgallery.gallery.ml.index.KEY_OUT_TOTAL_FACES
import com.toadgallery.gallery.ui.LocalSettingsHubRequest
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.components.ToadDisplayFont
import com.toadgallery.gallery.ui.components.ToadGlassSurface
import com.toadgallery.gallery.ui.components.ToadMotionSpec
import com.toadgallery.gallery.ui.components.ToadStatusPill
import com.toadgallery.gallery.ui.components.ToadSubPageTopBar
import com.toadgallery.gallery.ui.picker.PickedPhotosViewModel
import com.toadgallery.gallery.ui.theme.AllToadPalettes
import com.toadgallery.gallery.ui.theme.ToadShapes
import com.toadgallery.gallery.ui.theme.ToadTheme
import com.toadgallery.gallery.ui.theme.ToadThemeId
import com.toadgallery.gallery.ui.trash.TrashUiState
import com.toadgallery.gallery.ui.trash.TrashViewModel
import com.toadgallery.gallery.ui.vault.VaultUiState
import com.toadgallery.gallery.ui.vault.VaultViewModel

@OptIn(ExperimentalLayoutApi::class)
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
    val useDynamicColor by settingsViewModel.useDynamicColor.collectAsState()
    val toadPaletteId by settingsViewModel.toadPaletteId.collectAsState()

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            pickedViewModel.onPicked(uris)
            onBrowsePicked()
        }
    }

    val locationState by settingsViewModel.mediaLocation.state.collectAsState()
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> settingsViewModel.mediaLocation.onPermissionResult(granted) }

    // r77.10: the hub's LIVE storage read (the prototype's Storage & Data
    // Management section carries the running total twice: the eyebrow hint
    // ("N MB", bold) and the compact segmented preview bar above the
    // manager row). The SAME [StorageInsightsViewModel] the Storage Manager
    // sub-page instantiates --- a read-only sum over app-owned directories,
    // no new business logic, no new scan.
    val hubStorageViewModel: StorageInsightsViewModel = viewModel()
    val hubStorageState by hubStorageViewModel.state.collectAsState()
    val hubStorageTotalBytes = hubStorageState.entries.sumOf { it.bytes }

    var subPage by rememberSaveable { mutableStateOf(SettingsSubPage.HUB) }
    var settingsSearch by rememberSaveable { mutableStateOf("") }

    val hubScroll = rememberScrollState()
    val subPageScrolls = remember {
        SettingsSubPage.entries.associateWith { ScrollState(initial = 0) }
    }

    BackHandler(enabled = subPage != SettingsSubPage.HUB) {
        subPage = SettingsSubPage.HUB
    }

    val hubRequest = LocalSettingsHubRequest.current
    LaunchedEffect(subPage) {
        if (subPage != SettingsSubPage.HUB) hubRequest.requestHubReturn()
    }

    val query = settingsSearch.trim().lowercase()
    val match: (String) -> Boolean = { label ->
        query.isEmpty() || label.lowercase().contains(query)
    }

    // Shared section predicates (used by both the section gates and the empty state).
    val appearanceMatch = match(stringResource(R.string.settings_section_appearance)) ||
        match("theme") || match("timeline") || match("layout") ||
        match("grid") || match("palette") || match("color")
    val aiMatch = BuildShape.withEngine &&
        (match("ai") || match("people") || match("magic") || match("search") || match("things"))
    val privacyMatch = match("privacy") || match("permissions") || match("picker") ||
        match("location") || match("gps") || match("vault") || match("exif") || match("media")
    val storageMatch = match("storage") || match("cache") || match("data") ||
        match("space") || match("database")
    val aboutMatch = match("about") || match("version") || match("changelog") || match("diagnostics")
    val anyMatch = appearanceMatch || aiMatch || privacyMatch || storageMatch || aboutMatch

    Column(modifier = Modifier.fillMaxSize()) {
        if (subPage == SettingsSubPage.HUB) {
            ToadGlassSurface(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    ToadStatusPill(
                        text = "v" + BuildConfig.VERSION_NAME,
                        // r77.10: the hub header's version badge is the
                        // prototype's `primary-container` `text-[11px]`
                        // pill --- one step larger and in the emphasized
                        // container colors, unlike the sub-page status
                        // pills that stay on `bgSurfaceHigh`.
                        background = MaterialTheme.colorScheme.primaryContainer,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        if (subPage != SettingsSubPage.HUB) {
            ToadSubPageTopBar(
                title = when (subPage) {
                    SettingsSubPage.THEME -> "Theme & Colors"
                    SettingsSubPage.LAYOUT -> "Timeline Layout"
                    SettingsSubPage.AI_ENGINE -> "AI Engine"
                    SettingsSubPage.VAULT -> "Hidden Vault"
                    SettingsSubPage.STORAGE -> "Storage Manager"
                    SettingsSubPage.MEDIA_ACCESS -> "Media Access"
                    SettingsSubPage.HUB -> ""
                },
                statusPill = when (subPage) {
                    SettingsSubPage.MEDIA_ACCESS ->
                        if (usePhotoPicker) "Photo Picker" else "Full library"
                    SettingsSubPage.LAYOUT -> appearance.columns.toString() + " cols"
                    SettingsSubPage.AI_ENGINE ->
                        if (BuildShape.withEngine) "On-device" else "Not in this build"
                    SettingsSubPage.VAULT -> when (vaultState) {
                        is VaultUiState.Unlocked -> "Unlocked"
                        VaultUiState.Locked -> "Locked"
                        VaultUiState.NotConfigured -> "Not set up"
                        else -> "Encrypted"
                    }
                    else -> null
                },
                onBack = { subPage = SettingsSubPage.HUB },
            )
        }

        AnimatedContent(
            targetState = subPage,
            transitionSpec = {
                val forward = targetState != SettingsSubPage.HUB
                val enter = if (forward) {
                    slideInHorizontally(animationSpec = tween(ToadMotionSpec.SUBPAGE_MS)) { it } +
                        fadeIn(tween(ToadMotionSpec.SUBPAGE_MS))
                } else {
                    slideInHorizontally(animationSpec = tween(ToadMotionSpec.SUBPAGE_MS)) { -it / 3 } +
                        fadeIn(tween(ToadMotionSpec.SUBPAGE_MS))
                }
                val exit = if (forward) {
                    slideOutHorizontally(animationSpec = tween(ToadMotionSpec.SUBPAGE_MS)) { -it / 3 } +
                        fadeOut(tween(ToadMotionSpec.SUBPAGE_MS))
                } else {
                    slideOutHorizontally(animationSpec = tween(ToadMotionSpec.SUBPAGE_MS)) { it } +
                        fadeOut(tween(ToadMotionSpec.SUBPAGE_MS))
                }
                (enter togetherWith exit).apply {
                    targetContentZIndex = if (forward) 2f else 0f
                }
            },
            label = "settingsSubPage",
        ) { page ->
            val pageScroll = if (page == SettingsSubPage.HUB) {
                hubScroll
            } else {
                subPageScrolls.getValue(page)
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(pageScroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {

        if (page == SettingsSubPage.HUB) {

        TextField(
            value = settingsSearch,
            onValueChange = { settingsSearch = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("settings_search_field"),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                if (settingsSearch.isNotEmpty()) {
                    IconButton(
                        onClick = { settingsSearch = "" },
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            )
                            .testTag("settings_search_clear"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.cd_search_clear),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            shape = RoundedCornerShape(50),
        )

        if (query.isEmpty()) {
            ToadHeroStatusCard(
                paletteLabel = toadPaletteLabelFor(toadPaletteId),
                appearance = appearance,
                onAiStatus = { subPage = SettingsSubPage.AI_ENGINE },
            )
            Spacer(Modifier.height(16.dp))
        }

        if (appearanceMatch) {
            SettingsSectionHeader(
                stringResource(R.string.settings_section_appearance),
                hint = stringResource(R.string.settings_section_hint_appearance),
            )
            SettingsGroupCard {
                SettingsNavRow(
                    title = stringResource(R.string.settings_theme_mode),
                    supporting = themeModeSupporting(appearance.themeMode, settingsViewModel),
                    onClick = { subPage = SettingsSubPage.THEME },
                    iconVector = Icons.Filled.Palette,
                    testTag = "settings_theme_entry",
                    modifier = Modifier.padding(top = 12.dp),
                )
                SettingsNavRow(
                    title = stringResource(R.string.settings_layout_title),
                    supporting = layoutSupporting(appearance),
                    onClick = { subPage = SettingsSubPage.LAYOUT },
                    iconVector = Icons.Filled.GridView,
                    testTag = "settings_layout_grid",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        if (aiMatch) {
            SettingsSectionHeader(
                stringResource(R.string.settings_ai_section),
                hint = stringResource(R.string.settings_section_hint_ai),
            )
            SettingsGroupCard {
                SettingsNavRow(
                    title = stringResource(R.string.settings_ai_engine_entry),
                    supporting = aiEngineSupporting(),
                    onClick = { subPage = SettingsSubPage.AI_ENGINE },
                    iconVector = Icons.Filled.Memory,
                    testTag = "settings_ai_entry",
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }
        }

        if (privacyMatch) {
            SettingsSectionHeader(
                stringResource(R.string.settings_section_access),
                hint = stringResource(R.string.settings_section_hint_access),
            )
            SettingsGroupCard {
                SettingsNavRow(
                    title = stringResource(R.string.settings_media_access_entry),
                    supporting = mediaAccessSupporting(usePhotoPicker),
                    onClick = { subPage = SettingsSubPage.MEDIA_ACCESS },
                    iconVector = Icons.Filled.FolderOpen,
                    testTag = "settings_media_access_entry",
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                SettingsGroupCard {
                    SettingsNavRow(
                        title = stringResource(R.string.settings_vault_title),
                        supporting = when (val vault = vaultState) {
                            VaultUiState.NotConfigured ->
                                stringResource(R.string.vault_settings_not_configured)
                            VaultUiState.Locked ->
                                stringResource(R.string.vault_settings_locked)
                            is VaultUiState.Unlocked -> pluralStringResource(
                                R.plurals.vault_header_items,
                                vault.entries.size,
                                vault.entries.size,
                            )
                            else -> null
                        },
                        onClick = { subPage = SettingsSubPage.VAULT },
                        iconRes = R.drawable.ic_vault,
                        testTag = "settings_vault_entry",
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                }
            }
        }

        if (storageMatch) {
            // r77.10: the hint is the prototype's live "N MB" total (bold
            // muted) --- rendered only while a measurement has RESOLVED
            // ("measuring" or an all-empty read keeps the header hint
            // absent rather than lying with a zero).
            SettingsSectionHeader(
                stringResource(R.string.settings_storage_section),
                hint = if (!hubStorageState.measuring && hubStorageTotalBytes > 0L) {
                    DataSizeFormats.formatBytes(hubStorageTotalBytes)
                } else {
                    null
                },
                hintEmphasized = true,
            )
            SettingsGroupCard {
                // r77.10: the prototype hub's compact segmented preview bar
                // (h-2.5 rounded-full track on `bg-surface-highest`, 2dp
                // padding + 2dp inter-segment gaps, flush color fills) sits
                // directly above the Storage & Cache Manager row, inside
                // the same card. The segments are the LIVE per-key bytes
                // from the same read the Storage Manager sub-page renders
                // --- the same five-key color vocabulary, in the same
                // order, scaled by their real share of the total.
                ToadStorageMiniBar(
                    entries = hubStorageState.entries,
                    measuring = hubStorageState.measuring,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                SettingsNavRow(
                    title = stringResource(R.string.settings_storage_entry),
                    supporting = stringResource(R.string.settings_storage_section_body),
                    onClick = { subPage = SettingsSubPage.STORAGE },
                    iconVector = Icons.Filled.Storage,
                    testTag = "settings_storage_entry",
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }

        if (aboutMatch) {
            val buildFeatureRes = remember {
                buildList {
                    if (BuildShape.faceIndex) add(R.string.settings_feature_people)
                    if (BuildShape.semanticIndex) add(R.string.settings_feature_semantic)
                    if (BuildConfig.FLAVOR == "unified") add(R.string.settings_feature_editor)
                }
            }
            val buildFeaturesLine = if (buildFeatureRes.isEmpty()) {
                stringResource(R.string.settings_features_core)
            } else {
                buildFeatureRes.map { stringResource(it) }.joinToString(" \u00b7 ")
            }
            SettingsGroupCard {
                SettingsNavRow(
                    title = stringResource(
                        R.string.about_version_line,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    supporting = buildFeaturesLine,
                    onClick = onOpenAbout,
                    iconVector = Icons.Filled.Info,
                    testTag = "settings_about_entry",
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }
        }

        if (query.isNotEmpty() && !anyMatch) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.settings_search_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_search_empty_body, settingsSearch),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = { settingsSearch = "" },
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .testTag("settings_search_empty_clear"),
                ) {
                    Text(stringResource(R.string.settings_search_empty_clear))
                }
            }
        }
        } // HUB

        if (page == SettingsSubPage.LAYOUT) {
            SettingsSectionHeader(stringResource(R.string.settings_layout_title))
            SettingsGroupCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
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
            FlowRow(
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
                                GroupBy.WEEK -> "settings_group_by_week"
                                GroupBy.MONTH -> "settings_group_by_month"
                            }
                        ),
                    )
                }
            }
            SettingsToggleRow(
                title = stringResource(R.string.settings_stories_on_home),
                supporting = stringResource(R.string.settings_stories_on_home_body),
                checked = appearance.storiesOnHome,
                onCheckedChange = { settingsViewModel.setStoriesOnHome(it) },
                iconRes = R.drawable.ic_setting_stories,
                testTag = "settings_toggle_stories_on_home",
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
        } // LAYOUT

        if (page == SettingsSubPage.THEME) {
            SettingsSectionHeader(stringResource(R.string.settings_theme_section))
            SettingsGroupCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIconBadge(
                    iconRes = R.drawable.ic_setting_theme,
                    modifier = Modifier.padding(end = 16.dp),
                )
                Text(
                    text = stringResource(R.string.settings_theme_mode),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.padding(start = 56.dp, top = 4.dp)) {
                Text(
                    text = stringResource(R.string.settings_theme_mode_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = appearance.themeMode == mode,
                            onClick = { settingsViewModel.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ThemeMode.entries.size,
                            ),
                            modifier = Modifier.testTag("settings_theme_" + mode.name),
                        ) {
                            Text(stringResource(mode.labelRes))
                        }
                    }
                }
            }
            }
            SettingsToggleRow(
                title = stringResource(R.string.settings_dynamic_color),
                supporting = stringResource(R.string.settings_dynamic_color_body),
                checked = useDynamicColor,
                onCheckedChange = { settingsViewModel.setUseDynamicColor(it) },
                iconVector = Icons.Filled.Settings,
                testTag = "settings_toggle_dynamic_color",
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
            SettingsSectionHeader(stringResource(R.string.settings_theme_palettes_title))
            SettingsGroupCard {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AllToadPalettes.forEach { palette ->
                        val selected = palette.id == toadPaletteId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp),
                                )
                                .clickable { settingsViewModel.setToadPaletteId(palette.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(palette.bgApp)
                                    .border(2.dp, palette.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(palette.primary),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = toadPaletteLabelFor(palette.id),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = toadPaletteDescriptionFor(palette.id),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                            }
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        } // THEME

        if (page == SettingsSubPage.VAULT) {
            SettingsSectionHeader(stringResource(R.string.settings_vault_title))
            val biometrics by vaultViewModel.biometrics.collectAsState()
            val biometricReady = biometrics as? VaultBiometricReadiness.Supported
            if (biometricReady != null) {
                SettingsGroupCard {
                    SettingsToggleRow(
                        title = stringResource(R.string.vault_biometric_section_title),
                        supporting = stringResource(R.string.vault_biometric_section_body),
                        checked = biometricReady.bindingEnrolled,
                        onCheckedChange = { enabled ->
                            if (enabled) vaultViewModel.enableBiometricUnlock()
                            else vaultViewModel.disableBiometricUnlock()
                        },
                        iconRes = R.drawable.ic_vault,
                        testTag = "settings_toggle_vault_biometric",
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            SettingsGroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.vault_autolock_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.vault_autolock_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ToadStatusPill(text = stringResource(R.string.vault_autolock_value))
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenVault,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_vault_open"),
                ) {
                    Text(stringResource(R.string.settings_vault_open))
                }
                OutlinedButton(
                    onClick = onOpenVault,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_vault_change_pin"),
                ) {
                    Text(stringResource(R.string.settings_vault_change_pin))
                }
            }
        } // VAULT

        if (page == SettingsSubPage.STORAGE) {
        val storageViewModel: StorageInsightsViewModel = viewModel()
        val storageState by storageViewModel.state.collectAsState()
        SettingsSectionHeader(stringResource(R.string.settings_storage_section))
        Text(
            text = stringResource(R.string.settings_storage_section_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        val entriesByKey = storageState.entries.associateBy { it.key }
        val catalogueBytes = entriesByKey[STORAGE_KEY_CATALOGUE]?.bytes ?: 0L
        val mlIndexBytes = entriesByKey[STORAGE_KEY_ML_INDEX]?.bytes ?: 0L
        val modelsBytes = entriesByKey[STORAGE_KEY_MODELS]?.bytes ?: 0L
        val vaultBytes = entriesByKey[STORAGE_KEY_VAULT]?.bytes ?: 0L
        val cacheBytes = entriesByKey[STORAGE_KEY_CACHE]?.bytes ?: 0L
        val totalBytes = catalogueBytes + mlIndexBytes + modelsBytes +
            vaultBytes + cacheBytes
        SettingsGroupCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_storage_private_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (storageState.measuring) {
                        stringResource(R.string.settings_storage_measuring)
                    } else {
                        DataSizeFormats.formatBytes(totalBytes) + " total"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            // r77.4: the field-report Storage Manager renders the breakdown
            // as a single continuous rounded bar (an outlined track, the
            // segments flush against each other, no inter-segment gaps),
            // matching the screenshot. The outer 50% clip gives the pill
            // ends; each segment is a plain fillMaxHeight Box so the colors
            // butt up against each other and read as one stack, not five.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp)
                        .clip(RoundedCornerShape(50)),
                ) {
                    if (totalBytes > 0L) {
                        listOf(
                            catalogueBytes to Color(0xFF38BDF8),
                            modelsBytes to MaterialTheme.colorScheme.primary,
                            mlIndexBytes to Color(0xFFA78BFA),
                            vaultBytes to Color(0xFF34D399),
                            cacheBytes to Color(0xFFFBBF24),
                        ).forEach { (bytes, color) ->
                            if (bytes > 0L) {
                                Box(
                                    modifier = Modifier
                                        .weight(bytes.toFloat() / totalBytes)
                                        .fillMaxHeight()
                                        .background(color),
                                )
                            }
                        }
                    } else if (storageState.measuring) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            @Composable
            fun LegendRow(label: String, bytes: Long, color: Color) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (storageState.measuring) {
                            stringResource(R.string.settings_storage_measuring)
                        } else {
                            DataSizeFormats.formatBytes(bytes)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            LegendRow(
                stringResource(R.string.settings_storage_catalogue),
                catalogueBytes,
                Color(0xFF38BDF8),
            )
            LegendRow(
                stringResource(R.string.settings_storage_models),
                modelsBytes,
                MaterialTheme.colorScheme.primary,
            )
            LegendRow(
                stringResource(R.string.settings_storage_ml_index),
                mlIndexBytes,
                Color(0xFFA78BFA),
            )
            LegendRow(
                stringResource(R.string.settings_storage_vault),
                vaultBytes,
                Color(0xFF34D399),
            )
            LegendRow(
                stringResource(R.string.settings_storage_cache),
                cacheBytes,
                Color(0xFFFBBF24),
            )
            Spacer(Modifier.height(4.dp))
            // r77.4: the field-report Storage Manager shows the two
            // disposable actions side-by-side (screenshot 2): Clear cache
            // (primary pill) and Reset ML index (outlined secondary, only on
            // the face-index flavors where there IS an index to reset). Both
            // disable while a measurement or the index reset is in flight so
            // the totals cannot shift under a running action.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = storageViewModel::clearCache,
                    enabled = !storageState.measuring,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("settings_storage_clear_cache"),
                ) {
                    Text(stringResource(R.string.settings_storage_action_clear_cache))
                }
                if (BuildShape.faceIndex) {
                    OutlinedButton(
                        onClick = storageViewModel::resetMlIndex,
                        enabled = !storageState.measuring &&
                            !storageState.resettingIndex,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_storage_reset_ml_index"),
                    ) {
                        Text(stringResource(R.string.settings_storage_action_reset_ml_index))
                    }
                }
            }
        }
        SettingsGroupCard {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val trashReady = trashState as? TrashUiState.Ready
                SettingsNavRow(
                    title = stringResource(R.string.settings_recycle_bin_title),
                    supporting = if (trashReady != null) {
                        pluralStringResource(
                            R.plurals.trash_header_items,
                            trashReady.items.size,
                            trashReady.items.size,
                        ) + "  \u00b7  " + DataSizeFormats.formatBytes(trashReady.totalBytes)
                    } else null,
                    onClick = onOpenTrash,
                    iconRes = R.drawable.ic_trash,
                    testTag = "settings_recycle_bin_entry",
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_recycle_bin_pre_r_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                SettingsNavRow(
                    title = stringResource(R.string.settings_vault_title),
                    supporting = when (val vault = vaultState) {
                        VaultUiState.NotConfigured ->
                            stringResource(R.string.vault_settings_not_configured)
                        VaultUiState.Locked ->
                            stringResource(R.string.vault_settings_locked)
                        is VaultUiState.Unlocked -> pluralStringResource(
                            R.plurals.vault_header_items,
                            vault.entries.size,
                            vault.entries.size,
                        ) + "  \u00b7  " + DataSizeFormats.formatBytes(
                            vault.entries.sumOf { it.sizeBytes },
                        )
                        else -> null
                    },
                    onClick = onOpenVault,
                    iconRes = R.drawable.ic_vault,
                    testTag = "settings_vault_entry",
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_vault_pre_q_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
            }
        }

        // Privacy centre.
        val mediaReadGranted = rememberMediaReadGranted()
        val peopleOn by settingsViewModel.peopleAlbumsEnabled.collectAsState()
        val magicOn by settingsViewModel.magicSearchEnabled.collectAsState()
        val thingsOn by settingsViewModel.thingsAlbumsEnabled.collectAsState()
        val aiEnabledCount = listOf(peopleOn, magicOn, thingsOn).count { it }
        SettingsSectionHeader(stringResource(R.string.settings_privacy_section))
        Text(
            text = stringResource(R.string.settings_privacy_section_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        SettingsGroupCard {
            PrivacyStatusRow(
                label = stringResource(R.string.settings_privacy_media),
                status = stringResource(
                    if (mediaReadGranted) R.string.settings_privacy_media_status_granted
                    else R.string.settings_privacy_media_status_denied
                ),
                ok = mediaReadGranted,
            )
            PrivacyStatusRow(
                label = stringResource(R.string.settings_privacy_location),
                status = stringResource(
                    when (locationState) {
                        MediaLocationState.GRANTED -> R.string.settings_privacy_location_granted
                        MediaLocationState.DENIED -> R.string.settings_privacy_location_denied
                        MediaLocationState.NOT_REQUIRED -> R.string.settings_privacy_location_na
                        MediaLocationState.UNKNOWN -> R.string.settings_privacy_location_unknown
                    }
                ),
                ok = locationState == MediaLocationState.GRANTED ||
                    locationState == MediaLocationState.NOT_REQUIRED,
            )
            PrivacyStatusRow(
                label = stringResource(R.string.settings_privacy_vault),
                status = stringResource(
                    when (vaultState) {
                        VaultUiState.Locked -> R.string.settings_privacy_vault_configured
                        is VaultUiState.Unlocked -> R.string.settings_privacy_vault_unlocked
                        else -> R.string.settings_privacy_vault_none
                    }
                ),
                ok = vaultState is VaultUiState.Locked,
            )
            PrivacyStatusRow(
                label = stringResource(R.string.settings_privacy_ai),
                status = if (aiEnabledCount == 0) {
                    stringResource(R.string.settings_privacy_ai_off)
                } else {
                    stringResource(R.string.settings_privacy_ai_on, aiEnabledCount)
                },
                ok = aiEnabledCount == 0,
            )
        }

        } // STORAGE

        if (page == SettingsSubPage.MEDIA_ACCESS) {
            SettingsSectionHeader(stringResource(R.string.settings_section_access))
            SettingsGroupCard {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_use_photo_picker),
                    supporting = stringResource(R.string.settings_use_photo_picker_body),
                    checked = usePhotoPicker,
                    onCheckedChange = { settingsViewModel.setUsePhotoPicker(it) },
                    iconRes = R.drawable.ic_setting_picker,
                    testTag = "settings_toggle_photo_picker",
                    modifier = Modifier.padding(top = 12.dp),
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
                                .padding(top = 12.dp, start = 56.dp)
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
                                modifier = Modifier.padding(top = 12.dp, start = 56.dp),
                            )
                            TextButton(
                                onClick = onBrowsePicked,
                                modifier = Modifier.padding(start = 40.dp),
                            ) {
                                Text(stringResource(R.string.settings_browse_picked))
                            }
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.settings_photo_picker_pre33_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, start = 56.dp, bottom = 8.dp),
                        )
                    }
                }
            }

            SettingsSectionHeader(stringResource(R.string.settings_location_title))
            SettingsGroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsIconBadge(
                        iconVector = Icons.Filled.LocationOn,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_location_body),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Text(
                                text = when (locationState) {
                                    MediaLocationState.GRANTED ->
                                        stringResource(R.string.settings_location_state_granted)
                                    MediaLocationState.DENIED ->
                                        stringResource(R.string.settings_location_state_denied)
                                    else -> stringResource(R.string.settings_location_state_unknown)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.settings_location_pre_q_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    (locationState == MediaLocationState.UNKNOWN ||
                        locationState == MediaLocationState.DENIED)
                ) {
                    Button(
                        onClick = {
                            locationPermissionLauncher.launch(
                                Manifest.permission.ACCESS_MEDIA_LOCATION,
                            )
                        },
                        modifier = Modifier
                            .padding(bottom = 12.dp, start = 56.dp)
                            .testTag("settings_location_request"),
                    ) {
                        Text(stringResource(R.string.settings_location_request))
                    }
                }
            }

            val mediaReadGranted = rememberMediaReadGranted()
            SettingsSectionHeader(stringResource(R.string.settings_media_posture_title))
            SettingsGroupCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsIconBadge(
                        iconVector = Icons.Filled.FolderOpen,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                    Column {
                        Text(
                            text = stringResource(
                                if (mediaReadGranted) R.string.settings_media_posture_granted
                                else R.string.settings_media_posture_denied
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_media_posture_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } // MEDIA_ACCESS

        if (page == SettingsSubPage.AI_ENGINE && BuildShape.withEngine) {

            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.settings_ai_section),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .testTag("settings_ai_section")
                    .semantics { heading() },
            )
            Text(
                text = stringResource(R.string.settings_ai_section_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Consent toggles (re-used SettingsToggleRow instead of the
            // inline Row + Switch duplicate).
            if (BuildShape.faceIndex) {
                val peopleAlbumsEnabled by settingsViewModel.peopleAlbumsEnabled.collectAsState()
                SettingsToggleRow(
                    title = stringResource(R.string.settings_people_albums),
                    supporting = stringResource(R.string.settings_people_albums_body),
                    checked = peopleAlbumsEnabled,
                    onCheckedChange = { settingsViewModel.setPeopleAlbumsEnabled(it) },
                    iconVector = Icons.Filled.Memory,
                    testTag = "settings_toggle_people_albums",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (BuildShape.semanticIndex) {
                val magicSearchEnabled by settingsViewModel.magicSearchEnabled.collectAsState()
                SettingsToggleRow(
                    title = stringResource(R.string.settings_magic_search),
                    supporting = stringResource(R.string.settings_magic_search_body),
                    checked = magicSearchEnabled,
                    onCheckedChange = { settingsViewModel.setMagicSearchEnabled(it) },
                    iconVector = Icons.Filled.Memory,
                    testTag = "settings_toggle_magic_search",
                    modifier = Modifier.padding(top = 8.dp),
                )
                val thingsAlbumsEnabled by settingsViewModel.thingsAlbumsEnabled.collectAsState()
                SettingsToggleRow(
                    title = stringResource(R.string.settings_things_albums),
                    supporting = stringResource(R.string.settings_things_albums_body),
                    checked = thingsAlbumsEnabled,
                    onCheckedChange = { settingsViewModel.setThingsAlbumsEnabled(it) },
                    iconVector = Icons.Filled.Memory,
                    testTag = "settings_toggle_things_albums",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Text(
                text = stringResource(R.string.settings_models_group),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .semantics { heading() },
            )
            Text(
                text = stringResource(R.string.settings_models_group_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            val aiViewModel: AiEngineViewModel = viewModel()
            val aiState by aiViewModel.state.collectAsState()
            LaunchedEffect(Unit) { aiViewModel.refresh() }

            if (aiState.busy) {
                val progress = aiState.downloadProgress
                if (progress != null) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        LinearProgressIndicator(
                            progress = {
                                if (progress.totalBytes <= 0L) 0f
                                else (progress.bytesSoFar.toFloat() / progress.totalBytes.toFloat())
                                    .coerceIn(0f, 1f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_ai_download_progress"),
                        )
                        Text(
                            text = if (progress.verifying) {
                                stringResource(
                                    R.string.settings_ai_progress_verifying,
                                    progress.fileName,
                                )
                            } else {
                                stringResource(
                                    R.string.settings_ai_progress_downloading,
                                    progress.fileName,
                                    DataSizeFormats.formatBytes(progress.bytesSoFar),
                                    DataSizeFormats.formatBytes(progress.totalBytes),
                                    progress.fileIndex,
                                    progress.fileCount,
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("settings_ai_download_progress_line"),
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.settings_ai_busy),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    if (aiState.engineLibraryPresent) R.string.settings_ai_library_present
                    else R.string.settings_ai_library_missing
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            aiState.engineInfoLine?.let { info ->
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .testTag("settings_ai_engine_info"),
                )
            }

            if (BuildShape.faceIndex) {
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
                            if (asset.installed) R.string.settings_ai_asset_installed
                            else R.string.settings_ai_asset_missing
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                var faceDeleteConfirm by remember { mutableStateOf(false) }
                Button(
                    onClick = aiViewModel::downloadFaceModels,
                    enabled = !aiState.busy,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .testTag("settings_ai_download"),
                ) {
                    Text(stringResource(R.string.settings_ai_download))
                }
                OutlinedButton(
                    onClick = { faceDeleteConfirm = true },
                    enabled = !aiState.busy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("settings_ai_delete"),
                ) {
                    Text(stringResource(R.string.settings_ai_delete))
                }
                if (faceDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { faceDeleteConfirm = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    faceDeleteConfirm = false
                                    aiViewModel.deleteFaceModels()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text(stringResource(R.string.file_ops_action_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { faceDeleteConfirm = false }) {
                                Text(stringResource(R.string.file_ops_action_cancel))
                            }
                        },
                        title = { Text(stringResource(R.string.settings_ai_delete_confirm_title)) },
                        text = { Text(stringResource(R.string.settings_ai_delete_confirm_body)) },
                    )
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
                            R.plurals.settings_ai_selftest_hit,
                            result.faces,
                            result.scanned,
                            result.faces,
                            result.elapsedMs,
                            result.firstFaceFile,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    is AiDogfoodState.SelfTestResult.Zero -> Text(
                        text = stringResource(
                            R.string.settings_ai_selftest_no_faces,
                            result.scanned,
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
            }

            if (BuildShape.semanticIndex) {
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
                            if (asset.installed) R.string.settings_ai_asset_installed
                            else R.string.settings_ai_asset_missing
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                var semanticDeleteConfirm by remember { mutableStateOf(false) }
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
                OutlinedButton(
                    onClick = { semanticDeleteConfirm = true },
                    enabled = !aiState.busy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("settings_ai_semantic_delete"),
                ) {
                    Text(stringResource(R.string.settings_ai_semantic_delete))
                }
                if (semanticDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { semanticDeleteConfirm = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    semanticDeleteConfirm = false
                                    aiViewModel.deleteSemanticModels()
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) { Text(stringResource(R.string.file_ops_action_delete)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { semanticDeleteConfirm = false }) {
                                Text(stringResource(R.string.file_ops_action_cancel))
                            }
                        },
                        title = { Text(stringResource(R.string.settings_ai_delete_confirm_title)) },
                        text = { Text(stringResource(R.string.settings_ai_delete_confirm_body)) },
                    )
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
                    else -> Unit
                }
            }

            if (BuildShape.faceIndex) {
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
                    enabled = aiState.engineLibraryPresent &&
                        aiState.faceCapability == ModelProvisioner.CapabilityState.Ready &&
                        queueState !is FaceIndexWorkState.Enqueued &&
                        queueState !is FaceIndexWorkState.Running,
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
                OutlinedButton(
                    onClick = aiViewModel::resetFaceIndex,
                    enabled = !aiState.busy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
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
                            text = if (clusters >= 0) {
                                stringResource(
                                    R.string.settings_ai_queue_done,
                                    queue.output.getInt(KEY_OUT_INDEXED, 0),
                                    queue.output.getInt(KEY_OUT_FACES, 0),
                                    queue.output.getInt(KEY_OUT_SKIPPED_UP_TO_DATE, 0),
                                    clusters,
                                    queue.output.getLong(KEY_OUT_TOTAL_FACES, 0L),
                                    queue.output.getInt(KEY_OUT_SKIPPED_UNREADABLE, 0),
                                )
                            } else {
                                stringResource(
                                    R.string.settings_ai_queue_done_no_cluster,
                                    queue.output.getInt(KEY_OUT_INDEXED, 0),
                                    queue.output.getInt(KEY_OUT_FACES, 0),
                                    queue.output.getInt(KEY_OUT_SKIPPED_UP_TO_DATE, 0),
                                    queue.output.getLong(KEY_OUT_TOTAL_FACES, 0L),
                                    queue.output.getInt(KEY_OUT_SKIPPED_UNREADABLE, 0),
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    is FaceIndexWorkState.Failed -> {
                        Text(
                            text = stringResource(R.string.settings_ai_queue_failed, queue.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        TextButton(
                            onClick = aiViewModel::startBackgroundIndex,
                            enabled = aiState.engineLibraryPresent &&
                                aiState.faceCapability == ModelProvisioner.CapabilityState.Ready,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("settings_ai_queue_retry"),
                        ) {
                            Text(stringResource(R.string.state_action_retry))
                        }
                    }
                    FaceIndexWorkState.Cancelled -> Text(
                        text = stringResource(R.string.settings_ai_queue_cancelled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    FaceIndexWorkState.Idle -> Unit
                }
            }

            if (BuildShape.semanticIndex) {
                Text(
                    text = stringResource(R.string.settings_ai_semantic_queue_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                val clipQueueState by aiViewModel.clipQueueState.collectAsState()
                Button(
                    onClick = aiViewModel::startSemanticIndex,
                    enabled = aiState.engineLibraryPresent &&
                        aiState.semanticCapability == ModelProvisioner.CapabilityState.Ready &&
                        clipQueueState !is ClipIndexWorkState.Enqueued &&
                        clipQueueState !is ClipIndexWorkState.Running,
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
                OutlinedButton(
                    onClick = aiViewModel::resetSemanticIndex,
                    enabled = !aiState.busy,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
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
                        val fillOutcome = clip.output.getString(KEY_CLIP_OUT_FILL_OUTCOME)
                        Text(
                            text = stringResource(
                                R.string.settings_ai_semantic_queue_done,
                                clip.output.getInt(KEY_CLIP_OUT_INDEXED, 0),
                                clip.output.getInt(KEY_CLIP_OUT_SKIPPED_UP_TO_DATE, 0),
                                clip.output.getLong(KEY_CLIP_OUT_TOTAL_CLIPS, 0L),
                                clip.output.getInt(KEY_CLIP_OUT_SKIPPED_UNREADABLE, 0),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        if (fillOutcome != null) {
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
                    is ClipIndexWorkState.Failed -> {
                        Text(
                            text = stringResource(R.string.settings_ai_semantic_queue_failed, clip.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        TextButton(
                            onClick = aiViewModel::startSemanticIndex,
                            enabled = aiState.engineLibraryPresent &&
                                aiState.semanticCapability == ModelProvisioner.CapabilityState.Ready,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .testTag("settings_ai_semantic_queue_retry"),
                        ) {
                            Text(stringResource(R.string.state_action_retry))
                        }
                    }
                    ClipIndexWorkState.Cancelled -> Text(
                        text = stringResource(R.string.settings_ai_semantic_queue_cancelled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    ClipIndexWorkState.Idle -> Unit
                }
            }
        } // AI_ENGINE (engine builds)

        if (page == SettingsSubPage.AI_ENGINE && !BuildShape.withEngine) {
            SettingsSectionHeader(stringResource(R.string.settings_ai_engine_entry))
            SettingsGroupCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_ai_engine_absent),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_ai_engine_absent_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } // AI_ENGINE (no engine)

            } // per-page scrollable column
        } // AnimatedContent
        } // pinned page column
}

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
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
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
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
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

private val GroupBy.labelRes: Int
    get() = when (this) {
        GroupBy.NONE -> R.string.settings_group_by_none
        GroupBy.DAY -> R.string.settings_group_by_day
        GroupBy.WEEK -> R.string.settings_group_by_week
        GroupBy.MONTH -> R.string.settings_group_by_month
    }

enum class SettingsSubPage {
    HUB,
    THEME,
    LAYOUT,
    AI_ENGINE,
    VAULT,
    STORAGE,
    MEDIA_ACCESS,
}

/**
 * r77.10: the hub section header's TWO-EDGE shape --- the prototype's
 * `px-1 flex items-center justify-between` eyebrow row: the section label
 * on the leading edge as an `11sp` ExtraBold primary line with the
 * `tracking-wider` letterspacing (the settings markup's
 * `text-[11px] font-extrabold uppercase tracking-wider` --- the Title Case
 * label resource is the settled app convention, per the r76.1 stories
 * eyebrow), and the optional trailing hint on the far edge (the markup's
 * `text-[10px]` right label: "Look & grid", "Local NPU", "Zero tracking",
 * or the storage section's live bold "N MB" total).
 *
 * The heading semantics stay: the eyebrow remains the TalkBack anchor for
 * its section. Sub-pages keep calling with no hint (the prototype's
 * sub-pages carry no eyebrow hints) and the old `titleMedium` weight is
 * retired everywhere --- one eyebrow vocabulary for every section header.
 *
 * `hintEmphasized` renders the storage variant (the markup's
 * `text-[10px] font-bold` in the muted tone instead of the dim one).
 */
@Composable
private fun SettingsSectionHeader(
    title: String,
    hint: String? = null,
    hintEmphasized: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontFamily = ToadDisplayFont,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp,
                letterSpacing = 0.6.sp,
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (hint != null) {
            Text(
                text = hint,
                style = TextStyle(
                    fontFamily = ToadBodyFont,
                    fontWeight = if (hintEmphasized) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 10.sp,
                ),
                color = if (hintEmphasized) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    ToadTheme.palette.textDim
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * r77.10: the hub Storage section's compact segmented preview bar --- the
 * prototype's `h-2.5 rounded-full overflow-hidden flex gap-0.5 p-0.5` bar
 * on `bg-surface-highest` that sits directly above the Storage & Cache
 * Manager row. The segments are the LIVE per-key bytes of the same
 * [StorageInsightsViewModel] read the Storage Manager sub-page renders,
 * in the sub-page's five-key color vocabulary and order (catalogue sky,
 * models primary, ML index violet, vault emerald, cache amber), each
 * scaled by its real share of the measured total. The end segments carry
 * the rounded ends (the markup's `rounded-l-full` / `rounded-r-full`);
 * interior segments stay square so the 2dp gaps read as separations.
 *
 * While a measurement is in flight (or nothing was measured yet) the bar
 * renders its track with the sub-page's measuring placeholder --- the
 * honest "not known yet" state, never a fake fill.
 */
@Composable
private fun ToadStorageMiniBar(
    entries: List<StorageEntry>,
    measuring: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = ToadTheme.palette
    val segmentColors = listOf(
        STORAGE_KEY_CATALOGUE to Color(0xFF38BDF8),
        STORAGE_KEY_MODELS to MaterialTheme.colorScheme.primary,
        STORAGE_KEY_ML_INDEX to Color(0xFFA78BFA),
        STORAGE_KEY_VAULT to Color(0xFF34D399),
        STORAGE_KEY_CACHE to Color(0xFFFBBF24),
    )
    val segments = segmentColors.mapNotNull { (key, color) ->
        entries.firstOrNull { it.key == key }?.takeIf { it.bytes > 0L }?.let { it.bytes to color }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(palette.bgSurfaceHighest)
            .padding(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            if (segments.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            } else {
                val totalBytes = segments.sumOf { it.first }
                segments.forEachIndexed { index, (bytes, color) ->
                    val shape = when {
                        segments.size == 1 -> RoundedCornerShape(50)
                        index == 0 -> RoundedCornerShape(
                            topStartPercent = 50,
                            bottomStartPercent = 50,
                        )
                        index == segments.size - 1 -> RoundedCornerShape(
                            topEndPercent = 50,
                            bottomEndPercent = 50,
                        )
                        else -> RectangleShape
                    }
                    // The CELL owns the weight and the trailing 2dp gap
                    // (the markup's gap-0.5); the INNER box owns the
                    // shape + fill --- a background painted before a
                    // padding would bleed into the gap, so the fill must
                    // be inset by the cell first.
                    Box(
                        modifier = Modifier
                            .weight(bytes.toFloat() / totalBytes)
                            .fillMaxHeight()
                            .padding(end = if (index < segments.size - 1) 2.dp else 0.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(shape)
                                .background(color),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(ToadShapes.large))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun PrivacyStatusRow(
    label: String,
    status: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

@Composable
private fun SettingsIconBadge(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    SettingsIconBadgeContainer(modifier = modifier) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SettingsIconBadge(
    iconVector: ImageVector,
    modifier: Modifier = Modifier,
) {
    SettingsIconBadgeContainer(modifier = modifier) {
        Icon(
            imageVector = iconVector,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SettingsIconBadgeContainer(
    modifier: Modifier,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(ToadShapes.medium))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun SettingsNavRow(
    title: String,
    supporting: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    @DrawableRes iconRes: Int? = null,
    iconVector: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            SettingsIconBadge(iconRes = iconRes, modifier = Modifier.padding(end = 16.dp))
        } else if (iconVector != null) {
            SettingsIconBadge(iconVector = iconVector, modifier = Modifier.padding(end = 16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    supporting: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    @DrawableRes iconRes: Int? = null,
    iconVector: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconRes != null) {
            SettingsIconBadge(iconRes = iconRes, modifier = Modifier.padding(end = 16.dp))
        } else if (iconVector != null) {
            SettingsIconBadge(iconVector = iconVector, modifier = Modifier.padding(end = 16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

@Composable
private fun ToadHeroStatusCard(
    paletteLabel: String,
    appearance: Appearance,
    onAiStatus: () -> Unit,
) {
    val palette = ToadTheme.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(palette.primaryContainer, palette.bgSurfaceHigh),
                    start = Offset.Zero,
                    end = Offset(120f, 120f),
                ),
            )
            .border(1.dp, palette.borderStrong, RoundedCornerShape(24.dp))
            .padding(16.dp),
    ) {
        ToadStatusPill(
            text = stringResource(R.string.settings_hero_offline_pill),
            background = palette.bgSurfaceHighest,
            textColor = palette.onPrimaryContainer,
        )
        Text(
            text = stringResource(R.string.settings_hero_title),
            style = TextStyle(
                fontFamily = ToadDisplayFont,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
            ),
            color = palette.textMain,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(
                R.string.settings_hero_body,
                paletteLabel,
                appearance.columns,
            ),
            style = TextStyle(
                fontFamily = ToadBodyFont,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
            ),
            color = palette.textMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Button(
            onClick = onAiStatus,
            modifier = Modifier
                .padding(top = 12.dp)
                .testTag("settings_hero_ai_status"),
        ) {
            Text(stringResource(R.string.settings_hero_cta))
        }
    }
}

@Composable
private fun mediaAccessSupporting(usePhotoPicker: Boolean): String =
    stringResource(
        if (usePhotoPicker) R.string.settings_media_access_picker_on
        else R.string.settings_media_access_picker_off
    )

fun toadPaletteLabelFor(id: ToadThemeId): String = when (id) {
    ToadThemeId.TOAD -> "Toad Green"
    ToadThemeId.AMBER -> "Dynamic Amber"
    ToadThemeId.OCEAN -> "Ocean Slate"
    ToadThemeId.OLED -> "OLED Pure Black"
    ToadThemeId.LIGHT -> "Botanical Light"
}

private fun toadPaletteDescriptionFor(id: ToadThemeId): String = when (id) {
    ToadThemeId.TOAD -> "Signature botanical dark"
    ToadThemeId.AMBER -> "Warm Material You gold"
    ToadThemeId.OCEAN -> "Deep twilight blue"
    ToadThemeId.OLED -> "Zero-nit battery saver"
    ToadThemeId.LIGHT -> "High-clarity daylight"
}

@Composable
private fun themeModeSupporting(
    mode: ThemeMode,
    viewModel: SettingsViewModel,
): String {
    val id by viewModel.toadPaletteId.collectAsState()
    return stringResource(mode.labelRes) + " • " + toadPaletteLabelFor(id)
}

@Composable
private fun layoutSupporting(appearance: Appearance): String {
    val layoutLabel = stringResource(
        if (appearance.layout == LayoutStyle.GRID) R.string.settings_layout_grid
        else R.string.settings_layout_collage,
    )
    return layoutLabel + " • " + appearance.columns + " • " +
        stringResource(appearance.groupBy.labelRes)
}

@Composable
private fun aiEngineSupporting(): String =
    stringResource(
        if (BuildShape.withEngine) R.string.settings_ai_engine_ready
        else R.string.settings_ai_engine_absent,
    )

/**
 * Shared media-read grant check (was duplicated in the privacy centre and
 * the media-posture card).
 */
@Composable
private fun rememberMediaReadGranted(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        fun granted(perm: String) =
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 33) {
            granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                granted(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
