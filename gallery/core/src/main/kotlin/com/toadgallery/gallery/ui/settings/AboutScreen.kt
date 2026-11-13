package com.toadgallery.gallery.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.toadgallery.gallery.R

/** The public repository, opened via a plain ACTION_VIEW intent. */
private const val REPO_URL = "https://github.com/dukeoftoad/toadGALLERY"

/**
 * The About sub-screen (roadmap Phase 3: appearance/About slice): identity,
 * privacy posture and credits, reached from the Settings entry
 * (`settings_about_entry`) and shaped like the other sub-pages --- own top
 * app bar with Up, no bottom bar (the shell hides it for non-top-level
 * routes).
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
 * r77 beta: the r-W1 vault-diagnostics probe and the DiagLog-ring dump
 * are GONE --- this page is a user surface now (identity, privacy posture,
 * credits, repository link), the prototype's own About shape. The vault
 * facts it printed stay reachable through the vault screen's own states;
 * nothing user-facing was lost.
 *
 * ## Privacy / credits
 * ADR-0014 phrasing, stated plainly: offline by design, no accounts,
 * vault bytes stay on device --- plus the honest credits (Aves as the
 * metadata reference, Ente as the vendored on-device AI engine,
 * Compose/Coil/Media3 as the stack). r50: the privacy line is FLAVOR
 * HONEST now --- the basic build's overlay (src/basic/res) states the
 * stronger no-network-permission-at-all truth, while the engine-family
 * builds keep the main copy (their one network use is the ADR-0020
 * model download, permission present, host pinned). "View source on
 * GitHub" fires a plain `ACTION_VIEW` intent wrapped in `runCatching`:
 * the system hands the URL to whatever browser the user has, and a
 * browserless device is silently ignored --- the app itself never opens
 * a socket for it either way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.about_title),
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .testTag("about_screen"),
        ) {
            // ---- Identity (r77: the prototype's centered card --- the
            //      launcher tile, the name, the version line in primary,
            //      the device line under it, all centered on one column). ----
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(24.dp))
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
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            )
            if (versionLine != null) {
                Text(
                    text = versionLine,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                )
            }
            Text(
                text = deviceLine,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
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
        }
    }
}
