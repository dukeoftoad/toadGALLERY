package io.github.dukeoftoad.gallery.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.dukeoftoad.gallery.R
import io.github.dukeoftoad.gallery.data.MediaItem
import io.github.dukeoftoad.gallery.data.displayAspectRatio

/**
 * One aspect-ratio-aware cell of the staggered media grid (issues #33/#34).
 *
 * Renders the item's thumbnail via Coil `AsyncImage`, center-cropped inside a
 * `Box` sized by [MediaItem.displayAspectRatio] --- the MediaStore pixel
 * width/height clamped to `[0.5f, 2.0f]` (and `1f` for unknown dimensions) by
 * `io.github.dukeoftoad.gallery.data.displayAspectRatioOf`. The clamping lives
 * in that single pure helper so the UI never re-clamps and the bounds stay
 * unit-testable; here the value goes straight into `Modifier.aspectRatio`.
 *
 * Two subtle, Mi-Gallery-style indicators (issue #33):
 *  - a video duration chip (bottom-end) when `isVideo && durationMs > 0`,
 *    text from [formatDurationMs];
 *  - a "GIF" badge (top-end) for animated images, text from
 *    `R.string.media_badge_gif`.
 * Both share the same scrim style: white `labelSmall` text over
 * `Color.Black.copy(alpha = 0.6f)`, `RoundedCornerShape(6.dp)`, tiny padding
 * and a 4.dp margin from the cell edge.
 *
 * Accessibility: the thumbnail carries a semantic content description
 * ("Video"/"GIF"/"Photo" via `cd_media_thumb_*`) instead of staying
 * decorative --- grid cells are the primary browsing surface, and the media
 * type is exactly what the overlays communicate visually.
 *
 * Privacy (ADR-0006 / ADR-0014): the model is a local content URI. Coil is
 * wired without its network artifact, so nothing can be fetched remotely, and
 * `coil-video` (`VideoFrameDecoder`) decodes video frames purely on-device.
 *
 * The parallel paging slice (Phase 3) ships its own grid composable over
 * `LazyPagingItems`; it swaps its inline cell for this composable at
 * integration time, so both grids render identical cells.
 */
@Composable
fun MediaThumb(item: MediaItem, modifier: Modifier = Modifier) {
    Box(modifier = modifier.aspectRatio(item.displayAspectRatio)) {
        AsyncImage(
            model = item.uri,
            contentDescription = when {
                item.isVideo -> stringResource(R.string.cd_media_thumb_video)
                item.isGif -> stringResource(R.string.cd_media_thumb_gif)
                else -> stringResource(R.string.cd_media_thumb_image)
            },
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        if (item.isVideo && item.durationMs > 0) {
            ThumbBadge(
                text = formatDurationMs(item.durationMs),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
            )
        }
        if (item.isGif) {
            ThumbBadge(
                text = stringResource(R.string.media_badge_gif),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp),
            )
        }
    }
}

/**
 * The shared scrim chip of the [MediaThumb] overlays (issue #33): white
 * `labelSmall` text over a 60%-alpha black rounded rectangle. Plain `Box` +
 * `background` on purpose --- no elevation or tonal surface behavior, just
 * a legible scrim over arbitrary thumbnail pixels.
 */
@Composable
private fun ThumbBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
