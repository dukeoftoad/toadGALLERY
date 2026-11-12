package com.toadgallery.gallery.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.toadgallery.gallery.BuildConfig
import com.toadgallery.gallery.R
import com.toadgallery.gallery.buildshape.BuildShape
import com.toadgallery.gallery.ui.LocalSettingsHubRequest
import com.toadgallery.gallery.ui.components.ToadBodyFont
import com.toadgallery.gallery.ui.components.ToadDisplayFont
import com.toadgallery.gallery.ui.components.ToadGlassSurface
import com.toadgallery.gallery.ui.components.ToadMotionSpec
import com.toadgallery.gallery.ui.components.ToadStatusPill
import com.toadgallery.gallery.ui.components.ToadSubPageTopBar
import com.toadgallery.gallery.ui.theme.AllToadPalettes
import com.toadgallery.gallery.ui.theme.ToadShapes
import com.toadgallery.gallery.ui.theme.ToadTheme
import com.toadgallery.gallery.ui.theme.ToadThemeId
import com.toadgallery.gallery.data.diag.DiagLog
import com.toadgallery.gallery.data.DataSizeFormats
import com.toadgallery.gallery.data.media.location.MediaLocationState
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
import com.toadgallery.gallery.ui.picker.PickedPhotosViewModel
import com.toadgallery.gallery.ui.trash.TrashUiState
import com.toadgallery.gallery.ui.trash.TrashViewModel
import com.toadgallery.gallery.ui.vault.VaultUiState
import com.toadgallery.gallery.ui.vault.VaultViewModel

/**
 * The Settings tab (r64, the re-craft round: a modern carded page).
 *
 * PAGE SHAPE (r64): the title and the segmented tabs (General | AI |
 * System) are PINNED above a scrollable body, so the tab switcher stays
 * reachable however far the content scrolls; the scroll position resets
 * on every switch so a tab never lands mid-page. Every section is a
 * rounded group card ([SettingsGroupCard]) of icon-badged rows ---
 * [SettingsNavRow] for entries, [SettingsToggleRow] for switches --- the
 * Android-Settings card language, hand-drawn on the house tokens (no
 * new dependencies; the badge glyphs live in res/drawable, the rest
 * ride the core icon set).
 *
 * ## GENERAL: appearance, theme, media access, location, about
 *  - Appearance: the layout cards (grid/collage), the photos-per-row
 *    stepper, the group-by chips, the Stories toggle (the shell-scoped
 *    [SettingsViewModel] write-through --- a change lands live on the
 *    Photos timeline in the same session).
 *  - Theme (r64's new hand-written feature): a REAL light/dark/system
 *    preference --- [ThemeMode], persisted under `"theme_mode"`, resolved
 *    by `ToadGalleryApp` into the theme call's darkTheme --- plus the
 *    Material You dynamic-color toggle (r62) beside it.
 *  - Media access: the system-Photo-Picker toggle (default OFF) with the
 *    pick entry, the live picked count and the browse row (API 33+; a
 *    graceful pre-33 fallback copy).
 *  - Location data: the ACCESS_MEDIA_LOCATION consent display + request
 *    (Phase 4; below API 29 the permission does not exist).
 *  - About: the one honest build row --- version + what THIS APK carries
 *    ([BuildShape] + the unified flavor's editor), opening the About
 *    sub-screen. r64: this replaces the r55 "Installed components"
 *    family probe, which read "Not installed" for capabilities the
 *    unified build embeds all along, and the F-1 "Development progress"
 *    entry is GONE (the product UI no longer carries the project's dev
 *    dashboard).
 *
 * ## AI (engine builds only, r46 gate): the consents + the model
 * provisioning cards + the two index queues --- unchanged this round.
 *
 * ## SYSTEM: storage, privacy, dev tools
 *  - Storage: the r62 dashboard, with the recycle-bin and hidden-vault
 *    ENTRIES folded in as the card's first rows (their old standalone
 *    sections + their duplicate measurement rows merge away; a locked
 *    vault still leaks no count or size, by design).
 *  - Privacy center: the r62 audit-style status page (read-only).
 *  - Developer diagnostics: DEBUG-BUILDS ONLY ([BuildConfig.DEBUG]) ---
 *    the signed release surface never composes it.
 */
@OptIn(ExperimentalLayoutApi::class) // r27: FlowRow for the four group-by chips
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
    // r76: the Theme & Colors palette selection (the five prototype
    // palettes; the shell theme call reads the same flow).
    val toadPaletteId by settingsViewModel.toadPaletteId.collectAsState()

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

    // r76: the prototype's Settings IA --- a HUB with five grouped
    // sections + a live search field, and SIX drill-down sub-pages that
    // slide in horizontally (AnimatedContent, 220ms) and return via the
    // sub-page top bar's back button (or the nav bar's Settings tab ---
    // the shell's hub-return signal). This replaces the r62 segmented
    // tabs: everything the tabs carried is rehoused below, every test
    // tag intact.
    var subPage by rememberSaveable { mutableStateOf(SettingsSubPage.HUB) }
    var settingsSearch by rememberSaveable { mutableStateOf("") }
    val settingsScroll = rememberScrollState()
    LaunchedEffect(subPage) { settingsScroll.scrollTo(0) }

    // The hub-return signal (the nav bar's Settings-pop rule): while ANY
    // sub-page is composed the signal is armed, so tapping the Settings
    // tab pops to the hub instead of switching tabs.
    val hubRequest = LocalSettingsHubRequest.current
    LaunchedEffect(subPage) {
        if (subPage != SettingsSubPage.HUB) hubRequest.requestHubReturn()
    }
    // The shell consumes the one-shot flag on the tab tap; this side
    // lands the pop. (Both sides are idempotent: an unconsumed flag on
    // the HUB is harmless.)
    LaunchedEffect(hubRequest) {
        snapshotFlow { hubRequest.consume() }.collect { popped -> if (popped) subPage = SettingsSubPage.HUB }
    }

    // The live search filter: a lowercase contains over each section's
    // title + row titles (the searchable inventory below --- one entry
    // per hub row; the sub-pages are NOT searched, matching the
    // prototype's hub-only search).
    val query = settingsSearch.trim().lowercase()
    val match: (String) -> Boolean = { label ->
        query.isEmpty() || label.lowercase().contains(query)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---- The sticky frosted header (r76): title + version pill ----
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
                    // r40 a11y: the screen title is the heading anchor for
                    // the whole settings page.
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                ToadStatusPill(text = "v" + BuildConfig.VERSION_NAME)
            }
        }

        // ---- r76: the sticky sub-page top bar (rendered only while a
        //      sub-page is open; the per-page titles/status pills live
        //      here so the bar stays pinned above the scroll column). ----
        if (subPage != SettingsSubPage.HUB) {
            ToadSubPageTopBar(
                title = when (subPage) {
                    SettingsSubPage.THEME -> "Theme & Colors"
                    SettingsSubPage.LAYOUT -> "Timeline Layout"
                    SettingsSubPage.AI_ENGINE -> "AI Engine"
                    SettingsSubPage.VAULT -> "Hidden Vault"
                    SettingsSubPage.STORAGE -> "Storage & Cache"
                    SettingsSubPage.HUB -> ""
                },
                statusPill = when (subPage) {
                    SettingsSubPage.LAYOUT -> appearance.columns.toString() + " cols"
                    SettingsSubPage.AI_ENGINE -> "On-device"
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

        // ---- r76: the hub/sub-page router. The HUB composes the search
        // field + the five sections; each sub-page composes the rehoused
        // wired rows. Horizontal slide both directions (the prototype's
        // slideInRight/slideOutLeft).
        AnimatedContent(
            targetState = subPage,
            transitionSpec = {
                val forward = targetState != SettingsSubPage.HUB
                val slide = if (forward) {
                    (slideInHorizontally(ToadMotionSpec.SUBPAGE_MS) { it } +
                        fadeIn(tween(ToadMotionSpec.SUBPAGE_MS))) togetherWith
                        (slideOutHorizontally(ToadMotionSpec.SUBPAGE_MS) { -it / 3 } +
                            fadeOut(tween(ToadMotionSpec.SUBPAGE_MS)))
                } else {
                    (slideInHorizontally(ToadMotionSpec.SUBPAGE_MS) { -it / 3 } +
                        fadeIn(tween(ToadMotionSpec.SUBPAGE_MS))) togetherWith
                        (slideOutHorizontally(ToadMotionSpec.SUBPAGE_MS) { it } +
                            fadeOut(tween(ToadMotionSpec.SUBPAGE_MS)))
                }
                slide.apply { targetContentZIndex = if (forward) 2f else 0f }
            },
            label = "settingsSubPage",
        ) { page ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(settingsScroll)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {

        if (page == SettingsSubPage.HUB) {

        // ---- r76: the live settings search (the sticky header's field;
        //      hidden while a sub-page is open) ----
        OutlinedTextField(
            value = settingsSearch,
            onValueChange = { settingsSearch = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("settings_search_field"),
            singleLine = true,
            placeholder = {
                Text(stringResource(R.string.settings_search_hint))
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                )
            },
            trailingIcon = {
                if (settingsSearch.isNotEmpty()) {
                    IconButton(
                        onClick = { settingsSearch = "" },
                        modifier = Modifier.testTag("settings_search_clear"),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.cd_search_clear),
                        )
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        // ---- r76: the HERO status card (hidden while searching) ----
        if (query.isEmpty()) {
            ToadHeroStatusCard(
                paletteLabel = toadPaletteLabel(settingsViewModel),
                appearance = appearance,
                onAiStatus = { subPage = SettingsSubPage.AI_ENGINE },
            )
            Spacer(Modifier.height(16.dp))
        }

        // ---- r76 SECTION 1: Appearance & Timeline (two drill-downs) ----
        if (match(stringResource(R.string.settings_section_appearance)) ||
            match("theme") || match("timeline") || match("layout") || match("grid")
        ) {
            SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
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
                    // The tag the screenshot suite waits for: the hub's
                    // FIRST layout control (the Readme test's
                    // settings_layout_grid landmark --- carried by the
                    // drill-down row since the r76 IA moved the layout
                    // cards into the sub-page; same signal, still on the
                    // hub, still every flavor).
                    testTag = "settings_layout_grid",
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }

        // ---- r76 SECTION 2: On-Device AI & Smart Features ----
        if (BuildShape.withEngine && (match("ai") || match("people") ||
            match("magic") || match("search") || match("things"))
        ) {
            SettingsSectionHeader(stringResource(R.string.settings_ai_section))
            SettingsGroupCard {
                if (BuildShape.faceIndex) {
                    val peopleAlbumsEnabled by settingsViewModel.peopleAlbumsEnabled.collectAsState()
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_people_albums),
                        supporting = stringResource(R.string.settings_people_albums_body),
                        checked = peopleAlbumsEnabled,
                        onCheckedChange = { settingsViewModel.setPeopleAlbumsEnabled(it) },
                        iconVector = Icons.Filled.Memory,
                        testTag = "settings_toggle_people_albums",
                        modifier = Modifier.padding(top = 12.dp),
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
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    val thingsAlbumsEnabled by settingsViewModel.thingsAlbumsEnabled.collectAsState()
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_things_albums),
                        supporting = stringResource(R.string.settings_things_albums_body),
                        checked = thingsAlbumsEnabled,
                        onCheckedChange = { settingsViewModel.setThingsAlbumsEnabled(it) },
                        iconVector = Icons.Filled.Memory,
                        testTag = "settings_toggle_things_albums",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                SettingsNavRow(
                    title = stringResource(R.string.settings_ai_engine_entry),
                    supporting = aiEngineSupporting(),
                    onClick = { subPage = SettingsSubPage.AI_ENGINE },
                    iconVector = Icons.Filled.Memory,
                    testTag = "settings_ai_entry",
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }

        // ---- r76 SECTION 3: Privacy, Permissions & Vault ----
        if (match("privacy") || match("permissions") || match("picker") ||
            match("location") || match("gps") || match("vault") || match("exif")
        ) {
        // ---- Media access (issue #27) ----
        // The prototype keeps "Use system Photo Picker" and "Location &
        // EXIF GPS data" as INLINE switches (daily-gratification rows),
        // plus the vault drill-down. The search-filter wrapper hides the
        // whole section unless anything matches.
        if (match("privacy") || match("permissions") || match("picker") ||
            match("location") || match("gps") || match("vault") || match("exif")
        ) {
        // ---- Media access (issue #27) ----
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
                    // Graceful fallback (issue #27): no picker on pre-33, the
                    // current permission-based flow keeps carrying the app.
                    Text(
                        text = stringResource(R.string.settings_photo_picker_pre33_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 56.dp, bottom = 8.dp),
                    )
                }
            }
        }

        // ---- Location data (Phase 4: ACCESS_MEDIA_LOCATION consent) ----
        // Renders the shell-scoped state holder; on API 29+ the row asks
        // the OS (the grant is a DISPLAY gate for GPS coordinates only ---
        // ADR-0014: coordinates never leave the device). Below 29 the
        // permission does not exist and nothing was ever redactable, so
        // the copy says so (the same shape as the pre-R recycle bin).
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

        // ---- About (r64: the one honest build row) ----
        // What THIS APK carries, derived from the build shape itself ---
        // the honest replacement for the removed r55 "Installed
        // components" family probe, which read "Not installed" for
        // capabilities the unified build embeds all along. The row opens
        // the About sub-screen (identity, privacy posture, credits, the
        // vault diagnostics + ring log live THERE).
        SettingsSectionHeader(stringResource(R.string.settings_about_title))
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
            // map first (inline --- the composable call is legal inside),
            // joinToString's own transform is NOT inline, so it joins the
            // already-resolved strings.
            buildFeatureRes.map { res -> stringResource(res) }.joinToString(" \u00b7 ")
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
        // ---- The hidden-vault drill-down (r76: hub section 3's own row,
        //      beside the permission switches) ----
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
        } // the r76 search-filter wrapper (privacy + about sections)

        // ---- r76 SECTION 4: Storage & Data Management ----
        if (query.isEmpty()) {
            SettingsSectionHeader(stringResource(R.string.settings_storage_section))
            SettingsGroupCard {
                SettingsNavRow(
                    title = stringResource(R.string.settings_storage_entry),
                    supporting = stringResource(R.string.settings_storage_section_body),
                    onClick = { subPage = SettingsSubPage.STORAGE },
                    iconVector = Icons.Filled.Storage,
                    testTag = "settings_storage_entry",
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }
        }
        // ---- r76: the search empty state (nothing matched) ----
        if (query.isNotEmpty() &&
            !match(stringResource(R.string.settings_section_appearance)) &&
            !match("theme") && !match("timeline") && !match("layout") &&
            !match("grid") && !match("ai") && !match("people") &&
            !match("magic") && !match("search") && !match("things") &&
            !match("privacy") && !match("permissions") && !match("picker") &&
            !match("location") && !match("gps") && !match("vault") &&
            !match("exif") && !match("storage") && !match("about")
        ) {
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
        } // SettingsSubPage.HUB

        // ---- r76 SUB-PAGE 2: Timeline Layout & Density ----
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
            // r27: a FlowRow, not a Row --- the Moments round's WEEK option
            // makes four chips, which no longer fit one narrow-screen line
            // (the pre-r27 three did). FlowRow wraps instead of clipping.
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
            // r62's Stories toggle rides the same Appearance flow; r64
            // cards it under Appearance with its own badge glyph.
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
        } // SettingsSubPage.LAYOUT

        // ---- r76 SUB-PAGE 1: Theme & Colors ----
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
            // The mode body + the segmented control align under the row's
            // title (badge 40dp + 16dp gap), the modern settings rhythm.
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
            } // the mode column + the first theme card
            // r62's Material You toggle, moved beside its sibling: the
            // two color controls read as one Theme group.
            SettingsToggleRow(
                title = stringResource(R.string.settings_dynamic_color),
                supporting = stringResource(R.string.settings_dynamic_color_body),
                checked = useDynamicColor,
                onCheckedChange = { settingsViewModel.setUseDynamicColor(it) },
                iconVector = Icons.Filled.Settings,
                testTag = "settings_toggle_dynamic_color",
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            }
            // r76: the five selectable palettes (the prototype's Theme &
            // Colors cards). Checkmark = active.
            Spacer(Modifier.height(12.dp))
            SettingsSectionHeader("Palettes")
            SettingsGroupCard {
                AllToadPalettes.forEach { palette ->
                    val selected = palette.id == toadPaletteId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { settingsViewModel.setToadPaletteId(palette.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The dual-tone circular swatch: primary core +
                        // primaryContainer ring.
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(palette.primaryContainer)
                                .border(2.dp, palette.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(palette.primary),
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = toadPaletteLabelFor(palette.id),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        } // SettingsSubPage.THEME

        // ---- r76 SUB-PAGE 4: Hidden Vault & Security ----
        if (page == SettingsSubPage.VAULT) {
            SettingsSectionHeader(stringResource(R.string.settings_vault_title))
            // Biometric unlock (issue #57, ADR-0019): the REAL opt-in
            // binding state machine. Offered only where the hardware can
            // prompt; the switch flips the same enable/disable functions
            // the vault screen's own button drives.
            val biometrics by vaultViewModel.biometrics.collectAsState()
            if (biometrics.hardwareCanPrompt) {
                SettingsGroupCard {
                    SettingsToggleRow(
                        title = stringResource(R.string.vault_biometric_unlock),
                        supporting = stringResource(R.string.vault_biometric_body),
                        checked = biometrics.enrolled,
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
            // Auto-lock (ADR-0017): the vault session auto-locks when the
            // app goes to the background (ON_STOP) --- a FIXED posture,
            // not a preference. Rendered as the honest informational row
            // (the prototype's 3-pill selector describes a setting this
            // build does not have; a fake pill would lie).
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
            // The two actions: open the (encrypted) vault screen, or set
            // up / change the PIN there --- both route to the SAME vault
            // screen, whose own flow handles setup vs unlock.
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
        } // SettingsSubPage.VAULT

        // ---- r76 SUB-PAGE 5: Storage & Cache Manager ----
        // What toadGALLERY keeps on this device, one card: the two
        // ENTRIES (recycle bin, hidden vault) lead --- r64 folds their old
        // standalone sections in as the card's first rows, so the
        // duplicate vault/trash measurement rows merge away and a locked
        // vault still leaks no count or size (the nav row's supporting
        // line carries the same not-configured / locked / count-status
        // copy the standalone row always did). The measured rows keep
        // the r62 one-IO-walk-per-refresh behavior; the only destructive
        // action remains the disposable cache clear.
        // ---- r76 SUB-PAGE 5: Storage & Cache Manager ----
        if (page == SettingsSubPage.STORAGE) {
        // The dashboard ViewModel stays here (the r62 wiring; hoisted out
        // of the hub so only the sub-page pays for it).
        val storageViewModel: StorageInsightsViewModel = viewModel()
        val storageState by storageViewModel.state.collectAsState()
        SettingsSectionHeader(stringResource(R.string.settings_storage_section))
        Text(
            text = stringResource(R.string.settings_storage_section_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        SettingsGroupCard {
            // Recycle bin entry (issue #31). Count + size from the
            // entry-time snapshot; the Trash screen reloads on entry, so
            // this row catches up next time it shows. Below API 30 no
            // MediaStore trash protocol exists --- inert copy, the row
            // shape the section always had.
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
                    } else {
                        null
                    },
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
            // Hidden vault entry (issue #32, ADR-0017). The status line
            // comes from the SHELL-scoped vaultViewModel (the shared
            // session): "not set up" / "Locked" / count + size. The count
            // appears only while the vault is UNLOCKED --- a locked vault
            // leaks no size or count here, by design. Below Android 10
            // there is no consent protocol for the move/delete legs.
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
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_vault_pre_q_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            // The measured rows (r62). The vault and trash keys fold away
            // --- their entries above now carry the live status.
            storageState.entries.forEach { entry ->
                val labelRes = when (entry.key) {
                    STORAGE_KEY_CATALOGUE -> R.string.settings_storage_catalogue
                    STORAGE_KEY_ML_INDEX -> R.string.settings_storage_ml_index
                    STORAGE_KEY_MODELS -> R.string.settings_storage_models
                    STORAGE_KEY_CACHE -> R.string.settings_storage_cache
                    else -> null
                }
                if (labelRes != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = if (storageState.measuring) {
                                stringResource(R.string.settings_storage_measuring)
                            } else {
                                DataSizeFormats.formatBytes(entry.bytes)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = storageViewModel::clearCache,
                enabled = !storageState.measuring,
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 12.dp)
                    .testTag("settings_storage_clear_cache"),
            ) {
                Text(stringResource(R.string.settings_storage_action_clear_cache))
            }
        }
            // ---- Privacy center (r62) ----
            // The maintainer's feature pick: one audit-style page where every
            // consent and on-device posture stands. Read-only by design --- the
            // controls live on their own sections (media access, location data,
            // the vault, the AI consents); this page is the honest at-a-glance
            // answer to "what is on right now?".
            val privacyContext = LocalContext.current
            val mediaReadGranted = remember(privacyContext) {
                fun granted(perm: String) =
                    ContextCompat.checkSelfPermission(privacyContext, perm) ==
                        PackageManager.PERMISSION_GRANTED
                if (Build.VERSION.SDK_INT >= 33) {
                    granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                        granted(Manifest.permission.READ_MEDIA_VIDEO)
                } else {
                    granted(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
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
                        if (mediaReadGranted) {
                            R.string.settings_privacy_media_status_granted
                        } else {
                            R.string.settings_privacy_media_status_denied
                        }
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
            // ---- Developer diagnostics (r59, the debug-tools round) ----
            // The maintainer's "add option to add debug tool ... for fixing
            // bugs and flaws" directive. DEBUG-BUILDS ONLY (BuildConfig.DEBUG):
            // the signed release surface stays exactly as before --- the gate
            // is the standard Android debug flag, zero flavor plumbing, and
            // the section simply does not compose in a release build. Zero new
            // dependencies: the diagnostics ring (DiagLog.snapshot()), the
            // build-shape flags, the face-index directory state and a
            // clipboard export of the whole bundle --- the same data the F5
            // dogfood rounds wished for in the "indexing failed, no logs" reports.
            if (BuildConfig.DEBUG) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(R.string.settings_devtools_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(R.string.settings_devtools_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val context = LocalContext.current
                val diagText = remember {
                    buildString {
                        appendLine("toadGALLERY dev diagnostics")
                        appendLine("version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") debug")
                        appendLine("buildShape: withEngine=" + BuildShape.withEngine +
                            " faceIndex=" + BuildShape.faceIndex +
                            " semanticIndex=" + BuildShape.semanticIndex)
                        appendLine("applicationId: " + context.packageName)
                        appendLine()
                        appendLine("[diagnostics ring] (newest last)")
                        DiagLog.snapshot().takeLast(40).forEach { appendLine(it) }
                        appendLine()
                        val faceIndexDir = java.io.File(context.filesDir, "face-index")
                        appendLine("[face-index dir] " + faceIndexDir.absolutePath)
                        if (faceIndexDir.isDirectory) {
                            faceIndexDir.listFiles()?.sortedBy { it.name }?.forEach {
                                appendLine("  " + it.name + "  " + it.length() + " B")
                            }
                        } else {
                            appendLine("  (absent)")
                        }
                    }
                }
                Text(
                    text = diagText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("toadGALLERY diagnostics", diagText))
                        DiagLog.i("SettingsScreen", "dev diagnostics copied to clipboard")
                    }) {
                        Text(stringResource(R.string.settings_devtools_copy))
                    }
                    TextButton(onClick = { DiagLog.clear() }) {
                        Text(stringResource(R.string.settings_devtools_clear))
                    }
                }
            } // BuildConfig.DEBUG

        } // SettingsSubPage.STORAGE

        // ---- r76 SUB-PAGE 3: AI Models & Indexing Engine ----
        if (page == SettingsSubPage.AI_ENGINE && BuildShape.withEngine) {
    
            // ---- AI engine (r22 dogfood, ADR-0020) ----
            // The maintainer's window into the model-delivery path: FACE
            // provisioning over the REAL allowlisted-host downloader, deletion,
            // and one JNI inference self-test. Screen-scoped state holder (the
            // recycle-bin section's `viewModel()` pattern); every presence row
            // is the provisioner's own disk-backed derivation, never cached
            // wishful thinking. Actions run on Dispatchers.IO inside the VM;
            // the buttons sit disabled while one is in flight.
            // r46 build-shape gate: the WHOLE AI engine section --- header,
            // consent rows, provisioning cards, queue cards --- renders only
            // where the build carries the engine library (the basic APK hides
            // every engine surface, and the AiEngineViewModel below is never
            // constructed there).
            if (BuildShape.withEngine) {
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
    
                // r46 build-shape gate: FACE-index surfaces exist only on
                // the engine and face flavors.
                if (BuildShape.faceIndex) {
                    // ---- People albums consent (r25, Workstream 6; ADR-0010) ----
                    // The opt-in gate for the People UI: default OFF, one Switch, copy
                    // that states the on-device posture. The row rides the SHELL-scoped
                    // settingsViewModel (write-through persistence under the
                    // `peopleAlbumEnabled` key); the People/Albums surfaces read the
                    // preference at their own entry refresh, so a toggle here lands
                    // there on the next visit.
                    val peopleAlbumsEnabled by settingsViewModel.peopleAlbumsEnabled.collectAsState()
                    Row(
                        // r50 ux fix: whole-row toggle target (see the photo-picker
                        // row above for the full rationale).
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = peopleAlbumsEnabled,
                                role = Role.Switch,
                                onValueChange = { settingsViewModel.setPeopleAlbumsEnabled(it) },
                            )
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
                            onCheckedChange = null,
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
                } // BuildShape.faceIndex
    
                // r46 build-shape gate: SEMANTIC-index surfaces exist only on
                // the engine and semantic flavors.
                if (BuildShape.semanticIndex) {
                    // ---- Magic search consent (r26, Workstream 7; ADR-0010) ----
                    // The opt-in gate for the Search tab's Content mode: the SAME row
                    // shape as the People consent one row above (default OFF, one
                    // Switch, on-device copy, write-through under the
                    // `magicSearchEnabled` key); the Search tab's Content-mode
                    // ViewModel reads the preference at query time, so a toggle here
                    // lands there on the next query.
                    val magicSearchEnabled by settingsViewModel.magicSearchEnabled.collectAsState()
                    Row(
                        // r50 ux fix: whole-row toggle target (see the photo-picker
                        // row above for the full rationale).
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = magicSearchEnabled,
                                role = Role.Switch,
                                onValueChange = { settingsViewModel.setMagicSearchEnabled(it) },
                            )
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
                            onCheckedChange = null,
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
                } // BuildShape.semanticIndex
    
                // r46 build-shape gate: SEMANTIC-index surfaces exist only on
                // the engine and semantic flavors.
                if (BuildShape.semanticIndex) {
                    // ---- Things collections consent (r39, the ente-port exit round;
                    // ADR-0010) ----
                    // The opt-in gate for the Albums tab's Things section: the SAME
                    // row shape as the two rows above (default OFF, one Switch,
                    // on-device copy, write-through under the `thingsAlbumEnabled`
                    // key). Things rides the SAME semantic models and the SAME
                    // semantic index as Magic Search --- no new download card, no
                    // new queue; the body copy says so.
                    val thingsAlbumsEnabled by settingsViewModel.thingsAlbumsEnabled.collectAsState()
                    Row(
                        // r50 ux fix: whole-row toggle target (see the photo-picker
                        // row above for the full rationale).
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = thingsAlbumsEnabled,
                                role = Role.Switch,
                                onValueChange = { settingsViewModel.setThingsAlbumsEnabled(it) },
                            )
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_things_albums),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = thingsAlbumsEnabled,
                            onCheckedChange = null,
                            modifier = Modifier
                                .padding(start = 16.dp)
                                .testTag("settings_toggle_things_albums"),
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_things_albums_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } // BuildShape.semanticIndex
    
                // ---- Models (r62: the model-manager consolidation) ----
                // The two provisioning packs (FACE below, SEMANTIC further
                // down) now read as ONE managed group: a named header, the
                // pack lifecycle in one line (download verifies size +
                // SHA-256, delete removes the pack), and the r60 byte-level
                // progress bars live right on the cards. The consents above
                // gate the FEATURES; the cards below manage the WEIGHTS.
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
                // r50 ux fix: the provisioning busy state used to disable every
                // button while showing NO activity at all --- on a slow
                // connection the card looked frozen for minutes. One indeterminate
                // spinner + narration line for the actions without byte progress
                // (delete, self-test).
                // r60: while a model DOWNLOAD is in flight, the indeterminate
                // spinner becomes a DETERMINATE bar + live line (file, MiB in/of
                // the pinned total, file i of n; "verifying SHA-256" during the
                // hash pass) --- the r50 comment's "byte-level progress is
                // deliberately out of the provisioner contract" call is reversed
                // by the maintainer's 2026-11-08 device round: a ~207 MiB SEMANTIC
                // pack with zero feedback read as a frozen app.
                if (aiState.busy) {
                    val progress = aiState.downloadProgress
                    if (progress != null) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            LinearProgressIndicator(
                                progress = {
                                    if (progress.totalBytes <= 0L) {
                                        0f
                                    } else {
                                        (progress.bytesSoFar.toFloat() / progress.totalBytes.toFloat())
                                            .coerceIn(0f, 1f)
                                    }
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
                // r50 (the engine-flavor audit): the engine's own identity
                // line --- capability + model labels + the ORT bindings
                // version (MlEngine.engineInfo() previously had ZERO call
                // sites). Session-dependent: renders once a session has
                // opened (the self-test, a queue run, a People screen visit).
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
                // r46 build-shape gate: FACE-index surfaces exist only on
                // the engine and face flavors.
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
                    // r50 (the UI/UX polish round): destructive actions render
                    // as outlined error-toned buttons (the maintainer's device-
                    // screenshot audit: they previously carried the SAME filled
                    // style as the start actions) AND deleting the models
                    // re-downloads 100+ MB on the next install --- the action
                    // confirms too, like every other removal in the app (the
                    // repo's own convention).
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
                                ) {
                                    Text(stringResource(R.string.file_ops_action_delete))
                                }
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
                } // BuildShape.faceIndex
    
                // r46 build-shape gate: SEMANTIC-index surfaces exist only on
                // the engine and semantic flavors.
                if (BuildShape.semanticIndex) {
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
                    // r50 (the UI/UX polish round): the semantic pack gets the
                    // same outlined error-toned delete AND confirmation as the
                    // FACE pack above.
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
                                ) {
                                    Text(stringResource(R.string.file_ops_action_delete))
                                }
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
                        else -> Unit // the FACE notes render in their own block above
                    }
                } // BuildShape.semanticIndex
    
                // r46 build-shape gate: FACE-index surfaces exist only on
                // the engine and face flavors.
                if (BuildShape.faceIndex) {
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
                        // r50 ux fix: Start was tappable while a run was already
                        // Enqueued/Running (a second tap re-enqueued with no
                        // feedback) and while the FACE models were absent (a run
                        // doomed to fail at engine init, reported only via the
                        // Failed card). Gated: engine present + capability Ready
                        // + no live run.
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
                    // r50 (the UI/UX polish round): the reset is destructive ---
                    // outlined + error-toned (see the models-delete note above).
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
                                // Two honest terminal shapes: the draining run that
                                // clustered (clusters >= 0) and the no-op drain that
                                // did not (empty plan --- no wasted recluster pass).
                                // r50 (F-39): the unreadable (terminally skipped)
                                // count joins both drained lines.
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
                            // r50 ux fix: failure now READS as failure (error color,
                            // not the same muted tone as the success line) and the
                            // retry sits inline --- the old recovery was "scroll
                            // three controls up and press Start again", which the
                            // failure text never implied.
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
                } // BuildShape.faceIndex
    
                // r46 build-shape gate: SEMANTIC-index surfaces exist only on
                // the engine and semantic flavors.
                if (BuildShape.semanticIndex) {
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
                        // r50 ux fix: the face queue's Start gating (see above),
                        // semantic edition --- capability Ready + no live run.
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
                    // r50 (the UI/UX polish round): outlined + error-toned
                    // (the destructive reset styling, semantic sibling).
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
                                    clip.output.getInt(KEY_CLIP_OUT_SKIPPED_UNREADABLE, 0),
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
                        is ClipIndexWorkState.Failed -> {
                            // r50 ux fix: the face queue's Failed affordance (see
                            // above), semantic edition --- error color + inline retry.
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
                } // BuildShape.semanticIndex
            } // BuildShape.withEngine
        } // SettingsSubPage.AI_ENGINE

            } // the per-page scrollable column
        } // the AnimatedContent content lambda
        } // the pinned page column
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
        GroupBy.WEEK -> R.string.settings_group_by_week
        GroupBy.MONTH -> R.string.settings_group_by_month
    }

/**
 * r62: the Settings tab's segmented control scopes (the maintainer's IA
 * call: "General | AI | System --- flatter, fewer taps, longer pages").
 * One screen, three scopes; [labelRes] feeds the segment labels. The AI
 * scope renders nothing on non-engine builds (the r46 gate hides the
 * whole AI surface there), the same hide-not-crash posture the section
 * always had.
 */
/**
 * The r76 Settings destinations: the HUB plus the six animated drill-down
 * sub-pages (the prototype's slide-in pages). rememberSaveable-stable via
 * the enum NAME (the same wire discipline as every other preference).
 */
enum class SettingsSubPage {
    /** The five-section hub (the default). */
    HUB,

    /** Theme & Colors: mode segmented control, dynamic color, palettes. */
    THEME,

    /** Timeline Layout & Density: cards, stepper, group-by, stories. */
    LAYOUT,

    /** AI Models & Indexing Engine (engine builds only). */
    AI_ENGINE,

    /** Hidden Vault & Security. */
    VAULT,

    /** Storage & Cache Manager. */
    STORAGE,
}

/**
 * r62 polish: the evolved section header --- the pre-r62 shape (title,
 * primary color, heading anchor) shared by the NEW sections so they can
 * never drift from the house pattern. The existing sections keep their
 * inline headers untouched.
 */
@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(top = 24.dp)
            .semantics { heading() },
    )
}

/**
 * r62 polish: the evolved group card --- a 16dp ([ToadShapes.large])
 * rounded container over a softened `surfaceVariant`, wrapping the new
 * sections' rows so the Settings page reads as cards-with-headers instead
 * of bare rows on the background. Padding vertical is 4dp so a row's own
 * 12dp vertical padding makes the rhythm; horizontal 16dp matches the
 * screen margin.
 */
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

/**
 * r62: one Privacy center row --- label left, status right. [ok] picks
 * the status color: the primary (toad green) for the explicitly-safe
 * state, `onSurfaceVariant` otherwise (a neutral, non-alarming gray ---
 * the page narrates posture, it does not alarm).
 */
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
            color = if (ok) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * r64: the localized label of one theme mode (the UI-layer resource
 * mapping, the same shape as [GroupBy.labelRes]).
 */
private val ThemeMode.labelRes: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
    }

/**
 * r64: the Settings row icon badge --- a 40dp rounded-square container
 * ([ToadShapes.medium]) tinted with the theme's primary at 12% alpha,
 * carrying the row's glyph in the solid primary. The Android-Settings
 * card language, drawn on the house tokens; every glyph is either a
 * res/drawable vector (the five r64 badge icons, ic_trash, ic_vault) or
 * a core-set Material icon, so no new dependency rides along. The glyph
 * is decorative ([contentDescription] null) --- the row's title carries
 * the meaning.
 */
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

/** The [ImageVector] overload of [SettingsIconBadge] (core-set icons). */
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

/** The shared container of the two [SettingsIconBadge] overloads. */
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
    ) {
        icon()
    }
}

/**
 * r64: the Settings card's ENTRY row --- an optional leading icon badge
 * (exactly one of [iconRes]/[iconVector]; null renders no badge, for the
 * informational rows), the title, an optional supporting line, and a
 * trailing chevron announcing the navigation. The whole row is the tap
 * target (the Material settings idiom); [testTag] lands on the row.
 */
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
            SettingsIconBadge(
                iconRes = iconRes,
                modifier = Modifier.padding(end = 16.dp),
            )
        } else if (iconVector != null) {
            SettingsIconBadge(
                iconVector = iconVector,
                modifier = Modifier.padding(end = 16.dp),
            )
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
            contentDescription = null, // decorative; the row's semantics carry the action
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * r64: the Settings card's SWITCH row --- the r50 whole-row-toggle idiom
 * (the label merges into the Switch's semantics, so TalkBack announces a
 * named switch and the full width taps, not just the 48dp Switch itself)
 * on the new badge+title shape. The Switch renders with
 * `onCheckedChange = null` (the row owns the toggle); [testTag] lands on
 * the ROW for the future Settings UI tests.
 */
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
            SettingsIconBadge(
                iconRes = iconRes,
                modifier = Modifier.padding(end = 16.dp),
            )
        } else if (iconVector != null) {
            SettingsIconBadge(
                iconVector = iconVector,
                modifier = Modifier.padding(end = 16.dp),
            )
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

// ===========================================================================
// r76: the hub's helper composables (the hero card + the drill-down rows'
// live supporting lines)
// ===========================================================================

/**
 * The prototype's HERO STATUS CARD: a `135deg` linear gradient from
 * `primaryContainer` to `bgSurfaceHigh`, `borderStrong` stroke, the
 * "100% Offline • NPU Ready" pill, the "On-Device Privacy & AI" summary
 * showing the active palette + grid density, and the "AI Status" CTA.
 */
@Composable
private fun ToadHeroStatusCard(
    paletteLabel: String,
    appearance: com.toadgallery.gallery.ui.settings.Appearance,
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

/** The active palette's display label (the Theme sub-page's card titles). */
@Composable
private fun toadPaletteLabel(viewModel: SettingsViewModel): String {
    val id by viewModel.toadPaletteId.collectAsState()
    return toadPaletteLabelFor(id)
}

/** The palette display label for [id] (non-composable overload). */
fun toadPaletteLabelFor(id: ToadThemeId): String = when (id) {
    ToadThemeId.TOAD -> "Toad Green"
    ToadThemeId.AMBER -> "Dynamic Amber"
    ToadThemeId.OCEAN -> "Ocean Slate"
    ToadThemeId.OLED -> "OLED Pure Black"
    ToadThemeId.LIGHT -> "Botanical Light"
}

/** The Theme drill-down row's live supporting line. */
@Composable
private fun themeModeSupporting(
    mode: ThemeMode,
    viewModel: SettingsViewModel,
): String {
    val id by viewModel.toadPaletteId.collectAsState()
    val modeLabel = stringResource(mode.labelRes)
    return modeLabel + " • " + toadPaletteLabelFor(id)
}

/** The Timeline Layout drill-down row's live supporting line. */
@Composable
private fun layoutSupporting(appearance: Appearance): String {
    val layoutLabel = stringResource(
        if (appearance.layout == LayoutStyle.GRID) {
            R.string.settings_layout_grid
        } else {
            R.string.settings_layout_collage
        },
    )
    return layoutLabel + " • " + appearance.columns + " • " +
        stringResource(appearance.groupBy.labelRes)
}

/** The AI Engine drill-down row's live readiness line (engine builds). */
@Composable
private fun aiEngineSupporting(): String =
    stringResource(
        if (BuildShape.withEngine) {
            R.string.settings_ai_engine_ready
        } else {
            R.string.settings_ai_engine_absent
        },
    )
