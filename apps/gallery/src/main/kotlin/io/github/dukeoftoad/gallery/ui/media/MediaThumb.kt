package io.github.dukeoftoad.gallery.ui.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
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
 * Issue #66 adds a third indicator in the same scrim idiom: a small heart
 * chip (bottom-start) on favorited cells, read purely from
 * [MediaItem.isFavorite] --- additive and data-driven, so every grid that
 * already renders this cell (Photos timeline, album detail, favorites
 * pseudo-album, search results) gains the overlay with zero call-site
 * changes. Bottom-start deliberately avoids the other three corners:
 * bottom-end is the duration chip, top-end the GIF badge, and top-start is
 * where the selection overlay's check badge sits (the grids' chrome, not
 * this composable's).
 *
 * Accessibility: the thumbnail carries a semantic content description
 * ("Video"/"GIF"/"Photo" via `cd_media_thumb_*`) instead of staying
 * decorative --- grid cells are the primary browsing surface, and the media
 * type is exactly what the overlays communicate visually.
 *
 * Privacy (ADR-0006 / ADR-0014): the model is a local content URI.
 * `VideoFrameDecoder` (from `coil-video`) decodes video frames purely
 * on-device via `MediaMetadataRetriever`; because Coil 2.x has no decoder
 * auto-discovery, it is registered explicitly via the app's
 * `ImageLoaderFactory` (`ToadGalleryApplication`). The offline guarantee
 * rests on the app never holding the INTERNET permission --- enforced by
 * the build-time merged-manifest gate and `PrivacyEgressTest` --- so no
 * thumbnail can ever touch the network.
 *
 * Decode-failure fallback (issue #50): on some devices the platform
 * retriever cannot extract a frame from certain video codecs (on-device
 * logcat 2026-10-30 shows `getFrameAtTime: videoFrame is a NULL pointer`),
 * which makes the video request fail and the cell render as blank pixels
 * under its duration chip. Video cells therefore carry the
 * `ic_video_fallback` poster as the error painter --- the plate + play
 * triangle also doubles as the viewer's playback-error icon.
 *
 * The parallel paging slice (Phase 3) ships its own grid composable over
 * `LazyPagingItems`; it swaps its inline cell for this composable at
 * integration time, so both grids render identical cells.
 *
 * The appearance settings slice (roadmap Phase 3) adds [forceSquare] for the
 * GRID layout (Ente-style): when set, the cell ignores the item's aspect
 * ratio and renders a uniform `1f` square, the classic dense-gallery look.
 * The default `false` keeps every pre-slice call site (Photos timeline,
 * album detail, trash, favorites, search) rendering exactly as before ---
 * the COLLAGE default must stay byte-identical.
 */
@Composable
fun MediaThumb(item: MediaItem, modifier: Modifier = Modifier, forceSquare: Boolean = false) {
    Box(
        modifier = modifier.aspectRatio(
            if (forceSquare) 1f else item.displayAspectRatio
        )
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = when {
                item.isVideo -> stringResource(R.string.cd_media_thumb_video)
                item.isGif -> stringResource(R.string.cd_media_thumb_gif)
                else -> stringResource(R.string.cd_media_thumb_image)
            },
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
            error = if (item.isVideo) {
                painterResource(R.drawable.ic_video_fallback)
            } else {
                null
            },
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
        if (item.isFavorite) {
            FavoriteBadge(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp),
            )
        }
    }
}

/**
 * The favorites heart chip of [MediaThumb] (issue #66): the same scrim
 * idiom as [ThumbBadge] (60%-alpha black rounded chip) carrying the
 * CORE-set `Icons.Filled.Favorite` instead of text. Decorative by design
 * --- the viewer's favorite action is the narrated state surface; grid
 * cells stay quiet for TalkBack (the media-type content description on the
 * thumbnail itself remains the cell's announcement).
 */
@Composable
private fun FavoriteBadge(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null, // decorative; the viewer toggle narrates state
            tint = Color.White,
            modifier = Modifier.size(13.dp),
        )
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
