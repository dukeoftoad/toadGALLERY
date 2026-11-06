package io.github.dukeoftoad.gallery.ui.media

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.ui.theme.ToadSizing
import io.github.dukeoftoad.gallery.ui.theme.ToadSpacing

/**
 * The shared error screen state with a retry action (screen-state system:
 * issue #13).
 *
 * Renders the standard state layout — 72dp decorative icon, `headlineSmall`
 * title, centered `bodyMedium` body, `TextButton` retry — mirroring
 * `io.github.dukeoftoad.gallery.ui.StateScreen` token for
 * token so every state in the app looks native to it.
 *
 * **Frozen design choice — self-contained instead of delegating to
 * `StateScreen`:** `StateScreen`'s `iconRes` parameter is required (it has no
 * default) and the app's drawable set contains no generic error glyph, only
 * per-tab nav icons; this component's frozen signature carries no icon
 * resource. Calling `StateScreen` would force hardcoding e.g.
 * `ic_nav_photos` into a *generic* error state — mislabeling every non-Photos
 * call site — and adding an `iconRes` parameter would break the frozen
 * signature. Instead the component renders the Material `Icons.Filled.Warning`
 * vector (`material-icons-core`, already on the classpath). The layout is a
 * byte-for-byte mirror of `StateScreen`, so a future internal swap to
 * `StateScreen` (if it grows an optional icon) stays call-site compatible.
 * Documented in docs/architecture/screen-states.md §1.
 *
 * Call sites supply contextual copy (e.g. `state_photos_error_title` /
 * `state_photos_error_body` / `state_action_retry`); [onRetry] wires the
 * screen's own recovery action (e.g. `lazyItems.retry()`).
 *
 * Test surface (issue #38.7): [tag] is applied to the root `Column` when
 * non-null; the default is no tag. Call sites pass a stable per-screen tag
 * (e.g. `album_detail_state_error`).
 *
 * Migration note: PhotosScreen's error branch keeps its inline `StateScreen`
 * call in this slice (frozen for concurrent work); it adopts this component
 * in a later slice (docs/architecture/screen-states.md §5).
 *
 * Token adoption (issue #11, docs/architecture/design-tokens.md): every gap
 * comes from [ToadSpacing], the decorative glyph from [ToadSizing].stateIcon,
 * and the type roles from the theme-provided ToadTypography — the frozen
 * screen-state layout (72dp icon, `headlineSmall`/`bodyMedium` copy) is
 * reproduced exactly, so the token migration shifts nothing visually. The
 * retry [TextButton] clears the 48dp touch-target floor without visual
 * growth: the theme provides `LocalMinimumInteractiveComponentSize` from
 * `ToadTouchTarget.minimum`.
 */
@Composable
fun ErrorState(
    @StringRes titleRes: Int,
    @StringRes bodyRes: Int,
    @StringRes actionRes: Int,
    onRetry: () -> Unit,
    tag: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ToadSpacing.xxxl)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null, // decorative
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ToadSizing.stateIcon),
        )
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = ToadSpacing.xl),
        )
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ToadSpacing.md),
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = ToadSpacing.xxl),
        ) {
            Text(stringResource(actionRes))
        }
        // r50 ux fix: the internal "Phase 3" milestone badge is gone (see
        // the shell's StateScreen for the rationale); the state card is
        // user-facing, not a roadmap marker. The committed Roborazzi
        // baselines re-record with this round (the verify gate re-locks).
    }
}
