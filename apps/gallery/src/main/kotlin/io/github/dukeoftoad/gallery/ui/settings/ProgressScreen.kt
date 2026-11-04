package io.github.dukeoftoad.gallery.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.ProgressPhaseSnapshot
import io.github.dukeoftoad.gallery.data.ProgressScopeSnapshot
import io.github.dukeoftoad.gallery.data.ProgressSnapshot
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The in-app progress screen (F-1, follow-up 2026-11-04): the repo's two
 * INDEPENDENT progress scopes --- the overall project and the Ente port
 * workstream --- as build-time snapshot bars + phase lists, reached from
 * the Settings entry (`settings_progress_entry`) and shaped like the
 * other sub-pages (own top app bar with Up, no bottom bar).
 *
 * The data is the `progress.json` mirrored into the APK assets at build
 * time ([ProgressSnapshot.ASSET_PATH]) --- a deliberately static mirror:
 * the app never fetches it (zero network), the screen says so, and each
 * bar displays ITS OWN scope's stored percent (no cross-scope aggregate,
 * exactly the independence the file guarantees).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshot by produceState<ProgressSnapshot?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            try {
                context.assets.open(ProgressSnapshot.ASSET_PATH).use { input ->
                    ProgressSnapshot.parse(input.readBytes().decodeToString())
                }
            } catch (_: IOException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_progress_title)) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            // The honesty line first: this is a build-time mirror, not a
            // live view (the app never fetches progress).
            Text(
                text = stringResource(R.string.settings_progress_snapshot_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val snap = snapshot
            if (snap != null) {
                snap.updatedAt?.let { updated ->
                    Text(
                        text = stringResource(R.string.settings_progress_updated, updated),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                ScopeSection(scope = snap.project, sectionTag = "progress_project")
                ScopeSection(scope = snap.entePort, sectionTag = "progress_ente_port")
            } else {
                Text(
                    text = stringResource(R.string.settings_progress_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/** One scope: the label, the bar + stored percent, the done-count, the phase list, the source line. */
@Composable
private fun ScopeSection(scope: ProgressScopeSnapshot, sectionTag: String) {
    val doneCount = scope.phases.count { it.status == "done" }
    Spacer(Modifier.height(24.dp))
    Text(
        text = scope.label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag(sectionTag),
    )
    val percent = scope.percent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The bar announces itself as "<label>, <percent> percent" --- the
        // a11y convention over every progress surface.
        val barDescription = stringResource(
            R.string.settings_progress_bar_description,
            scope.label,
            percent,
        )
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = barDescription },
        )
        Text(
            text = stringResource(R.string.settings_progress_percent, percent),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Text(
        text = stringResource(R.string.settings_progress_phase_count, doneCount, scope.phases.size),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    scope.phases.forEach { phase -> PhaseRow(phase) }
    scope.sourceOfTruth?.let { source ->
        Text(
            text = stringResource(R.string.settings_progress_source, source),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** One phase: the status marker, the label (+ evidence line), the status word. */
@Composable
private fun PhaseRow(phase: ProgressPhaseSnapshot) {
    val statusLabel = when (phase.status) {
        "done" -> stringResource(R.string.settings_progress_status_done)
        "in-progress" -> stringResource(R.string.settings_progress_status_in_progress)
        "pending" -> stringResource(R.string.settings_progress_status_pending)
        else -> phase.status // an unknown status is the file's own word; shown verbatim
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMarker(phase.status)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        ) {
            Text(
                text = phase.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            phase.evidence?.let { evidence ->
                Text(
                    text = evidence,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** The status glyph: check (done), filled dot (in progress), hollow ring (pending). Decorative --- the row's trailing word carries the a11y. */
@Composable
private fun StatusMarker(status: String) {
    when (status) {
        "done" -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        "in-progress" -> Box(
            modifier = Modifier.size(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        else -> Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
    }
}
