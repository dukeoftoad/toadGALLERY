package com.toadgallery.gallery.ui.vault

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.toadgallery.gallery.R
import com.toadgallery.gallery.data.DataSizeFormats
import com.toadgallery.gallery.data.vault.VaultBiometricReadiness
import com.toadgallery.gallery.data.vault.VaultIndexEntry
import com.toadgallery.gallery.ui.StateScreen
import com.toadgallery.gallery.ui.media.LoadingState

/**
 * The hidden vault sub-screen (issue #32, ADR-0017; #57/#58 extend it).
 *
 * UX follows the sub-page contract (issue #29, like the Trash screen): no
 * bottom bar, own top app bar with Up. Lock phases:
 *  - [VaultUiState.NotConfigured]: the setup flow. The explainer states the
 *    ADR-0017 trade-off verbatim --- encrypted on this device only, no
 *    cloud, no recovery; a forgotten PIN or a lost/reset device makes the
 *    vault permanently unopenable.
 *  - [VaultUiState.Locked]: PIN entry. A wrong PIN gets generic copy (no
 *    hints). Unlock runs PBKDF2 off the main thread; the button carries a
 *    spinner while it does. Issue #57 (ADR-0019): when the device can
 *    prompt AND a binding is enrolled, an "Unlock with biometrics" button
 *    sits above the PIN field and the prompt fires once on entry; the PIN
 *    path stays untouched as the always-available fallback.
 *  - [VaultUiState.Unlocked]: the entry list. Issue #58: rows now carry
 *    DECRYPTED, size-bounded thumbnails (in memory only --- photos decode
 *    downsampled off the unseal stream, videos extract one frame via a
 *    temp file in the private cache that is deleted immediately; failures
 *    degrade honestly to the placeholder plate). A settings-style toggle
 *    offers the opt-in biometric unlock (#57: enrollment binds a fresh
 *    session secret under the phone's secure hardware --- the toggle is
 *    deliberately visible right after setup, i.e. the optional post-PIN
 *    step). Per-item Restore (immediate) and Delete (explicit confirm,
 *    permanent, no undo), plus a progress dialog while either runs.
 *
 * The shell scopes the shared [VaultViewModel] (the `viewerViewModel`
 * pattern), so the Settings entry and the Photos move-to-vault flow see the
 * same session state; auto-lock on `ON_STOP` lands the screen back in
 * Locked and clears every decrypted byte with it (thumbnails included).
 *
 * The BiometricPrompt itself lives HERE, not in the ViewModel: the androidx
 * prompt needs the hosting [FragmentActivity] (which `MainActivity` is
 * since #57), so the ViewModel emits the request via its biometric flow and
 * this screen drives `VaultBiometrics` (the thin device-only wrapper).
 *
 * Privacy (ADR-0006/ADR-0010/ADR-0014/ADR-0019): every byte is local ---
 * sealed app-private files, AndroidKeyStore keys, no INTERNET permission.
 * Biometric data never enters the app at all: the OS compares and the
 * Keystore authorizes; the app only ever sees whether the round succeeded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val biometrics by viewModel.biometrics.collectAsState()
    val thumbnails by viewModel.thumbnails.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // Only the Photos move-to-vault flow emits consent requests;
                // nothing on this screen can (restore inserts are app-owned).
                is VaultEvent.RequestConsent -> Unit
                is VaultEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    message = if (event.formatArgs.isEmpty()) {
                        context.getString(event.messageRes)
                    } else {
                        context.getString(event.messageRes, *event.formatArgs.toTypedArray())
                    },
                    duration = SnackbarDuration.Short,
                )
                is VaultEvent.ShowPlural -> snackbarHostState.showSnackbar(
                    message = context.resources.getQuantityString(
                        event.messageRes, event.count, event.count,
                    ),
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    // Issue #57: the BiometricPrompt requests arrive on their own flow (the
    // prompt needs this screen's FragmentActivity front-and-center; see
    // VaultBiometricRequest's KDoc). The ViewModel owns the session the
    // authorized decrypt opens; this screen only drives the device round.
    LaunchedEffect(Unit) {
        viewModel.biometricRequests.collect { request ->
            val activity = context as? FragmentActivity ?: return@collect
            when (request) {
                is VaultBiometricRequest.Unlock -> VaultBiometrics.unlock(
                    activity,
                    request.sealedSecret,
                ) { result -> viewModel.onBiometricUnlockResult(result) }

                VaultBiometricRequest.Enroll -> VaultBiometrics.enroll(activity) { result ->
                    viewModel.onBiometricEnrollResult(result)
                }
            }
        }
    }

    // Issue #57: one automatic biometric prompt on entering the Locked
    // screen with an enrolled binding --- predictable (exactly once per
    // visit, never again mid-screen), and the PIN field stays right there
    // for the always-available fallback.
    val biometricUnlockReady =
        (biometrics as? VaultBiometricReadiness.Supported)?.bindingEnrolled == true
    var biometricAutoPrompted by remember { mutableStateOf(false) }
    LaunchedEffect(state, biometricUnlockReady) {
        if (state == VaultUiState.Locked && biometricUnlockReady && !biometricAutoPrompted) {
            biometricAutoPrompted = true
            viewModel.requestBiometricUnlock()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.vault_title),
                        modifier = Modifier.semantics { heading() },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        when (val s = state) {
            VaultUiState.Unsupported -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("vault_state_unsupported"),
            ) {
                StateScreen(
                    iconRes = R.drawable.ic_vault,
                    titleRes = R.string.vault_unsupported_title,
                    bodyRes = R.string.vault_unsupported_body,
                )
            }
            VaultUiState.Resolving -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                LoadingState(tag = "vault_state_loading")
            }
            VaultUiState.NotConfigured -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                VaultSetupBody(
                    busy = busy?.kind == VaultBusyKind.VERIFYING,
                    onSetup = viewModel::setup,
                )
            }
            VaultUiState.Locked -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                VaultUnlockBody(
                    busy = busy?.kind == VaultBusyKind.VERIFYING,
                    showBiometricUnlock = biometricUnlockReady,
                    onBiometricUnlock = viewModel::requestBiometricUnlock,
                    onUnlock = viewModel::attemptUnlock,
                )
            }
            is VaultUiState.Unlocked -> {
                if (s.entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("vault_state_empty"),
                    ) {
                        StateScreen(
                            iconRes = R.drawable.ic_vault,
                            titleRes = R.string.vault_empty_title,
                            bodyRes = R.string.vault_empty_body,
                        )
                    }
                } else {
                    VaultContent(
                        entries = s.entries,
                        busy = busy,
                        thumbnails = thumbnails,
                        biometrics = biometrics,
                        onEnableBiometric = viewModel::enableBiometricUnlock,
                        onDisableBiometric = viewModel::disableBiometricUnlock,
                        onRestore = viewModel::restore,
                        onDelete = viewModel::deleteEntry,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

/**
 * The setup flow (first run): the ADR-0017 trade-off in plain words, then
 * PIN + confirm fields. The Create button is enabled only when the PIN is
 * at least [VaultViewModel.MIN_PIN_LENGTH] digits and both fields match; the
 * submitted [CharArray] is handed to the ViewModel, which wipes it after
 * use (the `VaultCrypto` contract).
 */
@Composable
private fun VaultSetupBody(
    busy: Boolean,
    onSetup: (CharArray) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val pinChars = pin.toCharArray()
    val confirmChars = confirm.toCharArray()
    val longEnough = pinChars.size >= VaultViewModel.MIN_PIN_LENGTH
    val matches = pinChars.contentEquals(confirmChars)
    val problem = when {
        pin.isEmpty() && confirm.isEmpty() -> null
        !longEnough -> stringResource(R.string.vault_setup_short)
        !matches -> stringResource(R.string.vault_setup_mismatch)
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // r47 ux fix: pay the IME inset --- the app is edge-to-edge, so
            // without this the keyboard covers the PIN fields (and the
            // Create button) instead of the column shrinking above it.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.vault_setup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        VaultPinField(
            labelRes = R.string.vault_setup_pin_label,
            value = pin,
            onValueChange = { pin = it },
            modifier = Modifier.testTag("vault_setup_pin"),
        )
        VaultPinField(
            labelRes = R.string.vault_setup_confirm_label,
            value = confirm,
            onValueChange = { confirm = it },
            modifier = Modifier.testTag("vault_setup_confirm"),
        )
        if (problem != null) {
            Text(
                text = problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        VaultActionButton(
            labelRes = R.string.vault_setup_action,
            enabled = longEnough && matches && !busy,
            busy = busy,
            onClick = { onSetup(pin.toCharArray()) },
            modifier = Modifier
                .padding(top = 24.dp)
                .testTag("vault_setup_action"),
        )
    }
}

/**
 * The locked flow: optional biometric unlock (issue #57, ADR-0019) over PIN
 * entry, with the auto-lock behavior stated. The biometric button appears
 * only when the device can prompt AND a binding is enrolled; the PIN field
 * is ALWAYS present --- the fallback is a first-class path, not a recovery
 * mode.
 */
@Composable
private fun VaultUnlockBody(
    busy: Boolean,
    showBiometricUnlock: Boolean,
    onBiometricUnlock: () -> Unit,
    onUnlock: (CharArray) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // r47 ux fix: pay the IME inset (the setup column's twin) so the
            // keyboard never covers the unlock PIN field or its button.
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.vault_locked_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showBiometricUnlock) {
            Spacer(Modifier.height(24.dp))
            OutlinedButton(
                onClick = onBiometricUnlock,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vault_unlock_biometric"),
            ) {
                Text(stringResource(R.string.vault_biometric_unlock_action))
            }
        }
        Spacer(Modifier.height(24.dp))
        VaultPinField(
            labelRes = R.string.vault_unlock_pin_label,
            value = pin,
            onValueChange = { pin = it },
            modifier = Modifier.testTag("vault_unlock_pin"),
        )
        VaultActionButton(
            labelRes = R.string.vault_unlock_action,
            enabled = pin.length >= VaultViewModel.MIN_PIN_LENGTH && !busy,
            busy = busy,
            onClick = { onUnlock(pin.toCharArray()) },
            modifier = Modifier
                .padding(top = 24.dp)
                .testTag("vault_unlock_action"),
        )
    }
}

/**
 * The unlocked vault body: a "n items · size" header, the opt-in biometric
 * toggle (issue #57), and the entry list. Entries render newest-first
 * (gallery convention; the index is insertion-ordered) with issue #58
 * thumbnails: a decrypted, size-bounded in-memory preview when one decoded
 * successfully, the type plate otherwise (honest degradation --- a blob
 * that fails to decode never takes the listing down).
 */
@Composable
private fun VaultContent(
    entries: List<VaultIndexEntry>,
    busy: VaultBusy?,
    thumbnails: Map<Long, ImageBitmap>,
    biometrics: VaultBiometricReadiness,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit,
    onRestore: (VaultIndexEntry) -> Unit,
    onDelete: (VaultIndexEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newestFirst = remember(entries) { entries.asReversed() }
    var deleteTarget by remember { mutableStateOf<VaultIndexEntry?>(null) }
    val biometricSupported = biometrics as? VaultBiometricReadiness.Supported

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = pluralStringResource(R.plurals.vault_header_items, entries.size, entries.size) +
                "  ·  " + DataSizeFormats.formatBytes(entries.sumOf { it.sizeBytes }),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (biometricSupported != null) {
            VaultBiometricToggle(
                enrolled = biometricSupported.bindingEnrolled,
                onCheckedChange = { enable ->
                    if (enable) onEnableBiometric() else onDisableBiometric()
                },
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("vault_screen_list"),
        ) {
            items(
                items = newestFirst,
                key = { entry -> entry.id },
            ) { entry ->
                VaultEntryRow(
                    entry = entry,
                    thumbnail = thumbnails[entry.id],
                    onRestore = { onRestore(entry) },
                    onDelete = { deleteTarget = entry },
                )
            }
        }
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(entry)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.testTag("vault_delete_confirm_yes"),
                ) {
                    Text(stringResource(R.string.vault_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.file_ops_action_cancel))
                }
            },
            title = { Text(stringResource(R.string.vault_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.vault_delete_confirm_body, entry.displayName))
            },
            modifier = Modifier.testTag("vault_dialog_delete"),
        )
    }

    // Restore/delete stream bytes; the non-dismissable dialog keeps the
    // interaction honest until the operation settles (no cancel in v1: both
    // ops are single-item and crash-safe by ordering).
    val opBusy = busy?.takeIf { it.kind == VaultBusyKind.RESTORING || it.kind == VaultBusyKind.DELETING }
    if (opBusy != null) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = {
                Text(
                    text = stringResource(
                        if (opBusy.kind == VaultBusyKind.RESTORING) {
                            R.string.vault_progress_restore
                        } else {
                            R.string.vault_progress_delete
                        },
                    ),
                    // r40 a11y: the vault batch progress updates politely
                    // announce (the same convention as the file-ops bars).
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            modifier = Modifier.testTag("vault_progress"),
        )
    }
}

/**
 * The settings-style biometric unlock toggle (issue #57, ADR-0019): opt-in
 * on (runs the enrollment prompt: a fresh session secret sealed under the
 * phone's secure hardware), off removes the binding. The copy states the
 * ADR-0019 facts plainly: biometric data never leaves the device, and the
 * PIN keeps working either way.
 */
@Composable
private fun VaultBiometricToggle(
    enrolled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("vault_biometric_toggle_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.vault_biometric_section_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.vault_biometric_section_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enrolled,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("vault_biometric_toggle"),
        )
    }
}

/**
 * One vault entry row: the #58 thumbnail slot (decrypted preview when
 * available, the type plate otherwise), display name (single line),
 * plaintext size, then the two per-item actions --- Restore (immediate; the
 * safe direction) and Delete (error-colored; confirmed by the dialog).
 */
@Composable
private fun VaultEntryRow(
    entry: VaultIndexEntry,
    thumbnail: ImageBitmap?,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("vault_entry_row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (thumbnail != null) {
            // Issue #58: decrypted, size-bounded, in-memory-only preview;
            // cleared wholesale with the session on auto-lock.
            Image(
                bitmap = thumbnail,
                contentDescription = stringResource(
                    if (entry.isVideo) R.string.cd_media_thumb_video else R.string.cd_media_thumb_image,
                ),
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
            )
        } else {
            Icon(
                painter = painterResource(
                    if (entry.isVideo) R.drawable.ic_video_fallback else R.drawable.ic_nav_photos,
                ),
                contentDescription = stringResource(
                    if (entry.isVideo) R.string.cd_media_thumb_video else R.string.cd_media_thumb_image,
                ),
                // Unspecified keeps the video plate's own two-tone look (the
                // viewer reuses the same drawable this way); photos tint normally.
                tint = if (entry.isVideo) Color.Unspecified else LocalContentColor.current,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = DataSizeFormats.formatBytes(entry.sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onRestore,
            modifier = Modifier.testTag("vault_action_restore"),
        ) {
            Text(stringResource(R.string.vault_action_restore))
        }
        TextButton(
            onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            modifier = Modifier.testTag("vault_action_delete"),
        ) {
            Text(stringResource(R.string.vault_action_delete))
        }
    }
}

/**
 * The shared PIN field: digit keyboard, masked input, single line. Input is
 * filtered to digits --- the PIN is a numeric code by design, and the
 * NumberPassword keyboard matches that expectation.
 */
@Composable
private fun VaultPinField(
    labelRes: Int,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() }) },
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = modifier.fillMaxWidth(),
    )
}

/** The shared setup/unlock button with the inline PBKDF2 spinner. */
@Composable
private fun VaultActionButton(
    labelRes: Int,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(labelRes))
    }
}
