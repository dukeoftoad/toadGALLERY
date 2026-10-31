package io.github.dukeoftoad.gallery.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.DataSizeFormats
import io.github.dukeoftoad.gallery.data.diag.DiagLog
import io.github.dukeoftoad.gallery.data.vault.VaultDiagnostics
import io.github.dukeoftoad.gallery.ui.vault.VaultViewModel

/** The public repository, opened via a plain ACTION_VIEW intent. */
private const val REPO_URL = "https://github.com/dukeoftoad/toadGALLERY"

/**
 * The About sub-screen (roadmap Phase 3: appearance/About slice): identity,
 * privacy posture, credits and the vault diagnostics, reached from the
 * Settings entry (`settings_about_entry`) and shaped like the other
 * sub-pages --- own top app bar with Up, no bottom bar (the shell hides it
 * for non-top-level routes).
 *
 * ## Identity
 * The launcher glyph is drawn as the launcher's own FOREGROUND vector over
 * its background color: `painterResource(R.mipmap.ic_launcher)` would
 * resolve to an `AdaptiveIconDrawable` on every supported API level
 * (minSdk 26), which painterResource cannot load --- the foreground +
 * background pair renders the identical art safely. The version line comes
 * from PackageManager (`"<versionName> (build <versionCode>)"`); the
 * API-28 `longVersionCode` switch carries the lexically visible SDK_INT
 * guard the pre-28 `versionCode` deprecation needs.
 *
 * ## Privacy / credits
 * ADR-0014 phrasing, stated plainly: offline by design, no INTERNET
 * permission, no accounts, vault bytes stay on device --- plus the honest
 * credits (Aves as the metadata reference, Ente as the appearance/on-device
 * AI inspiration, Compose/Coil/Media3 as the stack). "View source on
 * GitHub" fires a plain `ACTION_VIEW` intent wrapped in `runCatching`: the
 * system hands the URL to whatever browser the user has, and a browserless
 * device is silently ignored --- the app itself still holds no network
 * permission either way.
 *
 * ## Diagnostics
 * The vault probe ([VaultViewModel.probeDiagnostics], the W1 diagnostics
 * API) runs once per entry through `produceState` against the SAME
 * shell-scoped vault session the vault screen uses, rendering the store
 * facts as one-line facts: configured (PIN + index), biometric binding,
 * sealed blob count + size ([DataSizeFormats.formatBytes]), keystore key,
 * storage writability. Below it, the [DiagLog] ring (oldest-first, max 400
 * lines) renders in a height-capped monospaced scroll; "Copy diagnostics"
 * places app + device + vault + log lines on the clipboard and "Clear log"
 * empties the ring in place. The probe reads ONLY already-on-device state
 * --- it never unlocks, touches, or uploads anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    vaultViewModel: VaultViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // Identity: PackageManager can throw NameNotFoundException (paranoia ---
    // we ask for our own package); a null render beats a crash on this page.
    val packageInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionLine = packageInfo?.let { info ->
        // longVersionCode exists from API 28; the Int versionCode below P
        // is the deprecated pre-28 path, guarded by the SDK_INT check above.
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            val legacyCode = info.versionCode.toLong()
            legacyCode
        }
        stringResource(R.string.about_version_line, info.versionName ?: "", versionCode)
    }
    val appName = stringResource(R.string.app_launcher_name)
    val deviceLine = stringResource(R.string.about_device_line, Build.MODEL, Build.VERSION.SDK_INT)

    // The vault probe: once per entry (keyed on the shell's ViewModel); a
    // failure degrades to the same quiet `about_diagnostics_empty` line an
    // empty ring shows, never an error surface on this page.
    val diagnostics by produceState<VaultDiagnostics?>(initialValue = null, vaultViewModel) {
        value = runCatching { vaultViewModel.probeDiagnostics() }.getOrNull()
    }
    val vaultLines = diagnostics?.let { vaultDiagnosticsLines(it) } ?: emptyList()

    // The ring is plain process memory (no snapshot state), so it is held
    // in a remember + refreshed on clear --- entry shows entry-time lines.
    var logLines by remember { mutableStateOf(DiagLog.snapshot()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .testTag("about_screen"),
        ) {
            // ---- Identity ----
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null, // decorative; the app name is right below
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = appName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp),
            )
            if (versionLine != null) {
                Text(
                    text = versionLine,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                text = deviceLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton(
                onClick = {
                    // No INTERNET permission is needed or used: the intent
                    // hands the URL to the user's browser, and a device
                    // without one is silently ignored.
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                    }
                },
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("about_view_source"),
            ) {
                Text(stringResource(R.string.about_view_source))
            }

            // ---- Privacy (ADR-0014) ----
            Text(
                text = stringResource(R.string.about_privacy_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.about_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // ---- Credits ----
            Text(
                text = stringResource(R.string.about_credits_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = stringResource(R.string.about_credits_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // ---- Diagnostics ----
            Text(
                text = stringResource(R.string.about_diagnostics_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp),
            )
            if (diagnostics == null) {
                Text(
                    text = stringResource(R.string.about_diagnostics_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                vaultLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (logLines.isEmpty()) {
                Text(
                    text = stringResource(R.string.about_diagnostics_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("about_diagnostics_log"),
                ) {
                    logLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = {
                        // Everything below was composed above; the click
                        // only assembles it into clipboard plain text.
                        clipboard.setText(
                            AnnotatedString(
                                buildString {
                                    appendLine(appName)
                                    versionLine?.let { appendLine(it) }
                                    appendLine(deviceLine)
                                    if (vaultLines.isNotEmpty()) {
                                        appendLine()
                                        vaultLines.forEach { appendLine(it) }
                                    }
                                    if (logLines.isNotEmpty()) {
                                        appendLine()
                                        logLines.forEach { appendLine(it) }
                                    }
                                }.trimEnd()
                            )
                        )
                    },
                    modifier = Modifier.testTag("about_diagnostics_copy"),
                ) {
                    Text(stringResource(R.string.about_diagnostics_copy))
                }
                TextButton(
                    onClick = {
                        DiagLog.clear()
                        logLines = DiagLog.snapshot()
                    },
                    modifier = Modifier.testTag("about_diagnostics_clear"),
                ) {
                    Text(stringResource(R.string.about_diagnostics_clear))
                }
            }
        }
    }
}

/**
 * The vault probe as one-line user-facing facts, in report order:
 * configured (PIN + index present), biometric binding, sealed blob count +
 * size, keystore key, storage writability. Rendered by the diagnostics
 * block AND folded into the copy-diagnostics clipboard text, so the screen
 * and the report can never disagree.
 */
@Composable
private fun vaultDiagnosticsLines(diagnostics: VaultDiagnostics): List<String> {
    val store = diagnostics.store
    return listOf(
        stringResource(
            if (store.pinPresent && store.indexPresent) {
                R.string.about_vault_configured
            } else {
                R.string.about_vault_not_configured
            }
        ),
        stringResource(
            if (store.biometricBindingPresent) {
                R.string.about_vault_binding_on
            } else {
                R.string.about_vault_binding_off
            }
        ),
        pluralStringResource(
            R.plurals.about_vault_blobs,
            store.blobCount,
            store.blobCount,
            DataSizeFormats.formatBytes(store.blobBytes),
        ),
        stringResource(
            if (diagnostics.keystoreKeyPresent) {
                R.string.about_vault_keystore_key_present
            } else {
                R.string.about_vault_keystore_key_absent
            }
        ),
        stringResource(
            if (store.rootWritable) {
                R.string.about_vault_store_writable_yes
            } else {
                R.string.about_vault_store_writable_no
            }
        ),
    )
}
