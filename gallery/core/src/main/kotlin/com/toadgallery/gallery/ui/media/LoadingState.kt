package com.toadgallery.gallery.ui.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.toadgallery.gallery.ui.theme.ToadSizing

/**
 * The shared loading screen state (screen-state system: issue #13).
 *
 * One centered `CircularProgressIndicator` in a fill-max-size `Box` — the same
 * geometry every screen previously inlined. Screens show this while their
 * ViewModel state is unresolved (permission check, first query, refresh with
 * nothing emitted yet).
 *
 * Test surface (issue #38.7): [tag] is applied to the root `Box` via
 * `Modifier.testTag` when non-null; the default is no tag. Call sites pass a
 * stable per-screen tag (e.g. `albums_state_loading`,
 * `album_detail_state_loading`) so instrumented tests can assert the state
 * decoupled from copy.
 *
 * No call-site padding parameter by design: the component fills whatever
 * constraints it receives; screens that must respect insets (e.g. the album
 * detail top bar) wrap it in an inset-padded `Box`.
 *
 * Token adoption (issue #11, docs/architecture/design-tokens.md): the
 * indicator diameter is pinned to [ToadSizing].stateProgress (48dp, aligned
 * to the touch-target grid) so it cannot drift with material3 default
 * changes; the stroke and track colors come from the theme. There are no
 * interactive affordances on this state, so the 48dp touch-target floor needs
 * no extra wiring here.
 */
@Composable
fun LoadingState(tag: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(ToadSizing.stateProgress))
    }
}
