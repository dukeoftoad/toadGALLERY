package io.github.dukeoftoad.gallery.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dukeoftoad.gallery.R

/**
 * The shared unsupported-file screen state (screen-state system: issue #13).
 *
 * Layout for a media item the app cannot decode or display — the standard
 * state column (72dp decorative icon, `headlineSmall` title, centered body,
 * `state_shell_badge` footer). Shows `state_unsupported_title` ("This file
 * type isn't supported yet") plus [fileName] when known, middle-ellipsized so
 * long names stay readable. The icon is the Material `Icons.Filled.Clear`
 * vector (`material-icons-core`): "no preview available", deliberately less
 * alarmist than the error state's warning glyph — the file is intact, the app
 * just cannot render it (honest copy, no blame, no alarm).
 *
 * **No call sites yet.** This is defined now (issue #13: freeze the state
 * system before feature code lands) so the later slices that need it — the
 * viewer hitting a codec it cannot decode (roadmap Phase 3), the editor
 * round trip receiving `ERROR_UNSUPPORTED` (roadmap Phase 6,
 * docs/architecture/editor-contract.md) — adopt one component instead of
 * reinventing per-screen variants. Until then it is referenced only by
 * docs/architecture/screen-states.md (a public composable is not dead code).
 *
 * Test surface (issue #38.7): [tag] is applied to the root `Column` when
 * non-null; the default is no tag. Future call sites pass a stable per-screen
 * tag (e.g. `viewer_state_unsupported`).
 */
@Composable
fun UnsupportedFileState(
    fileName: String?,
    tag: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Clear,
            contentDescription = null, // decorative
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
        Text(
            text = stringResource(R.string.state_unsupported_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (fileName != null) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Middle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
        Text(
            text = stringResource(R.string.state_shell_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
